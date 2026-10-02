package com.mrzero.filemanager;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A full-screen "pushed" screen: its own nav bar, a back button, and no tab bar. */
public class DetailPane extends FrameLayout {

    public interface Listener {
        void onBack();

        void onTitleTap(String title);
    }

    private final NavBar nav;
    private final LinearLayout body;
    private Listener listener;

    public DetailPane(Context ctx, String title) {
        this(ctx, title, null);
    }

    public DetailPane(Context ctx, String title, String path) {
        super(ctx);
        setBackgroundColor(0xFF000000);

        nav = new NavBar(ctx);
        nav.setLargeTitle(title);
        nav.setPathLine(path);
        nav.setOnBack(() -> {
            if (listener != null) listener.onBack();
        });
        addView(nav, new LayoutParams(LayoutParams.MATCH_PARENT,
                NavBar.expandedHeightFor(ctx)));

        // Back chevron in the leading slot.
        ImageView back = Controls.iconButton(ctx, R.drawable.ic_chevron_left, 0xFF0A84FF,
                "Back", v -> {
                    if (listener != null) listener.onBack();
                });
        nav.addLeading(back);

        body = new LinearLayout(ctx);
        body.setOrientation(LinearLayout.VERTICAL);
        // The body starts below the nav band and scrolls, so the large title is
        // the first thing inside it rather than an overlay on the bar.
        body.setPadding(0, Design.dp(ctx, 4f), 0, 0);
        LayoutParams bodyLp = new LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT);
        bodyLp.topMargin = NavBar.expandedHeightFor(ctx);
        addView(body, bodyLp);

        setTranslationX(0f);
    }

    public NavBar navBar() { return nav; }

    public LinearLayout body() { return body; }

    public void setListener(Listener l) { listener = l; }

    /** iOS push: the incoming screen slides in from the right. */
    public void animatePushIn() {
        setTranslationX(getWidth() > 0 ? getWidth() : 400f);
        animate().translationX(0f).setDuration(340).setInterpolator(Draw.EASE_OUT).start();
    }

    public void animatePopOut(Runnable done) {
        animate().translationX(getWidth() > 0 ? getWidth() : 400f)
                .setDuration(280).setInterpolator(Draw.EASE_IN_OUT)
                .withEndAction(done).start();
    }

    /** Standard empty state: icon, title, subtitle. */
    public static LinearLayout emptyState(Context ctx, int iconRes, String title, String sub) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(Design.dp(ctx, 32f), Design.dp(ctx, 64f), Design.dp(ctx, 32f), Design.dp(ctx, 64f));

        ImageView ic = new ImageView(ctx);
        ic.setImageResource(iconRes);
        ic.setColorFilter(0x1FFFFFFF);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                Design.dp(ctx, 56f), Design.dp(ctx, 56f));
        ilp.bottomMargin = Design.dp(ctx, 16f);
        box.addView(ic, ilp);

        TextView t = new TextView(ctx);
        t.setText(title);
        t.setTextColor(0x99EBEBF5);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                android.graphics.Typeface.NORMAL));
        t.setLetterSpacing(-0.011f);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        box.addView(t);

        if (sub != null && !sub.isEmpty()) {
            TextView s = new TextView(ctx);
            s.setText(sub);
            s.setTextColor(0x4DEBEBF5);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            s.setGravity(Gravity.CENTER);
            s.setIncludeFontPadding(false);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Design.dp(ctx, 6f);
            box.addView(s, slp);
        }
        return box;
    }
}
