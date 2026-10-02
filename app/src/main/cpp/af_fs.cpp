// Native metadata engine for File Manager.
//
// Why this exists
// ---------------
// The browse/search/storage paths are the only thing in this app that can
// visibly stall, and they are all metadata-bound rather than CPU-bound. The
// Java equivalent costs, per entry in a directory:
//
//   File.isDirectory()  -> stat()
//   File.length()       -> stat()
//   File.lastModified() -> stat()
//   File.getName()      -> String substring
//   extension(name)     -> 2 Strings (substring + toLowerCase)
//   listFiles()         -> a File[] holding one File per entry
//
// So a 20k-entry DCIM meant roughly 60k stat() calls, each re-resolving the
// full absolute path, plus about ten objects of garbage per entry. What follows
// does the same job in one pass:
//
//   opendir/readdir     -> names, and d_type tells us the type for free
//   fstatat(dirfd, ...) -> size + mtime, relative to the open dir fd, so the
//                          kernel never re-walks the parent path
//
// which is one stat per entry, path resolution included. Kind classification
// and sorting move here too, so the HashSet probes and the Comparator lambdas
// disappear as well.
//
// Results leave native code as a single direct ByteBuffer of struct-of-arrays
// columns (layout documented on serialize()): one JNI crossing for a whole
// directory, zero copies, and Java builds only the FileEntry objects the UI
// actually needs.
//
// FileEntry keeps its Java implementation as the fallback for every entry
// point below, so a failed readdir still produces the same user-facing error
// text it always did.

#include <jni.h>

#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <string>
#include <string_view>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

// Must stay in sync with FileEntry.Kind.
enum Kind : int32_t {
    K_FOLDER = 0,
    K_IMAGE = 1,
    K_VIDEO = 2,
    K_AUDIO = 3,
    K_PDF = 4,
    K_ARCHIVE = 5,
    K_CODE = 6,
    K_APK = 7,
    K_TEXT = 8,
    K_FILE = 9,
};

// Must stay in sync with FileEntry.SORT_*.
enum Sort : int32_t {
    SORT_NAME = 0,
    SORT_DATE = 1,
    SORT_SIZE = 2,
    SORT_TYPE = 3,
};

// Must stay in sync with FileEntry.CHILD_COUNT_BUDGET.
constexpr int CHILD_COUNT_BUDGET = 200;

/** Safety net for the size walk; real trees are nowhere near this deep. */
constexpr int MAX_WALK_DEPTH = 512;

// ------------------------------------------------------------------
// Extension classification
// ------------------------------------------------------------------
// Same tables as FileEntry, so a file's icon and label do not change.

const char* const EXT_IMAGE[] = {"jpg", "jpeg", "png", "gif",  "webp", "bmp",
                                 "heic", "heif", "avif", "dng",  "tif",  "tiff"};
const char* const EXT_VIDEO[] = {"mp4", "mkv", "mov",  "avi", "webm", "3gp", "m4v",
                                 "flv", "wmv",  "mpg",  "mpeg", "ts",   "m2ts"};
const char* const EXT_AUDIO[] = {"mp3", "aac", "wav",  "flac", "ogg",  "m4a", "wma",
                                 "opus", "mid", "amr",  "aiff", "alac"};
const char* const EXT_ARCHIVE[] = {"zip", "rar", "7z",  "tar",  "gz",   "bz2", "xz",
                                   "tgz", "iso", "cab", "arj",  "lzh",  "zst",
                                   "apkm", "xapk"};
const char* const EXT_CODE[] = {"java", "kt",   "kts",  "c",    "cpp",  "h",
                                "hpp",  "cc",   "cs",   "js",   "jsx",  "ts",
                                "tsx",  "py",   "rb",   "go",   "rs",   "php",
                                "swift", "m",   "mm",   "sh",   "bash", "zsh",
                                "gradle", "json", "xml", "yml",   "yaml", "toml",
                                "html", "css",  "scss", "sql",  "smali", "vue",
                                "dart", "lua",  "svg",  "ico"};
