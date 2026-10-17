package com.s24film.camera;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class CinemaVideoRecorder {
    private static final String TAG = "S24FilmCinema";
    private static final File STORAGE_DIR = new File("/sdcard/DCIM/S24Film");

    public interface Callback {
        void onRecordingStarted(String mp4Path);
        void onRecordingProgress(int elapsedSec, int feetRemaining, int framesRemaining);
        void onRecordingSaved(String mp4Path, String gcsvPath, long mp4Bytes, int gyroSamples);
        void onRecordingError(String error);
    }

    private final Context context;
    private final ImuTelemetry imuTelemetry;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaCodec videoEncoder;
    private Surface encoderInputSurface;
    private MediaMuxer mediaMuxer;
    private Thread drainThread;

    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private File currentMp4File;
    private File currentGcsvFile;
    private long recordStartMs = 0;

    public CinemaVideoRecorder(Context context, ImuTelemetry imuTelemetry) {
        this.context = context.getApplicationContext();
        this.imuTelemetry = imuTelemetry;
        if (!STORAGE_DIR.exists()) {
            STORAGE_DIR.mkdirs();
        }
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public synchronized boolean startRecording(String stockTag, int width, int height, final Callback callback) {
        if (isRecording.get()) return false;

        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentMp4File = new File(STORAGE_DIR, "VID_" + timeStamp + "_" + stockTag + ".mp4");
            // MediaStore blocks .gcsv under DCIM, so the gyro log lives in the app's own external folder.
            File gyroDir = context.getExternalFilesDir("gyro");
            if (gyroDir != null && !gyroDir.exists()) gyroDir.mkdirs();
            currentGcsvFile = new File(gyroDir != null ? gyroDir : context.getFilesDir(),
                "VID_" + timeStamp + "_" + stockTag + ".gcsv");

            // Ensure 16-pixel aligned dimensions for Exynos Hardware HEVC Encoder (e.g., 1440x1920 3:4 Open Gate)
            int encW = (width > 0 ? width : 1440) & ~15;
            int encH = (height > 0 ? height : 1920) & ~15;

            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, encW, encH);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 48_000_000); // 48 Mbps High-Bitrate Cinema HEVC
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            videoEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            mediaMuxer = new MediaMuxer(currentMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            // Attach encoder Surface to our Vulkan Dual-Swapchain pipeline (zero-copy GPU blit!)
            if (!VulkanRenderer.nativeSetEncoderSurface(encoderInputSurface)) {
                throw new RuntimeException("Failed to attach MediaCodec Surface to Vulkan swapchain");
            }

            // Start 500 Hz LSM6DSVTR Gyroflow .gcsv timed metadata track
            if (imuTelemetry != null) {
                imuTelemetry.startGyroLog(currentGcsvFile);
            }

            CameraEngine.videoHold = true;   // freeze exposure for the take
            isRecording.set(true);
            recordStartMs = System.currentTimeMillis();

            drainThread = new Thread(new DrainTask(this, callback), "CinemaEncoderDrainThread");
            drainThread.start();

            if (callback != null) {
                mainHandler.post(new NotifyStartTask(callback, currentMp4File.getAbsolutePath()));
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Cinema recording: " + t.getMessage(), t);
            cleanupEncoder();
            if (callback != null) {
                mainHandler.post(new NotifyErrorTask(callback, t.getMessage()));
            }
            return false;
        }
    }

    public synchronized void stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return;
        CameraEngine.videoHold = false;
        // Detach Vulkan encoder swapchain first so no new frames are queued
        VulkanRenderer.nativeSetEncoderSurface(null);
        if (drainThread != null) {
            try {
                drainThread.join(2500);
            } catch (InterruptedException ignored) {}
            drainThread = null;
        }
    }

    private void cleanupEncoder() {
        CameraEngine.videoHold = false;
        try {
            VulkanRenderer.nativeSetEncoderSurface(null);
        } catch (Throwable ignored) {}
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Throwable ignored) {}
            try { videoEncoder.release(); } catch (Throwable ignored) {}
            videoEncoder = null;
        }
        if (encoderInputSurface != null) {
            try { encoderInputSurface.release(); } catch (Throwable ignored) {}
            encoderInputSurface = null;
        }
        if (mediaMuxer != null) {
            try { mediaMuxer.stop(); } catch (Throwable ignored) {}
            try { mediaMuxer.release(); } catch (Throwable ignored) {}
            mediaMuxer = null;
        }
    }

    static class NotifyStartTask implements Runnable {
        private final Callback cb;
        private final String path;
        NotifyStartTask(Callback cb, String path) { this.cb = cb; this.path = path; }
        @Override public void run() { cb.onRecordingStarted(path); }
    }

    static class NotifyProgressTask implements Runnable {
        private final Callback cb;
        private final int sec, feet, frames;
        NotifyProgressTask(Callback cb, int sec, int feet, int frames) {
            this.cb = cb; this.sec = sec; this.feet = feet; this.frames = frames;
        }
        @Override public void run() { cb.onRecordingProgress(sec, feet, frames); }
    }

    static class NotifySavedTask implements Runnable {
        private final Callback cb;
        private final String mp4, gcsv;
        private final long bytes;
        private final int samples;
        NotifySavedTask(Callback cb, String mp4, String gcsv, long bytes, int samples) {
            this.cb = cb; this.mp4 = mp4; this.gcsv = gcsv; this.bytes = bytes; this.samples = samples;
        }
        @Override public void run() { cb.onRecordingSaved(mp4, gcsv, bytes, samples); }
    }

    static class NotifyErrorTask implements Runnable {
        private final Callback cb;
        private final String err;
        NotifyErrorTask(Callback cb, String err) { this.cb = cb; this.err = err; }
        @Override public void run() { cb.onRecordingError(err); }
    }

    static class ScanVideoListener implements MediaScannerConnection.OnScanCompletedListener {
        @Override
        public void onScanCompleted(String path, Uri uri) {
            Log.i(TAG, "Indexed Cinema MP4 in Samsung Gallery: " + path + " -> " + uri);
        }
    }

    static class DrainTask implements Runnable {
        private final CinemaVideoRecorder rec;
        private final Callback callback;

        DrainTask(CinemaVideoRecorder rec, Callback callback) {
            this.rec = rec;
            this.callback = callback;
        }

        @Override
        public void run() {
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int trackIndex = -1;
            boolean muxerStarted = false;
            long lastProgressSec = -1;

            try {
                while (rec.isRecording.get()) {
                    int outIndex = rec.videoEncoder.dequeueOutputBuffer(bufferInfo, 20_000);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat newFormat = rec.videoEncoder.getOutputFormat();
                        trackIndex = rec.mediaMuxer.addTrack(newFormat);
                        rec.mediaMuxer.start();
                        muxerStarted = true;
                    } else if (outIndex >= 0) {
                        ByteBuffer encodedData = rec.videoEncoder.getOutputBuffer(outIndex);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        if (bufferInfo.size > 0 && muxerStarted && encodedData != null) {
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            rec.mediaMuxer.writeSampleData(trackIndex, encodedData, bufferInfo);
                        }
                        rec.videoEncoder.releaseOutputBuffer(outIndex, false);
                    }

                    // Update 400ft Cinema Magazine Counter (35mm 4-perf = 16 frames per foot, 24 fps = 1.5 ft/sec)
                    long elapsedMs = System.currentTimeMillis() - rec.recordStartMs;
                    long elapsedSec = elapsedMs / 1000L;
                    if (elapsedSec != lastProgressSec && callback != null) {
                        lastProgressSec = elapsedSec;
                        int totalFramesShot = (int) ((elapsedMs * 24L) / 1000L);
                        int maxFrames400ft = 400 * 16; // 6,400 frames = 4m 26s
                        int remFrames = Math.max(0, maxFrames400ft - totalFramesShot);
                        int remFeet = remFrames / 16;
                        int remSubFrames = remFrames % 16;
                        rec.mainHandler.post(new NotifyProgressTask(callback, (int) elapsedSec, remFeet, remSubFrames));
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Encoder drain error: " + t.getMessage(), t);
            } finally {
                int gyroCount = (rec.imuTelemetry != null) ? rec.imuTelemetry.stopGyroLog() : 0;
                rec.cleanupEncoder();

                long mp4Bytes = (rec.currentMp4File != null && rec.currentMp4File.exists()) ? rec.currentMp4File.length() : 0;
                String mp4Path = (rec.currentMp4File != null) ? rec.currentMp4File.getAbsolutePath() : "";
                String gcsvPath = (rec.currentGcsvFile != null) ? rec.currentGcsvFile.getAbsolutePath() : "";

                if (mp4Bytes > 0 && rec.context != null) {
                    MediaScannerConnection.scanFile(
                        rec.context,
                        new String[]{mp4Path},
                        new String[]{"video/mp4"},
                        new ScanVideoListener()
                    );
                }
                if (callback != null) {
                    rec.mainHandler.post(new NotifySavedTask(callback, mp4Path, gcsvPath, mp4Bytes, gyroCount));
                }
            }
        }
    }
}
