package com.s24film.camera;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        CameraEngine.FrameCallback,
        View.OnClickListener,
        SeekBar.OnSeekBarChangeListener,
        DngSaver.Callback,
        CinemaVideoRecorder.Callback {

    private static final int PERMISSION_REQ_CODE = 1001;

    private static final String[] STOCK_NAMES = {
        "Linear ACEScg Ref",
        "Kodak Portra 400 (C-41)",
        "Kodak Ektar 100 (C-41)",
        "Kodak Tri-X 400 (B&W)",
        "Vision3 500T + 2383",
        "CineStill 800T (Halation)",
        "Fujichrome Velvia 100",
        "Ilford HP5 Plus 400",
        "Kodachrome 64 (K-14)",
        "Fuji Classic Neg (Superia)",
        "ARRI ALEXA 35 (LogC4)",
        "Kodak Aerochrome IR 1443"
    };

    private static final String[] STOCK_FILE_TAGS = {
        "ACEScg", "Portra400", "Ektar100", "TriX400", "Vision3_500T", "CineStill800T",
        "Velvia100", "HP5_400", "Kodachrome64", "FujiClassicNeg", "ArriLogC4", "AerochromeIR"
    };

    private static final String[] LENS_NAMES = {
        "Clean",
        "Helios 44-2 Swirl",
        "Leica Summilux Glow",
        "Takumar Thorium",
        "Panavision 2x Anamorphic"
    };

    private static final String[] ANAMORPHIC_LABELS = {
        "Desqueeze: 1.0x",
        "Desqueeze: 1.33x",
        "Desqueeze: 2.0x"
    };

    private static final String[] GATE_LABELS = {
        "Gate: 3:4",
        "Gate: 16:9",
        "Gate: 2.39:1"
    };

    private SurfaceView surfaceView;
    private SurfaceHolder currentHolder;
    private TextView hudOverlay;
    private TextView tvShutterStatus;
    private LinearLayout darkroomDrawer;
    private TextView tvEvLabel, tvGrainLabel, tvHalLabel;
    private SeekBar seekEv, seekGrain, seekHalation;
    private Button btn06x, btn1x, btn3x, btnFront, btnShutter, btnRecordVideo;
    private Button btnLens, btnAnamorphic, btnGate, btnStepPrint, btnBurst, btnWb, btnRotate, btnDarkroom;
    private final Button[] stockButtons = new Button[12];

    private ImuTelemetry imuTelemetry;
    private DngSaver dngSaver;
    private CinemaVideoRecorder videoRecorder;
    private CameraEngine cameraEngine;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean vulkanInitialized = false;
    private long lastHudUpdateTimeMs = 0;
    private String currentCameraId = "0";

    private int activeStockIndex = 1; // Default: Kodak Portra 400
    private int activeLensIndex = 0;  // Default: Clean
    private int anamorphicMode = 0;   // 0=1.0x, 1=1.33x, 2=2.0x
    private int cinemaGate = 0;       // 0=3:4, 1=16:9, 2=2.39:1
    private boolean stepPrint8fps = false;
    private boolean burstModeEnabled = true;
    private int rollFrameCounter = 1;
    private int magFeetRemaining = 400;
    private int magFramesRemaining = 0;

    private float userEvStops = 0.0f;
    private float userGrainMult = 1.0f;
    private float userHalationMult = 1.0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.camera_surface);
        hudOverlay = findViewById(R.id.hud_overlay);
        tvShutterStatus = findViewById(R.id.tv_shutter_status);
        darkroomDrawer = findViewById(R.id.darkroom_drawer);
        tvEvLabel = findViewById(R.id.tv_ev_label);
        tvGrainLabel = findViewById(R.id.tv_grain_label);
        tvHalLabel = findViewById(R.id.tv_hal_label);
        seekEv = findViewById(R.id.seek_ev);
        seekGrain = findViewById(R.id.seek_grain);
        seekHalation = findViewById(R.id.seek_halation);

        btn06x = findViewById(R.id.btn_06x);
        btn1x = findViewById(R.id.btn_1x);
        btn3x = findViewById(R.id.btn_3x);
        btnFront = findViewById(R.id.btn_front);
        btnShutter = findViewById(R.id.btn_shutter);
        btnRecordVideo = findViewById(R.id.btn_record_video);

        btnLens = findViewById(R.id.btn_lens);
        btnAnamorphic = findViewById(R.id.btn_anamorphic);
        btnGate = findViewById(R.id.btn_gate);
        btnStepPrint = findViewById(R.id.btn_step_print);
        btnBurst = findViewById(R.id.btn_burst);
        btnWb = findViewById(R.id.btn_wb);
        btnRotate = findViewById(R.id.btn_rotate);
        btnDarkroom = findViewById(R.id.btn_darkroom);

        int[] stockBtnIds = {
            R.id.btn_stock_0, R.id.btn_stock_1, R.id.btn_stock_2, R.id.btn_stock_3,
            R.id.btn_stock_4, R.id.btn_stock_5, R.id.btn_stock_6, R.id.btn_stock_7,
            R.id.btn_stock_8, R.id.btn_stock_9, R.id.btn_stock_10, R.id.btn_stock_11
        };
        for (int i = 0; i < 12; i++) {
            stockButtons[i] = findViewById(stockBtnIds[i]);
            stockButtons[i].setOnClickListener(this);
        }

        surfaceView.getHolder().addCallback(this);

        btn06x.setOnClickListener(this);
        btn1x.setOnClickListener(this);
        btn3x.setOnClickListener(this);
        btnFront.setOnClickListener(this);
        btnShutter.setOnClickListener(this);
        btnRecordVideo.setOnClickListener(this);
        btnLens.setOnClickListener(this);
        btnAnamorphic.setOnClickListener(this);
        btnGate.setOnClickListener(this);
        btnStepPrint.setOnClickListener(this);
        btnBurst.setOnClickListener(this);
        btnWb.setOnClickListener(this);
        btnRotate.setOnClickListener(this);
        btnDarkroom.setOnClickListener(this);

        seekEv.setOnSeekBarChangeListener(this);
        seekGrain.setOnSeekBarChangeListener(this);
        seekHalation.setOnSeekBarChangeListener(this);

        imuTelemetry = new ImuTelemetry(this);
        dngSaver = new DngSaver(this);
        videoRecorder = new CinemaVideoRecorder(this, imuTelemetry);
        cameraEngine = new CameraEngine(this, dngSaver, imuTelemetry, this);

        checkPermissions();
    }

    private void syncLookToNative() {
        boolean gateWeaveActive = (videoRecorder != null && videoRecorder.isRecording()) || stepPrint8fps;
        VulkanRenderer.nativeSetFilmLook(activeStockIndex, activeLensIndex, burstModeEnabled);
        VulkanRenderer.nativeSetDarkroomSliders(userEvStops, userGrainMult, userHalationMult);
        VulkanRenderer.nativeSetCinemaParams(anamorphicMode, cinemaGate, gateWeaveActive, stepPrint8fps);
        if (videoRecorder == null || !videoRecorder.isRecording()) {
            tvShutterStatus.setText(String.format(Locale.US,
                "ROLL [%02d/36] | MAG %03dFT | %s",
                rollFrameCounter, magFeetRemaining, STOCK_NAMES[activeStockIndex]));
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if (id == R.id.seek_ev) {
            userEvStops = (progress - 20) * 0.1f;
            tvEvLabel.setText(String.format(Locale.US, "EXPOSURE / PUSH-PULL: %+.1f EV", userEvStops));
        } else if (id == R.id.seek_grain) {
            userGrainMult = progress / 100.0f;
            tvGrainLabel.setText(String.format(Locale.US, "SILVER GRAIN INTENSITY: %d%%", progress));
        } else if (id == R.id.seek_halation) {
            userHalationMult = progress / 100.0f;
            tvHalLabel.setText(String.format(Locale.US, "TIR HALATION & BLOOM: %d%%", progress));
        }
        VulkanRenderer.nativeSetDarkroomSliders(userEvStops, userGrainMult, userHalationMult);
    }

    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override public void onStopTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_06x) {
            switchCamera("2", "0.6x Ultrawide (Sony IMX564)");
        } else if (id == R.id.btn_1x) {
            switchCamera("0", "1x Wide (ISOCELL GN3)");
        } else if (id == R.id.btn_3x) {
            switchCamera("6", "3x Telephoto (ISOCELL S5K3K1 #6)");
        } else if (id == R.id.btn_front) {
            switchCamera("1", "Front Selfie (ISOCELL S5K3LU)");
        } else if (id == R.id.btn_shutter) {
            tvShutterStatus.setText("Developing DNG + " + STOCK_NAMES[activeStockIndex] + "...");
            btnShutter.setEnabled(false);
            cameraEngine.triggerCapture(STOCK_FILE_TAGS[activeStockIndex], this);
        } else if (id == R.id.btn_record_video) {
            if (videoRecorder.isRecording()) {
                btnRecordVideo.setEnabled(false);
                tvShutterStatus.setText("Finalizing MP4 + 500Hz Gyroflow .gcsv...");
                videoRecorder.stopRecording();
            } else {
                videoRecorder.startRecording(STOCK_FILE_TAGS[activeStockIndex], 1440, 1920, this);
                syncLookToNative();
            }
        } else if (id == R.id.btn_lens) {
            activeLensIndex = (activeLensIndex + 1) % LENS_NAMES.length;
            btnLens.setText("Lens: " + LENS_NAMES[activeLensIndex]);
            syncLookToNative();
        } else if (id == R.id.btn_anamorphic) {
            anamorphicMode = (anamorphicMode + 1) % 3;
            btnAnamorphic.setText(ANAMORPHIC_LABELS[anamorphicMode]);
            syncLookToNative();
        } else if (id == R.id.btn_gate) {
            cinemaGate = (cinemaGate + 1) % 3;
            btnGate.setText(GATE_LABELS[cinemaGate]);
            syncLookToNative();
        } else if (id == R.id.btn_step_print) {
            stepPrint8fps = !stepPrint8fps;
            btnStepPrint.setText(stepPrint8fps ? "WKW 8fps Step" : "24fps Normal");
            syncLookToNative();
        } else if (id == R.id.btn_burst) {
            burstModeEnabled = !burstModeEnabled;
            btnBurst.setText(burstModeEnabled ? "4F Burst" : "1F Fast");
            syncLookToNative();
        } else if (id == R.id.btn_wb) {
            boolean nextWb = !cameraEngine.isAutoSceneWb();
            cameraEngine.setAutoSceneWb(nextWb);
            btnWb.setText(nextWb ? "WB: Auto" : "WB: Film");
        } else if (id == R.id.btn_rotate) {
            int offset = cameraEngine.cycleManualRotationOffset();
            btnRotate.setText("ROT +" + offset + "°");
        } else if (id == R.id.btn_darkroom) {
            boolean vis = (darkroomDrawer.getVisibility() == View.VISIBLE);
            darkroomDrawer.setVisibility(vis ? View.GONE : View.VISIBLE);
        } else {
            int[] stockBtnIds = {
                R.id.btn_stock_0, R.id.btn_stock_1, R.id.btn_stock_2, R.id.btn_stock_3,
                R.id.btn_stock_4, R.id.btn_stock_5, R.id.btn_stock_6, R.id.btn_stock_7,
                R.id.btn_stock_8, R.id.btn_stock_9, R.id.btn_stock_10, R.id.btn_stock_11
            };
            for (int i = 0; i < 12; i++) {
                if (id == stockBtnIds[i]) {
                    activeStockIndex = i;
                    syncLookToNative();
                    break;
                }
            }
        }
    }

    private void switchCamera(String camId, String label) {
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
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
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
        cameraEngine.closeCamera();
        imuTelemetry.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
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
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
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
                                final double cameraFps, final float vulkanMs,
                                final int activeOrient) {
        long now = System.currentTimeMillis();
        if (now - lastHudUpdateTimeMs < 120) return;
        lastHudUpdateTimeMs = now;

        final double gyroHz = imuTelemetry.getGyroHz();
        final float batteryTempC = getBatteryTemperature();
        final String cfaName = (cfa == 0) ? "RGGB" : (cfa == 1 ? "GRBG" : (cfa == 2 ? "GBRG" : "BGGR"));
        final boolean rec = (videoRecorder != null && videoRecorder.isRecording());

        String hudText = String.format(Locale.US,
            "S24+ EXYNOS 2400 VULKAN 1.3 CINEMA & DARKROOM (PHASE 5)%s\n" +
            "Cam: %s | %dx%d | CFA: %s | FPS: %.1f | GPU: %.2f ms\n" +
            "Stock: %s | Lens: %s | %s\n" +
            "Orient: %d° | EV: %+.1f | Gyro: %.0f Hz | Temp: %.1f°C",
            rec ? " [● REC]" : "",
            camId, width, height, cfaName, cameraFps, vulkanMs,
            STOCK_NAMES[activeStockIndex], LENS_NAMES[activeLensIndex],
            GATE_LABELS[cinemaGate],
            activeOrient, userEvStops, gyroHz, batteryTempC);

        mainHandler.post(new UpdateHudTask(hudOverlay, hudText));
    }

    // DngSaver.Callback
    @Override
    public void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
        btnShutter.setEnabled(true);
        rollFrameCounter = (rollFrameCounter % 36) + 1;
        long dngMb = dngBytes / (1024 * 1024);
        long jpgKb = jpgBytes / 1024;
        String msg = String.format(Locale.US, "ROLL [%02d/36] Saved DNG (%dMB) + %s JPG (%dKB)",
                                   rollFrameCounter, dngMb, STOCK_FILE_TAGS[activeStockIndex], jpgKb);
        tvShutterStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDngError(String message) {
        btnShutter.setEnabled(true);
        tvShutterStatus.setText("Capture Failed: " + message);
        Toast.makeText(this, "Capture Error: " + message, Toast.LENGTH_LONG).show();
    }

    // CinemaVideoRecorder.Callback
    @Override
    public void onRecordingStarted(String mp4Path) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setText("■ STOP REC");
        btnRecordVideo.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#B71C1C")));
        tvShutterStatus.setText("● REC 48Mbps HEVC + 500Hz Gyro (.gcsv)...");
    }

    @Override
    public void onRecordingProgress(int elapsedSec, int feetRemaining, int framesRemaining) {
        this.magFeetRemaining = feetRemaining;
        this.magFramesRemaining = framesRemaining;
        int min = elapsedSec / 60;
        int sec = elapsedSec % 60;
        tvShutterStatus.setText(String.format(Locale.US,
            "● REC %02d:%02d | MAG: %03d FT %02d FR | %s",
            min, sec, feetRemaining, framesRemaining, STOCK_NAMES[activeStockIndex]));
    }

    @Override
    public void onRecordingSaved(String mp4Path, String gcsvPath, long mp4Bytes, int gyroSamples) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setText("REC FILM VIDEO");
        btnRecordVideo.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#00897B")));
        syncLookToNative();
        double mb = mp4Bytes / (1024.0 * 1024.0);
        String msg = String.format(Locale.US, "Saved Video (%.1fMB) + %d Gyro Samples (.gcsv)", mb, gyroSamples);
        tvShutterStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRecordingError(String error) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setText("REC FILM VIDEO");
        btnRecordVideo.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#00897B")));
        tvShutterStatus.setText("Video Error: " + error);
        Toast.makeText(this, "Video Error: " + error, Toast.LENGTH_LONG).show();
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
