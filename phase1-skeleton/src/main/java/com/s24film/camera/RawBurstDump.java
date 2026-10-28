package com.s24film.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Stage A0 debug tool: saves the next N consecutive RAW preview frames as numbered DNGs
 * (DCIM/S24Film/a0/<tag>/0001.dng ...) plus burst.json, so two builds can be rendered on
 * the same frames on a PC (tools/ab_compare.py).
 *
 * The frame is copied on the camera thread and the Image is left to the caller to close as
 * usual, so the RAW stream is drained exactly as in normal preview. The DNG write happens on
 * a worker; at most SLOTS copies are in flight (about 25 MB each), extra frames are skipped.
 */
final class RawBurstDump {
    private static final String TAG = "S24FilmDebug";
    private static final File ROOT = new File("/sdcard/DCIM/S24Film/a0");
    private static final int SLOTS = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Semaphore slots = new Semaphore(SLOTS);

    private volatile int remaining = 0;
    private File dir;
    private int accepted;
    private int skipped;
    private StringBuilder json;
    private String camId;

    /** Arms the dump: the next n RAW frames are saved under a/<tag>. */
    synchronized void start(int n, String tag) {
        String safe = (tag == null || tag.isEmpty()) ? "burst" : tag.replaceAll("[^A-Za-z0-9_.-]", "_");
        dir = new File(ROOT, safe);
        dir.mkdirs();
        accepted = 0;
        skipped = 0;
        json = new StringBuilder("[");
        remaining = Math.max(1, Math.min(n, 32));
        Log.i(TAG, "rawburst armed: " + remaining + " frames -> " + dir);
    }

    boolean isArmed() { return remaining > 0; }

    /** Camera thread, once per RAW frame. Never closes the Image. */
    void offer(Image image, CameraCharacteristics ch, CaptureResult result, String cameraId) {
        if (remaining <= 0 || image == null || ch == null || result == null) return;
        if (!slots.tryAcquire()) {
            synchronized (this) { skipped++; }
            return;
        }
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane p = image.getPlanes()[0];
            ByteBuffer src = p.getBuffer();
            int rowStride = p.getRowStride();
            byte[] px = new byte[w * 2 * h];
            for (int y = 0; y < h; y++) {
                src.position(y * rowStride);
                src.get(px, y * w * 2, w * 2);
            }
            int idx;
            boolean last;
            synchronized (this) {
                idx = ++accepted;
                camId = cameraId;
                remaining--;
                last = remaining == 0;
            }
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            io.execute(new WriteTask(this, dir, idx, last, ch, result, w, h, px,
                iso == null ? 0 : iso, exp == null ? 0L : exp, ts == null ? 0L : ts,
                image.getTimestamp()));
        } catch (Throwable t) {
            slots.release();
            Log.e(TAG, "rawburst copy failed: " + t.getMessage(), t);
        }
    }

    private synchronized void record(int idx, int w, int h, int iso, long exp, long ts, long imgTs) {
        if (idx > 1) json.append(",");
        json.append(String.format(Locale.US,
            "\n {\"file\":\"%04d.dng\",\"width\":%d,\"height\":%d,\"iso\":%d,\"exposure_ns\":%d,"
                + "\"result_ts\":%d,\"image_ts\":%d}", idx, w, h, iso, exp, ts, imgTs));
    }

    private synchronized void finish(File d) {
        String s = "{\"camera\":\"" + camId + "\",\"skipped\":" + skipped + ",\"frames\":" + json + "\n]}\n";
        try (FileOutputStream f = new FileOutputStream(new File(d, "burst.json"))) {
            f.write(s.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.e(TAG, "burst.json failed: " + t.getMessage());
        }
        Log.i(TAG, "rawburst done: " + accepted + " frames, " + skipped + " skipped -> " + d);
    }

    // Named class: the build's d8 step cannot take anonymous "$1" class files.
    private static final class WriteTask implements Runnable {
        private final RawBurstDump o; private final File dir; private final int idx; private final boolean last;
        private final CameraCharacteristics ch; private final CaptureResult r;
        private final int w, h, iso; private final long exp, ts, imgTs; private final byte[] px;

        WriteTask(RawBurstDump o, File dir, int idx, boolean last, CameraCharacteristics ch, CaptureResult r,
                  int w, int h, byte[] px, int iso, long exp, long ts, long imgTs) {
            this.o = o; this.dir = dir; this.idx = idx; this.last = last; this.ch = ch; this.r = r;
            this.w = w; this.h = h; this.px = px; this.iso = iso; this.exp = exp; this.ts = ts; this.imgTs = imgTs;
        }

        @Override public void run() {
            try {
                File out = new File(dir, String.format(Locale.US, "%04d.dng", idx));
                try (DngCreator c = new DngCreator(ch, r); FileOutputStream f = new FileOutputStream(out)) {
                    c.writeByteBuffer(f, new Size(w, h), ByteBuffer.wrap(px), 0);
                }
                o.record(idx, w, h, iso, exp, ts, imgTs);
            } catch (Throwable t) {
                Log.e(TAG, "rawburst write " + idx + " failed: " + t.getMessage(), t);
            } finally {
                o.slots.release();
                if (last) o.finish(dir);
            }
        }
    }
}
