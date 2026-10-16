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

import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

public class CameraEngine {
    private static final String TAG = "S24FilmCamera";

    public interface FrameCallback {
        void onFrameRendered(String camId, int width, int height, int stride, int cfa,
                             double cameraFps, float vulkanMs, int activeOrient);
    }

    private final Context context;
    private final CameraManager cameraManager;
    private final DngSaver dngSaver;
    private final ImuTelemetry imuTelemetry;
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
    private String physicalSubId = null;
    private int rawWidth = 4080;
    private int rawHeight = 3060;
    private int rawStride = 4080;
    private int cfaCode = 2; // GBRG default for Cam 0
    private int camMatrixIdx = 0; // 0=Wide, 1=Front, 2=UW, 3=Tele
    private float whiteLevel = 1023.0f;

    private volatile float dngGainR = 1.00f;
    private volatile float dngGainG = 1.00f;
    private volatile float dngGainB = 1.00f;
    private volatile float userWbWarmth = 1.00f;
    private volatile float userWbTint = 1.00f;

    private volatile boolean autoSceneWb = true;
    private volatile int manualRotationOffset = 0; // 0, 90, 180, 270 user override

    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    private volatile String pendingStockShortName = "Portra400";
    private volatile DngSaver.Callback pendingDngCallback = null;
    private volatile TotalCaptureResult latestCaptureResult = null;

    private double currentCameraFps = 0.0;
    private int frameCount = 0;
    private long fpsStartTimeNs = 0;

    public CameraEngine(Context context, DngSaver dngSaver, ImuTelemetry imuTelemetry, FrameCallback frameCallback) {
        this.context = context;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        this.dngSaver = dngSaver;
        this.imuTelemetry = imuTelemetry;
        this.frameCallback = frameCallback;
    }

    public void setAutoSceneWb(boolean enabled) {
        this.autoSceneWb = enabled;
    }

    public boolean isAutoSceneWb() {
        return this.autoSceneWb;
    }

    public void setUserWbWarmth(float warmth) {
        this.userWbWarmth = warmth;
    }

    public void setUserWbTint(float tint) {
        this.userWbTint = tint;
    }

    public int cycleManualRotationOffset() {
        manualRotationOffset = (manualRotationOffset + 90) % 360;
        return manualRotationOffset;
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

            if ("6".equals(requestedId) || "21".equals(requestedId)) {
                openDeviceId = "0";
                physicalSubId = "6";
                activeCameraId = "0 (3x Tele #6)";
                camMatrixIdx = 3;
            } else if ("2".equals(requestedId)) {
                activeCameraId = "2 (0.6x UW)";
                camMatrixIdx = 2;
            } else if ("1".equals(requestedId) || "3".equals(requestedId)) {
                activeCameraId = requestedId + " (Front)";
                camMatrixIdx = 1;
            } else {
                activeCameraId = "0 (1x Wide)";
                camMatrixIdx = 0;
            }

            characteristics = cameraManager.getCameraCharacteristics(openDeviceId);

            if (physicalSubId != null) {
                rawWidth = 3648;
                rawHeight = 2736;
                cfaCode = 2; // GBRG
                whiteLevel = 1023.0f;
                try {
                    CameraCharacteristics subChars = cameraManager.getCameraCharacteristics(physicalSubId);
                    if (subChars != null) {
                        characteristics = subChars;
                        Log.i(TAG, "Assigned physical camera characteristics for " + physicalSubId);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to get physical characteristics: " + t.getMessage());
                }
            } else {
                Integer cfa = characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
                cfaCode = (cfa != null) ? cfa : 2;

                Integer wl = characteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
                whiteLevel = (wl != null) ? (float) wl : 1023.0f;

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

            companionTexture = new SurfaceTexture(10);
            companionTexture.setDefaultBufferSize(1920, 1080);
            companionSurface = new Surface(companionTexture);

            rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 4);
            rawImageReader.setOnImageAvailableListener(new ImageListener(this), cameraHandler);

            cameraManager.openCamera(openDeviceId,
                new DeviceStateCallback(this, companionSurface, rawImageReader, physicalSubId),
                cameraHandler);

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
                }
                SessionConfiguration sessionConfig = new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    Arrays.asList(outCompanion, outRaw),
                    new HandlerExecutor(engine.cameraHandler),
                    new SessionStateCallback(engine, device, companionSurface, rawImageReader, physicalSubId)
                );
                device.createCaptureSession(sessionConfig);
            } catch (Exception e) {
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

                session.setRepeatingRequest(builder.build(), new CaptureCallback(engine), engine.cameraHandler);
                engine.fpsStartTimeNs = System.nanoTime();
                engine.frameCount = 0;
            } catch (Exception e) {
                Log.e(TAG, "Failed to start repeating request: " + e.getMessage(), e);
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
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

            RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
            if (gains != null) {
                float g = Math.max((gains.getGreenEven() + gains.getGreenOdd()) * 0.5f, 0.05f);
                engine.dngGainR = gains.getRed() / g;
                engine.dngGainG = 1.0f;
                engine.dngGainB = gains.getBlue() / g;
            } else {
                Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                if (neutral != null && neutral.length >= 3) {
                    float nr = Math.max(neutral[0].floatValue(), 0.05f);
                    float ng = Math.max(neutral[1].floatValue(), 0.05f);
                    float nb = Math.max(neutral[2].floatValue(), 0.05f);
                    engine.dngGainR = ng / nr;
                    engine.dngGainG = 1.0f;
                    engine.dngGainB = ng / nb;
                }
            }
        }
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
            if (now - engine.fpsStartTimeNs >= 1_000_000_000L) {
                engine.currentCameraFps = (engine.frameCount * 1_000_000_000.0) / (now - engine.fpsStartTimeNs);
                engine.frameCount = 0;
                engine.fpsStartTimeNs = now;
            }

            if (image.getPlanes() != null && image.getPlanes().length > 0) {
                engine.rawStride = image.getPlanes()[0].getRowStride() / 2;
            }

            // Calibrated White Balance matched to Samsung's stock reference camera:
            float autoR = engine.autoSceneWb ? 0.82f : 1.0f;
            float autoB = engine.autoSceneWb ? 1.22f : 1.0f;
            float gainR = engine.dngGainR * autoR * engine.userWbWarmth;
            float gainG = 1.0f * engine.userWbTint;
            float gainB = engine.dngGainB * autoB * (2.0f - engine.userWbWarmth);

            int baseOrient = (engine.imuTelemetry != null) ? engine.imuTelemetry.getGravityOrientation() : 0;
            int activeOrient = (baseOrient + engine.manualRotationOffset) % 360;

            float vulkanMs = 0.0f;
            HardwareBuffer hb = image.getHardwareBuffer();
            if (hb != null) {
                try {
                    vulkanMs = VulkanRenderer.nativeRenderRaw(
                        hb, engine.rawWidth, engine.rawHeight, engine.rawStride,
                        engine.cfaCode, engine.whiteLevel,
                        gainR, gainG, gainB,
                        activeOrient, engine.camMatrixIdx
                    );
                } finally {
                    hb.close();
                }
            }

            if (engine.captureRequested.compareAndSet(true, false)) {
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
                    engine.currentCameraFps, vulkanMs, activeOrient
                );
            }
        }
    }
}