const char* const EXT_TEXT[] = {"txt",  "md",   "log", "csv", "rtf", "doc",
                                "docx", "odt",  "pages", "epub", "srt", "vtt",
                                "ini",  "cfg",  "conf", "properties", "lock",
                                "diff", "patch"};

struct ExtPair {
    std::string_view ext;
    int32_t kind;
};

/** Sorted once, then binary-searched per entry. */
const std::vector<ExtPair>& extTable() {
    static const std::vector<ExtPair> table = [] {
        std::vector<ExtPair> v;
        auto add = [&v](int32_t kind, const char* const* list, size_t n) {
            for (size_t i = 0; i < n; i++) v.push_back({list[i], kind});
        };
        add(K_IMAGE, EXT_IMAGE, sizeof(EXT_IMAGE) / sizeof(*EXT_IMAGE));
        add(K_VIDEO, EXT_VIDEO, sizeof(EXT_VIDEO) / sizeof(*EXT_VIDEO));
        add(K_AUDIO, EXT_AUDIO, sizeof(EXT_AUDIO) / sizeof(*EXT_AUDIO));
        add(K_ARCHIVE, EXT_ARCHIVE, sizeof(EXT_ARCHIVE) / sizeof(*EXT_ARCHIVE));
        add(K_CODE, EXT_CODE, sizeof(EXT_CODE) / sizeof(*EXT_CODE));
        add(K_TEXT, EXT_TEXT, sizeof(EXT_TEXT) / sizeof(*EXT_TEXT));
        v.push_back({"pdf", K_PDF});
        v.push_back({"apk", K_APK});
        std::sort(v.begin(), v.end(),
                  [](const ExtPair& a, const ExtPair& b) { return a.ext < b.ext; });
        return v;
    }();
    return table;
}

// ------------------------------------------------------------------
// Case handling
// ------------------------------------------------------------------
// Java folds through Character.toUpperCase/toLowerCase in
// compareToIgnoreCase, and through Locale.getDefault() in search. For the ASCII
// names that make up almost every real filesystem entry, both are identical to
// folding the ASCII range, which is what happens below. Non-ASCII names are
// matched and ordered by their UTF-8 bytes instead, so a handful of exotic
// names can order differently from the old Java path; sorting and hit count are
// the only things affected.

inline unsigned char fold(unsigned char c) {
    return (c >= 'A' && c <= 'Z') ? static_cast<unsigned char>(c + 32) : c;
}

int cmpNoCase(const char* a, int alen, const char* b, int blen) {
    const int n = alen < blen ? alen : blen;
    for (int i = 0; i < n; i++) {
        const int d = static_cast<int>(fold(static_cast<unsigned char>(a[i]))) -
                      static_cast<int>(fold(static_cast<unsigned char>(b[i])));
        if (d != 0) return d;
    }
    return alen - blen;
}

bool containsNoCase(const char* hay, int hlen, const std::string& needle) {
    const int nlen = static_cast<int>(needle.size());
    if (nlen == 0) return true;
    if (nlen > hlen) return false;
    const unsigned char* n = reinterpret_cast<const unsigned char*>(needle.data());
    for (int i = 0; i <= hlen - nlen; i++) {
        int j = 0;
        while (j < nlen && fold(static_cast<unsigned char>(hay[i + j])) == fold(n[j])) {
            j++;
        }
        if (j == nlen) return true;
    }
    return false;
}

// ------------------------------------------------------------------
// Directory identity
// ------------------------------------------------------------------
// libc++ has no std::hash for a pair, and a walk that guards against symlink
// loops needs one that survives a device+inode collision across mounts.

struct DirId {
    dev_t dev;
    ino_t ino;

    bool operator==(const DirId& o) const { return dev == o.dev && ino == o.ino; }
};

struct DirIdHash {
    size_t operator()(const DirId& id) const {
        return static_cast<size_t>(id.dev) * 1000003u
               ^ static_cast<size_t>(id.ino);
    }
};

using DirSet = std::unordered_set<DirId, DirIdHash>;

