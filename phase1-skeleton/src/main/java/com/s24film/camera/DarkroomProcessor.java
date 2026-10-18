package com.s24film.camera;

import android.graphics.Bitmap;
import android.graphics.Matrix;

/** CPU edit pipeline for the darkroom: geometry first, then tone, colour, vignette and grain. */
final class DarkroomProcessor {
    static final String[] CROP_NAMES = {"Original", "1:1", "4:5", "3:2", "16:9"};
    private static final float[] CROP_RATIO = {0f, 1f, 0.8f, 1.5f, 16f / 9f};

    static final class Params {
        float ev, contrast, sat, warmth, tint, fade, vignette, grain;   // sat is a multiplier delta, rest -1..1 / 0..1
        boolean mono;
        int rot;    // quarter turns clockwise
        int crop;   // index into CROP_NAMES

        Params copy() {
            Params p = new Params();
            p.ev = ev; p.contrast = contrast; p.sat = sat; p.warmth = warmth; p.tint = tint;
            p.fade = fade; p.vignette = vignette; p.grain = grain; p.mono = mono; p.rot = rot; p.crop = crop;
            return p;
        }
    }

    private DarkroomProcessor() { }

    /** Rotate and centre-crop only (also what the "compare" view shows). */
    static Bitmap geometry(Bitmap src, Params p) {
        Bitmap b = src;
        if ((p.rot & 3) != 0) {
            Matrix m = new Matrix();
            m.postRotate(90f * (p.rot & 3));
            b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
        float ratio = CROP_RATIO[p.crop];
        if (ratio > 0f) {
            int w = b.getWidth(), h = b.getHeight();
            // portrait frames take the inverted ratio so "4:5" stays 4:5 upright
            float r = (h > w) ? 1f / ratio : ratio;
            int cw = w, ch = h;
            if ((float) w / h > r) cw = Math.round(h * r); else ch = Math.round(w / r);
            if (cw != w || ch != h) b = Bitmap.createBitmap(b, (w - cw) / 2, (h - ch) / 2, cw, ch);
        }
        return b;
    }

    static Bitmap process(Bitmap src, Params p) {
        Bitmap g = geometry(src, p);
        int w = g.getWidth(), h = g.getHeight();
        int[] px = new int[w * h];
        g.getPixels(px, 0, w, 0, 0, w, h);

        int[] lut = new int[256];
        float gain = (float) Math.pow(2.0, p.ev * 2.0);
        float c = 1f + p.contrast * 0.8f;
        float lift = p.fade * 0.18f;
        for (int i = 0; i < 256; i++) {
            float x = (float) Math.pow(i / 255f, 2.2);
            x = (float) Math.pow(Math.min(1f, x * gain), 1f / 2.2f);
            x = (x - 0.5f) * c + 0.5f;
            x = lift + x * (1f - lift);
            lut[i] = clamp((int) (x * 255f + 0.5f));
        }

        float sat = Math.max(0f, 1f + p.sat);
        float rGain = 1f + 0.18f * p.warmth, bGain = 1f - 0.18f * p.warmth, gGain = 1f - 0.10f * p.tint;
        float vig = p.vignette, grain = p.grain * 30f;
        float cx = (w - 1) / 2f, cy = (h - 1) / 2f, inv = 1f / (cx * cx + cy * cy);
        int seed = 0x2545F491;
        for (int y = 0; y < h; y++) {
            float dy = (y - cy) * (y - cy);
            for (int x = 0; x < w; x++) {
                int i = y * w + x, v = px[i];
                float r = lut[(v >> 16) & 255], gg = lut[(v >> 8) & 255], b = lut[v & 255];
                float l = 0.2126f * r + 0.7152f * gg + 0.0722f * b;
                if (p.mono) { r = gg = b = l; } else {
                    r = l + (r - l) * sat; gg = l + (gg - l) * sat; b = l + (b - l) * sat;
                    r *= rGain; gg *= gGain; b *= bGain;
                }
                if (vig > 0f) {
                    float d = ((x - cx) * (x - cx) + dy) * inv;
                    float f = 1f - vig * d * d * 1.6f - vig * d * 0.2f;
                    r *= f; gg *= f; b *= f;
                }
                if (grain > 0f) {
                    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
                    float n = ((seed & 0xFFFF) / 32767.5f - 1f) * grain * (1f - Math.abs(l - 128f) / 170f);
                    r += n; gg += n; b += n;
                }
                px[i] = 0xFF000000 | (clamp((int) r) << 16) | (clamp((int) gg) << 8) | clamp((int) b);
            }
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(int v) { return v < 0 ? 0 : (v > 255 ? 255 : v); }
}
