package com.mrzero.filemanager;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** "Settings": grouped-inset iOS list — locations, view prefs, storage access. */
public class SettingsPage extends FrameLayout {

    private final MainActivity act;
    private final NavBar nav;
    private final LinearLayout root;
    private int topInset, bottomInset;
    private boolean globalShowHidden;

    public SettingsPage(MainActivity act) {
        super(act);
        this.act = act;

        nav = new NavBar(act);
        nav.setLargeTitle("Settings");
        nav.setPathLine("Access, locations and view options");
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(act)));

        topInset = NavBar.expandedHeightFor(act);
        bottomInset = act.tabBar.contentInset();

        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, topInset, 0, bottomInset + Apple.dp(act, 16f));

        ScrollView sv = new ScrollView(act);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFF000000);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        // Keeps the large title attached to the top of the list while collapsed.
        sv.setClipToPadding(true);
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Index 0: the scroll body is opaque, so it must sit under the nav bar.
        addView(sv, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

    }

    public void onShown() { refresh(); }

    public void refresh() {
        root.removeAllViews();
        buildStorageGroup();
        buildLocationsGroup();
        buildAboutGroup();
    }

    // "Show Hidden Files" is a global preference, not a per-folder one, so it
    // lives on the page rather than on whichever list is currently mounted.
    public void setGlobalShowHidden(boolean on) {
        globalShowHidden = on;
        if (act.browsePage != null) act.browsePage.setShowHidden(on);
        if (act.recentsPage != null) act.recentsPage.refresh();
        if (act.searchPage != null) act.searchPage.refresh();
    }

    private void buildStorageGroup() {
        root.addView(Controls.sectionHeader(act, "Storage"));
        LinearLayout g = Controls.group(act);
        int inset = Apple.dp(act, 16f);
        g.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FileEntry.Storage st = FileEntry.storage();
        g.addView(Controls.settingsRow(act, "All Files Access",
                act.hasStoragePermission() ? "On" : "Off", null, v -> act.requestStorageAccess()));
        g.addView(Controls.insetDivider(act));
        g.addView(Controls.settingsRow(act, "Capacity",
                st.total > 0 ? FileOps.formatSize(st.total) : "Unknown", null, null));
        g.addView(Controls.insetDivider(act));
        g.addView(Controls.settingsRow(act, "Available",
                st.total > 0 ? FileOps.formatSize(st.free) : "Unknown", null, null));
        g.addView(Controls.insetDivider(act));

        g.addView(Controls.settingsRow(act, "Root Access (Magisk)", rootLabel(), null,
                v -> requestRoot()));
        g.addView(Controls.insetDivider(act));

        Controls.Toggle showHidden = new Controls.Toggle(act, globalShowHidden);
        showHidden.setChecked(globalShowHidden, false);
        g.addView(Controls.settingsRow(act, "Show Hidden Files", null, showHidden, v -> {
            boolean next = !showHidden.isChecked();
            showHidden.setChecked(next, false);
            setGlobalShowHidden(next);
            act.toast(next ? "Hidden files will appear" : "Hidden files hidden");
        }));

        wrap(g, inset);
        root.addView(g);
    }

    private String rootLabel() {
        switch (Root.state(act)) {
            case Root.GRANTED: return "Granted";
            case Root.DENIED: return "Denied";
            default: return "Tap to request";
        }
    }

    /** Triggers Magisk's own grant dialog, then re-reads the result. */
    private void requestRoot() {
        act.io().execute(() -> {
            final boolean ok = Root.request(act);
            act.runOnUiThread(() -> {
                refresh();
                act.toast(ok ? "Root access granted"
                        : "Root access denied — “..” stops at " + BrowsePage.ROOT);
            });
        });
    }

    private void buildLocationsGroup() {
        root.addView(Controls.sectionHeader(act, "Locations"));

        String[][] locs = {
                {"Internal Storage", "/storage/emulated/0"},
                {"Downloads", "/storage/emulated/0/Download"},
                {"Documents", "/storage/emulated/0/Documents"},
                {"Pictures", "/storage/emulated/0/DCIM"},
                {"Movies", "/storage/emulated/0/Movies"},
                {"Music", "/storage/emulated/0/Music"},
        };
        for (int i = 0; i < locs.length; i++) {
            final String[] loc = locs[i];
            final File dir = new File(loc[1]);
            LinearLayout g = Controls.group(act);
            boolean exists = dir.isDirectory();
            View row = Controls.settingsRow(act, loc[0], exists ? null : "Not found",
                    null, exists ? v -> gotoFolder(dir) : null);
            if (!exists) row.setAlpha(0.4f);
            g.addView(row);
            wrap(g, Apple.dp(act, 16f));
            if (i < locs.length - 1) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) g.getLayoutParams();
                lp.bottomMargin = Apple.dp(act, 8f);
                g.setLayoutParams(lp);
            }
            root.addView(g);
        }

        root.addView(Controls.sectionHeader(act, "System"));
        LinearLayout sys = Controls.group(act);
        sys.addView(Controls.settingsRow(act, "Android Settings", null, null, v -> {
            try {
                act.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable t) {
                act.toast("Settings unavailable");
            }
        }));
        wrap(sys, Apple.dp(act, 16f));
        root.addView(sys);
    }

    private void buildAboutGroup() {
        root.addView(Controls.sectionHeader(act, "About"));
        LinearLayout g = Controls.group(act);
        g.addView(Controls.settingsRow(act, "Version",
                BuildConfig.VERSION_NAME, null, null));
        g.addView(Controls.insetDivider(act));
        g.addView(Controls.settingsRow(act, "Design",
                "Apple HIG · Inkscape", null, null));
        g.addView(Controls.insetDivider(act));
        g.addView(Controls.settingsRow(act, "Icons", "60 vector symbols", null, null));
        wrap(g, Apple.dp(act, 16f));
        root.addView(g);
    }

    private void wrap(LinearLayout g, int inset) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = inset;
        lp.rightMargin = inset;
        g.setLayoutParams(lp);
    }

    private void gotoFolder(File dir) {
        act.tabBar.select(MainActivity.TAB_BROWSE, true);
        act.browsePage.revealIntentUri(Uri.fromFile(dir));
    }
}
