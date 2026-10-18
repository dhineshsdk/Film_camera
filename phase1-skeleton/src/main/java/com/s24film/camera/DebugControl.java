package com.s24film.camera;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

/**
 * adb test hooks, so captures can be scripted without touching the screen:
 *   adb shell am broadcast -a com.s24film.camera.DEBUG --es cmd capture
 *   ... --es cmd lens --es id 0|2|6|1        (1x, 0.6x, 3x, front)
 *   ... --es cmd look --ei idx 24             (kMasterLooks120 index)
 *   ... --es cmd format --ei idx 0..3         (35mm, 16mm, Super 8, 6x7)
 *   ... --es cmd dng --ez on true|false       (keep the RAW DNG)
 *   ... --es cmd depth --ez on true|false     (depth bokeh on masters at f/5.6 and wider; logs S24FilmDepth)
 * Only acts while the app is running; the shell (adb) is the only sender that can reach it
 * besides the app itself because the action is unique to this package.
 */
final class DebugControl extends BroadcastReceiver {
    static final String ACTION = "com.s24film.camera.DEBUG";
    private static final String TAG = "S24FilmDebug";

    static volatile MainActivity ui;   // set by MainActivity while resumed

    static class ThemeTask implements Runnable {
        private final MainActivity a;
        private final int t;
        ThemeTask(MainActivity a, int t) { this.a = a; this.t = t; }
        @Override public void run() { a.debugTheme(t); }
    }

    private final CameraEngine engine;
    private int lookIdx = 24;

    // Named class: the build's d8 step cannot take anonymous "$1" class files.
    static class LogCallback implements DngSaver.Callback {
        @Override public void onCaptureSaved(String dngPath, String jpgPath, long dngBytes, long jpgBytes) {
            Log.i(TAG, "saved " + jpgPath + " (" + jpgBytes + " B) dng " + dngPath);
        }

        @Override public void onDngError(String message) {
            Log.e(TAG, "save failed: " + message);
        }
    }

    DebugControl(CameraEngine engine) {
        this.engine = engine;
    }

    void register(Context context) {
        context.registerReceiver(this, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) return;
        Log.i(TAG, "cmd " + cmd);
        switch (cmd) {
            case "capture":
                engine.triggerCapture("look" + lookIdx, new LogCallback());
                break;
            case "lens":
                String id = intent.getStringExtra("id");
                if (id != null) engine.openCamera(id);
                break;
            case "look":
                lookIdx = intent.getIntExtra("idx", lookIdx);
                VulkanRenderer.nativeSetMasterLook(lookIdx);
                break;
            case "format":
                VulkanRenderer.nativeSetFilmFormat(intent.getIntExtra("idx", 0));
                break;
            case "push":
                engine.setPushStops(intent.getIntExtra("stops", 0));
                break;
            case "autogain":
                engine.setAutoGain(intent.getBooleanExtra("on", false));
                break;
            case "filmiso":
                engine.setFilmIsoMode(intent.getBooleanExtra("on", true));
                break;
            case "optic":   // UI lens character 0..5 (Clean, Helios, Summilux, Takumar, Panavision, Super 70)
                VulkanRenderer.nativeSetFilmLook(1, intent.getIntExtra("idx", 0), true);
                break;
            case "lensfx":
                VulkanRenderer.nativeSetLensFxEnabled(intent.getBooleanExtra("on", false));
                break;
            case "shutter":
                engine.setManualShutterNs(intent.getLongExtra("ns", 0L));
                break;
            case "autoslow":   // slowest Auto shutter, ns (33333333 = 1/30 default)
                engine.setFilmMaxAutoShutterNs(intent.getLongExtra("ns", 33_333_333L));
                break;
            case "fpslock":
                engine.setSlowFpsAlways(intent.getBooleanExtra("on", false));
                break;
            case "aperture":
                engine.setVirtualAperture(intent.getFloatExtra("f", 2.0f));
                break;
            case "depth":   // depth bokeh (f/5.6 and wider masters): --ez on true|false
                DepthBokeh.enabled = intent.getBooleanExtra("on", true);
                Log.i(TAG, "depth bokeh " + DepthBokeh.enabled);
                break;
            case "status":
                Log.i(TAG, "look " + VulkanRenderer.nativeGetActiveLookIndex() + " EI " + engine.getEffectiveEi() + " push " + engine.getPushStops()
                    + " under " + engine.getUnderexposureEv() + " EV autoGain " + engine.isAutoGain()
                    + " shutter " + (engine.getCurrentShutterNs() / 1000) + " us meter " + engine.getMeterEv()
                    + " f/" + engine.getVirtualAperture()
                    + " grip " + engine.currentActiveOrient() + " ROT +" + engine.getManualRotationOffset());
                break;
            case "theme":   // 0 modern, 1 black SLR, 2 silver rangefinder
                if (ui != null) ui.runOnUiThread(new ThemeTask(ui, intent.getIntExtra("idx", 0)));
                break;
            case "dng":
                DngSaver.setSaveDng(intent.getBooleanExtra("on", false));
                break;
            default:
                Log.w(TAG, "unknown cmd " + cmd);
        }
    }
}
