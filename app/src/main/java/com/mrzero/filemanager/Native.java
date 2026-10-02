package com.mrzero.filemanager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * JNI bridge to the native metadata engine in {@code app/src/main/cpp/af_fs.cpp}.
 *
 * <p>One native call returns a whole directory as a direct {@link ByteBuffer} of
 * struct-of-arrays columns, so a 20k-entry folder costs one JNI crossing and no
 * copies. {@link Columns} is the read side of that buffer.
 *
 * <p>Every method here is a fast path with a Java equivalent behind it in
 * {@link FileEntry}: when the library is missing, or the native call declines
 * (which is how an unreadable folder is reported), the caller runs the original
 * implementation and behaves exactly as before.
 */
final class Native {

    private static final boolean AVAILABLE = load();

    private Native() {}

    private static boolean load() {
        try {
            System.loadLibrary("applefiles");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean available() {
        return AVAILABLE;
    }

    /**
     * Reads {@code path} and returns its entries, or null when the directory
     * could not be opened. Entries arrive already filtered, sorted and
     * child-counted; the ".." row is added by the caller.
     */
    static native ByteBuffer afList(String path, boolean showHidden, int sort,
                                    boolean ascending, boolean countChilds);

    /**
     * Breadth-first name search, newest hits first. Returns an empty buffer when
     * nothing matched, and null only when the arguments are unusable.
     */
    static native ByteBuffer afSearch(String root, String query, boolean showHidden,
                                      int maxResults, int maxDepth);

    /** Recursive byte total, or -1 when the walk could not run. */
    static native long afTreeSize(String path, int maxDepth);

    /**
     * Typed read side of the columnar buffer. Column offsets are recomputed here
     * from the header, which mirrors {@code serialize()} in af_fs.cpp - keep the
     * two in step.
     *
     * <p>The buffer is little-endian, which is every Android ABI.
     */
    static final class Columns {

        private static final int HEADER = 16;

        private final ByteBuffer b;
        private final int count;
        private final int nameOffAt;
        private final int extOffAt;
        private final int flagsAt;
        private final int kindAt;
        private final int childAt;
        private final int sizeAt;
        private final int mtimeAt;

        Columns(ByteBuffer buffer) {
            b = buffer.order(ByteOrder.LITTLE_ENDIAN);
            count = b.getInt(0);
            int nameBytes = b.getInt(4);
            int extBytes = b.getInt(8);

            int off = alignUp(HEADER + nameBytes + extBytes, 4);
            nameOffAt = off;
            off += 4 * (count + 1);
            extOffAt = off;
            off += 4 * (count + 1);
            flagsAt = off;
            off += 4 * count;
            kindAt = off;
            off += 4 * count;
            childAt = off;
            off += 4 * count;
            sizeAt = alignUp(off, 8);
            mtimeAt = sizeAt + 8 * count;
        }

        private static int alignUp(int v, int a) {
            return (v + a - 1) & ~(a - 1);
        }

        int count() {
            return count;
        }

        boolean isDir(int i) {
            return (b.getInt(flagsAt + i * 4) & 1) != 0;
        }

        boolean hidden(int i) {
            return (b.getInt(flagsAt + i * 4) & 2) != 0;
        }

        /** Ordinal of {@link FileEntry.Kind}. */
        int kind(int i) {
            return b.getInt(kindAt + i * 4);
        }

        int childCount(int i) {
            return b.getInt(childAt + i * 4);
        }

        long size(int i) {
            return b.getLong(sizeAt + i * 8);
        }

        long mtime(int i) {
            return b.getLong(mtimeAt + i * 8);
        }

        String name(int i, byte[] scratch) {
            return read(nameOffAt, i, scratch);
        }

        String ext(int i, byte[] scratch) {
            return read(extOffAt, i, scratch);
        }

        /** @param columnAt byte offset of the column's first entry. */
        private String read(int columnAt, int i, byte[] scratch) {
            int from = b.getInt(columnAt + i * 4);
            int to = b.getInt(columnAt + (i + 1) * 4);
            int len = to - from;
            if (len <= 0) return "";
            // Absolute gets, so the columns stay readable no matter where the
            // position happens to be. scratch is reused by the caller, which is
            // what keeps a 20k-entry listing from allocating 20k byte arrays.
            if (scratch.length < len) {
                byte[] exact = new byte[len];
                b.get(from, exact);
                return new String(exact, 0, len, StandardCharsets.UTF_8);
            }
            b.get(from, scratch, 0, len);
            return new String(scratch, 0, len, StandardCharsets.UTF_8);
        }
    }
}