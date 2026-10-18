package com.s24film.camera;

import android.app.Activity;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/** Pro controls on the Camera tab: ISO, white balance, tint, focus, aperture and the shooting aids. */
final class ProPanel {
    private static final int[] ISO_STEPS = {25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 400, 500, 640,
        800, 1000, 1250, 1600, 2000, 2500, 3200};

    interface Host { void onNewRoll(); }

    private final Activity act;
    private final CameraEngine engine;
    private final FilmUi filmUi;
    private final ProOverlay overlay;
    private final SharedPreferences prefs;
    private final TextView tvIso, tvWb, tvTint, tvFocus;
    private final SeekBar sbIso, sbWb, sbTint, sbFocus;
    private final Button btnPeak, btnHist, btnZones;
    private final Button[] apBtns = new Button[FilmUi.APERTURE_F.length];
    private boolean peaking, histogram, zones;

    ProPanel(Activity a, CameraEngine e, FilmUi f, final Host host) {
        act = a; engine = e; filmUi = f;
        prefs = a.getSharedPreferences("ui", 0);
        overlay = a.findViewById(R.id.pro_overlay);
        tvIso = a.findViewById(R.id.tv_pro_iso); sbIso = a.findViewById(R.id.seek_pro_iso);
        tvWb = a.findViewById(R.id.tv_pro_wb); sbWb = a.findViewById(R.id.seek_pro_wb);
        tvTint = a.findViewById(R.id.tv_pro_tint); sbTint = a.findViewById(R.id.seek_pro_tint);
        tvFocus = a.findViewById(R.id.tv_pro_focus); sbFocus = a.findViewById(R.id.seek_pro_focus);
        btnPeak = a.findViewById(R.id.btn_pro_peaking);
        btnHist = a.findViewById(R.id.btn_pro_histogram);
        btnZones = a.findViewById(R.id.btn_pro_zones);

        sbIso.setOnSeekBarChangeListener(new Seek(this, 0));
        sbWb.setOnSeekBarChangeListener(new Seek(this, 1));
        sbTint.setOnSeekBarChangeListener(new Seek(this, 2));
        sbFocus.setOnSeekBarChangeListener(new Seek(this, 3));

        LinearLayout row = a.findViewById(R.id.row_pro_aperture);
        for (int i = 0; i < apBtns.length; i++) {
            Button b = (Button) a.getLayoutInflater().inflate(R.layout.chip_button, row, false);
            b.setText("f/" + FilmUi.APERTURE_LABELS[i]);
            final int idx = i;
            b.setOnClickListener(new ApClick(this, idx));
            row.addView(b);
            apBtns[i] = b;
        }
        apBtns[0].setSelected(true);   // f/1.8, the engine's default

        peaking = prefs.getBoolean("pro_peaking", false);
        histogram = prefs.getBoolean("pro_hist", false);
        zones = prefs.getBoolean("pro_zones", false);
        btnPeak.setOnClickListener(new Toggle(this, 0));
        btnHist.setOnClickListener(new Toggle(this, 1));
        btnZones.setOnClickListener(new Toggle(this, 2));
        a.findViewById(R.id.btn_pro_roll).setOnClickListener(new RollClick(host));
        refreshAids();
    }

    void onPause() { overlay.pause(); }
    void onResume() { overlay.resume(); }

    /** A lens change resets focus to AF and the ISO range to the new lens. */
    void onLensChanged() {
        sbFocus.setProgress(0);
        engine.setManualFocus(-1f);
        tvFocus.setText("FOCUS  AUTO");
        int iso = isoFor(sbIso.getProgress());
        engine.setManualIso(iso);
        tvIso.setText(iso > 0 ? "ISO  " + iso : "ISO  AUTO");
    }

    private void setAperture(int idx) {
        engine.setVirtualAperture(FilmUi.APERTURE_F[idx]);
        if (filmUi != null) filmUi.setApertureIndex(idx);
        for (int i = 0; i < apBtns.length; i++) apBtns[i].setSelected(i == idx);
    }

    private int isoFor(int progress) {
        if (progress <= 0) return 0;
        int iso = ISO_STEPS[Math.min(ISO_STEPS.length - 1, progress - 1)];
        return Math.max(engine.getDeviceMinIso(), Math.min(engine.getDeviceMaxIso(), iso));
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

    static final class Seek implements SeekBar.OnSeekBarChangeListener {
        private final ProPanel p;
        private final int which;
        Seek(ProPanel p, int which) { this.p = p; this.which = which; }
        @Override public void onProgressChanged(SeekBar s, int pr, boolean user) {
            if (which == 0) {
                int iso = p.isoFor(pr);
                p.engine.setManualIso(iso);
                p.tvIso.setText(iso > 0 ? "ISO  " + iso : "ISO  AUTO");
            } else if (which == 1) {
                int k = (pr == 0) ? 0 : 2500 + (pr - 1) * 100;
                p.engine.setUserWbKelvin(k);
                p.tvWb.setText(k > 0 ? "WHITE BALANCE  " + k + " K" : "WHITE BALANCE  AUTO");
            } else if (which == 2) {
                float t = (pr - 10) / 10f;
                p.engine.setUserWbTintStops(t);
                p.tvTint.setText(String.format(Locale.US, "TINT  %s", pr == 10 ? "0" : String.format(Locale.US, "%+.1f %s", t, t > 0 ? "magenta" : "green")));
            } else {
                if (pr == 0) {
                    p.engine.setManualFocus(-1f);
                    p.tvFocus.setText("FOCUS  AUTO");
                } else {
                    float d = p.engine.getMinFocusDiopters() * (pr - 1) / 19f;
                    p.engine.setManualFocus(d);
                    p.tvFocus.setText(d < 0.05f ? "FOCUS  ∞" : String.format(Locale.US, "FOCUS  %.2f m", 1f / d));
                }
            }
        }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
    static class ApClick implements View.OnClickListener {
        private final ProPanel p; private final int idx;
        ApClick(ProPanel p, int idx) { this.p = p; this.idx = idx; }
        @Override public void onClick(View v) { p.setAperture(idx); }
    }

    static class RollClick implements View.OnClickListener {
        private final Host h;
        RollClick(Host h) { this.h = h; }
        @Override public void onClick(View v) { h.onNewRoll(); }
    }
}
