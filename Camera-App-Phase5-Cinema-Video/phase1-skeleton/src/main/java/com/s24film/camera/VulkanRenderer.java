package com.s24film.camera;

import android.hardware.HardwareBuffer;
import android.view.Surface;

public class VulkanRenderer {
    static {
        System.loadLibrary("s24film_vulkan");
    }

    public static native boolean nativeInit(Surface surface);
    public static native void nativeDestroy();
    public static native void nativeSetFilmLook(int stockIndex, int lensIndex, boolean burstEnabled);
    public static native void nativeSetDarkroomSliders(float evStops, float grainMult, float halationMult);
    public static native void nativeSetCinemaParams(int anamorphicMode, int cinemaGate, boolean gateWeave, boolean stepPrint8fps);
    public static native boolean nativeSetEncoderSurface(Surface encoderSurface);
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
}
