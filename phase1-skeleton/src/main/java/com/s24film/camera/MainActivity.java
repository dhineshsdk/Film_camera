package com.s24film.camera;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.RelativeLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

public class MainActivity extends Activity implements
        SurfaceHolder.Callback,
        CameraEngine.FrameCallback,
        View.OnClickListener,
        View.OnLongClickListener,
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

    // Stock button -> kMasterLooks120 index (-1 = legacy GeneratedFilmDatabase path).
    private static final int[] STOCK_TO_LOOK = {
        -1,   // Linear ACEScg Ref
        24,   // kodak_portra_400
        19,   // kodak_ektar_100
        87,   // kodak_trix_400
        40,   // kodak_vision3_500t
        40,   // CineStill 800T = Vision3 500T base without remjet (halation x3)
        52,   // fujifilm_velvia_100
        76,   // ilford_hp5
        62,   // kodak_kodachrome_64
        110,  // fuji_sim_classic_neg
        97,   // cinema_arri_alexa35_logc4
        -1    // Aerochrome IR (no 120-look entry)
    };
    private static final int CINESTILL_STOCK_INDEX = 5;
    private static final float CINESTILL_HALATION_BOOST = 3.0f;

    private static final String[] LENS_NAMES = {
        "Clean",
        "Helios 44-2 Swirl",
        "Leica Summilux Glow",
        "Takumar Thorium",
        "Panavision 2x Anamorphic",
        "Super 70mm IMAX 80mm f/2"
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
    private HorizontalScrollView stripStocks;
    private final Button[] formatButtons = new Button[4];
    private Button btnSaveRaw;
    private TextView modePhoto, modeVideo;
    private FilmUi filmUi;
    private boolean testRunning = false;
    private int testStep = 0;
    private int testWaitToken = 0;
    private static final String[] TEST_CAMS = {"2", "0", "6", "1"};
    private static final String[] TEST_CAM_TAGS = {"UW", "Wide", "Tele", "Front"};
    private static final int[] TEST_STOCKS = {0, 1, 2, 3, 4, 6, 9};
    private FilmFx fx;
    private int uiTheme = 0;          // 0 = modern, 1 = black SLR, 2 = silver rangefinder
    private boolean cocked = true;    // shutter cocked by the wind lever
    private boolean motorDrive = false;
    private final Button[] themeButtons = new Button[3];
    private View topBar, bottomBar;
    private FrameLayout filmRoot;
    private static final long[] SPEED_NS = {0L, 1000000L, 2000000L, 4000000L, 8000000L, 16666667L, 33333333L,
        66666667L, 125000000L, 250000000L, 500000000L, 1000000000L, -1L};
    private final Button[] pushButtons = new Button[6];   // -2 .. +3 stops
    private Button btnFilmIso, btnAutoGain, btnPushFix;
    private View warnBar;
    private TextView tvWarning;
    private float lastUnderEv = 0f;
    private ImageView btnGallery;
    private boolean videoMode = false;
    private float modeTouchDownX = 0f;
    private int filmFormat = 0;   // 0=35mm, 1=16mm, 2=Super 8, 3=6x7
    private int uiRotationDeg = 0;   // current on-screen rotation of the rotating controls
    private View[] rotatingViews;

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
    private int pickedLookIndex = -1; // long-press picker override over all 120 looks (-1 = none)
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
            stockButtons[i].setOnLongClickListener(this);
        }

        stripStocks = findViewById(R.id.strip_stocks);
        int[] fmtIds = {R.id.btn_fmt_0, R.id.btn_fmt_1, R.id.btn_fmt_2, R.id.btn_fmt_3};
        for (int i = 0; i < 4; i++) {
            formatButtons[i] = findViewById(fmtIds[i]);
            formatButtons[i].setOnClickListener(this);
        }
        btnSaveRaw = findViewById(R.id.btn_save_raw);
        int[] pushIds = {R.id.btn_pushm2, R.id.btn_pushm1, R.id.btn_push0,
                         R.id.btn_push1, R.id.btn_push2, R.id.btn_push3};
        for (int i = 0; i < 6; i++) {
            pushButtons[i] = findViewById(pushIds[i]);
            pushButtons[i].setOnClickListener(this);
        }
        btnFilmIso = findViewById(R.id.btn_film_iso);
        btnAutoGain = findViewById(R.id.btn_auto_gain);
        btnPushFix = findViewById(R.id.btn_push_fix);
        warnBar = findViewById(R.id.warn_bar);
        tvWarning = findViewById(R.id.tv_warning);
        btnFilmIso.setOnClickListener(this);
        btnAutoGain.setOnClickListener(this);
        btnPushFix.setOnClickListener(this);
        findViewById(R.id.btn_test_series).setOnClickListener(this);
        modePhoto = findViewById(R.id.mode_photo);
        modeVideo = findViewById(R.id.mode_video);
        btnGallery = findViewById(R.id.btn_gallery);
        modePhoto.setOnClickListener(this);
        modeVideo.setOnClickListener(this);
        btnGallery.setOnClickListener(this);
        findViewById(R.id.mode_strip).setOnTouchListener(new ModeSwipeListener(this));
        btnSaveRaw.setOnClickListener(this);
        rotatingViews = new View[] {
            btn06x, btn1x, btn3x, btnFront, btnShutter, btnRecordVideo, btnDarkroom,
            btnGallery, modePhoto, modeVideo
        };
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

        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        filmRoot = findViewById(R.id.film_root);
        int[] themeIds = {R.id.btn_theme_0, R.id.btn_theme_1, R.id.btn_theme_2};
        for (int i = 0; i < 3; i++) {
            themeButtons[i] = findViewById(themeIds[i]);
            themeButtons[i].setOnClickListener(this);
        }
        fx = new FilmFx(this);
        filmUi = new FilmUi(this, filmRoot, new FilmCallbacks(this), fx);
        uiTheme = getSharedPreferences("ui", 0).getInt("theme", 0);
        setMode(false);
        applyTheme(uiTheme);
        loadLatestThumbnail();
        checkPermissions();
    }

    private int activeLookIndex() {
        return (pickedLookIndex >= 0) ? pickedLookIndex : STOCK_TO_LOOK[activeStockIndex];
    }

    private float effectiveHalationMult() {
        boolean cineStill = (pickedLookIndex < 0 && activeStockIndex == CINESTILL_STOCK_INDEX);
        return cineStill ? userHalationMult * CINESTILL_HALATION_BOOST : userHalationMult;
    }

    private String activeLookLabel() {
        if (pickedLookIndex >= 0) {
            String name = VulkanRenderer.nativeGetLookName(pickedLookIndex);
            return (name != null && !name.isEmpty()) ? name : ("Look " + pickedLookIndex);
        }
        return STOCK_NAMES[activeStockIndex];
    }

    // File tag for IMG_<time>_<LookName>.jpg
    private String activeFileTag() {
        if (pickedLookIndex >= 0) {
            String tag = VulkanRenderer.nativeGetLookTag(pickedLookIndex);
            if (tag == null || tag.isEmpty()) tag = "Look" + pickedLookIndex;
            return tag.replaceAll("[^A-Za-z0-9_\\-]", "_");
        }
        return STOCK_FILE_TAGS[activeStockIndex];
    }

    private void syncLookToNative() {
        boolean gateWeaveActive = (videoRecorder != null && videoRecorder.isRecording()) || stepPrint8fps;
        VulkanRenderer.nativeSetFilmLook(activeStockIndex, activeLensIndex, burstModeEnabled);
        VulkanRenderer.nativeSetMasterLook(activeLookIndex());
        VulkanRenderer.nativeSetFilmFormat(filmFormat);
        VulkanRenderer.nativeSetDarkroomSliders(userEvStops, userGrainMult, effectiveHalationMult());
        VulkanRenderer.nativeSetCinemaParams(anamorphicMode, cinemaGate, gateWeaveActive, stepPrint8fps);
        refreshSelection();
        if (videoRecorder == null || !videoRecorder.isRecording()) {
            tvShutterStatus.setText(String.format(Locale.US,
                "%s\n%02d/36 \u00b7 %03d ft",
                activeLookLabel(), rollFrameCounter, magFeetRemaining));
        }
    }

    // Highlights the active film chip and the active lens button.
    private void refreshSelection() {
        for (int i = 0; i < stockButtons.length; i++) {
            stockButtons[i].setSelected(pickedLookIndex < 0 && i == activeStockIndex);
        }
        for (int i = 0; i < formatButtons.length; i++) {
            formatButtons[i].setSelected(i == filmFormat);
        }
        if (cameraEngine != null) {
            for (int i = 0; i < pushButtons.length; i++) {
                pushButtons[i].setSelected(cameraEngine.getPushStops() == i - 2);
            }
            btnFilmIso.setSelected(cameraEngine.isFilmIsoMode());
            btnFilmIso.setText(cameraEngine.isFilmIsoMode() ? "Film ISO: On" : "Film ISO: Off");
            btnAutoGain.setSelected(cameraEngine.isAutoGain());
            btnAutoGain.setText(cameraEngine.isAutoGain() ? "Auto Gain: On" : "Auto Gain: Off");
        }
        boolean saveRaw = DngSaver.isSaveDng();
        btnSaveRaw.setSelected(saveRaw);
        btnSaveRaw.setText(saveRaw ? "Save RAW: On" : "Save RAW: Off");
        if (filmUi != null) {
            int li = "2".equals(currentCameraId) ? 0 : ("0".equals(currentCameraId) ? 1
                : ("6".equals(currentCameraId) ? 2 : 3));
            filmUi.setActiveLens(li);
            filmUi.setCanister(activeStockIndex, activeLookLabel());
            filmUi.setMovie(videoMode);
        }
        btn06x.setSelected("2".equals(currentCameraId));
        btn1x.setSelected("0".equals(currentCameraId));
        btn3x.setSelected("6".equals(currentCameraId));
        btnFront.setSelected("1".equals(currentCameraId));
    }

    // Rotates the compact controls and the info pills so they read upright in a landscape grip.
    private void applyUiRotation() {
        int base = imuTelemetry.getGravityOrientation();
        int target = (base == 270) ? 90 : (base == 90 ? -90 : (base == 180 ? 180 : 0));
        if (target == uiRotationDeg) return;
        uiRotationDeg = target;
        for (View v : rotatingViews) {
            v.animate().rotation(target).setDuration(220).start();
        }
        float density = getResources().getDisplayMetrics().density;
        boolean sideways = (target == 90 || target == -90);
        for (Button b : stockButtons) {
            b.animate().rotation(target).setDuration(220).start();
        }
        ViewGroup.LayoutParams lp = stripStocks.getLayoutParams();
        lp.height = (int) ((sideways ? 104 : 40) * density);
        stripStocks.setLayoutParams(lp);
        pinPill(tvShutterStatus, target, true);
        pinPill(hudOverlay, target, false);
    }

    // Low-light banner: shows when the film-exposure logic is underexposing.
    private void updateExposureUi() {
        refreshFilmData();
        boolean show = lastUnderEv > 0.2f;
        if (show) {
            tvWarning.setText(String.format(Locale.US,
                "Not enough light, underexposing by %.1f stops", lastUnderEv));
        }
        int vis = show ? View.VISIBLE : View.GONE;
        if (warnBar.getVisibility() != vis) warnBar.setVisibility(vis);
    }

    // Rotates a pill about its centre, then shifts it so the pinned corner stays on screen.
    private void pinPill(TextView pill, int deg, boolean left) {
        pill.animate().rotation(deg).setDuration(220).start();
        pill.post(new PinPillTask(pill, deg, left));
    }

    static class PinPillTask implements Runnable {
        private final TextView pill;
        private final int deg;
        private final boolean left;

        PinPillTask(TextView pill, int deg, boolean left) {
            this.pill = pill;
            this.deg = deg;
            this.left = left;
        }

        @Override
        public void run() {
            float shift = (pill.getWidth() - pill.getHeight()) / 2.0f;
            boolean sideways = (deg == 90 || deg == -90);
            pill.animate().translationX(sideways ? (left ? -shift : shift) : 0f)
                .translationY(sideways ? shift : 0f).setDuration(220).start();
        }
    }

    // Test series: every lens x a set of film stocks, one photo each, files tagged <Lens>_<Stock>.
    private void startTestSeries() {
        if (testRunning) return;
        if (videoRecorder != null && videoRecorder.isRecording()) return;
        setMode(false);
        testRunning = true;
        testStep = 0;
        Toast.makeText(this, "Test series started: 4 lenses x " + TEST_STOCKS.length + " stocks", Toast.LENGTH_LONG).show();
        mainHandler.post(new TestTask(this, 0, 0));
    }

    private void testAdvance() {
        int total = TEST_CAMS.length * TEST_STOCKS.length;
        if (testStep >= total) {
            testRunning = false;
            tvShutterStatus.setText("Test series done");
            Toast.makeText(this, "Test series done: " + total + " photos in DCIM/S24Film", Toast.LENGTH_LONG).show();
            return;
        }
        int cam = testStep / TEST_STOCKS.length;
        int st = testStep % TEST_STOCKS.length;
        if (st == 0 && !TEST_CAMS[cam].equals(currentCameraId)) {
            switchCamera(TEST_CAMS[cam], TEST_CAM_TAGS[cam]);
            mainHandler.postDelayed(new TestTask(this, 0, 0), 3500);
            return;
        }
        activeStockIndex = TEST_STOCKS[st];
        pickedLookIndex = -1;
        syncLookToNative();
        tvShutterStatus.setText("Test " + (testStep + 1) + "/" + total + "  " + TEST_CAM_TAGS[cam] + " " + STOCK_FILE_TAGS[activeStockIndex]);
        mainHandler.postDelayed(new TestTask(this, 1, 0), 800);
    }

    private void testShoot() {
        int cam = testStep / TEST_STOCKS.length;
        testWaitToken++;
        btnShutter.setEnabled(false);
        cameraEngine.triggerCapture(TEST_CAM_TAGS[cam] + "_" + activeFileTag(), this);
        testStep++;
        mainHandler.postDelayed(new TestTask(this, 2, testWaitToken), 9000);
    }

    private void testShotFinished() {
        if (!testRunning) return;
        testWaitToken++;
        mainHandler.postDelayed(new TestTask(this, 0, 0), 500);
    }

    static class TestTask implements Runnable {
        private final MainActivity a;
        private final int kind, token;

        TestTask(MainActivity a, int kind, int token) {
            this.a = a;
            this.kind = kind;
            this.token = token;
        }

        @Override
        public void run() {
            if (!a.testRunning) return;
            if (kind == 0) a.testAdvance();
            else if (kind == 1) a.testShoot();
            else if (token == a.testWaitToken) {   // capture never reported back: move on
                a.btnShutter.setEnabled(true);
                a.testAdvance();
            }
        }
    }

    private int dpToPx(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // 0 = modern, 1 = black SLR, 2 = silver rangefinder.
    private void applyTheme(int t) {
        uiTheme = t;
        getSharedPreferences("ui", 0).edit().putInt("theme", t).apply();
        boolean film = (t != 0);
        topBar.setVisibility(film ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(film ? View.GONE : View.VISIBLE);
        filmRoot.setVisibility(film ? View.VISIBLE : View.GONE);
        if (film) filmUi.setTheme(t == 1 ? FilmUi.SLR : FilmUi.RF);
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) darkroomDrawer.getLayoutParams();
        lp.bottomMargin = dpToPx(film ? 170 : 300);
        darkroomDrawer.setLayoutParams(lp);
        warnBar.setTranslationY(film ? dpToPx(54) : 0f);
        for (int i = 0; i < 3; i++) themeButtons[i].setSelected(i == t);
        refreshSelection();
        refreshFilmData();
    }

    private static String fmtSpeed(long ns) {
        if (ns <= 0) return "--";
        if (ns >= 950000000L) return String.format(Locale.US, "%.0f\"", ns / 1e9);
        return "1/" + Math.round(1e9 / ns);
    }

    private void refreshFilmData() {
        if (filmUi == null || cameraEngine == null) return;
        int si = filmUi.speedIndex();
        String speed = (si == 0) ? ("A " + fmtSpeed(cameraEngine.getCurrentShutterNs())) : FilmUi.SPEED_SHOWN[si];
        int ei = cameraEngine.getEffectiveEi();
        filmUi.setData(speed, FilmUi.APERTURE_LABELS[filmUi.apertureIndex()], ei > 0 ? ("EI " + ei) : "",
            rollFrameCounter, cocked, cameraEngine.getMeterEv());
    }

    // The release: blocks until the lever is wound, then fires the whole mechanical sequence.
    private void filmRelease() {
        if (videoMode) {
            fx.click();
            btnRecordVideo.performClick();
            return;
        }
        if (!cocked) {
            fx.dead();
            filmUi.flashNeedWind();
            return;
        }
        if (!btnShutter.isEnabled()) return;
        int si = filmUi.speedIndex();
        long holdMs = (si == 0) ? Math.max(8L, cameraEngine.getCurrentShutterNs() / 1000000L) : FilmUi.SPEED_MS[si];
        fx.shutterOpen();
        fx.iris();
        filmUi.playShot(holdMs, FilmUi.APERTURE_F[filmUi.apertureIndex()]);
        cocked = false;
        btnShutter.performClick();
        mainHandler.postDelayed(new MechTask(this, 0), 90 + holdMs);
        if (motorDrive) mainHandler.postDelayed(new MechTask(this, 1), 1100 + holdMs);
        refreshFilmData();
    }

    static class MechTask implements Runnable {
        private final MainActivity a;
        private final int kind;

        MechTask(MainActivity a, int kind) {
            this.a = a;
            this.kind = kind;
        }

        @Override
        public void run() {
            if (kind == 0) {
                a.fx.shutterClose();
            } else {
                a.fx.advance();
                a.cocked = true;
                a.refreshFilmData();
            }
        }
    }

    static class FilmCallbacks implements FilmUi.Callbacks {
        private final MainActivity a;

        FilmCallbacks(MainActivity a) {
            this.a = a;
        }

        @Override public void onRelease() { a.filmRelease(); }

        @Override public void onWound() {
            a.cocked = true;
            a.refreshFilmData();
        }

        @Override public void onRewind() {
            a.rollFrameCounter = 1;
            a.cocked = true;
            a.refreshFilmData();
            Toast.makeText(a, "Roll rewound - fresh film loaded", Toast.LENGTH_SHORT).show();
        }

        @Override public void onStockTap() {
            a.stockButtons[(a.activeStockIndex + 1) % 12].performClick();
        }

        @Override public void onStockLong() { a.onLongClick(a.stockButtons[0]); }

        @Override public void onLens(int index) {
            Button[] b = {a.btn06x, a.btn1x, a.btn3x, a.btnFront};
            b[index].performClick();
        }

        @Override public void onMenu() { a.toggleSheet(); }

        @Override public void onMode(boolean movie) {
            a.setMode(movie);
            a.refreshSelection();
        }

        @Override public void onMotor(boolean on) {
            a.motorDrive = on;
            a.filmUi.setMotor(on);
        }

        @Override public void onSpeed(int index) {
            long ns = SPEED_NS[index];
            if (ns < 0) ns = a.cameraEngine.getDeviceMaxShutterNs();
            a.cameraEngine.setManualShutterNs(ns);
            a.refreshFilmData();
        }

        @Override public void onAperture(int index) {
            a.cameraEngine.setVirtualAperture(FilmUi.APERTURE_F[index]);
            a.refreshFilmData();
        }
    }

    // PHOTO / VIDEO: the shutter slot shows the photo button or the record button.
    private void setMode(boolean video) {
        if (videoRecorder != null && videoRecorder.isRecording()) return;
        videoMode = video;
        modePhoto.setSelected(!video);
        modeVideo.setSelected(video);
        btnShutter.setVisibility(video ? View.GONE : View.VISIBLE);
        btnRecordVideo.setVisibility(video ? View.VISIBLE : View.GONE);
    }

    static class ModeSwipeListener implements View.OnTouchListener {
        private final MainActivity activity;
        private float downX;

        ModeSwipeListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
            } else if (e.getAction() == MotionEvent.ACTION_UP) {
                float dx = e.getX() - downX;
                if (dx < -80f) {
                    activity.setMode(true);
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (dx > 80f) {
                    activity.setMode(false);
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            return false;
        }
    }

    // Slide-up sheet: fades and rises on open, fades out on close.
    private void toggleSheet() {
        float density = getResources().getDisplayMetrics().density;
        if (darkroomDrawer.getVisibility() == View.VISIBLE) {
            darkroomDrawer.animate().alpha(0f).translationY(24 * density).setDuration(160).start();
            mainHandler.postDelayed(new HideSheetTask(darkroomDrawer), 170);
        } else {
            darkroomDrawer.setAlpha(0f);
            darkroomDrawer.setTranslationY(36 * density);
            darkroomDrawer.setVisibility(View.VISIBLE);
            darkroomDrawer.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
    }

    static class HideSheetTask implements Runnable {
        private final View sheet;

        HideSheetTask(View sheet) {
            this.sheet = sheet;
        }

        @Override
        public void run() {
            if (sheet.getAlpha() < 0.05f) sheet.setVisibility(View.GONE);
        }
    }

    // Gallery thumbnail: newest S24Film JPEG, decoded small.
    private void loadLatestThumbnail() {
        try {
            File dir = new File("/sdcard/DCIM/S24Film");
            File[] files = dir.listFiles();
            File newest = null;
            if (files != null) {
                for (File f : files) {
                    if (f.getName().endsWith(".jpg")
                        && (newest == null || f.lastModified() > newest.lastModified())) {
                        newest = f;
                    }
                }
            }
            if (newest != null) setThumbnail(newest.getAbsolutePath());
        } catch (Throwable ignored) {}
    }

    private void setThumbnail(String jpgPath) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 16;
            Bitmap bmp = BitmapFactory.decodeFile(jpgPath, opts);
            if (bmp != null) btnGallery.setImageBitmap(bmp);
        } catch (Throwable ignored) {}
    }

    private void openGallery() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.sec.android.gallery3d");
            if (i != null) startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Gallery unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    static class LookPickListener implements DialogInterface.OnClickListener {
        private final MainActivity activity;

        LookPickListener(MainActivity activity) {
            this.activity = activity;
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            activity.pickedLookIndex = which;
            activity.syncLookToNative();
            dialog.dismiss();
        }
    }

    // Long-press any stock button: pick any of the 120 master looks.
    @Override
    public boolean onLongClick(View v) {
        if (!vulkanInitialized) return false;
        int count = VulkanRenderer.nativeGetLookCount();
        if (count <= 0) return false;
        String[] names = new String[count];
        for (int i = 0; i < count; i++) {
            names[i] = String.format(Locale.US, "%03d  %s", i, VulkanRenderer.nativeGetLookName(i));
        }
        new AlertDialog.Builder(this)
            .setTitle("Master Look (120)")
            .setItems(names, new LookPickListener(this))
            .show();
        return true;
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
        VulkanRenderer.nativeSetDarkroomSliders(userEvStops, userGrainMult, effectiveHalationMult());
    }

    @Override public void onStartTrackingTouch(SeekBar seekBar) {}
    @Override public void onStopTrackingTouch(SeekBar seekBar) {}

    @Override
    public void onClick(View v) {
        int id = v.getId();
        v.performHapticFeedback(id == R.id.btn_shutter || id == R.id.btn_record_video
            ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.KEYBOARD_TAP);
        if (id == R.id.btn_06x) {
            switchCamera("2", "0.6x Ultrawide (Sony IMX564)");
        } else if (id == R.id.btn_1x) {
            switchCamera("0", "1x Wide (ISOCELL GN3)");
        } else if (id == R.id.btn_3x) {
            switchCamera("6", "3x Telephoto (ISOCELL S5K3K1 #6)");
        } else if (id == R.id.btn_front) {
            switchCamera("1", "Front Selfie (ISOCELL S5K3LU)");
        } else if (id == R.id.btn_shutter) {
            tvShutterStatus.setText("Developing DNG + " + activeLookLabel() + "...");
            btnShutter.setEnabled(false);
            cameraEngine.triggerCapture(activeFileTag(), this);
        } else if (id == R.id.btn_record_video) {
            if (videoRecorder.isRecording()) {
                btnRecordVideo.setEnabled(false);
                tvShutterStatus.setText("Saving video…");
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
        } else if (id == R.id.btn_save_raw) {
            DngSaver.setSaveDng(!DngSaver.isSaveDng());
            refreshSelection();
        } else if (id == R.id.btn_fmt_0 || id == R.id.btn_fmt_1 || id == R.id.btn_fmt_2 || id == R.id.btn_fmt_3) {
            filmFormat = (id == R.id.btn_fmt_0) ? 0 : (id == R.id.btn_fmt_1 ? 1 : (id == R.id.btn_fmt_2 ? 2 : 3));
            VulkanRenderer.nativeSetFilmFormat(filmFormat);
            refreshSelection();
        } else if (id == R.id.btn_test_series) {
            startTestSeries();
        } else if (id == R.id.btn_theme_0 || id == R.id.btn_theme_1 || id == R.id.btn_theme_2) {
            applyTheme(id == R.id.btn_theme_0 ? 0 : (id == R.id.btn_theme_1 ? 1 : 2));
        } else if (id == R.id.btn_film_iso) {
            cameraEngine.setFilmIsoMode(!cameraEngine.isFilmIsoMode());
            refreshSelection();
        } else if (id == R.id.btn_auto_gain) {
            cameraEngine.setAutoGain(!cameraEngine.isAutoGain());
            refreshSelection();
        } else if (id == R.id.btn_push_fix) {
            int add = (int) Math.ceil(lastUnderEv);
            cameraEngine.setPushStops(cameraEngine.getPushStops() + Math.max(1, add));
            refreshSelection();
        } else if (id == R.id.btn_pushm2 || id == R.id.btn_pushm1 || id == R.id.btn_push0
                   || id == R.id.btn_push1 || id == R.id.btn_push2 || id == R.id.btn_push3) {
            int stops = (id == R.id.btn_pushm2) ? -2 : (id == R.id.btn_pushm1) ? -1 : (id == R.id.btn_push0) ? 0
                      : (id == R.id.btn_push1) ? 1 : (id == R.id.btn_push2) ? 2 : 3;
            cameraEngine.setPushStops(stops);
            refreshSelection();
        } else if (id == R.id.mode_photo) {
            setMode(false);
        } else if (id == R.id.mode_video) {
            setMode(true);
        } else if (id == R.id.btn_gallery) {
            openGallery();
        } else if (id == R.id.btn_darkroom) {
            toggleSheet();
        } else {
            int[] stockBtnIds = {
                R.id.btn_stock_0, R.id.btn_stock_1, R.id.btn_stock_2, R.id.btn_stock_3,
                R.id.btn_stock_4, R.id.btn_stock_5, R.id.btn_stock_6, R.id.btn_stock_7,
                R.id.btn_stock_8, R.id.btn_stock_9, R.id.btn_stock_10, R.id.btn_stock_11
            };
            for (int i = 0; i < 12; i++) {
                if (id == stockBtnIds[i]) {
                    activeStockIndex = i;
                    pickedLookIndex = -1;
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
        refreshSelection();
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
                        vulkanInitialized = VulkanRenderer.nativeInit(currentHolder.getSurface(), getAssets());
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
                    vulkanInitialized = VulkanRenderer.nativeInit(currentHolder.getSurface(), getAssets());
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
        boolean ok = VulkanRenderer.nativeInit(holder.getSurface(), getAssets());
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
            ((MainActivity) hudOverlay.getContext()).applyUiRotation();
            ((MainActivity) hudOverlay.getContext()).updateExposureUi();
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

        final int ei = cameraEngine.getEffectiveEi();
        final float underEv = cameraEngine.getUnderexposureEv();
        String hudText = String.format(Locale.US,
            "%s%.0f fps  %.1f ms\n%s  %.0f\u00b0C%s",
            rec ? "\u25cf REC  " : "", cameraFps, vulkanMs,
            cfaName, batteryTempC, ei > 0 ? ("\nEI " + ei) : "");
        lastUnderEv = underEv;

        mainHandler.post(new UpdateHudTask(hudOverlay, hudText));
    }

    // DngSaver.Callback
    @Override
    public void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
        btnShutter.setEnabled(true);
        rollFrameCounter = (rollFrameCounter % 36) + 1;
        testShotFinished();
        long dngMb = dngBytes / (1024 * 1024);
        long jpgKb = jpgBytes / 1024;
        String msg = (dngBytes > 0)
            ? String.format(Locale.US, "Saved %02d/36 · RAW %dMB + JPG %dKB", rollFrameCounter, dngMb, jpgKb)
            : String.format(Locale.US, "Saved %02d/36 · JPG %dKB", rollFrameCounter, jpgKb);
        if (jpgPath != null && !jpgPath.isEmpty()) setThumbnail(jpgPath);
        tvShutterStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDngError(String message) {
        btnShutter.setEnabled(true);
        testShotFinished();
        tvShutterStatus.setText("Capture Failed: " + message);
        Toast.makeText(this, "Capture Error: " + message, Toast.LENGTH_LONG).show();
    }

    // CinemaVideoRecorder.Callback
    @Override
    public void onRecordingStarted(String mp4Path) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(true);
        tvShutterStatus.setText("● Recording");
    }

    @Override
    public void onRecordingProgress(int elapsedSec, int feetRemaining, int framesRemaining) {
        this.magFeetRemaining = feetRemaining;
        this.magFramesRemaining = framesRemaining;
        int min = elapsedSec / 60;
        int sec = elapsedSec % 60;
        tvShutterStatus.setText(String.format(Locale.US,
            "● %02d:%02d · %03d ft",
            min, sec, feetRemaining));
    }

    @Override
    public void onRecordingSaved(String mp4Path, String gcsvPath, long mp4Bytes, int gyroSamples) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(false);
        syncLookToNative();
        double mb = mp4Bytes / (1024.0 * 1024.0);
        String msg = String.format(Locale.US, "Saved Video (%.1fMB) + %d Gyro Samples (.gcsv)", mb, gyroSamples);
        tvShutterStatus.setText(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRecordingError(String error) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(false);
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
