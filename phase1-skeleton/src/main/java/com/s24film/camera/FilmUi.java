package com.s24film.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import java.util.Locale;
import java.util.Random;

/**
 * Film-camera interface: a leatherette body with a chrome top plate, knurled shutter-speed and aperture
 * dials, a film-advance lever, a mechanical frame counter, a shutter release, a film-canister window and a
 * viewfinder data strip. Everything is drawn in code, so a theme is just a palette.
 */
final class FilmUi implements View.OnClickListener {

    interface Callbacks {
        void onRelease();
        void onWound();
        void onRewind();
        void onStockTap();
        void onStockLong();
        void onLens(int index);
        void onMenu();
        void onMode(boolean movie);
        void onMotor(boolean on);
        void onSpeed(int index);
        void onAperture(int index);
    }

    static final class Theme {
        final String brand, model;
        final int leather, leatherHi, plateHi, plateLo, edge, engrave, dialFace, dialText, special;
        final int dome, led, ledOff, accent;
        final boolean frameLines;

        Theme(String brand, String model, int leather, int leatherHi, int plateHi, int plateLo, int edge,
              int engrave, int dialFace, int dialText, int special, int dome, int led, int ledOff,
              int accent, boolean frameLines) {
            this.brand = brand; this.model = model;
            this.leather = leather; this.leatherHi = leatherHi;
            this.plateHi = plateHi; this.plateLo = plateLo; this.edge = edge; this.engrave = engrave;
            this.dialFace = dialFace; this.dialText = dialText; this.special = special;
            this.dome = dome; this.led = led; this.ledOff = ledOff; this.accent = accent;
            this.frameLines = frameLines;
        }
    }

    // Black SLR: black leatherette, dark-chrome plate, red release.
    static final Theme SLR = new Theme("S24 FILM", "SLR-2400",
        0xFF0D0D0E, 0xFF1C1C1E, 0xFFD8D8DA, 0xFF77787B, 0xFF2A2A2C, 0xFF1A1A1A,
        0xFF202022, 0xFFF1F1F1, 0xFFFF7A1A, 0xFFC8102E, 0xFFFF3B2F, 0xFF3A1210, 0xFFFFC857, false);
    // Silver rangefinder: brown leather, bright chrome plate, brass release, frame lines.
    static final Theme RF = new Theme("S24 FILM", "RANGEFINDER M",
        0xFF2A1F17, 0xFF3A2C20, 0xFFF1F1EF, 0xFFA3A39F, 0xFF4A3A2C, 0xFF222222,
        0xFFE9E9E6, 0xFF1B1B1B, 0xFFC8102E, 0xFFB08D57, 0xFFFFB000, 0xFF3A2B08, 0xFFFFC857, true);

    // The live preview is a 3:4 image centred in the full-screen surface, so the viewfinder window is whatever
    // is left between the top and bottom bands once the width has taken its 4/3 share of the height.
    static int holeTop(int w, int h) { return Math.max(0, Math.round((h - w * 4f / 3f) / 2f)); }
    static int holeBottom(int w, int h) { return h - holeTop(w, h); }

    static final String[] SPEED_LABELS = {"A", "1000", "500", "250", "125", "60", "30", "15", "8", "4", "2", "1", "B"};
    static final long[] SPEED_MS = {0, 1, 2, 4, 8, 16, 33, 66, 125, 250, 500, 1000, 2000};
    static final String[] SPEED_SHOWN = {"AUTO", "1/1000", "1/500", "1/250", "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1\"", "B"};
    static final String[] APERTURE_LABELS = {"1.8", "2.8", "4", "5.6", "8", "11", "16"};
    static final float[] APERTURE_F = {1.8f, 2.8f, 4f, 5.6f, 8f, 11f, 16f};

    private static final String[] STOCK_BRAND = {"ACEScg", "PORTRA", "EKTAR", "TRI-X", "VISION3", "CINESTILL",
        "VELVIA", "HP5+", "KODACHROME", "SUPERIA", "LOG C4", "AEROCHROME"};
    private static final int[] STOCK_ISO = {0, 400, 100, 400, 500, 800, 100, 400, 64, 400, 800, 400};
    private static final int[] STOCK_COLOR = {0xFF7A7A7A, 0xFFE59A2B, 0xFFC62828, 0xFFF2C500, 0xFF2D6CC0,
        0xFF2E9E8F, 0xFF2E8B3D, 0xFF8E8E8E, 0xFFD32F2F, 0xFF2E7D32, 0xFF3A3A3A, 0xFFC2185B};

    private final Context ctx;
    private final float d;
    private final Callbacks cb;
    final FilmFx fx;
    private Theme theme = SLR;

    private final BodyView body;
    private final DialView speedDial, apertureDial;
    private final KnobView rewindKnob;
    private final LeverView lever;
    private final CounterView counter;
    private final ReleaseButton release;
    private final CanisterView canister;
    private final DataStripView strip;
    private final ShotOverlay shot;
    private final ChromeButton[] lens = new ChromeButton[4];
    private final ChromeButton btnMenu, btnMode, btnMotor;

