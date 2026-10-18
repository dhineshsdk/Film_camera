package com.s24film.camera;

import android.content.res.AssetManager;
import android.hardware.HardwareBuffer;
import android.view.Surface;

public class VulkanRenderer {
    static {
        System.loadLibrary("s24film_vulkan");
    }

    // assetManager supplies assets/s24_master_120_looks.bin (256 x 120 RGBA32F LUT).
    public static native boolean nativeInit(Surface surface, AssetManager assetManager);
    public static native void nativeDestroy();
    public static native void nativeSetFilmLook(int stockIndex, int lensIndex, boolean burstEnabled);
    // lookIndex 0..119 selects kMasterLooks120; -1 keeps the legacy GeneratedFilmDatabase stock.
    public static native void nativeSetMasterLook(int lookIndex);
    public static native int nativeGetLookCount();
    public static native String nativeGetLookName(int lookIndex);
    public static native String nativeGetLookTag(int lookIndex);
    public static native void nativeSetDarkroomSliders(float evStops, float grainMult, float halationMult);
    public static native void nativeSetCinemaParams(int anamorphicMode, int cinemaGate, boolean gateWeave, boolean stepPrint8fps);
    // noiseS / noiseO: SENSOR_NOISE_PROFILE (normalized), pass 0 to use the lab model.
    public static native void nativeSetCaptureState(int iso, long exposureNs, float focusDiopters,
                                                    float noiseS, float noiseO, float blackLevel);
    // Row-major CCT-interpolated ForwardMatrix (camera WB RGB -> XYZ D50); null = built-in table.
    public static native void nativeSetForwardMatrix(float[] forwardMatrix);
    // 0 = 35mm, 1 = 16mm, 2 = Super 8, 3 = 6x7 medium format: grain, resolution and halation scale.
    public static native void nativeSetFilmFormat(int formatIndex);
    // Box speed (ISO) of a look; 0 for digital profiles / Fujifilm sims / print stocks.
    public static native int nativeGetLookBoxIso(int lookIndex);
    public static native int nativeGetActiveLookIndex();
    // digitalGain = exposure index / sensor ISO; pushStops: +1..+3 push, -1..-2 pull.
    public static native void nativeSetFilmExposure(float digitalGain, float pushStops);
    // Aperture ring f-number (virtual): depth of field and lens character, not exposure.
    public static native void nativeSetVirtualAperture(float fNumber);
    // Lens character (ghosts, halo, anamorphic streak, starburst, veil, CA, distortion, swirl). Off by default.
    public static native void nativeSetLensFxEnabled(boolean enabled);
    public static native void nativeSetPortraitMode(boolean on);
    public static native float nativeGetLastRawMean();
    public static native boolean nativeSetEncoderSurface(Surface encoderSurface);
    // Call before nativeSetEncoderSurface: true = encoder gets BT.2020 HLG (10-bit when the surface allows).
    public static native void nativeSetEncoderHlg(boolean hlg);
    // Attached encoder target, e.g. "10-bit HLG", "8-bit SDR", or "off".
    public static native String nativeGetEncoderInfo();
    public static native float nativeRenderRaw(
        HardwareBuffer buffer,
        int width,
        int height,
        int stride,
        int cfa,
        float whiteLevel,
        float gainR,
        float gainG,
        float gainB,
        int sensorOrientation,
        int camMatrixIdx
    );
    public static native byte[] nativeCaptureLastRgba(int[] outDims);
    // Full-resolution offline master render of the last RAW frame (RGBA8, alpha = 255).
    public static native byte[] nativeRenderMasterStill(int[] outDims);
    // Two-step master render for depth bokeh. Begin renders PREP + ISP + PYRAMID and returns a
    // dstW x dstH sRGB RGB copy (3 bytes per pixel) of the scene, or null (busy / no frame). Every
    // successful Begin must be followed by exactly one Finish: disparity normalized 0 = far .. 1 =
    // near (dw x dh, row major) and matte 0 = background .. 1 = subject (same grid, or null) or null to render without depth; focusDisp is the in-focus disparity.
    // Finish returns RGBA8 like nativeRenderMasterStill; the gain plane is then nativeGetMasterGainmap.
    public static native byte[] nativeMasterBegin(int dstW, int dstH);
    public static native byte[] nativeMasterFinish(float[] disparity, float[] matte, int dw, int dh, float focusDisp, int[] outDims);
    // Ultra HDR gain plane from the last master render (log2(gain)/log2(3), 8-bit).
    public static native byte[] nativeGetMasterGainmap(int[] outDims);
    // Multi-frame stacking on the frames already in the RAW ring. Mode: 0 burst average, 1 HDR bracket,
    // 2 long exposure average, 3 long exposure lighten. Add folds in the newest frame (relExposure =
    // its ISO x shutter over the reference frame's). Finish merges into the newest ring slot.
    public static native boolean nativeStackBegin(int mode);
    public static native boolean nativeStackAdd(float relExposure);
    public static native boolean nativeStackFinish();
    public static native void nativeStackEnd();
}
