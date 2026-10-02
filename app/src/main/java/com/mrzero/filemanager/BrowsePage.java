package com.mrzero.filemanager;

import android.text.Editable;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The "Browse" tab: a stack of directory levels pushed like an iOS navigation
 * controller. Back pops one level at a time; the large title collapses as the
 * list scrolls under the bar.
 */
public class BrowsePage extends FrameLayout implements FileAdapter.Listener {

    public static final String ROOT = "/storage/emulated/0";

    private final MainActivity act;
    private final NavBar nav;
    private final List<Level> stack = new ArrayList<>();
    private LinearLayout selectionBar;
    private TextView selectionTitle;
    private LinearLayout selectionActions;
    private View emptyView;
    private int topInset, bottomInset;
    private Level navLevel;
    /** Whether the nav bar was last built for selection mode, so we rebuild on flip. */
    private boolean navSelecting;
    /** Whether that build included a Select button; the listing can arrive after it. */
    private boolean navSelectable;
    /** Whether that build offered "Deselect All", which flips when everything is picked. */
    private boolean navAllSelected;
    private android.widget.EditText searchField;
    private View searchBand;

    private static final class Level {
        final File dir;
        final ListSurface surface;
        final String title;
        /** Height of the first visible row's top, used to restore scroll. */
        int firstItem;
        int firstOffset;
        /** Full listing as read from disk, before the in-folder filter. */
        List<FileEntry> loaded;
        /** Name filter typed into the search field; "" means show everything. */
        String query = "";

        Level(ContextHack ctx, File dir, String title) {
            this.dir = dir;
            this.title = title;
            this.surface = new ListSurface(ctx.ctx);
        }
    }

    /** Tiny holder so Level can reach the Context without capturing the page. */
    private static final class ContextHack {
        final android.content.Context ctx;

        ContextHack(android.content.Context c) { ctx = c; }
    }

