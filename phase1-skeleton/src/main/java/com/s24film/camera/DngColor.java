package com.s24film.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.params.ColorSpaceTransform;

/**
 * DNG-style colour calibration for one camera: interpolates the two ForwardMatrices by the
 * scene's correlated colour temperature, found from the as-shot neutral via the two
 * ColorMatrices (DNG spec, "Mapping Camera Color Space to CIE XYZ Space").
 */
final class DngColor {
    private static final double FILM_DAYLIGHT_CCT = 5500.0;
    private static final double LOCUS_LOCK = 0.85;   // fraction of the AWB tint removed

    private final double[] cm1;
    private final double[] cm2;
    private final double[] fm1;
    private final double[] fm2;
    private final double cct1;
    private final double cct2;

    private DngColor(double[] cm1, double[] cm2, double[] fm1, double[] fm2, double cct1, double cct2) {
        this.cm1 = cm1;
        this.cm2 = cm2;
        this.fm1 = fm1;
        this.fm2 = fm2;
        this.cct1 = cct1;
        this.cct2 = cct2;
    }

    /** Returns null when the camera does not publish both calibrations. */
    static DngColor from(CameraCharacteristics chars) {
        if (chars == null) return null;
        double[] cm1 = toArray(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        double[] cm2 = toArray(chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        double[] fm1 = toArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        double[] fm2 = toArray(chars.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        Integer il1 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Byte il2 = chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        if (cm1 == null || fm1 == null || il1 == null) return null;
        if (cm2 == null || fm2 == null || il2 == null) {
            return new DngColor(cm1, cm1, fm1, fm1, illuminantCct(il1), illuminantCct(il1));
        }
        return new DngColor(cm1, cm2, fm1, fm2, illuminantCct(il1), illuminantCct(il2));
    }

    /** Camera-space neutral (R, G, B) of daylight film balance. */
    float[] daylightNeutral() {
        double[] cm = interpolate(cm1, cm2, weight(FILM_DAYLIGHT_CCT));
        double[] xyz = xyToXyz(0.3324, 0.3474);   // D55
        double[] n = mul(cm, xyz);
        double g = Math.max(n[1], 1e-6);
        return new float[] {(float) (n[0] / g), 1.0f, (float) (n[2] / g)};
    }

    /**
     * Each module's AWB lands on a slightly different green/magenta tint for the same light
     * (on this phone the front camera reads up to 0.046 in y off the locus, the others ~0.01),
     * which is what makes lenses disagree. Keep the illuminant's colour temperature but move
     * it onto the Planckian locus, then express it back as this camera's neutral, so every
     * lens renders against the same white.
     */
    float[] lockNeutralToLocus(float[] neutral) {
        double[] xy = neutralToXy(neutral);
        double t = cctFromXy(xy[0], xy[1]);
        double[] p = planckXy(t);
        double x = xy[0] + LOCUS_LOCK * (p[0] - xy[0]);
        double y = xy[1] + LOCUS_LOCK * (p[1] - xy[1]);
        double[] cm = interpolate(cm1, cm2, weight(cctFromXy(x, y)));
        double[] n = mul(cm, xyToXyz(x, y));
        double g = Math.max(n[1], 1e-6);
        return new float[] {
            (float) Math.max(n[0] / g, 0.05), 1.0f, (float) Math.max(n[2] / g, 0.05)};
    }

    /** Row-major ForwardMatrix for an as-shot camera neutral. */
    float[] forwardMatrixForNeutral(float[] neutral) {
        double[] xy = neutralToXy(neutral);
        return toFloat(interpolate(fm1, fm2, weight(cctFromXy(xy[0], xy[1]))));
    }

    private double[] neutralToXy(float[] neutral) {
        double[] n = {neutral[0], neutral[1], neutral[2]};
        double x = 0.3457, y = 0.3585;   // start at D50
        for (int i = 0; i < 20; i++) {
            double[] cm = interpolate(cm1, cm2, weight(cctFromXy(x, y)));
            double[] inv = invert(cm);
            if (inv == null) break;
            double[] xyz = mul(inv, n);
            double sum = xyz[0] + xyz[1] + xyz[2];
            if (!(sum > 1e-9)) break;
            double nx = xyz[0] / sum, ny = xyz[1] / sum;
            if (Math.abs(nx - x) < 1e-6 && Math.abs(ny - y) < 1e-6) { x = nx; y = ny; break; }
            x = nx;
            y = ny;
        }
        return new double[] {x, y};
    }

    // Kim et al. cubic fit of the Planckian locus, 1667-25000 K.
    private static double[] planckXy(double t) {
        t = Math.max(1667.0, Math.min(25000.0, t));
        double t2 = t * t, t3 = t2 * t;
        double x = (t <= 4000.0)
            ? -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
            : -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390;
        double y;
        if (t <= 2222.0) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        } else if (t <= 4000.0) {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        }
        return new double[] {x, y};
    }

    float[] forwardMatrixForDaylight() {
        return toFloat(interpolate(fm1, fm2, weight(FILM_DAYLIGHT_CCT)));
    }

    // Weight of calibration 1, linear in inverse temperature, clamped to the calibrated range.
    private double weight(double cct) {
        if (Math.abs(cct1 - cct2) < 1.0) return 1.0;
        double w = (1.0 / cct - 1.0 / cct2) / (1.0 / cct1 - 1.0 / cct2);
        return Math.max(0.0, Math.min(1.0, w));
    }

    private static double cctFromXy(double x, double y) {
        double n = (x - 0.3320) / (0.1858 - y);
        double t = 449.0 * n * n * n + 3525.0 * n * n + 6823.3 * n + 5520.33;
        return Math.max(2000.0, Math.min(12000.0, t));
    }

    private static double illuminantCct(int il) {
        switch (il) {
            case 3: return 2850.0;    // tungsten
            case 17: return 2856.0;   // standard A
            case 24: return 3200.0;   // ISO studio tungsten
            case 15: return 3450.0;   // white fluorescent
            case 14: case 2: return 4150.0;   // cool white / fluorescent
            case 18: return 4874.0;   // standard B
            case 13: return 5000.0;   // day white fluorescent
            case 23: return 5003.0;   // D50
            case 20: return 5503.0;   // D55
            case 1: case 4: case 9: return 5500.0;   // daylight, flash, fine weather
            case 12: return 6430.0;   // daylight fluorescent
            case 10: case 21: return 6504.0;   // cloudy, D65
            case 19: return 6774.0;   // standard C
            case 11: case 22: return 7504.0;   // shade, D75
            default: return 5000.0;
        }
    }

    private static double[] xyToXyz(double x, double y) {
        return new double[] {x / y, 1.0, (1.0 - x - y) / y};
    }

    private static double[] toArray(ColorSpaceTransform t) {
        if (t == null) return null;
        double[] m = new double[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                m[r * 3 + c] = t.getElement(c, r).doubleValue();
            }
        }
        return m;
    }

    private static double[] interpolate(double[] a, double[] b, double w) {
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = w * a[i] + (1.0 - w) * b[i];
        return m;
    }

    private static double[] mul(double[] m, double[] v) {
        return new double[] {
            m[0] * v[0] + m[1] * v[1] + m[2] * v[2],
            m[3] * v[0] + m[4] * v[1] + m[5] * v[2],
            m[6] * v[0] + m[7] * v[1] + m[8] * v[2]};
    }

    private static double[] invert(double[] m) {
        double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
        double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
        double det = a * A + b * B + c * C;
        if (Math.abs(det) < 1e-12) return null;
        double s = 1.0 / det;
        return new double[] {
            A * s, -(b * i - c * h) * s, (b * f - c * e) * s,
            B * s, (a * i - c * g) * s, -(a * f - c * d) * s,
            C * s, -(a * h - b * g) * s, (a * e - b * d) * s};
    }

    private static float[] toFloat(double[] m) {
        float[] out = new float[m.length];
        for (int i = 0; i < m.length; i++) out[i] = (float) m[i];
        return out;
    }
}
