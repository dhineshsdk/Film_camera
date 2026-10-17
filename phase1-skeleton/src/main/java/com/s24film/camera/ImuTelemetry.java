package com.s24film.camera;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public class ImuTelemetry implements SensorEventListener {
    private static final String TAG = "S24FilmIMU";

    private final SensorManager sensorManager;
    private HandlerThread sensorThread;
    private Handler sensorHandler;

    private Sensor gyroSensor;
    private Sensor accelSensor;

    private final AtomicInteger gyroCounter = new AtomicInteger(0);
    private final AtomicInteger accelCounter = new AtomicInteger(0);

    private long lastCalculationTime = 0;
    private volatile double currentGyroHz = 0.0;
    private volatile double currentAccelHz = 0.0;
    private volatile int gravityOrientation = 0; // 0 = Upright Portrait default

    // Grip detection: low-passed gravity, ignored near flat, with a wide hysteresis band.
    private static final float GRAVITY_LP_ALPHA = 0.12f;      // ~20 ms time constant at 400 Hz
    private static final float MIN_PLANAR_GRAVITY = 3.0f;     // m/s2; below this (within ~18 deg of flat) keep the last grip
    private static final float SWITCH_ANGLE_DEG = 65.0f;      // leave a grip only past 45 + 20 deg
    private static final int SETTLE_SAMPLES = 8;              // samples to average before the first reading counts
    private final Context context;
    private float gravX = 0.0f, gravY = 9.81f;
    private int samplesSinceStart = 0;
    private boolean settled = false;

    // Phase 5: 500 Hz Gyroflow (.gcsv v1.3) Timed Metadata Logger
    private final Object logLock = new Object();
    private BufferedWriter gcsvWriter = null;
    private long logStartTimestampNs = 0;
    private int loggedGyroSamples = 0;
    private volatile float lastAx = 0.0f, lastAy = 9.81f, lastAz = 0.0f;

    public ImuTelemetry(Context context) {
        this.context = context;
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
        seedFromDisplay();
        sensorThread = new HandlerThread("IMU_500Hz_Thread", Thread.MAX_PRIORITY);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        if (gyroSensor == null) {
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        }
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        lastCalculationTime = SystemClock.elapsedRealtime();

        if (gyroSensor != null) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
        }
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
        }
    }

    public void stop() {
        stopGyroLog();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
    }

    public void startGyroLog(File gcsvFile) {
        synchronized (logLock) {
            try {
                if (gcsvWriter != null) {
                    gcsvWriter.close();
                }
                gcsvWriter = new BufferedWriter(new FileWriter(gcsvFile), 65536);
                // Write standard Gyroflow v1.3 .gcsv header for S24+ (Exynos 2400 LSM6DSVTR 500Hz)
                gcsvWriter.write("GYROFLOW IMU LOG\n");
                gcsvWriter.write("version,1.3\n");
                gcsvWriter.write("id,samsung_s24_plus_s5e9945\n");
                gcsvWriter.write("orientation,XYZ\n");
                gcsvWriter.write("note,S24Film_500Hz_LSM6DSVTR\n");
                gcsvWriter.write("fwversion,OneUI_8.5\n");
                gcsvWriter.write("tscale,0.000001\n");
                gcsvWriter.write("gscale,1.0\n");
                gcsvWriter.write("ascale,1.0\n");
                gcsvWriter.write("t,gx,gy,gz,ax,ay,az\n");
                logStartTimestampNs = 0;
                loggedGyroSamples = 0;
                Log.i(TAG, "Started 500Hz Gyroflow .gcsv track: " + gcsvFile.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "Failed to open .gcsv log: " + e.getMessage());
                gcsvWriter = null;
            }
        }
    }

    public int stopGyroLog() {
        synchronized (logLock) {
            int total = loggedGyroSamples;
            if (gcsvWriter != null) {
                try {
                    gcsvWriter.flush();
                    gcsvWriter.close();
                } catch (Exception ignored) {}
                gcsvWriter = null;
                Log.i(TAG, "Closed 500Hz Gyroflow .gcsv track (" + total + " samples written)");
            }
            return total;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtime();
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED || type == Sensor.TYPE_GYROSCOPE) {
            gyroCounter.incrementAndGet();
            synchronized (logLock) {
                if (gcsvWriter != null && event.values != null && event.values.length >= 3) {
                    if (logStartTimestampNs == 0) {
                        logStartTimestampNs = event.timestamp;
                    }
                    long relUs = (event.timestamp - logStartTimestampNs) / 1000L;
                    try {
                        gcsvWriter.write(String.format(Locale.US,
                            "%d,%.6f,%.6f,%.6f,%.4f,%.4f,%.4f\n",
                            relUs,
                            event.values[0], event.values[1], event.values[2],
                            lastAx, lastAy, lastAz));
                        loggedGyroSamples++;
                    } catch (Exception ignored) {}
                }
            }
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            accelCounter.incrementAndGet();
            if (event.values != null && event.values.length >= 3) {
                lastAx = event.values[0];
                lastAy = event.values[1];
                lastAz = event.values[2];
                updateGrip(lastAx, lastAy);
            }
        }

        long elapsed = now - lastCalculationTime;
        if (elapsed >= 1000) {
            int gCount = gyroCounter.getAndSet(0);
            int aCount = accelCounter.getAndSet(0);
            currentGyroHz = (gCount * 1000.0) / elapsed;
            currentAccelHz = (aCount * 1000.0) / elapsed;
            lastCalculationTime = now;
        }
    }

    // Starts each resume from the display's grip instead of whatever the last session ended on
    // (a landscape shot before leaving the app used to carry over into a portrait reopen).
    // The activity is locked to portrait, so this is normally 0.
    private void seedFromDisplay() {
        int displayRot = Surface.ROTATION_0;
        try {
            Display d = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                d = context.getDisplay();
            }
            if (d == null) {
                WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) d = wm.getDefaultDisplay();
            }
            if (d != null) displayRot = d.getRotation();
        } catch (Exception e) {
            Log.w(TAG, "Display rotation unavailable: " + e.getMessage());
        }
        // Same convention as the grip: device turned CCW (display ROTATION_90) reads ax > 0 = 270.
        int seed = (displayRot == Surface.ROTATION_90) ? 270
                 : (displayRot == Surface.ROTATION_180) ? 180
                 : (displayRot == Surface.ROTATION_270) ? 90 : 0;
        gravityOrientation = seed;
        double rad = Math.toRadians(-seed);
        gravX = (float) (9.81 * Math.sin(rad));
        gravY = (float) (9.81 * Math.cos(rad));
        samplesSinceStart = 0;
        settled = false;
        Log.i(TAG, "Grip seeded at " + seed + " (display rotation " + displayRot + ")");
    }

    // Runs on the sensor thread for every accelerometer sample.
    private void updateGrip(float ax, float ay) {
        gravX += GRAVITY_LP_ALPHA * (ax - gravX);
        gravY += GRAVITY_LP_ALPHA * (ay - gravY);
        if (samplesSinceStart < SETTLE_SAMPLES) {
            samplesSinceStart++;
            return;
        }
        float planar = (float) Math.hypot(gravX, gravY);
        if (planar < MIN_PLANAR_GRAVITY) return; // flat or pointing at floor/sky: keep the seed or last grip
        // 0 deg = top of phone up, +90 = left edge down (ax > 0).
        double theta = Math.toDegrees(Math.atan2(gravX, gravY));
        int nearest = (int) Math.round(theta / 90.0) * 90;
        int candidate = ((360 - nearest) % 360 + 360) % 360;
        int current = gravityOrientation;
        if (candidate == current) {
            settled = true;
            return;
        }
        if (!settled) {
            // First clear reading after a resume replaces the seed directly.
            gravityOrientation = candidate;
            settled = true;
            return;
        }
        double centre = (360 - current) % 360;
        double diff = Math.abs(((theta - centre) % 360 + 540) % 360 - 180);
        if (diff > SWITCH_ANGLE_DEG) {
            gravityOrientation = candidate;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public double getGyroHz() {
        return currentGyroHz;
    }

    public double getAccelHz() {
        return currentAccelHz;
    }

    public int getGravityOrientation() {
        return gravityOrientation;
    }
}
