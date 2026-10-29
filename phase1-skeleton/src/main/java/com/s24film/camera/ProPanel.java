package com.s24film.camera;

import android.app.Activity;
import android.content.SharedPreferences;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * PRO mode: a row of values (ISO, speed, WB, tint, focus, aperture, EV) on the bottom edge of the
 * preview. Tapping one opens the ruler dial above the row for it; tapping it again closes the dial.
 * Manual ISO, speed, WB, tint and focus only apply while PRO is the active mode: leaving PRO puts the
 * engine back on auto, entering it restores the last PRO values. Aperture and EV are shared with the
 * rest of the app (portrait blur and the Look tab), so they are not reset.
 * The viewfinder aids (peaking, histogram, zones) and New roll live on the Display tab.
 */
final class ProPanel {
    private static final int[] ISO_STEPS = {25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200};
    private static final int WB_MIN = 2500, WB_STEP = 100, WB_COUNT = 71;   // 2500..9500 K
    private static final int FOCUS_STEPS = 20;

    static final int P_ISO = 0, P_SPEED = 1, P_WB = 2, P_TINT = 3, P_FOCUS = 4, P_AP = 5, P_EV = 6;
    private static final String[] NAMES = {"ISO", "SPEED", "WB", "TINT", "FOCUS", "f/", "EV"};

    interface Host {
        void onNewRoll();
        float currentEv();
        void onProEv(float stops);
    }

    private final Activity act;
    private final CameraEngine engine;
    private final FilmUi filmUi;
    private final Host host;
    private final ProOverlay overlay;
    private final SharedPreferences prefs;
    private final View bar;
    private final RulerDial ruler;
    private final TextView[] items = new TextView[NAMES.length];
    private final Button btnPeak, btnHist, btnZones;
    private final int[] pos = new int[NAMES.length];   // ruler position per value (0 = AUTO where it has one)
    private int open = -1;                             // value whose dial is showing
    private boolean active;
    private boolean peaking, histogram, zones;

    ProPanel(Activity a, CameraEngine e, FilmUi f, FilmFx fx, Host h) {
        act = a; engine = e; filmUi = f; host = h;
        prefs = a.getSharedPreferences("ui", 0);
        overlay = a.findViewById(R.id.pro_overlay);
        bar = a.findViewById(R.id.pro_bar);
        ruler = a.findViewById(R.id.pro_ruler);
        ruler.fx = fx;
        ruler.listener = new Dial(this);

        pos[P_ISO] = prefs.getInt("pro_iso", 0);
        pos[P_SPEED] = prefs.getInt("pro_speed", 0);
        pos[P_WB] = prefs.getInt("pro_wb", 0);
        pos[P_TINT] = prefs.getInt("pro_tint", 10);
        pos[P_FOCUS] = 0;   // focus range depends on the lens, so a session starts on AF
        pos[P_AP] = 0;   // f/1.8, the engine's default

        LinearLayout row = a.findViewById(R.id.pro_items);
        for (int i = 0; i < items.length; i++) {
            TextView t = new TextView(a);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(2);
            t.setIncludeFontPadding(false);
            t.setBackgroundResource(R.drawable.bg_pro_item);
            t.setOnClickListener(new ItemClick(this, i));
            row.addView(t, new LinearLayout.LayoutParams(0, -1, 1f));
            items[i] = t;
        }

        btnPeak = a.findViewById(R.id.btn_pro_peaking);
        btnHist = a.findViewById(R.id.btn_pro_histogram);
        btnZones = a.findViewById(R.id.btn_pro_zones);
        peaking = prefs.getBoolean("pro_peaking", false);
        histogram = prefs.getBoolean("pro_hist", false);
        zones = prefs.getBoolean("pro_zones", false);
        btnPeak.setOnClickListener(new Toggle(this, 0));
        btnHist.setOnClickListener(new Toggle(this, 1));
        btnZones.setOnClickListener(new Toggle(this, 2));
        a.findViewById(R.id.btn_pro_roll).setOnClickListener(new RollClick(host));
        refreshAids();
        refreshItems();
    }

    void onPause() { overlay.pause(); }
    void onResume() { overlay.resume(); }

    boolean isActive() { return active; }

