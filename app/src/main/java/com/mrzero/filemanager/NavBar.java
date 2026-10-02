package com.mrzero.filemanager;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The top bar: the navigation controls and the screen title live in one opaque
 * unit, with the folder path (or a count/status line) directly under the title.
 *
 * Vertical structure, top to bottom:
 *
 *     [ status bar inset ]
 *     [ nav line, 44pt ]    back button ....... actions
 *     [ title, large ]
 *     [ path / subtitle, 22pt ]
 *     [ hairline ]
 *
 * The bar never resizes and never scrolls, and the list below it is clipped to
 * start under the hairline, so title, bar and scroll view are three separate
 * layers that can never overlap. The rows simply begin underneath the bar.
 */
public class NavBar extends FrameLayout {

    public interface OnBack {
        void onBack();
    }

    /** Height of the title block: large title line plus the path line. */
    public static final int TITLE_BLOCK_DP = 76;

    private final TextView largeTitle;
    private final TextView pathLine;
    private final View hairline;
    private final LinearLayout leading, trailing;
    private final FrameLayout navLine;
    private OnBack onBack;

    public NavBar(Context ctx) {
        super(ctx);
        int status = Apple.statusBarHeight(ctx);
        setClipToPadding(false);
        setClipChildren(true);
        setWillNotDraw(false);
        // Opaque: the content is clipped away below, so the bar reads as its own
        // layer instead of the list showing through it.
        setBackgroundColor(Apple.BG);

        // Nav line: the only row holding buttons, so it owns the 44pt height and
        // the actions centre in it.
        navLine = new FrameLayout(ctx);
        LayoutParams lineLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                Apple.dp(ctx, Apple.NAV_BAR_DP));
        lineLp.gravity = Gravity.TOP;
        lineLp.topMargin = status;
        addView(navLine, lineLp);

        leading = new LinearLayout(ctx);
        leading.setOrientation(LinearLayout.HORIZONTAL);
        leading.setGravity(Gravity.CENTER_VERTICAL);
        navLine.addView(leading, new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT));

        trailing = new LinearLayout(ctx);
        trailing.setOrientation(LinearLayout.HORIZONTAL);
        trailing.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams trailLp = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT);
        trailLp.gravity = Gravity.END;
        navLine.addView(trailing, trailLp);

        // Large title, directly under the nav line.
        largeTitle = new TextView(ctx);
        largeTitle.setTextColor(Apple.LABEL);
        largeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, Apple.LARGE_TITLE_SP);
        largeTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        largeTitle.setLetterSpacing(-0.022f);
        largeTitle.setIncludeFontPadding(false);
        largeTitle.setSingleLine(true);
        largeTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams ltLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                Apple.dp(ctx, 52f));
        ltLp.gravity = Gravity.TOP;
        ltLp.topMargin = status + Apple.dp(ctx, Apple.NAV_BAR_DP);
        ltLp.leftMargin = Apple.dp(ctx, 16f);
        ltLp.rightMargin = Apple.dp(ctx, 16f);
        addView(largeTitle, ltLp);

        // Folder path / status line, right under the title.
        pathLine = new TextView(ctx);
        pathLine.setTextColor(Apple.LABEL_2);
        pathLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        pathLine.setIncludeFontPadding(false);
        pathLine.setSingleLine(true);
        pathLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams pLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                Apple.dp(ctx, 24f));
        pLp.gravity = Gravity.TOP;
        pLp.topMargin = status + Apple.dp(ctx, Apple.NAV_BAR_DP) + Apple.dp(ctx, 52f);
        pLp.leftMargin = Apple.dp(ctx, 16f);
        pLp.rightMargin = Apple.dp(ctx, 16f);
        addView(pathLine, pLp);

        // Bottom edge of the bar: the boundary with the content below.
        hairline = new View(ctx);
        hairline.setBackgroundColor(Apple.SEPARATOR);
        LayoutParams hLp = new LayoutParams(LayoutParams.MATCH_PARENT, Apple.hairline(ctx));
        hLp.gravity = Gravity.BOTTOM;
        addView(hairline, hLp);
    }

    /** Height a page must reserve so its content clears the whole bar. */
    public static int expandedHeightFor(Context ctx) {
        return Apple.statusBarHeight(ctx) + Apple.dp(ctx, Apple.NAV_BAR_DP)
                + Apple.dp(ctx, TITLE_BLOCK_DP);
    }

    public void setLargeTitle(CharSequence text) {
        largeTitle.setText(text);
    }

    /** The line under the title: the current folder's path, or a status line. */
    public void setPathLine(CharSequence text) {
        if (text == null || text.length() == 0) {
            pathLine.setVisibility(INVISIBLE);
        } else {
            pathLine.setVisibility(VISIBLE);
            pathLine.setText(text);
        }
    }

    public void setOnBack(OnBack b) { this.onBack = b; }

    public void addLeading(View v) { leading.addView(v); }

    public void addTrailing(View v) { trailing.addView(v); }

    public void clearLeading() { leading.removeAllViews(); }

    public void clearTrailing() { trailing.removeAllViews(); }

    public LinearLayout trailingBox() { return trailing; }

    /** Nothing to collapse any more: the bar is fixed and always fully shown. */
    public void setCollapseProgress(float p) { }

    public boolean isCollapsed() { return false; }

    /** Text field used for the iOS-style inline search bar. */
    public static LinearLayout searchField(Context ctx) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Apple.dp(ctx, 7f), 0, Apple.dp(ctx, 7f), 0);
        box.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 10f)));

        android.widget.ImageView magnifier = new android.widget.ImageView(ctx);
        magnifier.setImageResource(R.drawable.ic_tab_search);
        magnifier.setColorFilter(Apple.GRAY, android.graphics.PorterDuff.Mode.SRC_IN);
        box.addView(magnifier, new LinearLayout.LayoutParams(
                Apple.dp(ctx, 15f), Apple.dp(ctx, 15f)));

        android.widget.EditText input = new android.widget.EditText(ctx);
        input.setHint("Search");
        input.setHintTextColor(Apple.GRAY);
        input.setTextColor(Apple.LABEL);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        input.setBackgroundColor(0x00000000);
        input.setSingleLine(true);
        input.setLetterSpacing(-0.011f);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        input.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(input);
        return box;
    }
}