    FilmUi(Context context, FrameLayout root, Callbacks callbacks, FilmFx effects) {
        ctx = context;
        d = context.getResources().getDisplayMetrics().density;
        cb = callbacks;
        fx = effects;
        android.util.DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int holeTop = holeTop(dm.widthPixels, dm.heightPixels), holeBottom = holeBottom(dm.widthPixels, dm.heightPixels);

        body = new BodyView(context);
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));

        shot = new ShotOverlay(context);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-1, holeBottom - holeTop);
        slp.topMargin = holeTop;
        root.addView(shot, slp);

        strip = new DataStripView(context);
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(-1, dp(30));
        dlp.topMargin = holeBottom - dp(30);
        root.addView(strip, dlp);
        root.addOnLayoutChangeListener(new HoleListener(this));

        // ---- top plate ----
        rewindKnob = new KnobView(context);
        root.addView(rewindKnob, lp(dp(66), dp(66), dp(10), dp(44), Gravity.TOP | Gravity.START));

        counter = new CounterView(context);
        root.addView(counter, lp(dp(66), dp(34), dp(10), dp(120), Gravity.TOP | Gravity.START));

        speedDial = new DialView(context, SPEED_LABELS, 0);
        root.addView(speedDial, lp(dp(124), dp(124), dp(88), dp(36), Gravity.TOP | Gravity.START));

        release = new ReleaseButton(context);
        release.setOnClickListener(this);
        root.addView(release, lp(dp(64), dp(64), dp(224), dp(46), Gravity.TOP | Gravity.START));

        lever = new LeverView(context);
        root.addView(lever, lp(dp(150), dp(86), dp(10), dp(82), Gravity.TOP | Gravity.END));

        // ---- bottom region ----
        apertureDial = new DialView(context, APERTURE_LABELS, 0);
        FrameLayout.LayoutParams alp = lp(dp(100), dp(100), 0, dp(6), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        alp.bottomMargin = dp(46);
        alp.topMargin = 0;
        root.addView(apertureDial, alp);

        canister = new CanisterView(context);
        FrameLayout.LayoutParams clp = lp(dp(112), dp(86), dp(12), 0, Gravity.BOTTOM | Gravity.START);
        clp.bottomMargin = dp(54);
        root.addView(canister, clp);

        String[] lensLabels = {"0.6", "1", "3", "SELF"};
        for (int i = 0; i < 4; i++) {
            lens[i] = new ChromeButton(context, lensLabels[i], true);
            lens[i].action = 100 + i;
            lens[i].setOnClickListener(this);
            FrameLayout.LayoutParams l = lp(dp(40), dp(40), 0, 0, Gravity.BOTTOM | Gravity.END);
            l.rightMargin = dp(12) + (1 - (i % 2)) * dp(46);
            l.bottomMargin = dp(54) + (1 - (i / 2)) * dp(46);
            root.addView(lens[i], l);
        }

        btnMenu = new ChromeButton(context, "MENU", false);
        btnMenu.action = 1;
        btnMode = new ChromeButton(context, "STILL", false);
        btnMode.action = 2;
        btnMotor = new ChromeButton(context, "MOTOR", false);
        btnMotor.action = 3;
        ChromeButton[] pills = {btnMenu, btnMode, btnMotor};
        for (int i = 0; i < 3; i++) {
            pills[i].setOnClickListener(this);
            FrameLayout.LayoutParams l = lp(dp(92), dp(32), 0, 0, Gravity.BOTTOM | Gravity.START);
            l.leftMargin = dp(14) + i * (dp(92) + dp(16)) + dp(0);
            l.bottomMargin = dp(12);
            root.addView(pills[i], l);
        }

        rewindKnob.ui = this;
        speedDial.ui = this;
        apertureDial.ui = this;
        lever.ui = this;
        canister.ui = this;
        speedDial.listener = new SpeedListener(this, true);
        apertureDial.listener = new SpeedListener(this, false);
        setTheme(SLR);
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private FrameLayout.LayoutParams lp(int w, int h, int left, int top, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.leftMargin = left;
        p.topMargin = top;
        return p;
    }

    /** Re-fits the shot overlay and data strip to the viewfinder window whenever the root is laid out. */
    static final class HoleListener implements View.OnLayoutChangeListener {
        private final FilmUi ui;

        HoleListener(FilmUi ui) { this.ui = ui; }

        @Override
        public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
            int w = r - l, h = b - t;
            if (w <= 0 || h <= 0) return;
            int top = holeTop(w, h), bottom = holeBottom(w, h);
            FrameLayout.LayoutParams slp = (FrameLayout.LayoutParams) ui.shot.getLayoutParams();
            if (slp.topMargin != top || slp.height != bottom - top) {
                slp.topMargin = top;
                slp.height = bottom - top;
                ui.shot.requestLayout();
            }
            FrameLayout.LayoutParams dlp = (FrameLayout.LayoutParams) ui.strip.getLayoutParams();
            int stripTop = bottom - ui.dp(30);
            if (dlp.topMargin != stripTop) {
                dlp.topMargin = stripTop;
                ui.strip.requestLayout();
            }
        }
    }

    static final class SpeedListener implements DialView.Listener {
        private final FilmUi ui;
        private final boolean speed;

        SpeedListener(FilmUi ui, boolean speed) {
            this.ui = ui;
            this.speed = speed;
        }

        @Override
        public void onDial(DialView v, int index) {
            if (speed) ui.cb.onSpeed(index); else ui.cb.onAperture(index);
        }
    }

    void setTheme(Theme t) {
        theme = t;
        body.setTheme(t);
        speedDial.theme = t; apertureDial.theme = t; rewindKnob.theme = t;
        lever.theme = t; counter.theme = t; release.theme = t; canister.theme = t;
        strip.theme = t;
        btnMenu.theme = t; btnMode.theme = t; btnMotor.theme = t;
        for (ChromeButton b : lens) b.theme = t;
        body.invalidate();
        speedDial.invalidate(); apertureDial.invalidate(); rewindKnob.invalidate(); lever.invalidate();
        counter.invalidate(); release.invalidate(); canister.invalidate(); strip.invalidate();
        btnMenu.invalidate(); btnMode.invalidate(); btnMotor.invalidate();
        for (ChromeButton b : lens) b.invalidate();
    }

    // ---------- state pushed in by MainActivity ----------
    void setData(String speedShown, String aperture, String ei, int frame, boolean cocked, float meterEv) {
        strip.speed = speedShown; strip.aperture = aperture; strip.ei = ei;
        strip.frame = frame; strip.cocked = cocked; strip.meterEv = meterEv;
        strip.invalidate();
        counter.setValue(frame);
        lever.canWind = !cocked;
    }

    void setCanister(int stockIndex, String name) {
        canister.setStock(stockIndex, name);
    }

    /** Canister for a look picked from the browser (any of the 120): label, box ISO and category colour. */
    void setCanisterLook(String brand, int iso, int color, String name) {
        canister.setPicked(brand, iso, color, name);
    }

    void setActiveLens(int index) {
        for (int i = 0; i < 4; i++) lens[i].lit = (i == index);
        for (ChromeButton b : lens) b.invalidate();
    }

    void setMovie(boolean movie) { btnMode.label = movie ? "MOVIE" : "STILL"; btnMode.lit = movie; btnMode.invalidate(); }
    void setMotor(boolean on) { btnMotor.lit = on; btnMotor.invalidate(); }

    void playShot(long holdMs, float apertureF) { shot.play(holdMs, apertureF); }
    void flashNeedWind() { strip.flash(); }

    int speedIndex() { return speedDial.idx; }
    int apertureIndex() { return apertureDial.idx; }
    void setApertureIndex(int i) { apertureDial.idx = i; apertureDial.shown = i; apertureDial.invalidate(); }

    @Override
    public void onClick(View v) {
        if (v == release) { cb.onRelease(); return; }
        int a = (v instanceof ChromeButton) ? ((ChromeButton) v).action : -1;
        fx.click();
        if (a == 1) cb.onMenu();
        else if (a == 2) cb.onMode(!btnMode.lit);
        else if (a == 3) cb.onMotor(!btnMotor.lit);
        else if (a >= 100 && a < 104) cb.onLens(a - 100);
    }

    // =====================================================================================
    //  Views
    // =====================================================================================

    static Paint paint() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        return p;
    }

    static int mix(int a, int b, float t) {
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.rgb(r, g, bl);
    }

    static Shader chrome(float cx, float cy, int hi, int lo) {
        int[] c = {hi, lo, hi, lo, hi, lo, hi, lo, hi};
        return new SweepGradient(cx, cy, c, null);
    }

    static final class BodyView extends View {
        private Theme th = SLR;
        private final Paint fill = paint(), line = paint(), text = paint();
        private BitmapShader leather;
        private final float d;

        BodyView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }

        void setTheme(Theme t) {
            th = t;
            Bitmap tile = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Random r = new Random(11);
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 96; x++) {
                    int n = r.nextInt(17) - 8;
                    float hi = ((x * 7 + y * 13) % 11 == 0) ? 0.35f : 0f;
                    int base = mix(t.leather, t.leatherHi, hi + r.nextFloat() * 0.35f);
                    tile.setPixel(x, y, Color.rgb(
                        clamp(Color.red(base) + n), clamp(Color.green(base) + n), clamp(Color.blue(base) + n)));
                }
            }
            leather = new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            invalidate();
        }

        private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float top = holeTop((int) w, (int) h), bot = holeBottom((int) w, (int) h);
            fill.setShader(leather);
            // leave the viewfinder window clear so the live preview shows through
            c.drawRect(0, 0, w, top, fill);
            c.drawRect(0, bot, w, h, fill);
            fill.setShader(null);

            // chrome top plate
            fill.setShader(new LinearGradient(0, d * 30, 0, top, new int[]{th.plateHi, th.plateLo, th.plateHi, th.plateLo},
                new float[]{0f, 0.45f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, d * 30, w, top, fill);
            fill.setShader(null);
            Random r = new Random(5);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(1f);
            for (int i = 0; i < 90; i++) {
                float y = d * 30 + r.nextFloat() * (top - d * 30);
                line.setColor(r.nextBoolean() ? 0x14FFFFFF : 0x14000000);
                c.drawLine(0, y, w, y, line);
            }
            line.setStrokeWidth(d * 2);
            line.setColor(th.edge);
            c.drawLine(0, top, w, top, line);
            line.setColor(0x88FFFFFF);
            c.drawLine(0, d * 30 + d, w, d * 30 + d, line);
            line.setColor(th.edge);
            c.drawLine(0, d * 30, w, d * 30, line);

            // engraved brand
            text.setColor(th.engrave);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(d * 10.5f);
            text.setLetterSpacing(0.3f);
            c.drawText(th.brand, w * 0.84f, d * 50, text);
            text.setTextSize(d * 6.5f);
            text.setLetterSpacing(0.15f);
            c.drawText(th.model, w * 0.84f, d * 61, text);
            text.setLetterSpacing(0f);

            // screws
            float[][] sc = {{d * 8, d * 38}, {w - d * 8, d * 38}, {d * 8, top - d * 8}, {w - d * 8, top - d * 8}};
            for (float[] p : sc) screw(c, p[0], p[1]);

            // bottom trim
            fill.setShader(new LinearGradient(0, bot, 0, bot + d * 5, th.plateHi, th.plateLo, Shader.TileMode.CLAMP));
            c.drawRect(0, bot, w, bot + d * 5, fill);
            fill.setShader(null);

            // viewfinder bevel
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 3);
            line.setColor(0xFF050505);
            c.drawRect(d * 1.5f, top + d * 1.5f, w - d * 1.5f, bot - d * 1.5f, line);

            // gentle corner vignette over the live image
            fill.setShader(new RadialGradient(w / 2f, (top + bot) / 2f, (float) Math.hypot(w / 2f, (bot - top) / 2f),
                new int[]{0x00000000, 0x00000000, 0x55000000}, new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, top, w, bot, fill);
            fill.setShader(null);

            if (th.frameLines) {
                line.setColor(0xCCFFFFFF);
                line.setStrokeWidth(d * 1.4f);
                float l = w * 0.09f, t2 = top + (bot - top) * 0.10f, rr = w - l, bb = bot - (bot - top) * 0.10f, k = d * 18;
                float[][] cn = {{l, t2, 1, 1}, {rr, t2, -1, 1}, {l, bb, 1, -1}, {rr, bb, -1, -1}};
                for (float[] q : cn) {
                    c.drawLine(q[0], q[1], q[0] + q[2] * k, q[1], line);
                    c.drawLine(q[0], q[1], q[0], q[1] + q[3] * k, line);
                }
                fill.setColor(0x33FFE08A);
                float cx = w / 2f, cy = (top + bot) / 2f;
                c.drawRect(cx - d * 16, cy - d * 12, cx + d * 16, cy + d * 12, fill);
                line.setColor(0x99FFE08A);
                line.setStrokeWidth(d);
                c.drawRect(cx - d * 16, cy - d * 12, cx + d * 16, cy + d * 12, line);
            }
        }

        private void screw(Canvas c, float x, float y) {
            fill.setShader(new RadialGradient(x - d, y - d, d * 6, 0xFFF4F4F4, 0xFF6C6C6C, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, d * 4.5f, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 1.2f);
            line.setColor(0xFF2B2B2B);
            c.drawLine(x - d * 3, y + d, x + d * 3, y - d, line);
        }
    }

    /** Knurled, clicking rotary dial that rotates under a fixed index mark. */
    static final class DialView extends View {
        interface Listener { void onDial(DialView v, int index); }

        Theme theme = SLR;
        FilmUi ui;
        Listener listener;
        final String[] labels;
        int idx;
        private float shown, dragIdx, startIdx, lastAng, acc;
        private boolean dragging;
        private final float d, stepDeg = 27f;
        private final Paint fill = paint(), line = paint(), text = paint();
        private final Path tri = new Path();

        DialView(Context c, String[] labels, int idx) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            this.labels = labels;
            this.idx = idx;
            this.shown = idx;
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        private float ang(MotionEvent e) {
            return (float) Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f, e.getX() - getWidth() / 2f));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true; startIdx = idx; dragIdx = idx; acc = 0; lastAng = ang(e);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float a = ang(e), da = a - lastAng;
                    if (da > 180) da -= 360;
                    if (da < -180) da += 360;
                    lastAng = a; acc += da;
                    float ni = Math.max(0, Math.min(labels.length - 1, startIdx - acc / stepDeg));
                    dragIdx = ni;
                    int r = Math.round(ni);
                    if (r != idx) {
                        idx = r;
                        if (ui != null) ui.fx.detent();
                        if (listener != null) listener.onDial(this, idx);
                    }
                    invalidate();
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    if (Math.abs(acc) < 4f && e.getAction() == MotionEvent.ACTION_UP) {
                        int ni = idx + (e.getX() > getWidth() / 2f ? 1 : -1);
                        ni = Math.max(0, Math.min(labels.length - 1, ni));
                        if (ni != idx) {
                            idx = ni;
                            if (ui != null) ui.fx.detent();
                            if (listener != null) listener.onDial(this, idx);
                        }
                    }
                    invalidate();
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f + d * 4, r = Math.min(w, h) / 2f - d * 7;
            float target = dragging ? dragIdx : idx;
            boolean anim = Math.abs(shown - target) > 0.004f;
            if (anim) shown += (target - shown) * 0.4f; else shown = target;

            fill.setShader(null);
            fill.setColor(0x66000000);
            c.drawCircle(cx, cy + d * 2, r + d * 1.5f, fill);
            fill.setShader(chrome(cx, cy, theme.plateHi, theme.plateLo));
            c.drawCircle(cx, cy, r, fill);
            fill.setShader(null);

            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 1.3f);
            for (int i = 0; i < 54; i++) {
                double a = Math.toRadians(i * (360f / 54f) - shown * stepDeg);
                float x0 = cx + (float) Math.cos(a) * r * 0.90f, y0 = cy + (float) Math.sin(a) * r * 0.90f;
                float x1 = cx + (float) Math.cos(a) * r, y1 = cy + (float) Math.sin(a) * r;
                line.setColor(0x77000000);
                c.drawLine(x0, y0, x1, y1, line);
            }
            line.setColor(0x99000000);
            line.setStrokeWidth(d);
            c.drawCircle(cx, cy, r * 0.82f, line);

            fill.setShader(new RadialGradient(cx - r * 0.2f, cy - r * 0.25f, r * 0.9f,
                mix(theme.dialFace, 0xFFFFFFFF, 0.18f), theme.dialFace, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.80f, fill);
            fill.setShader(null);

            text.setTextSize(r * 0.25f);
            for (int i = 0; i < labels.length; i++) {
                float ang = (i - shown) * stepDeg;
                if (Math.abs(ang) > 76f) continue;
                int col = (labels[i].equals("A") || labels[i].equals("1.8")) ? theme.special : theme.dialText;
                text.setColor(col);
                text.setAlpha((int) (255 * (1f - Math.abs(ang) / 95f)));
                c.save();
                c.rotate(ang, cx, cy);
                c.drawText(labels[i], cx, cy - r * 0.52f, text);
                c.restore();
            }
            // index mark
            tri.reset();
            tri.moveTo(cx - d * 5, cy - r - d * 8);
            tri.lineTo(cx + d * 5, cy - r - d * 8);
            tri.lineTo(cx, cy - r + d * 1);
            tri.close();
            fill.setColor(theme.special);
            c.drawPath(tri, fill);
            if (anim) postInvalidateOnAnimation();
        }
    }

    /** Free-spinning knurled rewind knob; hold it to rewind the roll. */
    static final class KnobView extends View {
        Theme theme = SLR;
        FilmUi ui;
        private float rot, lastAng, accum;
        private boolean down, longFired;
        private final float d;
        private final Paint fill = paint(), line = paint(), text = paint();
        private final LongPress lp = new LongPress(this);

        KnobView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        static final class LongPress implements Runnable {
            private final KnobView v;
            LongPress(KnobView v) { this.v = v; }
            @Override public void run() {
                if (v.down && v.ui != null) {
                    v.longFired = true;
                    v.ui.fx.rewind();
                    v.ui.cb.onRewind();
                }
            }
        }

        private float ang(MotionEvent e) {
            return (float) Math.toDegrees(Math.atan2(e.getY() - getHeight() / 2f, e.getX() - getWidth() / 2f));
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down = true; longFired = false; lastAng = ang(e); accum = 0;
                    postDelayed(lp, 700);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float a = ang(e), da = a - lastAng;
                    if (da > 180) da -= 360;
                    if (da < -180) da += 360;
                    lastAng = a; rot += da; accum += da;
                    if (Math.abs(accum) > 30f) {
                        accum = 0;
                        if (ui != null) ui.fx.click();
                    }
                    invalidate();
                    return true;
                }
                default:
                    down = false;
                    removeCallbacks(lp);
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f - d * 3;
            fill.setShader(null);
            fill.setColor(0x66000000);
            c.drawCircle(cx, cy + d * 2, r + d, fill);
            fill.setShader(chrome(cx, cy, theme.plateHi, theme.plateLo));
            c.drawCircle(cx, cy, r, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 1.3f);
            line.setColor(0x77000000);
            for (int i = 0; i < 40; i++) {
                double a = Math.toRadians(i * 9f + rot);
                c.drawLine(cx + (float) Math.cos(a) * r * 0.86f, cy + (float) Math.sin(a) * r * 0.86f,
                    cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, line);
            }
            fill.setShader(new RadialGradient(cx - r * 0.2f, cy - r * 0.2f, r, 0xFFFFFFFF, theme.plateLo, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r * 0.70f, fill);
            fill.setShader(null);
            c.save();
            c.rotate(rot, cx, cy);
            text.setColor(theme.engrave);
            text.setTextSize(r * 0.34f);
            c.drawText("R", cx, cy + r * 0.12f, text);
            fill.setColor(theme.special);
            c.drawCircle(cx, cy - r * 0.50f, d * 2.2f, fill);
            c.restore();
            text.setTextSize(d * 7);
            text.setColor(theme.engrave);
            c.drawText("REWIND", cx, getHeight() - d * 1f, text);
        }
    }

    /** Film-advance lever: wind it through its stroke to cock the shutter. */
    static final class LeverView extends View {
        Theme theme = SLR;
        FilmUi ui;
        boolean canWind = true;
        private float arm;
        private boolean dragging, fired;
        private int lastTick;
        private final float d, maxA = 66f;
        private final Paint fill = paint(), line = paint(), text = paint();

        LeverView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float px = getWidth() - d * 20, py = getHeight() - d * 20;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    dragging = true;
                    if (!canWind && ui != null) ui.fx.dead();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getX() - px, dy = py - e.getY();
                    float phi = (float) Math.toDegrees(Math.atan2(dy, dx));
                    float a = 180f - phi;
                    if (phi < 0) a = 0;
                    a = Math.max(0f, Math.min(canWind ? maxA : 5f, a));
                    arm = a;
                    int tk = (int) (arm / 13f);
                    if (tk > lastTick && ui != null) ui.fx.leverTick();
                    lastTick = tk;
                    if (canWind && !fired && arm >= maxA - 1.5f) {
                        fired = true;
                        if (ui != null) { ui.fx.advance(); ui.cb.onWound(); }
                    }
                    invalidate();
                    return true;
                }
                default:
                    dragging = false;
                    invalidate();
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float px = getWidth() - d * 20, py = getHeight() - d * 20;
            if (!dragging && arm > 0f) {
                arm = Math.max(0f, arm - Math.max(2.5f, arm * 0.18f));
                if (arm == 0f) { fired = false; lastTick = 0; }
                postInvalidateOnAnimation();
            }
            float L = Math.min(getWidth() - d * 40, d * 118);
            // base boss
            fill.setShader(null);
            fill.setColor(0x66000000);
            c.drawCircle(px, py + d * 2, d * 15, fill);
            fill.setShader(chrome(px, py, theme.plateHi, theme.plateLo));
            c.drawCircle(px, py, d * 14, fill);
            fill.setShader(null);
            c.save();
            c.rotate(arm, px, py);
            RectF armR = new RectF(px - L, py - d * 5, px + d * 4, py + d * 5);
            fill.setShader(new LinearGradient(0, py - d * 5, 0, py + d * 5, theme.plateHi, theme.plateLo, Shader.TileMode.CLAMP));
            c.drawRoundRect(armR, d * 5, d * 5, fill);
            fill.setShader(null);
            RectF tip = new RectF(px - L, py - d * 6, px - L + d * 34, py + d * 6);
            fill.setColor(0xFF161616);
            c.drawRoundRect(tip, d * 6, d * 6, fill);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d);
            line.setColor(0x55FFFFFF);
            c.drawLine(px - L + d * 4, py - d * 3, px - L + d * 30, py - d * 3, line);
            c.restore();
            fill.setShader(new RadialGradient(px - d, py - d, d * 8, 0xFFF4F4F4, 0xFF6C6C6C, Shader.TileMode.CLAMP));
            c.drawCircle(px, py, d * 5.5f, fill);
            fill.setShader(null);
            text.setColor(theme.engrave);
            text.setTextSize(d * 7.5f);
            c.drawText("WIND", getWidth() * 0.58f, getHeight() - d * 3, text);
        }
    }

    /** Mechanical frame counter window. */
    static final class CounterView extends View {
        Theme theme = SLR;
        private float shown, target;
        private final float d;
        private final Paint fill = paint(), text = paint(), line = paint();

        CounterView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        void setValue(int v) {
            if (target != v) { target = v; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF win = new RectF(d * 4, d * 2, w - d * 4, h - d * 4);
            fill.setColor(0xFF0A0A0A);
            c.drawRoundRect(win, d * 4, d * 4, fill);
            boolean anim = Math.abs(shown - target) > 0.01f;
            if (anim) shown += (target - shown) * 0.25f; else shown = target;
            c.save();
            c.clipRect(win);
            int n = (int) Math.floor(shown);
            float frac = shown - n;
            text.setColor(0xFFF2F2F2);
            text.setTextSize(h * 0.52f);
            float bh = win.height();
            c.drawText(String.format(Locale.US, "%02d", n), w / 2f, win.top + bh * 0.72f - frac * bh, text);
            c.drawText(String.format(Locale.US, "%02d", n + 1), w / 2f, win.top + bh * 0.72f + (1 - frac) * bh, text);
            c.restore();
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 1.5f);
            line.setColor(theme.edge);
            c.drawRoundRect(win, d * 4, d * 4, line);
            text.setColor(theme.engrave);
            text.setTextSize(d * 7);
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            c.drawText("EXPOSURES", w / 2f, h - d * 0.5f, text);
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            if (anim) postInvalidateOnAnimation();
        }
    }

    /** Shutter release: chrome collar with a dome button that sinks when pressed. */
    static final class ReleaseButton extends View {
        Theme theme = SLR;
        private boolean pressed;
        private final float d;
        private final Paint fill = paint(), line = paint();

        ReleaseButton(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    pressed = false; invalidate();
                    if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false; invalidate(); return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(getWidth(), getHeight()) / 2f - d * 3;
            fill.setShader(null);
            fill.setColor(0x77000000);
            c.drawCircle(cx, cy + d * 2, r + d, fill);
            fill.setShader(chrome(cx, cy, theme.plateHi, theme.plateLo));
            c.drawCircle(cx, cy, r, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d);
            line.setColor(0x55000000);
            for (int i = 1; i <= 3; i++) c.drawCircle(cx, cy, r * (0.55f + i * 0.1f), line);
            float dr = r * (pressed ? 0.56f : 0.62f);
            float oy = pressed ? d * 1.5f : 0f;
            fill.setShader(new RadialGradient(cx - dr * 0.3f, cy - dr * 0.35f + oy, dr * 1.3f,
                mix(theme.dome, 0xFFFFFFFF, pressed ? 0.12f : 0.35f), mix(theme.dome, 0xFF000000, pressed ? 0.45f : 0.25f),
                Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy + oy, dr, fill);
            fill.setShader(null);
        }
    }

    /** Chrome push-button (round or pill) with an engraved label and an optional indicator LED. */
    static final class ChromeButton extends View {
        Theme theme = SLR;
        String label;
        boolean lit;
        int action;
        private final boolean round;
        private boolean pressed;
        private final float d;
        private final Paint fill = paint(), line = paint(), text = paint();

        ChromeButton(Context c, String label, boolean round) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            this.label = label;
            this.round = round;
            setClickable(true);
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pressed = true; invalidate(); return true;
                case MotionEvent.ACTION_UP:
                    pressed = false; invalidate();
                    if (e.getX() >= 0 && e.getX() <= getWidth() && e.getY() >= 0 && e.getY() <= getHeight()) performClick();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    pressed = false; invalidate(); return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF rf = new RectF(d * 2, d * 2, w - d * 2, h - d * 3);
            float rad = round ? Math.min(w, h) / 2f : d * 8;
            fill.setShader(null);
            fill.setColor(0x77000000);
            c.drawRoundRect(new RectF(rf.left, rf.top + d * 1.5f, rf.right, rf.bottom + d * 1.5f), rad, rad, fill);
            int hi = pressed ? theme.plateLo : theme.plateHi, lo = pressed ? mix(theme.plateLo, 0xFF000000, 0.3f) : theme.plateLo;
            fill.setShader(new LinearGradient(0, rf.top, 0, rf.bottom, hi, lo, Shader.TileMode.CLAMP));
            c.drawRoundRect(rf, rad, rad, fill);
            fill.setShader(null);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d);
            line.setColor(lit ? theme.accent : 0x66000000);
            c.drawRoundRect(rf, rad, rad, line);
            text.setColor(theme.engrave);
            text.setTextSize(round ? (label.length() > 3 ? d * 8.5f : d * 14) : d * 11);
            Paint.FontMetrics fm = text.getFontMetrics();
            c.drawText(label, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2f - d, text);
            fill.setColor(lit ? theme.led : theme.ledOff);
            c.drawCircle(w - d * (round ? 8 : 9), d * (round ? 8 : 9), d * 2.6f, fill);
        }
    }

    /** Film canister window: shows the loaded stock; tap for next stock, hold for the 120-look list. */
    static final class CanisterView extends View {
        Theme theme = SLR;
        private int stock = 1;
        private String name = "";
        private float downX;
        private boolean longFired, down;
        private final float d;
        private final Paint fill = paint(), line = paint(), text = paint();
        private final Hold hold = new Hold(this);
        FilmUi ui;

        CanisterView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setClickable(true);
            text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            text.setTextAlign(Paint.Align.CENTER);
        }

        static final class Hold implements Runnable {
            private final CanisterView v;
            Hold(CanisterView v) { this.v = v; }
            @Override public void run() {
                if (v.down) { v.longFired = true; v.getFilmUi().fx.click(); v.getFilmUi().cb.onStockLong(); }
            }
        }

        private FilmUi getFilmUi() { return ui; }

        private String pickedBrand;
        private int pickedIso, pickedColor;

        void setStock(int s, String n) {
            stock = Math.max(0, Math.min(STOCK_BRAND.length - 1, s)); name = n; pickedBrand = null; invalidate();
        }

        void setPicked(String brand, int iso, int color, String n) {
            pickedBrand = brand; pickedIso = iso; pickedColor = color; name = n; invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    down = true; longFired = false; postDelayed(hold, 600); return true;
                case MotionEvent.ACTION_UP:
                    down = false; removeCallbacks(hold);
                    if (!longFired && ui != null) { ui.fx.click(); ui.cb.onStockTap(); }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    down = false; removeCallbacks(hold); return true;
                default:
                    return true;
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            RectF win = new RectF(d * 2, d * 2, w - d * 2, h - d * 14);
            fill.setColor(0xFF080808);
            c.drawRoundRect(win, d * 6, d * 6, fill);
            // canister body
            RectF can = new RectF(win.left + d * 10, win.top + d * 6, win.right - d * 10, win.bottom - d * 6);
            fill.setShader(new LinearGradient(can.left, 0, can.right, 0,
                new int[]{0xFF555555, 0xFFEFEFEF, 0xFF6A6A6A}, new float[]{0f, 0.3f, 1f}, Shader.TileMode.CLAMP));
            c.drawRoundRect(can, d * 4, d * 4, fill);
            fill.setShader(null);
            RectF lab = new RectF(can.left + d * 4, can.top + d * 10, can.right - d * 4, can.bottom - d * 8);
            fill.setColor(pickedBrand != null ? pickedColor : STOCK_COLOR[stock]);
            c.drawRect(lab, fill);
            fill.setColor(0x33000000);
            c.drawRect(lab.left, lab.bottom - d * 7, lab.right, lab.bottom, fill);
            text.setColor(0xFFFFFFFF);
            String brand = pickedBrand != null ? pickedBrand : STOCK_BRAND[stock];
            int iso = pickedBrand != null ? pickedIso : STOCK_ISO[stock];
            text.setTextSize(d * (brand.length() > 7 ? 9f : 11f));
            c.drawText(brand, lab.centerX(), lab.centerY() - d * 1f, text);
            text.setTextSize(d * 8);
            text.setColor(0xDDFFFFFF);
            c.drawText(iso > 0 ? ("ISO " + iso) : "DIGITAL", lab.centerX(), lab.centerY() + d * 10, text);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d * 2);
            line.setColor(theme.edge);
            c.drawRoundRect(win, d * 6, d * 6, line);
            text.setColor(theme.dialText == 0xFFF1F1F1 ? 0xFFDDDDDD : theme.engrave);
            text.setTextSize(d * 7.5f);
            String shown = (name == null || name.isEmpty()) ? "FILM" : name.toUpperCase(Locale.US);
            if (shown.length() > 20) shown = shown.substring(0, 20);
            c.drawText(shown, w / 2f, h - d * 2f, text);
        }
    }

    /** Viewfinder data strip: shutter, aperture, light-meter needle, EI and frame number. */
    static final class DataStripView extends View {
        Theme theme = SLR;
        String speed = "AUTO", aperture = "f/1.8", ei = "";
        int frame = 1;
        boolean cocked = true;
        float meterEv = 0f;
        private float flash;
        private final float d;
        private final Paint fill = paint(), text = paint(), line = paint();

        DataStripView(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        }

        void flash() { flash = 1f; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            fill.setColor(0xB0000000);
            c.drawRect(0, 0, w, h, fill);
            int led = theme.led;
            text.setColor(led);
            text.setTextSize(d * 12);
            text.setTextAlign(Paint.Align.LEFT);
            float by = h / 2f + d * 4.5f;
            c.drawText(speed + "  f/" + aperture, d * 10, by, text);

            // meter: -2 .. +2 scale with needle
            float mx0 = w * 0.50f, mx1 = w * 0.76f, my = h / 2f;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(d);
            line.setColor(0x99FFFFFF);
            c.drawLine(mx0, my, mx1, my, line);
            for (int i = -2; i <= 2; i++) {
                float x = mx0 + (mx1 - mx0) * (i + 2) / 4f;
                c.drawLine(x, my - d * (i == 0 ? 5 : 3), x, my + d * (i == 0 ? 5 : 3), line);
            }
            float nx = mx0 + (mx1 - mx0) * (Math.max(-2f, Math.min(2f, meterEv)) + 2f) / 4f;
            fill.setColor(led);
            c.drawCircle(nx, my, d * 3.2f, fill);
            text.setTextSize(d * 8);
            text.setColor(0xCCFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            c.drawText("-", mx0 - d * 8, my + d * 3, text);
            c.drawText("+", mx1 + d * 8, my + d * 3, text);

            text.setTextSize(d * 11);
            text.setColor(led);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText((ei.isEmpty() ? "" : ei + "  ") + String.format(Locale.US, "%02d", frame), w - d * 26, by, text);
            boolean blink = flash > 0f && ((int) (flash * 8)) % 2 == 0;
            fill.setColor(cocked ? 0xFF35D06A : (blink ? 0xFFFF3B2F : 0xFF5A1511));
            c.drawCircle(w - d * 12, h / 2f, d * 4f, fill);
            if (flash > 0f) {
                flash -= 0.03f;
                postInvalidateOnAnimation();
            }
        }
    }

    /** Mirror blackout with an iris that stops down, then reopens, over the viewfinder. */
    static final class ShotOverlay extends View {
        private long startMs = 0, holdMs = 80;
        private float stopFrac = 0.3f;
        private boolean active;
        private final Paint fill = paint(), line = paint();
        private final Path hole = new Path(), shade = new Path();
        private final float d;
        private static final int CLOSE = 90, OPEN = 130, BLADES = 7;

        ShotOverlay(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            setClickable(false);
        }

        void play(long hold, float apertureF) {
            holdMs = Math.max(50L, Math.min(2500L, hold));
            stopFrac = Math.max(0.12f, Math.min(0.9f, 1.8f / apertureF + 0.05f));
            startMs = System.currentTimeMillis();
            active = true;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (!active) return;
            long t = System.currentTimeMillis() - startMs;
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            float rMax = (float) Math.hypot(w / 2f, h / 2f) * 1.1f;
            float r;
            if (t < CLOSE) {
                float p = t / (float) CLOSE;
                r = rMax * (1f - p) + rMax * stopFrac * p * 0f;
                r = rMax * (1f - p * (1f - stopFrac * 0.4f));
                if (p > 0.6f) r = rMax * stopFrac * 0.4f * (1f - (p - 0.6f) / 0.4f);
            } else if (t < CLOSE + holdMs) {
                r = 0f;
            } else if (t < CLOSE + holdMs + OPEN) {
                float p = (t - CLOSE - holdMs) / (float) OPEN;
                r = rMax * p;
            } else {
                active = false;
                return;
            }
            float rot = (t % 400) * 0.05f;
            hole.reset();
            for (int i = 0; i < BLADES; i++) {
                double a = Math.toRadians(i * 360.0 / BLADES + rot);
                float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                if (i == 0) hole.moveTo(x, y); else hole.lineTo(x, y);
            }
            hole.close();
            shade.reset();
            shade.setFillType(Path.FillType.EVEN_ODD);
            shade.addRect(0, 0, w, h, Path.Direction.CW);
            shade.addPath(hole);
            fill.setColor(0xFF050505);
            c.drawPath(shade, fill);
            if (r > d * 4) {
                line.setStyle(Paint.Style.STROKE);
                line.setStrokeWidth(d * 1.5f);
                line.setColor(0xFF3A3A3A);
                c.drawPath(hole, line);
                for (int i = 0; i < BLADES; i++) {
                    double a = Math.toRadians(i * 360.0 / BLADES + rot);
                    double b = Math.toRadians((i + 1) * 360.0 / BLADES + rot);
                    float x = cx + (float) Math.cos(a) * r, y = cy + (float) Math.sin(a) * r;
                    float ex = x + (float) Math.cos(b + 1.2) * rMax * 0.5f, ey = y + (float) Math.sin(b + 1.2) * rMax * 0.5f;
                    line.setColor(0x66222222);
                    c.drawLine(x, y, ex, ey, line);
                }
            }
            postInvalidateOnAnimation();
        }
    }
}
