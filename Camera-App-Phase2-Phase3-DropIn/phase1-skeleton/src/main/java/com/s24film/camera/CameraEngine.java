package com.s24film.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraEngine {
    private static final String TAG = "S24FilmCamera";

    public interface FrameCallback {
        void onFrameRendered(String camId, int width, int height, int stride, int cfa,
                             double cameraFps, float vulkanMs);
    }

    private final Context context;
    private final CameraManager cameraManager;
    private final DngSaver dngSaver;
    private final FrameCallback frameCallback;

    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CameraCharacteristics characteristics;

    private SurfaceTexture companionTexture;
    private Surface companionSurface;
    private ImageReader rawImageReader;

    private String activeCameraId = "0";
    private String physicalSubId = null; // "6" when 3x Telephoto is selected on Logical Cam "0"
    private int rawWidth = 4080;
    private int rawHeight = 3060;
    private int rawStride = 4080;
    private int cfaCode = 2; // GBRG default for Cam 0
    private int sensorOrientation = 90;
    private int camMatrixIdx = 0; // 0=Wide, 1=Front, 2=UW, 3=Tele
    private float whiteLevel = 1023.0f;

    private volatile float dngGainR = 1.05f;
    private volatile float dngGainG = 1.00f;
    private volatile float dngGainB = 1.85f;

    private volatile float sceneGainR = 1.00f;
    private volatile float sceneGainB = 1.65f;
    private volatile boolean autoSceneWb = true; // True = Scene-Adaptive Auto WB, False = Film-True WB

    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    private volatile String pendingStockShortName = "Portra400";
    private volatile DngSaver.Callback pendingDngCallback = null;
    private volatile TotalCaptureResult latestCaptureResult = null;

    private double currentCameraFps = 0.0;
    private int frameCount = 0;
    private int totalFrames = 0;
    private long fpsStartTimeNs = 0;

    public CameraEngine(Context context, DngSaver dngSaver, FrameCallback frameCallback) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.dngSaver = dngSaver;
        this.frameCallback = frameCallback;
    }

    public void setAutoSceneWb(boolean enabled) {
        this.autoSceneWb = enabled;
    }

    public boolean isAutoSceneWb() {
        return this.autoSceneWb;
    }

    public synchronized void start() {
        if (cameraThread == null) {
            cameraThread = new HandlerThread("CameraBackgroundThread", Thread.MAX_PRIORITY);
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    public synchronized void stop() {
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    static class OpenCameraTask implements Runnable {
        private final CameraEngine engine;
        private final String cameraId;

        OpenCameraTask(CameraEngine engine, String cameraId) {
            this.engine = engine;
            this.cameraId = cameraId;
        }

        @Override
        public void run() {
            engine.openCameraInternal(cameraId);
        }
    }

    static class CloseCameraTask implements Runnable {
        private final CameraEngine engine;

        CloseCameraTask(CameraEngine engine) {
            this.engine = engine;
        }

        @Override
        public void run() {
            engine.closeCameraInternal();
        }
    }

    static class HandlerExecutor implements Executor {
        private final Handler handler;
        HandlerExecutor(Handler h) { this.handler = h; }
        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }

    public synchronized void openCamera(final String cameraId) {
        this.activeCameraId = cameraId;
        start();
        if (cameraHandler != null) {
            cameraHandler.post(new OpenCameraTask(this, cameraId));
        }
    }

    public void closeCamera() {
        if (cameraHandler != null) {
            cameraHandler.post(new CloseCameraTask(this));
        }
    }

    private void openCameraInternal(String requestedId) {
        try {
            closeCameraInternal();

            String openDeviceId = requestedId;
            physicalSubId = null;

            // Phase 0 Verified Rule: 3x Telephoto (S5K3K1) is Physical Camera "6" inside Logical Camera "0"
            if ("6".equals(requestedId) || "21".equals(requestedId)) {
                openDeviceId = "0";
                physicalSubId = "6";
                activeCameraId = "0 (Tele #6)";
                camMatrixIdx = 3;
            } else if ("2".equals(requestedId)) {
                activeCameraId = "2 (UW)";
                camMatrixIdx = 2;
            } else if ("1".equals(requestedId) || "3".equals(requestedId)) {
                activeCameraId = requestedId + " (Front)";
                camMatrixIdx = 1;
            } else {
                activeCameraId = "0 (Wide)";
                camMatrixIdx = 0;
            }

            characteristics = cameraManager.getCameraCharacteristics(openDeviceId);

            if (physicalSubId != null) {
                // Verified Phase 0 Telephoto S5K3K1 specs from dumpsys: 3648x2736 GBRG
                rawWidth = 3648;
                rawHeight = 2736;
                cfaCode = 2; // GBRG
                whiteLevel = 1023.0f;
                sensorOrientation = 90;
            } else {
                Integer cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfaCode = (cfa != null) ? cfa : 2;

                Integer wl = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
                whiteLevel = (wl != null) ? (float) wl : 1023.0f;

                Integer orient = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                sensorOrientation = (orient != null) ? orient : ("1".equals(openDeviceId) ? 270 : 90);

                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size selectedSize = null;
                if (map != null) {
                    Size[] rawSizes = map.getOutputSizes(ImageFormat.RAW_SENSOR);
                    if (rawSizes != null && rawSizes.length > 0) {
                        selectedSize = rawSizes[0];
                    }
                }
                if (selectedSize == null) {
                    Rect active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                    selectedSize = (active != null) ? new Size(active.width(), active.height()) : new Size(4080, 3060);
                }
                rawWidth = selectedSize.getWidth();
                rawHeight = selectedSize.getHeight();
            }

            Log.i(TAG, "Opening Camera " + activeCameraId + " (device=" + openDeviceId + ", phys=" + physicalSubId + ") -> "
                       + rawWidth + "x" + rawHeight + ", CFA=" + cfaCode + ", Orient=" + sensorOrientation);

            companionTexture = new SurfaceTexture(10);
            companionTexture.setDefaultBufferSize(1920, 1080);
            companionSurface = new Surface(companionTexture);

            rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 4);
            rawImageReader.setOnImageAvailableListener(new ImageListener(this), cameraHandler);

            cameraManager.openCamera(openDeviceId,
                new DeviceStateCallback(this, companionSurface, rawImageReader, physicalSubId),
                cameraHandler);

        } catch (SecurityException se) {
            Log.e(TAG, "Camera permission not granted: " + se.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera " + requestedId + ": " + e.getMessage(), e);
        }
    }

    public synchronized void triggerCapture(String stockShortName, DngSaver.Callback callback) {
        this.pendingStockShortName = stockShortName;
        this.pendingDngCallback = callback;
        this.captureRequested.set(true);
    }

    private void closeCameraInternal() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
                captureSession.abortCaptures();
                captureSession.close();
            } catch (Exception ignored) {}
            captureSession = null;
        }
        if (cameraDevice != null) {
            try { cameraDevice.close(); } catch (Exception ignored) {}
            cameraDevice = null;
        }
        if (rawImageReader != null) {
            try { rawImageReader.close(); } catch (Exception ignored) {}
            rawImageReader = null;
        }
        if (companionSurface != null) {
            try { companionSurface.release(); } catch (Exception ignored) {}
            companionSurface = null;
        }
        if (companionTexture != null) {
            try { companionTexture.release(); } catch (Exception ignored) {}
            companionTexture = null;
        }
    }

    static class DeviceStateCallback extends CameraDevice.StateCallback {
        private final CameraEngine engine;
        private final Surface companionSurface;
        private final ImageReader rawImageReader;
        private final String physicalSubId;

        DeviceStateCallback(CameraEngine engine, Surface companionSurface, ImageReader rawImageReader, String physicalSubId) {
            this.engine = engine;
            this.companionSurface = companionSurface;
            this.rawImageReader = rawImageReader;
            this.physicalSubId = physicalSubId;
        }

        @Override
        public void onOpened(CameraDevice device) {
            engine.cameraDevice = device;
            try {
                OutputConfiguration outCompanion = new OutputConfiguration(companionSurface);
                OutputConfiguration outRaw = new OutputConfiguration(rawImageReader.getSurface());
                if (physicalSubId != null) {
                    outCompanion.setPhysicalCameraId(physicalSubId);
                    outRaw.setPhysicalCameraId(physicalSubId);
                    Log.i(TAG, "Binding OutputConfigurations to Physical Camera ID: " + physicalSubId);
                }
                SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(outCompanion, outRaw),
                    new HandlerExecutor(engine.cameraHandler),
                    new SessionStateCallback(engine, device, companionSurface, rawImageReader, physicalSubId)
                );
                device.createCaptureSession(sessionConfig);
            } catch (Exception e) {
                Log.e(TAG, "SessionConfiguration failed (" + e.getMessage() + "), falling back to standard Cam 0");
                if (physicalSubId != null) {
                    engine.openCamera("0");
                }
            }
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            engine.closeCameraInternal();
        }

        @Override
        public void onError(CameraDevice device, int error) {
            Log.e(TAG, "CameraDevice error: " + error);
            engine.closeCameraInternal();
        }
    }

    static class SessionStateCallback extends CameraCaptureSession.StateCallback {
        private final CameraEngine engine;
        private final CameraDevice device;
        private final Surface companionSurface;
        private final ImageReader rawImageReader;
        private final String physicalSubId;

        SessionStateCallback(CameraEngine engine, CameraDevice device, Surface companionSurface,
                             ImageReader rawImageReader, String physicalSubId) {
            this.engine = engine;
            this.device = device;
            this.companionSurface = companionSurface;
            this.rawImageReader = rawImageReader;
            this.physicalSubId = physicalSubId;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            engine.captureSession = session;
            try {
                CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(companionSurface);
                builder.addTarget(rawImageReader.getSurface());

                builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                            CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);

                session.setRepeatingRequest(builder.build(), new CaptureCallback(engine), engine.cameraHandler);
                engine.fpsStartTimeNs = System.nanoTime();
                engine.frameCount = 0;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start repeating request: " + e.getMessage(), e);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "CaptureSession onConfigureFailed! physicalSubId=" + physicalSubId);
            if (physicalSubId != null) {
                engine.openCamera("0");
            }
        }
    }

    static class CaptureCallback extends CameraCaptureSession.CaptureCallback {
        private final CameraEngine engine;

        CaptureCallback(CameraEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            engine.latestCaptureResult = result;

            // Prefer DNG SENSOR_NEUTRAL_COLOR_POINT (AsShotNeutral = [1/gR, 1/gG, 1/gB])
            Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
            if (neutral != null && neutral.length >= 3) {
                float nr = Math.max(neutral[0].floatValue(), 0.05f);
                float ng = Math.max(neutral[1].floatValue(), 0.05f);
                float nb = Math.max(neutral[2].floatValue(), 0.05f);
                engine.dngGainR = ng / nr;
                engine.dngGainG = 1.0f;
                engine.dngGainB = ng / nb;
            } else {
                RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if (gains != null) {
                    float g = Math.max((gains.getGreenEven() + gains.getGreenOdd()) * 0.5f, 0.05f);
                    engine.dngGainR = gains.getRed() / g;
                    engine.dngGainG = 1.0f;
                    engine.dngGainB = gains.getBlue() / g;
                }
            }
        }
    }

    // Fast 16x16 grid midtone gray-world estimator directly on the Bayer buffer (runs once every 10 frames in <0.03ms)
    private void updateSceneAutoWbFromBayer(Image image) {
        try {
            ByteBuffer buf = image.getPlanes()[0].getBuffer();
            buf.order(ByteOrder.LITTLE_ENDIAN);
            int stride16 = image.getPlanes()[0].getRowStride() / 2;
            double sumR = 0.0, sumG = 0.0, sumB = 0.0;
            int count = 0;

            int stepX = Math.max(2, (rawWidth / 24) & ~1);
            int stepY = Math.max(2, (rawHeight / 24) & ~1);
            for (int y = rawHeight / 8; y < (rawHeight * 7) / 8; y += stepY) {
                for (int x = rawWidth / 8; x < (rawWidth * 7) / 8; x += stepX) {
                    int idx00 = (y * stride16 + x) * 2;
                    int idx01 = (y * stride16 + (x + 1)) * 2;
                    int idx10 = ((y + 1) * stride16 + x) * 2;
                    int idx11 = ((y + 1) * stride16 + (x + 1)) * 2;
                    if (idx11 + 2 >= buf.limit()) continue;

                    int v00 = buf.getShort(idx00) & 0xFFFF;
                    int v01 = buf.getShort(idx01) & 0xFFFF;
                    int v10 = buf.getShort(idx10) & 0xFFFF;
                    int v11 = buf.getShort(idx11) & 0xFFFF;

                    int r, g, b;
                    if (cfaCode == 0) { // RGGB
                        r = v00; g = (v01 + v10) >> 1; b = v11;
                    } else if (cfaCode == 1) { // GRBG
                        g = (v00 + v11) >> 1; r = v01; b = v10;
                    } else if (cfaCode == 2) { // GBRG (Cam 0 Wide & Tele)
                        g = (v00 + v11) >> 1; b = v01; r = v10;
                    } else { // BGGR
                        b = v00; g = (v01 + v10) >> 1; r = v11;
                    }

                    // Only include midtone unclipped samples (exclude dark noise & bright screens)
                    if (g > 15 && g < 550 && r > 15 && r < 780 && b > 10 && b < 780) {
                        sumR += r;
                        sumG += g;
                        sumB += b;
                        count++;
                    }
                }
            }
            if (count > 20 && sumR > 1.0 && sumB > 1.0) {
                float targetR = (float) (sumG / sumR);
                float targetB = (float) (sumG / sumB);
                targetR = Math.max(0.42f, Math.min(2.40f, targetR));
                targetB = Math.max(0.65f, Math.min(2.80f, targetB));
                sceneGainR = 0.75f * sceneGainR + 0.25f * targetR;
                sceneGainB = 0.75f * sceneGainB + 0.25f * targetB;
            }
        } catch (Exception ignored) {}
    }

    static class ImageListener implements ImageReader.OnImageAvailableListener {
        private final CameraEngine engine;

        ImageListener(CameraEngine engine) {
            this.engine = engine;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
            } catch (Exception e) {
                return;
            }
            if (image == null) return;

            long now = System.nanoTime();
            engine.frameCount++;
            engine.totalFrames++;
            if (now - engine.fpsStartTimeNs >= 1_000_000_000L) {
                engine.currentCameraFps = (engine.frameCount * 1_000_000_000.0) / (now - engine.fpsStartTimeNs);
                engine.frameCount = 0;
                engine.fpsStartTimeNs = now;
            }

            if (image.getPlanes() != null && image.getPlanes().length > 0) {
                engine.rawStride = image.getPlanes()[0].getRowStride() / 2;
            }

            if ((engine.totalFrames % 10) == 1) {
                engine.updateSceneAutoWbFromBayer(image);
            }

            float activeGainR = engine.autoSceneWb ? engine.sceneGainR : engine.dngGainR;
            float activeGainG = 1.0f;
            float activeGainB = engine.autoSceneWb ? engine.sceneGainB : engine.dngGainB;

            // Always render the frame through Vulkan first so even on shutter press the readback buffer has the latest frame!
            float vulkanMs = 0.0f;
            HardwareBuffer hb = image.getHardwareBuffer();
            if (hb != null) {
                try {
                    vulkanMs = VulkanRenderer.nativeRenderRaw(
                        hb, engine.rawWidth, engine.rawHeight, engine.rawStride,
                        engine.cfaCode, engine.whiteLevel,
                        activeGainR, activeGainG, activeGainB,
                        engine.sensorOrientation, engine.camMatrixIdx
                    );
                } finally {
                    hb.close();
                }
            }

            if (engine.captureRequested.compareAndSet(true, false)) {
                Log.i(TAG, "Shutter triggered! Saving uncompressed DNG + Master Film JPEG...");
                engine.dngSaver.saveDngAndMasterAsync(
                    image, engine.characteristics, engine.latestCaptureResult,
                    engine.pendingStockShortName, engine.pendingDngCallback
                );
                return;
            }

            image.close();

            if (engine.frameCallback != null) {
                engine.frameCallback.onFrameRendered(
                    engine.activeCameraId, engine.rawWidth, engine.rawHeight,
                    engine.rawStride * 2, engine.cfaCode,
                    engine.currentCameraFps, vulkanMs
                );
            }
        }
    }
}
