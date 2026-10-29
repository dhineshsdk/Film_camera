package com.s24film.camera;

import android.Manifest;
import android.app.Activity;
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
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
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
    private TextView tvFilm, tvReadout;
    private View settingsSheet, sheetScrim;
    private ImageButton btnSettings, btnFlash, btnPortrait;
    private static final int TAB_LOOK = 0, TAB_CAPTURE = 1, TAB_DISPLAY = 2, TAB_DEBUG = 3;
    private final TextView[] sheetTabs = new TextView[4];   // Look, Capture, Display, Debug
    private final View[] sheetPages = new View[4];
    private int sheetTab = TAB_LOOK;
    private boolean debugMenu = false;   // Debug tab unlocked (long-press the sheet title)
    private boolean showStats = false;   // pipeline stats pill
    private Button btnStats;
    private TextView tvEvLabel, tvGrainLabel, tvHalLabel;
    private SeekBar seekEv, seekGrain, seekHalation;
    private Button btn06x, btn1x, btn3x, btnFront, btnShutter, btnRecordVideo;
    private View viewShutterFlash;
    private Button btnLens, btnAnamorphic, btnGate, btnStepPrint, btnBurst, btnStack, btnWb, btnRotate;
    private Button btnTimer, btnGrid, btnSounds;
    // Print and scan (Look tab): print paper, lab scanner, B&W paper, development edge effects
    private Button btnPrintPaper, btnScanner, btnBwPaper, btnAdjacency;
    private int printPaper = 0, scanner = 0, bwPaper = 0;
    private boolean adjacency = true;
    private static final String[] PRINT_PAPERS = {"Auto", "Crystal Archive", "Endura Premier", "Supra Endura",
        "Portra Endura", "Ultra Endura", "Vision 2383", "Vision 2393", "Ektacolor Edge"};
    private static final String[] SCANNERS = {"Neutral", "Frontier", "Noritsu"};
    private static final String[] BW_PAPERS = {"Scan", "Multigrade RC", "Multigrade FB Warmtone"};
    private ViewfinderHud vfHud;
    private int timerSec = 0;          // self-timer: 0, 3 or 10 s
    private int timerLeft = 0;         // > 0 while counting down
    private int timerToken = 0;
    private boolean gridOn = false;
    private boolean soundsOn = true;
    private String lastReadout = "";
    private long lastBatteryReadMs = 0;
    private float lastBatteryTempC = 0f;
    private final Button[] stockButtons = new Button[12];
    private HorizontalScrollView stripStocks;
    private final Button[] formatButtons = new Button[4];
    private Button btnSaveRaw;
    private TextView modePhoto, modeVideo, modePro;
    // Capture modes on the mode strip, left to right: PRO | PHOTO | VIDEO
    static final int MODE_PHOTO = 0, MODE_VIDEO = 1, MODE_PRO = 2;
    private int captureMode = MODE_PHOTO;
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
    static final long[] SPEED_NS = {0L, 1000000L, 2000000L, 4000000L, 8000000L, 16666667L, 33333333L,
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
    private LookCatalog lookCatalog;
    private StockBrowser stockBrowser;
    private int pickedLookIndex = -1; // long-press picker override over all 120 looks (-1 = none)
    private int anamorphicMode = 0;   // 0=1.0x, 1=1.33x, 2=2.0x
    private int cinemaGate = 0;       // 0=3:4, 1=16:9, 2=2.39:1
    private boolean stepPrint8fps = false;
    private boolean burstModeEnabled = true;
    private int rollFrameCounter = 1;
    private ProPanel proPanel;
    private VideoPanel videoPanel;
    private String recFormatLabel = "";
    private static final int AUDIO_PERMISSION_REQ_CODE = 1002;

    static final class VideoHost implements VideoPanel.Host {
        private final MainActivity a;
        VideoHost(MainActivity a) { this.a = a; }
        @Override public boolean isRecording() { return a.videoRecorder != null && a.videoRecorder.isRecording(); }
    }

    static final class NewRoll implements ProPanel.Host {
        private final MainActivity a;
        NewRoll(MainActivity a) { this.a = a; }
        @Override public void onNewRoll() {
            a.rollFrameCounter = 1;
            a.refreshReadout();
            Toast.makeText(a, "New roll: 36 exposures", Toast.LENGTH_SHORT).show();
        }
        @Override public float currentEv() { return a.userEvStops; }
        @Override public void onProEv(float stops) { a.setUserEv(stops); }
    }

    /** Viewfinder gestures: tap point, drag-to-expose, pinch between rear lenses. */
    static final class HudHost implements ViewfinderHud.Host {
        private final MainActivity a;
        HudHost(MainActivity a) { this.a = a; }
        @Override public void onTapPoint(float u, float v) { DepthBokeh.setFocusPoint(u, v); }
        @Override public float currentEv() { return a.userEvStops; }
        @Override public void onEvDrag(float stops) { a.setUserEv(stops); }
        @Override public void onPinchLens(int dir) { a.stepRearLens(dir); }
    }
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
        viewShutterFlash = findViewById(R.id.view_shutter_flash);
        // Taps, drags and pinches on the preview go to the viewfinder HUD (focus point, exposure, lens).
        vfHud = findViewById(R.id.vf_hud);
        vfHud.host = new HudHost(this);
        hudOverlay = findViewById(R.id.hud_overlay);
        tvShutterStatus = findViewById(R.id.tv_shutter_status);
        tvFilm = findViewById(R.id.btn_film);
        tvReadout = findViewById(R.id.tv_readout);
        settingsSheet = findViewById(R.id.settings_sheet);
        sheetScrim = findViewById(R.id.sheet_scrim);
        btnSettings = findViewById(R.id.btn_settings);
        btnStats = findViewById(R.id.btn_stats);
        int[] tabIds = {R.id.tab_look, R.id.tab_capture, R.id.tab_display, R.id.tab_debug};
        int[] pageIds = {R.id.page_look, R.id.page_capture, R.id.page_display, R.id.page_debug};
        for (int i = 0; i < sheetTabs.length; i++) {
            sheetTabs[i] = findViewById(tabIds[i]);
            sheetTabs[i].setOnClickListener(this);
            sheetPages[i] = findViewById(pageIds[i]);
        }
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
        btnPortrait = findViewById(R.id.btn_portrait);
        btnStack = findViewById(R.id.btn_stack);
        btnFlash = findViewById(R.id.btn_flash);
        btnWb = findViewById(R.id.btn_wb);
        btnRotate = findViewById(R.id.btn_rotate);
        btnTimer = findViewById(R.id.btn_timer);
        btnGrid = findViewById(R.id.btn_grid);
        btnSounds = findViewById(R.id.btn_sounds);
        btnTimer.setOnClickListener(this);
        btnPrintPaper = findViewById(R.id.btn_print_paper);
        btnScanner = findViewById(R.id.btn_scanner);
        btnBwPaper = findViewById(R.id.btn_bw_paper);
        btnAdjacency = findViewById(R.id.btn_adjacency);
        btnPrintPaper.setOnClickListener(this);
        btnScanner.setOnClickListener(this);
        btnBwPaper.setOnClickListener(this);
        btnAdjacency.setOnClickListener(this);
        btnGrid.setOnClickListener(this);
        btnSounds.setOnClickListener(this);

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

        lookCatalog = new LookCatalog(this);
        stockBrowser = new StockBrowser(this, lookCatalog, new LookPicked(this));
        int last = lookCatalog.lastUsed();
        if (last >= 0 && last < lookCatalog.looks.size()) pickedLookIndex = last;

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
        modePro = findViewById(R.id.mode_pro);
        btnGallery = findViewById(R.id.btn_gallery);
        modePhoto.setOnClickListener(this);
        modeVideo.setOnClickListener(this);
        modePro.setOnClickListener(this);
        btnGallery.setOnClickListener(this);
        findViewById(R.id.mode_strip).setOnTouchListener(new ModeSwipeListener(this));
        btnSaveRaw.setOnClickListener(this);
        tvFilm.setOnClickListener(this);
        btnSettings.setOnClickListener(this);
        btnStats.setOnClickListener(this);
        sheetScrim.setOnClickListener(this);
        findViewById(R.id.btn_sheet_done).setOnClickListener(this);
        findViewById(R.id.tv_sheet_title).setOnLongClickListener(this);
        // Icons and round buttons turn to stay upright in a landscape grip; text rows stay put.
        rotatingViews = new View[] {
            btn06x, btn1x, btn3x, btnFront, btnShutter, btnRecordVideo, btnSettings, btnGallery,
            btnFlash, btnPortrait, btnTimer
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
        btnPortrait.setOnClickListener(this);
        btnStack.setOnClickListener(this);
        btnFlash.setOnClickListener(this);
        btnWb.setOnClickListener(this);
        btnRotate.setOnClickListener(this);

        seekEv.setOnSeekBarChangeListener(this);
        seekGrain.setOnSeekBarChangeListener(this);
        seekHalation.setOnSeekBarChangeListener(this);

        imuTelemetry = new ImuTelemetry(this);
        dngSaver = new DngSaver(this);
        videoRecorder = new CinemaVideoRecorder(this, imuTelemetry);
        cameraEngine = new CameraEngine(this, dngSaver, imuTelemetry, this);
        cameraEngine.setScreenFlash(new ScreenFlashUi(this));
        cameraEngine.setStackStatus(new StackStatusBridge(this));
        applyStackChoice();

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
        proPanel = new ProPanel(this, cameraEngine, filmUi, fx, new NewRoll(this));
        videoPanel = new VideoPanel(this, cameraEngine, new VideoHost(this));
        android.content.SharedPreferences prefs = getSharedPreferences("ui", 0);
        uiTheme = prefs.getInt("theme", 0);
        debugMenu = prefs.getBoolean("debug", false);
        sheetTabs[TAB_DEBUG].setVisibility(debugMenu ? View.VISIBLE : View.GONE);
        DepthBokeh.setPortrait(prefs.getBoolean("portrait", false));
        timerSec = prefs.getInt("timer", 0);
        printPaper = Math.max(0, Math.min(PRINT_PAPERS.length - 1, prefs.getInt("print_paper", 0)));
        scanner = Math.max(0, Math.min(SCANNERS.length - 1, prefs.getInt("scanner", 0)));
        bwPaper = Math.max(0, Math.min(BW_PAPERS.length - 1, prefs.getInt("bw_paper", 0)));
        adjacency = prefs.getBoolean("adjacency", true);
        gridOn = prefs.getBoolean("grid", false);
        soundsOn = prefs.getBoolean("sounds", true);
        fx.setSound(soundsOn);
        vfHud.setGrid(gridOn);
        setCaptureMode(prefs.getInt("mode", MODE_PHOTO) == MODE_PRO ? MODE_PRO : MODE_PHOTO);
        applyTheme(uiTheme);
        loadLatestThumbnail();
        checkPermissions();
    }

    // Short canister brand: first word of the look name, capped for the label.
    private static String canisterBrand(String name) {
        String b = name.trim().replaceFirst(
            "^(?i)(kodak|fujifilm|fujicolor|fujichrome|fuji|agfacolor|agfa|ilford|eastman|konica|foma|orwo)\\s+", "");
        int sp = b.indexOf(' ');
        if (sp > 0 && b.length() > 9) b = b.substring(0, sp);
        return b.toUpperCase(Locale.US);
    }

    private int currentLookForBrowser() {
        return (pickedLookIndex >= 0) ? pickedLookIndex : STOCK_TO_LOOK[activeStockIndex];
    }

    static final class LookPicked implements StockBrowser.Listener {
        private final MainActivity a;
        LookPicked(MainActivity a) { this.a = a; }
        @Override public void onLookPicked(int lookIndex) {
            a.pickedLookIndex = lookIndex;
            a.syncLookToNative();
        }
    }

    private void openStockBrowser() {
        if (stockBrowser == null) return;
        stockBrowser.show(currentLookForBrowser());
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
        pushPrintScan();
        refreshSelection();
        tvFilm.setText(activeLookLabel());
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
            LookCatalog.Look pl = (pickedLookIndex >= 0) ? lookCatalog.get(pickedLookIndex) : null;
            if (pl != null) {
                filmUi.setCanisterLook(canisterBrand(pl.name), pl.iso,
                    LookCatalog.CATEGORY_COLORS[pl.category], pl.name);
            }
            filmUi.setMovie(videoMode);
        }
        btn06x.setSelected("2".equals(currentCameraId));
        btn1x.setSelected("0".equals(currentCameraId));
        btn3x.setSelected("6".equals(currentCameraId));
        btnFront.setSelected("1".equals(currentCameraId));
        btnStats.setSelected(showStats);
        btnStats.setText(showStats ? "Stats: On" : "Stats: Off");
        hudOverlay.setVisibility(showStats && uiTheme == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < sheetTabs.length; i++) {
            sheetTabs[i].setSelected(i == sheetTab);
            sheetPages[i].setVisibility(i == sheetTab ? View.VISIBLE : View.GONE);
        }
        boolean sheetOpen = settingsSheet.getVisibility() == View.VISIBLE;
        findViewById(R.id.group_video).setVisibility(videoMode ? View.VISIBLE : View.GONE);
        btnSettings.setSelected(sheetOpen);
        btnGrid.setSelected(gridOn);
        btnGrid.setText(gridOn ? "Grid: 3×3" : "Grid: Off");
        btnSounds.setSelected(soundsOn);
        btnSounds.setText(soundsOn ? "Sounds: On" : "Sounds: Off");
        btnPrintPaper.setText("Paper: " + PRINT_PAPERS[printPaper]);
        btnPrintPaper.setSelected(printPaper != 0);
        btnScanner.setText("Scan: " + SCANNERS[scanner]);
        btnScanner.setSelected(scanner != 0);
        btnBwPaper.setText("B&W: " + BW_PAPERS[bwPaper]);
        btnBwPaper.setSelected(bwPaper != 0);
        btnAdjacency.setText(adjacency ? "Edge effects: On" : "Edge effects: Off");
        btnAdjacency.setSelected(adjacency);
        refreshQuickBar();
    }

    // The renderer keeps none of these across a restart, so they are re-sent with every look sync.
    private void pushPrintScan() {
        VulkanRenderer.nativeSetPrintPaper(printPaper);
        VulkanRenderer.nativeSetScanner(scanner);
        VulkanRenderer.nativeSetBwPaper(bwPaper);
        VulkanRenderer.nativeSetAdjacency(adjacency);
    }

    // Quick bar on the top band. Video hides the still-only items (capture mode, timer, portrait).
    private void refreshQuickBar() {
        int fm = cameraEngine != null ? cameraEngine.getFlashMode() : 1;
        btnFlash.setImageResource(fm == 0 ? R.drawable.ic_flash_off : (fm == 2 ? R.drawable.ic_flash_on : R.drawable.ic_flash_auto));
        btnFlash.setSelected(fm == 2);
        btnFlash.setContentDescription("Flash: " + FLASH_LABELS[fm]);
        btnStack.setText(STACK_CHOICES[stackChoice]);
        btnStack.setSelected(stackChoice != 0);
        btnGate.setText(GATE_SHORT[cinemaGate]);
        btnGate.setSelected(cinemaGate != 0);
        btnTimer.setText(timerSec > 0 ? timerSec + "s" : "");
        btnTimer.setSelected(timerSec > 0);
        btnPortrait.setSelected(DepthBokeh.portrait);
        int still = videoMode ? View.GONE : View.VISIBLE;
        btnStack.setVisibility(still);
        btnTimer.setVisibility(still);
        btnPortrait.setVisibility(still);
    }

    private static final String[] FLASH_LABELS = {"Off", "Auto", "On"};
    private static final String[] GATE_SHORT = {"3:4", "16:9", "2.39"};

    // Rotates the compact controls and the info pills so they read upright in a landscape grip.
    private void applyUiRotation() {
        int base = imuTelemetry.getGravityOrientation();
        int target = (base == 270) ? 90 : (base == 90 ? -90 : (base == 180 ? 180 : 0));
        if (target == uiRotationDeg) return;
        uiRotationDeg = target;
        for (View v : rotatingViews) {
            v.animate().rotation(target).setDuration(220).start();
        }
    }

    // Top readout: A/M + shutter, sensor ISO, film EI and push, lens, ROT when set, frame on the roll.
    private void refreshReadout() {
        if (cameraEngine == null) return;
        StringBuilder sb = new StringBuilder();
        boolean manual = cameraEngine.getManualShutterNs() > 0;
        sb.append(manual ? "M " : "A ").append(fmtSpeed(cameraEngine.getCurrentShutterNs()));
        sb.append("   ISO ").append(cameraEngine.getCurrentIso());
        int ei = cameraEngine.getEffectiveEi();
        if (ei > 0) sb.append("   EI ").append(ei);
        int push = cameraEngine.getPushStops();
        if (push != 0) sb.append(String.format(Locale.US, " %+d", push));
        String lens = "2".equals(currentCameraId) ? ".6×" : ("0".equals(currentCameraId) ? "1×"
            : ("6".equals(currentCameraId) ? "3×" : "Front"));
        sb.append("   ").append(lens);
        if (cameraEngine != null && cameraEngine.hasOis()) {
            sb.append(" \u00b7 OIS");
        }
        int rot = cameraEngine.getManualRotationOffset();
        if (rot != 0) sb.append("  ROT ").append(rot).append('\u00b0');
        if (videoRecorder != null && videoRecorder.isRecording()) {
            sb.append("   \u25cf REC");
        } else {
            sb.append(String.format(Locale.US, "   %02d/36 \u00b7 %d left", rollFrameCounter, 37 - rollFrameCounter));
        }
        String plain = sb.toString();
        if (plain.equals(lastReadout)) return;   // called every ~120 ms: skip relayout when nothing moved
        lastReadout = plain;
        tvReadout.setText(styleReadout(plain));
    }

    // Labels (A/M, ISO, EI, left, OIS) dim, values bright, manual and REC in gold.
    private static CharSequence styleReadout(String t) {
        SpannableStringBuilder b = new SpannableStringBuilder(t);
        String[] dim = {"ISO", "EI", "left", "OIS", "/36", "\u00b7", "ROT"};
        for (String k : dim) {
            int at = t.indexOf(k);
            while (at >= 0) {
                b.setSpan(new ForegroundColorSpan(0x8CFFFFFF), at, at + k.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = t.indexOf(k, at + k.length());
            }
        }
        if (t.startsWith("M ")) b.setSpan(new ForegroundColorSpan(0xFFFFC845), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else b.setSpan(new ForegroundColorSpan(0x8CFFFFFF), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int rec = t.indexOf("\u25cf REC");
        if (rec >= 0) b.setSpan(new ForegroundColorSpan(0xFFFF453A), rec, rec + 5, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    // Low-light banner: shows when the film-exposure logic is underexposing.
    private void updateExposureUi() {
        if (uiTheme != 0) refreshFilmData();   // the film-body data strip is only on screen in the film themes
        boolean show = lastUnderEv > 0.2f;
        if (show) {
            tvWarning.setText(String.format(Locale.US,
                "Not enough light, underexposing by %.1f stops", lastUnderEv));
        }
        int vis = show ? View.VISIBLE : View.GONE;
        if (warnBar.getVisibility() != vis) warnBar.setVisibility(vis);
    }

    // Test series: every lens x a set of film stocks, one photo each, files tagged <Lens>_<Stock>.
    private void startTestSeries() {
        if (testRunning) return;
        if (videoRecorder != null && videoRecorder.isRecording()) return;
        setCaptureMode(MODE_PHOTO);
        testRunning = true;
        testStep = 0;
        cameraEngine.resetManualRotationOffset();   // a leftover ROT press turned a whole series sideways (issue 4)
        btnRotate.setText("ROT +0°");
        Toast.makeText(this, "Test series started: 4 lenses x " + TEST_STOCKS.length + " stocks", Toast.LENGTH_LONG).show();
        mainHandler.post(new TestTask(this, 0, 0));
    }

    private void testAdvance() {
        int total = TEST_CAMS.length * TEST_STOCKS.length;
        if (testStep >= total) {
            testRunning = false;
            showStatus("Test series done", false);
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
        showStatus("Test " + (testStep + 1) + "/" + total + "  " + TEST_CAM_TAGS[cam] + " " + STOCK_FILE_TAGS[activeStockIndex], true);
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

    // Front-camera flash: a white overlay with the screen at full brightness lights the face.
    private View screenFlashView;

    void setScreenFlashShown(boolean on) {
        android.view.ViewGroup root = (android.view.ViewGroup) findViewById(android.R.id.content);
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (on) {
            if (screenFlashView == null) {
                screenFlashView = new View(this);
                screenFlashView.setBackgroundColor(0xFFFFFFFF);
            }
            if (screenFlashView.getParent() == null) {
                root.addView(screenFlashView, new android.view.ViewGroup.LayoutParams(-1, -1));
            }
            lp.screenBrightness = 1.0f;
        } else {
            if (screenFlashView != null && screenFlashView.getParent() != null) root.removeView(screenFlashView);
            lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(lp);
    }

    static class ScreenFlashUi implements CameraEngine.ScreenFlash {
        private final MainActivity a;
        ScreenFlashUi(MainActivity a) { this.a = a; }
        @Override public void onScreenFlash(boolean on) { a.runOnUiThread(new ScreenFlashTask(a, on)); }
    }

    static class ScreenFlashTask implements Runnable {
        private final MainActivity a; private final boolean on;
        ScreenFlashTask(MainActivity a, boolean on) { this.a = a; this.on = on; }
        @Override public void run() { a.setScreenFlashShown(on); }
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

    void debugTheme(int t) {
        applyTheme(Math.max(0, Math.min(2, t)));
    }

    // 0 = modern, 1 = black SLR, 2 = silver rangefinder.
    private void applyTheme(int t) {
        uiTheme = t;
        getSharedPreferences("ui", 0).edit().putInt("theme", t).apply();
        boolean film = (t != 0);
        if (film && captureMode == MODE_PRO) setCaptureMode(MODE_PHOTO);
        if (film) cancelTimer();
        topBar.setVisibility(film ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(film ? View.GONE : View.VISIBLE);
        filmRoot.setVisibility(film ? View.VISIBLE : View.GONE);
        if (film) {
            filmUi.setTheme(t == 1 ? FilmUi.SLR : FilmUi.RF);
            new FilmCallbacks(this).onSpeed(filmUi.speedIndex());   // the speed dial owns the shutter here
        } else if (captureMode != MODE_PRO) {
            cameraEngine.setManualShutterNs(0L);                   // Modern PHOTO/VIDEO meter on auto
        }
        // Modern: the sheet stops above the shutter row. Film themes: above the dials and pills.
        RelativeLayout.LayoutParams lp = (RelativeLayout.LayoutParams) settingsSheet.getLayoutParams();
        lp.bottomMargin = dpToPx(film ? 170 : 100);
        settingsSheet.setLayoutParams(lp);
        findViewById(R.id.info_stack).setTranslationY(film ? dpToPx(170) : 0f);
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

    static class ShutterBounceTask implements Runnable {
        private final View v;
        ShutterBounceTask(View v) { this.v = v; }
        @Override public void run() {
            if (v != null) v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start();
        }
    }

    static class FlashDismissTask implements Runnable {
        private final View v;
        FlashDismissTask(View v) { this.v = v; }
        @Override public void run() {
            if (v != null) v.setVisibility(View.GONE);
        }
    }

    private void triggerShutterFeedback() {
        if (fx != null) {
            fx.shutterOpen();
        }
        if (btnShutter != null) {
            btnShutter.animate().scaleX(0.85f).scaleY(0.85f).setDuration(50)
                .withEndAction(new ShutterBounceTask(btnShutter)).start();
        }
        if (viewShutterFlash != null) {
            viewShutterFlash.setAlpha(0.85f);
            viewShutterFlash.setVisibility(View.VISIBLE);
            viewShutterFlash.animate().alpha(0.0f).setDuration(120)
                .withEndAction(new FlashDismissTask(viewShutterFlash)).start();
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
            a.openStockBrowser();
        }

        @Override public void onStockLong() { a.openStockBrowser(); }

        @Override public void onLens(int index) {
            Button[] b = {a.btn06x, a.btn1x, a.btn3x, a.btnFront};
            b[index].performClick();
        }

        @Override public void onMenu() { a.toggleSheet(a.sheetTab); }

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

    // Film themes only know STILL / MOVIE.
    private void setMode(boolean video) {
        setCaptureMode(video ? MODE_VIDEO : MODE_PHOTO);
    }

    // PRO / PHOTO / VIDEO: the shutter slot shows the photo button or the record button, and PRO shows
    // the pro bar (its manual values apply only while PRO is active).
    private void setCaptureMode(int m) {
        if (videoRecorder != null && videoRecorder.isRecording()) return;
        if (m != MODE_PHOTO) cancelTimer();
        captureMode = m;
        boolean video = (m == MODE_VIDEO);
        videoMode = video;
        if (cameraEngine != null) cameraEngine.setVideoMode(video);
        if (video) {
            if (videoPanel != null) videoPanel.apply();
            requestAudioPermissionOnce();
        }
        if (proPanel != null && proPanel.isActive() != (m == MODE_PRO)) proPanel.setActive(m == MODE_PRO);
        modePhoto.setSelected(m == MODE_PHOTO);
        modeVideo.setSelected(video);
        modePro.setSelected(m == MODE_PRO);
        btnShutter.setVisibility(video ? View.GONE : View.VISIBLE);
        btnRecordVideo.setVisibility(video ? View.VISIBLE : View.GONE);
        if (m != MODE_VIDEO) getSharedPreferences("ui", 0).edit().putInt("mode", m).apply();
        lastReadout = "";
        refreshQuickBar();
    }

    // Position on the strip, left to right: PRO 0, PHOTO 1, VIDEO 2.
    private static final int[] MODE_AT = {MODE_PRO, MODE_PHOTO, MODE_VIDEO};

    private void stepMode(int dir) {
        int at = (captureMode == MODE_PRO) ? 0 : (captureMode == MODE_PHOTO ? 1 : 2);
        int next = Math.max(0, Math.min(2, at + dir));
        if (next != at) setCaptureMode(MODE_AT[next]);
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
                    activity.stepMode(1);
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                } else if (dx > 80f) {
                    activity.stepMode(-1);
                    v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            return false;
        }
    }

    // Exposure from the Look tab slider, the viewfinder drag and the PRO EV dial all land here.
    private void setUserEv(float stops) {
        stops = Math.max(-2f, Math.min(2f, stops));
        seekEv.setProgress(Math.round(stops * 10f) + 20);   // fires onProgressChanged, which pushes it native
        if (proPanel != null && proPanel.isActive()) proPanel.refreshItems();
    }

    // Pinch on the viewfinder: out = longer rear lens, in = wider. The front camera is left alone.
    private void stepRearLens(int dir) {
        String[] ids = {"2", "0", "6"};
        int at = "2".equals(currentCameraId) ? 0 : ("0".equals(currentCameraId) ? 1 : ("6".equals(currentCameraId) ? 2 : -1));
        if (at < 0) return;
        int next = Math.max(0, Math.min(2, at + dir));
        if (next == at) return;
        Button[] b = {btn06x, btn1x, btn3x};
        b[next].performClick();
    }

    // Self-timer: the first shutter press starts the count on the viewfinder, a second press cancels it.
    private void startTimer() {
        timerToken++;
        timerLeft = timerSec;
        vfHud.setCountdown(timerLeft);
        if (fx != null) fx.click();
        mainHandler.postDelayed(new TimerTick(this, timerToken), 1000);
    }

    private void cancelTimer() {
        if (timerLeft <= 0) return;
        timerToken++;
        timerLeft = 0;
        vfHud.setCountdown(0);
    }

    static final class TimerTick implements Runnable {
        private final MainActivity a;
        private final int token;
        TimerTick(MainActivity a, int token) { this.a = a; this.token = token; }
        @Override public void run() {
            if (token != a.timerToken || a.timerLeft <= 0) return;
            a.timerLeft--;
            a.vfHud.setCountdown(a.timerLeft);
            if (a.timerLeft > 0) {
                if (a.fx != null) a.fx.click();
                a.mainHandler.postDelayed(this, 1000);
            } else if (a.btnShutter.isEnabled()) {
                a.capturePhoto();
            }
        }
    }

    private void capturePhoto() {
        triggerShutterFeedback();
        showStatus("Developing " + activeLookLabel() + "…", true);
        btnShutter.setEnabled(false);
        cameraEngine.triggerCapture(activeFileTag(), this);
    }

    // Settings sheet: the gear (or the film-body MENU) opens it on the last tab; pressing it again closes it.
    private void toggleSheet(int tab) {
        boolean open = settingsSheet.getVisibility() == View.VISIBLE && settingsSheet.getAlpha() > 0.5f;
        if (open && tab == sheetTab) {
            closeSheet();
            return;
        }
        sheetTab = tab;
        if (!open) {
            float density = getResources().getDisplayMetrics().density;
            settingsSheet.setAlpha(0f);
            settingsSheet.setTranslationY(36 * density);
            settingsSheet.setVisibility(View.VISIBLE);
            sheetScrim.setVisibility(View.VISIBLE);
            settingsSheet.animate().alpha(1f).translationY(0f).setDuration(220).start();
        }
        refreshSelection();
    }

    private void closeSheet() {
        if (settingsSheet.getVisibility() != View.VISIBLE) return;
        float density = getResources().getDisplayMetrics().density;
        settingsSheet.animate().alpha(0f).translationY(24 * density).setDuration(160).start();
        sheetScrim.setVisibility(View.GONE);
        mainHandler.postDelayed(new HideSheetTask(this), 170);
    }

    private void setDebugMenu(boolean on) {
        debugMenu = on;
        getSharedPreferences("ui", 0).edit().putBoolean("debug", on).apply();
        sheetTabs[TAB_DEBUG].setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on && sheetTab == TAB_DEBUG) sheetTab = TAB_CAPTURE;
        if (!on) showStats = false;
        Toast.makeText(this, on ? "Debug tools shown" : "Debug tools hidden", Toast.LENGTH_SHORT).show();
        refreshSelection();
    }

    private final Runnable hideStatusTask = new HideStatusTask(this);

    // Status pill under the top band. Sticky messages (recording, test series) stay until replaced.
    // Multi-frame shot choices on the CAPTURE row: mode, long-exposure length (ms), chip label.
    private static final int[] STACK_MODES = {
        CameraEngine.STACK_OFF, CameraEngine.STACK_BURST, CameraEngine.STACK_HDR,
        CameraEngine.STACK_LONG, CameraEngine.STACK_LONG, CameraEngine.STACK_LONG, CameraEngine.STACK_TRAILS};
    private static final long[] STACK_LONG_MS = {0, 0, 0, 2000, 4000, 8000, 8000};
    private static final String[] STACK_CHOICES = {
        "Single", "Burst", "HDR", "Long 2s", "Long 4s", "Long 8s", "Trails"};
    private static final String[] STACK_HINTS = {
        "Single frame", "Burst: 8 frames merged for less noise", "HDR: 3 bracketed frames merged",
        "Long exposure 2 s", "Long exposure 4 s", "Long exposure 8 s", "Light trails 8 s"};
    private int stackChoice = 0;

    private void applyStackChoice() {
        cameraEngine.setStackMode(STACK_MODES[stackChoice]);
        if (STACK_LONG_MS[stackChoice] > 0) cameraEngine.setLongExposureMs(STACK_LONG_MS[stackChoice]);
        if (btnStack != null) refreshQuickBar();
    }

    static class StackStatusBridge implements CameraEngine.StackStatus {
        private final MainActivity a;

        StackStatusBridge(MainActivity a) {
            this.a = a;
        }

        @Override
        public void onStackStatus(String text) {
            a.mainHandler.post(new StackStatusTask(a, text));
        }

        @Override
        public void onStackCancelled() {
            a.mainHandler.post(new StackStatusTask(a, null));
        }
    }

    static class StackStatusTask implements Runnable {
        private final MainActivity a;
        private final String text;

        StackStatusTask(MainActivity a, String text) {
            this.a = a;
            this.text = text;
        }

        @Override
        public void run() {
            if (text == null) {
                a.btnShutter.setEnabled(true);
                a.showStatus("Stack cancelled", false);
            } else {
                a.showStatus(text, true);
            }
        }
    }

    private void showStatus(String text, boolean sticky) {
        mainHandler.removeCallbacks(hideStatusTask);
        tvShutterStatus.animate().cancel();
        tvShutterStatus.setText(text);
        tvShutterStatus.setAlpha(1f);
        tvShutterStatus.setVisibility(View.VISIBLE);
        if (!sticky) mainHandler.postDelayed(hideStatusTask, 2500);
    }

    static class HideStatusTask implements Runnable {
        private final MainActivity a;

        HideStatusTask(MainActivity a) {
            this.a = a;
        }

        @Override
        public void run() {
            a.tvShutterStatus.animate().alpha(0f).setDuration(250).start();
        }
    }

    @Override
    public void onBackPressed() {
        if (settingsSheet.getVisibility() == View.VISIBLE) {
            closeSheet();
            return;
        }
        super.onBackPressed();
    }

    static class HideSheetTask implements Runnable {
        private final MainActivity a;

        HideSheetTask(MainActivity a) {
            this.a = a;
        }

        @Override
        public void run() {
            if (a.settingsSheet.getAlpha() < 0.05f) {
                a.settingsSheet.setVisibility(View.GONE);
                a.refreshSelection();
            }
        }
    }

    // Gallery thumbnail: newest S24Film JPEG, decoded small. Listing the folder and decoding a 50 MP
    // JPEG took the UI thread ~100+ ms right after each shot, so both run on a worker thread.
    private void loadLatestThumbnail() {
        new Thread(new ThumbJob(this, null), "thumb").start();
    }

    private void setThumbnail(String jpgPath) {
        new Thread(new ThumbJob(this, jpgPath), "thumb").start();
    }

    static final class ThumbJob implements Runnable {
        private final MainActivity a;
        private final String path;   // null = find the newest JPEG
        ThumbJob(MainActivity a, String path) { this.a = a; this.path = path; }
        @Override public void run() {
            try {
                String p = path;
                if (p == null) {
                    File[] files = new File("/sdcard/DCIM/S24Film").listFiles();
                    File newest = null;
                    if (files != null) {
                        for (File f : files) {
                            if (f.getName().endsWith(".jpg")
                                && (newest == null || f.lastModified() > newest.lastModified())) {
                                newest = f;
                            }
                        }
                    }
                    if (newest == null) return;
                    p = newest.getAbsolutePath();
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 16;
                Bitmap bmp = BitmapFactory.decodeFile(p, opts);
                if (bmp != null) a.runOnUiThread(new ThumbSet(a, bmp));
            } catch (Throwable ignored) {}
        }
    }

    static final class ThumbSet implements Runnable {
        private final MainActivity a;
        private final Bitmap bmp;
        ThumbSet(MainActivity a, Bitmap bmp) { this.a = a; this.bmp = bmp; }
        @Override public void run() {
            a.btnGallery.setImageBitmap(bmp);
            a.btnGallery.setScaleX(0.8f);
            a.btnGallery.setScaleY(0.8f);
            a.btnGallery.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
    }

    private void openGallery() {
        startActivity(new Intent(this, LibraryActivity.class));
    }

    // Long-press any stock button: open the stock browser (all 120 looks).
    @Override
    public boolean onLongClick(View v) {
        if (v.getId() == R.id.tv_sheet_title) {
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setDebugMenu(!debugMenu);
            return true;
        }
        return openLookPicker();
    }

    // Film chip, canister and long-press all open the stock browser.
    private boolean openLookPicker() {
        if (!vulkanInitialized) return false;
        openStockBrowser();
        return true;
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        int id = seekBar.getId();
        if (id == R.id.seek_ev) {
            userEvStops = (progress - 20) * 0.1f;
            tvEvLabel.setText(String.format(Locale.US, "EXPOSURE  %+.1f EV", userEvStops));
            if (fromUser && proPanel != null && proPanel.isActive()) proPanel.refreshItems();
        } else if (id == R.id.seek_grain) {
            userGrainMult = progress / 100.0f;
            tvGrainLabel.setText(String.format(Locale.US, "GRAIN  %d%%", progress));
        } else if (id == R.id.seek_halation) {
            userHalationMult = progress / 100.0f;
            tvHalLabel.setText(String.format(Locale.US, "HALATION & BLOOM  %d%%", progress));
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
            if (timerLeft > 0) {
                cancelTimer();
                showStatus("Timer cancelled", false);
            } else if (timerSec > 0 && uiTheme == 0) {
                startTimer();
            } else {
                capturePhoto();
            }
        } else if (id == R.id.btn_timer) {
            timerSec = (timerSec == 0) ? 3 : (timerSec == 3 ? 10 : 0);
            getSharedPreferences("ui", 0).edit().putInt("timer", timerSec).apply();
            cancelTimer();
            refreshQuickBar();
            showStatus(timerSec > 0 ? "Self-timer " + timerSec + " s" : "Self-timer off", false);
        } else if (id == R.id.btn_print_paper || id == R.id.btn_scanner || id == R.id.btn_bw_paper
                   || id == R.id.btn_adjacency) {
            if (id == R.id.btn_print_paper) printPaper = (printPaper + 1) % PRINT_PAPERS.length;
            else if (id == R.id.btn_scanner) scanner = (scanner + 1) % SCANNERS.length;
            else if (id == R.id.btn_bw_paper) bwPaper = (bwPaper + 1) % BW_PAPERS.length;
            else adjacency = !adjacency;
            getSharedPreferences("ui", 0).edit().putInt("print_paper", printPaper).putInt("scanner", scanner)
                .putInt("bw_paper", bwPaper).putBoolean("adjacency", adjacency).apply();
            if (vulkanInitialized) pushPrintScan();
            refreshSelection();
        } else if (id == R.id.btn_grid) {
            gridOn = !gridOn;
            getSharedPreferences("ui", 0).edit().putBoolean("grid", gridOn).apply();
            vfHud.setGrid(gridOn);
            refreshSelection();
        } else if (id == R.id.btn_sounds) {
            soundsOn = !soundsOn;
            getSharedPreferences("ui", 0).edit().putBoolean("sounds", soundsOn).apply();
            fx.setSound(soundsOn);
            refreshSelection();
        } else if (id == R.id.btn_record_video) {
            if (videoRecorder.isRecording()) {
                btnRecordVideo.setEnabled(false);
                showStatus("Saving video…", true);
                videoRecorder.stopRecording();
            } else {
                VideoSettings vs = videoPanel.settings.copy();
                recFormatLabel = vs.fps + " fps \u00b7 " + VideoSettings.angleLabel(vs.angle);
                // Same grip value the still path hands to DngSaver.setJpegRotation (grip + ROT), so a
                // photo and a take in one pose display alike; the muxer hint is clockwise degrees.
                // TODO: verify on device that frames render upright for orientation 0 (portrait) and
                // that the 90/270 hints are not swapped for the video path.
                videoRecorder.startRecording(STOCK_FILE_TAGS[activeStockIndex], vs,
                    cameraEngine.currentActiveOrient(), this);
                videoPanel.refresh();
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
        } else if (id == R.id.btn_portrait) {
            DepthBokeh.setPortrait(!DepthBokeh.portrait);
            getSharedPreferences("ui", 0).edit().putBoolean("portrait", DepthBokeh.portrait).apply();
            refreshQuickBar();
            showStatus(DepthBokeh.portrait ? "Portrait on: tap to focus, aperture sets blur" : "Portrait off", false);
        } else if (id == R.id.btn_gate) {
            cinemaGate = (cinemaGate + 1) % 3;
            refreshQuickBar();
            showStatus(GATE_LABELS[cinemaGate].replace("Gate: ", "Frame "), false);
            syncLookToNative();
        } else if (id == R.id.btn_step_print) {
            stepPrint8fps = !stepPrint8fps;
            btnStepPrint.setText(stepPrint8fps ? "WKW 8fps Step" : "24fps Normal");
            syncLookToNative();
        } else if (id == R.id.btn_burst) {
            burstModeEnabled = !burstModeEnabled;
            btnBurst.setText(burstModeEnabled ? "4F Burst" : "1F Fast");
            syncLookToNative();
        } else if (id == R.id.btn_flash) {
            cameraEngine.setFlashMode((cameraEngine.getFlashMode() + 1) % 3);
            refreshQuickBar();
            showStatus("Flash " + FLASH_LABELS[cameraEngine.getFlashMode()].toLowerCase(Locale.US), false);
        } else if (id == R.id.btn_stack) {
            stackChoice = (stackChoice + 1) % STACK_CHOICES.length;
            applyStackChoice();
            showStatus(STACK_HINTS[stackChoice], false);
        } else if (id == R.id.btn_wb) {
            boolean nextWb = !cameraEngine.isAutoSceneWb();
            cameraEngine.setAutoSceneWb(nextWb);
            btnWb.setText(nextWb ? "WB: Auto" : "WB: Film");
        } else if (id == R.id.btn_rotate) {
            int offset = cameraEngine.cycleManualRotationOffset();
            btnRotate.setText("ROT +" + offset + "°");
            refreshReadout();
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
            setCaptureMode(MODE_PHOTO);
        } else if (id == R.id.mode_video) {
            setCaptureMode(MODE_VIDEO);
        } else if (id == R.id.mode_pro) {
            setCaptureMode(MODE_PRO);
        } else if (id == R.id.btn_gallery) {
            openGallery();
        } else if (id == R.id.btn_settings) {
            toggleSheet(sheetTab);
        } else if (id == R.id.btn_sheet_done || id == R.id.sheet_scrim) {
            closeSheet();
        } else if (id == R.id.btn_film) {
            openLookPicker();
        } else if (id == R.id.btn_stats) {
            showStats = !showStats;
            refreshSelection();
        } else if (id == R.id.tab_look || id == R.id.tab_capture || id == R.id.tab_display || id == R.id.tab_debug) {
            sheetTab = (id == R.id.tab_look) ? TAB_LOOK : (id == R.id.tab_capture) ? TAB_CAPTURE
                     : (id == R.id.tab_display) ? TAB_DISPLAY : TAB_DEBUG;
            refreshSelection();
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
                    lookCatalog.setLastUsed(-1);
                    syncLookToNative();
                    break;
                }
            }
        }
    }

    void triggerUiCapture() {
        if (btnShutter != null && btnShutter.isEnabled()) {
            capturePhoto();   // adb capture skips the self-timer
        }
    }

    private void switchCamera(String camId, String label) {
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
        currentCameraId = camId;
        refreshSelection();
        refreshReadout();
        showStatus("Switching to " + label + "…", false);
        cameraEngine.openCamera(camId);   // also clears the ROT override
        btnRotate.setText("ROT +" + cameraEngine.getManualRotationOffset() + "°");
        if (proPanel != null) proPanel.onLensChanged();
    }

    // Microphone is asked for the first time video mode is entered; a take is silent if denied.
    private void requestAudioPermissionOnce() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return;
        android.content.SharedPreferences p = getSharedPreferences("ui", 0);
        if (p.getBoolean("audio_perm_asked", false)) return;
        p.edit().putBoolean("audio_perm_asked", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQ_CODE);
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
        if (requestCode == AUDIO_PERMISSION_REQ_CODE) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) Toast.makeText(this, "Microphone denied: video will be silent", Toast.LENGTH_LONG).show();
            return;
        }
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
        DebugControl.ui = this;
        if (proPanel != null) proPanel.onResume();
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
        if (DebugControl.ui == this) DebugControl.ui = null;
        cancelTimer();
        if (videoRecorder != null && videoRecorder.isRecording()) {
            videoRecorder.stopRecording();
        }
        if (proPanel != null) proPanel.onPause();
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
            MainActivity a = (MainActivity) hudOverlay.getContext();
            if (a.showStats) hudOverlay.setText(text);
            a.applyUiRotation();
            a.updateExposureUi();
            a.refreshReadout();
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
        if (showStats && now - lastBatteryReadMs > 5000) {   // a sticky-broadcast read every frame tick is wasteful
            lastBatteryReadMs = now;
            lastBatteryTempC = getBatteryTemperature();
        }
        final float batteryTempC = showStats ? lastBatteryTempC : 0f;
        final String cfaName = (cfa == 0) ? "RGGB" : (cfa == 1 ? "GRBG" : (cfa == 2 ? "GBRG" : "BGGR"));
        final boolean rec = (videoRecorder != null && videoRecorder.isRecording());

        final int ei = cameraEngine.getEffectiveEi();
        final float underEv = cameraEngine.getUnderexposureEv();
        String hudText = String.format(Locale.US,
            "%s%.0f fps  %.1f ms\n%s  %.0f\u00b0C",
            rec ? "\u25cf REC  " : "", cameraFps, vulkanMs, cfaName, batteryTempC);
        lastUnderEv = underEv;

        mainHandler.post(new UpdateHudTask(hudOverlay, hudText));
    }

    // DngSaver.Callback
    @Override
    public void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
        if (fx != null) fx.shutterClose();
        btnShutter.setEnabled(true);
        rollFrameCounter = (rollFrameCounter % 36) + 1;
        testShotFinished();
        long dngMb = dngBytes / (1024 * 1024);
        long jpgKb = jpgBytes / 1024;
        String msg = (dngBytes > 0)
            ? String.format(Locale.US, "Saved %02d/36 · RAW %dMB + JPG %dKB", rollFrameCounter, dngMb, jpgKb)
            : String.format(Locale.US, "Saved %02d/36 · JPG %dKB", rollFrameCounter, jpgKb);
        if (jpgPath != null && !jpgPath.isEmpty()) setThumbnail(jpgPath);
        showStatus(msg, false);
    }

    @Override
    public void onDngError(String message) {
        if (fx != null) fx.shutterClose();
        btnShutter.setEnabled(true);
        testShotFinished();
        showStatus("Capture failed: " + message, false);
        Toast.makeText(this, "Capture Error: " + message, Toast.LENGTH_LONG).show();
    }

    // CinemaVideoRecorder.Callback
    @Override
    public void onRecordingStarted(String mp4Path, String rangeLabel, String note) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(true);
        recFormatLabel = recFormatLabel + " \u00b7 " + rangeLabel;
        videoPanel.refresh();
        showStatus("\u25cf REC 00:00 \u00b7 " + recFormatLabel, true);
        if (note != null) Toast.makeText(this, note, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRecordingProgress(int elapsedSec, int feetRemaining, int framesRemaining) {
        this.magFeetRemaining = feetRemaining;
        this.magFramesRemaining = framesRemaining;
        int min = elapsedSec / 60;
        int sec = elapsedSec % 60;
        showStatus(String.format(Locale.US, "\u25cf REC %02d:%02d \u00b7 %s \u00b7 %03d ft", min, sec, recFormatLabel, feetRemaining), true);
    }

    @Override
    public void onRecordingSaved(String mp4Path, String gcsvPath, long mp4Bytes, int gyroSamples, String summary) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(false);
        videoPanel.refresh();
        syncLookToNative();
        showStatus(summary, false);
    }

    @Override
    public void onRecordingError(String error) {
        btnRecordVideo.setEnabled(true);
        btnRecordVideo.setSelected(false);
        videoPanel.refresh();
        showStatus("Video error: " + error, false);
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
