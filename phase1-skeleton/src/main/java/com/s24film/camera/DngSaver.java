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
    // Exposure index the film look was rated at (0 = digital look: the sensor ISO is written).
    private static volatile int exposureIndex = 0;
    public static void setExposureIndex(int ei) { exposureIndex = Math.max(0, ei); }

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
            // "Folder/name" puts the frame in a sub-folder of DCIM/S24Film (compare sets).
            File dir = STORAGE_DIR;
            String name = stockShortName;
            int slash = (name != null) ? name.lastIndexOf('/') : -1;
            if (slash > 0) {
                dir = new File(STORAGE_DIR, name.substring(0, slash));
                name = name.substring(slash + 1);
                if (!dir.exists()) dir.mkdirs();
            }
            File outDng = new File(dir, "IMG_" + timeStamp + ".dng");
            File outJpg = new File(dir, "IMG_" + timeStamp + "_" + name + ".jpg");

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
                    } finally {
                        bmp.recycle();
                    }
                    try {
                        writeExif(outJpg, captureResult, timeStamp);
                    } catch (Throwable t) {
                        Log.w(TAG, "EXIF write failed: " + t.getMessage());
                    }
                    jpgBytes = outJpg.length();
                    Log.i(TAG, "Saved Master Film JPEG: " + outJpg.getAbsolutePath() + " (" + (jpgBytes / 1024) + " KB)");
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

    // Inserts a minimal EXIF block (camera, shutter, aperture, ISO / film EI, focal length, time)
    // after SOI/APP0. Bitmap.compress writes none. Inserting ahead of the Ultra HDR MPF segment
    // keeps the gain map valid: MPF offsets are relative to the MPF header, which moves with it.
    static void writeExif(File jpg, CaptureResult r, String timeStamp) throws java.io.IOException {
        byte[] src = java.nio.file.Files.readAllBytes(jpg.toPath());
        if (src.length < 4 || (src[0] & 0xFF) != 0xFF || (src[1] & 0xFF) != 0xD8) return;
        int insertAt = 2;
        if ((src[2] & 0xFF) == 0xFF && (src[3] & 0xFF) == 0xE0) {
            insertAt = 4 + (((src[4] & 0xFF) << 8) | (src[5] & 0xFF));
        }
        Long expNs = (r != null) ? r.get(CaptureResult.SENSOR_EXPOSURE_TIME) : null;
        Integer sens = (r != null) ? r.get(CaptureResult.SENSOR_SENSITIVITY) : null;
        Float fNum = (r != null) ? r.get(CaptureResult.LENS_APERTURE) : null;
        Float focal = (r != null) ? r.get(CaptureResult.LENS_FOCAL_LENGTH) : null;
        int ei = exposureIndex;
        int iso = (ei > 0) ? ei : (sens != null ? sens : 0);
        String date = timeStamp.length() >= 15
            ? timeStamp.substring(0, 4) + ":" + timeStamp.substring(4, 6) + ":" + timeStamp.substring(6, 8) + " "
              + timeStamp.substring(9, 11) + ":" + timeStamp.substring(11, 13) + ":" + timeStamp.substring(13, 15)
            : "";
        ExifBuilder ifd0 = new ExifBuilder();
        ifd0.ascii(0x010F, "samsung");
        ifd0.ascii(0x0110, Build.MODEL);
        ifd0.ascii(0x0131, "S24Film");
        ExifBuilder exif = new ExifBuilder();
        if (expNs != null && expNs > 0) {
            if (expNs < 1_000_000_000L) exif.rational(0x829A, 1, (int) Math.max(1L, Math.round(1e9 / expNs)));
            else exif.rational(0x829A, (int) (expNs / 1_000_000L), 1000);
        }
        if (fNum != null && fNum > 0) exif.rational(0x829D, Math.round(fNum * 100), 100);
        if (iso > 0) exif.shortVal(0x8827, Math.min(iso, 65535));
        if (!date.isEmpty()) exif.ascii(0x9003, date);
        if (focal != null && focal > 0) exif.rational(0x920A, Math.round(focal * 100), 100);
        byte[] tiff = ExifBuilder.tiff(ifd0, exif);
        int segLen = 2 + 6 + tiff.length;
        if (segLen > 0xFFFF) return;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(src.length + segLen + 2);
        out.write(src, 0, insertAt);
        out.write(0xFF); out.write(0xE1);
        out.write((segLen >> 8) & 0xFF); out.write(segLen & 0xFF);
        out.write(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.write(tiff);
        out.write(src, insertAt, src.length - insertAt);
        try (FileOutputStream fos = new FileOutputStream(jpg)) {
            out.writeTo(fos);
        }
    }

    // Big-endian TIFF IFD writer for the few EXIF tags above.
    static final class ExifBuilder {
        private final ArrayList<int[]> entries = new ArrayList<>();   // tag, type, count, valueIndex
        private final ArrayList<byte[]> values = new ArrayList<>();

        void ascii(int tag, String v) {
            byte[] b = (v + " ").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            add(tag, 2, b.length, b);
        }
        void shortVal(int tag, int v) { add(tag, 3, 1, new byte[] {(byte) (v >> 8), (byte) v, 0, 0}); }
        void rational(int tag, int num, int den) {
            add(tag, 5, 1, new byte[] {(byte) (num >> 24), (byte) (num >> 16), (byte) (num >> 8), (byte) num,
                (byte) (den >> 24), (byte) (den >> 16), (byte) (den >> 8), (byte) den});
        }
        private void add(int tag, int type, int count, byte[] data) {
            entries.add(new int[] {tag, type, count, values.size()});
            values.add(data);
        }
        int size(boolean withPointer) {
            int n = entries.size() + (withPointer ? 1 : 0);
            int sz = 2 + n * 12 + 4;
            for (byte[] v : values) if (v.length > 4) sz += (v.length + 1) & ~1;
            return sz;
        }
        // Writes this IFD at offset 'at' (from the TIFF header); pointerTag adds a LONG entry.
        void write(java.io.ByteArrayOutputStream o, int at, int pointerTag, int pointerValue) {
            int n = entries.size() + (pointerTag != 0 ? 1 : 0);
            int data = at + 2 + n * 12 + 4;
            java.io.ByteArrayOutputStream extra = new java.io.ByteArrayOutputStream();
            u16(o, n);
            for (int[] e : entries) {
                byte[] v = values.get(e[3]);
                u16(o, e[0]); u16(o, e[1]); u32(o, e[2]);
                if (v.length <= 4) {
                    o.write(v, 0, v.length);
                    for (int i = v.length; i < 4; i++) o.write(0);
                } else {
                    u32(o, data + extra.size());
                    extra.write(v, 0, v.length);
                    if ((v.length & 1) != 0) extra.write(0);
                }
            }
            if (pointerTag != 0) { u16(o, pointerTag); u16(o, 4); u32(o, 1); u32(o, pointerValue); }
            u32(o, 0);
            byte[] x = extra.toByteArray();
            o.write(x, 0, x.length);
        }
        static byte[] tiff(ExifBuilder ifd0, ExifBuilder exif) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            o.write('M'); o.write('M'); u16(o, 42); u32(o, 8);
            int exifAt = 8 + ifd0.size(true);
            ifd0.write(o, 8, 0x8769, exifAt);
            exif.write(o, exifAt, 0, 0);
            return o.toByteArray();
        }
        private static void u16(java.io.ByteArrayOutputStream o, int v) { o.write((v >> 8) & 0xFF); o.write(v & 0xFF); }
        private static void u32(java.io.ByteArrayOutputStream o, int v) { u16(o, v >>> 16); u16(o, v & 0xFFFF); }
    }
}
