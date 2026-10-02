package com.mrzero.filemanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional Magisk/su shell.
 *
 * Android scopes every app to its own sandbox plus the shared volume, so
 * /storage/emulated, /storage, /sdcard and finally / itself are unreadable even
 * with "All files access". A rooted device is the only way to keep walking up
 * past /storage/emulated/0, which is what the ".." row needs at the top.
 *
 * Nothing here is required for normal use: every method degrades to "no root"
 * and the app then simply reports that a folder cannot be read.
 */
public final class Root {

    private static final String TAG = "AppleFilesRoot";
    private static final String PREFS = "root_access";
    private static final String KEY_STATE = "state";
    /** Unknown / granted / denied, so the Magisk dialog is shown at most once. */
    private static String state;
    private static String suPath;

    private Root() { }

    private static Context app;

    /** Set once from MainActivity so non-UI helpers can reach the grant state. */
    public static void attach(Context ctx) { app = ctx.getApplicationContext(); }

    private static Context ctx() {
        if (app == null) throw new IllegalStateException("Root.attach() not called");
        return app;
    }

    /**
     * Where Magisk and the other root solutions put su. The list is probed by
     * running it: an app cannot stat() these paths (canExecute() is false even
     * for a perfectly working su), so exec is the only reliable test.
     */
    private static final String[] CANDIDATES = {
            "/system_ext/bin/su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/su/bin/su", "/debug_ramdisk/su", "/vendor/bin/su", "su",
            "/system_ext/bin/magisk", "/sbin/magisk",
    };

    public static final String UNKNOWN = "unknown";
    public static final String GRANTED = "granted";
    public static final String DENIED = "denied";

    /** Cached state: granted, denied or not probed yet. */
    public static synchronized String state(Context unused) {
        if (state == null) {
            state = prefs(ctx()).getString(KEY_STATE, UNKNOWN);
        }
        return state;
    }

    public static boolean isGranted(Context unused) {
        return GRANTED.equals(state(null));
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void remember(String value) {
        state = value;
        prefs(ctx()).edit().putString(KEY_STATE, value).apply();
    }

    /**
     * Asks Magisk for root. The first call shows Magisk's own dialog; the answer
     * is cached so the prompt is not repeated on every launch.
     *
     * @return true when the shell really runs as uid 0.
     */
    public static boolean request(Context ctx) {
        String found = findSu();
        if (found == null) {
            Log.i(TAG, "request: no su binary");
            remember(DENIED);
            return false;
        }
        Result r = exec(found + " -c id");
        boolean ok = r.ok && r.out.contains("uid=0");
        remember(ok ? GRANTED : DENIED);
        Log.i(TAG, "root request -> " + (ok ? "granted" : "denied") + " (" + r.err.trim() + ")");
        return ok;
    }

    /** Probes without prompting: only confirms an existing grant. */
    public static boolean revalidate(Context ctx) {
        if (!GRANTED.equals(state(ctx))) return false;
        String found = findSu();
        if (found == null) {
            Log.i(TAG, "request: no su binary");
            remember(DENIED);
            return false;
        }
        Result r = exec(found + " -c id");
        if (!r.ok || !r.out.contains("uid=0")) {
            remember(DENIED);
            return false;
        }
        return true;
    }

    public static void forget() { state = null; suPath = null; }

    private static String findSu() {
        if (suPath != null) return suPath;
        for (String c : CANDIDATES) {
            Result r = exec(c + " -c id");
            if (r.ok) {
                suPath = c;
                Log.i(TAG, "using su at " + c);
                return c;
            }
        }
        Log.i(TAG, "no working su found");
        return null;
    }

    // ------------------------------------------------------------------
    // Shell
    // ------------------------------------------------------------------
    private static Result exec(String command) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", command});
            String out = read(p.getInputStream());
            String err = read(p.getErrorStream());
            int code = p.waitFor();
            return new Result(code == 0, out, err);
        } catch (Throwable t) {
            if (p != null) p.destroy();
            return new Result(false, "", String.valueOf(t.getMessage()));
        }
    }

    private static String read(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    /** Runs a shell snippet as root. Callers must already be off the UI thread. */
    public static Result sh(String script) {
        String found = suPath != null ? suPath : findSu();
        if (found == null) return new Result(false, "", "no su");
        return exec(found + " -c " + quote(script));
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------
    // Directory listing
    // ------------------------------------------------------------------
    /** One row as reported by the shell. */
    public static final class Item {
        public final String name;
        public final boolean dir;
        public final long size;
        public final long modified;

        Item(String name, boolean dir, long size, long modified) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.modified = modified;
        }
    }

    /**
     * Lists a directory through the root shell. One su invocation for the whole
     * folder: names come from ls, metadata from a single stat per entry.
     *
     * @return the entries, or null when there is no root or the read failed.
     */
    public static List<Item> list(File dir) {
        if (dir == null) return null;
        String d = dir.getAbsolutePath();
        StringBuilder script = new StringBuilder();
        script.append("d=").append(quote(d)).append("; ");
        script.append("ls -1A \"$d\" 2>/dev/null | while IFS= read -r n; do ");
        script.append("[ -z \"$n\" ] && continue; ");
        script.append("p=\"$d/$n\"; ");
        script.append("st=$(stat -c '%s %Y' \"$p\" 2>/dev/null); ");
        script.append("sz=${st%% *}; mt=${st##* }; ");
        script.append("[ -z \"$sz\" ] && sz=0; [ -z \"$mt\" ] && mt=0; ");
        script.append("if [ -d \"$p\" ]; then t=d; else t=f; fi; ");
        script.append("printf '%s\\t%s\\t%s\\t%s\\n' \"$t\" \"$sz\" \"$mt\" \"$n\"; ");
        script.append("done");

        Result r = sh(script.toString());
        if (!r.ok) return null;
        List<Item> out = new ArrayList<>();
        for (String line : r.out.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", 4);
            if (f.length < 4) continue;
            long size, mod;
            try {
                size = Long.parseLong(f[1].trim());
                mod = Long.parseLong(f[2].trim());
            } catch (NumberFormatException e) {
                size = 0;
                mod = 0;
            }
            out.add(new Item(f[3], "d".equals(f[0]), size, mod * 1000L));
        }
        return out;
    }

    /** Shell-quoted absolute path, for building one-off commands. */
    public static String path(File f) {
        return f == null ? "" : quote(f.getAbsolutePath());
    }

    /** Outcome of one shell command. */
    public static final class Result {
        public final boolean ok;
        public final String out;
        public final String err;

        Result(boolean ok, String out, String err) {
            this.ok = ok;
            this.out = out;
            this.err = err;
        }
    }
}