// stat's fields are wider than dev_t/ino_t on 32-bit ABIs, so narrow explicitly
// rather than letting brace initialisation reject it.
DirId idOf(const struct stat& st) {
    return DirId{static_cast<dev_t>(st.st_dev), static_cast<ino_t>(st.st_ino)};
}

// ------------------------------------------------------------------
// Entries
// ------------------------------------------------------------------
// Names live in one growable arena and are referenced by offset: readdir reuses
// its own buffer between calls, and a growing std::vector<char> would
// invalidate any char* held across a reallocation. Every stored string is also
// NUL terminated, so at(off) is usable as a C string wherever that is easier
// than threading a length around (openat, most notably).

struct Entry {
    int32_t nameOff;
    int32_t nameLen;
    int32_t extOff;
    int32_t extLen;
    int32_t dir;         // 0 or 1
    int32_t hidden;      // 0 or 1
    int32_t kind;
    int32_t childCount;  // -1 when not counted
    int64_t size;
    int64_t mtime;       // milliseconds, matching File.lastModified()
};

struct Bag {
    std::vector<char> arena;
    std::vector<Entry> entries;

    /** Appends `data` plus a NUL, returning its offset. */
    int32_t put(const char* data, int len) {
        const int32_t off = static_cast<int32_t>(arena.size());
        arena.insert(arena.end(), data, data + len);
        arena.push_back('\0');
        return off;
    }

    const char* at(int32_t off) const { return arena.data() + off; }
};

/**
 * Stores one entry.
 *
 * @param name,nlen the bare filename. Always what the extension and kind are
 *                  derived from, because a full path can carry a dot in one of
 *                  its directory names ("a.b/notes" must not read as "b/notes").
 * @param path      what Java should resolve. Null for a plain listing, where the
 *                  name is relative to the directory being listed. Set for
 *                  search hits, which live anywhere under the root and so must
 *                  carry their own absolute path or Java would rebuild every hit
 *                  directly inside the root.
 */
void appendEntry(Bag& bag, const char* name, int nlen, bool isDir, bool hidden,
                 int64_t size, int64_t mtimeMs, const std::string* path = nullptr) {
    const char* const stored = path != nullptr ? path->data() : name;
    const int storedLen = path != nullptr ? static_cast<int>(path->size()) : nlen;

    Entry e;
    e.nameOff = bag.put(stored, storedLen);
    e.nameLen = storedLen;
    e.dir = isDir ? 1 : 0;
    e.hidden = hidden ? 1 : 0;
    e.size = isDir ? 0 : size;   // matches FileEntry.of(): folders report size 0
    e.mtime = mtimeMs;

    // Mirrors FileEntry.extension(): no dot, a leading dot, or a trailing dot
    // all mean "no extension". '.' is single-byte UTF-8, so a byte scan finds
    // the same position String.lastIndexOf would.
    int32_t extLen = 0;
    const char* extAt = nullptr;
    for (int i = nlen - 1; i >= 0; i--) {
        if (name[i] == '.') {
            if (i > 0 && i < nlen - 1) {
                extAt = name + i + 1;
                extLen = nlen - i - 1;
            }
            break;
        }
    }
    if (extLen > 0) {
        // Reserve first, then take the pointer: resizing can reallocate the
        // arena, so grabbing data() beforehand leaves dst dangling.
        const int32_t extOff = static_cast<int32_t>(bag.arena.size());
        bag.arena.resize(bag.arena.size() + extLen + 1);
        char* const dst = bag.arena.data() + extOff;
        for (int32_t i = 0; i < extLen; i++) {
            dst[i] = static_cast<char>(fold(static_cast<unsigned char>(extAt[i])));
        }
        dst[extLen] = '\0';
        e.extOff = extOff;
        e.extLen = extLen;

        const std::string_view key(dst, static_cast<size_t>(extLen));
        const std::vector<ExtPair>& table = extTable();
        const auto it = std::lower_bound(
            table.begin(), table.end(), key,
            [](const ExtPair& p, std::string_view k) { return p.ext < k; });
        e.kind = (it != table.end() && it->ext == key) ? it->kind : K_FILE;
    } else {
        e.extOff = e.nameOff;
        e.extLen = 0;
        e.kind = K_FILE;
    }
    if (isDir) e.kind = K_FOLDER;
    e.childCount = -1;

    bag.entries.push_back(e);
}

