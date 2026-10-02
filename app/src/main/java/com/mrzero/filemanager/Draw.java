package com.mrzero.filemanager;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Small drawing utilities: iOS easing curves and text truncation. */
public final class Draw {

    private Draw() {}

    /** iOS uses a custom ease-out for sheets; this is the standard UIKit curve. */
    public static final PathInterpolator EASE_OUT =
            new PathInterpolator(0.25f, 0.10f, 0.25f, 1.0f);
    public static final PathInterpolator EASE_IN_OUT =
            new PathInterpolator(0.42f, 0.0f, 0.58f, 1.0f);
    public static final PathInterpolator SPRING =
            new PathInterpolator(0.32f, 0.72f, 0.0f, 1.0f);

    /** Middle-elide a path the way iOS does when a filename is too long. */
    public static String ellipsizeMiddle(String s, int max) {
        if (s == null || s.length() <= max) return s;
        if (max <= 3) return s.substring(0, Math.max(0, max));
        int keep = max - 1;
        int head = (int) Math.ceil(keep * 0.45);
        int tail = keep - head;
        return s.substring(0, head) + "…" + s.substring(s.length() - tail);
    }

    /** Draw a rounded top-cornered rect (sheet shape). */
    public static void roundTopRect(Canvas c, Paint p, float l, float t, float r, float b,
                                    float radius) {
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
        c.save();
        c.clipRect(l, t + radius, r, b);
        c.drawRect(l, t, r, t + radius * 2, p);
        c.restore();
    }

    public static int lerp(int a, int b, float t) {
        return Color.argb(
                Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    /** Interpolate a two-stop vertical gradient (used by cells + selection). */
    public static int blend(int top, int bottom, float y, float height) {
        return lerp(top, bottom, Math.max(0f, Math.min(1f, y / Math.max(1f, height))));
    }

    public static void setVisible(View v, boolean visible) {
        if (visible) {
            if (v.getVisibility() != View.VISIBLE) v.setVisibility(View.VISIBLE);
        } else if (v.getVisibility() != View.GONE) {
            v.setVisibility(View.GONE);
        }
    }
}
