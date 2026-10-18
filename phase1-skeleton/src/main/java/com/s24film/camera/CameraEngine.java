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
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import java.util.ArrayList;
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

    // 1080p companion stream the HAL runs its 3A on. It must be drained: an undrained
    // SurfaceTexture let the HAL run out of preview buffers (8 s buffer timeouts, stalled
    // session), so frames go to an ImageReader that is emptied on its own thread.
    private ImageReader companionReader;
    private Surface companionSurface;
    private HandlerThread drainThread;
    private Handler drainHandler;
    private ImageReader rawImageReader;

    private String activeCameraId = "0";
    private String physicalSubId = null;
    private int rawWidth = 4080;
    private int rawHeight = 3060;
    private int rawStride = 4080;
    private int cfaCode = 2; // GBRG default for Cam 0
    // Per camMatrixIdx (0 wide, 1 front, 2 UW, 3 tele) white-balance trims, see the capture callback.
    private static final float[] LENS_TRIM_R = {1.03f, 1.0f, 1.0f, 1.0f};
    private static final float[] LENS_TRIM_B = {1.0f, 1.0f, 1.065f, 1.0f};
    private int camMatrixIdx = 0; // 0=Wide, 1=Front, 2=UW, 3=Tele
    private float whiteLevel = 1023.0f;

    private volatile float dngGainR = 1.00f;
    private volatile float dngGainG = 1.00f;
    private volatile float dngGainB = 1.00f;
    private volatile float userWbWarmth = 1.00f;
    private volatile float userWbTint = 1.00f;

    private volatile boolean autoSceneWb = true;   // true: as-shot neutral, false: daylight film balance
    private volatile DngColor dngColor = null;
    private DebugControl debugControl = null;
    private volatile int manualRotationOffset = 0; // 0, 90, 180, 270 user override
    private volatile int lockedCaptureOrient = 0;  // grip + ROT at the moment the shutter was pressed

    private final AtomicBoolean captureRequested = new AtomicBoolean(false);
    private String lastRequestedCameraId = null;
    private volatile String pendingStockShortName = "Portra400";
    private volatile DngSaver.Callback pendingDngCallback = null;
    private volatile TotalCaptureResult latestCaptureResult = null;

    // ---------------------------------------------------------------- DCG-aware Auto-ISO
    // Lab models (S24HardwareCalibrationMaster.h kS24SensorNoiseModels):
    // {minIso, maxIso, LCG sweet spot, HCG switch} per camMatrixIdx.
    private static final int[][] DCG_MODELS = {
        {25, 3200, 125, 250},   // 0 Wide GN3: prefer 125, then 250 / 400
        {25, 3200, 160, 400},   // 1 Front
        {48, 3200, 160, 250},   // 2 UW
        {25, 3200, 100, 400},   // 3 Tele #6
    };
    private static final int AVOID_ISO = 320;               // DCG transition dip (lab report)
    private static final int[] HIGH_ISO_STEPS = {500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200};
    private static final int[] SUB_SWEET_ISOS = {100, 80, 64, 50, 40, 32, 25};
    private static final long TARGET_SHUTTER_NS = 16_666_667L;   // 1/60 s
    private static final long MAX_SHUTTER_NS = 33_333_333L;      // keep 30 fps
    private static final long MIN_SUB_SWEET_SHUTTER_NS = 250_000L;
    private static final long HW_MIN_SHUTTER_FLOOR_NS = 85_000L;
    private static final int AE_WARMUP_MIN_FRAMES = 8;
    private static final int AE_WARMUP_MAX_FRAMES = 45;
    private static final int AE_APPLY_INTERVAL_FRAMES = 3;
    private static final float AE_DEADBAND_EV = 0.17f;
    private static final float AE_GAIN = 0.6f;
    private static final float AE_MAX_STEP_EV = 2.0f;

    // Film-exposure loop: the capture pipeline lags the request by several frames, so it checks
    // rarely, holds inside a dead band, leaves it only on a clear sustained error, then walks in
    // small steps (no pumping). Shutter moves on a 1/3-stop ladder so it never creeps.
    private static final int FILM_AE_INTERVAL_FRAMES = 8;
    private static final float FILM_HOLD_EV = 0.30f;      // inside this: hold
    private static final float FILM_LEAVE_EV = 0.50f;     // outside this twice running: start moving
    private static final float FILM_SETTLE_EV = 0.12f;    // moving stops inside this
    private static final float FILM_STEP_EV = 0.5f;       // largest move per check
    private static final float FILM_GAIN = 0.6f;
    /** Set while a video take is recording: exposure stays where it was when the take started. */
    static volatile boolean videoHold = false;
    private boolean filmMoving = false;
    private int filmOutCount = 0;

    private CaptureRequest.Builder previewBuilder;
    private volatile boolean manualAeActive = false;
    private int aeWarmupFrames = 0;
    private float aeTargetRawMean = 0.0f;
    private int framesSinceAeApply = 0;
    private int currentIso = 100;
    private long currentExposureNs = 10_000_000L;
    private volatile int resultIso = 100;
    private volatile long resultExposureNs = 10_000_000L;
    private volatile boolean aeConverged = false;
    private int[] isoLadder = new int[0];
    private int[] subSweetLadder = new int[0];
    private int deviceMinIso = 25;
    private int deviceMaxIso = 3200;
    private long minShutterNs = HW_MIN_SHUTTER_FLOOR_NS;
    private long maxShutterNs = MAX_SHUTTER_NS;
    // Longest frame the AE target fps ranges allow (1/15 s at the 15 fps floor). A manual
    // exposure longer than 1/30 s needs that range in the request: without it the UW and front
    // HALs silently cap the shutter at 30 ms and raise the ISO instead.
    private long slowestFrameNs = MAX_SHUTTER_NS;
    private Range<Integer> slowFpsRange = null;
    private Range<Integer> defaultFpsRange = null;
    private volatile boolean videoMode = false;
    private float staticBlackLevel = 0.0f;

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

    // ---------------------------------------------------------------- Pro controls
    private volatile int manualIso = 0;              // 0 = Auto (clean-ISO ladder)
    private volatile float manualFocusDiopters = -1f; // < 0 = continuous AF

    private int clampIso(int iso) { return Math.max(deviceMinIso, Math.min(deviceMaxIso, iso)); }

    /** Fixed sensor ISO (the shutter follows the scene); 0 returns to Auto. */
    public void setManualIso(int iso) {
        manualIso = Math.max(0, iso);
        if (cameraHandler != null) cameraHandler.post(new IsoTask(this));
    }
    public int getManualIso() { return manualIso; }
    public int getDeviceMinIso() { return deviceMinIso; }
    public int getDeviceMaxIso() { return deviceMaxIso; }

    /** Manual focus in diopters (0 = infinity .. max = closest); negative returns to continuous AF. */
    public void setManualFocus(float diopters) {
        manualFocusDiopters = diopters;
        if (cameraHandler != null) cameraHandler.post(new FocusTask(this));
    }
    public boolean isManualFocus() { return manualFocusDiopters >= 0f; }

    /** Closest focus in diopters for the open lens (0 when it is fixed-focus). */
    public float getMinFocusDiopters() {
        Float f = (characteristics != null)
            ? characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) : null;
        return (f != null) ? f : 0f;
    }

    private void applyFocus() {
        if (captureSession == null || previewBuilder == null) return;
        try {
            if (manualFocusDiopters >= 0f) {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                previewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(manualFocusDiopters, getMinFocusDiopters()));
            } else {
                previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            }
            captureSession.setRepeatingRequest(previewBuilder.build(), new CaptureCallback(this), cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Focus update failed: " + e.getMessage(), e);
        }
    }

    /** White balance as a colour temperature of the light (K; 0 = as-shot) and a green/magenta tint (-1..1). */
    public void setUserWbKelvin(int kelvin) {
        userWbWarmth = (kelvin <= 0) ? 1.0f
            : Math.max(0.5f, Math.min(1.5f, 1.0f + 0.6f * (float) Math.log(kelvin / 6500.0)));
    }
    public void setUserWbTintStops(float tint) {
        userWbTint = Math.max(0.7f, Math.min(1.3f, 1.0f - 0.25f * tint));   // + = magenta (less green)
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

    public void resetManualRotationOffset() {
        manualRotationOffset = 0;
    }

    // Grip plus the ROT override, in the accelerometer convention (0 portrait, 270 = left edge down).
    int currentActiveOrient() {
        int base = (imuTelemetry != null) ? imuTelemetry.getGravityOrientation() : 0;
        return (base + manualRotationOffset) % 360;
    }

    public synchronized void start() {
        if (debugControl == null) {
            debugControl = new DebugControl(this);
            debugControl.register(context);
        }
        if (cameraThread == null) {
            cameraThread = new HandlerThread("CameraBackgroundThread", Thread.MAX_PRIORITY);
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    public synchronized void stop() {
        if (debugControl != null) {
            debugControl.unregister(context);
            debugControl = null;
        }
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
        if (drainThread != null) {
            drainThread.quitSafely();
            drainThread = null;
            drainHandler = null;
        }
    }

    // Named classes: d8 cannot take javac's anonymous $1 class files in this build.
    static class IsoTask implements Runnable {
        private final CameraEngine e;
        IsoTask(CameraEngine e) { this.e = e; }
        @Override public void run() {
            if (e.manualAeActive && e.filmKey.isEmpty()) {
                if (e.videoMode) e.filmIsoSeconds = e.currentIso * (e.currentExposureNs * 1e-9);
                e.chooseIsoAndShutter(e.currentIso * (e.currentExposureNs * 1e-9));
                e.applyManualExposure();
            }
        }
    }

    static class FocusTask implements Runnable {
        private final CameraEngine e;
        FocusTask(CameraEngine e) { this.e = e; }
        @Override public void run() { e.applyFocus(); }
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
        // A ROT override is for one lens; a lens change starts upright again (issue 4).
        if (lastRequestedCameraId != null && !lastRequestedCameraId.equals(cameraId)) {
            manualRotationOffset = 0;
        }
        lastRequestedCameraId = cameraId;
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
            resetAutoIso();

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

            configureAutoIsoForCamera();

            if (drainThread == null) {
                drainThread = new HandlerThread("CompanionDrain");
                drainThread.start();
                drainHandler = new Handler(drainThread.getLooper());
            }
            companionReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 3);
            companionReader.setOnImageAvailableListener(new DrainListener(), drainHandler);
            companionSurface = companionReader.getSurface();

            rawImageReader = ImageReader.newInstance(rawWidth, rawHeight, ImageFormat.RAW_SENSOR, 4);
            rawImageReader.setOnImageAvailableListener(new ImageListener(this), cameraHandler);

            cameraManager.openCamera(openDeviceId,
                new DeviceStateCallback(this, companionSurface, rawImageReader, physicalSubId),
                cameraHandler);

        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera " + requestedId + ": " + e.getMessage(), e);
        }
    }

    private void resetAutoIso() {
        manualAeActive = false;
        aeConverged = false;
        aeWarmupFrames = 0;
        aeTargetRawMean = 0.0f;
        meterLogL = Double.NaN;
        framesSinceAeApply = 0;
        previewBuilder = null;
    }

    // ---------------------------------------------------------------- Scene metering
    // Scene luminance L (raw per ISO-second) comes from each frame's own capture result, so it does
    // not depend on which request the pipeline had reached (the old error-chasing loop lagged the
    // pipeline and had to be slowed to a crawl). Smoothed in log2, then the exposure the scene
    // needs for the reference brightness is aeTargetRawMean / L.
    private double meterLogL = Double.NaN;
    private static final double LN2 = Math.log(2.0);

    /** ISO x seconds (exposure index x shutter) that renders the reference brightness, or -1 if not ready. */
    private double meterDesiredIsoSeconds(float mean) {
        double es = resultIso * resultExposureNs * 1e-9;
        if (es <= 0 || mean <= 1e-5f || aeTargetRawMean <= 0) return -1;
        double logL = Math.log(mean / es) / LN2;
        if (Double.isNaN(meterLogL)) meterLogL = logL;
        double d = logL - meterLogL;
        double a = videoMode ? 0.06 : 0.30;
        if (Math.abs(d) > 1.0) a = videoMode ? 0.30 : 0.70;   // big scene change: catch up fast
        meterLogL += a * d;
        return aeTargetRawMean / Math.pow(2.0, meterLogL);
    }

    // Builds the preferred ISO ladder for the active camera, always skipping ISO 320.
    private void configureAutoIsoForCamera() {
        int[] model = DCG_MODELS[Math.max(0, Math.min(3, camMatrixIdx))];
        deviceMinIso = model[0];
        deviceMaxIso = model[1];
        Range<Integer> isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) {
            deviceMinIso = Math.max(deviceMinIso, isoRange.getLower());
            deviceMaxIso = Math.min(deviceMaxIso, isoRange.getUpper());
        }
        Range<Long> expRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        minShutterNs = HW_MIN_SHUTTER_FLOOR_NS;
        maxShutterNs = MAX_SHUTTER_NS;
        if (expRange != null) {
            minShutterNs = Math.max(minShutterNs, expRange.getLower());
            maxShutterNs = Math.min(maxShutterNs, expRange.getUpper());
            deviceMaxShutterNs = expRange.getUpper();
        }
        slowFpsRange = null;
        slowestFrameNs = MAX_SHUTTER_NS;
        Range<Integer>[] fpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (fpsRanges != null) {
            for (Range<Integer> r : fpsRanges) {
                if (r.getLower() <= 0) continue;
                if (slowFpsRange == null || r.getLower() < slowFpsRange.getLower()
                    || (r.getLower().equals(slowFpsRange.getLower()) && r.getUpper() > slowFpsRange.getUpper())) {
                    slowFpsRange = r;
                }
            }
        }
        if (slowFpsRange != null) {
            slowestFrameNs = Math.max(MAX_SHUTTER_NS, 1_000_000_000L / slowFpsRange.getLower());
        }
        deviceMaxShutterNs = Math.min(deviceMaxShutterNs, slowestFrameNs);

        int sweet = model[2];
        int hcg = model[3];
        ArrayList<Integer> ladder = new ArrayList<>();
        addIso(ladder, sweet);
        addIso(ladder, hcg);
        if (400 > hcg) addIso(ladder, 400);
        for (int iso : HIGH_ISO_STEPS) addIso(ladder, iso);
        isoLadder = toArray(ladder);

        ArrayList<Integer> sub = new ArrayList<>();
        for (int iso : SUB_SWEET_ISOS) {
            if (iso < sweet) addIso(sub, iso);
        }
        subSweetLadder = toArray(sub);

        dngColor = DngColor.from(characteristics);
        if (dngColor == null) VulkanRenderer.nativeSetForwardMatrix(null);

        BlackLevelPattern blp = characteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        staticBlackLevel = 0.0f;
        if (blp != null) {
            staticBlackLevel = (blp.getOffsetForIndex(0, 0) + blp.getOffsetForIndex(1, 0)
                + blp.getOffsetForIndex(0, 1) + blp.getOffsetForIndex(1, 1)) * 0.25f;
        }
        Log.i(TAG, "Auto-ISO cam " + camMatrixIdx + ": ladder " + Arrays.toString(isoLadder)
            + " sub-sweet " + Arrays.toString(subSweetLadder)
            + " shutter " + minShutterNs + ".." + maxShutterNs + " ns, black " + staticBlackLevel);
    }

    private void addIso(ArrayList<Integer> list, int iso) {
        if (iso == AVOID_ISO || iso < deviceMinIso || iso > deviceMaxIso) return;
        if (list.contains(iso)) return;
        list.add(iso);
    }

    private static int[] toArray(ArrayList<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    // Splits a total exposure (ISO x seconds) into the cleanest ISO / shutter pair:
    // lowest ladder ISO that keeps the shutter at or under 1/60 s; sub-sweet ISOs only when
    // the sweet-spot ISO would need a shutter shorter than 250 us.
    private void chooseIsoAndShutter(double isoSeconds) {
        if (isoLadder.length == 0) return;
        if (videoMode) {
            // Video: the shutter angle fixes the shutter, ISO takes up the exposure.
            long tv = videoShutterNs();
            currentExposureNs = tv;
            currentIso = (manualIso > 0) ? clampIso(manualIso) : nearestCleanIso(isoSeconds / (tv * 1e-9));
            return;
        }
        if (manualIso > 0) {
            currentIso = clampIso(manualIso);
            currentExposureNs = Math.max(minShutterNs, Math.min(maxShutterNs, (long) (isoSeconds / currentIso * 1e9)));
            return;
        }
        int iso = isoLadder[isoLadder.length - 1];
        long t = (long) (isoSeconds / iso * 1e9);
        for (int candidate : isoLadder) {
            long tc = (long) (isoSeconds / candidate * 1e9);
            if (tc <= TARGET_SHUTTER_NS) {
                iso = candidate;
                t = tc;
                break;
            }
        }
        if (iso == isoLadder[0] && t < MIN_SUB_SWEET_SHUTTER_NS) {
            for (int candidate : subSweetLadder) {
                iso = candidate;
                t = (long) (isoSeconds / candidate * 1e9);
                if (t >= MIN_SUB_SWEET_SHUTTER_NS) break;
            }
        }
        currentIso = iso;
        currentExposureNs = Math.max(minShutterNs, Math.min(maxShutterNs, t));
    }

    private void applyManualExposure() {
        if (captureSession == null || previewBuilder == null) return;
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            previewBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, currentIso);
            previewBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, currentExposureNs);
            previewBuilder.set(CaptureRequest.SENSOR_FRAME_DURATION,
                videoMode ? videoFrameDurationNs() : Math.max(currentExposureNs, MAX_SHUTTER_NS));
            if (defaultFpsRange == null) defaultFpsRange = previewBuilder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
            Range<Integer> fps = ((currentExposureNs > MAX_SHUTTER_NS || slowFpsAlways) && slowFpsRange != null) ? slowFpsRange : defaultFpsRange;
            if (videoMode) fps = videoFpsRange();
            if (fps != null) previewBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
            captureSession.setRepeatingRequest(previewBuilder.build(), new CaptureCallback(this), cameraHandler);
        } catch (Exception e) {
            Log.e(TAG, "Manual exposure update failed: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- Film-speed exposure
    // A film camera never changes ISO: the stock's box speed (x 2^push) is the exposure index,
    // the sensor sits at the nearest clean ISO, the remainder is an exact digital gain, and Auto
    // moves only the shutter. Out of light, the frame underexposes (getUnderexposureEv) unless
    // auto gain is on, which pushes the film up to +3 stops instead.
    private static final long FILM_MAX_AUTO_SHUTTER_NS = 66_666_667L;   // 1/15 s

    // Slowest speed Auto may pick in photo mode (video stays at 1/30 s). 1/15 s is safe since the
    // companion stream is drained; debug "autoslow" changes it for testing.
    private volatile long filmMaxAutoShutterNs = FILM_MAX_AUTO_SHUTTER_NS;
    // Debug "fpslock": request the slow AE fps range on every manual frame, not only on frames
    // longer than 1/30 s, so the range never toggles while Auto walks across 1/30.
    private volatile boolean slowFpsAlways = false;
    public void setFilmMaxAutoShutterNs(long ns) { filmMaxAutoShutterNs = Math.max(MAX_SHUTTER_NS, ns); }
    public void setSlowFpsAlways(boolean on) { slowFpsAlways = on; }

    private volatile boolean filmIsoMode = true;
    private volatile int pushStops = 0;
    private volatile boolean autoGain = false;
    private volatile float underexposureEv = 0.0f;
    private volatile int effectiveEi = 0;
    private float filmDigitalGain = 1.0f;
    private double filmIsoSeconds = 0.0;
    private String filmKey = "";
    private int cachedLook = -2;
    private int cachedBoxIso = 0;

    private volatile long manualShutterNs = 0;          // 0 = A (film-ISO auto)
    private volatile long deviceMaxShutterNs = MAX_SHUTTER_NS;
    private volatile float meterEv = 0.0f;
    private volatile float virtualFNumber = 2.0f;

    /**
     * Shutter dial. ns > 0 sets a manual speed (clamped to the sensor's range; preview frame
     * rate drops with long speeds); 0 returns to A (Auto moves the shutter at the film's ISO).
     */
    public void setManualShutterNs(long ns) { manualShutterNs = Math.max(0L, ns); }
    /** Video mode keeps the shutter at 1/30 s or faster so the take runs at 30 fps. */
    public void setVideoMode(boolean video) {
        boolean changed = (videoMode != video);
        videoMode = video;
        if (changed) requestVideoReapply();
    }

    // ---------------------------------------------------------------- Video format
    private static final double VIDEO_MIN_DIGITAL_GAIN = 0.25;   // exposure index floor = min ISO x this
    private static final double VIDEO_MAX_DIGITAL_GAIN = 2.0;    // exposure index ceiling = max ISO x this (the shutter opens first)
    private volatile int videoFps = 24;
    private volatile float videoAngleDeg = 180f;
    private volatile boolean videoReapply = false;

    /** Video frame rate and shutter angle. Only affects exposure while in video mode. */
    public void setVideoFormat(int fps, float shutterAngleDeg) {
        videoFps = Math.max(1, fps);
        videoAngleDeg = Math.max(1f, Math.min(360f, shutterAngleDeg));
        if (videoMode) requestVideoReapply();
    }

    private void requestVideoReapply() {
        videoReapply = true;
        if (cameraHandler != null) cameraHandler.post(new IsoTask(this));
    }

    private long videoFrameDurationNs() { return 1_000_000_000L / videoFps; }

    private long videoShutterNs() {
        long fd = videoFrameDurationNs();
        long t = (long) (videoAngleDeg / 360.0 * fd);
        long hi = Math.max(minShutterNs, Math.min(fd, deviceMaxShutterNs));
        return Math.max(minShutterNs, Math.min(hi, t));
    }

    /** [fps,fps] if the device lists it, else the narrowest range topping out at fps, else the default. */
    private Range<Integer> videoFpsRange() {
        Range<Integer> best = null;
        Range<Integer>[] rs = (characteristics != null)
            ? characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) : null;
        int fps = videoFps;
        if (rs != null) {
            for (Range<Integer> r : rs) {
                int lo = r.getLower().intValue(), hi = r.getUpper().intValue();
                if (lo == fps && hi == fps) return r;
                if (hi == fps && (best == null || hi - lo < best.getUpper().intValue() - best.getLower().intValue())) best = r;
            }
        }
        return best != null ? best : defaultFpsRange;
    }

    /** Slowest shutter the preview stream can run: 1/15 s for photos, one video frame for video. */
    private long slowestShutterNs() { return videoMode ? videoFrameDurationNs() : Math.max(maxShutterNs, slowestFrameNs); }
    public long getManualShutterNs() { return manualShutterNs; }
    /** Shutter speed actually in use, ns. */
    public long getCurrentShutterNs() { return currentExposureNs; }

    public int getCurrentIso() { return currentIso; }

    public int getManualRotationOffset() { return manualRotationOffset; }
    /** Longest speed the sensor accepts (for the dial's slow end / B). */
    public long getDeviceMaxShutterNs() { return deviceMaxShutterNs; }
    /** Light meter: + means over, - under, in stops, at the current shutter vs what the scene needs. */
    public float getMeterEv() { return meterEv; }
    /**
     * Aperture ring (virtual: the phone lenses are fixed-aperture). Drives depth of field and
     * lens character (vignette, glow, flare shape) in the renderer, never exposure.
     */
    public void setVirtualAperture(float fNumber) {
        virtualFNumber = Math.max(0.95f, Math.min(22.0f, fNumber));
        VulkanRenderer.nativeSetVirtualAperture(virtualFNumber);
    }
    public float getVirtualAperture() { return virtualFNumber; }

    public void setFilmIsoMode(boolean enabled) { filmIsoMode = enabled; }
    public boolean isFilmIsoMode() { return filmIsoMode; }
    public void setPushStops(int stops) { pushStops = Math.max(-2, Math.min(3, stops)); }
    public int getPushStops() { return pushStops; }
    public void setAutoGain(boolean enabled) { autoGain = enabled; }
    public boolean isAutoGain() { return autoGain; }
    /** Stops the current frame is underexposed by because the shutter hit its slowest auto speed. */
    public float getUnderexposureEv() { return underexposureEv; }
    /** Exposure index in use (box speed x push), 0 when the look has no film speed. */
    public int getEffectiveEi() { return effectiveEi; }

    private int activeBoxIso() {
        int look = VulkanRenderer.nativeGetActiveLookIndex();
        if (look != cachedLook) {
            cachedLook = look;
            cachedBoxIso = (look >= 0) ? VulkanRenderer.nativeGetLookBoxIso(look) : 0;
        }
        return cachedBoxIso;
    }

    private int nearestCleanIso(double ei) {
        int best = (isoLadder.length > 0) ? isoLadder[0] : deviceMinIso;
        double bestDist = Double.MAX_VALUE;
        for (int[] list : new int[][] {isoLadder, subSweetLadder}) {
            for (int iso : list) {
                double d = Math.abs(Math.log(iso / ei));
                if (d < bestDist - 1e-6 || (Math.abs(d - bestDist) <= 1e-6 && iso > best)) {
                    best = iso;
                    bestDist = d;
                }
            }
        }
        return best;
    }

    // isoSeconds: exposure the scene needs, as (exposure index x seconds).
    private void computeFilmExposure(int boxIso, double isoSeconds) {
        if (videoMode) { computeFilmExposureVideo(boxIso, isoSeconds); return; }
        int push = pushStops;
        double ei = boxIso * Math.pow(2.0, push);
        double tWanted = isoSeconds / ei;
        // Auto reaches 1/15 s in photo mode. The 17 Oct "1/15 wedges the HAL" hangs were the
        // undrained companion stream running out of buffers, not the long frame itself.
        double tMax = Math.min(filmMaxAutoShutterNs, slowestShutterNs()) * 1e-9;
        double under = (tWanted > tMax) ? Math.log(tWanted / tMax) / Math.log(2.0) : 0.0;
        if (autoGain && under > 0.05 && push < 3) {
            push = Math.min(3, push + (int) Math.ceil(under - 0.05));
            ei = boxIso * Math.pow(2.0, push);
            tWanted = isoSeconds / ei;
            under = (tWanted > tMax) ? Math.log(tWanted / tMax) / Math.log(2.0) : 0.0;
        }
        // Fastest shutter reached and still too bright (e.g. ISO 800 film in daylight): lower the
        // exposure index like an ND filter instead of blowing the frame out.
        double tFast = minShutterNs * 1e-9;
        if (manualShutterNs == 0 && manualIso == 0 && tWanted < tFast) {
            ei = Math.max(deviceMinIso * 0.25, isoSeconds / tFast);
            tWanted = isoSeconds / ei;
        }
        int sensorIso = manualIso > 0 ? clampIso(manualIso) : nearestCleanIso(ei);
        long t = (long) (Math.max(minShutterNs * 1e-9, Math.min(tMax, tWanted)) * 1e9);
        if (manualShutterNs == 0 && t > 0) {   // 1/3-stop shutter ladder
            t = (long) (Math.pow(2.0, Math.round(Math.log((double) t) / Math.log(2.0) * 3.0) / 3.0));
            t = Math.max(minShutterNs, Math.min((long) (tMax * 1e9), t));
        }
        long manual = manualShutterNs;
        if (manual > 0) {
            t = Math.max(minShutterNs, Math.min(Math.min(deviceMaxShutterNs, slowestShutterNs()), manual));
            under = 0.0;
        }
        meterEv = (float) (Math.log((t * 1e-9) / tWanted) / Math.log(2.0));
        currentIso = sensorIso;
        currentExposureNs = t;
        filmDigitalGain = (float) (ei / sensorIso);
        underexposureEv = (float) under;
        effectiveEi = (int) Math.round(ei);
        DngSaver.setExposureIndex(effectiveEi);
        VulkanRenderer.nativeSetFilmExposure(filmDigitalGain, push);
    }

    // Video: shutter fixed by the angle, the exposure index floats so ISO solves exposure.
    private void computeFilmExposureVideo(int boxIso, double isoSeconds) {
        int push = pushStops;
        double eiBox = boxIso * Math.pow(2.0, push);
        long t = videoShutterNs();
        if (manualIso == 0) {
            // Out of ISO range at the chosen angle: shorten the shutter when too bright, open it
            // (up to a full frame) when too dim, before leaning on digital gain.
            double atAngle = isoSeconds / (t * 1e-9);
            if (atAngle < deviceMinIso * VIDEO_MIN_DIGITAL_GAIN) {
                t = Math.max(minShutterNs, (long) (isoSeconds / (deviceMinIso * VIDEO_MIN_DIGITAL_GAIN) * 1e9));
            } else if (atAngle > deviceMaxIso) {
                long full = Math.min(videoFrameDurationNs(), deviceMaxShutterNs);
                t = Math.min(full, Math.max(t, (long) (isoSeconds / deviceMaxIso * 1e9)));
            }
        }
        double tSec = t * 1e-9;
        double need = isoSeconds / tSec;               // exposure index that meters correctly
        double ei;
        int sensorIso;
        double under = 0.0;
        if (manualIso > 0) {
            sensorIso = clampIso(manualIso);
            ei = eiBox;                                // look stays put; meterEv shows the error
        } else {
            double eiMin = deviceMinIso * VIDEO_MIN_DIGITAL_GAIN;
            double eiMax = deviceMaxIso * VIDEO_MAX_DIGITAL_GAIN;
            ei = Math.max(eiMin, Math.min(eiMax, need));
            if (need > eiMax) under = Math.log(need / eiMax) / Math.log(2.0);
            sensorIso = nearestCleanIso(ei);
        }
        double tWanted = isoSeconds / ei;
        meterEv = (float) (Math.log(tSec / tWanted) / Math.log(2.0));
        currentIso = sensorIso;
        currentExposureNs = t;
        filmDigitalGain = (float) (ei / sensorIso);
        underexposureEv = (float) under;
        effectiveEi = (int) Math.round(eiBox);
        DngSaver.setExposureIndex(effectiveEi);
        VulkanRenderer.nativeSetFilmExposure(filmDigitalGain, push);
    }

    private String filmKeyFor(int boxIso) {
        return boxIso + "/" + pushStops + "/" + autoGain + "/" + manualShutterNs + "/" + videoMode + "/" + manualIso
            + "/" + videoFps + "/" + videoAngleDeg;
    }

    private void updateFilmExposure(int boxIso, float mean) {
        // A new session (lens switch) starts on the HAL's own AE: the film exposure must be sent
        // again even when it equals the previous lens's, or the HAL keeps choosing its own ISO.
        boolean fresh = false;
        if (!manualAeActive) {
            // Meter once with the camera's AE, then hold ISO and drive the shutter ourselves.
            aeWarmupFrames++;
            boolean ready = (aeConverged && aeWarmupFrames > AE_WARMUP_MIN_FRAMES)
                || aeWarmupFrames >= AE_WARMUP_MAX_FRAMES;
            if (!ready || mean <= 1e-4f) return;
            aeTargetRawMean = mean;
            filmIsoSeconds = resultIso * (resultExposureNs * 1e-9);
            Log.i(TAG, "Meter lock (film): raw mean " + mean + " ISO " + resultIso + " / " + (resultExposureNs / 1000) + " us");
            manualAeActive = true;
            fresh = true;
        } else if (filmKey.isEmpty()) {
            // Coming from a digital look: keep the exposure it had settled on.
            filmIsoSeconds = currentIso * (currentExposureNs * 1e-9);
        } else {
            String key = filmKeyFor(boxIso);
            framesSinceAeApply++;
            double want = meterDesiredIsoSeconds(mean);   // every frame: keeps the smoothing current
            if (key.equals(filmKey)) {
                if (want < 0 || framesSinceAeApply < (videoMode ? 2 : 3)) return;
                double err = Math.log(want / filmIsoSeconds) / LN2;
                if (Math.abs(err) < (videoMode ? 0.06 : 0.12)) return;
                // Photos jump to the smoothed estimate; video eases in small steps so it never pumps.
                double step = videoMode ? Math.max(-0.12, Math.min(0.12, err)) : err;
                filmIsoSeconds *= Math.pow(2.0, step);
            }
        }
        // Never let the requested exposure run away while the shutter is pinned.
        double ei = boxIso * Math.pow(2.0, pushStops);
        filmIsoSeconds = Math.max(ei * minShutterNs * 1e-9 * 0.25,
            Math.min(ei * FILM_MAX_AUTO_SHUTTER_NS * 1e-9 * 256.0, filmIsoSeconds));
        filmKey = filmKeyFor(boxIso);
        int prevIso = currentIso;
        long prevExp = currentExposureNs;
        computeFilmExposure(boxIso, filmIsoSeconds);
        framesSinceAeApply = 0;
        boolean reapply = videoReapply;
        videoReapply = false;
        if (fresh || reapply || currentIso != prevIso || currentExposureNs != prevExp) applyManualExposure();
    }

    // Runs on the camera thread once per rendered RAW frame.
    private void updateAutoIso() {
        if (stackActive) return;       // a stack shot owns the exposure until it is merged
        if (isoLadder.length == 0 || captureSession == null || previewBuilder == null) return;
        float mean = VulkanRenderer.nativeGetLastRawMean();
        int boxIso = filmIsoMode ? activeBoxIso() : 0;
        if (boxIso > 0) {
            updateFilmExposure(boxIso, mean);
            return;
        }
        if (!filmKey.isEmpty()) {
            // Leaving a film look: back to the camera's own ISO ladder at the same exposure.
            filmKey = "";
            effectiveEi = 0;
            DngSaver.setExposureIndex(0);
            underexposureEv = 0.0f;
            VulkanRenderer.nativeSetFilmExposure(1.0f, 0.0f);
            if (manualAeActive) {
                chooseIsoAndShutter(filmIsoSeconds);
                applyManualExposure();
            }
        }
        if (!manualAeActive) {
            aeWarmupFrames++;
            boolean ready = (aeConverged && aeWarmupFrames > AE_WARMUP_MIN_FRAMES)
                || aeWarmupFrames >= AE_WARMUP_MAX_FRAMES;
            if (!ready || mean <= 1e-4f) return;
            aeTargetRawMean = mean;
            double isoSeconds = resultIso * (resultExposureNs * 1e-9);
            filmIsoSeconds = isoSeconds;
            chooseIsoAndShutter(isoSeconds);
            manualAeActive = true;
            framesSinceAeApply = 0;
            applyManualExposure();
            Log.i(TAG, "Auto-ISO locked target raw mean " + aeTargetRawMean + " from AE ISO " + resultIso
                + " / " + (resultExposureNs / 1000) + " us -> ISO " + currentIso + " / " + (currentExposureNs / 1000) + " us");
            return;
        }

        framesSinceAeApply++;
        double want = meterDesiredIsoSeconds(mean);
        if (want < 0 || framesSinceAeApply < (videoMode ? 2 : 3)) return;
        double cur = videoMode ? filmIsoSeconds : currentIso * (currentExposureNs * 1e-9);
        double err = Math.log(want / cur) / LN2;
        if (Math.abs(err) < (videoMode ? 0.06 : 0.12)) return;
        double step = videoMode ? Math.max(-0.12, Math.min(0.12, err)) : err;
        double isoSeconds = cur * Math.pow(2.0, step);
        if (videoMode) filmIsoSeconds = isoSeconds;
        int prevIso = currentIso;
        long prevExp = currentExposureNs;
        chooseIsoAndShutter(isoSeconds);
        framesSinceAeApply = 0;
        if (currentIso != prevIso || currentExposureNs != prevExp) {
            applyManualExposure();
        }
    }

    public synchronized void triggerCapture(String stockShortName, DngSaver.Callback callback) {
        this.pendingStockShortName = stockShortName;
        this.pendingDngCallback = callback;
        // Lock the grip now: the shutter tap itself can jolt the phone before the next frame lands.
        this.lockedCaptureOrient = currentActiveOrient();
        if (stackMode != STACK_OFF && !stackActive && (stackMode != STACK_HDR || manualAeActive)) {
            // Multi-frame shot: the frame listener collects the frames, then takes the normal capture path.
            this.stackBegun = false;
            this.stackActive = true;
            return;
        }
        this.captureRequested.set(true);
    }

    // ---------------------------------------------------------------- Burst / HDR / long-exposure stacking
    // Every mode reads the frames the existing RAW stream already delivers (no extra streams: the
    // Samsung HAL restarts the phone when a stream is left unread). HDR only moves the exposure of
    // the preview request, then puts it back.
    public static final int STACK_OFF = 0;
    public static final int STACK_BURST = 1;    // 8 frames, ghost-rejecting average (cleaner shadows)
    public static final int STACK_HDR = 2;      // base, -2 EV, +2 EV bracket merged in linear RAW
    public static final int STACK_LONG = 3;     // long exposure, mean of frames (water, crowds)
    public static final int STACK_TRAILS = 4;   // long exposure, brightest value per pixel (stars, lights)

    private static final int BURST_FRAMES = 8;
    private static final int MAX_STACK_FRAMES = 600;
    private static final int STACK_SETTLE_FRAMES = 5;
    private static final int STACK_TIMEOUT_FRAMES = 90;
    private static final long HDR_MAX_SHUTTER_NS = 125_000_000L;   // 1/8 s: the +2 EV frame stays hand-holdable on a rest
    private static final float HDR_STEP_UNDER = 0.25f;
    private static final float HDR_STEP_OVER = 4.0f;

    public interface StackStatus {
        void onStackStatus(String text);
        void onStackCancelled();
    }

    private volatile int stackMode = STACK_OFF;
    private volatile long longExposureMs = 4000L;
    private volatile StackStatus stackStatus;
    private volatile boolean stackActive = false;
    private boolean stackBegun = false;
    private int stackPhase = 0;
    private int stackFrames = 0;
    private int stackSettle = 0;
    private int stackWait = 0;
    private long stackStartNs = 0L;
    private long stackStatusNs = 0L;
    private int stackBaseIso = 100;
    private long stackBaseExpNs = 10_000_000L;
    private double stackBaseProd = 1.0;
    private int stackTargetIso = 100;
    private long stackTargetExpNs = 10_000_000L;

    public void setStackMode(int mode) { stackMode = Math.max(STACK_OFF, Math.min(STACK_TRAILS, mode)); }
    public int getStackMode() { return stackMode; }
    public void setLongExposureMs(long ms) { longExposureMs = Math.max(500L, Math.min(30_000L, ms)); }
    public long getLongExposureMs() { return longExposureMs; }
    public void setStackStatus(StackStatus listener) { stackStatus = listener; }
    public boolean isStackActive() { return stackActive; }

    private void postStack(String text) {
        StackStatus s = stackStatus;
        if (s != null) s.onStackStatus(text);
    }

    private void postStackThrottled(String text) {
        long now = System.nanoTime();
        if (now - stackStatusNs < 200_000_000L) return;
        stackStatusNs = now;
        postStack(text);
    }

    private static int nativeStackMode(int mode) {
        switch (mode) {
            case STACK_HDR: return 1;
            case STACK_LONG: return 2;
            case STACK_TRAILS: return 3;
            default: return 0;
        }
    }

    /** Puts the exposure back to what the shot started with (HDR moved it). */
    private void restoreBaseExposure() {
        if (stackMode == STACK_HDR && stackBegun
            && (currentIso != stackBaseIso || currentExposureNs != stackBaseExpNs)) {
            currentIso = stackBaseIso;
            currentExposureNs = stackBaseExpNs;
            applyManualExposure();
        }
    }

    /** Gives up on the stack: the frame in hand is taken as a normal single capture. */
    private boolean stackAbort(String why) {
        Log.w(TAG, "Stack aborted: " + why);
        restoreBaseExposure();
        VulkanRenderer.nativeStackEnd();
        stackActive = false;
        stackBegun = false;
        postStack("Stack failed, single frame");
        return true;
    }

    private void stackDone() {
        stackActive = false;
        stackBegun = false;
    }

    /** Exposure for a bracket step: shutter first, ISO makes up what the shutter range cannot. */
    private void planBracket(float factor) {
        long hi = Math.max(minShutterNs, Math.min(deviceMaxShutterNs, HDR_MAX_SHUTTER_NS));
        long t = Math.max(minShutterNs, Math.min(hi, (long) (stackBaseExpNs * (double) factor)));
        double left = (double) factor * stackBaseExpNs / (double) t;
        stackTargetExpNs = t;
        stackTargetIso = clampIso((int) Math.round(stackBaseIso * left));
    }

    private boolean resultMatches(int iso, long expNs) {
        long e = resultExposureNs;
        int i = resultIso;
        return Math.abs(e - expNs) <= expNs * 0.03 + 20_000L && Math.abs(i - iso) <= Math.max(2, iso / 33);
    }

    /**
     * Runs on the camera thread once per rendered RAW frame while a stack shot is active.
     * Returns true when the merge is in the ring and the frame in hand should be captured now.
     */
    private boolean stackStep() {
        long now = System.nanoTime();
        if (!stackBegun) {
            if (!VulkanRenderer.nativeStackBegin(nativeStackMode(stackMode))) {
                stackActive = false;
                return true;
            }
            stackBegun = true;
            stackPhase = 0;
            stackFrames = 0;
            stackSettle = 0;
            stackWait = 0;
            stackStartNs = now;
            stackBaseIso = currentIso;
            stackBaseExpNs = currentExposureNs;
            stackBaseProd = (double) resultIso * (double) resultExposureNs;
            if (stackBaseProd <= 0.0) stackBaseProd = (double) currentIso * currentExposureNs;
        }
        stackWait++;
        if (stackWait > MAX_STACK_FRAMES + STACK_TIMEOUT_FRAMES) return stackAbort("timeout");

        switch (stackMode) {
            case STACK_BURST:
                if (!VulkanRenderer.nativeStackAdd(1.0f)) return stackAbort("add failed");
                stackFrames++;
                postStack("Burst " + stackFrames + "/" + BURST_FRAMES);
                if (stackFrames >= BURST_FRAMES) return stackFinish();
                return false;

            case STACK_LONG:
            case STACK_TRAILS: {
                if (!VulkanRenderer.nativeStackAdd(1.0f)) return stackAbort("add failed");
                stackFrames++;
                long elapsedMs = (now - stackStartNs) / 1_000_000L;
                postStackThrottled(String.format(java.util.Locale.US, "Exposing %.1f / %.1f s",
                    elapsedMs / 1000.0, longExposureMs / 1000.0));
                if ((elapsedMs >= longExposureMs && stackFrames >= 2) || stackFrames >= MAX_STACK_FRAMES) return stackFinish();
                return false;
            }

            case STACK_HDR:
                return hdrStep();

            default:
                return stackAbort("bad mode");
        }
    }

    // Phases: 0 base frame, 1 under (-2 EV), 2 over (+2 EV), 3 back to base, then merge.
    private boolean hdrStep() {
        if (stackPhase == 0) {
            if (++stackSettle < 2) return false;           // one steady frame after the tap
            if (!VulkanRenderer.nativeStackAdd(1.0f)) return stackAbort("add failed");
            stackFrames++;
            postStack("HDR 1/3");
            planBracket(HDR_STEP_UNDER);
            stackPhase = 1;
            stackSettle = 0;
            stackWait = 0;
            currentIso = stackTargetIso;
            currentExposureNs = stackTargetExpNs;
            applyManualExposure();
            return false;
        }
        if (stackWait > STACK_TIMEOUT_FRAMES) return stackAbort("exposure never settled");
        stackSettle = resultMatches(stackTargetIso, stackTargetExpNs) ? stackSettle + 1 : 0;
        if (stackSettle < STACK_SETTLE_FRAMES) return false;

        double prod = (double) resultIso * (double) resultExposureNs;
        float rel = (float) (prod / stackBaseProd);
        if (!VulkanRenderer.nativeStackAdd(rel)) return stackAbort("add failed");
        stackFrames++;
        Log.i(TAG, "HDR frame " + stackFrames + " at " + String.format(java.util.Locale.US, "%+.2f EV", Math.log(rel) / Math.log(2.0))
            + " (ISO " + resultIso + ", " + (resultExposureNs / 1000) + " us)");
        stackSettle = 0;
        stackWait = 0;
        if (stackPhase == 1) {
            postStack("HDR 2/3");
            planBracket(HDR_STEP_OVER);
            stackPhase = 2;
        } else if (stackPhase == 2) {
            postStack("HDR 3/3");
            stackTargetIso = stackBaseIso;
            stackTargetExpNs = stackBaseExpNs;
            stackPhase = 3;
        } else {
            return stackFinish();
        }
        currentIso = stackTargetIso;
        currentExposureNs = stackTargetExpNs;
        applyManualExposure();
        return false;
    }

    private boolean stackFinish() {
        boolean ok = VulkanRenderer.nativeStackFinish();
        restoreBaseExposure();
        stackDone();
        if (!ok) {
            VulkanRenderer.nativeStackEnd();
            postStack("Stack failed, single frame");
        } else {
            postStack("Merged " + stackFrames + " frames");
        }
        return true;
    }

    private void closeCameraInternal() {
        if (stackActive) {
            stackActive = false;
            stackBegun = false;
            VulkanRenderer.nativeStackEnd();
            StackStatus s = stackStatus;
            if (s != null) s.onStackCancelled();
        }
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
        companionSurface = null;
        if (companionReader != null) {
            try { companionReader.close(); } catch (Exception ignored) {}
            companionReader = null;
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
            Log.e(TAG, "Camera device error " + error + " at ISO " + engine.currentIso + " / "
                + (engine.currentExposureNs / 1000) + " us");
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

                engine.previewBuilder = builder;
                if (engine.manualFocusDiopters >= 0f) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
                    builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, Math.min(engine.manualFocusDiopters, engine.getMinFocusDiopters()));
                }
                engine.defaultFpsRange = builder.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE);
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
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, android.hardware.camera2.CaptureFailure failure) {
            Log.w(TAG, "Capture failed frame " + failure.getFrameNumber() + " reason " + failure.getReason()
                + " at " + (engine.currentExposureNs / 1000) + " us");
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            engine.latestCaptureResult = result;

            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
            engine.aeConverged = aeState != null
                && (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED
                    || aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                    || aeState == CaptureResult.CONTROL_AE_STATE_LOCKED);
            Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
            Long exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (iso != null) engine.resultIso = iso;
            if (exp != null) engine.resultExposureNs = exp;
            Float focus = result.get(CaptureResult.LENS_FOCUS_DISTANCE);

            float noiseS = 0.0f;
            float noiseO = 0.0f;
            Pair<Double, Double>[] profile = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if (profile != null && profile.length > 0) {
                double s = 0.0;
                double o = 0.0;
                for (Pair<Double, Double> p : profile) {
                    s += p.first;
                    o += p.second;
                }
                noiseS = (float) (s / profile.length);
                noiseO = (float) (o / profile.length);
            }

            float black = engine.staticBlackLevel;
            float[] dyn = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
            if (dyn != null && dyn.length > 0) {
                float sum = 0.0f;
                for (float v : dyn) sum += v;
                black = sum / dyn.length;
            }

            VulkanRenderer.nativeSetCaptureState(
                engine.resultIso, engine.resultExposureNs,
                (focus != null) ? focus : 0.0f,
                noiseS, noiseO, black);

            // White balance from the RAW as-shot neutral (what the DNG records), so the gains and the
            // ForwardMatrix interpolation describe the same illuminant. COLOR_CORRECTION_GAINS is the
            // ISP's processed-output gain and only a fallback.
            DngColor color = engine.dngColor;
            float[] neutralRgb = null;
            if (!engine.autoSceneWb && color != null) {
                neutralRgb = color.daylightNeutral();
            } else {
                Rational[] neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
                if (neutral != null && neutral.length >= 3) {
                    neutralRgb = new float[] {
                        Math.max(neutral[0].floatValue(), 0.05f),
                        Math.max(neutral[1].floatValue(), 0.05f),
                        Math.max(neutral[2].floatValue(), 0.05f)};
                    if (color != null) neutralRgb = color.lockNeutralToLocus(neutralRgb);
                }
            }
            if (neutralRgb != null) {
                engine.dngGainR = neutralRgb[1] / neutralRgb[0];
                engine.dngGainG = 1.0f;
                engine.dngGainB = neutralRgb[1] / neutralRgb[2];
                if (color != null) {
                    VulkanRenderer.nativeSetForwardMatrix(engine.autoSceneWb
                        ? color.forwardMatrixForNeutral(neutralRgb)
                        : color.forwardMatrixForDaylight());
                }
            } else {
                RggbChannelVector gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS);
                if (gains != null) {
                    float g = Math.max((gains.getGreenEven() + gains.getGreenOdd()) * 0.5f, 0.05f);
                    engine.dngGainR = gains.getRed() / g;
                    engine.dngGainG = 1.0f;
                    engine.dngGainB = gains.getBlue() / g;
                }
            }
            // Residual lens-to-lens white offset measured on the same scene (Portra, grey cushion
            // and mid-tones): UW rendered ~6.5% low in B/G, wide ~3% low in R/G vs the tele.
            // Front has no same-scene reference yet, so it is left alone.
            engine.dngGainR *= LENS_TRIM_R[engine.camMatrixIdx];
            engine.dngGainB *= LENS_TRIM_B[engine.camMatrixIdx];
        }
    }

    /** Returns every companion frame straight away so the HAL never waits on that stream. */
    static class DrainListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            try {
                Image image = reader.acquireLatestImage();
                if (image != null) image.close();
            } catch (Exception ignored) {
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

            float gainR = engine.dngGainR * engine.userWbWarmth;
            float gainG = 1.0f * engine.userWbTint;
            float gainB = engine.dngGainB * (2.0f - engine.userWbWarmth);

            // Claim the capture before rendering so this frame is rendered (and the master taken)
            // in the grip locked at shutter press, not whatever the accelerometer reads now.
            boolean capture = engine.captureRequested.compareAndSet(true, false);
            int activeOrient = capture ? engine.lockedCaptureOrient : engine.currentActiveOrient();
            boolean stacked = false;
            // Shader orientation cases (rear sensor, raw is the scene turned 90 deg CCW):
            //   90 = scene upright for a portrait grip (full sensor), 270 = that turned 180,
            //   0 / 180 = raw un-rotated (3:4 crop), i.e. the scene turned 270 / 90 deg CW.
            // Camera and screen turn together with the phone, so the live view (drawn in the
            // screen frame; only the controls rotate with the grip) is always the upright
            // portrait render. The saved photo is that same render turned by the grip in the
            // JPEG step, which keeps the full 4080 x 3060 for landscape shots.
            int renderOrient = 90;
            // Front sensor is mounted 180 degrees from the rear ones (SENSOR_ORIENTATION 270 vs 90).
            if (engine.camMatrixIdx == 1) renderOrient = (renderOrient + 180) % 360;

            float vulkanMs = 0.0f;
            HardwareBuffer hb = image.getHardwareBuffer();
            if (hb != null) {
                try {
                    vulkanMs = VulkanRenderer.nativeRenderRaw(
                        hb, engine.rawWidth, engine.rawHeight, engine.rawStride,
                        engine.cfaCode, engine.whiteLevel,
                        gainR, gainG, gainB,
                        renderOrient, engine.camMatrixIdx
                    );
                } finally {
                    hb.close();
                }
            }

            if (!capture && engine.stackActive) {
                stacked = true;
                try {
                    capture = engine.stackStep();
                } catch (Throwable t) {
                    Log.e(TAG, "Stack step failed: " + t.getMessage(), t);
                    capture = engine.stackAbort("exception");
                }
                if (capture) activeOrient = engine.lockedCaptureOrient;
            }

            engine.updateAutoIso();

            if (capture) {
                Log.i(TAG, "Capture grip " + activeOrient + " (render " + renderOrient + ", ROT +" + engine.manualRotationOffset + ")");
                // Full-resolution master render of this exact frame (ring holds it plus the
                // previous burst frames), done before the RAW Image goes to the DNG writer.
                int[] masterDims = new int[2];
                int[] gainDims = new int[2];
                byte[] master = null;
                byte[] gain = null;
                long t0 = System.nanoTime();
                try {
                    master = VulkanRenderer.nativeRenderMasterStill(masterDims);
                    if (master != null) {
                        gain = VulkanRenderer.nativeGetMasterGainmap(gainDims);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Master still render failed: " + t.getMessage(), t);
                    master = null;
                    gain = null;
                }
                if (stacked) VulkanRenderer.nativeStackEnd();
                Log.i(TAG, "Master still " + masterDims[0] + "x" + masterDims[1] + " in "
                    + ((System.nanoTime() - t0) / 1_000_000) + " ms (ISO " + engine.resultIso
                    + ", " + (engine.resultExposureNs / 1000) + " us)");
                // Master render is portrait; a landscape grip (270 = +90 CW, 90 = -90) needs the JPEG turned upright.
                engine.dngSaver.setJpegRotation(activeOrient);
                engine.dngSaver.saveDngAndMasterAsync(
                    image, engine.characteristics, engine.latestCaptureResult,
                    engine.pendingStockShortName,
                    master, masterDims[0], masterDims[1],
                    gain, gainDims[0], gainDims[1],
                    engine.pendingDngCallback
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
