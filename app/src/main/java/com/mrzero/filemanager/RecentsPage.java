package com.mrzero.filemanager;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** "Recents": files touched in the last 30 days, newest first, grouped by day. */
public class RecentsPage extends FrameLayout implements FileAdapter.Listener {

    private static final long WINDOW_MS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_ITEMS = 200;

    private final MainActivity act;
    private final NavBar nav;
    private final ListSurface surface;
    private View emptyView;
    private int topInset, bottomInset;

    public RecentsPage(MainActivity act) {
        super(act);
        this.act = act;

        nav = new NavBar(act);
        nav.setLargeTitle("Recents");
        nav.setPathLine("Items changed in the last 30 days");
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(act)));

        topInset = NavBar.expandedHeightFor(act);
        bottomInset = act.tabBar.contentInset();

        surface = new ListSurface(act);
        surface.setListener(this);
        surface.adapter.setSelectionEnabled(false);
        surface.setTopInset(topInset);
        surface.setBottomInset(bottomInset + Apple.dp(act, 12f));
        // Index 0: an opaque surface added on top would hide the nav bar.
        addView(surface, 0, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        nav.addTrailing(Controls.iconButton(act, R.drawable.ic_sliders, 0xFF0A84FF,
                "Options", v -> showOptions()));

        surface.list.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                nav.setCollapseProgress(surface.scrolledDistance()
                        / Apple.dp(act, Apple.LARGE_TITLE_DP));
            }
        });
    }

    public void onShown() { refresh(); }

    public void setShowHidden(boolean on) {
        surface.adapter.setShowHidden(on);
    }

    public void refresh() {
        act.io().execute(() -> {
            final List<FileEntry> recent = collect();
            act.runOnUiThread(() -> {
                if (recent.isEmpty()) {
                    showEmpty();
                } else {
                    hideEmpty();
                    surface.adapter.submit(recent);
                    nav.setPathLine(recent.size() + (recent.size() == 1
                            ? " item changed in the last 30 days"
                            : " items changed in the last 30 days"));
                }
            });
        });
    }

    /**
     * Walks a bounded set of roots rather than the whole volume: a full
     * recursive scan of a real device's storage takes tens of seconds.
     */
    private List<FileEntry> collect() {
        long cutoff = System.currentTimeMillis() - WINDOW_MS;
        List<FileEntry> out = new ArrayList<>();
        File root = new File(BrowsePage.ROOT).exists() ? new File(BrowsePage.ROOT)
                : android.os.Environment.getExternalStorageDirectory();
        String[] dirs = {"DCIM", "Pictures", "Movies", "Music", "Documents",
                "Download", "Podcasts", "Screenshots", "WhatsApp", "Telegram"};
        for (String d : dirs) {
            File f = new File(root, d);
            if (!f.isDirectory() || !f.canRead()) continue;
            scan(f, cutoff, out, 3);
            if (out.size() >= MAX_ITEMS) break;
        }
        if (out.size() < MAX_ITEMS) {
            // top-level files too (APKs, zips, documents dropped in the root)
            File[] kids = root.listFiles();
            if (kids != null) {
                for (File f : kids) {
                    if (out.size() >= MAX_ITEMS) break;
                    if (!f.isFile() || f.isHidden()) continue;
                    if (f.lastModified() < cutoff) continue;
                    out.add(FileEntry.of(f));
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));
        if (out.size() > MAX_ITEMS) return new ArrayList<>(out.subList(0, MAX_ITEMS));
        return out;
    }

    private void scan(File dir, long cutoff, List<FileEntry> out, int depth) {
        if (depth < 0 || out.size() >= MAX_ITEMS) return;
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File f : kids) {
            if (out.size() >= MAX_ITEMS) return;
            if (f.isHidden()) continue;
            if (f.isDirectory()) {
                scan(f, cutoff, out, depth - 1);
            } else if (f.lastModified() >= cutoff) {
                out.add(FileEntry.of(f));
            }
        }
    }

    private void showOptions() {
        List<Sheets.Option> opts = new ArrayList<>();
        opts.add(new Sheets.Option("Clear", false, () -> act.toast("Recents are computed on the fly")));
        opts.add(new Sheets.Option("About these items", false, () -> Sheets.info(act,
                FileEntry.of(new File(BrowsePage.ROOT)))));
        Sheets.options(act, "Recents", opts, null);
    }

    private void showEmpty() {
        hideEmpty();
        surface.adapter.submit(new ArrayList<>());
        emptyView = DetailPane.emptyState(act, R.drawable.ic_clock, "No Recent Items",
                "Items you modify will appear here.");
        addView(emptyView, new LayoutParams(LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void hideEmpty() {
        if (emptyView != null) {
            removeView(emptyView);
            emptyView = null;
        }
    }

    @Override
    public void onOpen(FileEntry e, int position) {
        if (e.isFolder()) {
            if (act.browsePage != null) {
                act.tabBar.select(MainActivity.TAB_BROWSE, true);
                act.browsePage.revealIntentUri(android.net.Uri.fromFile(e.file));
            }
        } else {
            Actions.open(act, e);
        }
    }

    @Override
    public void onMenu(FileEntry e, int position, View anchor) {
        Actions.showMenu(act, e, anchor, this::refresh);
    }

    @Override
    public void onSelectionChanged() {
        // Bulk edit is not offered on Recents; selection is disabled entirely.
    }
}
