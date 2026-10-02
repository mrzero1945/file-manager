package com.mrzero.filemanager;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Copy / move / delete / rename / zip against both the local FS and SAF trees. */
public final class FileOps {

    public interface Progress {
        void onProgress(String name, long done, long total);
    }

    private static final int BUF = 128 * 1024;

    private FileOps() {}

    // ------------------------------------------------------------------
    public static void copy(File src, File dstDir, boolean newName, Progress p)
            throws IOException {
        File out = newName ? FileEntry.uniqueChild(dstDir, src.getName()) : new File(dstDir, src.getName());
        if (src.isDirectory()) {
            mkdirsChecked(out);
            File[] kids = src.listFiles();
            if (kids == null) throw FileEntry.io("Cannot read " + src.getName());
            for (File k : kids) copy(k, out, false, p);
        } else {
            stream(src, out, p);
        }
    }

    public static void copyUri(Context ctx, Uri src, File dstDir) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        File out = FileEntry.uniqueChild(dstDir, displayName(ctx, src, dstDir));
        try (InputStream in = cr.openInputStream(src)) {
            if (in == null) throw FileEntry.io("Cannot open source");
            copyStream(in, out);
        }
    }

    public static void move(File src, File dstDir, boolean newName, Progress p)
            throws IOException {
        File out = newName ? FileEntry.uniqueChild(dstDir, src.getName()) : new File(dstDir, src.getName());
        if (src.getParentFile() != null && out.getParentFile() != null
                && src.getParentFile().getAbsolutePath().equals(out.getParentFile().getAbsolutePath())) {
            if (!src.renameTo(out)) {
                copy(src, dstDir, true, p);
                deleteRecursively(src);
            }
            return;
        }
        if (src.isDirectory()) {
            if (out.exists()) throw FileEntry.io("Destination already exists: " + out.getName());
            // A rename across volumes/mounts can fail; fall back to copy+delete.
            // copy() takes the *parent* dir, and uniqueChild() is deterministic,
            // so this recreates exactly the same destination name.
            if (!src.renameTo(out)) {
                copy(src, dstDir, newName, p);
                deleteRecursively(src);
            }
        } else {
            if (src.renameTo(out)) return;
            stream(src, out, p);
            if (!src.delete()) throw FileEntry.io("Copied but could not remove " + src.getName());
        }
    }

    public static void moveUri(Context ctx, Uri src, File dstDir) throws IOException {
        File f = FileOps.localFileFor(ctx, src);
        if (f != null) { move(f, dstDir, true, null); return; }
        copyUri(ctx, src, dstDir);
        try { DocumentsContract.deleteDocument(ctx.getContentResolver(), src); }
        catch (Throwable ignored) { /* source stays; nothing else we can do */ }
    }

    private static File localFileFor(Context ctx, Uri uri) {
        try {
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                String id = DocumentsContract.getDocumentId(uri);
                if (id != null && id.startsWith("primary:")) {
                    String rel = id.substring(8);
                    File root = Environment.getExternalStorageDirectory();
                    return rel.isEmpty() ? root : new File(root, rel);
                }
            }
            if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
                return new File(uri.getPath());
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void stream(File src, File out, Progress p) throws IOException {
        long total = src.length();
        long done = 0;
        try (InputStream in = new FileInputStream(src)) {
            if (p != null) p.onProgress(src.getName(), 0, total);
            copyStream(in, out, src.getName(), done, total, p);
        }
    }

    private static void copyStream(InputStream in, File out) throws IOException {
        copyStream(in, out, null, 0, 0, null);
    }

    private static void copyStream(InputStream in, File out, String label,
                                   long done, long total, Progress p) throws IOException {
        mkdirsChecked(out.getParentFile());
        byte[] buf = new byte[BUF];
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            long sinceReport = 0;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                sinceReport += n;
                if (p != null && sinceReport > 2 * 1024 * 1024) {
                    sinceReport = 0;
                    p.onProgress(label, done, total);
                }
            }
            os.flush();
        }
        if (p != null) p.onProgress(label, total, total);
    }

    public static void mkdirsChecked(File dir) throws IOException {
        if (dir == null) return;
        if (dir.isDirectory()) return;
        if (dir.exists()) throw FileEntry.io("Not a folder: " + dir.getName());
        if (!dir.mkdirs()) throw FileEntry.io("Cannot create " + dir.getAbsolutePath());
    }

    public static boolean deleteRecursively(File f) {
        if (f == null) return true;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            // A folder java.io cannot open (everything above the shared volume)
            // must go through the shell, or its contents would be left behind.
            if (kids == null) return rootDelete(f);
            for (File k : kids) {
                if (!deleteRecursively(k)) return false;
            }
        }
        if (f.delete()) return true;
        // The unlink was refused. That is either an entry the app cannot even
        // stat, or a path the platform keeps for itself, so ask the shell.
        return !f.exists() || rootDelete(f);
    }

    /** rm -rf through Magisk, then confirm the entry is really gone. */
    private static boolean rootDelete(File f) {
        if (!Root.isGranted(null)) return false;
        if (!Root.sh("rm -rf -- " + Root.path(f)).ok) return false;
        return !Root.sh("test -e " + Root.path(f)).ok;   // fails => no longer there
    }

    public static int deleteAll(List<FileEntry> entries) {
        int n = 0;
        for (FileEntry e : entries) if (deleteRecursively(e.file)) n++;
        return n;
    }

    public static void rename(File f, String newName) throws IOException {
        String clean = sanitize(newName);
        if (clean.isEmpty()) throw FileEntry.io("Name cannot be empty");
        File target = new File(f.getParentFile(), clean);
        if (!TextUtils.equals(clean, f.getName()) && target.exists()) {
            throw FileEntry.io("“" + clean + "” already exists");
        }
        if (!f.renameTo(target) && !rootMove(f, target)) {
            throw FileEntry.io("Rename failed");
        }
    }

    public static void newFolder(File parent, String name) throws IOException {
        String clean = sanitize(name);
        if (clean.isEmpty()) throw FileEntry.io("Name cannot be empty");
        File target = new File(parent, clean);
        try {
            mkdirsChecked(target);
        } catch (IOException first) {
            if (!Root.sh("mkdir -p -- " + Root.path(target)).ok) throw first;
        }
    }

    /** New empty document, falling back to touch for root-only folders. */
    public static void newFile(File parent, String name) throws IOException {
        String clean = sanitize(name);
        if (clean.isEmpty()) clean = "Untitled.txt";
        if (clean.indexOf('.') < 0) clean = clean + ".txt";
        File target = FileEntry.uniqueChild(parent, clean);
        if (!target.createNewFile()
                && !Root.sh("touch -- " + Root.path(target)).ok) {
            throw FileEntry.io("Cannot create file");
        }
    }

    static boolean rootMove(File from, File to) {
        if (!Root.isGranted(null)) return false;
        return Root.sh("mv -f -- " + Root.path(from) + " " + Root.path(to)).ok;
    }

    // ------------------------------------------------------------------
    // Zip
    // ------------------------------------------------------------------
    /**
     * Packs files and folders into a new archive next to them.
     *
     * Entry names are relative to each source's parent, so compressing a folder
     * produces one top-level entry for that folder rather than scattering its
     * contents into the archive root.
     */
    public static File compress(List<File> sources, Progress p) throws IOException {
        if (sources == null || sources.isEmpty()) throw FileEntry.io("Nothing to compress");
        File first = sources.get(0);
        File dir = first.getParentFile() == null ? new File(".") : first.getParentFile();
        String base = first.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0 && sources.size() == 1) base = base.substring(0, dot);
        base = sanitize(base);
        if (base.isEmpty()) base = "Archive";
        File zip = FileEntry.uniqueChild(dir, base + ".zip");

        long total = 0;
        for (File s : sources) total += bytesOf(s);

        mkdirsChecked(zip.getParentFile());
        long[] done = {0};
        try (ZipOutputStream out = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zip)))) {
            // Photos and video are already compressed; spending CPU on them makes
            // the archive bigger-slower for no gain, so keep the level low.
            out.setLevel(Deflater.BEST_SPEED);
            for (File s : sources) addToZip(out, s, baseName(s), p, done, total);
        } catch (IOException e) {
            // Never leave a half-written archive behind.
            if (zip.exists() && !zip.delete()) zip.deleteOnExit();
            throw e;
        }
        if (p != null) p.onProgress(zip.getName(), total, total);
        return zip;
    }

    /** Strips any directory part, for archive entry names and source names. */
    private static String baseName(String n) {
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        return slash >= 0 ? n.substring(slash + 1) : n;
    }

    private static String baseName(File f) {
        return baseName(f.getName());
    }

    private static void addToZip(ZipOutputStream out, File f, String entryName,
                                 Progress p, long[] done, long total)
            throws IOException {
        if (f.isDirectory()) {
            ZipEntry dir = new ZipEntry(entryName + "/");
            dir.setTime(f.lastModified());
            out.putNextEntry(dir);
            out.closeEntry();
            File[] kids = f.listFiles();
            if (kids == null) {
                // Unreadable folder (above the shared volume): the shell can
                // enumerate it even when java.io cannot.
                List<Root.Item> viaRoot = Root.list(f);
                if (viaRoot == null) throw FileEntry.io("Cannot read " + f.getName());
                for (Root.Item it : viaRoot) {
                    addToZip(out, new File(f, it.name), entryName + "/" + it.name,
                            p, done, total);
                }
                return;
            }
            for (File k : kids) {
                addToZip(out, k, entryName + "/" + k.getName(), p, done, total);
            }
            return;
        }
        if (!f.isFile() || f.length() == 0) return;

        ZipEntry ze = new ZipEntry(entryName);
        ze.setTime(f.lastModified());
        out.putNextEntry(ze);
        long n = 0;
        byte[] buf = new byte[BUF];
        try (InputStream in = new FileInputStream(f)) {
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                n += r;
                done[0] += r;
                if (p != null) p.onProgress(f.getName(), done[0], total);
            }
        }
        out.closeEntry();
    }

    private static long bytesOf(File f) {
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) sum += bytesOf(k);
        return sum;
    }

    /**
     * Unpacks an archive into a new folder beside it, named after the archive.
     * The folder is removed again if the archive turns out to hold nothing.
     */
    public static File extract(File zip, Progress p) throws IOException {
        if (zip == null || !zip.isFile()) throw FileEntry.io("Not a file");
        File dir = zip.getParentFile() == null ? new File(".") : zip.getParentFile();
        String name = baseName(zip);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        name = sanitize(name);
        if (name.isEmpty()) name = "Extracted";
        File outDir = FileEntry.uniqueChild(dir, name);
        mkdirsChecked(outDir);

        int files = 0;
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry ze;
            byte[] buf = new byte[BUF];
            while ((ze = zin.getNextEntry()) != null) {
                File target = safeExtractPath(outDir, ze.getName());
                if (target == null) {
                    throw FileEntry.io("Archive contains an unsafe path: " + ze.getName());
                }
                if (ze.isDirectory()) {
                    mkdirsChecked(target);
                    zin.closeEntry();
                    continue;
                }
                mkdirsChecked(target.getParentFile());
                try (OutputStream os = new FileOutputStream(target)) {
                    int r;
                    while ((r = zin.read(buf)) > 0) {
                        os.write(buf, 0, r);
                        total += r;
                        if (p != null) p.onProgress(baseName(ze.getName()), total, -1);
                    }
                }
                if (ze.getTime() > 0) target.setLastModified(ze.getTime());
                files++;
                zin.closeEntry();
            }
        } catch (IOException e) {
            deleteRecursively(outDir);
            throw e;
        }
        if (files == 0) {
            deleteRecursively(outDir);
            throw FileEntry.io("Archive is empty");
        }
        if (p != null) p.onProgress(outDir.getName(), total, total);
        return outDir;
    }

    /**
     * Resolves an archive entry inside dest, or null when the entry tries to
     * escape it. This is the "zip slip" guard: a crafted archive can otherwise
     * write to arbitrary paths such as /data/local/tmp.
     */
    private static File safeExtractPath(File dest, String entryName) {
        if (entryName == null || entryName.isEmpty()) return null;
        String name = entryName.replace('\\', '/');
        if (name.startsWith("/") || name.contains(":")) return null;
        File out = new File(dest, name);
        String base = dest.getAbsolutePath();
        String path = out.getAbsolutePath();
        return path.equals(base) || path.startsWith(base + File.separator) ? out : null;
    }

    private static final String ILLEGAL =
            "/\\:*?\"<>|" + (char) 0 + "-";   // NUL written as a char, not an escape

    public static String sanitize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.trim().toCharArray()) {
            sb.append(ILLEGAL.indexOf(c) >= 0 ? '_' : c);
        }
        String out = sb.toString().trim();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }

    public static String displayName(Context ctx, Uri uri, File fallbackDir) {
        String name = DocumentsContract.Document.COLUMN_DISPLAY_NAME;
        try {
            String n = queryString(ctx, uri, name);
            if (!TextUtils.isEmpty(n)) return n;
        } catch (Throwable ignored) {
        }
        String p = uri.getLastPathSegment();
        return TextUtils.isEmpty(p) ? "Untitled" : p;
    }

    private static String queryString(Context ctx, Uri uri, String col) {
        try (android.database.Cursor c = ctx.getContentResolver()
                .query(uri, new String[]{col}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        }
        return null;
    }

    /** Recursive size, run off the UI thread by callers. */
    public static List<File> childrenOf(File dir) {
        List<File> out = new ArrayList<>();
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) out.add(k);
        return out;
    }

    /** Read access: All files access on API 30+, granted READ below that. */
    public static boolean hasStoragePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    /** Write access, checked before any create/rename/delete. */
    public static boolean canWriteStorage(Context ctx) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23) {
            return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static String formatSize(long bytes) {
        if (bytes <= 0) return "Zero bytes";
        if (bytes < 1024) return bytes + " byte" + (bytes == 1 ? "" : "s");
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        String num = v < 10 ? String.format(Locale.US, "%.1f", v)
                            : String.format(Locale.US, "%.0f", v);
        return num + " " + units[u];
    }

    public static String formatDate(long millis) {
        java.text.DateFormat df =
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM);
        java.text.DateFormat tf =
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT);
        java.util.Date d = new java.util.Date(millis);
        return df.format(d) + " at " + tf.format(d);
    }
}
