package com.s24film.camera;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Video format choices, persisted in the "ui" preferences. */
final class VideoSettings {
    static final int[] FPS_CHOICES = {24, 25, 30};
    static final float[] ANGLE_CHOICES = {45f, 90f, 144f, 172.8f, 180f, 270f, 360f};
    static final int RES_1440 = 0;   // 1440 x 1920 (3:4)
    static final int RES_4K = 1;     // 2160 x 2880 (3:4)

    int fps = 24;
    float angle = 180f;
    boolean hlg = false;
    int res = RES_1440;
    boolean audio = true;

    static VideoSettings load(Context c) {
        SharedPreferences p = c.getSharedPreferences("ui", 0);
        VideoSettings s = new VideoSettings();
        int fps = p.getInt("video_fps", 24);
        s.fps = (fps == 25 || fps == 30) ? fps : 24;
        float a = p.getFloat("video_angle", 180f);
        s.angle = 180f;
        for (float choice : ANGLE_CHOICES) {
            if (Math.abs(choice - a) < 0.05f) s.angle = choice;
        }
        s.hlg = p.getBoolean("video_hlg", false);
        s.res = (p.getInt("video_res", RES_1440) == RES_4K) ? RES_4K : RES_1440;
        s.audio = p.getBoolean("video_audio", true);
        return s;
    }

    void save(Context c) {
        c.getSharedPreferences("ui", 0).edit()
            .putInt("video_fps", fps).putFloat("video_angle", angle)
            .putBoolean("video_hlg", hlg).putInt("video_res", res)
            .putBoolean("video_audio", audio).apply();
    }

    VideoSettings copy() {
        VideoSettings s = new VideoSettings();
        s.fps = fps; s.angle = angle; s.hlg = hlg; s.res = res; s.audio = audio;
        return s;
    }

    long frameDurationNs() { return 1_000_000_000L / fps; }

    long shutterNs() { return (long) (angle / 360.0 * frameDurationNs()); }

    int width() { return res == RES_4K ? 2160 : 1440; }
    int height() { return res == RES_4K ? 2880 : 1920; }

    static String angleLabel(float a) {
        return (a == Math.rint(a)) ? String.format(Locale.US, "%d°", (int) a)
            : String.format(Locale.US, "%.1f°", a);
    }
}
