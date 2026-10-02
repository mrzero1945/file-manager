package com.mrzero.filemanager;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * The single place that knows Apple's metrics. Everything visual reads from
 * here so spacing stays on the 4pt grid and radii stay consistent.
 */
public final class Apple {

    // 4pt spacing grid
    public static final int S1 = 4, S2 = 8, S3 = 12, S4 = 16, S5 = 20, S6 = 24, S8 = 32, S12 = 48;

    // Radii (pt)
    public static final float R_SMALL = 8, R_MEDIUM = 12, R_LARGE = 16, R_SHEET = 22;
    public static final float R_TILE = 9.5f, R_PILL = 100;

    public static final int CAT_ICON_DP = 20;
    public static final int ROW_MIN_HEIGHT = 52;
    public static final int TILE_DP = 38;
    public static final int TAB_BAR_DP = 50;
    public static final int NAV_BAR_DP = 44;
    public static final int LARGE_TITLE_DP = 52;
    public static final float LARGE_TITLE_SP = 32f, NAV_TITLE_SP = 17f, ROW_TITLE_SP = 16f,
            ROW_SUBTITLE_SP = 13f, BUTTON_SP = 17f, TAB_LABEL_SP = 10f, SECTION_SP = 13f;

    public static final int BLUE = 0xFF0A84FF;
    public static final int INDIGO = 0xFF5E5CE6;
    public static final int GREEN = 0xFF30D158;
    public static final int ORANGE = 0xFFFF9F0A;
    public static final int RED = 0xFFFF453A;
    public static final int PURPLE = 0xFFBF5AF2;
    public static final int TEAL = 0xFF40C8E0;
    public static final int YELLOW = 0xFFFFD60A;
    public static final int PINK = 0xFFFF375F;

    /** Category accent order used by tiles and the storage chart. */
    public static final int[] CATEGORY_HUE = {
            GREEN, PURPLE, PINK, BLUE, ORANGE, TEAL, 0xFF8E8E93,
    };

    public static final int BG = 0xFF000000;
    public static final int BG_ELEVATED = 0xFF1C1C1E;
    public static final int BG_CARD = 0xFF1C1C1E;
    public static final int BG_CARD_ALT = 0xFF2C2C2E;
    public static final int BG_CELL = 0xFF1C1C1E;
    public static final int BAR_BLUR = 0xCC0C0C0E;
    public static final int SEPARATOR = 0x3FD4D4D6;
    public static final int LABEL = 0xFFFFFFFF;
    public static final int LABEL_2 = 0x99EBEBF5;
    public static final int LABEL_3 = 0x4DEBEBF5;
    public static final int FILL_QUATERNARY = 0x1FFFFFFF;
    public static final int GRAY = 0xFF8E8E93;
    public static final int GRAY_DARK = 0xFF636366;

    public static final int NAV_TRANSPARENT = 0x00000000;

    private Apple() {}

    public static boolean isDark(Context ctx) {
        int mode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode != Configuration.UI_MODE_NIGHT_NO;
    }

    public static int color(Context ctx, int res) {
        return ctx.getResources().getColor(res, ctx.getTheme());
    }

    /** dp -> px */
    public static int dp(Context ctx, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics()));
    }

    public static float sp(Context ctx, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp,
                ctx.getResources().getDisplayMetrics());
    }

    /** Height of the status bar, so custom bars can inset themselves correctly. */
    public static int statusBarHeight(Context ctx) {
        int id = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    public static int navBarHeight(Context ctx) {
        int id = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? ctx.getResources().getDimensionPixelSize(id) : 0;
    }

    public static void edgeToEdge(Window w) {
        w.setStatusBarColor(NAV_TRANSPARENT);
        w.setNavigationBarColor(NAV_TRANSPARENT);
        w.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Rounded rect drawable helper used by tiles, cells and sheets. */
    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(color);
        gd.setCornerRadius(radiusDp);
        return gd;
    }

    public static GradientDrawable stroke(int fill, int strokeColor, float radiusDp, int widthPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fill);
        gd.setCornerRadius(radiusDp);
        gd.setStroke(widthPx, strokeColor);
        return gd;
    }

    /**
     * Translucent bar background. Android has no backdrop-sampling blur for a
     * plain View (RenderEffect would only blur the bar's own children), so the
     * material-style translucent scrim is what actually reads as frosted glass.
     */
    public static void applyBarScrim(View bar, int baseColor) {
        bar.setBackground(new ColorDrawable(baseColor | 0xCC000000));
    }

    public static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    public static int scaleAlpha(int color, float scale) {
        return withAlpha(color, Math.max(0, Math.min(255,
                Math.round(Color.alpha(color) * scale))));
    }

    /** A hairline that matches the 0.33pt iOS separator at any density. */
    public static int hairline(Context ctx) {
        return Math.max(1, Math.round(0.5f * ctx.getResources().getDisplayMetrics().density));
    }

    public static void setLightStatusBar(Window w, boolean lightIcons) {
        w.getDecorView().setSystemUiVisibility(lightIcons
                ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    public static Window setNoTitleFlag(Window w) {
        w.requestFeature(Window.FEATURE_NO_TITLE);
        return w;
    }

    public static void layoutInStatusBar(View bar, boolean includeBottom) {
        int top = statusBarHeight(bar.getContext());
        int bottom = includeBottom ? navBarHeight(bar.getContext()) : 0;
        bar.setPadding(bar.getPaddingLeft(), top, bar.getPaddingRight(), bottom);
    }

    public static int statusBarTop(View v) {
        int id = v.getContext().getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? v.getContext().getResources().getDimensionPixelSize(id) : 0;
    }

    public static int navBarBottom(View v) {
        int id = v.getContext().getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? v.getContext().getResources().getDimensionPixelSize(id) : 0;
    }
}