/** True for "." and "..", which readdir yields but File.listFiles() does not. */
bool isDotOrDotDot(const char* n) {
    if (n[0] != '.') return false;
    if (n[1] == '\0') return true;
    return n[1] == '.' && n[2] == '\0';
}

// ------------------------------------------------------------------
// Reading one directory
// ------------------------------------------------------------------
// `dfd` is an open descriptor for the directory, so every stat below is relative
// to it rather than re-resolving an absolute path.

bool readDirInto(Bag& bag, int dfd, bool showHidden) {
    const int copy = dup(dfd);
    if (copy < 0) return false;
    DIR* const d = fdopendir(copy);
    if (d == nullptr) {
        close(copy);
        return false;
    }

    struct stat st;
    for (;;) {
        errno = 0;
        struct dirent* const de = readdir(d);
        if (de == nullptr) break;   // end of directory, or an error we ignore

        const char* const name = de->d_name;
        int nlen = 0;
        while (name[nlen] != '\0') nlen++;
        if (nlen <= 2 && isDotOrDotDot(name)) continue;

        const bool hidden = name[0] == '.';
        if (hidden && !showHidden) continue;

        // One stat per entry, resolved against the open directory fd. Symlinks
        // are followed, which is what File.isDirectory()/length() do.
        if (fstatat(dfd, name, &st, 0) != 0) {
            // Vanished or unreadable: still list it, with no metadata.
            appendEntry(bag, name, nlen, false, hidden, 0, 0);
            continue;
        }
        const bool isDir = S_ISDIR(st.st_mode);
        const int64_t mtimeMs = static_cast<int64_t>(st.st_mtim.tv_sec) * 1000 +
                                (st.st_mtim.tv_nsec / 1000000);
        appendEntry(bag, name, nlen, isDir, hidden, static_cast<int64_t>(st.st_size),
                    mtimeMs);
    }

    closedir(d);
    return true;
}

// ------------------------------------------------------------------
// Sorting
// ------------------------------------------------------------------

void sortEntries(Bag& bag, int32_t sortKey, bool ascending) {
    const char* const arena = bag.arena.data();
    std::sort(bag.entries.begin(), bag.entries.end(),
              [arena, sortKey, ascending](const Entry& a, const Entry& b) {
                  // Folders always lead, in both directions - matches iOS Files.
                  if ((a.dir != 0) != (b.dir != 0)) return a.dir != 0;

                  int c = 0;
                  switch (sortKey) {
                      // c > 0 means "a before b". Java builds these keys as
                      // Long.compare(b, a), so ascending puts the smaller or
                      // older value first - get the sign right or the two
                      // toggle directions swap.
                      case SORT_DATE:
                          c = a.mtime > b.mtime ? 1 : (a.mtime < b.mtime ? -1 : 0);
                          break;
                      case SORT_SIZE:
                          c = a.size > b.size ? 1 : (a.size < b.size ? -1 : 0);
                          break;
                      case SORT_TYPE:
                          c = cmpNoCase(arena + a.extOff, a.extLen,
                                        arena + b.extOff, b.extLen);
                          break;
                      case SORT_NAME:
                      default:
                          c = cmpNoCase(arena + a.nameOff, a.nameLen,
                                        arena + b.nameOff, b.nameLen);
                          break;
                  }
                  return ascending ? c < 0 : c > 0;
              });
}

// ------------------------------------------------------------------
// Child counts
// ------------------------------------------------------------------
// How many folders in one listing we are willing to readdir.

