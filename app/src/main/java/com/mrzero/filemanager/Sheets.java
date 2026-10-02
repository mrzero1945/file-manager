package com.mrzero.filemanager;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * iOS-style action sheet, alert, and context menu, built from scratch.
 *
 * A real bottom sheet with a dimming scrim, rounded top corners, a grabber,
 * and stacked rows that press-in on touch. These are the pieces that make the
 * app read as "Apple" rather than as stock Material, so they are implemented
 * natively instead of pulling in Material's dialogs.
 */
public final class Sheets {

    private Sheets() {}

    // ==================================================================
    // Base: dimmed modal container
    // ==================================================================
    private static class Host {
        final Activity act;
        final FrameLayout root;
        final View scrim;
        boolean dismissed;

        Host(Activity act) {
            this.act = act;
            FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
            this.root = new FrameLayout(act);
            this.scrim = new View(act);
            scrim.setBackgroundColor(0x99000000);
            root.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            decor.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrim.setAlpha(0f);
            scrim.animate().alpha(1f).setDuration(220).setInterpolator(Draw.EASE_OUT).start();
        }

        void dismiss() {
            if (dismissed) return;
            dismissed = true;
            scrim.animate().alpha(0f).setDuration(160).setInterpolator(Draw.EASE_IN_OUT)
                    .withEndAction(() -> {
                        if (root.getParent() != null) {
                            ((ViewGroup) root.getParent()).removeView(root);
                        }
                    }).start();
        }
    }

    /** Item in an action sheet / context menu. */
    public static class Item {
        public final String label;
        public final int iconRes;
        public final int color;
        public final boolean destructive;
        public final Runnable action;

        public Item(String label, int iconRes, Runnable action) {
            this(label, iconRes, 0xFF0A84FF, false, action);
        }

        public Item(String label, int iconRes, int color, boolean destructive, Runnable action) {
            this.label = label;
            this.iconRes = iconRes;
            this.color = color;
            this.destructive = destructive;
            this.action = action;
        }
    }

