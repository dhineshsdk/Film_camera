package com.s24film.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
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

    public interface Callback {
        void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes);
        void onDngError(String message);
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
        diskExecutor.execute(new SaveTask(context, rawImage, characteristics, captureResult, stockShortName, callback, mainHandler));
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
        private final Callback callback;
        private final Handler mainHandler;

        SaveTask(Context context, Image rawImage, CameraCharacteristics characteristics,
                 CaptureResult captureResult, String stockShortName,
                 Callback callback, Handler mainHandler) {
            this.context = context;
            this.rawImage = rawImage;
            this.characteristics = characteristics;
            this.captureResult = captureResult;
            this.stockShortName = stockShortName;
            this.callback = callback;
            this.mainHandler = mainHandler;
        }

        @Override
        public void run() {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            File outDng = new File(STORAGE_DIR, "IMG_" + timeStamp + ".dng");
            File outJpg = new File(STORAGE_DIR, "IMG_" + timeStamp + "_" + stockShortName + ".jpg");

            long dngBytes = 0;
            long jpgBytes = 0;

            try {
                // 1. Save Uncompressed RAW DNG ("Save First, Process Later")
                if (characteristics != null && captureResult != null) {
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
                byte[] rgba = VulkanRenderer.nativeCaptureLastRgba(dims);
                if (rgba != null && dims[0] > 0 && dims[1] > 0) {
                    Bitmap bmp = Bitmap.createBitmap(dims[0], dims[1], Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
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
