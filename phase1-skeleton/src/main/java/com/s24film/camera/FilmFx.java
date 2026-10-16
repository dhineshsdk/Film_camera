package com.s24film.camera;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Mechanical camera sounds (SoundPool) and matching haptics for the film-camera themes. */
final class FilmFx {
    private SoundPool pool;
    private Vibrator vibrator;
    private int sShutterOpen, sShutterClose, sAdvance, sDetent, sIris, sRewind, sClick;
    private boolean enabled = true;

    FilmFx(Context context) {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
            pool = new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build();
            sShutterOpen = pool.load(context, R.raw.shutter_open, 1);
            sShutterClose = pool.load(context, R.raw.shutter_close, 1);
            sAdvance = pool.load(context, R.raw.advance, 1);
            sDetent = pool.load(context, R.raw.detent, 1);
            sIris = pool.load(context, R.raw.iris, 1);
            sRewind = pool.load(context, R.raw.rewind, 1);
            sClick = pool.load(context, R.raw.click, 1);
        } catch (Throwable ignored) {
            pool = null;
        }
        try {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = (vm != null) ? vm.getDefaultVibrator() : null;
        } catch (Throwable ignored) {
            vibrator = null;
        }
    }

    void setEnabled(boolean on) { enabled = on; }

    private void play(int id, float vol) {
        if (pool != null && enabled && id != 0) pool.play(id, vol, vol, 1, 0, 1.0f);
    }

    private void buzz(int effect) {
        try {
            if (vibrator != null && enabled) vibrator.vibrate(VibrationEffect.createPredefined(effect));
        } catch (Throwable ignored) {}
    }

    void shutterOpen() { play(sShutterOpen, 1.0f); buzz(VibrationEffect.EFFECT_HEAVY_CLICK); }
    void shutterClose() { play(sShutterClose, 0.9f); buzz(VibrationEffect.EFFECT_CLICK); }
    void advance() { play(sAdvance, 0.9f); buzz(VibrationEffect.EFFECT_CLICK); }
    void detent() { play(sDetent, 0.8f); buzz(VibrationEffect.EFFECT_TICK); }
    void leverTick() { play(sDetent, 0.5f); buzz(VibrationEffect.EFFECT_TICK); }
    void iris() { play(sIris, 0.8f); }
    void rewind() { play(sRewind, 0.9f); buzz(VibrationEffect.EFFECT_HEAVY_CLICK); }
    void click() { play(sClick, 0.8f); buzz(VibrationEffect.EFFECT_TICK); }
    void dead() { play(sClick, 1.0f); buzz(VibrationEffect.EFFECT_DOUBLE_CLICK); }

    void release() {
        if (pool != null) pool.release();
        pool = null;
    }
}
