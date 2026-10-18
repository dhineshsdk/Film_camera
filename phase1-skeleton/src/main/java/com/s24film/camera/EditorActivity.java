package com.s24film.camera;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.ExifInterface;
import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Darkroom: non-destructive-looking edit of a saved JPEG; Save writes a new "_ed" file next to it. */
public class EditorActivity extends Activity implements View.OnClickListener,
        SeekBar.OnSeekBarChangeListener, View.OnTouchListener {
    static final String EXTRA_PATH = "path";
    private static final int PREVIEW_MAX = 1400;
    private static final String[] PRESET_NAMES = {"Original", "Warm", "Cool", "Faded", "Punchy", "Noir", "Cine"};
    // ev, contrast, sat, warmth, tint, fade, vignette, grain, mono
    private static final float[][] PRESETS = {
        {0, 0, 0, 0, 0, 0, 0, 0, 0},
        {0.05f, 0.05f, 0.05f, 0.45f, 0.05f, 0.05f, 0.15f, 0.15f, 0},
        {0, 0.05f, -0.05f, -0.45f, -0.05f, 0.05f, 0.15f, 0.15f, 0},
        {0.05f, -0.15f, -0.2f, 0.15f, 0, 0.55f, 0.2f, 0.25f, 0},
        {0, 0.35f, 0.4f, 0.1f, 0, 0, 0.25f, 0.1f, 0},
        {0, 0.3f, 0, 0, 0, 0.1f, 0.35f, 0.5f, 1},
        {-0.05f, 0.2f, -0.1f, 0.2f, 0.15f, 0.25f, 0.3f, 0.3f, 0},
    };
    private static final String[] SLIDERS = {"Exposure", "Contrast", "Saturation", "Warmth", "Tint", "Fade", "Vignette", "Grain"};
    private static final boolean[] SIGNED = {true, true, true, true, true, false, false, false};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final DarkroomProcessor.Params params = new DarkroomProcessor.Params();
    private final List<SeekBar> bars = new ArrayList<>();
    private File file;
    private Bitmap preview;          // downsampled, orientation-corrected source
    private ImageView image;
    private Button btnMono, btnCrop;
    private volatile int generation;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) { finish(); return; }
        file = new File(path);
        preview = decode(path, PREVIEW_MAX);
        if (preview == null) { Toast.makeText(this, "Cannot open photo", Toast.LENGTH_SHORT).show(); finish(); return; }
        buildUi();
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ---- UI ------------------------------------------------------------------------------------
    private int dp(int v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics())); }

    private Button chip(String text, int tag) {
        Button t = new Button(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setAllCaps(false);
        t.setBackgroundColor(0xFF2A2A2A);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setMinWidth(0); t.setMinimumWidth(0); t.setMinHeight(0); t.setMinimumHeight(0);
        t.setTag(tag);
        t.setOnClickListener(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        lp.setMargins(dp(3), dp(3), dp(3), dp(3));
        t.setLayoutParams(lp);
        return t;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(0, dp(28), 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(chip("Back", 100));
        TextView title = new TextView(this);
        title.setText("Darkroom");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(title);
        top.addView(chip("Save", 101));
        root.addView(top);

        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setOnTouchListener(this);
        root.addView(image, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        HorizontalScrollView presets = new HorizontalScrollView(this);
        presets.setHorizontalScrollBarEnabled(false);
        LinearLayout pr = new LinearLayout(this);
        for (int i = 0; i < PRESET_NAMES.length; i++) pr.addView(chip(PRESET_NAMES[i], i));
        presets.addView(pr);
        root.addView(presets);

        HorizontalScrollView tools = new HorizontalScrollView(this);
        tools.setHorizontalScrollBarEnabled(false);
        LinearLayout tl = new LinearLayout(this);
        tl.addView(chip("Rotate", 102));
        btnCrop = chip("Crop: Original", 103);
        tl.addView(btnCrop);
        btnMono = chip("B&W: off", 104);
        tl.addView(btnMono);
        tl.addView(chip("Reset", 105));
        tools.addView(tl);
        root.addView(tools);

        ScrollView sv = new ScrollView(this);
        LinearLayout sl = new LinearLayout(this);
        sl.setOrientation(LinearLayout.VERTICAL);
        sl.setPadding(dp(12), 0, dp(12), dp(8));
        for (int i = 0; i < SLIDERS.length; i++) {
            TextView t = new TextView(this);
            t.setText(SLIDERS[i]);
            t.setTextColor(0xFFBBBBBB);
            t.setTextSize(12);
            sl.addView(t);
            SeekBar s = new SeekBar(this);
            s.setMax(200);
            s.setProgress(SIGNED[i] ? 100 : 0);
            s.setTag(i);
            s.setOnSeekBarChangeListener(this);
            bars.add(s);
            sl.addView(s);
        }
        sv.addView(sl);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)));
        setContentView(root);
    }

    // ---- Events --------------------------------------------------------------------------------
    @Override
    public void onClick(View v) {
        int tag = (Integer) v.getTag();
        if (tag < PRESETS.length) {
            applyPreset(PRESETS[tag]);
        } else if (tag == 100) {
            finish();
        } else if (tag == 101) {
            save();
        } else if (tag == 102) {
            params.rot = (params.rot + 1) & 3;
            render();
        } else if (tag == 103) {
            params.crop = (params.crop + 1) % DarkroomProcessor.CROP_NAMES.length;
            btnCrop.setText("Crop: " + DarkroomProcessor.CROP_NAMES[params.crop]);
            render();
        } else if (tag == 104) {
            params.mono = !params.mono;
            btnMono.setText(params.mono ? "B&W: on" : "B&W: off");
            render();
        } else if (tag == 105) {
            applyPreset(PRESETS[0]);
            params.rot = 0; params.crop = 0;
            btnCrop.setText("Crop: Original");
            render();
        }
    }

    private void applyPreset(float[] p) {
        float[] vals = {p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]};
        for (int i = 0; i < bars.size(); i++) {
            bars.get(i).setOnSeekBarChangeListener(null);
            bars.get(i).setProgress(SIGNED[i] ? Math.round(100 + vals[i] * 100) : Math.round(vals[i] * 200));
            bars.get(i).setOnSeekBarChangeListener(this);
        }
        params.mono = p[8] > 0;
        btnMono.setText(params.mono ? "B&W: on" : "B&W: off");
        readSliders();
        render();
    }

    private void readSliders() {
        float[] v = new float[SLIDERS.length];
        for (int i = 0; i < v.length; i++) {
            int pr = bars.get(i).getProgress();
            v[i] = SIGNED[i] ? (pr - 100) / 100f : pr / 200f;
        }
        params.ev = v[0]; params.contrast = v[1]; params.sat = v[2]; params.warmth = v[3];
        params.tint = v[4]; params.fade = v[5]; params.vignette = v[6]; params.grain = v[7];
    }

    @Override public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) { readSliders(); render(); } }
    @Override public void onStartTrackingTouch(SeekBar s) { }
    @Override public void onStopTrackingTouch(SeekBar s) { }

    /** Hold the picture to compare with the unedited frame. */
    @Override
    public boolean onTouch(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            generation++;
            image.setImageBitmap(DarkroomProcessor.geometry(preview, params));
        } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
            render();
        }
        return true;
    }

    // ---- Rendering -----------------------------------------------------------------------------
    private void render() {
        int gen = ++generation;
        worker.execute(new RenderJob(this, params.copy(), gen));
    }

    static class RenderJob implements Runnable {
        private final EditorActivity a; private final DarkroomProcessor.Params p; private final int gen;
        RenderJob(EditorActivity a, DarkroomProcessor.Params p, int gen) { this.a = a; this.p = p; this.gen = gen; }
        @Override public void run() {
            if (gen != a.generation) return;
            Bitmap out = DarkroomProcessor.process(a.preview, p);
            a.ui.post(new Show(a, out, gen));
        }
    }

    static class Show implements Runnable {
        private final EditorActivity a; private final Bitmap bmp; private final int gen;
        Show(EditorActivity a, Bitmap bmp, int gen) { this.a = a; this.bmp = bmp; this.gen = gen; }
        @Override public void run() { if (gen == a.generation) a.image.setImageBitmap(bmp); }
    }

    // ---- Save ----------------------------------------------------------------------------------
    private void save() {
        Toast.makeText(this, "Saving...", Toast.LENGTH_SHORT).show();
        worker.execute(new SaveJob(this, params.copy()));
    }

    static class SaveJob implements Runnable {
        private final EditorActivity a; private final DarkroomProcessor.Params p;
        SaveJob(EditorActivity a, DarkroomProcessor.Params p) { this.a = a; this.p = p; }
        @Override public void run() {
            String msg;
            try {
                Bitmap full = decode(a.file.getAbsolutePath(), 4096);
                Bitmap out = DarkroomProcessor.process(full, p);
                String name = a.file.getName();
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                if (base.endsWith("_ed")) base = base.substring(0, base.length() - 3);
                File dest = new File(a.file.getParentFile(), base + "_ed.jpg");
                int n = 2;
                while (dest.exists()) dest = new File(a.file.getParentFile(), base + "_ed" + (n++) + ".jpg");
                FileOutputStream fos = new FileOutputStream(dest);
                out.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
                MediaScannerConnection.scanFile(a, new String[]{dest.getAbsolutePath()}, null, null);
                msg = "Saved " + dest.getName();
            } catch (Throwable t) {
                msg = "Save failed: " + t.getMessage();
            }
            a.ui.post(new Toaster(a, msg));
        }
    }

    static class Toaster implements Runnable {
        private final EditorActivity a; private final String m;
        Toaster(EditorActivity a, String m) { this.a = a; this.m = m; }
        @Override public void run() { Toast.makeText(a, m, Toast.LENGTH_LONG).show(); }
    }

    /** Decode with EXIF orientation applied, longest side at most maxSide. */
    static Bitmap decode(String path, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap b = BitmapFactory.decodeFile(path, o);
            if (b == null) return null;
            int deg = 0;
            int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) deg = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) deg = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) deg = 270;
            if (deg != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(deg);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }
}