    /** Enter or leave PRO mode. */
    void setActive(boolean on) {
        active = on;
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) closeDial();
        if (on) {
            for (int p = P_ISO; p <= P_FOCUS; p++) apply(p);
        } else {
            engine.setManualIso(0);
            engine.setManualShutterNs(0L);
            engine.setUserWbKelvin(0);
            engine.setUserWbTintStops(0f);
            engine.setManualFocus(-1f);
        }
        refreshItems();
    }

    /** A lens change resets focus to AF and clamps ISO to the new lens. */
    void onLensChanged() {
        pos[P_FOCUS] = 0;
        if (active) {
            apply(P_ISO);
            apply(P_FOCUS);
        }
        if (open == P_FOCUS) ruler.setIndex(0);
        refreshItems();
    }

    /** Values that live elsewhere too (EV slider, film-theme aperture dial) changed: redraw the row. */
    void refreshItems() {
        for (int i = 0; i < items.length; i++) {
            String v = valueText(i);
            boolean manual = isManual(i);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(NAMES[i]);
            sb.setSpan(new ForegroundColorSpan(0x99FFFFFF), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(9, true), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append('\n');
            int s = sb.length();
            sb.append(v);
            sb.setSpan(new ForegroundColorSpan(manual ? 0xFFFFC845 : 0xFFFFFFFF), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new AbsoluteSizeSpan(13, true), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i].setText(sb);
            items[i].setSelected(i == open);
        }
    }

    private boolean isManual(int p) {
        if (p == P_TINT) return pos[p] != 10;
        if (p == P_AP) return false;
        if (p == P_EV) return Math.abs(host.currentEv()) > 0.05f;
        return pos[p] > 0;
    }

    private String valueText(int p) {
        switch (p) {
            case P_ISO: {
                int iso = isoFor(pos[p]);
                return iso > 0 ? Integer.toString(iso) : "AUTO";
            }
            case P_SPEED:
                return pos[p] == 0 ? "AUTO" : FilmUi.SPEED_SHOWN[pos[p]];
            case P_WB:
                return pos[p] == 0 ? "AUTO" : (WB_MIN + (pos[p] - 1) * WB_STEP) + "K";
            case P_TINT: {
                int t = pos[p] - 10;
                return t == 0 ? "0" : (t > 0 ? "M" : "G") + Math.abs(t);
            }
            case P_FOCUS: {
                if (pos[p] == 0) return "AF";
                float dpt = focusDiopters(pos[p]);
                return dpt < 0.05f ? "∞" : String.format(Locale.US, "%.2fm", 1f / dpt);
            }
            case P_AP:
                return FilmUi.APERTURE_LABELS[apertureIndex()];
            default: {
                float ev = host.currentEv();
                return Math.abs(ev) < 0.05f ? "0.0" : String.format(Locale.US, "%+.1f", ev);
            }
        }
    }

    private int apertureIndex() {
        float f = engine.getVirtualAperture();
        int best = 0;
        for (int i = 1; i < FilmUi.APERTURE_F.length; i++) {
            if (Math.abs(FilmUi.APERTURE_F[i] - f) < Math.abs(FilmUi.APERTURE_F[best] - f)) best = i;
        }
        return best;
    }

    private float focusDiopters(int p) {
        return engine.getMinFocusDiopters() * (p - 1) / (float) (FOCUS_STEPS - 1);
    }

    private int isoFor(int p) {
        if (p <= 0) return 0;
        int iso = ISO_STEPS[Math.min(ISO_STEPS.length - 1, p - 1)];
        return Math.max(engine.getDeviceMinIso(), Math.min(engine.getDeviceMaxIso(), iso));
    }

    /** Pushes one PRO value to the engine. */
    private void apply(int p) {
        switch (p) {
            case P_ISO:
                engine.setManualIso(isoFor(pos[p]));
                break;
            case P_SPEED: {
                long ns = MainActivity.SPEED_NS[pos[p]];
                if (ns < 0) ns = engine.getDeviceMaxShutterNs();
                engine.setManualShutterNs(ns);
                break;
            }
            case P_WB:
                engine.setUserWbKelvin(pos[p] == 0 ? 0 : WB_MIN + (pos[p] - 1) * WB_STEP);
                break;
            case P_TINT:
                engine.setUserWbTintStops((pos[p] - 10) / 10f);
                break;
            case P_FOCUS:
                engine.setManualFocus(pos[p] == 0 ? -1f : focusDiopters(pos[p]));
                break;
            case P_AP:
                engine.setVirtualAperture(FilmUi.APERTURE_F[pos[p]]);
                if (filmUi != null) filmUi.setApertureIndex(pos[p]);
                break;
            default:
                host.onProEv((pos[p] - 20) / 10f);
                break;
        }
    }

    private void save() {
        prefs.edit().putInt("pro_iso", pos[P_ISO]).putInt("pro_speed", pos[P_SPEED]).putInt("pro_wb", pos[P_WB])
            .putInt("pro_tint", pos[P_TINT]).putInt("pro_focus", pos[P_FOCUS]).apply();
    }

    private void closeDial() {
        open = -1;
        ruler.setVisibility(View.GONE);
    }

    private void onItem(int p) {
        if (open == p) {
            closeDial();
            refreshItems();
            return;
        }
        open = p;
        if (p == P_AP) pos[p] = apertureIndex();
        if (p == P_EV) pos[p] = Math.round(host.currentEv() * 10f) + 20;
        ruler.setScale(scale(p), pos[p], spacing(p));
        ruler.setVisibility(View.VISIBLE);
        refreshItems();
    }

    private static float spacing(int p) {
        if (p == P_SPEED || p == P_AP) return 44;
        if (p == P_ISO) return 30;
        return 12;
    }

    private String[] scale(int p) {
        String[] s;
        switch (p) {
            case P_ISO:
                s = new String[ISO_STEPS.length + 1];
                s[0] = "AUTO";
                for (int i = 0; i < ISO_STEPS.length; i++) {
                    int iso = ISO_STEPS[i];
                    // full stops get a label, thirds are minor ticks
                    s[i + 1] = (iso == 25 || iso == 50 || iso == 100 || iso == 200 || iso == 400 || iso == 800
                        || iso == 1600 || iso == 3200) ? Integer.toString(iso) : "";
                }
                return s;
            case P_SPEED:
                return FilmUi.SPEED_SHOWN.clone();
            case P_WB:
                s = new String[WB_COUNT + 1];
                s[0] = "AUTO";
                for (int i = 0; i < WB_COUNT; i++) {
                    int k = WB_MIN + i * WB_STEP;
                    s[i + 1] = (k % 1000 == 0) ? (k / 1000) + "K" : "";
                }
                return s;
            case P_TINT:
                s = new String[21];
                for (int i = 0; i < 21; i++) {
                    int t = i - 10;
                    s[i] = (t == 0) ? "0" : (t == -10 ? "G" : (t == 10 ? "M" : (t % 5 == 0 ? Integer.toString(Math.abs(t)) : "")));
                }
                return s;
            case P_FOCUS:
                s = new String[FOCUS_STEPS + 1];
                s[0] = "AF";
                s[1] = "∞";
                for (int i = 2; i <= FOCUS_STEPS; i++) s[i] = (i == FOCUS_STEPS) ? "MIN" : "";
                for (int i = 5; i < FOCUS_STEPS; i += 5) {
                    float dpt = focusDiopters(i);
                    if (dpt > 0.05f) s[i] = String.format(Locale.US, "%.1fm", 1f / dpt);
                }
                return s;
            case P_AP:
                return FilmUi.APERTURE_LABELS.clone();
            default:
                s = new String[41];
                for (int i = 0; i < 41; i++) {
                    int t = i - 20;
                    s[i] = (t % 10 == 0) ? (t == 0 ? "0" : String.format(Locale.US, "%+d", t / 10)) : "";
                }
                return s;
        }
    }

    private void onDial(int index) {
        if (open < 0) return;
        pos[open] = index;
        apply(open);
        save();
        refreshItems();
    }

    private void refreshAids() {
        btnPeak.setSelected(peaking);  btnPeak.setText(peaking ? "Peaking: On" : "Peaking: Off");
        btnHist.setSelected(histogram); btnHist.setText(histogram ? "Histogram: On" : "Histogram: Off");
        btnZones.setSelected(zones);   btnZones.setText(zones ? "Zones: On" : "Zones: Off");
        overlay.setAids(peaking, histogram, zones);
    }

    // Static with an explicit owner: d8 8.2 crashes on javac 21's inner-class constructor metadata.
    static final class Toggle implements View.OnClickListener {
        private final ProPanel p;
        private final int which;
        Toggle(ProPanel p, int which) { this.p = p; this.which = which; }
        @Override public void onClick(View v) {
            if (which == 0) p.peaking = !p.peaking; else if (which == 1) p.histogram = !p.histogram; else p.zones = !p.zones;
            p.prefs.edit().putBoolean("pro_peaking", p.peaking).putBoolean("pro_hist", p.histogram)
                .putBoolean("pro_zones", p.zones).apply();
            p.refreshAids();
        }
    }

    static final class ItemClick implements View.OnClickListener {
        private final ProPanel p;
        private final int which;
        ItemClick(ProPanel p, int which) { this.p = p; this.which = which; }
        @Override public void onClick(View v) {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            p.onItem(which);
        }
    }

    static final class Dial implements RulerDial.Listener {
        private final ProPanel p;
        Dial(ProPanel p) { this.p = p; }
        @Override public void onRuler(RulerDial v, int index) { p.onDial(index); }
    }

    static class RollClick implements View.OnClickListener {
        private final Host h;
        RollClick(Host h) { this.h = h; }
        @Override public void onClick(View v) { h.onNewRoll(); }
    }
}
