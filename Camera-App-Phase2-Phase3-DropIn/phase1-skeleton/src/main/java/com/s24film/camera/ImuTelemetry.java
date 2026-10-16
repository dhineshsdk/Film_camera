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

    public ImuTelemetry(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
    }

    public void start() {
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
            Log.i(TAG, "Registered Gyro: " + gyroSensor.getName() + " (" + gyroSensor.getVendor() + ")");
        }
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
            Log.i(TAG, "Registered Accel: " + accelSensor.getName() + " (" + accelSensor.getVendor() + ")");
        }
    }

    public void stop() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = SystemClock.elapsedRealtime();
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE_UNCALIBRATED ||
            event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroCounter.incrementAndGet();
        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accelCounter.incrementAndGet();
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

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public double getGyroHz() {
        return currentGyroHz;
    }

    public double getAccelHz() {
        return currentAccelHz;
    }
}
