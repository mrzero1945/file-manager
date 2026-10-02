package com.mrzero.filemanager;

import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** "Storage": capacity ring plus a per-category breakdown, iOS Settings style. */
public class StoragePage extends FrameLayout {

    private final MainActivity act;
    private final NavBar nav;
    private final StorageChart chart;
    private final TextView freeLabel;
    private final TextView usedLabel;
    private final TextView stampLabel;
    private final LinearLayout listBox;
    private int topInset, bottomInset;

    /**
     * Last measurement currently on screen. {@code stamp} is when it was taken,
     * so a slow scan that started earlier can never clobber a newer result.
     */
    private long stamp;
    private boolean scanning;

    private static final String CACHE = "storage_cache";
    private static final String K_STAMP = "stamp";
    private static final String K_TOTAL = "total";
    private static final String K_FREE = "free";
    private static final String K_BYTES = "bytes";
    /** Numbers older than this are shown as cached rather than trusted. */
    private static final long STALE_MS = 10 * 60_000L;

    public StoragePage(MainActivity act) {
        super(act);
        this.act = act;

        nav = new NavBar(act);
        nav.setLargeTitle("Storage");
        nav.setPathLine("Device capacity and categories");
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(act)));

        topInset = NavBar.expandedHeightFor(act);
        bottomInset = act.tabBar.contentInset();

        LinearLayout scroll = new LinearLayout(act);
        scroll.setOrientation(LinearLayout.VERTICAL);
        scroll.setPadding(0, topInset, 0, bottomInset + Apple.dp(act, 12f));

        int chartSize = Math.min(Apple.dp(act, 200f),
                act.getResources().getDisplayMetrics().widthPixels - Apple.dp(act, 120f));
        chart = new StorageChart(act);
        FrameLayout chartWrap = new FrameLayout(act);
        FrameLayout.LayoutParams chartLp = new FrameLayout.LayoutParams(chartSize, chartSize,
                Gravity.CENTER_HORIZONTAL);
        chartWrap.addView(chart, chartLp);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.topMargin = Apple.dp(act, 8f);
        wrapLp.bottomMargin = Apple.dp(act, 4f);
        scroll.addView(chartWrap, wrapLp);

        freeLabel = new TextView(act);
        freeLabel.setTextColor(0xFFFFFFFF);
        freeLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        freeLabel.setGravity(Gravity.CENTER);
        freeLabel.setIncludeFontPadding(false);
        freeLabel.setPadding(0, Apple.dp(act, 10f), 0, 0);
        scroll.addView(freeLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        usedLabel = new TextView(act);
        usedLabel.setTextColor(0x99EBEBF5);
        usedLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        usedLabel.setGravity(Gravity.CENTER);
        usedLabel.setIncludeFontPadding(false);
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.bottomMargin = Apple.dp(act, 2f);
        scroll.addView(usedLabel, ulp);

        stampLabel = new TextView(act);
        stampLabel.setTextColor(0x60EBEBF5);
        stampLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        stampLabel.setGravity(Gravity.CENTER);
        stampLabel.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Apple.dp(act, 18f);
        scroll.addView(stampLabel, slp);

        listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.leftMargin = Apple.dp(act, 16f);
        listLp.rightMargin = Apple.dp(act, 16f);
        scroll.addView(listBox, listLp);

        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        // re-parent: ScrollView gives the vertical scrolling the page needs
        removeView(scroll);
        sv.setFillViewport(true);
        sv.setBackgroundColor(0xFF000000);
        sv.setOverScrollMode(OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(scroll, new android.widget.ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // Index 0 for the same reason: the scroll body is opaque black.
        addView(sv, 0, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        sv.setOnScrollChangeListener(
                (v, x, y, ox, oy) -> nav.setCollapseProgress(
                        Math.max(0f, y) / Apple.dp(act, Apple.LARGE_TITLE_DP)));

        nav.addTrailing(Controls.iconButton(act, R.drawable.ic_refresh, 0xFF0A84FF,
                "Refresh", v -> refresh()));
    }

    /**
     * Shows any stored numbers straight away, then measures again. The scan walks
     * the whole volume, so the page is never blank while one is already in flight
     * and a background rescan never blocks the tab switch.
     */
    public void onShown() {
        if (stamp == 0) {
            long[] cached = readCache();
            if (cached != null) {
                stamp = prefs().getLong(K_STAMP, 0);
                render(FileEntry.categoriesFrom(cached), stamp, true);
            } else {
                freeLabel.setText("Calculating\u2026");
            }
        }
        refresh();
    }

    public void refresh() {
        if (scanning) return;
        scanning = true;
        final long started = System.currentTimeMillis();
        act.io().execute(() -> {
            final FileEntry.Storage st = FileEntry.storage();
            final List<FileEntry.Category> cats = FileEntry.categories();
            final long done = System.currentTimeMillis();
            act.runOnUiThread(() -> {
                scanning = false;
                // Only a strictly newer measurement may replace what is on screen.
                if (done <= stamp) return;
                stamp = done;
                writeCache(FileEntry.bytesOf(cats), st, stamp);
                render(cats, stamp, false);
            });
        });
    }

    private void render(List<FileEntry.Category> cats, long when, boolean fromCache) {
        if (cats == null || cats.isEmpty()) {
            freeLabel.setText("Storage unavailable");
            usedLabel.setText("Grant All files access to view capacity");
            stampLabel.setText("");
            chart.setData(new float[0], new int[0]);
            chart.setCenter("", "");
            listBox.removeAllViews();
            return;
        }

        long total = 0, free = 0;
        for (FileEntry.Category c : cats) {
            if (c.colorIndex == 6) {
                // "Other" is the remainder, so recover capacity from it.
                long counted = 0;
                for (FileEntry.Category o : cats) {
                    if (o.colorIndex != 6) counted += o.bytes;
                }
                total = counted + c.bytes;
            }
        }
        FileEntry.Storage st = FileEntry.storage();
        if (st.total > 0) {
            total = st.total;
            free = st.free;
        }

        chart.setCenter(total > 0
                ? String.format(Locale.US, "%d%%", Math.round((total - free) * 100.0 / total))
                : "", "used");
        freeLabel.setText(String.format(Locale.US, "%s available of %s",
                FileOps.formatSize(free), FileOps.formatSize(total)));
        usedLabel.setText(FileOps.formatSize(Math.max(0, total - free)) + " used of "
                + FileOps.formatSize(total));

        long age = System.currentTimeMillis() - when;
        boolean stale = age > STALE_MS;
        stampLabel.setText(fromCache || stale
                ? "Cached \u00b7 updated " + relative(age) + " ago"
                : "Updated " + relative(age) + " ago");
        stampLabel.setTextColor(fromCache || stale ? 0x80FF9F0A : 0x60EBEBF5);

        float[] values = new float[cats.size()];
        int[] colors = new int[cats.size()];
        for (int i = 0; i < cats.size(); i++) {
            values[i] = cats.get(i).bytes;
            colors[i] = Apple.CATEGORY_HUE[cats.get(i).colorIndex % Apple.CATEGORY_HUE.length];
        }
        chart.setData(values, colors);

        listBox.removeAllViews();
        listBox.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(act, 10f)));
        for (int i = 0; i < cats.size(); i++) {
            final FileEntry.Category c = cats.get(i);
            int accent = Apple.CATEGORY_HUE[c.colorIndex % Apple.CATEGORY_HUE.length];

            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int padH = Apple.dp(act, 14f);
            row.setPadding(padH, Apple.dp(act, 10f), padH, Apple.dp(act, 10f));
            row.setMinimumHeight(Apple.dp(act, 52f));

            ImageView ic = new ImageView(act);
            ic.setImageResource(resFor(c.iconName));
            ic.setColorFilter(accent, android.graphics.PorterDuff.Mode.SRC_IN);
            row.addView(ic, new LinearLayout.LayoutParams(Apple.dp(act, 20f),
                    Apple.dp(act, 20f)));

            TextView label = new TextView(act);
            label.setText(c.label);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
            label.setLetterSpacing(-0.011f);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = Apple.dp(act, 12f);
            row.addView(label, llp);

            View spacer = new View(act);
            row.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView size = new TextView(act);
            size.setText(FileOps.formatSize(c.bytes));
            size.setTextColor(0x99EBEBF5);
            size.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
            size.setIncludeFontPadding(false);
            row.addView(size);

            if (c.dir != null && c.dir.isDirectory()) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setBackground(Controls.pressable(0xFF2C2C2E));
                row.setOnClickListener(v -> {
                    act.tabBar.select(MainActivity.TAB_BROWSE, true);
                    act.browsePage.revealIntentUri(
                            android.net.Uri.fromFile(c.dir));
                });
            }
            listBox.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < cats.size() - 1) {
                View div = Controls.insetDivider(act);
                div.setBackgroundColor(0x3FD4D4D6);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Apple.hairline(act));
                dlp.leftMargin = Apple.dp(act, 46f);
                listBox.addView(div, dlp);
            }
        }
    }

    private android.content.SharedPreferences prefs() {
        return act.getSharedPreferences(CACHE, android.content.Context.MODE_PRIVATE);
    }

    /** Per-category byte counts from the last run, or null when never measured. */
    private long[] readCache() {
        android.content.SharedPreferences p = prefs();
        if (!p.contains(K_STAMP) || p.getLong(K_STAMP, 0) <= 0) return null;
        String csv = p.getString(K_BYTES, null);
        if (csv == null || csv.isEmpty()) return null;
        String[] parts = csv.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    private void writeCache(long[] bytes, FileEntry.Storage st, long when) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bytes[i]);
        }
        prefs().edit()
                .putLong(K_STAMP, when)
                .putLong(K_TOTAL, st.total)
                .putLong(K_FREE, st.free)
                .putString(K_BYTES, sb.toString())
                .apply();
    }

    private static String relative(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s < 60) return s + " sec";
        long m = s / 60;
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 24) return h + " hr";
        return (h / 24) + " day";
    }

    private int resFor(String iconName) {
        switch (iconName) {
            case "file_image": return R.drawable.ic_file_image;
            case "file_video": return R.drawable.ic_file_video;
            case "file_audio": return R.drawable.ic_file_audio;
            case "file_text": return R.drawable.ic_file_text;
            case "file_apk": return R.drawable.ic_file_apk;
            case "tray": return R.drawable.ic_tray;
            case "drive": return R.drawable.ic_drive;
            default: return R.drawable.ic_file;
        }
    }
}
