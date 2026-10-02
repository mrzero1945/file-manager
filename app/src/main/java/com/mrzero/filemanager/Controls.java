package com.mrzero.filemanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Factory for the tappable chrome: icon buttons, text buttons, and the iOS toggle. */
public final class Controls {

    private Controls() {}

    /** A round, tappable icon button (44pt target, press-in highlight). */
    public static ImageView iconButton(Context ctx, int iconRes, int tint, String desc,
                                       View.OnClickListener onClick) {
        ImageView v = new ImageView(ctx);
        v.setImageResource(iconRes);
        v.setColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN);
        v.setPadding(Apple.dp(ctx, 9f), Apple.dp(ctx, 9f), Apple.dp(ctx, 9f), Apple.dp(ctx, 9f));
        v.setClickable(true);
        v.setFocusable(true);
        if (desc != null) v.setContentDescription(desc);
        v.setOnClickListener(onClick);
        v.setBackground(pressable(0x1FFFFFFF));
        return v;
    }

    public static TextView textButton(Context ctx, String label, int color, String desc,
                                      View.OnClickListener onClick) {
        TextView v = new TextView(ctx);
        v.setText(label);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, Apple.BUTTON_SP);
        v.setTypeface(android.graphics.Typeface.create("sans-serif",
                android.graphics.Typeface.BOLD));
        v.setLetterSpacing(-0.011f);
        v.setIncludeFontPadding(false);
        v.setGravity(Gravity.CENTER);
        v.setPadding(Apple.dp(ctx, 8f), Apple.dp(ctx, 6f), Apple.dp(ctx, 8f), Apple.dp(ctx, 6f));
        v.setClickable(true);
        v.setFocusable(true);
        if (desc != null) v.setContentDescription(desc);
        v.setOnClickListener(onClick);
        v.setBackground(pressable(0x1FFFFFFF));
        return v;
    }

    /** Selector that mimics the iOS press-in highlight. */
    public static android.graphics.drawable.Drawable pressable(final int highlight) {
        android.graphics.drawable.StateListDrawable d =
                new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_pressed},
                Apple.round(highlight, 20f));
        d.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0x00000000));
        return d;
    }

    /** iOS switch: 51x31pt, 27pt knob, green when on. */
    public static class Toggle extends FrameLayout {
        private final View knob;
        private final android.graphics.drawable.GradientDrawable track;
        private final int trackHeight;
        private final int pad;
        private final int knobSize;
        private boolean checked;
        private Runnable onChange;

        public Toggle(Context ctx, boolean initial) {
            super(ctx);
            checked = initial;
            trackHeight = Apple.dp(ctx, 31f);
            pad = Apple.dp(ctx, 2f);
            knobSize = trackHeight - pad * 2;
            setLayoutParams(new FrameLayout.LayoutParams(
                    Apple.dp(ctx, 51f), trackHeight));

            track = new android.graphics.drawable.GradientDrawable();
            track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            track.setCornerRadius(trackHeight / 2f);
            track.setColor(0xFF39393D);
            setBackground(track);
            setClickable(true);
            setFocusable(true);

            knob = new View(ctx);
            knob.setBackground(Apple.round(0xFFFFFFFF, knobSize / 2f));
            addView(knob, new FrameLayout.LayoutParams(knobSize, knobSize));
            apply();
        }

        public void setOnChange(Runnable r) { onChange = r; }

        public boolean isChecked() { return checked; }

        public void setChecked(boolean c, boolean notify) {
            checked = c;
            setActivated(c);
            apply();
            if (notify && onChange != null) onChange.run();
        }

        private void apply() {
            track.setColor(checked ? 0xFF30D158 : 0xFF39393D);
            int travel = Math.max(0, getWidth() - knobSize - pad * 2);
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) knob.getLayoutParams();
            lp.leftMargin = pad + (checked ? travel : 0);
            lp.gravity = Gravity.CENTER_VERTICAL;
            knob.setLayoutParams(lp);
            knob.setAlpha(checked ? 1f : 0.85f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            apply();
        }
    }

    /** iOS segmented control used for the list/grid switch. */
    public static class Segmented extends LinearLayout {
        public interface OnChange {
            void onChange(int index);
        }

        private final TextView[] tabs;
        private OnChange onChange;
        private int selected;

        public Segmented(Context ctx, String[] labels, int initial) {
            super(ctx);
            setOrientation(HORIZONTAL);
            setPadding(Apple.dp(ctx, 2f), Apple.dp(ctx, 2f),
                    Apple.dp(ctx, 2f), Apple.dp(ctx, 2f));
            setBackground(Apple.round(0xFF2C2C2E, Apple.dp(ctx, 9f)));

            tabs = new TextView[labels.length];
            for (int i = 0; i < labels.length; i++) {
                TextView t = new TextView(ctx);
                t.setText(labels[i]);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                t.setTypeface(android.graphics.Typeface.create("sans-serif",
                        android.graphics.Typeface.BOLD));
                t.setLetterSpacing(-0.008f);
                t.setGravity(Gravity.CENTER);
                t.setSingleLine(true);
                t.setMinWidth(Apple.dp(ctx, 62f));
                t.setMinHeight(Apple.dp(ctx, 28f));
                t.setIncludeFontPadding(false);
                tabs[i] = t;
                final int index = i;
                t.setOnClickListener(v -> select(index, true));
                addView(t, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            }
            select(initial, false);
        }

        public void setOnChange(OnChange c) { onChange = c; }

        public void select(int index, boolean notify) {
            selected = index;
            for (int i = 0; i < tabs.length; i++) {
                boolean active = i == index;
                tabs[i].setTextColor(active ? 0xFF000000 : 0xFFEBEBF5);
                tabs[i].setBackground(Apple.round(active ? 0xFFE8E8EA : 0x00000000,
                        Apple.dp(getContext(), 7f)));
            }
            if (notify && onChange != null) onChange.onChange(index);
        }

        public int selected() { return selected; }
    }

    /** Grouped-inset container, the signature iOS list section. */
    public static LinearLayout group(Context ctx) {
        LinearLayout g = new LinearLayout(ctx);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 10f)));
        return g;
    }

    public static TextView sectionHeader(Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text.toUpperCase());
        t.setTextColor(0x99EBEBF5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, Apple.SECTION_SP);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.BOLD));
        t.setLetterSpacing(-0.006f);
        t.setIncludeFontPadding(false);
        t.setPadding(Apple.dp(ctx, 16f), Apple.dp(ctx, 22f), Apple.dp(ctx, 16f), Apple.dp(ctx, 7f));
        return t;
    }

    /** One row in a settings-style group: label, optional value, optional control. */
    public static View settingsRow(Context ctx, String label, String value,
                                   View control, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Apple.dp(ctx, 46f));
        row.setPadding(Apple.dp(ctx, 16f), Apple.dp(ctx, 8f), Apple.dp(ctx, 16f), Apple.dp(ctx, 8f));

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(0xFFFFFFFF);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        l.setLetterSpacing(-0.011f);
        l.setIncludeFontPadding(false);
        row.addView(l, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            TextView v = new TextView(ctx);
            v.setText(value);
            v.setTextColor(0x99EBEBF5);
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            v.setIncludeFontPadding(false);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.leftMargin = Apple.dp(ctx, 8f);
            row.addView(v, vlp);
        }
        // Trailing control, as in iOS Settings.
        if (control != null) {
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = Apple.dp(ctx, 12f);
            row.addView(control, clp);
        }
        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackground(pressable(0xFF2C2C2E));
            row.setOnClickListener(onClick);
        }
        return row;
    }

    /** Hairline divider inset to the label, the iOS "inset grouped" separator. */
    public static View insetDivider(Context ctx) {
        View v = new View(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Apple.hairline(ctx));
        lp.leftMargin = Apple.dp(ctx, 16f);
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x3FD4D4D6);
        return v;
    }

    /** Full-width hairline for list rows. */
    public static View divider(Context ctx) {
        View v = new View(ctx);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Apple.hairline(ctx)));
        v.setBackgroundColor(0x3FD4D4D6);
        return v;
    }
}
