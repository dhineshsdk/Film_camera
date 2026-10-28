package com.s24film.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Horizontal scroll ruler for the PRO bar: ticks under a fixed gold index, one detent (sound + haptic)
 * per step. Steps with a label get a long tick and their text; empty labels are minor ticks.
 * Drag to scroll, tap left or right of the index to step once.
 */
public class RulerDial extends View {
    interface Listener { void onRuler(RulerDial v, int index); }

    private static final int ACCENT = 0xFFFFC845;

    Listener listener;
    FilmFx fx;
    private String[] labels = {""};
    private int idx;
    private float spacing;          // px between steps
    private float shown;            // drawn position (animates toward idx)
    private float downX, startPos, dragPos;
    private boolean dragging, moved;
    private final float d;
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint();
    private final Path tri = new Path();
    private int fadeW = -1;

    public RulerDial(Context c) { this(c, null); }

    public RulerDial(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        spacing = 18 * d;
        tick.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(11 * d);
        setHapticFeedbackEnabled(true);
    }

    /** New scale: labels per step ("" = minor tick), the selected step and the step spacing in dp. */
    void setScale(String[] labels, int index, float spacingDp) {
        this.labels = labels;
        this.spacing = spacingDp * d;
        idx = clamp(index);
        shown = idx;
        invalidate();
    }

    void setIndex(int index) {
        idx = clamp(index);
        invalidate();
    }

    int getIndex() { return idx; }

    private int clamp(int i) { return Math.max(0, Math.min(labels.length - 1, i)); }

    private void step(int ni) {
        ni = clamp(ni);
        if (ni == idx) return;
        idx = ni;
        if (fx != null) fx.detent();
        if (listener != null) listener.onRuler(this, idx);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = e.getX();
                startPos = idx;
                dragPos = idx;
                dragging = true;
                moved = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getX() - downX;
                if (Math.abs(dx) > 6 * d) moved = true;
                if (!moved) return true;
                dragPos = Math.max(0, Math.min(labels.length - 1, startPos - dx / spacing));
                step(Math.round(dragPos));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (!moved) step(idx + (e.getX() > getWidth() / 2f ? 1 : -1));
                dragging = false;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2f;
        float target = dragging ? dragPos : idx;
        boolean anim = Math.abs(shown - target) > 0.01f;
        shown = anim ? shown + (target - shown) * 0.35f : target;

        float base = h - 8 * d;
        int first = Math.max(0, (int) Math.floor(shown - cx / spacing) - 1);
        int last = Math.min(labels.length - 1, (int) Math.ceil(shown + cx / spacing) + 1);
        for (int i = first; i <= last; i++) {
            float x = cx + (i - shown) * spacing;
            boolean major = labels[i].length() > 0;
            float dist = Math.min(1f, Math.abs(x - cx) / cx);
            int alpha = (int) (255 * (1f - 0.75f * dist));
            tick.setColor(0xFFFFFFFF);
            tick.setAlpha(major ? alpha : alpha / 2);
            tick.setStrokeWidth((major ? 1.6f : 1.1f) * d);
            c.drawLine(x, base, x, base - (major ? 12 : 6) * d, tick);
            if (major) {
                boolean on = (i == idx);
                text.setColor(on ? ACCENT : 0xFFFFFFFF);
                text.setAlpha(on ? 255 : alpha);
                c.drawText(labels[i], x, base - 18 * d, text);
            }
        }
        // index: gold line and a small notch at the top
        tick.setColor(ACCENT);
        tick.setAlpha(255);
        tick.setStrokeWidth(2.2f * d);
        c.drawLine(cx, base + 3 * d, cx, base - 16 * d, tick);
        tri.reset();
        tri.moveTo(cx - 4 * d, 0);
        tri.lineTo(cx + 4 * d, 0);
        tri.lineTo(cx, 5 * d);
        tri.close();
        tick.setStyle(Paint.Style.FILL);
        c.drawPath(tri, tick);
        tick.setStyle(Paint.Style.STROKE);

        // soft fade at both ends so the scale reads as a wheel
        if (fadeW != (int) w) {
            fadeW = (int) w;
            fade.setShader(new LinearGradient(0, 0, w, 0,
                new int[] {0xFF101012, 0x00101012, 0x00101012, 0xFF101012},
                new float[] {0f, 0.14f, 0.86f, 1f}, Shader.TileMode.CLAMP));
        }
        fade.setXfermode(null);
        c.drawRect(0, 0, w, h, fade);
        if (anim) postInvalidateOnAnimation();
    }
}
