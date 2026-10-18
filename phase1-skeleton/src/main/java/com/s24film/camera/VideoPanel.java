package com.s24film.camera;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;

/** VIDEO section of the settings sheet: frame rate, shutter angle, range, size and audio. */
final class VideoPanel {
    interface Host { boolean isRecording(); }

    private final Activity act;
    private final CameraEngine engine;
    private final Host host;
    final VideoSettings settings;
    private final Button[] fpsBtns = new Button[VideoSettings.FPS_CHOICES.length];
    private final Button[] angleBtns = new Button[VideoSettings.ANGLE_CHOICES.length];
    private final Button[] rangeBtns = new Button[2];
    private final Button[] sizeBtns = new Button[2];
    private final Button[] audioBtns = new Button[2];

    VideoPanel(Activity a, CameraEngine e, Host h) {
        act = a; engine = e; host = h;
        settings = VideoSettings.load(a);
        build(R.id.row_video_fps, fpsBtns, 0, new String[] {"24", "25", "30"});
        String[] angles = new String[angleBtns.length];
        for (int i = 0; i < angles.length; i++) angles[i] = VideoSettings.angleLabel(VideoSettings.ANGLE_CHOICES[i]);
        build(R.id.row_video_angle, angleBtns, 1, angles);
        build(R.id.row_video_range, rangeBtns, 2, new String[] {"SDR", "HLG 10-bit"});
        build(R.id.row_video_size, sizeBtns, 3, new String[] {"1440p", "4K"});
        build(R.id.row_video_audio, audioBtns, 4, new String[] {"On", "Off"});
        refresh();
        apply();
    }

    private void build(int rowId, Button[] out, int group, String[] labels) {
        LinearLayout row = act.findViewById(rowId);
        for (int i = 0; i < out.length; i++) {
            Button b = (Button) act.getLayoutInflater().inflate(R.layout.chip_button, row, false);
            b.setText(labels[i]);
            b.setOnClickListener(new Click(this, group, i));
            row.addView(b);
            out[i] = b;
        }
    }

    /** Pushes the current fps / shutter angle to the engine. */
    void apply() { engine.setVideoFormat(settings.fps, settings.angle); }

    /** Re-reads the chip states, e.g. when a take starts (chips go disabled) or ends. */
    void refresh() {
        boolean rec = host.isRecording();
        for (int i = 0; i < fpsBtns.length; i++)
            mark(fpsBtns[i], settings.fps == VideoSettings.FPS_CHOICES[i], rec);
        for (int i = 0; i < angleBtns.length; i++)
            mark(angleBtns[i], Math.abs(settings.angle - VideoSettings.ANGLE_CHOICES[i]) < 0.05f, rec);
        mark(rangeBtns[0], !settings.hlg, rec);  mark(rangeBtns[1], settings.hlg, rec);
        mark(sizeBtns[0], settings.res == VideoSettings.RES_1440, rec);
        mark(sizeBtns[1], settings.res == VideoSettings.RES_4K, rec);
        mark(audioBtns[0], settings.audio, rec);  mark(audioBtns[1], !settings.audio, rec);
    }

    private static void mark(Button b, boolean selected, boolean disabled) {
        b.setSelected(selected);
        b.setEnabled(!disabled);
    }

    private void onChip(int group, int idx) {
        if (host.isRecording()) return;
        if (group == 0) settings.fps = VideoSettings.FPS_CHOICES[idx];
        else if (group == 1) settings.angle = VideoSettings.ANGLE_CHOICES[idx];
        else if (group == 2) settings.hlg = (idx == 1);
        else if (group == 3) settings.res = idx;
        else settings.audio = (idx == 0);
        settings.save(act);
        refresh();
        apply();
    }

    static final class Click implements View.OnClickListener {
        private final VideoPanel p;
        private final int group, idx;
        Click(VideoPanel p, int group, int idx) { this.p = p; this.group = group; this.idx = idx; }
        @Override public void onClick(View v) { p.onChip(group, idx); }
    }
}
