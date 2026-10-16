package com.s24film.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        CameraEngine.FrameCallback,
        View.OnClickListener,
        DngSaver.Callback {

    private static final int PERMISSION_REQ_CODE = 1001;

    private static final String[] STOCK_NAMES = {
        "Phase 2: Linear ACEScg",
        "Kodak Portra 400 (C-41)",
        "Kodak Ektar 100 (C-41)",
        "Kodak Tri-X 400 (B&W)",
        "Vision3 500T + 2383",
        "CineStill 800T (Halation)",
        "Fujichrome Velvia 100",
        "Ilford HP5 Plus 400"
    };

    private static final String[] STOCK_FILE_TAGS = {
        "ACEScg",
        "Portra400",
        "Ektar100",
        "TriX400",
        "Vision3_500T",
        "CineStill800T",
        "Velvia100",
        "HP5_400"
    };

    private static final String[] LENS_NAMES = {
        "Clean",
        "Helios 44-2 Swirl",
        "Leica Summilux Glow",
        "Takumar Thorium",
        "Panavision 2x Anamorphic"
    };

    private SurfaceView surfaceView;
    private SurfaceHolder currentHolder;
    private TextView hudOverlay;
    private TextView tvShutterStatus;
    private Button btn06x, btn1x, btn3x, btnFront, btnShutter;
    private Button btnLens, btnBurst, btnWb;
    private final Button[] stockButtons = new Button[8];

    private ImuTelemetry imuTelemetry;
    private DngSaver dngSaver;
    private CameraEngine cameraEngine;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean vulkanInitialized = false;
    private long lastHudUpdateTimeMs = 0;
    private String currentCameraId = "0";

    private int activeStockIndex = 1; // Default: Kodak Portra 400
    private int activeLensIndex = 0;  // Default: Clean
    private boolean burstModeEnabled = true; // Default: 4-Frame Burst ON

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.camera_surface);
        hudOverlay = findViewById(R.id.hud_overlay);
        tvShutterStatus = findViewById(R.id.tv_shutter_status);
        btn06x = findViewById(R.id.btn_06x);
        btn1x = findViewById(R.id.btn_1x);
        btn3x = findViewById(R.id.btn_3x);
        btnFront = findViewById(R.id.btn_front);
        btnShutter = findViewById(R.id.btn_shutter);

        btnLens = findViewById(R.id.btn_lens);
        btnBurst = findViewById(R.id.btn_burst);
        btnWb = findViewById(R.id.btn_wb);

        int[] stockBtnIds = {
            R.id.btn_stock_0, R.id.btn_stock_1, R.id.btn_stock_2, R.id.btn_stock_3,
            R.id.btn_stock_4, R.id.btn_stock_5, R.id.btn_stock_6, R.id.btn_stock_7
        };
        for (int i = 0; i < 8; i++) {
            stockButtons[i] = findViewById(stockBtnIds[i]);
            stockButtons[i].setOnClickListener(this);
        }

        surfaceView.getHolder().addCallback(this);

        btn06x.setOnClickListener(this);
        btn1x.setOnClickListener(this);
        btn3x.setOnClickListener(this);
        btnFront.setOnClickListener(this);
        btnShutter.setOnClickListener(this);
        btnLens.setOnClickListener(this);
        btnBurst.setOnClickListener(this);
        btnWb.setOnClickListener(this);

        imuTelemetry = new ImuTelemetry(this);
        dngSaver = new DngSaver();
        cameraEngine = new CameraEngine(this, dngSaver, this);

        checkPermissions();
    }

    private void syncLookToNative() {
        VulkanRenderer.nativeSetFilmLook(activeStockIndex, activeLensIndex, burstModeEnabled);
        tvShutterStatus.setText(String.format(Locale.US,
            "%s | %s | %s",
            STOCK_NAMES[activeStockIndex],
            LENS_NAMES[activeLensIndex],
            burstModeEnabled ? "4F GAT Burst" : "Single Frame"));
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_06x) {
            switchCamera("2", "0.6x Ultrawide (Sony IMX564)");
        } else if (id == R.id.btn_1x) {
            switchCamera("0", "1x Wide (ISOCELL GN3)");
        } else if (id == R.id.btn_3x) {
            // Phase 0 Verified: Physical Camera "6" on Logical Camera "0" is the 3x Telephoto S5K3K1
            switchCamera("6", "3x Telephoto (ISOCELL S5K3K1 #6)");
        } else if (id == R.id.btn_front) {
            switchCamera("1", "Front Selfie (ISOCELL S5K3LU)");
        } else if (id == R.id.btn_shutter) {
            tvShutterStatus.setText("Saving DNG + " + STOCK_NAMES[activeStockIndex] + " JPG...");
            btnShutter.setEnabled(false);
            cameraEngine.triggerCapture(STOCK_FILE_TAGS[activeStockIndex], this);
        } else if (id == R.id.btn_lens) {
            activeLensIndex = (activeLensIndex + 1) % LENS_NAMES.length;
            btnLens.setText("Lens: " + LENS_NAMES[activeLensIndex]);
            syncLookToNative();
        } else if (id == R.id.btn_burst) {
            burstModeEnabled = !burstModeEnabled;
            btnBurst.setText(burstModeEnabled ? "Burst: 4F ON" : "Burst: 1F OFF");
            syncLookToNative();
        } else if (id == R.id.btn_wb) {
            boolean nextWb = !cameraEngine.isAutoSceneWb();
            cameraEngine.setAutoSceneWb(nextWb);
            btnWb.setText(nextWb ? "WB: Auto" : "WB: Film-True");
        } else {
            int[] stockBtnIds = {
                R.id.btn_stock_0, R.id.btn_stock_1, R.id.btn_stock_2, R.id.btn_stock_3,
                R.id.btn_stock_4, R.id.btn_stock_5, R.id.btn_stock_6, R.id.btn_stock_7
            };
            for (int i = 0; i < 8; i++) {
                if (id == stockBtnIds[i]) {
                    activeStockIndex = i;
                    syncLookToNative();
                    break;
                }
            }
        }
    }

    private void switchCamera(String camId, String label) {
        currentCameraId = camId;
        tvShutterStatus.setText("Switching to " + label + "...");
        cameraEngine.openCamera(camId);
    }

    private void checkPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                Manifest.permission.CAMERA,
                Manifest.permission.HIGH_SAMPLING_RATE_SENSORS
            }, PERMISSION_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (currentHolder != null && currentHolder.getSurface().isValid()) {
                    if (!vulkanInitialized) {
                        vulkanInitialized = VulkanRenderer.nativeInit(currentHolder.getSurface());
                        syncLookToNative();
                    }
                    if (vulkanInitialized) {
                        cameraEngine.openCamera(currentCameraId);
                    }
                }
            } else {
                Toast.makeText(this, "Camera permission is required!", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        imuTelemetry.start();
        hideSystemUI();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (currentHolder != null && currentHolder.getSurface().isValid()) {
                if (!vulkanInitialized) {
                    vulkanInitialized = VulkanRenderer.nativeInit(currentHolder.getSurface());
                    syncLookToNative();
                }
                if (vulkanInitialized) {
                    cameraEngine.openCamera(currentCameraId);
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraEngine.closeCamera();
        imuTelemetry.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraEngine.stop();
        dngSaver.shutdown();
        if (vulkanInitialized) {
            VulkanRenderer.nativeDestroy();
            vulkanInitialized = false;
        }
    }

    private void hideSystemUI() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        currentHolder = holder;
        boolean ok = VulkanRenderer.nativeInit(holder.getSurface());
        vulkanInitialized = ok;
        if (ok) {
            syncLookToNative();
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraEngine.openCamera(currentCameraId);
            }
        } else {
            hudOverlay.setText("CRITICAL: Failed to initialize Vulkan 1.3 pipeline on Xclipse 940!");
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        currentHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        cameraEngine.closeCamera();
        if (vulkanInitialized) {
            VulkanRenderer.nativeDestroy();
            vulkanInitialized = false;
        }
        currentHolder = null;
    }

    static class UpdateHudTask implements Runnable {
        private final TextView hudOverlay;
        private final String text;

        UpdateHudTask(TextView hudOverlay, String text) {
            this.hudOverlay = hudOverlay;
            this.text = text;
        }

        @Override
        public void run() {
            hudOverlay.setText(text);
        }
    }

    @Override
    public void onFrameRendered(final String camId, final int width, final int height,
                                final int strideBytes, final int cfa,
                                final double cameraFps, final float vulkanMs) {
        long now = System.currentTimeMillis();
        if (now - lastHudUpdateTimeMs < 120) return;
        lastHudUpdateTimeMs = now;

        final double gyroHz = imuTelemetry.getGyroHz();
        final float batteryTempC = getBatteryTemperature();
        final String cfaName = (cfa == 0) ? "RGGB" : (cfa == 1 ? "GRBG" : (cfa == 2 ? "GBRG" : "BGGR"));

        String hudText = String.format(Locale.US,
            "S24+ EXYNOS 2400 VULKAN 1.3 RAW DARKROOM (PHASE 2+3)\n" +
            "Cam: %s | %dx%d | CFA: %s | FPS: %.1f | GPU: %.2f ms\n" +
            "Stock: %s | Lens: %s\n" +
            "Burst: %s | WB: %s | Gyro: %.0f Hz | Temp: %.1f°C",
            camId, width, height, cfaName, cameraFps, vulkanMs,
            STOCK_NAMES[activeStockIndex], LENS_NAMES[activeLensIndex],
            burstModeEnabled ? "4-Frame GAT" : "1-Frame",
            cameraEngine.isAutoSceneWb() ? "Scene-Auto" : "Film-True",
            gyroHz, batteryTempC);

        mainHandler.post(new UpdateHudTask(hudOverlay, hudText));
    }

    @Override
    public void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
        btnShutter.setEnabled(true);
        long dngMb = dngBytes / (1024 * 1024);
        long jpgKb = jpgBytes / 1024;
        String msg = String.format(Locale.US, "Saved DNG (%dMB) + %s JPG (%dKB) in DCIM/S24Film",
                                   dngMb, STOCK_FILE_TAGS[activeStockIndex], jpgKb);
        tvShutterStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDngError(String message) {
        btnShutter.setEnabled(true);
        tvShutterStatus.setText("Capture Failed: " + message);
        Toast.makeText(this, "Capture Error: " + message, Toast.LENGTH_LONG).show();
    }

    private float getBatteryTemperature() {
        Intent intent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (intent != null) {
            int temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            return temp / 10.0f;
        }
        return 0.0f;
    }
}
