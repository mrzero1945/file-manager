package com.mrzero.filemanager;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * Donut chart for the Storage tab, drawn the way iOS draws storage rings:
 * a grey track, a coloured arc per category, and a rounded cap on the ends.
 */
public class StorageChart extends View {

    private float[] values = new float[0];
    private int[] colors = new int[0];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private float sweepTotal;
    private String centerTop = "";
    private String centerBottom = "";
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public StorageChart(Context c) { super(c); init(); }

    public StorageChart(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setData(float[] values, int[] colors) {
        this.values = values != null ? values : new float[0];
        this.colors = colors != null ? colors : new int[0];
        float sum = 0;
        for (float v : this.values) sum += Math.max(0, v);
        sweepTotal = sum;
        invalidate();
    }

    public void setCenter(String top, String bottom) {
        centerTop = top != null ? top : "";
        centerBottom = bottom != null ? bottom : "";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth(), h = getHeight();
        float size = Math.min(w, h);
        float stroke = size * 0.155f;
        float inset = stroke / 2f + size * 0.02f;
        float cx = w / 2f, cy = h / 2f;
        float r = size / 2f - inset;

        arc.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(0xFF2C2C2E);
        canvas.drawArc(arc, 0, 360, false, paint);

        if (sweepTotal <= 0) {
            drawCenter(canvas, cx, cy);
            return;
        }

        // A gap between slices reads as separate categories; the first slice
        // starts at 12 o'clock like iOS.
        float gap = values.length > 1 ? 2.0f : 0f;
        float start = -90f;
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (int i = 0; i < values.length; i++) {
            float v = Math.max(0, values[i]);
            if (v <= 0) continue;
            float sweep = (v / sweepTotal) * 360f;
            float drawSweep = Math.max(0.6f, sweep - gap);
            paint.setColor(colors[i % Math.max(1, colors.length)]);
            canvas.drawArc(arc, start + gap / 2f, drawSweep, false, paint);
            start += sweep;
        }
        drawCenter(canvas, cx, cy);
    }

    private void drawCenter(Canvas c, float cx, float cy) {
        float size = Math.min(getWidth(), getHeight());
        if (centerTop.isEmpty()) return;
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        textPaint.setTextSize(size * 0.145f);
        c.drawText(centerTop, cx, cy + size * 0.02f, textPaint);
        if (!centerBottom.isEmpty()) {
            textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            textPaint.setTextSize(size * 0.085f);
            textPaint.setColor(0x99EBEBF5);
            c.drawText(centerBottom, cx, cy + size * 0.135f, textPaint);
        }
    }
}
