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

        sbIso.setOnSeekBarChangeListener(new Seek(0));
        sbWb.setOnSeekBarChangeListener(new Seek(1));
        sbTint.setOnSeekBarChangeListener(new Seek(2));
        sbFocus.setOnSeekBarChangeListener(new Seek(3));

        LinearLayout row = a.findViewById(R.id.row_pro_aperture);
        for (int i = 0; i < apBtns.length; i++) {
            Button b = (Button) a.getLayoutInflater().inflate(R.layout.chip_button, row, false);
            b.setText("f/" + FilmUi.APERTURE_LABELS[i]);
            final int idx = i;
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { setAperture(idx); }
            });
            row.addView(b);
            apBtns[i] = b;
        }
        apBtns[0].setSelected(true);   // f/1.8, the engine's default

        peaking = prefs.getBoolean("pro_peaking", false);
        histogram = prefs.getBoolean("pro_hist", false);
        zones = prefs.getBoolean("pro_zones", false);
        btnPeak.setOnClickListener(new Toggle(0));
        btnHist.setOnClickListener(new Toggle(1));
        btnZones.setOnClickListener(new Toggle(2));
        a.findViewById(R.id.btn_pro_roll).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.onNewRoll(); }
        });
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

    private final class Toggle implements View.OnClickListener {
        private final int which;
        Toggle(int which) { this.which = which; }
        @Override public void onClick(View v) {
            if (which == 0) peaking = !peaking; else if (which == 1) histogram = !histogram; else zones = !zones;
            prefs.edit().putBoolean("pro_peaking", peaking).putBoolean("pro_hist", histogram)
                .putBoolean("pro_zones", zones).apply();
            refreshAids();
        }
    }

    private final class Seek implements SeekBar.OnSeekBarChangeListener {
        private final int which;
        Seek(int which) { this.which = which; }
        @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
            if (which == 0) {
                int iso = isoFor(p);
                engine.setManualIso(iso);
                tvIso.setText(iso > 0 ? "ISO  " + iso : "ISO  AUTO");
            } else if (which == 1) {
                int k = (p == 0) ? 0 : 2500 + (p - 1) * 100;
                engine.setUserWbKelvin(k);
                tvWb.setText(k > 0 ? "WHITE BALANCE  " + k + " K" : "WHITE BALANCE  AUTO");
            } else if (which == 2) {
                float t = (p - 10) / 10f;
                engine.setUserWbTintStops(t);
                tvTint.setText(String.format(Locale.US, "TINT  %s", p == 10 ? "0" : String.format(Locale.US, "%+.1f %s", t, t > 0 ? "magenta" : "green")));
            } else {
                if (p == 0) {
                    engine.setManualFocus(-1f);
                    tvFocus.setText("FOCUS  AUTO");
                } else {
                    float d = engine.getMinFocusDiopters() * (p - 1) / 19f;
                    engine.setManualFocus(d);
                    tvFocus.setText(d < 0.05f ? "FOCUS  ∞" : String.format(Locale.US, "FOCUS  %.2f m", 1f / d));
                }
            }
        }
        @Override public void onStartTrackingTouch(SeekBar s) {}
        @Override public void onStopTrackingTouch(SeekBar s) {}
    }
}
