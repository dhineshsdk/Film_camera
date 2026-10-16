package com.s24film.probe;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String TAG = "S24Probe";
    private TextView statusTextView;
    private HandlerThread bgThread;
    private Handler bgHandler;

    private AtomicInteger gyroCount = new AtomicInteger(0);
    private AtomicInteger accelCount = new AtomicInteger(0);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusTextView = new TextView(this);
        statusTextView.setTextSize(16);
        statusTextView.setPadding(32, 48, 32, 48);
        statusTextView.setText("S24+ Phase 0 Probe Running...\nPackage: " + getPackageName() + "\nPlease wait...");
        setContentView(statusTextView);

        bgThread = new HandlerThread("ProbeWorker");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());

        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 100);
        } else {
            bgHandler.post(new ProbeTask(this));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            bgHandler.post(new ProbeTask(this));
        } else {
            statusTextView.setText("ERROR: CAMERA permission denied!");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
                || event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroCount.incrementAndGet();
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelCount.incrementAndGet();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    static class ProbeTask implements Runnable {
        private final MainActivity activity;

        ProbeTask(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void run() {
            activity.executeProbe();
        }
    }

    void executeProbe() {
        JSONObject root = new JSONObject();
        try {
            root.put("packageName", getPackageName());
            root.put("timestamp", System.currentTimeMillis());
            root.put("deviceModel", android.os.Build.MODEL);
            root.put("deviceProduct", android.os.Build.PRODUCT);
            root.put("deviceHardware", android.os.Build.HARDWARE);
            root.put("androidVersion", android.os.Build.VERSION.RELEASE);
            root.put("sdkInt", android.os.Build.VERSION.SDK_INT);

            // 1. High-Rate IMU Test
            Log.i(TAG, "Starting IMU Rate Test...");
            JSONObject imuResult = testHighRateIMU();
            root.put("imuTest", imuResult);

            // 2. Camera Enumeration & Characteristics
            CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            Log.i(TAG, "Enumerating cameras...");
            JSONArray camerasArray = new JSONArray();
            String[] cameraIds = cm.getCameraIdList();
            root.put("cameraIdList", new JSONArray(Arrays.asList(cameraIds)));

            for (String id : cameraIds) {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                JSONObject camJson = inspectCamera(id, cc);
                camerasArray.put(camJson);
            }
            root.put("cameras", camerasArray);

            // 3. Concurrent Camera Combinations
            Log.i(TAG, "Testing concurrent combinations...");
            JSONArray concurrentArray = new JSONArray();
            try {
                Set<Set<String>> concurrentCombinations = cm.getConcurrentCameraIds();
                for (Set<String> combo : concurrentCombinations) {
                    concurrentArray.put(new JSONArray(combo));
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error getting concurrentCameraIds: " + t.getMessage());
            }
            root.put("concurrentCameraCombinations", concurrentArray);

            // 4. Live Stream & Row-Stride Test for RAW cameras
            Log.i(TAG, "Testing Live RAW Capture & Row Stride...");
            JSONArray liveRawTests = testLiveRawStreams(cm, cameraIds);
            root.put("liveRawTests", liveRawTests);

            // Save report to disk
            File outFile = new File(getExternalFilesDir(null), "probe_result.json");
            try (FileWriter writer = new FileWriter(outFile)) {
                writer.write(root.toString(2));
            }
            Log.i(TAG, "PROBE SUCCESS! Output written to: " + outFile.getAbsolutePath());
            Log.i(TAG, "JSON_REPORT_BEGIN\n" + root.toString(2) + "\nJSON_REPORT_END");

            runOnUiThread(new UiUpdateTask(statusTextView, getPackageName(), cameraIds.length, outFile.getAbsolutePath()));

        } catch (Exception e) {
            Log.e(TAG, "Probe failed with exception", e);
            runOnUiThread(new ErrorUpdateTask(statusTextView, e.getMessage()));
        }
    }

    static class UiUpdateTask implements Runnable {
        private final TextView tv;
        private final String pkg;
        private final int camCount;
        private final String outPath;

        UiUpdateTask(TextView tv, String pkg, int camCount, String outPath) {
            this.tv = tv;
            this.pkg = pkg;
            this.camCount = camCount;
            this.outPath = outPath;
        }

        @Override
        public void run() {
            tv.setText("PROBE COMPLETED SUCCESSFULLY!\n\n"
                    + "Package: " + pkg + "\n"
                    + "Cameras Found: " + camCount + "\n"
                    + "Output File: " + outPath + "\n\n"
                    + "Check adb logcat -s S24Probe");
        }
    }

    static class ErrorUpdateTask implements Runnable {
        private final TextView tv;
        private final String msg;

        ErrorUpdateTask(TextView tv, String msg) {
            this.tv = tv;
            this.msg = msg;
        }

        @Override
        public void run() {
            tv.setText("PROBE FAILED: " + msg);
        }
    }

    private JSONObject testHighRateIMU() throws Exception {
        JSONObject imu = new JSONObject();
        SensorManager sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (gyro == null) gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        gyroCount.set(0);
        accelCount.set(0);

        if (gyro != null) {
            sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, bgHandler);
            imu.put("gyroVendor", gyro.getVendor());
            imu.put("gyroName", gyro.getName());
            imu.put("gyroMinDelayUs", gyro.getMinDelay());
        }
        if (accel != null) {
            sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, bgHandler);
            imu.put("accelVendor", accel.getVendor());
            imu.put("accelName", accel.getName());
            imu.put("accelMinDelayUs", accel.getMinDelay());
        }

        long start = SystemClock.elapsedRealtime();
        Thread.sleep(1000);
        long elapsed = SystemClock.elapsedRealtime() - start;

        sm.unregisterListener(this);

        double gyroHz = (gyroCount.get() * 1000.0) / elapsed;
        double accelHz = (accelCount.get() * 1000.0) / elapsed;

        imu.put("elapsedMs", elapsed);
        imu.put("gyroSamplesReceived", gyroCount.get());
        imu.put("gyroFrequencyHz", gyroHz);
        imu.put("accelSamplesReceived", accelCount.get());
        imu.put("accelFrequencyHz", accelHz);

        Log.i(TAG, "IMU Test Results -> Gyro: " + gyroHz + " Hz, Accel: " + accelHz + " Hz");
        return imu;
    }

    private JSONObject inspectCamera(String id, CameraCharacteristics cc) throws Exception {
        JSONObject cam = new JSONObject();
        cam.put("id", id);

        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        cam.put("facing", facing == null ? "UNKNOWN" : (facing == 0 ? "FRONT" : (facing == 1 ? "BACK" : "EXTERNAL")));

        float[] focalLengths = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focalLengths != null && focalLengths.length > 0) {
            cam.put("focalLength", focalLengths[0]);
        }

        Rect activeArray = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (activeArray != null) {
            cam.put("activeArraySize", activeArray.width() + "x" + activeArray.height());
            cam.put("activeArrayRect", "[" + activeArray.left + "," + activeArray.top + "," + activeArray.right + "," + activeArray.bottom + "]");
        }

        Size pixelArray = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (pixelArray != null) {
            cam.put("pixelArraySize", pixelArray.getWidth() + "x" + pixelArray.getHeight());
        }

        Integer cfa = cc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        String cfaName = "UNKNOWN";
        if (cfa != null) {
            switch (cfa) {
                case 0: cfaName = "RGGB"; break;
                case 1: cfaName = "GRBG"; break;
                case 2: cfaName = "GBRG"; break;
                case 3: cfaName = "BGGR"; break;
                case 4: cfaName = "RGB"; break;
                default: cfaName = "UNKNOWN_" + cfa; break;
            }
        }
        cam.put("cfaCode", cfa);
        cam.put("cfaName", cfaName);

        Integer whiteLevel = cc.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        cam.put("whiteLevel", whiteLevel);

        BlackLevelPattern blp = cc.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        if (blp != null) {
            int[] bl = new int[4];
            blp.copyTo(bl, 0);
            cam.put("blackLevelPattern", Arrays.toString(bl));
        }

        Range<Long> expRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (expRange != null) {
            cam.put("minExposureNs", expRange.getLower());
            cam.put("maxExposureNs", expRange.getUpper());
            cam.put("maxExposureMs", expRange.getUpper() / 1_000_000.0);
        }

        Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (isoRange != null) {
            cam.put("isoRange", isoRange.getLower() + " - " + isoRange.getUpper());
        }

        int[] caps = cc.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean hasRawCap = false;
        JSONArray capsArray = new JSONArray();
        if (caps != null) {
            for (int c : caps) {
                capsArray.put(c);
                if (c == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    hasRawCap = true;
                }
            }
        }
        cam.put("capabilities", capsArray);
        cam.put("hasRawCapability", hasRawCap);

        Set<String> physIds = cc.getPhysicalCameraIds();
        cam.put("physicalCameraIds", new JSONArray(physIds));

        // Stream formats & sizes
        StreamConfigurationMap scm = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        JSONObject formatsJson = new JSONObject();
        if (scm != null) {
            int[] testFormats = new int[]{
                    ImageFormat.RAW_SENSOR, // 32
                    ImageFormat.RAW10,      // 37
                    ImageFormat.RAW12,      // 38
                    ImageFormat.YCBCR_P010, // 54
                    ImageFormat.JPEG,       // 256
                    ImageFormat.YUV_420_888 // 35
            };

            for (int fmt : testFormats) {
                Size[] sizes = scm.getOutputSizes(fmt);
                if (sizes != null && sizes.length > 0) {
                    JSONArray sizeArr = new JSONArray();
                    for (Size s : sizes) {
                        JSONObject sObj = new JSONObject();
                        sObj.put("width", s.getWidth());
                        sObj.put("height", s.getHeight());
                        long minDur = scm.getOutputMinFrameDuration(fmt, s);
                        sObj.put("minFrameDurationNs", minDur);
                        if (minDur > 0) {
                            sObj.put("maxFps", 1_000_000_000.0 / minDur);
                        }
                        sizeArr.put(sObj);
                    }
                    String fmtName = getFormatName(fmt);
                    formatsJson.put(fmtName, sizeArr);
                }
            }
        }
        cam.put("streamConfigurations", formatsJson);

        return cam;
    }

    private JSONArray testLiveRawStreams(CameraManager cm, String[] cameraIds) {
        JSONArray tests = new JSONArray();

        for (String id : cameraIds) {
            try {
                CameraCharacteristics cc = cm.getCameraCharacteristics(id);
                StreamConfigurationMap scm = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (scm == null) continue;

                int targetFormat = -1;
                Size targetSize = null;

                int[] rawFormats = new int[]{ImageFormat.RAW_SENSOR, ImageFormat.RAW10, ImageFormat.RAW12};
                for (int fmt : rawFormats) {
                    Size[] sizes = scm.getOutputSizes(fmt);
                    if (sizes != null && sizes.length > 0) {
                        targetFormat = fmt;
                        targetSize = sizes[0];
                        break;
                    }
                }

                if (targetFormat == -1 || targetSize == null) {
                    continue;
                }

                JSONObject testObj = new JSONObject();
                testObj.put("cameraId", id);
                testObj.put("testedFormat", getFormatName(targetFormat));
                testObj.put("testedWidth", targetSize.getWidth());
                testObj.put("testedHeight", targetSize.getHeight());

                CountDownLatch frameLatch = new CountDownLatch(1);
                ImageReader reader = ImageReader.newInstance(targetSize.getWidth(), targetSize.getHeight(), targetFormat, 2);

                reader.setOnImageAvailableListener(new RawImageAvailableListener(testObj, targetSize.getWidth(), frameLatch), bgHandler);

                CountDownLatch sessionLatch = new CountDownLatch(1);
                CameraDeviceHolder holder = new CameraDeviceHolder();

                CameraOpenCallback openCallback = new CameraOpenCallback(holder, reader, sessionLatch, bgHandler);
                cm.openCamera(id, openCallback, bgHandler);

                boolean sessionReady = sessionLatch.await(3000, TimeUnit.MILLISECONDS);
                if (sessionReady && holder.session != null) {
                    CaptureRequest.Builder builder = holder.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    builder.addTarget(reader.getSurface());

                    holder.session.capture(builder.build(), new RawCaptureResultCallback(testObj), bgHandler);
                    frameLatch.await(3000, TimeUnit.MILLISECONDS);
                }

                if (holder.session != null) {
                    try { holder.session.close(); } catch (Exception ignored) {}
                }
                if (holder.device != null) {
                    try { holder.device.close(); } catch (Exception ignored) {}
                }
                reader.close();

                testObj.put("success", testObj.has("rowStride"));
                tests.put(testObj);

            } catch (Exception e) {
                Log.w(TAG, "Failed live raw test for cam " + id + ": " + e.getMessage());
            }
        }

        return tests;
    }

    static class CameraDeviceHolder {
        CameraDevice device;
        CameraCaptureSession session;
    }

    static class RawImageAvailableListener implements ImageReader.OnImageAvailableListener {
        private final JSONObject testObj;
        private final int width;
        private final CountDownLatch latch;

        RawImageAvailableListener(JSONObject testObj, int width, CountDownLatch latch) {
            this.testObj = testObj;
            this.width = width;
            this.latch = latch;
        }

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
                if (img != null) {
                    Image.Plane plane0 = img.getPlanes()[0];
                    int rowStride = plane0.getRowStride();
                    int pixelStride = plane0.getPixelStride();
                    testObj.put("rowStride", rowStride);
                    testObj.put("pixelStride", pixelStride);
                    testObj.put("rowStrideEqualsWidth", rowStride == width);
                    Log.i(TAG, "Captured 1 RAW frame! rowStride=" + rowStride + ", width=" + width);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error acquiring image", e);
            } finally {
                if (img != null) img.close();
                latch.countDown();
            }
        }
    }

    static class CameraOpenCallback extends CameraDevice.StateCallback {
        private final CameraDeviceHolder holder;
        private final ImageReader reader;
        private final CountDownLatch sessionLatch;
        private final Handler bgHandler;

        CameraOpenCallback(CameraDeviceHolder holder, ImageReader reader, CountDownLatch sessionLatch, Handler bgHandler) {
            this.holder = holder;
            this.reader = reader;
            this.sessionLatch = sessionLatch;
            this.bgHandler = bgHandler;
        }

        @Override
        public void onOpened(CameraDevice camera) {
            holder.device = camera;
            try {
                camera.createCaptureSession(Arrays.asList(reader.getSurface()), new SessionStateCallback(holder, sessionLatch), bgHandler);
            } catch (Exception e) {
                sessionLatch.countDown();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            sessionLatch.countDown();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            sessionLatch.countDown();
        }
    }

    static class SessionStateCallback extends CameraCaptureSession.StateCallback {
        private final CameraDeviceHolder holder;
        private final CountDownLatch sessionLatch;

        SessionStateCallback(CameraDeviceHolder holder, CountDownLatch sessionLatch) {
            this.holder = holder;
            this.sessionLatch = sessionLatch;
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            holder.session = session;
            sessionLatch.countDown();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            sessionLatch.countDown();
        }
    }

    static class RawCaptureResultCallback extends CameraCaptureSession.CaptureCallback {
        private final JSONObject testObj;

        RawCaptureResultCallback(JSONObject testObj) {
            this.testObj = testObj;
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Long skew = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (skew != null) {
                try {
                    testObj.put("rollingShutterSkewNs", skew);
                    testObj.put("rollingShutterSkewMs", skew / 1_000_000.0);
                } catch (Exception ignored) {}
            }
        }
    }

    private static String getFormatName(int fmt) {
        switch (fmt) {
            case ImageFormat.RAW_SENSOR: return "RAW_SENSOR (32)";
            case ImageFormat.RAW10: return "RAW10 (37)";
            case ImageFormat.RAW12: return "RAW12 (38)";
            case ImageFormat.YCBCR_P010: return "YCBCR_P010 (54)";
            case ImageFormat.JPEG: return "JPEG (256)";
            case ImageFormat.YUV_420_888: return "YUV_420_888 (35)";
            default: return "FORMAT_" + fmt;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (bgThread != null) {
            bgThread.quitSafely();
        }
    }
}
