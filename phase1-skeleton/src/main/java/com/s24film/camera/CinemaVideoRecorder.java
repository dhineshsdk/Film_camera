package com.s24film.camera;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class CinemaVideoRecorder {
    private static final String TAG = "S24FilmCinema";
    private static final String SOAK_TAG = "S24FilmSoak";
    private static final File STORAGE_DIR = new File("/sdcard/DCIM/S24Film");
    private static final String HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    private static final int AUDIO_RATE = 48000;
    private static final int AUDIO_CHANNELS = 2;
    private static final long EOS_TIMEOUT_MS = 1500;
    private static final int THERMAL_STOP = 3;   // PowerManager.THERMAL_STATUS_SEVERE (API 29)

    public interface Callback {
        /** rangeLabel is "HLG" or "SDR"; note is null or a short remark (fallbacks, audio off). */
        void onRecordingStarted(String mp4Path, String rangeLabel, String note);
        void onRecordingProgress(int elapsedSec, int feetRemaining, int framesRemaining);
        /** summary is e.g. "Saved 312 MB · 23.9 fps · 0 dropped · HLG". */
        void onRecordingSaved(String mp4Path, String gcsvPath, long mp4Bytes, int gyroSamples, String summary);
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
    private final AtomicBoolean busy = new AtomicBoolean(false);   // recording or still draining/saving
    private File currentMp4File;
    private File currentGcsvFile;
    private long recordStartMs = 0;

    // Per-take settings and state
    private int fps = 24;
    private boolean hlgActive = false;
    private String encoderInfo = "";
    private volatile String stopReason = null;
    private String audioNote = null;

    // Muxer coordination: video and audio threads both write, the muxer starts once all tracks exist.
    private final Object muxLock = new Object();
    private int videoTrack = -1;
    private int audioTrack = -1;
    private boolean muxerStarted = false;
    private boolean audioWanted = false;
    private long videoFormatAtMs = 0;
    private long videoFirstPtsUs = -1;
    private final ArrayList<PendingSample> pendingVideo = new ArrayList<>();

    // Audio
    private AudioRecord audioRecord;
    private MediaCodec audioEncoder;
    private Thread audioThread;
    private volatile boolean audioStop = false;

    // Soak stats (video drain thread only)
    private long statFrames = 0;
    private long statFirstPtsUs = -1;
    private long statLastPtsUs = -1;
    private long statMaxGapUs = 0;
    private long statDrops = 0;
    private long statBytes = 0;
    private PrintWriter soakCsv;

    static final class PendingSample {
        final byte[] data;
        final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        PendingSample(byte[] data, MediaCodec.BufferInfo bi) {
            this.data = data;
            info.set(0, data.length, bi.presentationTimeUs, bi.flags);
        }
    }

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

    private static MediaCodecInfo findHevcEncoder() {
        MediaCodecInfo fallback = null;
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) continue;
            boolean has = false;
            for (String t : info.getSupportedTypes()) {
                if (t.equalsIgnoreCase(HEVC)) { has = true; break; }
            }
            if (!has) continue;
            String n = info.getName();
            if (!n.startsWith("c2.android.") && !n.startsWith("OMX.google.")) return info;
            if (fallback == null) fallback = info;
        }
        return fallback;
    }

    private static boolean supportsMain10(MediaCodecInfo.CodecCapabilities caps) {
        if (caps == null || caps.profileLevels == null) return false;
        for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) {
            if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) return true;
        }
        return false;
    }

    private static boolean sizeOk(MediaCodecInfo.CodecCapabilities caps, int w, int h, int fps) {
        try {
            MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();
            return vc != null && vc.areSizeAndRateSupported(w, h, fps);
        } catch (Throwable t) {
            return false;
        }
    }

    // mode 0 = plain (no colour keys), 1 = SDR BT.709 limited, 2 = HLG Main10 BT.2020 limited.
    private static MediaFormat buildFormat(int w, int h, int fps, int bitrate, int mode) {
        MediaFormat f = MediaFormat.createVideoFormat(HEVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (mode == 2) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        } else if (mode == 1) {
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        }
        return f;
    }

    /**
     * orientationHintDeg: clockwise rotation (0/90/180/270) players apply to the portrait frames;
     * pass the same grip value the still path uses so a photo and a take in one pose agree.
     */
    public synchronized boolean startRecording(String stockTag, VideoSettings s, int orientationHintDeg, final Callback callback) {
        if (!busy.compareAndSet(false, true)) return false;

        try {
            fps = s.fps;
            stopReason = null;
            audioNote = null;
            resetTakeState();
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            currentMp4File = new File(STORAGE_DIR, "VID_" + timeStamp + "_" + stockTag + ".mp4");
            // MediaStore blocks .gcsv under DCIM, so the gyro log lives in the app's own external folder.
            File gyroDir = context.getExternalFilesDir("gyro");
            if (gyroDir != null && !gyroDir.exists()) gyroDir.mkdirs();
            currentGcsvFile = new File(gyroDir != null ? gyroDir : context.getFilesDir(),
                "VID_" + timeStamp + "_" + stockTag + ".gcsv");
            File soakDir = context.getExternalFilesDir("soak");
            if (soakDir != null && !soakDir.exists()) soakDir.mkdirs();
            try {
                if (soakDir != null) {
                    soakCsv = new PrintWriter(new FileWriter(new File(soakDir, "VID_" + timeStamp + "_soak.csv")));
                    soakCsv.println("elapsed_s,frames,fps,drops,max_gap_ms,thermal,mb_written");
                    soakCsv.flush();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Soak CSV unavailable: " + t.getMessage());
                soakCsv = null;
            }

            MediaCodecInfo codecInfo = findHevcEncoder();
            if (codecInfo == null) throw new RuntimeException("No HEVC encoder on this device");
            MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(HEVC);

            // Ensure 16-pixel aligned dimensions for the Exynos hardware HEVC encoder.
            int encW = s.width() & ~15;
            int encH = s.height() & ~15;
            int bitrate = (s.res == VideoSettings.RES_4K) ? 100_000_000 : 48_000_000;
            String note = null;
            if (s.res == VideoSettings.RES_4K && !sizeOk(caps, encW, encH, fps)) {
                encW = 1440;
                encH = 1920;
                bitrate = 48_000_000;
                note = "4K not supported, using 1440p";
                Log.w(TAG, note);
            }

            boolean wantHlg = s.hlg && supportsMain10(caps);
            if (s.hlg && !wantHlg) note = append(note, "HLG not supported, using SDR");
            int mode = wantHlg ? 2 : 1;
            hlgActive = wantHlg;
            videoEncoder = MediaCodec.createByCodecName(codecInfo.getName());
            try {
                videoEncoder.configure(buildFormat(encW, encH, fps, bitrate, mode), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable t) {
                Log.w(TAG, "Encoder rejected colour keys, retrying plain SDR: " + t.getMessage());
                try { videoEncoder.release(); } catch (Throwable ignored) {}
                videoEncoder = MediaCodec.createByCodecName(codecInfo.getName());
                videoEncoder.configure(buildFormat(encW, encH, fps, bitrate, 0), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                if (hlgActive) note = append(note, "HLG rejected by encoder, using SDR");
                hlgActive = false;
            }
            encoderInputSurface = videoEncoder.createInputSurface();
            videoEncoder.start();

            mediaMuxer = new MediaMuxer(currentMp4File.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int hint = (((orientationHintDeg % 360) + 360) % 360 / 90) * 90;
            mediaMuxer.setOrientationHint(hint);

            // Audio is optional: any failure leaves a silent take.
            audioWanted = false;
            if (s.audio) {
                String why = initAudio();
                if (why == null) {
                    audioWanted = true;
                } else {
                    audioNote = why;
                    note = append(note, why);
                }
            }

            // Attach encoder Surface to our Vulkan Dual-Swapchain pipeline (zero-copy GPU blit!)
            try {
                VulkanRenderer.nativeSetEncoderHlg(hlgActive);
            } catch (Throwable t) {
                Log.w(TAG, "nativeSetEncoderHlg unavailable: " + t.getMessage());
            }
            if (!VulkanRenderer.nativeSetEncoderSurface(encoderInputSurface)) {
                throw new RuntimeException("Failed to attach MediaCodec Surface to Vulkan swapchain");
            }
            try {
                encoderInfo = VulkanRenderer.nativeGetEncoderInfo();
            } catch (Throwable t) {
                encoderInfo = "";
            }
            Log.i(TAG, "Recording " + encW + "x" + encH + " @" + fps + " fps, " + (bitrate / 1_000_000) + " Mbps, hint "
                + hint + ", renderer: " + encoderInfo + ", audio " + audioWanted);

            // Start 500 Hz LSM6DSVTR Gyroflow .gcsv timed metadata track
            if (imuTelemetry != null) {
                imuTelemetry.startGyroLog(currentGcsvFile);
            }

            if (audioWanted) {
                audioStop = false;
                audioRecord.startRecording();
                audioThread = new Thread(new AudioTask(this), "CinemaAudioThread");
                audioThread.start();
            }

            CameraEngine.videoHold = true;   // freeze exposure for the take
            isRecording.set(true);
            recordStartMs = System.currentTimeMillis();

            drainThread = new Thread(new DrainTask(this, callback), "CinemaEncoderDrainThread");
            drainThread.start();

            if (callback != null) {
                mainHandler.post(new NotifyStartTask(callback, currentMp4File.getAbsolutePath(),
                    hlgActive ? "HLG" : "SDR", note));
            }
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start Cinema recording: " + t.getMessage(), t);
            cleanupEncoder();
            busy.set(false);
            if (callback != null) {
                mainHandler.post(new NotifyErrorTask(callback, String.valueOf(t.getMessage())));
            }
            return false;
        }
    }

    private static String append(String a, String b) { return a == null ? b : a + "; " + b; }

    private void resetTakeState() {
        videoTrack = -1;
        audioTrack = -1;
        muxerStarted = false;
        audioWanted = false;
        videoFirstPtsUs = -1;
        pendingVideo.clear();
        statFrames = 0;
        statFirstPtsUs = -1;
        statLastPtsUs = -1;
        statMaxGapUs = 0;
        statDrops = 0;
        statBytes = 0;
    }

    /** Returns null on success, else a short reason the take will be silent. */
    private String initAudio() {
        try {
            if (Build.VERSION.SDK_INT >= 23
                && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return "Audio off: no microphone permission";
            }
            int minBuf = AudioRecord.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) return "Audio off: stereo 48 kHz unavailable";
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, AUDIO_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 4, AUDIO_RATE * 2));
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                try { audioRecord.release(); } catch (Throwable ignored) {}
                audioRecord = null;
                return "Audio off: microphone busy";
            }
            MediaFormat af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_RATE, AUDIO_CHANNELS);
            af.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            af.setInteger(MediaFormat.KEY_BIT_RATE, 256_000);
            af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            audioEncoder.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audioEncoder.start();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "Audio init failed: " + t.getMessage(), t);
            releaseAudio();
            return "Audio off: " + t.getMessage();
        }
    }

    private void releaseAudio() {
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Throwable ignored) {}
            try { audioRecord.release(); } catch (Throwable ignored) {}
            audioRecord = null;
        }
        if (audioEncoder != null) {
            try { audioEncoder.stop(); } catch (Throwable ignored) {}
            try { audioEncoder.release(); } catch (Throwable ignored) {}
            audioEncoder = null;
        }
    }

    /**
     * Ends the take. Returns at once; the drain thread finishes the file and reports through
     * onRecordingSaved. Order: detach the Vulkan surface, signal end of input, drain to EOS.
     */
    public synchronized void stopRecording() {
        if (!isRecording.compareAndSet(true, false)) return;
        CameraEngine.videoHold = false;
        // Detach Vulkan encoder swapchain first so no new frames are queued
        try { VulkanRenderer.nativeSetEncoderSurface(null); } catch (Throwable t) { Log.w(TAG, "detach: " + t.getMessage()); }
        try {
            if (videoEncoder != null) videoEncoder.signalEndOfInputStream();
        } catch (Throwable t) {
            Log.w(TAG, "signalEndOfInputStream: " + t.getMessage());
        }
    }

    private void cleanupEncoder() {
        CameraEngine.videoHold = false;
        try {
            VulkanRenderer.nativeSetEncoderSurface(null);
        } catch (Throwable ignored) {}
        audioStop = true;
        releaseAudio();
        if (videoEncoder != null) {
            try { videoEncoder.stop(); } catch (Throwable ignored) {}
            try { videoEncoder.release(); } catch (Throwable ignored) {}
            videoEncoder = null;
        }
        if (encoderInputSurface != null) {
            try { encoderInputSurface.release(); } catch (Throwable ignored) {}
            encoderInputSurface = null;
        }
        synchronized (muxLock) {
            if (mediaMuxer != null) {
                try { if (muxerStarted) mediaMuxer.stop(); } catch (Throwable ignored) {}
                try { mediaMuxer.release(); } catch (Throwable ignored) {}
                mediaMuxer = null;
            }
            muxerStarted = false;
            pendingVideo.clear();
        }
        if (soakCsv != null) {
            try { soakCsv.close(); } catch (Throwable ignored) {}
            soakCsv = null;
        }
    }

    // ------------------------------------------------------------------ muxer plumbing
    private void muxAddVideoTrack(MediaFormat f) {
        synchronized (muxLock) {
            if (mediaMuxer == null || videoTrack >= 0) return;
            videoTrack = mediaMuxer.addTrack(f);
            videoFormatAtMs = System.currentTimeMillis();
            maybeStartMuxerLocked();
        }
    }

    private void muxAddAudioTrack(MediaFormat f) {
        synchronized (muxLock) {
            if (mediaMuxer == null || muxerStarted || audioTrack >= 0 || !audioWanted) return;
            audioTrack = mediaMuxer.addTrack(f);
            maybeStartMuxerLocked();
        }
    }

    private void maybeStartMuxerLocked() {
        if (muxerStarted || mediaMuxer == null || videoTrack < 0) return;
        if (audioWanted && audioTrack < 0) return;
        mediaMuxer.start();
        muxerStarted = true;
        for (PendingSample p : pendingVideo) {
            try {
                mediaMuxer.writeSampleData(videoTrack, ByteBuffer.wrap(p.data), p.info);
            } catch (Throwable t) {
                Log.w(TAG, "pending write: " + t.getMessage());
            }
        }
        pendingVideo.clear();
    }

    private void muxWriteVideo(ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (mediaMuxer == null) return;
            if (videoFirstPtsUs < 0) videoFirstPtsUs = info.presentationTimeUs;
            if (!muxerStarted && audioWanted && audioTrack < 0
                && System.currentTimeMillis() - videoFormatAtMs > 1500) {
                // The audio encoder never produced a format: go on without sound.
                audioWanted = false;
                audioNote = "Audio off: encoder produced nothing";
                maybeStartMuxerLocked();
            }
            if (muxerStarted) {
                data.limit(info.offset + info.size);
                data.position(info.offset);
                mediaMuxer.writeSampleData(videoTrack, data, info);
            } else if (pendingVideo.size() < 120) {
                byte[] copy = new byte[info.size];
                data.limit(info.offset + info.size);
                data.position(info.offset);
                data.get(copy);
                pendingVideo.add(new PendingSample(copy, info));
            }
        }
    }

    private void muxWriteAudio(ByteBuffer data, MediaCodec.BufferInfo info) {
        synchronized (muxLock) {
            if (mediaMuxer == null || !muxerStarted || audioTrack < 0) return;   // before start: dropped
            if (info.presentationTimeUs < videoFirstPtsUs) return;
            data.limit(info.offset + info.size);
            data.position(info.offset);
            mediaMuxer.writeSampleData(audioTrack, data, info);
        }
    }

    static class NotifyStartTask implements Runnable {
        private final Callback cb;
        private final String path, range, note;
        NotifyStartTask(Callback cb, String path, String range, String note) {
            this.cb = cb; this.path = path; this.range = range; this.note = note;
        }
        @Override public void run() { cb.onRecordingStarted(path, range, note); }
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
        private final String mp4, gcsv, summary;
        private final long bytes;
        private final int samples;
        NotifySavedTask(Callback cb, String mp4, String gcsv, long bytes, int samples, String summary) {
            this.cb = cb; this.mp4 = mp4; this.gcsv = gcsv; this.bytes = bytes; this.samples = samples; this.summary = summary;
        }
        @Override public void run() { cb.onRecordingSaved(mp4, gcsv, bytes, samples, summary); }
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

    /** Reads the microphone, feeds the AAC encoder and drains it into the muxer. */
    static class AudioTask implements Runnable {
        private final CinemaVideoRecorder rec;
        AudioTask(CinemaVideoRecorder rec) { this.rec = rec; }

        @Override
        public void run() {
            AudioRecord ar = rec.audioRecord;
            MediaCodec enc = rec.audioEncoder;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] chunk = new byte[4096];
            long baseUs = -1;
            long framesDone = 0;
            try {
                while (!rec.audioStop) {
                    int n = ar.read(chunk, 0, chunk.length);
                    if (n <= 0) {
                        if (n < 0) { Log.w(TAG, "AudioRecord.read error " + n); break; }
                        continue;
                    }
                    int frames = n / (2 * AUDIO_CHANNELS);
                    long nowUs = System.nanoTime() / 1000L;
                    long chunkUs = frames * 1_000_000L / AUDIO_RATE;
                    // Running PTS from the sample count, re-anchored to the monotonic clock if it drifts.
                    long wallStart = nowUs - chunkUs;
                    if (baseUs < 0) baseUs = wallStart - framesDone * 1_000_000L / AUDIO_RATE;
                    long pts = baseUs + framesDone * 1_000_000L / AUDIO_RATE;
                    if (Math.abs(pts - wallStart) > 50_000L) {
                        baseUs = wallStart - framesDone * 1_000_000L / AUDIO_RATE;
                        pts = wallStart;
                    }
                    framesDone += frames;
                    int off = 0;
                    while (off < n && !rec.audioStop) {
                        int idx = enc.dequeueInputBuffer(10_000);
                        if (idx >= 0) {
                            ByteBuffer in = enc.getInputBuffer(idx);
                            in.clear();
                            int len = Math.min(n - off, in.remaining());
                            in.put(chunk, off, len);
                            long p = pts + (off / (2 * AUDIO_CHANNELS)) * 1_000_000L / AUDIO_RATE;
                            enc.queueInputBuffer(idx, 0, len, p, 0);
                            off += len;
                        }
                        drain(enc, info, false);
                    }
                    drain(enc, info, false);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Audio thread error: " + t.getMessage(), t);
            }
            // End of stream: queue EOS and drain what is left.
            try {
                long deadline = System.currentTimeMillis() + 1000;
                boolean queued = false;
                while (!queued && System.currentTimeMillis() < deadline) {
                    int idx = enc.dequeueInputBuffer(10_000);
                    if (idx >= 0) {
                        enc.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        queued = true;
                    } else {
                        drain(enc, info, false);
                    }
                }
                while (System.currentTimeMillis() < deadline) {
                    if (drain(enc, info, true)) break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Audio EOS: " + t.getMessage());
            }
        }

        // Returns true once the end-of-stream buffer was seen.
        private boolean drain(MediaCodec enc, MediaCodec.BufferInfo info, boolean wait) {
            while (true) {
                int out = enc.dequeueOutputBuffer(info, wait ? 20_000 : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rec.muxAddAudioTrack(enc.getOutputFormat());
                } else if (out >= 0) {
                    ByteBuffer data = enc.getOutputBuffer(out);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && data != null) {
                        try {
                            rec.muxWriteAudio(data, info);
                        } catch (Throwable t) {
                            Log.w(TAG, "audio write: " + t.getMessage());
                        }
                    }
                    enc.releaseOutputBuffer(out, false);
                    if (eos) return true;
                } else {
                    return false;   // INFO_TRY_AGAIN_LATER or buffers changed
                }
            }
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
            long lastProgressSec = -1;
            long lastSoakSec = 0;
            long deadline = 0;
            boolean eos = false;
            double periodUs = 1_000_000.0 / rec.fps;

            try {
                // Keep draining until the encoder reports EOS (or a timeout after stop): an
                // undrained encoder stalls the HAL.
                while (!eos) {
                    if (!rec.isRecording.get()) {
                        if (deadline == 0) deadline = System.currentTimeMillis() + EOS_TIMEOUT_MS;
                        else if (System.currentTimeMillis() > deadline) {
                            Log.w(TAG, "EOS not seen within timeout, closing anyway");
                            break;
                        }
                    }
                    int outIndex = rec.videoEncoder.dequeueOutputBuffer(bufferInfo, 20_000);
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rec.muxAddVideoTrack(rec.videoEncoder.getOutputFormat());
                    } else if (outIndex >= 0) {
                        ByteBuffer encodedData = rec.videoEncoder.getOutputBuffer(outIndex);
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) eos = true;
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                        }
                        if (bufferInfo.size > 0 && encodedData != null) {
                            long size = bufferInfo.size;
                            long pts = bufferInfo.presentationTimeUs;
                            try {
                                rec.muxWriteVideo(encodedData, bufferInfo);
                            } catch (Throwable t) {
                                Log.w(TAG, "video write: " + t.getMessage());
                            }
                            rec.statBytes += size;
                            rec.statFrames++;
                            if (rec.statFirstPtsUs < 0) rec.statFirstPtsUs = pts;
                            if (rec.statLastPtsUs >= 0) {
                                long gap = pts - rec.statLastPtsUs;
                                if (gap > rec.statMaxGapUs) rec.statMaxGapUs = gap;
                                if (gap > 1.5 * periodUs) rec.statDrops += Math.round(gap / periodUs) - 1;
                            }
                            rec.statLastPtsUs = pts;
                        }
                        rec.videoEncoder.releaseOutputBuffer(outIndex, false);
                    }

                    long elapsedMs = System.currentTimeMillis() - rec.recordStartMs;
                    long elapsedSec = elapsedMs / 1000L;
                    if (elapsedSec != lastProgressSec) {
                        lastProgressSec = elapsedSec;
                        // 400 ft magazine: 35mm 4-perf is 16 frames per foot, counted at the chosen frame rate.
                        int totalFramesShot = (int) ((elapsedMs * rec.fps) / 1000L);
                        int maxFrames400ft = 400 * 16;
                        int remFrames = Math.max(0, maxFrames400ft - totalFramesShot);
                        if (callback != null) {
                            rec.mainHandler.post(new NotifyProgressTask(callback, (int) elapsedSec, remFrames / 16, remFrames % 16));
                        }
                        if (rec.isRecording.get() && rec.thermalStatus() >= THERMAL_STOP) {
                            rec.stopReason = "Stopped: phone too hot";
                            Log.w(SOAK_TAG, "Thermal severe, stopping take");
                            rec.stopRecording();
                        }
                        if (elapsedSec - lastSoakSec >= 10) {
                            lastSoakSec = elapsedSec;
                            rec.logSoak(elapsedSec);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "Encoder drain error: " + t.getMessage(), t);
            } finally {
                // Make sure input is closed even on an error path, then stop the audio side.
                if (rec.isRecording.compareAndSet(true, false)) {
                    CameraEngine.videoHold = false;
                    try { VulkanRenderer.nativeSetEncoderSurface(null); } catch (Throwable ignored) {}
                }
                rec.audioStop = true;
                if (rec.audioThread != null) {
                    try { rec.audioThread.join(2500); } catch (InterruptedException ignored) {}
                    rec.audioThread = null;
                }
                rec.logSoak((System.currentTimeMillis() - rec.recordStartMs) / 1000L);
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
                String summary = rec.buildSummary(mp4Bytes);
                rec.busy.set(false);
                if (callback != null) {
                    rec.mainHandler.post(new NotifySavedTask(callback, mp4Path, gcsvPath, mp4Bytes, gyroCount, summary));
                }
            }
        }
    }

    // ------------------------------------------------------------------ stats
    private int thermalStatus() {
        if (Build.VERSION.SDK_INT < 29) return 0;
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null ? pm.getCurrentThermalStatus() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private double achievedFps() {
        if (statFrames < 2 || statLastPtsUs <= statFirstPtsUs) return 0.0;
        return (statFrames - 1) * 1_000_000.0 / (statLastPtsUs - statFirstPtsUs);
    }

    private void logSoak(long elapsedSec) {
        String line = String.format(Locale.US, "%d,%d,%.2f,%d,%.1f,%d,%.1f",
            elapsedSec, statFrames, achievedFps(), statDrops, statMaxGapUs / 1000.0,
            thermalStatus(), statBytes / (1024.0 * 1024.0));
        Log.i(SOAK_TAG, line);
        if (soakCsv != null) {
            soakCsv.println(line);
            soakCsv.flush();
        }
    }

    private String buildSummary(long mp4Bytes) {
        String s = String.format(Locale.US, "Saved %.0f MB · %.1f fps · %d dropped · %s",
            mp4Bytes / (1024.0 * 1024.0), achievedFps(), statDrops, hlgActive ? "HLG" : "SDR");
        if (stopReason != null) s += " · " + stopReason;
        return s;
    }
}
