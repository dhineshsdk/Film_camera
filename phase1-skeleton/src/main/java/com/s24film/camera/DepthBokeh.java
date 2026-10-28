package com.s24film.camera;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.media.Image;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Depth-based bokeh for master captures. Monocular depth (Depth Anything V2 Small, int8 ONNX, CPU) plus a MODNet subject matte
 * runs on its own thread so the camera callback only pays for the first half of the master render
 * (PREP + ISP + PYRAMID); the second half (depth bokeh + film + readback) and the save follow from
 * the worker. The RAW Image is owned here from tryCapture until it is handed to the DNG saver.
 * Anything that goes wrong after the first half still finishes the render, just without depth.
 */
final class DepthBokeh {
    private static final String TAG = "S24FilmDepth";
    private static final String MODEL_ASSET = "depth_anything_v2_small_int8.onnx";
    static final int NET_W = 392;      // multiples of 14, 3:4 portrait
    static final int NET_H = 518;
    private static final String MATTE_ASSET = "modnet_int8.onnx";
    private static final int MATTE_W = 384;   // MODNet: multiples of 32, same 3:4 portrait as the depth grid
    private static final int MATTE_H = 512;
    private static final float PERSON_MIN_MEAN = 0.02f;   // mean matte above this: a person is in the frame

    /** adb: --es cmd depth --ez on false is a kill switch for the effect. */
    static volatile boolean enabled = true;
    /** Portrait mode (UI chip, adb --es cmd portrait --ez on true): depth blur is opt-in; the aperture dial sets its strength. */
    static volatile boolean portrait = false;

    /** Portrait off means no out-of-focus blur anywhere: depth bokeh, Super 70mm bokeh and lens swirl. */
    static void setPortrait(boolean on) {
        portrait = on;
        try { VulkanRenderer.nativeSetPortraitMode(on); } catch (Throwable ignored) { }
        DepthBokeh d = instance;
        if (on && d != null) d.warmUp();
    }

