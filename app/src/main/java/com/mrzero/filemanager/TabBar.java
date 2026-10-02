package com.mrzero.filemanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The iOS tab bar: a translucent scrim, a hairline top border, 50pt of content
 * plus whatever the system gesture/navigation bar needs, and an icon over a
 * 10pt label. Selection is expressed with colour only, never a filled pill.
 *
 * The content band is measured explicitly so the labels always sit inside the
 * 50pt, never pushed down into the system inset.
 */
public class TabBar extends FrameLayout {

    public interface OnTab {
        void onTab(int index);
    }

    private static final int[] ICONS = {
            R.drawable.ic_tab_files, R.drawable.ic_tab_recents, R.drawable.ic_tab_search,
            R.drawable.ic_tab_storage, R.drawable.ic_tab_settings,
    };
    private static final String[] TITLES = {
            "Browse", "Recents", "Search", "Storage", "Settings",
    };

    private final ImageView[] icons = new ImageView[TITLES.length];
    private final TextView[] labels = new TextView[TITLES.length];

    private int current = 0;
    private OnTab callback;
    private int hairline;
    private int contentHeight;
    private int bottomInset;
    private final int totalHeight;

    public TabBar(Context ctx) {
        super(ctx);
        hairline = Apple.hairline(ctx);
        contentHeight = Apple.dp(ctx, Apple.TAB_BAR_DP);
        bottomInset = Apple.navBarHeight(ctx);
        totalHeight = contentHeight + bottomInset;
        Apple.applyBarScrim(this, 0xFF0C0C0E);
        setWillNotDraw(false);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LayoutParams rowLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, contentHeight);
        rowLp.gravity = Gravity.TOP;
        addView(row, rowLp);

        // Keeps the scrim opaque behind the system bar; nothing else lives here.
        View inset = new View(ctx);
        LayoutParams insetLp = new LayoutParams(LayoutParams.MATCH_PARENT, bottomInset);
        insetLp.gravity = Gravity.BOTTOM;
        addView(inset, insetLp);

        for (int i = 0; i < TITLES.length; i++) {
            LinearLayout slot = new LinearLayout(ctx);
            slot.setOrientation(LinearLayout.VERTICAL);
            slot.setGravity(Gravity.CENTER_HORIZONTAL);
            slot.setClickable(true);
            slot.setFocusable(true);
            slot.setContentDescription(TITLES[i]);

            ImageView ic = new ImageView(ctx);
            ic.setImageResource(ICONS[i]);
            ic.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                    Apple.dp(ctx, 25f), Apple.dp(ctx, 25f));
            ilp.topMargin = Apple.dp(ctx, 6f);
            slot.addView(ic, ilp);
            icons[i] = ic;

            TextView tv = new TextView(ctx);
            tv.setText(TITLES[i]);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, Apple.TAB_LABEL_SP);
            tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL));
            tv.setLetterSpacing(0.004f);
            tv.setIncludeFontPadding(false);
            tv.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = Apple.dp(ctx, 2.5f);
            slot.addView(tv, tlp);
            labels[i] = tv;

            final int index = i;
            slot.setOnClickListener(v -> select(index, true));
            row.addView(slot, new LinearLayout.LayoutParams(0,
                    LayoutParams.MATCH_PARENT, 1f));
        }

        select(0, false);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(
                totalHeight, View.MeasureSpec.EXACTLY));
    }

    /** Height the content area must leave free, so lists clear the tab bar. */
    public int contentInset() { return totalHeight; }

    public void setOnTab(OnTab cb) { this.callback = cb; }

    public void select(int index, boolean notify) {
        if (index < 0 || index >= TITLES.length) return;
        current = index;
        for (int i = 0; i < TITLES.length; i++) {
            int color = i == index ? 0xFF0A84FF : 0xFF8E8E93;
            icons[i].setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            labels[i].setTextColor(color);
        }
        if (notify && callback != null) callback.onTab(index);
        invalidate();
    }

    public int current() { return current; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        Paint p = new Paint();
        p.setColor(0x1FD4D4D6);
        canvas.drawRect(0, 0, getWidth(), hairline, p);

    }
}
