# Apple Files

A file manager for Android with an Apple Files–inspired interface, written in Java with a
native C++ metadata engine.

<p align="center">
  <img src="design/playstore-512.png" width="128" height="128" alt="Apple Files icon">
</p>

## Features

- **Browse** — folder tree with pull-to-refresh, breadcrumbs, and pull-down large titles.
- **Recents** — everything touched in the last 30 days, newest first, grouped by day.
- **Storage** — capacity breakdown with a per-category chart.
- **Search** — recursive filename search across a chosen root.
- **File operations** — copy, move, rename, delete, create folder/file, select all, paste here.
- **Archives** — compress entries to ZIP and extract ZIP archives in place.
- **Sharing** — share any file through the system share sheet, and appear in the
  "Open with" / "Send to" choosers of other apps.
- **Sorting** — by name, date modified, size, or kind, ascending or descending.
- **Hidden files** — optional toggle, applied globally.
- **Root access** — optional Magisk/`su` support so folders outside the storage
  permission boundary can be browsed. Degrades silently when unavailable.
- **Thumbnails** — image previews for the file list.
- **Permissions** — runtime prompts for legacy storage permissions, or a deep link to
  *All files access* on Android 11+.

No ads, no analytics, no network access. The app never uploads anything; it only talks to
the local filesystem.

## Requirements

| | |
|---|---|
| Minimum Android version | 7.0 (API 24) |
| Target / compile SDK | 35 |
| Build JDK | 17 |
| Native toolchain | Android NDK + CMake 3.22.1 |

## Permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | "All files access" — needed on Android 11+ to browse shared storage outside the per-media grants. |
| `READ_EXTERNAL_STORAGE` (max API 32) | Reading files on Android 7–12L. |
| `WRITE_EXTERNAL_STORAGE` (max API 29) | Writing files on Android 7–10. |
| `READ_MEDIA_IMAGES` / `_VIDEO` / `_AUDIO` (API 33+) | Split media access on Android 13+. |

## Building

Point Gradle at your SDK, either through `ANDROID_HOME` or a `local.properties` file with
`sdk.dir=/path/to/Android/Sdk` (it is git-ignored):

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleRelease
```

The signed APK is written to
`app/build/outputs/apk/release/AppleFiles-release-<versionName>.apk`.

Debug builds use the `.debug` application ID suffix so they can sit next to a release
install:

```bash
./gradlew assembleDebug
```

### Release signing

Release signing credentials are read from `keystore.properties` in the repository root,
which is git-ignored and never committed:

```properties
storeFile=applefiles.jks
storePassword=…
keyAlias=…
keyPassword=…
```

If the file (or the `storeFile` key) is absent, the release build falls back to unsigned
output and CI can supply the same values as environment variables instead.

## Project layout

```
app/src/main/java/com/mrzero/filemanager/   UI and app logic (Java)
app/src/main/cpp/af_fs.cpp                 native metadata engine (readdir/stat/sort/walk)
app/src/main/res/                          layouts, drawables, strings
design/                                    launcher artwork and generated icon sources
tools/                                     design spec + asset generation scripts
```

### Why there is a C++ engine

Scanning a directory through the Java `File` API costs 2–3 `stat()` syscalls per entry and
allocates a `File` plus several `String`s for each one. On large folders that is the
difference between a list that appears instantly and one that stutters. `af_fs.cpp` walks
the tree once and hands back a single direct `ByteBuffer` of packed `FileEntry` records
that the adapter renders without allocating per row.

If the shared library fails to load, the app transparently falls back to the Java path, so
it stays usable instead of crashing.

## License

Released under the [GNU General Public License v3.0](LICENSE). You are free to use,
study, modify and redistribute it — provided you keep the source open under the same
license and pass on the exact same freedoms to anyone you give a copy to.
