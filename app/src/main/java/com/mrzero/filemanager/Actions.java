package com.mrzero.filemanager;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Everything the app does *to* a file: open, share, rename, copy, move, delete. */
public final class Actions {

    private Actions() {}

    // ------------------------------------------------------------------
    // Clipboard for copy/move
    // ------------------------------------------------------------------
    private static final List<FileEntry> CLIP = new ArrayList<>();
    private static boolean clipIsMove;

    public static boolean hasClip() { return !CLIP.isEmpty(); }

    public static boolean clipIsMove() { return clipIsMove; }

    public static int clipCount() { return CLIP.size(); }

    public static void setClip(List<FileEntry> entries, boolean move) {
        CLIP.clear();
        if (entries != null) CLIP.addAll(entries);
        clipIsMove = move;
    }

    // ------------------------------------------------------------------
    public static void open(MainActivity act, FileEntry e) {
        if (e.isFolder()) return;
        Context ctx = act.getApplicationContext();
        Uri uri;
        try {
            if (e.documentUri != null) {
                uri = e.documentUri;
            } else if (FileOps.hasStoragePermission(ctx)) {
                uri = FileProvider.getUriForFile(ctx, act.getPackageName() + ".fileprovider",
                        e.file);
            } else {
                uri = Uri.fromFile(e.file);
            }
        } catch (Throwable t) {
            uri = Uri.fromFile(e.file);
        }

        String mime = mimeFor(e);
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, mime);
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // Keep the task in this app: back returns to the file list.
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT);
        try {
            act.startActivity(view);
        } catch (ActivityNotFoundException ane) {
            act.toast("No app can open " + e.name);
        } catch (SecurityException se) {
            act.toast("Permission denied opening " + e.name);
        }
    }

    public static String mimeFor(FileEntry e) {
        String ext = e.ext;
        if (ext.isEmpty()) return "application/octet-stream";
        switch (ext) {
            case "jpg": case "jpeg": return "image/jpeg";
            case "png": return "image/png";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "bmp": return "image/bmp";
            case "heic": return "image/heic";
            case "svg": return "image/svg+xml";
            case "mp4": case "m4v": return "video/mp4";
            case "mkv": return "video/x-matroska";
            case "mov": return "video/quicktime";
            case "webm": return "video/webm";
            case "3gp": return "video/3gpp";
            case "mp3": return "audio/mpeg";
            case "m4a": return "audio/mp4";
            case "flac": return "audio/flac";
            case "wav": return "audio/wav";
            case "ogg": case "opus": return "audio/ogg";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "txt": case "log": case "md": return "text/plain";
            case "html": return "text/html";
            case "json": return "application/json";
            case "xml": return "text/xml";
            case "csv": return "text/csv";
            case "apk": return "application/vnd.android.package-archive";
            default: return "application/octet-stream";
        }
    }

    public static void share(MainActivity act, List<FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        ArrayList<Uri> uris = new ArrayList<>();
        for (FileEntry e : entries) {
            try {
                if (e.documentUri != null) {
                    uris.add(e.documentUri);
                } else {
                    uris.add(FileProvider.getUriForFile(act,
                            act.getPackageName() + ".fileprovider", e.file));
                }
            } catch (Throwable t) {
                uris.add(Uri.fromFile(e.file));
            }
        }
        Intent i;
        if (uris.size() == 1) {
            i = new Intent(Intent.ACTION_SEND);
            i.setType(entries.get(0).isFolder() ? "resource/folder" : mimeFor(entries.get(0)));
            i.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("*/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_SUBJECT, uris.size() == 1 ? entries.get(0).name
                : uris.size() + " items");
        try {
            act.startActivity(Intent.createChooser(i, act.getString(R.string.action_share)));
        } catch (Throwable t) {
            act.toast("No app available to share");
        }
    }

    public static void copyPath(MainActivity act, FileEntry e) {
        ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("path", e.file.getAbsolutePath()));
        act.toast("Path copied");
    }

    /**
     * Every mutating action starts here: without write access the operation
     * would fail deep inside FileOps with an opaque message, so refuse early
     * with the reason.
     */
    private static boolean requireWrite(MainActivity act) {
        if (act.canWriteStorage()) return true;
        act.toast(act.writeBlockedReason());
        return false;
    }

    public static void duplicate(MainActivity act, final FileEntry e) {
        if (!requireWrite(act)) return;
        act.io().execute(() -> {
            final String error = op(() -> {
                FileOps.copy(e.file, e.file.getParentFile(), true, null);
            });
            act.runOnUiThread(() -> {
                if (error == null) {
                    act.toast("Duplicated “" + e.name + "”");
                    BrowsePage.refreshCurrent(act);
                } else {
                    act.toast(error);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    // Zip
    // ------------------------------------------------------------------
    public static void compress(final MainActivity act, final List<FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (!requireWrite(act)) return;
        final List<File> files = new ArrayList<>(entries.size());
        for (FileEntry e : entries) files.add(e.file);
        act.busy(true);
        act.io().execute(() -> {
            final File[] made = {null};
            final String error = op(() -> made[0] = FileOps.compress(files, null));
            act.runOnUiThread(() -> {
                act.busy(false);
                if (error == null) {
                    act.toast("Created “" + made[0].getName() + "”");
                    BrowsePage.refreshCurrent(act);
                } else {
                    act.toast(error);
                }
            });
        });
    }

    public static void extract(final MainActivity act, final FileEntry e) {
        if (!requireWrite(act)) return;
        act.busy(true);
        act.io().execute(() -> {
            final File[] made = {null};
            final String error = op(() -> made[0] = FileOps.extract(e.file, null));
            act.runOnUiThread(() -> {
                act.busy(false);
                if (error == null) {
                    act.toast("Extracted to “" + made[0].getName() + "”");
                    BrowsePage.reveal(act, made[0]);
                } else {
                    act.toast(error);
                }
            });
        });
    }

    // ------------------------------------------------------------------
    public static void rename(MainActivity act, final FileEntry e) {
        if (!requireWrite(act)) return;
        Sheets.alert(act, act.getString(R.string.rename), null, e.name,
                act.getString(R.string.done), null, value -> {
                    act.io().execute(() -> {
                        final String error = op(() -> FileOps.rename(e.file, value));
                        act.runOnUiThread(() -> {
                            if (error == null) {
                                act.toast("Renamed");
                                BrowsePage.refreshCurrent(act);
                            } else {
                                act.toast(error);
                            }
                        });
                    });
                }, null);
    }

    public static void newFolder(MainActivity act, final File dir) {
        if (dir == null) {
            act.toast("Open a folder first");
            return;
        }
        if (!requireWrite(act)) return;
        Sheets.alert(act, "New Folder", "Creates a folder inside “" + dir.getName() + "”",
                "Untitled folder", act.getString(R.string.done), null, value -> {
                    act.io().execute(() -> {
                        final String error = op(() -> FileOps.newFolder(dir, value));
                        act.runOnUiThread(() -> {
                            if (error == null) {
                                act.toast("Folder created");
                                BrowsePage.refreshCurrent(act);
                            } else {
                                act.toast(error);
                            }
                        });
                    });
                }, null);
    }

    public static void newFile(MainActivity act, final File dir) {
        if (dir == null) {
            act.toast("Open a folder first");
            return;
        }
        if (!requireWrite(act)) return;
        Sheets.alert(act, "New File", "Creates an empty document inside “" + dir.getName() + "”",
                "Untitled.txt", act.getString(R.string.done), null, value -> {
                    act.io().execute(() -> {
                        final String error = op(() -> {
                            String name = FileOps.sanitize(value);
                            if (name.isEmpty()) name = "Untitled.txt";
                            if (name.indexOf('.') < 0) name = name + ".txt";
                            File f = FileEntry.uniqueChild(dir, name);
                            if (!f.createNewFile()) throw FileEntry.io("Cannot create file");
                        });
                        act.runOnUiThread(() -> {
                            if (error == null) {
                                act.toast("File created");
                                BrowsePage.refreshCurrent(act);
                            } else {
                                act.toast(error);
                            }
                        });
                    });
                }, null);
    }

    public static void delete(MainActivity act, final List<FileEntry> entries) {
        if (entries == null || entries.isEmpty()) return;
        if (!requireWrite(act)) return;
        String title = entries.size() == 1
                ? "Delete “" + entries.get(0).name + "”?"
                : "Delete " + entries.size() + " items?";
        String body = entries.size() == 1
                ? "This cannot be undone."
                : "These items will be permanently removed. This cannot be undone.";
        Sheets.alert(act, title, body, act.getString(R.string.delete), () -> {
            act.io().execute(() -> {
                int n = 0;
                String error = null;
                for (FileEntry e : entries) {
                    if (e.isFolder() && isProtected(e.file)) {
                        error = "“" + e.name + "” is a system folder";
                        continue;
                    }
                    if (FileOps.deleteRecursively(e.file)) n++;
                    else if (error == null) error = "Could not delete “" + e.name + "”";
                }
                final int deleted = n;
                final String err = error;
                act.runOnUiThread(() -> {
                    if (deleted > 0) {
                        act.toast(deleted == 1 ? "Deleted 1 item" : "Deleted " + deleted + " items");
                        BrowsePage.refreshCurrent(act);
                    }
                    if (err != null) act.toast(err);
                });
            });
        }, null);
    }

    public static void copyTo(final MainActivity act, List<FileEntry> entries) {
        setClip(entries, false);
        act.toast(entries.size() == 1
                ? "Copied “" + entries.get(0).name + "”" : "Copied " + entries.size() + " items");
    }

    public static void moveTo(final MainActivity act, List<FileEntry> entries) {
        setClip(entries, true);
        act.toast(entries.size() == 1
                ? "Moving “" + entries.get(0).name + "”" : "Moving " + entries.size() + " items");
    }

    public static void paste(MainActivity act, final File destDir) {
        if (destDir == null) {
            act.toast("Open a folder first");
            return;
        }
        if (CLIP.isEmpty()) {
            act.toast("Clipboard is empty");
            return;
        }
        final List<FileEntry> snapshot = new ArrayList<>(CLIP);
        final boolean isMove = clipIsMove;
        act.io().execute(() -> {
            int done = 0;
            String error = null;
            for (FileEntry e : snapshot) {
                final FileEntry entry = e;
                final String err = op(() -> {
                    if (isMove) FileOps.move(entry.file, destDir, true, null);
                    else FileOps.copy(entry.file, destDir, true, null);
                });
                if (err == null) done++;
                else if (error == null) error = err;
            }
            if (isMove) CLIP.clear();
            final int count = done;
            final String finalError = error;
            act.runOnUiThread(() -> {
                if (count > 0) {
                    act.toast((isMove ? "Moved " : "Copied ") + count
                            + (count == 1 ? " item" : " items"));
                    BrowsePage.refreshCurrent(act);
                }
                if (finalError != null) act.toast(finalError);
            });
        });
    }

    // ------------------------------------------------------------------
    /** Context menu for a single row. */
    public static void showMenu(final MainActivity act, final FileEntry e, View anchor,
                                final Runnable onChanged) {
        final List<Sheets.Item> items = new ArrayList<>();
        if (e.isFolder()) {
            items.add(new Sheets.Item("Open", R.drawable.ic_folder,
                    () -> BrowsePage.openEntry(act, e, onChanged)));
        } else {
            items.add(new Sheets.Item("Open", R.drawable.ic_file,
                    () -> open(act, e)));
        }
        items.add(new Sheets.Item("Get Info", R.drawable.ic_info, () -> Sheets.info(act, e)));
        items.add(new Sheets.Item("Share", R.drawable.ic_share, () -> {
            List<FileEntry> one = new ArrayList<>();
            one.add(e);
            share(act, one);
        }));
        items.add(new Sheets.Item("Rename", R.drawable.ic_pencil, () -> {
            act.runOnUiThread(() -> rename(act, e));
        }));
        items.add(new Sheets.Item("Duplicate", R.drawable.ic_copy, () -> {
            act.runOnUiThread(() -> duplicate(act, e));
        }));
        items.add(new Sheets.Item("Copy", R.drawable.ic_copy, () -> copyTo(act, one(e))));
        items.add(new Sheets.Item("Move", R.drawable.ic_arrow_right, () -> moveTo(act, one(e))));
        items.add(new Sheets.Item("Compress", R.drawable.ic_zip, () -> {
            compress(act, one(e));
            if (onChanged != null) onChanged.run();
        }));
        if (isZip(e)) {
            items.add(new Sheets.Item("Extract Here", R.drawable.ic_unzip, () -> {
                act.runOnUiThread(() -> extract(act, e));
            }));
        }
        if (e.isFolder()) {
            items.add(new Sheets.Item("New Folder", R.drawable.ic_folder_plus,
                    () -> act.runOnUiThread(() -> newFolder(act, e.file))));
        }
        items.add(new Sheets.Item("Copy Path", R.drawable.ic_link, () -> {
            copyPath(act, e);
            if (onChanged != null) onChanged.run();
        }));
        items.add(new Sheets.Item("Delete", R.drawable.ic_trash, 0xFFFF453A, true,
                () -> act.runOnUiThread(() -> {
                    delete(act, one(e));
                    if (onChanged != null) onChanged.run();
                })));
        Sheets.contextMenu(act, anchor, items);
    }

    /** Action sheet for a multi-selection. */
    public static void showSelectionMenu(final MainActivity act,
                                          final List<FileEntry> entries) {
        final int n = entries.size();
        String title = n + (n == 1 ? " item selected" : " items selected");
        List<Sheets.Item> items = new ArrayList<>();
        items.add(new Sheets.Item("Share", R.drawable.ic_share, () -> share(act, entries)));
        items.add(new Sheets.Item("Copy", R.drawable.ic_copy, () -> copyTo(act, entries)));
        items.add(new Sheets.Item("Move", R.drawable.ic_arrow_right, () -> moveTo(act, entries)));
        items.add(new Sheets.Item("Compress", R.drawable.ic_zip, () -> compress(act, entries)));
        items.add(new Sheets.Item("Duplicate", R.drawable.ic_copy, () -> {
            final int[] ok = {0};
            act.io().execute(() -> {
                for (FileEntry e : entries) {
                    final FileEntry entry = e;
                    if (op(() -> FileOps.copy(entry.file, entry.file.getParentFile(), true, null)) == null) {
                        ok[0]++;
                    }
                }
                act.runOnUiThread(() -> {
                    act.toast("Duplicated " + ok[0] + (ok[0] == 1 ? " item" : " items"));
                    BrowsePage.refreshCurrent(act);
                });
            });
        }));
        items.add(new Sheets.Item("Delete", R.drawable.ic_trash, 0xFFFF453A, true,
                () -> act.runOnUiThread(() -> delete(act, entries))));
        Sheets.actionSheet(act, title, null, items, null);
    }

    private static List<FileEntry> one(FileEntry e) {
        List<FileEntry> l = new ArrayList<>();
        l.add(e);
        return l;
    }

    /** Only real zip archives can be unpacked; rar/7z need a native unpacker. */
    private static boolean isZip(FileEntry e) {
        return e != null && !e.isFolder() && "zip".equals(e.ext);
    }

    /** Runs a file op and returns null on success or a message on failure. */
    public static String op(IOAction a) {
        try {
            a.run();
            return null;
        } catch (Throwable t) {
            String m = t.getMessage();
            return (m == null || m.isEmpty()) ? "Operation failed" : m;
        }
    }

    public interface IOAction {
        void run() throws Exception;
    }

    static boolean isProtected(File f) {
        String n = f.getName();
        return n.equals("Android") || n.equals("data") || n.equals("obb");
    }
}
