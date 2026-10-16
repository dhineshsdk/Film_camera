package com.s24film.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DngSaver {
    private static final String TAG = "S24FilmDNG";
    private static final File STORAGE_DIR = new File("/sdcard/DCIM/S24Film");
    // Must match kHdrSdrRatio in vulkan_raw_pipeline.cpp: gain byte = log2(g) / log2(3).
    private static final float HDR_SDR_RATIO = 3.0f;

    public interface Callback {
        void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes);
        void onDngError(String message);
    }

    // Keep the RAW DNG next to the developed JPEG. Off by default (about 20-25 MB per frame).
    private static volatile boolean saveDng = false;

    public static void setSaveDng(boolean enabled) {
        saveDng = enabled;
    }

    public static boolean isSaveDng() {
        return saveDng;
    }

    private final Context context;
    private final ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public DngSaver(Context context) {
        this.context = (context != null) ? context.getApplicationContext() : null;
        if (!STORAGE_DIR.exists()) {
            boolean created = STORAGE_DIR.mkdirs();
            Log.i(TAG, "Created DCIM/S24Film directory: " + created);
        }
    }

    public void saveDngAndMasterAsync(Image rawImage, CameraCharacteristics characteristics,
                                      CaptureResult captureResult, String stockShortName,
                                      Callback callback) {
        diskExecutor.execute(new SaveTask(context, rawImage, characteristics, captureResult, stockShortName,
            null, 0, 0, null, 0, 0, callback, mainHandler, 0));
    }

    // masterRgba / gain come from VulkanRenderer.nativeRenderMasterStill / nativeGetMasterGainmap,
    // rendered on the camera thread before the RAW Image is handed over. A null masterRgba
    // falls back to the live viewfinder readback.
    public void saveDngAndMasterAsync(Image rawImage, CameraCharacteristics characteristics,
                                      CaptureResult captureResult, String lookTag,
                                      byte[] masterRgba, int masterW, int masterH,
                                      byte[] gain, int gainW, int gainH,
                                      Callback callback) {
        diskExecutor.execute(new SaveTask(context, rawImage, characteristics, captureResult, lookTag,
            masterRgba, masterW, masterH, gain, gainW, gainH, callback, mainHandler, jpegRotationDeg));
    }

    // Clockwise degrees (0/90/270) applied to the saved JPEG so landscape grips come out upright.
    private volatile int jpegRotationDeg = 0;

    public void setJpegRotation(int deg) {
        jpegRotationDeg = ((deg % 360) + 360) % 360;
    }

    public void shutdown() {
        diskExecutor.shutdown();
    }

    static class NotifySuccessTask implements Runnable {
        private final Callback callback;
        private final String dngPath;
        private final String jpgPath;
        private final long dngBytes;
        private final long jpgBytes;

        NotifySuccessTask(Callback callback, String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
            this.callback = callback;
            this.dngPath = dngPath;
            this.jpgPath = jpgPath;
            this.dngBytes = dngBytes;
            this.jpgBytes = jpgBytes;
        }

        @Override
        public void run() {
            if (callback != null) {
                callback.onCaptureSaved(dngPath, jpgPath, dngBytes, jpgBytes);
            }
        }
    }

    static class NotifyErrorTask implements Runnable {
        private final Callback callback;
        private final String message;

        NotifyErrorTask(Callback callback, String message) {
            this.callback = callback;
            this.message = message;
        }

        @Override
        public void run() {
            if (callback != null) {
                callback.onDngError(message);
            }
        }
    }

    static class ScanListener implements MediaScannerConnection.OnScanCompletedListener {
        @Override
        public void onScanCompleted(String path, Uri uri) {
            Log.i(TAG, "Scanned into Samsung Gallery MediaStore: " + path + " -> " + uri);
        }
    }

    static class SaveTask implements Runnable {
        private final Context context;
        private final Image rawImage;
        private final CameraCharacteristics characteristics;
        private final CaptureResult captureResult;
        private final String stockShortName;
        private byte[] masterRgba;
        private final int masterW;
        private final int masterH;
        private byte[] gain;
        private final int gainW;
        private final int gainH;
        private final Callback callback;
        private final Handler mainHandler;
        private final int rotationDeg;

        SaveTask(Context context, Image rawImage, CameraCharacteristics characteristics,
                 CaptureResult captureResult, String stockShortName,
                 byte[] masterRgba, int masterW, int masterH,
                 byte[] gain, int gainW, int gainH,
                 Callback callback, Handler mainHandler, int rotationDeg) {
            this.rotationDeg = rotationDeg;
            this.context = context;
            this.rawImage = rawImage;
            this.characteristics = characteristics;
            this.captureResult = captureResult;
            this.stockShortName = stockShortName;
            this.masterRgba = masterRgba;
            this.masterW = masterW;
            this.masterH = masterH;
            this.gain = gain;
            this.gainW = gainW;
            this.gainH = gainH;
            this.callback = callback;
            this.mainHandler = mainHandler;
        }

        // Android 14 Ultra HDR: single-channel gainmap, log-encoded between 1x and HDR_SDR_RATIO.
        private void attachGainmap(Bitmap bmp, int rotDeg) {
            if (gain == null || gainW <= 0 || gainH <= 0) return;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.w(TAG, "Ultra HDR gainmap needs Android 14+; saving SDR JPEG only");
                return;
            }
            if (gain.length < gainW * gainH) {
                Log.w(TAG, "Gainmap buffer too small (" + gain.length + " < " + (gainW * gainH) + ")");
                return;
            }
            try {
                Bitmap gainBmp = Bitmap.createBitmap(gainW, gainH, Bitmap.Config.ALPHA_8);
                gainBmp.copyPixelsFromBuffer(ByteBuffer.wrap(gain, 0, gainBmp.getByteCount()));
                if (rotDeg != 0) {
                    Matrix gm0 = new Matrix();
                    gm0.postRotate(rotDeg);
                    gainBmp = Bitmap.createBitmap(gainBmp, 0, 0, gainW, gainH, gm0, true);
                }
                Gainmap gm = new Gainmap(gainBmp);
                gm.setRatioMin(1.0f, 1.0f, 1.0f);
                gm.setRatioMax(HDR_SDR_RATIO, HDR_SDR_RATIO, HDR_SDR_RATIO);
                gm.setGamma(1.0f, 1.0f, 1.0f);
                gm.setEpsilonSdr(0.0f, 0.0f, 0.0f);
                gm.setEpsilonHdr(0.0f, 0.0f, 0.0f);
                gm.setDisplayRatioForFullHdr(HDR_SDR_RATIO);
                gm.setMinDisplayRatioForHdrTransition(1.0f);
                bmp.setGainmap(gm);
                Log.i(TAG, "Attached Ultra HDR gainmap " + gainW + "x" + gainH + " (hdrSdrRatio " + HDR_SDR_RATIO + ")");
            } catch (Throwable t) {
                Log.e(TAG, "Gainmap attach failed: " + t.getMessage(), t);
            }
        }

        @Override
        public void run() {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File outDng = new File(STORAGE_DIR, "IMG_" + timeStamp + ".dng");
            File outJpg = new File(STORAGE_DIR, "IMG_" + timeStamp + "_" + stockShortName + ".jpg");

            long dngBytes = 0;
            long jpgBytes = 0;

            try {
                // 1. Optional RAW DNG (off by default: the developed film JPEG is the output)
                if (saveDng && characteristics != null && captureResult != null) {
                    try {
                        try (DngCreator dngCreator = new DngCreator(characteristics, captureResult);
                             FileOutputStream fos = new FileOutputStream(outDng)) {
                            long start = System.currentTimeMillis();
                            dngCreator.writeImage(fos, rawImage);
                            fos.flush();
                            long elapsed = System.currentTimeMillis() - start;
                            dngBytes = outDng.length();
                            Log.i(TAG, "Saved DNG: " + outDng.getAbsolutePath() + " (" + (dngBytes / (1024 * 1024)) + " MB) in " + elapsed + " ms");
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "DngCreator failed to save DNG: " + t.getMessage(), t);
                        if (outDng.exists()) {
                            outDng.delete();
                        }
                        dngBytes = 0;
                    }
                }

                // 2. Read back the rendered Master Film Frame from Vulkan and save 98% Quality JPEG
                int[] dims = new int[2];
                byte[] rgba = masterRgba;
                boolean isMaster = (rgba != null && masterW > 0 && masterH > 0);
                if (isMaster) {
                    dims[0] = masterW;
                    dims[1] = masterH;
                } else {
                    Log.w(TAG, "No master render; falling back to viewfinder readback");
                    rgba = VulkanRenderer.nativeCaptureLastRgba(dims);
                }
                masterRgba = null;
                if (rgba != null && dims[0] > 0 && dims[1] > 0) {
                    Bitmap bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
                    rgba = null;
                    if (isMaster && rotationDeg != 0) {
                        Matrix m = new Matrix();
                        m.postRotate(rotationDeg);
                        Bitmap rot = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        bmp.recycle();
                        bmp = rot;
                    }
                    if (isMaster) {
                        attachGainmap(bmp, isMaster ? rotationDeg : 0);
                    }
                    gain = null;
                    try (FileOutputStream jfos = new FileOutputStream(outJpg)) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 98, jfos);
                        jfos.flush();
                        jpgBytes = outJpg.length();
                        Log.i(TAG, "Saved Master Film JPEG: " + outJpg.getAbsolutePath() + " (" + (jpgBytes / 1024) + " KB)");
                    } finally {
                        bmp.recycle();
                    }
                }

                // 3. Index immediately into Samsung Gallery / Android MediaStore
                if (context != null) {
                    ArrayList<String> paths = new ArrayList<>();
                    ArrayList<String> mimeTypes = new ArrayList<>();
                    if (outJpg.exists() && outJpg.length() > 0) {
                        paths.add(outJpg.getAbsolutePath());
                        mimeTypes.add("image/jpeg");
                    }
                    if (outDng.exists() && outDng.length() > 0) {
                        paths.add(outDng.getAbsolutePath());
                        mimeTypes.add("image/x-adobe-dng");
                    }
                    if (!paths.isEmpty()) {
                        MediaScannerConnection.scanFile(
                            context,
                            paths.toArray(new String[0]),
                            mimeTypes.toArray(new String[0]),
                            new ScanListener()
                        );
                    }
                }

                mainHandler.post(new NotifySuccessTask(callback,
                    (outDng.exists() ? outDng.getAbsolutePath() : ""),
                    (outJpg.exists() ? outJpg.getAbsolutePath() : ""),
                    dngBytes, jpgBytes));
            } catch (Throwable e) {
                Log.e(TAG, "Failed to write capture: " + e.getMessage(), e);
                mainHandler.post(new NotifyErrorTask(callback, e.getMessage()));
            } finally {
                try {
                    rawImage.close();
                } catch (Throwable ignored) {}
            }
        }
    }
}