    public BrowsePage(MainActivity act) {
        super(act);
        this.act = act;

        nav = new NavBar(act);
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(act)));

        topInset = NavBar.expandedHeightFor(act);
        bottomInset = act.tabBar.contentInset();

        buildSearchField(act);
        buildSelectionBar(act);

        final File root = new File(ROOT).exists()
                ? new File(ROOT)
                : Environment.getExternalStorageDirectory();
        push(root, false);
    }

    /**
     * A name field that filters the folder on screen. The whole-device walk lives
     * in the Search tab; this one only narrows what is already listed, so it can
     * answer on every keystroke without touching the disk.
     */
    private void buildSearchField(MainActivity act) {
        searchField = new android.widget.EditText(act);
        searchField.setHint("Search in this folder");
        searchField.setHintTextColor(0xFF8E8E93);
        searchField.setTextColor(0xFFFFFFFF);
        searchField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f);
        searchField.setBackground(Design.round(0xFF1C1C1E, Design.dp(act, 10f)));
        searchField.setSingleLine(true);
        searchField.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchField.setPadding(Design.dp(act, 12f), Design.dp(act, 8f),
                Design.dp(act, 12f), Design.dp(act, 8f));

        android.widget.ImageView magnifier = new android.widget.ImageView(act);
        magnifier.setImageResource(R.drawable.ic_tab_search);
        magnifier.setColorFilter(0xFF8E8E93, android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout wrap = new FrameLayout(act);
        LayoutParams magLp = new LayoutParams(Design.dp(act, 15f), Design.dp(act, 15f),
                Gravity.CENTER_VERTICAL | Gravity.START);
        magLp.leftMargin = Design.dp(act, 10f);
        wrap.addView(magnifier, magLp);
        LayoutParams inLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = Design.dp(act, 24f);
        wrap.addView(searchField, inLp);
        wrap.setBackground(Design.round(0xFF1C1C1E, Design.dp(act, 10f)));

        searchBand = wrap;
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                Design.dp(act, 36f), Gravity.TOP);
        // Sits under the whole bar, so it can never cover the pinned title.
        lp.topMargin = NavBar.expandedHeightFor(act) + Design.dp(act, 8f);
        lp.leftMargin = Design.dp(act, 16f);
        lp.rightMargin = Design.dp(act, 16f);
        addView(wrap, lp);

        searchField.setOnEditorActionListener((v, actionId, ev) -> {
            hideKeyboard();
            return true;
        });
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                onQueryTyped(s.toString());
            }
        });
    }

    private void onQueryTyped(String text) {
        if (stack.isEmpty()) return;
        Level level = top();
        if (text.equals(level.query)) return;
        level.query = text;
        applyFilter(level);
        if (text.trim().isEmpty()) hideEmpty();
    }

    /** Each level keeps its own filter, so the field follows the navigation. */
    private void syncSearchField() {
        if (searchField == null || stack.isEmpty()) return;
        String q = top().query;
        if (q.equals(searchField.getText().toString())) return;
        searchField.setText(q);
        searchField.setSelection(q.length());
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(searchField.getWindowToken(), 0);
    }

    /** Height of the field plus the gap that separates it from the bar. */
    private int searchBandHeight() {
        if (searchBand == null) return 0;
        return searchBand.getHeight() > 0
                ? searchBand.getHeight() + Design.dp(act, 8f)
                : Design.dp(act, 36f) + Design.dp(act, 8f);
    }

    /** Clears the filter, e.g. when the folder view is refreshed from disk. */
    public void clearFilter() {
        if (searchField == null) return;
        searchField.setText("");
    }

    // ------------------------------------------------------------------
    // Navigation stack
    // ------------------------------------------------------------------
    public void push(File dir, boolean animate) {
        if (dir == null) return;
        if (stack.size() >= 32) {
            act.toast("Maximum folder depth reached");
            return;
        }
        final Level level = new Level(new ContextHack(act), dir, displayName(dir));
        stack.add(level);
        attach(level);
        if (animate) {
            // Slide the whole surface in from the right; the header row is part
            // of it, so the title travels with the rows.
            level.surface.setTranslationX(Math.max(1, getWidth()));
            level.surface.animate().translationX(0f)
                    .setDuration(330).setInterpolator(Draw.EASE_OUT).start();
        }
        load(level);
    }

    /**
     * Goes up one directory. Above the shared volume the stack has nothing to
     * pop, so the bottom level is re-pointed at the parent instead; that is what
     * lets ".." walk all the way up to the filesystem root.
     */
    public boolean goUp() {
        if (stack.size() > 1) {
            final Level leaving = stack.remove(stack.size() - 1);
            if (leaving.surface.getParent() instanceof ViewGroup) {
                ((ViewGroup) leaving.surface.getParent()).removeView(leaving.surface);
            }
            attach(stack.get(stack.size() - 1));
            load(stack.get(stack.size() - 1));
            return true;
        }
        final File parent = currentDir() == null ? null : currentDir().getParentFile();
        if (parent == null) return false;
        if (parent.getAbsolutePath().equals(currentDir().getAbsolutePath())) return false;
        if (parent.canRead()) {
            replaceBottom(parent);
            return true;
        }
        // Above the shared volume the platform refuses the read, so Magisk has to
        // say yes. That is asked on a worker thread: the grant dialog blocks until
        // the user answers and must never sit on the UI thread.
        act.io().execute(() -> {
            final boolean ok = Root.isGranted(act) || Root.request(act);
            act.runOnUiThread(() -> {
                if (ok) {
                    replaceBottom(parent);
                } else {
                    act.toast("Grant Magisk root access to browse above “"
                            + BrowsePage.ROOT + "”");
                }
            });
        });
        return true;
    }

    /** Swaps the bottom level for its parent, so the stack never grows upwards. */
    private void replaceBottom(File dir) {
        final Level leaving = stack.remove(0);
        if (leaving.surface.getParent() instanceof ViewGroup) {
            ((ViewGroup) leaving.surface.getParent()).removeView(leaving.surface);
        }
        navLevel = null;
        push(dir, false);
    }

    /** True only at the filesystem root, where there is nowhere left to go up. */
    public boolean isAtRoot() {
        File d = currentDir();
        return d == null || d.getParentFile() == null;
    }

    /**
     * True at the folder the app opens on. System back exits from here, while
     * the ".." row and the chevron keep climbing towards "/".
     */
    public boolean isAtStart() {
        File d = currentDir();
        return d == null || d.getAbsolutePath().equals(startDir().getAbsolutePath());
    }

    private File startDir() {
        return new File(ROOT).exists() ? new File(ROOT)
                : android.os.Environment.getExternalStorageDirectory();
    }

    public File currentDir() { return stack.isEmpty() ? null : top().dir; }

    private Level top() { return stack.get(stack.size() - 1); }

    private void attach(Level level) {
        if (level.surface.getParent() instanceof ViewGroup) {
            ((ViewGroup) level.surface.getParent()).removeView(level.surface);
        }
        // Only one level's surface may be in the hierarchy at a time, otherwise
        // the previous folder's list is drawn on top of the one we just pushed.
        for (Level other : stack) {
            if (other == level) continue;
            if (other.surface.getParent() instanceof ViewGroup) {
                ((ViewGroup) other.surface.getParent()).removeView(other.surface);
            }
        }
        if (emptyView != null) {
            removeView(emptyView);
            emptyView = null;
        }
        addView(level.surface, 0, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        // The list starts under the bar *and* the search band, so neither is
        // ever drawn over by a row.
        level.surface.setTopInset(topInset + Design.dp(act, 4f) + searchBandHeight());
        level.surface.setBottomInset(bottomInset + Design.dp(act, 12f));
        level.surface.adapter.setListener(this);
        level.surface.list.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                onListScrolled();
            }
        });
        updateNav(level);
    }

    /** Called after the tab becomes visible again. */
    public void onShown() {
        if (stack.isEmpty()) {
            File root = new File(ROOT).exists() ? new File(ROOT)
                    : Environment.getExternalStorageDirectory();
            push(root, false);
            return;
        }
        load(top());
    }

    public void refresh() {
        if (!stack.isEmpty()) load(top());
    }

    /** Applies to every level, so hidden files stay visible after navigating. */
    public void setShowHidden(boolean on) {
        for (Level l : stack) l.surface.adapter.setShowHidden(on);
        if (!stack.isEmpty()) load(top());
    }

    public boolean isShowHidden() {
        return !stack.isEmpty() && top().surface.adapter.isShowHidden();
    }

    public static void refreshCurrent(MainActivity act) {
        if (act.browsePage != null) act.browsePage.refresh();
        if (act.recentsPage != null) act.recentsPage.refresh();
        if (act.storagePage != null) act.storagePage.refresh();
    }

    /**
     * Opens a folder in Browse, replacing whatever stack is on screen. Used
     * after extraction so the user lands in the folder that was just created
     * instead of having to navigate to it.
     */
    public static void reveal(final MainActivity act, final File dir) {
        if (act == null || dir == null) return;
        act.runOnUiThread(() -> {
            act.tabBar.select(MainActivity.TAB_BROWSE, true);
            if (act.browsePage == null) return;
            act.browsePage.revealFolder(dir);
        });
    }

    /** Drops the stack and shows dir, without the "open with" intent handling. */
    public void revealFolder(File dir) {
        if (dir == null) return;
        if (!stack.isEmpty()) {
            View v = stack.get(stack.size() - 1).surface;
            if (v.getParent() instanceof ViewGroup) {
                ((ViewGroup) v.getParent()).removeView(v);
            }
        }
        stack.clear();
        navLevel = null;
        push(dir, false);
    }

    // ------------------------------------------------------------------
    private void load(final Level level) {
        final File dir = level.dir;
        act.io().execute(() -> {
            // ".." is offered whenever the folder has a parent to go to, which
            // includes the shared volume itself all the way up to "/".
            final boolean withParent = dir.getParentFile() != null;
            final int sort = level.surface.adapter.getSort();
            final boolean asc = level.surface.adapter.isAscending();
            final boolean hidden = level.surface.adapter.isShowHidden();
            final FileEntry.Listing listing = dir.canRead()
                    ? FileEntry.list(dir, sort, asc, hidden, withParent)
                    : rootList(level, dir, sort, asc, hidden, withParent);
            act.runOnUiThread(() -> {
                if (stack.isEmpty() || top() != level) return;
                if (listing.error != null) {
                    showEmpty(level, R.drawable.ic_folder, "Folder Unavailable",
                            listing.error);
                    return;
                }
                hideEmpty();
                level.loaded = listing.items;
                applyFilter(level);
                updateNav(level);
                // The bar is built when the level changes, which can happen long
                // before this listing arrives. Now that we know whether anything is
                // selectable, correct it if a Select button belongs there.
                FileAdapter a = level.surface.adapter;
                if (navLevel == level
                        && navSelectable != (a.isSelectionEnabled() && a.selectableCount() > 0)) {
                    buildNavActions();
                }
            });
        });
    }

    /**
     * Applies the in-folder name filter to the listing already on disk. Filtering
     * the loaded list rather than re-reading keeps typing instant and means a
     * root-only folder (read through the shell) is not walked again per keystroke.
     */
    private void applyFilter(Level level) {
        final List<FileEntry> all = level.loaded;
        if (all == null) return;
        final String q = level.query == null ? "" : level.query.trim();
        if (q.isEmpty()) {
            level.surface.adapter.submit(all);
            return;
        }
        final String needle = q.toLowerCase(java.util.Locale.getDefault());
        List<FileEntry> hits = new ArrayList<>();
        for (FileEntry e : all) {
            // ".." is navigation, not content, so it drops out while filtering.
            if (e.isParentRow) continue;
            if (e.name.toLowerCase(java.util.Locale.getDefault()).contains(needle)) {
                hits.add(e);
            }
        }
        level.surface.adapter.submit(hits);
        if (hits.isEmpty()) {
            showEmpty(level, R.drawable.ic_tab_search, "No Matches",
                    "Nothing in this folder is called “" + q + "”.");
        } else {
            hideEmpty();
        }
    }

    /**
     * Above /storage/emulated/0 the Java API is refused by the platform, so the
     * listing comes from the Magisk shell instead. Child counts are left
     * unknown there rather than costing one shell call per folder.
     */
    private FileEntry.Listing rootList(Level level, File dir, int sort, boolean asc,
                                       boolean hidden, boolean withParent) {
        if (!Root.isGranted(act) && !Root.request(act)) {
            return new FileEntry.Listing(null, 0, 0, 0L,
                    "Needs root access to open “" + dir.getAbsolutePath() + "”", false);
        }
        List<Root.Item> rows = Root.list(dir);
        if (rows == null) {
            return new FileEntry.Listing(null, 0, 0, 0L,
                    "Cannot read “" + dir.getAbsolutePath() + "”", false);
        }
        List<FileEntry> items = new ArrayList<>(rows.size() + 1);
        int folders = 0, files = 0;
        long bytes = 0;
        for (Root.Item it : rows) {
            if (it.name.startsWith(".") && !hidden) continue;
            FileEntry e = FileEntry.of(new File(dir, it.name), it.dir, it.size, it.modified);
            if (it.dir) folders++;
            else { files++; bytes += it.size; }
            items.add(e);
        }
        FileEntry.sort(items, sort, asc);
        if (withParent) items.add(0, FileEntry.parentEntry(dir));
        return new FileEntry.Listing(items, folders, files, bytes, null, false);
    }

    private void onListScrolled() {
        if (stack.isEmpty()) return;
        updateNav(top());
    }

    private void updateNav(Level level) {
        float scrolled = level.surface.scrolledDistance();
        level.firstItem = level.surface.lm.findFirstVisibleItemPosition();
        level.firstOffset = 0;
        // The large title block is 52pt tall, so the title has fully cleared the
        // nav line by the time that much has scrolled under the bar.
        nav.setCollapseProgress(scrolled / Design.dp(act, Design.LARGE_TITLE_DP));
        // Title, path and actions change with the level, not on every frame.
        if (navLevel != level) {
            navLevel = level;
            nav.setLargeTitle(level.title);
            nav.setPathLine(pathOf(level.dir));
            buildNavActions();
            syncSearchField();
        }
    }

    /** Full path of the open folder, shown under the title. */
    private static String pathOf(File dir) {
        return dir == null ? "" : dir.getAbsolutePath();
    }

    private void buildNavActions() {
        nav.clearLeading();
        nav.clearTrailing();

        final FileAdapter a = top().surface.adapter;
        navSelecting = a.isSelectionMode();
        navSelectable = a.isSelectionEnabled() && a.selectableCount() > 0;
        navAllSelected = false;

        if (!isAtRoot()) {
            nav.addLeading(Controls.iconButton(act, R.drawable.ic_chevron_left, 0xFF0A84FF,
                    "Up one folder", v -> goUp()));
        }

        // While selecting, the bar trades the view tools for the selection tools
        // so there is always a way out and a way to grab everything.
        if (navSelecting) {
            final boolean all = a.isAllSelected();
            navAllSelected = all;
            nav.addTrailing(Controls.iconButton(act, R.drawable.ic_select_all, 0xFF0A84FF,
                    all ? "Deselect All" : "Select All",
                    v -> {
                        if (all) a.clearSelection();
                        else a.selectAll();
                    }));
            nav.addTrailing(Controls.iconButton(act, R.drawable.ic_check, 0xFF0A84FF,
                    "Done", v -> a.clearSelection()));
            return;
        }

        if (navSelectable) {
            nav.addTrailing(Controls.iconButton(act, R.drawable.ic_select, 0xFF0A84FF,
                    "Select", v -> a.setSelectionMode(true)));
        }
        nav.addTrailing(Controls.iconButton(act, R.drawable.ic_tab_search, 0xFF0A84FF,
                "Search", v -> act.tabBar.select(MainActivity.TAB_SEARCH, true)));
        nav.addTrailing(Controls.iconButton(act, R.drawable.ic_sliders, 0xFF0A84FF,
                "View options", v -> showOptions()));
        nav.addTrailing(Controls.iconButton(act, R.drawable.ic_folder_plus, 0xFF0A84FF,
                "New folder", v -> Actions.newFolder(act, currentDir())));
    }

    private void showOptions() {
        final ListSurface s = top().surface;
        final FileAdapter a = s.adapter;
        List<Sheets.Option> opts = new ArrayList<>();
        opts.add(new Sheets.Option(a.isGridMode() ? "Show as List" : "Show as Icons", false,
                () -> {
                    a.setGridMode(!a.isGridMode());
                    act.toast(a.isGridMode() ? "Icon view" : "List view");
                }));
        if (a.isSelectionEnabled() && a.selectableCount() > 0) {
            opts.add(new Sheets.Option("Select All", false, () -> a.selectAll()));
        }
        String[] names = {"Sort by Name", "Sort by Date", "Sort by Size", "Sort by Kind"};
        for (int i = 0; i < names.length; i++) {
            final int sort = i;
            opts.add(new Sheets.Option(names[i], a.getSort() == sort, () -> {
                a.setSort(sort, a.isAscending());
                load(top());
            }));
        }
        opts.add(new Sheets.Option(a.isAscending() ? "Reverse Order" : "Normal Order", false,
                () -> {
                    a.setSort(a.getSort(), !a.isAscending());
                    load(top());
                }));
        opts.add(new Sheets.Option("Show Hidden Files", a.isShowHidden(),
                () -> {
                    a.setShowHidden(!a.isShowHidden());
                    load(top());
                }));
        if (Actions.hasClip()) {
            opts.add(new Sheets.Option(
                    Actions.clipIsMove() ? "Paste (moving)" : "Paste (copying)", false,
                    () -> Actions.paste(act, currentDir())));
        }
        Sheets.options(act, "Options", opts, null);
    }

    // ------------------------------------------------------------------
    // Selection bar
    // ------------------------------------------------------------------
    private void buildSelectionBar(android.content.Context ctx) {
        selectionBar = new LinearLayout(ctx);
        selectionBar.setOrientation(LinearLayout.VERTICAL);
        selectionBar.setBackground(Design.round(0xFF1C1C1E, Design.dp(ctx, 18f)));
        selectionBar.setElevation(Design.dp(ctx, 14f));
        selectionBar.setPadding(Design.dp(ctx, 14f), Design.dp(ctx, 11f),
                Design.dp(ctx, 14f), Design.dp(ctx, 11f));
        selectionBar.setVisibility(GONE);

        selectionTitle = new TextView(ctx);
        selectionTitle.setTextColor(0xFF0A84FF);
        selectionTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
        selectionTitle.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        selectionTitle.setLetterSpacing(-0.011f);
        selectionTitle.setIncludeFontPadding(false);
        selectionTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = Design.dp(ctx, 9f);
        selectionBar.addView(selectionTitle, tlp);

        selectionActions = new LinearLayout(ctx);
        selectionActions.setOrientation(LinearLayout.HORIZONTAL);
        selectionActions.setGravity(Gravity.CENTER);
        selectionBar.addView(selectionActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = bottomInset + Design.dp(ctx, 12f);
        lp.leftMargin = Design.dp(ctx, 20f);
        lp.rightMargin = Design.dp(ctx, 20f);
        addView(selectionBar, lp);
        populateSelectionActions();
    }

    private void populateSelectionActions() {
        selectionActions.removeAllViews();
        addAction(R.drawable.ic_share, "Share", () -> {
            Actions.share(act, top().surface.adapter.getSelected());
            act.toast("Shared " + top().surface.adapter.selectedCount() + " items");
            top().surface.adapter.clearSelection();
        });
        addAction(R.drawable.ic_folder, "Move", () -> {
            Actions.moveTo(act, top().surface.adapter.getSelected());
            top().surface.adapter.clearSelection();
        });
        addAction(R.drawable.ic_copy, "Copy", () -> {
            Actions.copyTo(act, top().surface.adapter.getSelected());
            top().surface.adapter.clearSelection();
        });
        addAction(R.drawable.ic_trash, "Delete", 0xFFFF453A, () -> {
            Actions.delete(act, top().surface.adapter.getSelected());
            top().surface.adapter.clearSelection();
        });
        addAction(R.drawable.ic_close, "Cancel", 0xFF8E8E93,
                () -> top().surface.adapter.clearSelection());
    }

    private void addAction(int icon, String label, Runnable onClick) {
        addAction(icon, label, Design.LABEL, onClick);
    }

    private void addAction(int icon, String label, int color, Runnable onClick) {
        android.content.Context ctx = getContext();
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setClickable(true);
        col.setFocusable(true);
        col.setContentDescription(label);
        col.setPadding(Design.dp(ctx, 6f), 0, Design.dp(ctx, 6f), 0);
        col.setBackground(Controls.pressable(0xFF2C2C2E));

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(icon);
        ic.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        col.addView(ic, new LinearLayout.LayoutParams(Design.dp(ctx, 21f), Design.dp(ctx, 21f)));

        TextView tv = new TextView(ctx);
        tv.setText(label);
        tv.setTextColor(color);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Design.dp(ctx, 3f);
        col.addView(tv, tlp);

        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Design.dp(ctx, 14f);
        col.setLayoutParams(clp);
        col.setOnClickListener(v -> onClick.run());
        selectionActions.addView(col);
    }

    @Override
    public void onSelectionChanged() {
        if (stack.isEmpty()) return;
        FileAdapter a = top().surface.adapter;
        // The nav bar swaps its whole set of buttons when entering or leaving
        // selection mode, and flips Select All to Deselect All once everything is
        // picked, so rebuild it on those transitions rather than on every tap.
        if (navSelecting != a.isSelectionMode() || navAllSelected != a.isAllSelected()) {
            buildNavActions();
        }
        int n = a.selectedCount();
        populateSelectionActions();
        if (n == 0) {
            selectionBar.animate().alpha(0f).translationY(Design.dp(act, 18f))
                    .setDuration(150)
                    .withEndAction(() -> selectionBar.setVisibility(GONE)).start();
            return;
        }
        selectionTitle.setText(n == 1 ? "1 Item Selected" : n + " Items Selected");
        if (selectionBar.getVisibility() != VISIBLE) {
            selectionBar.setAlpha(0f);
            selectionBar.setTranslationY(Design.dp(act, 18f));
            selectionBar.setVisibility(VISIBLE);
            selectionBar.animate().alpha(1f).translationY(0f)
                    .setDuration(250).setInterpolator(Draw.SPRING).start();
        }
    }

    // ------------------------------------------------------------------
    // FileAdapter.Listener
    @Override
    public void onOpen(FileEntry e, int position) {
        if (e.isParentRow) {
            goUp();
            return;
        }
        if (e.isFolder()) {
            // Unreadable folders are not rejected here: load() falls back to the
            // root shell and asks Magisk from its worker thread.
            push(e.file, true);
        } else {
            Actions.open(act, e);
        }
    }

    /**
     * Opens one entry for the context menu: folders descend in place, anything
     * else is handed to an external app. `onChanged` fires when the entry was
     * renamed, moved or deleted underneath us.
     */
    public static void openEntry(final MainActivity act, final FileEntry e,
                                 final Runnable onChanged) {
        MainActivity.runUi(act, () -> {
            if (e.isFolder()) {
                if (!e.file.canRead()) {
                    act.toast("Permission denied — grant All files access");
                    return;
                }
                BrowsePage p = act.browsePage;
                if (p == null) return;
                p.push(e.file, true);
            } else {
                Actions.open(act, e);
            }
            if (onChanged != null) onChanged.run();
        });
    }

    @Override
    public void onMenu(FileEntry e, int position, View anchor) {
        if (e.isParentRow) return;
        Actions.showMenu(act, e, anchor, () -> load(top()));
    }

    // ------------------------------------------------------------------
    private void showEmpty(Level level, int icon, String title, String sub) {
        hideEmpty();
        LinearLayout empty = DetailPane.emptyState(act, icon, title, sub);
        emptyView = empty;
        addView(empty, 1, new LayoutParams(LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void hideEmpty() {
        if (emptyView != null) {
            removeView(emptyView);
            emptyView = null;
        }
    }

    public boolean onBack() {
        if (!stack.isEmpty() && top().surface.adapter.isSelectionMode()) {
            top().surface.adapter.clearSelection();
            return true;
        }
        return false;
    }

    /** Handles "Open with" from another app: walk the stack to the target's folder. */
    public void revealIntentUri(Uri uri) {
        File f = localFileFor(uri);
        File dir = f != null && f.isFile() ? f.getParentFile() : f;
        if (dir == null || !dir.exists()) dir = new File(ROOT);
        final File target = dir;
        act.io().execute(() -> act.runOnUiThread(() -> {
            // Detach the visible level before dropping the stack, otherwise the
            // old surface would stay in the view hierarchy.
            if (!stack.isEmpty()) {
                View v = stack.get(stack.size() - 1).surface;
                if (v.getParent() instanceof ViewGroup) {
                    ((ViewGroup) v.getParent()).removeView(v);
                }
            }
            stack.clear();
            push(target, false);
        }));
    }

    private File localFileFor(Uri uri) {
        try {
            if ("file".equals(uri.getScheme())) return new File(uri.getPath());
            if (android.provider.DocumentsContract.isDocumentUri(act, uri)) {
                String id = android.provider.DocumentsContract.getDocumentId(uri);
                if (id != null && id.startsWith("primary:")) {
                    String rel = id.substring(8);
                    File root = Environment.getExternalStorageDirectory();
                    return rel.isEmpty() ? root : new File(root, rel);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String displayName(File f) {
        if (f == null) return "";
        // /storage/emulated/0 is literally named "0"; iOS calls the equivalent
        // "On My iPhone", and "Internal Storage" is the Android convention.
        String root = Environment.getExternalStorageDirectory().getAbsolutePath();
        if (f.getAbsolutePath().equals(root)) return "Internal Storage";
        String n = f.getName();
        if (n == null || n.isEmpty()) return "/";
        return n;
    }
}