void countChildren(Bag& bag, int dfd, bool showHidden) {
    if (dfd < 0) return;
    int budget = CHILD_COUNT_BUDGET;
    const char* const arena = bag.arena.data();
    for (Entry& e : bag.entries) {
        if (budget <= 0) return;
        if (e.dir == 0) continue;
        budget--;

        const int cfd = openat(dfd, arena + e.nameOff, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
        if (cfd < 0) continue;   // childCount stays -1, as in the Java path
        const int copy = dup(cfd);
        close(cfd);
        if (copy < 0) continue;
        DIR* const d = fdopendir(copy);
        if (d == nullptr) {
            close(copy);
            continue;
        }
        int n = 0;
        for (;;) {
            errno = 0;
            struct dirent* const de = readdir(d);
            if (de == nullptr) break;
            if (isDotOrDotDot(de->d_name)) continue;
            if (!showHidden && de->d_name[0] == '.') continue;
            n++;
        }
        closedir(d);
        e.childCount = n;
    }
}

// ------------------------------------------------------------------
// Serialisation
// ------------------------------------------------------------------
// One direct ByteBuffer per call, laid out as struct-of-arrays so Java reads
// each column with no per-entry JNI crossing and no intermediate object:
//
//   0   int32   count
//   4   int32   nameBytes
//   8   int32   extBytes
//   12  int32   reserved
//   16  char    names[nameBytes]      UTF-8, concatenated
//       char    exts[extBytes]        UTF-8 lowercase, concatenated
//       (pad to 4)
//       int32   nameOff[count + 1]    length of i is nameOff[i+1] - nameOff[i]
//       int32   extOff[count + 1]     same convention; empty when the two are equal
//       int32   flags[count]          bit0 dir, bit1 hidden
//       int32   kind[count]           FileEntry.Kind ordinal
//       int32   childCount[count]     -1 when not counted
//       (pad to 8)
//       int64   size[count]
//       int64   mtime[count]          milliseconds
//
// The name column is what Java hands to File: a bare filename for afList, which
// is resolved against the directory that was listed, and an absolute path for
// afSearch, whose hits come from anywhere under the root. Either way the
// extension and kind were derived from the bare filename, not from this string.
//
// Every Android ABI (armeabi-v7a, arm64-v8a, x86, x86_64) is little-endian, so
// values are memcpy'd in host order and Native.java reads them as such. The
// buffer is malloc'd, and ART frees it when the DirectByteBuffer is collected.

constexpr size_t HEADER_BYTES = 16;

size_t alignUp(size_t v, size_t a) { return (v + a - 1) & ~(a - 1); }

jobject serialize(JNIEnv* env, const Bag& bag) {
    const size_t n = bag.entries.size();

    size_t nameBytes = 0;
    size_t extBytes = 0;
    for (const Entry& e : bag.entries) {
        nameBytes += static_cast<size_t>(e.nameLen);
        extBytes += static_cast<size_t>(e.extLen);
    }

    const size_t namesAt = HEADER_BYTES;
    const size_t extsAt = namesAt + nameBytes;

    size_t off = alignUp(extsAt + extBytes, 4);
    const size_t nameOffAt = off; off += 4 * (n + 1);
    const size_t extOffAt = off;  off += 4 * (n + 1);
    const size_t flagsAt = off;   off += 4 * n;
    const size_t kindAt = off;    off += 4 * n;
    const size_t childAt = off;   off += 4 * n;
    off = alignUp(off, 8);
    const size_t sizeAt = off;   off += 8 * n;
    const size_t mtimeAt = off;  off += 8 * n;
    const size_t total = off;

    void* const raw = malloc(total);
    if (raw == nullptr) return nullptr;
    unsigned char* const p = static_cast<unsigned char*>(raw);
    std::memset(raw, 0, total);

    const int32_t header[4] = {static_cast<int32_t>(n), static_cast<int32_t>(nameBytes),
                               static_cast<int32_t>(extBytes), 0};
    std::memcpy(p, header, sizeof(header));

    int32_t* const nameOffCol = reinterpret_cast<int32_t*>(p + nameOffAt);
    int32_t* const extOffCol = reinterpret_cast<int32_t*>(p + extOffAt);

    size_t nameCursor = namesAt;
    size_t extCursor = extsAt;
    for (size_t i = 0; i < n; i++) {
        const Entry& e = bag.entries[i];
        nameOffCol[i] = static_cast<int32_t>(nameCursor);
        if (e.nameLen > 0) {
            std::memcpy(p + nameCursor, bag.at(e.nameOff),
                        static_cast<size_t>(e.nameLen));
            nameCursor += static_cast<size_t>(e.nameLen);
        }
        extOffCol[i] = static_cast<int32_t>(extCursor);
        if (e.extLen > 0) {
            std::memcpy(p + extCursor, bag.at(e.extOff), static_cast<size_t>(e.extLen));
            extCursor += static_cast<size_t>(e.extLen);
        }
    }
    nameOffCol[n] = static_cast<int32_t>(nameCursor);
    extOffCol[n] = static_cast<int32_t>(extCursor);

    int32_t* const flagsCol = reinterpret_cast<int32_t*>(p + flagsAt);
    int32_t* const kindCol = reinterpret_cast<int32_t*>(p + kindAt);
    int32_t* const childCol = reinterpret_cast<int32_t*>(p + childAt);
    int64_t* const sizeCol = reinterpret_cast<int64_t*>(p + sizeAt);
    int64_t* const mtimeCol = reinterpret_cast<int64_t*>(p + mtimeAt);
    for (size_t i = 0; i < n; i++) {
        const Entry& e = bag.entries[i];
        flagsCol[i] = (e.dir != 0 ? 1 : 0) | (e.hidden != 0 ? 2 : 0);
        kindCol[i] = e.kind;
        childCol[i] = e.childCount;
        sizeCol[i] = e.size;
        mtimeCol[i] = e.mtime;
    }

    return env->NewDirectByteBuffer(raw, static_cast<jlong>(total));
}

// ------------------------------------------------------------------
// Recursive size
// ------------------------------------------------------------------

/** @param depth current level, @param limit deepest level to descend into. */
bool walkSize(int dfd, int depth, int limit, DirSet& seen, int64_t* out) {
    if (depth > limit) return true;

    struct stat st;
    if (fstat(dfd, &st) != 0) return false;
    // Guarded by device+inode rather than by path, because a symlink pointing at
    // an ancestor is the case that would otherwise recurse forever. Alias paths
    // collapse here, which is invisible: this only ever yields a byte total.
    if (!seen.insert(idOf(st)).second) return true;

    if (!S_ISDIR(st.st_mode)) {
        *out += st.st_size;
        return true;
    }

    const int copy = dup(dfd);
    if (copy < 0) return false;
    DIR* const d = fdopendir(copy);
    if (d == nullptr) {
        close(copy);
        return false;
    }

    for (;;) {
        errno = 0;
        struct dirent* const de = readdir(d);
        if (de == nullptr) break;
        if (isDotOrDotDot(de->d_name)) continue;
        // O_NONBLOCK is not optional here: opening a FIFO read-only blocks until a
        // writer shows up, which would hang the walk (and the UI thread behind it)
        // on any tree containing a pipe. It is a no-op for regular files.
        const int cfd = openat(dfd, de->d_name, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (cfd < 0) continue;
        walkSize(cfd, depth + 1, limit, seen, out);
        close(cfd);
    }

    closedir(d);
    return true;
}

// ------------------------------------------------------------------
// JNI plumbing
// ------------------------------------------------------------------

std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* const chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

}  // namespace

extern "C" {

/**
 * Reads one directory and returns the columnar buffer, or null when the
 * directory could not be opened. The ".." row is not included: Java prepends
 * it, because its File points at the parent rather than at a listed entry.
 */
JNIEXPORT jobject JNICALL
Java_com_mrzero_filemanager_Native_afList(JNIEnv* env, jclass, jstring path,
                                          jboolean showHidden, jint sortKey,
                                          jboolean ascending, jboolean countChilds) {
    const std::string dir = toStdString(env, path);
    if (dir.empty()) return nullptr;

    const int dfd = open(dir.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
    if (dfd < 0) return nullptr;

    Bag bag;
    if (!readDirInto(bag, dfd, showHidden == JNI_TRUE)) {
        close(dfd);
        return nullptr;
    }
    sortEntries(bag, sortKey, ascending == JNI_TRUE);
    if (countChilds == JNI_TRUE) countChildren(bag, dfd, showHidden == JNI_TRUE);
    close(dfd);

    return serialize(env, bag);
}

/**
 * Breadth-first name search with the same ordering and caps as
 * FileEntry.search(). Hits come back already sorted newest-first.
 *
 * A non-directory root or an unreadable one yields an empty buffer rather than
 * null: an empty result is a legitimate outcome, so there is nothing to fall
 * back to.
 */
JNIEXPORT jobject JNICALL
Java_com_mrzero_filemanager_Native_afSearch(JNIEnv* env, jclass, jstring root,
                                            jstring query, jboolean showHidden,
                                            jint maxResults, jint maxDepth) {
    const std::string start = toStdString(env, root);
    const std::string needle = toStdString(env, query);
    if (start.empty() || needle.empty() || maxResults <= 0) return nullptr;
    const int cap = maxResults;

    const bool hidden = showHidden == JNI_TRUE;
    const int depthCap = maxDepth <= 0 ? 0 : maxDepth;

    Bag hits;
    // Keyed by absolute path, exactly like the Java walk, so both paths report
    // the same hits: deduping on device+inode would collapse a symlinked alias
    // onto its target and silently change which entries a search finds.
    std::unordered_set<std::string> seenDirs;
    std::vector<std::string> frontier{start};
    std::vector<std::string> next;

    for (int depth = 0; depth < depthCap && !frontier.empty(); depth++) {
        next.clear();
        for (const std::string& dirPath : frontier) {
            if (static_cast<int>(hits.entries.size()) >= cap) break;

            const int dfd = open(dirPath.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC);
            if (dfd < 0) continue;
            if (!seenDirs.insert(dirPath).second) {
                close(dfd);
                continue;
            }

            Bag dir;
            const bool read = readDirInto(dir, dfd, hidden);
            close(dfd);
            if (!read) continue;

            for (const Entry& e : dir.entries) {
                const char* const nm = dir.at(e.nameOff);
                const std::string full = dirPath + "/" + std::string(nm, e.nameLen);
                if (e.dir != 0) next.push_back(full);
                if (containsNoCase(nm, e.nameLen, needle)) {
                    // The hit carries its own absolute path: Java resolves hits
                    // against this string, not against the search root.
                    appendEntry(hits, nm, e.nameLen, e.dir != 0, e.hidden != 0, e.size,
                                e.mtime, &full);
                    if (static_cast<int>(hits.entries.size()) >= cap) break;
                }
            }
        }
        if (static_cast<int>(hits.entries.size()) >= cap) break;
        frontier.swap(next);
    }

    // Matches List.sort with a comparator on mtime, which is stable.
    std::stable_sort(hits.entries.begin(), hits.entries.end(),
                     [](const Entry& a, const Entry& b) { return a.mtime > b.mtime; });

    return serialize(env, hits);
}

/**
 * Recursive byte total, or -1 when the walk could not run. -1 is not a value
 * FileEntry cares about, so it doubles as the "fall back to Java" signal.
 */
JNIEXPORT jlong JNICALL
Java_com_mrzero_filemanager_Native_afTreeSize(JNIEnv* env, jclass, jstring path,
                                              jint maxDepth) {
    const std::string target = toStdString(env, path);
    if (target.empty()) return -1;

    const int dfd = open(target.c_str(), O_RDONLY | O_CLOEXEC);
    if (dfd < 0) return -1;

    int64_t total = 0;
    DirSet seen;
    const bool ok = walkSize(dfd, 0, maxDepth <= 0 ? MAX_WALK_DEPTH : maxDepth, seen,
                             &total);
    close(dfd);
    return ok ? static_cast<jlong>(total) : -1;
}

}  // extern "C"