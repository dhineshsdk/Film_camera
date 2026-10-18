package com.s24film.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Shooting aids drawn over the live view: focus peaking, histogram and a zone-system meter.
 * A low-rate sampler reads the last rendered frame back (at most twice a second, only while an aid
 * is on), so the preview rate is untouched when the aids are off.
 */
public class ProOverlay extends View {
    private static final long SAMPLE_MS = 600;
    private static final int PEAK_THRESHOLD = 46;

    private boolean peaking, histogram, zones;
    private final int[][] hist = new int[4][64];      // R, G, B, luma
    private int histMax = 1;
    private float[] peakPts = new float[0];            // x, y pairs in 0..1
    private int peakCount = 0;
    private float meanZone = Float.NaN;
    private boolean running = false;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (!running) return;
            new Thread(new Job(ProOverlay.this), "ProSampler").start();
            postDelayed(this, SAMPLE_MS);
        }
    };

    public ProOverlay(Context c) { super(c); }
    public ProOverlay(Context c, AttributeSet a) { super(c, a); }

    public void setAids(boolean peaking, boolean histogram, boolean zones) {
        this.peaking = peaking; this.histogram = histogram; this.zones = zones;
        boolean any = peaking || histogram || zones;
        setVisibility(any ? VISIBLE : GONE);
        if (any && !running) { running = true; post(sampler); }
        if (!any) { running = false; removeCallbacks(sampler); peakCount = 0; }
        invalidate();
    }

    public void pause() { running = false; removeCallbacks(sampler); }
    public void resume() {
        if ((peaking || histogram || zones) && !running) { running = true; post(sampler); }
    }

    @Override protected void onDetachedFromWindow() { pause(); super.onDetachedFromWindow(); }

    /** Reads one frame back and reduces it on a worker thread. */
    private static final class Job implements Runnable {
        private final ProOverlay o;
        Job(ProOverlay o) { this.o = o; }
        @Override public void run() {
            int[] dims = new int[2];
            byte[] px;
            try { px = VulkanRenderer.nativeCaptureLastRgba(dims); } catch (Throwable t) { return; }
            if (px == null || dims[0] < 8 || dims[1] < 8) return;
            final int w = dims[0], h = dims[1];
            int[][] hs = new int[4][64];
            int stepX = Math.max(1, w / 192), stepY = Math.max(1, h / 144);
            float[] pts = new float[2 * 4000];
            int n = 0;
            double sumLin = 0;
            int cnt = 0;
            for (int y = 1; y < h - 1; y += stepY) {
                int row = y * w * 4;
                for (int x = 1; x < w - 1; x += stepX) {
                    int i = row + x * 4;
                    int r = px[i] & 255, g = px[i + 1] & 255, b = px[i + 2] & 255;
                    int l = (r * 54 + g * 183 + b * 19) >> 8;
                    hs[0][r >> 2]++; hs[1][g >> 2]++; hs[2][b >> 2]++; hs[3][l >> 2]++;
                    double lin = l / 255.0;
                    sumLin += lin <= 0.04045 ? lin / 12.92 : Math.pow((lin + 0.055) / 1.055, 2.4);
                    cnt++;
                    if (n < 4000) {
                        int il = i - 4, ir = i + 4, iu = i - w * 4, id = i + w * 4;
                        int gx = Math.abs(lum(px, ir) - lum(px, il));
                        int gy = Math.abs(lum(px, id) - lum(px, iu));
                        if (gx + gy > PEAK_THRESHOLD) {
                            pts[2 * n] = x / (float) w;
                            pts[2 * n + 1] = y / (float) h;
                            n++;
                        }
                    }
                }
            }
            double mean = cnt > 0 ? sumLin / cnt : 0.18;
            final float zone = (float) (5.0 + Math.log(Math.max(mean, 1e-4) / 0.18) / Math.log(2.0));
            int mx = 1;
            for (int v : hs[3]) mx = Math.max(mx, v);
            final int fmx = mx, fn = n;
            final float[] fp = pts;
            final int[][] fh = hs;
            o.post(new Runnable() {
                @Override public void run() {
                    o.hist[0] = fh[0]; o.hist[1] = fh[1]; o.hist[2] = fh[2]; o.hist[3] = fh[3];
                    o.histMax = fmx; o.peakPts = fp; o.peakCount = fn; o.meanZone = zone;
                    o.invalidate();
                }
            });
        }
        private static int lum(byte[] p, int i) {
            return ((p[i] & 255) * 54 + (p[i + 1] & 255) * 183 + (p[i + 2] & 255) * 19) >> 8;
        }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), d = getResources().getDisplayMetrics().density;
        if (peaking && peakCount > 0) {
            paint.setColor(0xFFFF3B30);
            paint.setStyle(Paint.Style.FILL);
            float r = 1.6f * d;
            for (int i = 0; i < peakCount; i++) c.drawCircle(peakPts[2 * i] * w, peakPts[2 * i + 1] * h, r, paint);
        }
        float bottom = h * 0.80f;       // above the shutter row
        if (histogram) {
            float hw = 150 * d, hh = 64 * d, x0 = 14 * d, y0 = bottom - hh - (zones ? 34 * d : 0);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x66000000);
            c.drawRoundRect(x0 - 6 * d, y0 - 6 * d, x0 + hw + 6 * d, y0 + hh + 6 * d, 8 * d, 8 * d, paint);
            int[] cols = {0xAAFF4D4D, 0xAA4DFF6A, 0xAA4D8CFF, 0xCCFFFFFF};
            for (int k = 0; k < 4; k++) {
                paint.setColor(cols[k]);
                paint.setStyle(k == 3 ? Paint.Style.FILL : Paint.Style.STROKE);
                paint.setStrokeWidth(1.2f * d);
                if (k == 3) paint.setAlpha(90);
                float bw = hw / 64f;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(x0, y0 + hh);
                for (int i = 0; i < 64; i++) {
                    float v = (float) Math.sqrt(hist[k][i] / (float) histMax);   // sqrt keeps the tails visible
                    path.lineTo(x0 + i * bw, y0 + hh - Math.min(1f, v) * hh);
                }
                path.lineTo(x0 + hw, y0 + hh);
                c.drawPath(path, paint);
            }
        }
        if (zones) {
            float zw = 150 * d, zh = 14 * d, x0 = 14 * d, y0 = bottom - zh;
            paint.setStyle(Paint.Style.FILL);
            for (int z = 0; z < 11; z++) {
                int g = (int) (255 * Math.pow(Math.pow(2, z - 5) * 0.18, 1 / 2.2));
                g = Math.max(0, Math.min(255, g));
                paint.setColor(0xFF000000 | (g << 16) | (g << 8) | g);
                c.drawRect(x0 + z * zw / 11f, y0, x0 + (z + 1) * zw / 11f, y0 + zh, paint);
            }
            if (!Float.isNaN(meanZone)) {
                float zc = Math.max(0f, Math.min(10.99f, meanZone));
                float mx = x0 + (zc + 0.5f) * zw / 11f - 0.5f;
                paint.setColor(0xFFFFB300);
                c.drawRect(mx - 2 * d, y0 - 4 * d, mx + 2 * d, y0 + zh + 4 * d, paint);
                paint.setTextSize(11 * d);
                c.drawText(String.format(Locale.US, "Zone %s  (%+.1f)", roman(Math.round(zc)), meanZone - 5f),
                    x0, y0 - 8 * d, paint);
            }
        }
    }

    private static String roman(int z) {
        String[] r = {"0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return r[Math.max(0, Math.min(10, z))];
    }
}
