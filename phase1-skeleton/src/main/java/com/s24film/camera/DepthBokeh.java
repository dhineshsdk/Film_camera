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
 * Depth-based bokeh for master captures. Monocular depth (Depth Anything V2 Small, int8 ONNX, CPU)
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
    static final float MAX_F_NUMBER = 5.6f;   // f/5.6 and slower: no synthetic depth of field

    /** adb: --es cmd depth --ez on false turns the effect off. */
    static volatile boolean enabled = true;

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new WorkerFactory());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean unavailable = false;
    private OrtEnvironment env;
    private OrtSession session;

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
        worker.execute(new WarmUpTask(this));
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
        if (!enabled || unavailable || !(fNumber < MAX_F_NUMBER)) return false;
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

    private void runJob(JobTask j) {
        float[] disp = null;
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
                    focusDisp = focusDisparity(disp, 0.5f, 0.5f);
                    Log.i(TAG, "Depth inference+load " + ms + " ms, raw range " + stats[0] + ".." + stats[1]
                        + " (p1 " + stats[2] + "), focus disparity " + focusDisp);
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
            master = VulkanRenderer.nativeMasterFinish(disp, NET_W, NET_H, focusDisp, masterDims);
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
        int rx = Math.max(2, (int) (0.03f * NET_W)), ry = Math.max(2, (int) (0.03f * NET_H));
        int x0 = Math.max(0, cx - rx), x1 = Math.min(NET_W - 1, cx + rx);
        int y0 = Math.max(0, cy - ry), y1 = Math.min(NET_H - 1, cy + ry);
        float[] w = new float[(x1 - x0 + 1) * (y1 - y0 + 1)];
        int k = 0;
        for (int y = y0; y <= y1; ++y) for (int x = x0; x <= x1; ++x) w[k++] = d[y * NET_W + x];
        Arrays.sort(w);
        return w[w.length / 2];
    }
}