    // The depth and matting models (tens of MB once loaded) load only when portrait mode is on, so a
    // normal start does not spend CPU and memory on them while the camera and Vulkan come up.
    private static volatile DepthBokeh instance;
    private final AtomicBoolean warmQueued = new AtomicBoolean(false);

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new WorkerFactory());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean unavailable = false;
    private OrtEnvironment env;
    private OrtSession session;
    private OrtSession matteSession;   // null when the matting model is missing: depth only

    static class WorkerFactory implements java.util.concurrent.ThreadFactory {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "DepthBokeh");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    }

    static class RejectTask implements Runnable {
        private final DngSaver.Callback cb;
        RejectTask(DngSaver.Callback cb) { this.cb = cb; }
        @Override public void run() { if (cb != null) cb.onDngError("previous photo is still developing"); }
    }

    static class JobTask implements Runnable {
        private final DepthBokeh owner;
        private final Image image;
        private final CameraCharacteristics characteristics;
        private final CaptureResult result;
        private final String tag;
        private final int orient;
        private final byte[] rgb;
        private final DngSaver saver;
        private final DngSaver.Callback callback;

        JobTask(DepthBokeh owner, Image image, CameraCharacteristics characteristics, CaptureResult result,
                String tag, int orient, byte[] rgb, DngSaver saver, DngSaver.Callback callback) {
            this.owner = owner;
            this.image = image;
            this.characteristics = characteristics;
            this.result = result;
            this.tag = tag;
            this.orient = orient;
            this.rgb = rgb;
            this.saver = saver;
            this.callback = callback;
        }

        @Override public void run() { owner.runJob(this); }
    }

    DepthBokeh(Context context) {
        this.context = context.getApplicationContext();
        instance = this;
        if (portrait) warmUp();
    }

    private void warmUp() {
        if (!warmQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(new WarmUpTask(this));
        } catch (Throwable t) {
            warmQueued.set(false);
        }
    }

    static class WarmUpTask implements Runnable {
        private final DepthBokeh owner;
        WarmUpTask(DepthBokeh owner) { this.owner = owner; }
        @Override public void run() {
            try {
                owner.ensureSession();
            } catch (Throwable t) {
                Log.w(TAG, "Depth model unavailable, depth bokeh off: " + t);
                owner.unavailable = true;
            }
        }
    }

    private void ensureSession() throws Exception {
        if (session != null) return;
        long t0 = System.nanoTime();
        byte[] model;
        try (InputStream in = context.getAssets().open(MODEL_ASSET)) {
            model = in.readAllBytes();
        }
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setIntraOpNumThreads(4);
        opts.addConfigEntry("session.intra_op.allow_spinning", "0");
        session = env.createSession(model, opts);
        Log.i(TAG, "Depth model loaded in " + ((System.nanoTime() - t0) / 1_000_000) + " ms");
        try (InputStream in = context.getAssets().open(MATTE_ASSET)) {
            matteSession = env.createSession(in.readAllBytes(), opts);
            Log.i(TAG, "Matting model loaded, total " + ((System.nanoTime() - t0) / 1_000_000) + " ms");
        } catch (Throwable t) {
            Log.w(TAG, "Matting model unavailable, portrait uses depth only: " + t);
        }
    }

    /**
     * Takes over a capture when depth bokeh applies (or when a previous one is still in flight).
     * Returns true when the Image has been handed off or closed here; false leaves the normal
     * synchronous master path to the caller.
     */
    boolean tryCapture(Image image, CameraCharacteristics characteristics, CaptureResult result, String tag,
                       int orient, boolean stacked, float fNumber, DngSaver saver, DngSaver.Callback callback) {
        if (busy.get()) {
            Log.w(TAG, "Capture while the previous depth render is pending: rejected");
            image.close();
            if (stacked) VulkanRenderer.nativeStackEnd();
            new Handler(Looper.getMainLooper()).post(new RejectTask(callback));
            return true;
        }
        if (!enabled || !portrait || unavailable) return false;
        busy.set(true);
        byte[] rgb = null;
        try {
            rgb = VulkanRenderer.nativeMasterBegin(NET_W, NET_H);
        } catch (Throwable t) {
            Log.e(TAG, "Master begin failed: " + t.getMessage(), t);
        }
        if (rgb == null) {
            busy.set(false);
            return false;
        }
        if (stacked) VulkanRenderer.nativeStackEnd();   // the ring was consumed by Begin
        try {
            worker.execute(new JobTask(this, image, characteristics, result, tag, orient, rgb, saver, callback));
        } catch (Throwable t) {
            // Executor gone: finish synchronously without depth so the render and the Image are not leaked.
            runJob(new JobTask(this, image, characteristics, result, tag, orient, null, saver, callback));
        }
        return true;
    }

    // Last tap on the viewfinder (0..1 of the frame); the frame centre until the first tap.
    private static volatile float focusU = 0.5f, focusV = 0.5f;
    static void setFocusPoint(float u, float v) {
        focusU = Math.max(0f, Math.min(1f, u));
        focusV = Math.max(0f, Math.min(1f, v));
    }

    private void runJob(JobTask j) {
        float[] disp = null;
        float[] matte = null;
        float focusDisp = 0.5f;
        try {
            if (j.rgb != null) {
                long t0 = System.nanoTime();
                ensureSession();
                float[] raw = infer(j.rgb);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                float[] stats = new float[3];
                disp = normalize(raw, stats);
                if (disp != null) {
                    focusDisp = focusDisparity(disp, focusU, focusV);
                    String who = "tap/centre";
                    try {
                        long t1 = System.nanoTime();
                        matte = inferMatte(j.rgb);
                        float mean = 0f;
                        if (matte != null) {
                            for (float m : matte) mean += m;
                            mean /= matte.length;
                            if (mean > PERSON_MIN_MEAN) {
                                focusDisp = subjectDisparity(disp, matte, focusDisp);
                                who = "subject";
                            }
                        }
                        Log.i(TAG, "Matting " + ((System.nanoTime() - t1) / 1_000_000) + " ms, mean matte " + mean);
                    } catch (Throwable t) {
                        Log.w(TAG, "Matting failed, depth only: " + t);
                        matte = null;
                    }
                    Log.i(TAG, "Depth inference+load " + ms + " ms, raw range " + stats[0] + ".." + stats[1]
                        + " (p1 " + stats[2] + "), focus disparity " + focusDisp + " (" + who + ")");
                } else {
                    Log.w(TAG, "Depth map degenerate (flat), rendering without depth");
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Depth inference failed, rendering without depth: " + t, t);
            unavailable = true;
            disp = null;
        }
        byte[] master = null;
        byte[] gain = null;
        int[] masterDims = new int[2];
        int[] gainDims = new int[2];
        try {
            master = VulkanRenderer.nativeMasterFinish(disp, matte, NET_W, NET_H, focusDisp, masterDims);
            if (master != null) gain = VulkanRenderer.nativeGetMasterGainmap(gainDims);
        } catch (Throwable t) {
            Log.e(TAG, "Master finish failed: " + t.getMessage(), t);
            master = null;
            gain = null;
        }
        try {
            j.saver.setJpegRotation(j.orient);
            j.saver.saveDngAndMasterAsync(j.image, j.characteristics, j.result, j.tag,
                master, masterDims[0], masterDims[1], gain, gainDims[0], gainDims[1], j.callback);
        } catch (Throwable t) {
            Log.e(TAG, "Hand-off to the saver failed: " + t.getMessage(), t);
            j.image.close();
        } finally {
            busy.set(false);
        }
    }

    private float[] infer(byte[] rgb) throws Exception {
        final int n = NET_W * NET_H;
        float[] chw = new float[3 * n];
        final float[] mean = {0.485f, 0.456f, 0.406f};
        final float[] std = {0.229f, 0.224f, 0.225f};
        for (int i = 0; i < n; ++i) {
            for (int c = 0; c < 3; ++c) {
                chw[c * n + i] = ((rgb[i * 3 + c] & 0xFF) / 255.0f - mean[c]) / std[c];
            }
        }
        String inName = session.getInputNames().iterator().next();
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, NET_H, NET_W});
             OrtSession.Result out = session.run(Collections.singletonMap(inName, in))) {
            FloatBuffer fb = ((OnnxTensor) out.get(0)).getFloatBuffer();
            if (fb.remaining() < n) throw new IllegalStateException("depth output too small: " + fb.remaining());
            float[] d = new float[n];
            fb.get(d, 0, n);
            return d;
        }
    }

    /** MODNet alpha matte 0 (background) .. 1 (subject) on the depth grid, lightly smoothed; null if unavailable. */
    private float[] inferMatte(byte[] rgb) throws Exception {
        if (matteSession == null) return null;
        final int n = MATTE_W * MATTE_H;
        float[] chw = new float[3 * n];
        for (int y = 0; y < MATTE_H; ++y) {
            float sy = Math.min((y + 0.5f) * NET_H / MATTE_H - 0.5f, NET_H - 1f);
            sy = Math.max(sy, 0f);
            int y0 = (int) sy, y1 = Math.min(y0 + 1, NET_H - 1);
            float fy = sy - y0;
            for (int x = 0; x < MATTE_W; ++x) {
                float sx = Math.max(Math.min((x + 0.5f) * NET_W / MATTE_W - 0.5f, NET_W - 1f), 0f);
                int x0 = (int) sx, x1 = Math.min(x0 + 1, NET_W - 1);
                float fx = sx - x0;
                for (int c = 0; c < 3; ++c) {
                    float a = (rgb[(y0 * NET_W + x0) * 3 + c] & 0xFF) * (1f - fx) + (rgb[(y0 * NET_W + x1) * 3 + c] & 0xFF) * fx;
                    float b = (rgb[(y1 * NET_W + x0) * 3 + c] & 0xFF) * (1f - fx) + (rgb[(y1 * NET_W + x1) * 3 + c] & 0xFF) * fx;
                    chw[c * n + y * MATTE_W + x] = ((a * (1f - fy) + b * fy) / 255f - 0.5f) / 0.5f;
                }
            }
        }
        float[] m = new float[n];
        String inName = matteSession.getInputNames().iterator().next();
        try (OnnxTensor in = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, MATTE_H, MATTE_W});
             OrtSession.Result out = matteSession.run(Collections.singletonMap(inName, in))) {
            FloatBuffer fb = ((OnnxTensor) out.get(0)).getFloatBuffer();
            if (fb.remaining() < n) throw new IllegalStateException("matte output too small: " + fb.remaining());
            fb.get(m, 0, n);
        }
        float[] r = new float[NET_W * NET_H];
        for (int y = 0; y < NET_H; ++y) {
            float sy = Math.max(Math.min((y + 0.5f) * MATTE_H / NET_H - 0.5f, MATTE_H - 1f), 0f);
            int y0 = (int) sy, y1 = Math.min(y0 + 1, MATTE_H - 1);
            float fy = sy - y0;
            for (int x = 0; x < NET_W; ++x) {
                float sx = Math.max(Math.min((x + 0.5f) * MATTE_W / NET_W - 0.5f, MATTE_W - 1f), 0f);
                int x0 = (int) sx, x1 = Math.min(x0 + 1, MATTE_W - 1);
                float fx = sx - x0;
                float a = m[y0 * MATTE_W + x0] * (1f - fx) + m[y0 * MATTE_W + x1] * fx;
                float b = m[y1 * MATTE_W + x0] * (1f - fx) + m[y1 * MATTE_W + x1] * fx;
                float v = a * (1f - fy) + b * fy;
                r[y * NET_W + x] = v < 0f ? 0f : (v > 1f ? 1f : v);
            }
        }
        // Two 3x3 box passes: feather the edge so hair blends instead of cutting out.
        float[] t = new float[r.length];
        for (int pass = 0; pass < 2; ++pass) {
            for (int y = 0; y < NET_H; ++y) {
                for (int x = 0; x < NET_W; ++x) {
                    float sum = 0f;
                    for (int dy = -1; dy <= 1; ++dy) {
                        int yy = Math.max(0, Math.min(NET_H - 1, y + dy));
                        for (int dx = -1; dx <= 1; ++dx) sum += r[yy * NET_W + Math.max(0, Math.min(NET_W - 1, x + dx))];
                    }
                    t[y * NET_W + x] = sum * (1f / 9f);
                }
            }
            float[] sw = r; r = t; t = sw;
        }
        return r;
    }

    /** Matte-weighted median disparity of the subject; fallback when the matte carries no weight. */
    static float subjectDisparity(float[] d, float[] m, float fallback) {
        final int bins = 256;
        float[] h = new float[bins];
        float total = 0f;
        for (int i = 0; i < d.length; ++i) {
            float w = m[i] > 0.5f ? m[i] : 0f;
            h[Math.min(bins - 1, (int) (d[i] * bins))] += w;
            total += w;
        }
        if (!(total > 0f)) return fallback;
        float acc = 0f;
        for (int b = 0; b < bins; ++b) {
            acc += h[b];
            if (acc >= 0.5f * total) return (b + 0.5f) / bins;
        }
        return fallback;
    }

    /** 1st / 99th percentile stretch to 0 (far) .. 1 (near); null when the map is flat. */
    static float[] normalize(float[] raw, float[] stats) {
        float[] s = raw.clone();
        Arrays.sort(s);
        float lo = s[(int) (s.length * 0.01f)];
        float hi = s[Math.min(s.length - 1, (int) (s.length * 0.99f))];
        stats[0] = s[0];
        stats[1] = s[s.length - 1];
        stats[2] = lo;
        if (!(hi - lo > 1e-6f)) return null;
        float[] d = new float[raw.length];
        float inv = 1.0f / (hi - lo);
        for (int i = 0; i < d.length; ++i) {
            float v = (raw[i] - lo) * inv;
            d[i] = v < 0f ? 0f : (v > 1f ? 1f : v);
        }
        return d;
    }

    /** Median disparity in a small window around the focus point (u, v in 0..1). */
    static float focusDisparity(float[] d, float u, float v) {
        int cx = (int) (u * NET_W), cy = (int) (v * NET_H);
        int rx = Math.max(2, (int) (0.05f * NET_W)), ry = Math.max(2, (int) (0.05f * NET_H));
        int x0 = Math.max(0, cx - rx), x1 = Math.min(NET_W - 1, cx + rx);
        int y0 = Math.max(0, cy - ry), y1 = Math.min(NET_H - 1, cy + ry);
        float[] w = new float[(x1 - x0 + 1) * (y1 - y0 + 1)];
        int k = 0;
        for (int y = y0; y <= y1; ++y) for (int x = x0; x <= x1; ++x) w[k++] = d[y * NET_W + x];
        Arrays.sort(w);
        return w[w.length / 2];
    }
}