    // ==================================================================
    // Action sheet: centered, stacked, iOS proportions
    // ==================================================================
    public static void actionSheet(Activity act, String title, String message,
                                   List<Item> items, Runnable onCancel) {
        Host h = new Host(act);
        Context ctx = act;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 13f)));
        card.setElevation(Apple.dp(ctx, 16f));

        LinearLayout holder = new LinearLayout(ctx);
        holder.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int m = Apple.dp(ctx, 8f);
        cardLp.setMargins(m, 0, m, 0);
        holder.addView(card, cardLp);

        boolean hasHeader = (title != null && !title.isEmpty())
                || (message != null && !message.isEmpty());
        if (hasHeader) {
            LinearLayout head = new LinearLayout(ctx);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setGravity(Gravity.CENTER);
            int pad = Apple.dp(ctx, 16f);
            head.setPadding(pad, pad, pad, pad);
            if (title != null && !title.isEmpty()) {
                TextView t = new TextView(ctx);
                t.setText(title);
                t.setTextColor(0xFFFFFFFF);
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                t.setGravity(Gravity.CENTER);
                head.addView(t);
            }
            if (message != null && !message.isEmpty()) {
                TextView m2 = new TextView(ctx);
                m2.setText(message);
                m2.setTextColor(0x99EBEBF5);
                m2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                m2.setGravity(Gravity.CENTER);
                if (title != null && !title.isEmpty()) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = Apple.dp(ctx, 4f);
                    m2.setLayoutParams(lp);
                }
                head.addView(m2);
            }
            head.setBackground(Apple.round(0xFF2C2C2E, Apple.dp(ctx, 13f)));
            card.addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (!items.isEmpty()) spacer(card, ctx, 0xFF48484A, 0.5f);
        }

        for (int i = 0; i < items.size(); i++) {
            addSheetRow(card, ctx, items.get(i), i == items.size() - 1, h);
            if (i < items.size() - 1) spacer(card, ctx, 0xFF48484A, 0.5f);
        }

        Button cancel = new Button(ctx);
        cancel.setText("Cancel");
        cancel.setTextColor(0xFF0A84FF);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        cancel.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cancel.setAllCaps(false);
        cancel.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 13f)));
        cancel.setMinHeight(Apple.dp(ctx, 57f));
        cancel.setOnClickListener(v -> {
            h.dismiss();
            if (onCancel != null) onCancel.run();
        });
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.setMargins(m, Apple.dp(ctx, 8f), m, 0);
        holder.addView(cancel, cancelLp);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        holder.setPadding(0, 0, 0, Apple.navBarBottom(h.scrim) + Apple.dp(ctx, 8f));
        h.root.addView(holder, lp);

        // Pressing the scrim cancels, exactly like iOS.
        h.scrim.setOnClickListener(v -> {
            h.dismiss();
            if (onCancel != null) onCancel.run();
        });
        h.scrim.setClickable(true);

        animateUp(holder, h);
    }

    private static void addSheetRow(LinearLayout parent, Context ctx, Item item,
                                    boolean last, Host h) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Apple.dp(ctx, 16f);
        int padV = Apple.dp(ctx, 13f);
        row.setPadding(padH, padV, padH, padV);
        row.setMinimumHeight(Apple.dp(ctx, 57f));

        int color = item.color != 0 ? item.color
                : (item.destructive ? 0xFFFF453A : 0xFF0A84FF);
        TextView label = new TextView(ctx);
        label.setText(item.label);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        label.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        label.setLetterSpacing(-0.02f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        if (item.iconRes != 0) {
            ImageView ic = new ImageView(ctx);
            ic.setImageResource(item.iconRes);
            ic.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            int s = Apple.dp(ctx, 21f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.leftMargin = Apple.dp(ctx, 12f);
            ic.setLayoutParams(ilp);
            row.addView(ic);
        }

        row.setOnTouchListener(pressHighlight(0xFF2C2C2E, h, () -> {
            if (item.action != null) item.action.run();
        }));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ==================================================================
    // Alert: title, message, optional text field, buttons
    // ==================================================================
    public static void alert(Activity act, String title, String message,
                             String confirmLabel, final Runnable onConfirm,
                             Runnable onCancel) {
        alert(act, title, message, null, confirmLabel, null,
                onConfirm == null ? null : v -> onConfirm.run(), onCancel);
    }

    public static void alert(Activity act, String title, String message,
                             String initialValue, String confirmLabel, String cancelLabel,
                             final ValueCallback onConfirm, Runnable onCancel) {
        Host h = new Host(act);
        Context ctx = act;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 13f)));
        card.setElevation(Apple.dp(ctx, 16f));

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(-0.012f);
        t.setGravity(Gravity.CENTER);
        int pad = Apple.dp(ctx, 16f);
        t.setPadding(pad, Apple.dp(ctx, 19f), pad, message != null ? 0 : Apple.dp(ctx, 19f));
        card.addView(t);

        if (message != null && !message.isEmpty()) {
            TextView m = new TextView(ctx);
            m.setText(message);
            m.setTextColor(0x99EBEBF5);
            m.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            m.setGravity(Gravity.CENTER);
            m.setLineSpacing(0, 1.15f);
            m.setPadding(pad, 0, pad, Apple.dp(ctx, 16f));
            card.addView(m);
        }

        final EditText input;
        if (initialValue != null) {
            input = new EditText(ctx);
            input.setText(initialValue);
            input.setTextColor(0xFFFFFFFF);
            input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
            input.setSingleLine(true);
            input.setSelectAllOnFocus(true);
            input.setBackground(Apple.round(0xFF2C2C2E, Apple.dp(ctx, 10f)));
            input.setPadding(Apple.dp(ctx, 12f), Apple.dp(ctx, 10f),
                    Apple.dp(ctx, 12f), Apple.dp(ctx, 10f));
            FrameLayout wrap = new FrameLayout(ctx);
            FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wlp.setMargins(pad, 0, pad, Apple.dp(ctx, 16f));
            wrap.addView(input, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(wrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            input = null;
        }

        spacer(card, ctx, 0xFF48484A, 0.5f);

        LinearLayout buttons = new LinearLayout(ctx);
        buttons.setOrientation(LinearLayout.VERTICAL);
        String cancelText = cancelLabel != null ? cancelLabel : "Cancel";
        Button cancel = alertButton(ctx, cancelText, 0xFF0A84FF);
        cancel.setOnClickListener(v -> {
            h.dismiss();
            if (onCancel != null) onCancel.run();
        });
        buttons.addView(cancel);

        if (confirmLabel != null) {
            spacer(buttons, ctx, 0xFF48484A, 0.5f);
            Button go = alertButton(ctx, confirmLabel, 0xFF0A84FF);
            go.setOnClickListener(v -> {
                String value = input != null ? input.getText().toString() : null;
                h.dismiss();
                if (onConfirm != null) onConfirm.onValue(value);
            });
            buttons.addView(go);
        }
        card.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        int side = Apple.dp(ctx, 20f);
        lp.setMargins(side, 0, side, 0);
        h.root.addView(card, lp);

        h.scrim.setOnClickListener(v -> {
            h.dismiss();
            if (onCancel != null) onCancel.run();
        });
        h.scrim.setClickable(true);

        card.setScaleX(1.08f);
        card.setScaleY(1.08f);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(240).setInterpolator(Draw.SPRING).start();

        if (input != null) {
            final EditText f = input;
            f.postDelayed(() -> {
                f.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager)
                                act.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(f,
                        android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }, 180);
        }
    }

    public interface ValueCallback {
        void onValue(String value);
    }

    private static Button alertButton(Context ctx, String label, int color) {
        Button b = new Button(ctx);
        b.setText(label);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        b.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        b.setLetterSpacing(-0.012f);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setMinHeight(Apple.dp(ctx, 44f));
        return b;
    }

    // ==================================================================
    // Context menu: anchored popup menu, iOS style
    // ==================================================================
    public static void contextMenu(Activity act, View anchor, List<Item> items) {
        Host h = new Host(act);
        Context ctx = act;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Apple.round(0xFF2C2C2E, Apple.dp(ctx, 14f)));
        card.setElevation(Apple.dp(ctx, 20f));

        for (int i = 0; i < items.size(); i++) {
            addMenuRow(card, ctx, items.get(i), h);
        }

        card.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = card.getMeasuredWidth();
        int hgt = card.getMeasuredHeight();

        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        int screenH = ctx.getResources().getDisplayMetrics().heightPixels;

        int x = loc[0] + anchor.getWidth() - w;
        int y = loc[1] + anchor.getHeight() + Apple.dp(ctx, 4f);
        if (x + w + Apple.dp(ctx, 8f) > screenW) {
            x = Math.max(Apple.dp(ctx, 8f), screenW - w - Apple.dp(ctx, 8f));
        }
        if (y + hgt + Apple.dp(ctx, 12f) > screenH) {
            y = Math.max(Apple.dp(ctx, 12f), loc[1] - hgt - Apple.dp(ctx, 4f));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, hgt);
        lp.leftMargin = x;
        lp.topMargin = y;
        h.root.addView(card, lp);

        h.scrim.setOnClickListener(v -> h.dismiss());
        h.scrim.setClickable(true);

        card.setScaleX(0.86f);
        card.setScaleY(0.86f);
        card.setPivotX(w);
        card.setPivotY(0);
        card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(170).setInterpolator(Draw.SPRING).start();
    }

    private static void addMenuRow(LinearLayout parent, Context ctx, Item item, Host h) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Apple.dp(ctx, 15f);
        row.setPadding(padH, Apple.dp(ctx, 11f), padH, Apple.dp(ctx, 11f));
        row.setMinimumHeight(Apple.dp(ctx, 44f));

        int color = item.color != 0 ? item.color
                : (item.destructive ? 0xFFFF453A : 0xFFFFFFFF);

        if (item.iconRes != 0) {
            ImageView ic = new ImageView(ctx);
            ic.setImageResource(item.iconRes);
            ic.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
            int s = Apple.dp(ctx, 19f);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(s, s);
            ilp.rightMargin = Apple.dp(ctx, 12f);
            ic.setLayoutParams(ilp);
            row.addView(ic);
        }

        TextView label = new TextView(ctx);
        label.setText(item.label);
        label.setTextColor(color);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        label.setLetterSpacing(-0.011f);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);

        row.setOnTouchListener(pressHighlight(0xFF48484A, h, () -> {
            if (item.action != null) item.action.run();
        }));
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ==================================================================
    // Toggleable options list (Sort, View options)
    // ==================================================================
    public static void options(Activity act, String title, final List<Option> options,
                               final Runnable onPick) {
        Host h = new Host(act);
        Context ctx = act;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 13f)));
        card.setElevation(Apple.dp(ctx, 16f));

        TextView head = new TextView(ctx);
        head.setText(title);
        head.setTextColor(0x99EBEBF5);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        head.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        head.setLetterSpacing(-0.006f);
        int pad = Apple.dp(ctx, 16f);
        head.setPadding(pad, Apple.dp(ctx, 16f), pad, Apple.dp(ctx, 12f));
        card.addView(head);

        for (int i = 0; i < options.size(); i++) {
            final Option o = options.get(i);
            final LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(pad, Apple.dp(ctx, 12f), pad, Apple.dp(ctx, 12f));
            row.setMinimumHeight(Apple.dp(ctx, 46f));

            TextView label = new TextView(ctx);
            label.setText(o.label);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            label.setLetterSpacing(-0.011f);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            if (o.checked) {
                ImageView check = new ImageView(ctx);
                check.setImageResource(R.drawable.ic_check);
                check.setColorFilter(0xFF0A84FF, android.graphics.PorterDuff.Mode.SRC_IN);
                int s = Apple.dp(ctx, 17f);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(s, s);
                clp.leftMargin = Apple.dp(ctx, 12f);
                check.setLayoutParams(clp);
                row.addView(check);
            }
            row.setOnTouchListener(pressHighlight(0xFF2C2C2E, h, () -> {
                if (o.action != null) o.action.run();
                if (onPick != null) onPick.run();
            }));
            card.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (i < options.size() - 1) spacer(card, ctx, 0xFF3A3A3C, 0.5f);
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.setMargins(Apple.dp(ctx, 20f), 0, Apple.dp(ctx, 20f), 0);
        h.root.addView(card, lp);

        h.scrim.setOnClickListener(v -> h.dismiss());
        h.scrim.setClickable(true);

        card.setAlpha(0f);
        card.setTranslationY(Apple.dp(ctx, 12f));
        card.animate().alpha(1f).translationY(0f)
                .setDuration(200).setInterpolator(Draw.EASE_OUT).start();
    }

    public static class Option {
        public final String label;
        public final boolean checked;
        public final Runnable action;

        public Option(String label, boolean checked, Runnable action) {
            this.label = label;
            this.checked = checked;
            this.action = action;
        }
    }

    // ==================================================================
    // Get-info sheet
    // ==================================================================
    public static void info(Activity act, FileEntry e) {
        Host h = new Host(act);
        Context ctx = act;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Apple.round(0xFF1C1C1E, Apple.dp(ctx, 20f)));
        card.setElevation(Apple.dp(ctx, 20f));

        int pad = Apple.dp(ctx, 20f);

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout tile = new FrameLayout(ctx);
        tile.setBackgroundResource(FileAdapter.tileResFor(e));
        int tilePx = Apple.dp(ctx, 62f);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(tilePx, tilePx);
        tlp.bottomMargin = Apple.dp(ctx, 12f);
        ImageView g = new ImageView(ctx);
        g.setImageResource(FileAdapter.iconFor(e));
        g.setColorFilter(0xFFFFFFFF, android.graphics.PorterDuff.Mode.SRC_IN);
        tile.addView(g, new FrameLayout.LayoutParams(
                Apple.dp(ctx, 34f), Apple.dp(ctx, 34f), Gravity.CENTER));
        head.addView(tile, tlp);

        TextView name = new TextView(ctx);
        name.setText(e.name);
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        name.setLetterSpacing(-0.016f);
        name.setGravity(Gravity.CENTER);
        head.addView(name);

        TextView kind = new TextView(ctx);
        kind.setText(FileAdapter.kindLabel(e));
        kind.setTextColor(0x99EBEBF5);
        kind.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        klp.topMargin = Apple.dp(ctx, 4f);
        head.addView(kind, klp);

        LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headLp.bottomMargin = Apple.dp(ctx, 18f);
        card.addView(head, headLp);

        infoRow(card, ctx, pad, "Kind", FileAdapter.kindLabel(e));
        infoRow(card, ctx, pad, "Size",
                e.isFolder() ? FileOps.formatSize(FileEntry.sizeOf(e.file))
                        : FileOps.formatSize(e.size));
        if (!e.isFolder()) {
            String[] bits = e.ext.toUpperCase(java.util.Locale.US).split("");
            StringBuilder sb = new StringBuilder();
            for (char ch : e.ext.toUpperCase(java.util.Locale.US).toCharArray()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(ch);
            }
            infoRow(card, ctx, pad, "Extension", sb.toString());
        } else {
            String[] kids = e.file.list();
            infoRow(card, ctx, pad, "Contains",
                    kids == null ? "—" : (kids.length + (kids.length == 1 ? " item" : " items")));
        }
        infoRow(card, ctx, pad, "Modified", FileOps.formatDate(e.lastModified));
        infoRow(card, ctx, pad, "Where", e.file.getParent() == null
                ? e.file.getPath() : e.file.getParent());
        infoRow(card, ctx, pad, "Path", e.file.getAbsolutePath());

        Button done = new Button(ctx);
        done.setText("Done");
        done.setTextColor(0xFF0A84FF);
        done.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        done.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        done.setAllCaps(false);
        done.setBackgroundColor(Color.TRANSPARENT);
        done.setOnClickListener(v -> h.dismiss());
        card.addView(done, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        lp.setMargins(Apple.dp(ctx, 16f), 0, Apple.dp(ctx, 16f), 0);
        h.root.addView(card, lp);

        h.scrim.setOnClickListener(v -> h.dismiss());
        h.scrim.setClickable(true);

        card.setAlpha(0f);
        card.setScaleX(1.04f);
        card.setScaleY(1.04f);
        card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(Draw.SPRING).start();
    }

    private static void infoRow(LinearLayout card, Context ctx, int pad,
                                String key, String value) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(pad, Apple.dp(ctx, 8f), pad, Apple.dp(ctx, 8f));

        TextView k = new TextView(ctx);
        k.setText(key);
        k.setTextColor(0x99EBEBF5);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        k.setLayoutParams(new LinearLayout.LayoutParams(
                Apple.dp(ctx, 88f), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(k);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(0xFFFFFFFF);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        v.setTextIsSelectable(true);
        v.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // ==================================================================
    // helpers
    // ==================================================================
    private static void spacer(LinearLayout parent, Context ctx, int color, float dpHeight) {
        View v = new View(ctx);
        v.setBackgroundColor(color);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Apple.hairline(ctx));
        parent.addView(v, lp);
    }

    private static void animateUp(View v, Host h) {
        v.setTranslationY(h.scrim.getHeight() > 0 ? h.scrim.getHeight() : 400f);
        v.setAlpha(0.6f);
        v.post(() -> {
            float target = v.getTranslationY();
            v.setTranslationY(target);
            v.animate().translationY(0f).alpha(1f)
                    .setDuration(300).setInterpolator(Draw.EASE_OUT).start();
        });
    }

    /** Press-in highlight, the tactile cue iOS rows have and Android lists lack. */
    private static android.view.View.OnTouchListener pressHighlight(
            int highlightColor, final Host h, final Runnable onTap) {
        final int normal = 0x00000000;
        return (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setBackgroundColor(highlightColor);
                    v.setAlpha(0.85f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().alpha(1f).setDuration(90).start();
                    v.setBackgroundColor(normal);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        // Dismiss first: most actions open another sheet, and
                        // stacking two hosts leaves two scrims on screen.
                        h.dismiss();
                        onTap.run();
                    }
                    return true;
                default:
                    return true;
            }
        };
    }

    /** iOS-style toast, floating and auto-dismissing. */
    public static void toast(Activity act, String message) {
        Context ctx = act;
        TextView tv = new TextView(ctx);
        tv.setText(message);
        tv.setTextColor(0xFF000000);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(Apple.dp(ctx, 18f), Apple.dp(ctx, 11f), Apple.dp(ctx, 18f), Apple.dp(ctx, 11f));
        tv.setBackground(Apple.round(0xF0E8E8EA, Apple.dp(ctx, 14f)));
        tv.setElevation(Apple.dp(ctx, 12f));

        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = Apple.dp(ctx, 96f) + Apple.navBarBottom(decor);
        decor.addView(tv, lp);

        tv.setAlpha(0f);
        tv.setTranslationY(Apple.dp(ctx, 14f));
        tv.animate().alpha(1f).translationY(0f)
                .setDuration(220).setInterpolator(Draw.EASE_OUT).start();
        tv.postDelayed(() -> {
            tv.animate().alpha(0f).translationY(Apple.dp(ctx, 8f))
                    .setDuration(240).setInterpolator(Draw.EASE_IN_OUT)
                    .withEndAction(() -> ((ViewGroup) tv.getParent()).removeView(tv))
                    .start();
        }, 1900);
    }
}
