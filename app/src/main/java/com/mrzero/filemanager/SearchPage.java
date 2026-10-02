package com.mrzero.filemanager;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** "Search": an inline search field with recursive, name-substring matching. */
public class SearchPage extends FrameLayout implements FileAdapter.Listener {

    private static final int MAX_RESULTS = 300;
    private static final int MAX_DEPTH = 6;

    private final MainActivity act;
    private final NavBar nav;
    private final EditText input;
    private final ListSurface surface;
    private final TextView status;
    private final LinearLayout chrome;
    private View emptyView;
    private int topInset, bottomInset;
    private final List<String> history = new ArrayList<>();
    private LinearLayout scopeRow;
    private LinearLayout scopeHost;
    private File scope;
    private java.util.concurrent.Future<?> pending;

    public SearchPage(MainActivity act) {
        super(act);
        this.act = act;

        nav = new NavBar(act);
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(act)));

        // Fixed chrome: search field, scope chips and status. It is its own band
        // between the nav bar and the results, so the list is clipped below it
        // and nothing is drawn on top of the rows.
        chrome = new LinearLayout(act);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setBackgroundColor(0xFF000000);
        LayoutParams chromeTop = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT, Gravity.TOP);
        // The band starts under the whole bar (nav line + title + path), not at
        // the top of the page, so it can never overlap the pinned title.
        chromeTop.topMargin = NavBar.expandedHeightFor(act);
        // Inserted at 0 so the opaque list added later cannot paint over the bar.
        addView(chrome, 0, chromeTop);

        topInset = NavBar.expandedHeightFor(act);
        bottomInset = act.tabBar.contentInset();

        // The search field replaces the large title once the tab is shown.
        input = new EditText(act);
        input.setHint("Search");
        input.setHintTextColor(0xFF8E8E93);
        input.setTextColor(0xFFFFFFFF);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f);
        input.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(act, 10f)));
        input.setSingleLine(true);
        input.setLetterSpacing(-0.011f);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setPadding(Apple.dp(act, 12f), Apple.dp(act, 9f),
                Apple.dp(act, 12f), Apple.dp(act, 9f));
        input.setCursorVisible(true);

        android.widget.ImageView magnifier = new android.widget.ImageView(act);
        magnifier.setImageResource(R.drawable.ic_tab_search);
        magnifier.setColorFilter(0xFF8E8E93, android.graphics.PorterDuff.Mode.SRC_IN);
        FrameLayout fieldWrap = new FrameLayout(act);
        LayoutParams magLp = new LayoutParams(Apple.dp(act, 15f), Apple.dp(act, 15f),
                Gravity.CENTER_VERTICAL | Gravity.START);
        magLp.leftMargin = Apple.dp(act, 10f);
        fieldWrap.addView(magnifier, magLp);
        LayoutParams inLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        inLp.leftMargin = Apple.dp(act, 24f);
        fieldWrap.addView(input, inLp);
        LayoutParams fieldLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                Apple.dp(act, 36f), Gravity.TOP | Gravity.START);
        fieldLp.leftMargin = Apple.dp(act, 16f);
        fieldLp.rightMargin = Apple.dp(act, 16f);
        LayoutParams chromeLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT);
        chrome.addView(fieldWrap, chromeLp);
        fieldWrap.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(act, 10f)));

        status = new TextView(act);
        status.setTextColor(Apple.LABEL_2);
        status.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        status.setIncludeFontPadding(false);
        status.setBackgroundColor(0xFF000000);
        status.setPadding(Apple.dp(act, 16f), Apple.dp(act, 7f),
                Apple.dp(act, 16f), Apple.dp(act, 7f));
        chrome.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        surface = new ListSurface(act);
        surface.setListener(this);
        surface.setBottomInset(bottomInset + Apple.dp(act, 12f));
        addView(surface, 0, new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        // Both bands are wrap_content, so their real height is only known after
        // the first pass; the list is then clipped to start right below them.
        chrome.post(() -> surface.setTopInset(topInset + chrome.getHeight()));

        surface.list.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView rv, int dx, int dy) {
                nav.setCollapseProgress(surface.scrolledDistance()
                        / Apple.dp(act, Apple.LARGE_TITLE_DP));
            }
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                scheduleSearch(s.toString());
            }
        });

        nav.setLargeTitle("Search");
        nav.setPathLine("Search all files");
        showScopeRow();
    }

    public void onShown() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                act.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        }
        runSearch(input.getText().toString());
    }

    public void setShowHidden(boolean on) {
        surface.adapter.setShowHidden(on);
    }

    public void refresh() {
        runSearch(input.getText().toString());
    }

    /** Scope chips let the user narrow the walk to one volume or folder. */
    private void showScopeRow() {
        // Rebuilding the chips must not stack a second row on top of the first.
        if (scopeHost != null) chrome.removeView(scopeHost);
        LinearLayout scopeRowLocal = new LinearLayout(act);
        scopeRowLocal.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow = scopeRowLocal;
        scope = new File(BrowsePage.ROOT).exists() ? new File(BrowsePage.ROOT)
                : android.os.Environment.getExternalStorageDirectory();

        addScopeChip(scopeRowLocal, "All Files", scope);
        addScopeChip(scopeRowLocal, "Downloads", new File(scope, "Download"));
        addScopeChip(scopeRowLocal, "Documents", new File(scope, "Documents"));
        addScopeChip(scopeRowLocal, "DCIM", new File(scope, "DCIM"));

        LinearLayout host = new LinearLayout(act);
        host.setOrientation(LinearLayout.VERTICAL);
        host.addView(scopeRowLocal);
        scopeHost = host;

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Apple.dp(act, 8f);
        chrome.addView(host, lp);
        // A different chip set can change the band height, so re-clip the list.
        chrome.post(() -> surface.setTopInset(topInset + chrome.getHeight()));
    }

    private void addScopeChip(LinearLayout parent, String label, File dir) {
        TextView chip = new TextView(act);
        chip.setText(label);
        chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f);
        chip.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        chip.setIncludeFontPadding(false);
        chip.setPadding(Apple.dp(act, 12f), Apple.dp(act, 6f),
                Apple.dp(act, 12f), Apple.dp(act, 6f));
        boolean active = dir.equals(scope);
        chip.setTextColor(active ? 0xFF000000 : 0xFFEBEBF5);
        chip.setBackground(Apple.round(active ? 0xFF0A84FF : 0xFF1C1C1E,
                Apple.dp(act, 15f)));
        chip.setClickable(true);
        chip.setFocusable(true);
        if (!dir.exists()) {
            chip.setAlpha(0.35f);
            chip.setEnabled(false);
        }
        chip.setOnClickListener(v -> {
            scope = dir;
            showScopeRow();
            runSearch(input.getText().toString());
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Apple.dp(act, 16f);
        parent.addView(chip, lp);
    }

    private void scheduleSearch(String q) {
        if (pending != null) pending.cancel(true);
        // The query doubles as the path line, so the bar always says what is
        // being searched.
        nav.setPathLine(q == null || q.trim().isEmpty()
                ? "Search all files" : "“" + q.trim() + "”");
        if (q == null || q.trim().isEmpty()) {
            surface.adapter.submit(new ArrayList<>());
            showInitial();
            return;
        }
        pending = act.io().submit(() -> search(q, scope));
    }

    private void runSearch(String q) {
        if (q == null || q.trim().isEmpty()) {
            surface.adapter.submit(new ArrayList<>());
            showInitial();
            return;
        }
        act.io().execute(() -> search(q, scope));
    }

    private void search(String q, final File from) {
        if (from == null || !from.isDirectory()) {
            act.runOnUiThread(() -> showNoScope());
            return;
        }
        final String query = q.trim();
        if (!history.contains(query)) history.add(0, query);
        final boolean includeHidden = surface.adapter.isShowHidden();
        List<FileEntry> hits = FileEntry.search(from, query, includeHidden,
                MAX_RESULTS, MAX_DEPTH);
        act.runOnUiThread(() -> {
            surface.adapter.submit(hits);
            if (hits.isEmpty()) {
                showNoResults(query);
            } else {
                hideEmpty();
                status.setVisibility(VISIBLE);
                status.setText(hits.size() + (hits.size() == 1
                        ? " result for “" + query + "”"
                        : " results for “" + query + "”")
                        + (hits.size() >= MAX_RESULTS ? " (showing first " + MAX_RESULTS + ")" : ""));
            }
        });
    }

    private void showInitial() {
        hideEmpty();
        surface.adapter.submit(new ArrayList<>());
        emptyView = DetailPane.emptyState(act, R.drawable.ic_tab_search, "Search Files",
                "Type a name to search " + (scope == null ? "this device" : scope.getName()) + ".");
        addView(emptyView, new LayoutParams(LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        status.setVisibility(GONE);
    }

    private void showNoResults(String q) {
        surface.adapter.submit(new ArrayList<>());
        showEmptyFor(act.getString(R.string.no_results, q),
                "Try a different name or search a wider scope.");
    }

    private void showNoScope() {
        surface.adapter.submit(new ArrayList<>());
        showEmptyFor("Folder Unavailable", "This location is not readable.");
    }

    private void showEmptyFor(String title, String sub) {
        hideEmpty();
        emptyView = DetailPane.emptyState(act, R.drawable.ic_tab_search, title, sub);
        addView(emptyView, new LayoutParams(LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        status.setVisibility(GONE);
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
            act.tabBar.select(MainActivity.TAB_BROWSE, true);
            act.browsePage.revealIntentUri(android.net.Uri.fromFile(e.file));
        } else {
            Actions.open(act, e);
        }
    }

    @Override
    public void onMenu(FileEntry e, int position, View anchor) {
        Actions.showMenu(act, e, anchor, () ->
                runSearch(input.getText().toString()));
    }

    @Override
    public void onSelectionChanged() { }
}
