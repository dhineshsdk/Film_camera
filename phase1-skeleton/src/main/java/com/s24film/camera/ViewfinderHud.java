package com.s24film.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * Viewfinder layer over the 3:4 preview window: rule-of-thirds grid, the tap ring, a sun scale for
 * drag-to-expose and the self-timer count. It only takes touches that start inside the preview, so
 * the bands and every button above it behave as before.
 *   tap          ring at the point, and the depth-bokeh focus point moves there
 *   drag up/down exposure (the Look tab's EV slider), 0.1 EV steps, a tick every half stop
 *   pinch        out/in steps to the next longer/shorter rear lens
 */
public class ViewfinderHud extends View {
    interface Host {
        void onTapPoint(float u, float v);
        float currentEv();
        void onEvDrag(float stops);
        void onPinchLens(int dir);
    }

    private static final int ACCENT = 0xFFFFC845;
    private static final long RING_MS = 2600;

    Host host;
    private boolean grid;
    private int countdown;              // > 0 shows the self-timer number
    private float ringX = -1, ringY = -1;
    private long ringAt;                // uptime of the last tap or drag
    private boolean showScale;
    private float ev;
    private final float d;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);

    // gesture state
    private float downX, downY, startEv, pinchDist;
    private boolean tracking, dragging, pinching, pinchUsed;
    private int lastHalf;

    public ViewfinderHud(Context c) { this(c, null); }

    public ViewfinderHud(Context c, AttributeSet a) {
        super(c, a);
        d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(0.8f * d);
        line.setColor(0x59FFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(1.6f * d);
        label.setTextSize(12 * d);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setColor(ACCENT);
        label.setShadowLayer(3 * d, 0, 0, 0x99000000);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(110 * d);
        big.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        big.setColor(0xFFFFFFFF);
        big.setShadowLayer(8 * d, 0, 0, 0x99000000);
    }

    void setGrid(boolean on) { grid = on; invalidate(); }

    void setCountdown(int n) { countdown = n; invalidate(); }

    private float holeTop() { return FilmUi.holeTop(getWidth(), getHeight()); }
    private float holeBottom() { return FilmUi.holeBottom(getWidth(), getHeight()); }

    private static float dist(MotionEvent e) {
        float dx = e.getX(0) - e.getX(1), dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (e.getY() < holeTop() || e.getY() > holeBottom()) return false;
                tracking = true;
                dragging = pinching = pinchUsed = false;
                downX = e.getX();
                downY = e.getY();
                startEv = (host != null) ? host.currentEv() : 0f;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    pinching = true;
                    pinchDist = dist(e);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!tracking) return false;
                if (pinching && e.getPointerCount() >= 2) {
                    float r = dist(e) / Math.max(1f, pinchDist);
                    if ((r > 1.45f || r < 0.69f) && host != null) {
                        host.onPinchLens(r > 1f ? 1 : -1);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        pinchDist = dist(e);
                        pinchUsed = true;
                    }
                    return true;
                }
                if (pinching || pinchUsed) return true;
                float dy = e.getY() - downY;
                if (!dragging && Math.abs(dy) > 14 * d && Math.abs(dy) > Math.abs(e.getX() - downX)) {
                    dragging = true;
                    if (ringX < 0 || SystemClock.uptimeMillis() - ringAt > RING_MS) { ringX = downX; ringY = downY; }
                    lastHalf = Math.round(startEv * 2f);
                }
                if (dragging) {
                    // 70 dp of travel per stop; up is brighter
                    float v = startEv - (e.getY() - downY) / (70 * d);
                    v = Math.max(-2f, Math.min(2f, Math.round(v * 10f) / 10f));
                    if (v != ev || !showScale) {
                        ev = v;
                        showScale = true;
                        int half = Math.round(v * 2f);
                        if (half != lastHalf) {
                            lastHalf = half;
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        }
                        if (host != null) host.onEvDrag(v);
                    }
                    ringAt = SystemClock.uptimeMillis();
                    postInvalidateOnAnimation();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (tracking && !dragging && !pinching && !pinchUsed) {
                    ringX = e.getX();
                    ringY = e.getY();
                    ringAt = SystemClock.uptimeMillis();
                    ev = (host != null) ? host.currentEv() : 0f;
                    showScale = Math.abs(ev) > 0.05f;
                    if (host != null && getWidth() > 0 && getHeight() > 0) {
                        host.onTapPoint(e.getX() / getWidth(), e.getY() / getHeight());
                    }
                    postInvalidateOnAnimation();
                }
                tracking = dragging = pinching = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                tracking = dragging = pinching = false;
                return true;
            default:
                return tracking;
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), top = holeTop(), bot = holeBottom();
        if (grid) {
            float hh = bot - top;
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3f, top, w * i / 3f, bot, line);
                c.drawLine(0, top + hh * i / 3f, w, top + hh * i / 3f, line);
            }
        }

        long age = SystemClock.uptimeMillis() - ringAt;
        if (ringX >= 0 && age < RING_MS) {
            float alpha = age > RING_MS - 400 ? (RING_MS - age) / 400f : 1f;
            float grow = age < 220 ? 1.35f - 0.35f * (age / 220f) : 1f;
            float r = 34 * d * grow;
            ring.setColor(0xFFFFFFFF);
            ring.setAlpha((int) (230 * alpha));
            c.drawCircle(ringX, ringY, r, ring);
            ring.setColor(ACCENT);
            ring.setAlpha((int) (255 * alpha));
            c.drawCircle(ringX, ringY, 2.2f * d, ring);

            if (showScale) {
                // vertical exposure scale beside the ring, sun marker at the current EV
                boolean left = ringX > w - 90 * d;
                float sx = left ? ringX - r - 16 * d : ringX + r + 16 * d;
                float half = 56 * d;
                ring.setColor(0xFFFFFFFF);
                ring.setAlpha((int) (150 * alpha));
                c.drawLine(sx, ringY - half, sx, ringY + half, ring);
                float sy = ringY - (ev / 2f) * half;
                ring.setColor(ACCENT);
                ring.setAlpha((int) (255 * alpha));
                c.drawCircle(sx, sy, 5 * d, ring);
                for (int k = 0; k < 8; k++) {
                    double a = Math.PI * k / 4;
                    float x0 = sx + (float) Math.cos(a) * 7.5f * d, y0 = sy + (float) Math.sin(a) * 7.5f * d;
                    float x1 = sx + (float) Math.cos(a) * 10f * d, y1 = sy + (float) Math.sin(a) * 10f * d;
                    c.drawLine(x0, y0, x1, y1, ring);
                }
                label.setAlpha((int) (255 * alpha));
                label.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
                String t = Math.abs(ev) < 0.05f ? "0.0" : String.format(Locale.US, "%+.1f", ev);
                c.drawText(t, left ? sx - 14 * d : sx + 14 * d, sy + 4 * d, label);
            }
            postInvalidateOnAnimation();
        }

        if (countdown > 0) {
            float cy = (top + bot) / 2f - (big.descent() + big.ascent()) / 2f;
            c.drawText(Integer.toString(countdown), w / 2f, cy, big);
        }
    }
}
