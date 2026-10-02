package com.mrzero.filemanager;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** File metadata, categorisation and listing. Pure logic, no UI, no Context. */
public final class FileEntry {

    public enum Kind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, ARCHIVE, CODE, APK, TEXT, FILE }

    /** Cached so the native column lookup does not clone the enum per entry. */
    private static final Kind[] KINDS = Kind.values();

    public final File file;
    public final String name;
    public final long size;
    public final long lastModified;
    public final Kind kind;
    public final boolean hidden;
    public final String ext;
    /** Non-null when the entry is on a SAF-backed provider rather than the local FS. */
    public final Uri documentUri;

    private FileEntry(File file, String name, long size, long lastModified,
                      Kind kind, boolean hidden, String ext, Uri documentUri) {
        this.file = file;
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
        this.kind = kind;
        this.hidden = hidden;
        this.ext = ext;
        this.documentUri = documentUri;
    }

    /**
     * Number of direct children, or -1 when it was never counted. Filled in by
     * list() off the UI thread: doing a readdir per row in onBindViewHolder is
     * what made the first layout pass take 600ms+ on a large root.
     */
    public int childCount = -1;

    /** True for the ".." row, which navigates up instead of opening a folder. */
    public boolean isParentRow;

    public boolean isFolder() { return kind == Kind.FOLDER; }
    public long lastModifiedSort() { return lastModified; }

    /**
     * The synthetic ".." row. Its file is the real parent, so opening it walks up
     * exactly like tapping any other folder; kindOfName marks it for the adapter.
     */
    public static FileEntry parentEntry(File dir) {
        File parent = dir == null ? null : dir.getParentFile();
        FileEntry e = new FileEntry(parent == null ? dir : parent, "..",
                0L, 0L, Kind.FOLDER, false, "", null);
        e.isParentRow = true;
        return e;
    }

    public static FileEntry of(File f) {
        boolean dir = f.isDirectory();
        String name = f.getName();
        String ext = dir ? "" : extension(name);
        return new FileEntry(f, name, dir ? 0L : f.length(), f.lastModified(),
                dir ? Kind.FOLDER : kindOf(name, ext), name.startsWith("."), ext, null);
    }

    /**
     * Entry built from metadata that came from the root shell, for folders the
     * Java File API cannot stat.
     */
    public static FileEntry of(File f, boolean dir, long size, long lastModified) {
        String name = f.getName();
        String ext = dir ? "" : extension(name);
        return new FileEntry(f, name, dir ? 0L : size, lastModified,
                dir ? Kind.FOLDER : kindOf(name, ext), name.startsWith("."), ext, null);
    }

    /**
     * Builds an entry from one column of a native listing. The native side has
     * already derived the extension and the kind, so the only strings allocated
     * here are the ones the UI actually shows - and the scratch array is reused
     * across every entry of the listing instead of per entry.
     *
     * @param file the entry's location; callers resolve it differently for a
     *             listing (relative to the folder) and a search (already absolute)
     */
    private static FileEntry fromColumns(Native.Columns c, int i, File file, String name,
                                         byte[] scratch) {
        FileEntry e = new FileEntry(file, name, c.size(i), c.mtime(i), KINDS[c.kind(i)],
                c.hidden(i), c.ext(i, scratch), null);
        e.childCount = c.childCount(i);
        return e;
    }

    public static String extension(String name) {
        int i = name.lastIndexOf('.');
        if (i <= 0 || i == name.length() - 1) return "";
        return name.substring(i + 1).toLowerCase(Locale.US);
    }

    // BitmapFactory cannot decode these, so they are not photo previews:
    // svg is markup, ico needs a different decoder.
    private static final Set<String> IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp",
            "bmp", "heic", "heif", "avif", "dng", "tif", "tiff");
    private static final Set<String> VIDEO = setOf("mp4", "mkv", "mov", "avi", "webm",
            "3gp", "m4v", "flv", "wmv", "mpg", "mpeg", "ts", "m2ts");
    private static final Set<String> AUDIO = setOf("mp3", "aac", "wav", "flac", "ogg",
            "m4a", "wma", "opus", "mid", "amr", "aiff", "alac");
    private static final Set<String> ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz",
            "bz2", "xz", "tgz", "iso", "cab", "arj", "lzh", "zst", "apkm", "xapk");
    private static final Set<String> CODE = setOf("java", "kt", "kts", "c", "cpp", "h",
            "hpp", "cc", "cs", "js", "jsx", "ts", "tsx", "py", "rb", "go", "rs", "php",
            "swift", "m", "mm", "sh", "bash", "zsh", "gradle", "json", "xml", "yml",
            "yaml", "toml", "html", "css", "scss", "sql", "smali", "vue", "dart", "lua",
            "svg", "ico");
    private static final Set<String> TEXT = setOf("txt", "md", "log", "csv", "rtf",
            "doc", "docx", "odt", "pages", "epub", "srt", "vtt", "ini", "cfg", "conf",
            "properties", "lock", "diff", "patch");

    static Kind kindOf(String name, String ext) {
        if (ext.isEmpty()) return Kind.FILE;
        if (IMAGE.contains(ext)) return Kind.IMAGE;
        if (VIDEO.contains(ext)) return Kind.VIDEO;
        if (AUDIO.contains(ext)) return Kind.AUDIO;
        if (ext.equals("pdf")) return Kind.PDF;
        if (ext.equals("apk")) return Kind.APK;
        if (ARCHIVE.contains(ext)) return Kind.ARCHIVE;
        if (CODE.contains(ext)) return Kind.CODE;
        if (TEXT.contains(ext)) return Kind.TEXT;
        return Kind.FILE;
    }

    private static Set<String> setOf(String... v) {
        return new HashSet<>(Arrays.asList(v));
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    public static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;

    public static final class Listing {
        public final List<FileEntry> items;
        public final int folderCount, fileCount;
        public final long totalSize;
        public final String error;
        public final boolean truncated;

        Listing(List<FileEntry> items, int folderCount, int fileCount,
               long totalSize, String error, boolean truncated) {
            this.items = items;
            this.folderCount = folderCount;
            this.fileCount = fileCount;
            this.totalSize = totalSize;
            this.error = error;
            this.truncated = truncated;
        }
    }

    /**
     * Reads a directory. Reading a 20k-entry DCIM is the one thing that can
     * stall the UI thread here, so callers run this off-thread.
     */
    /** Folders first, then the requested key; direction applies to both. */
    public static void sort(List<FileEntry> items, int sort, boolean ascending) {
        Comparator<FileEntry> cmp;
        switch (sort) {
            case SORT_DATE: cmp = (a, b) -> Long.compare(b.lastModified, a.lastModified); break;
            case SORT_SIZE: cmp = (a, b) -> Long.compare(b.size, a.size); break;
            case SORT_TYPE: cmp = (a, b) -> a.ext.compareToIgnoreCase(b.ext); break;
            case SORT_NAME:
            default: cmp = (a, b) -> a.name.compareToIgnoreCase(b.name); break;
        }
        // Folders always lead, in both directions - matches iOS Files.
        items.sort((a, b) -> {
            if (a.isFolder() != b.isFolder()) return a.isFolder() ? -1 : 1;
            int c = cmp.compare(a, b);
            return ascending ? c : -c;
        });
    }

    public static Listing list(File dir, int sort, boolean ascending, boolean showHidden) {
        return list(dir, sort, ascending, showHidden, false);
    }

    /**
     * @param withParent prepend the ".." row so a folder can be walked back up
     *                   from inside the list. The volume root has no parent to
     *                   go to, so the row is omitted there.
     */
    public static Listing list(File dir, int sort, boolean ascending, boolean showHidden,
                               boolean withParent) {
        Listing fast = listNative(dir, sort, ascending, showHidden, withParent);
        if (fast != null) return fast;

        if (dir == null) return new Listing(null, 0, 0, 0L, "No folder", false);
        if (!dir.exists()) return new Listing(null, 0, 0, 0L, "Folder unavailable", false);
        if (!dir.canRead()) return new Listing(null, 0, 0, 0L, "Permission denied", false);

        File[] raw = dir.listFiles();
        if (raw == null) return new Listing(null, 0, 0, 0L, "Cannot read folder", false);

        List<FileEntry> items = new ArrayList<>(raw.length);
        int folders = 0, files = 0;
        long bytes = 0;
        for (File f : raw) {
            FileEntry e = of(f);
            if (e.hidden && !showHidden) continue;
            if (e.isFolder()) folders++;
            else { files++; bytes += e.size; }
            items.add(e);
        }

        sort(items, sort, ascending);
        countChildren(items, showHidden);
        if (withParent) items.add(0, parentEntry(dir));
        return new Listing(items, folders, files, bytes, null, false);
    }

    /**
     * The same listing, read through the native engine: one stat per entry
     * instead of two or three, no File[] intermediate, and the sort plus
     * child-count run in C++ against columns rather than object graphs.
     *
     * @return null when the engine is unavailable or declined the directory, so
     *         the caller falls back and keeps the original error messages.
     */
    private static Listing listNative(File dir, int sort, boolean ascending,
                                      boolean showHidden, boolean withParent) {
        if (dir == null || !Native.available()) return null;
        ByteBuffer buf = Native.afList(dir.getAbsolutePath(), showHidden, sort,
                ascending, true);
        if (buf == null) return null;

        Native.Columns c = new Native.Columns(buf);
        int n = c.count();
        byte[] scratch = new byte[128];
        List<FileEntry> items = new ArrayList<>(n + (withParent ? 1 : 0));
        int folders = 0, files = 0;
        long bytes = 0;
        for (int i = 0; i < n; i++) {
            String name = c.name(i, scratch);
            FileEntry e = fromColumns(c, i, new File(dir, name), name, scratch);
            if (e.isFolder()) folders++;
            else { files++; bytes += e.size; }
            items.add(e);
        }
        if (withParent) items.add(0, parentEntry(dir));
        return new Listing(items, folders, files, bytes, null, false);
    }

    // ------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------

    public static final class Storage {
        public final long total, free, used;
        public final File root;

        Storage(long total, long free, File root) {
            this.total = total;
            this.free = free;
            this.used = Math.max(0, total - free);
            this.root = root;
        }

        public int usedPercent() {
            return total <= 0 ? 0 : (int) Math.round(used * 100.0 / total);
        }
    }

    public static Storage storage() {
        File root = Environment.getExternalStorageDirectory();
        try {
            StatFs fs = new StatFs(root.getAbsolutePath());
            long total = fs.getTotalBytes();
            long free = fs.getAvailableBytes();
            return new Storage(total, free, root);
        } catch (Throwable t) {
            return new Storage(0, 0, root);
        }
    }

    public static long sizeOf(File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        if (Native.available()) {
            // The native walk resolves every child against an open dir fd and
            // keeps visited folders in a small native set, so it also stops on
            // the symlink cycles this recursion would otherwise follow forever.
            long total = Native.afTreeSize(f.getAbsolutePath(), 0);
            if (total >= 0) return total;
        }
        long total = 0;
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        for (File k : kids) total += sizeOf(k);
        return total;
    }

    /** Top-level breakdown for the Storage tab: app-chosen buckets, not MIME magic. */
    public static final class Category {
        public final String label;
        public final String iconName;
        public final long bytes;
        public final int colorIndex;
        public final File dir;

        Category(String label, String iconName, long bytes, int colorIndex, File dir) {
            this.label = label;
            this.iconName = iconName;
            this.bytes = bytes;
            this.colorIndex = colorIndex;
            this.dir = dir;
        }
    }

    public static final String[] CATEGORY_LABELS =
            {"Photos", "Movies", "Music", "Documents", "Downloads", "Apps", "Other"};
    private static final String[] CATEGORY_DIRS =
            {"DCIM", "Movies", "Music", "Documents", "Download", "Android", null};
    private static final String[] CATEGORY_ICONS =
            {"file_image", "file_video", "file_audio", "file_text", "tray", "file_apk", "drive"};

    public static List<Category> categories() {
        File root = Environment.getExternalStorageDirectory();
        List<Category> out = new ArrayList<>();
        long counted = 0;
        for (int i = 0; i < CATEGORY_DIRS.length; i++) {
            File d = CATEGORY_DIRS[i] == null ? null : new File(root, CATEGORY_DIRS[i]);
            long b = d == null ? 0 : sizeOf(d);
            counted += b;
            out.add(new Category(CATEGORY_LABELS[i], CATEGORY_ICONS[i], b, i, d));
        }
        long other = Math.max(0, storage().used - counted);
        out.add(new Category(CATEGORY_LABELS[6], CATEGORY_ICONS[6], other, 6, root));
        return out;
    }

    /** Rebuilds a category list from previously measured byte counts. */
    public static List<Category> categoriesFrom(long[] bytes) {
        File root = Environment.getExternalStorageDirectory();
        List<Category> out = new ArrayList<>();
        for (int i = 0; i < CATEGORY_LABELS.length; i++) {
            long b = bytes != null && i < bytes.length ? bytes[i] : 0;
            File d = i >= CATEGORY_DIRS.length || CATEGORY_DIRS[i] == null
                    ? (i == 6 ? root : null) : new File(root, CATEGORY_DIRS[i]);
            out.add(new Category(CATEGORY_LABELS[i], CATEGORY_ICONS[i], b, i, d));
        }
        return out;
    }

    /** Flattens a category list back into per-index byte counts for caching. */
    public static long[] bytesOf(List<Category> cats) {
        long[] out = new long[CATEGORY_LABELS.length];
        if (cats == null) return out;
        for (Category c : cats) {
            if (c.colorIndex >= 0 && c.colorIndex < out.length) out[c.colorIndex] = c.bytes;
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /** Breadth-first walk from `root`, capped so the UI thread never stalls. */
    public static List<FileEntry> search(File root, String query, boolean showHidden,
                                         int maxResults, int maxDepth) {
        if (query == null || query.trim().isEmpty() || root == null || !root.isDirectory()) {
            return new ArrayList<>();
        }
        List<FileEntry> fast = searchNative(root, query.trim(), showHidden, maxResults,
                maxDepth);
        if (fast != null) return fast;
        return searchJava(root, query.trim(), showHidden, maxResults, maxDepth);
    }

    /**
     * The same walk in the native engine. Matching happens on UTF-8 bytes with
     * no lowercased copy of any filename, and nothing but a File object is built
     * for a hit - the rest of the tree costs no Java allocation at all.
     *
     * <p>Each hit arrives as its own absolute path, so a match deep in the tree
     * still resolves to where it was actually found rather than to the root.
     */
    private static List<FileEntry> searchNative(File root, String query,
                                                boolean showHidden, int maxResults,
                                                int maxDepth) {
        if (!Native.available()) return null;
        ByteBuffer buf = Native.afSearch(root.getAbsolutePath(), query, showHidden,
                maxResults, maxDepth);
        if (buf == null) return null;

        Native.Columns c = new Native.Columns(buf);
        int n = c.count();
        byte[] scratch = new byte[128];
        List<FileEntry> hits = new ArrayList<>(Math.min(n, 256));
        for (int i = 0; i < n; i++) {
            String path = c.name(i, scratch);
            int slash = path.lastIndexOf('/');
            String name = slash < 0 ? path : path.substring(slash + 1);
            hits.add(fromColumns(c, i, new File(path), name, scratch));
        }
        return hits;
    }

    private static List<FileEntry> searchJava(File root, String query, boolean showHidden,
                                              int maxResults, int maxDepth) {
        List<FileEntry> hits = new ArrayList<>();
        String q = query.toLowerCase(Locale.getDefault());
        List<File> frontier = new ArrayList<>();
        frontier.add(root);
        Set<String> seenDirs = new LinkedHashSet<>();

        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            List<File> next = new ArrayList<>();
            for (File dir : frontier) {
                if (hits.size() >= maxResults) return hits;
                if (!seenDirs.add(dir.getAbsolutePath())) continue;
                File[] kids = dir.listFiles();
                if (kids == null) continue;
                for (File f : kids) {
                    if (hits.size() >= maxResults) return hits;
                    if (f.isHidden() && !showHidden) continue;
                    if (f.getName().toLowerCase(Locale.getDefault()).contains(q)) {
                        hits.add(of(f));
                    }
                    if (f.isDirectory()) next.add(f);
                }
            }
            frontier = next;
        }
        hits.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        return hits;
    }

    /** How many folders in one listing we are willing to readdir. */
    private static final int CHILD_COUNT_BUDGET = 200;

    private static void countChildren(List<FileEntry> items, boolean showHidden) {
        int budget = CHILD_COUNT_BUDGET;
        for (FileEntry e : items) {
            if (budget <= 0) return;
            if (!e.isFolder()) continue;
            budget--;
            String[] kids = e.file.list();
            if (kids == null) continue;
            int n = 0;
            for (String k : kids) {
                if (showHidden || !k.startsWith(".")) n++;
            }
            e.childCount = n;
        }
    }

    public static File uniqueChild(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            File c = new File(dir, base + " " + i + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    // ------------------------------------------------------------------
    // Thumbnails
    // ------------------------------------------------------------------

    public static Uri thumbnailUri(Context ctx, File f) {
        return Uri.fromFile(f);
    }

    /**
     * The app holds full storage access, so the file itself is the cheapest and
     * most reliable thumbnail source. Thumbs.decode() does a bounds pass plus
     * inSampleSize, so multi-megapixel photos never land in memory whole.
     */
    public static InputStream openThumbnail(Context ctx, File f) {
        try {
            if (!f.isFile() || f.length() <= 0) return null;
            return new FileInputStream(f);
        } catch (FileNotFoundException | SecurityException e) {
            return null;
        }
    }

    public static Uri treeUriFor(File dir) {
        String id = treeIdFor(dir);
        return id == null ? null : DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", id);
    }

    public static String treeIdFor(File dir) {
        String rel = relativeToStorageRoot(dir);
        return rel == null ? null
                : "primary:" + (rel.isEmpty() ? "" : rel);
    }

    private static String relativeToStorageRoot(File dir) {
        try {
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            String p = dir.getAbsolutePath();
            if (p.equals(root)) return "";
            if (p.startsWith(root + "/")) return p.substring(root.length() + 1);
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Uri mediaUriFor(File f) {
        if (Build.VERSION.SDK_INT >= 29 && Environment.isExternalStorageManager()) {
            return MediaStore.setRequireOriginal(uriForFile(f));
        }
        return uriForFile(f);
    }

    private static Uri uriForFile(File f) {
        return Uri.fromFile(f);
    }

    public static File realFile(Uri treeUri) {
        String id = DocumentsContract.getTreeDocumentId(treeUri);
        if (id == null) return null;
        String[] parts = id.split(":");
        if (parts.length < 2) return null;
        File root = "primary".equals(parts[0])
                ? Environment.getExternalStorageDirectory()
                : new File("/storage/" + parts[0]);
        String rel = parts[1];
        return rel.isEmpty() ? root : new File(root, rel);
    }

    public static IOException io(String msg) { return new IOException(msg); }
}
