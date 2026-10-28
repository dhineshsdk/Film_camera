#!/usr/bin/env python3
"""Stage A5: fit noise curves and lens shading from the calibration bursts (tools/a5_shoot.ps1).

    python tools/a5_calibrate.py calib            # writes calib/a5_calibration.json + PNG plots
    python tools/a5_calibrate.py --selftest

Noise ("grey" bursts, one per ISO): for each of the 4 Bayer channels, the temporal mean and
variance of every pixel over the 8 frames, binned by mean, fitted to var = S*x + O in normalized
units (x = (DN - black) / (white - black)), the same form as Camera2 SENSOR_NOISE_PROFILE, so the
merge and the spatial NR can use measured numbers instead of the HAL's.

Shading ("white" bursts): the frame-averaged white wall per channel on a 17 x 13 grid (the size of
the shader's lens-shading table), as the gain that flattens it relative to the centre, plus the
R/G and B/G colour error left in the corners (the front camera's magenta corners show up there).
Needs numpy and Pillow; reads DNGs with tools/ab_compare.py.
"""
import argparse, glob, json, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ab_compare import load_dng, load_burst  # noqa: E402

GRID_W, GRID_H = 17, 13


def channels(raw, m):
    """Four Bayer planes (raster order of the 2x2 cell) normalized 0..1, and their colour codes."""
    f = raw.astype(np.float32)
    h, w = (f.shape[0] // 2) * 2, (f.shape[1] // 2) * 2
    rng = m["white"] - float(m["black"].mean())
    planes = [(f[k // 2:h:2, k % 2:w:2] - m["black"][k // 2, k % 2]) / rng for k in range(4)]
    return planes, m["cfa"]


def fit_noise(burst_dir, bins=48):
    files = load_burst(burst_dir)[:8]
    stacks = None
    for p in files:
        raw, m = load_dng(p)
        planes, cfa = channels(raw, m)
        if stacks is None:
            stacks = [[] for _ in planes]
        for k, pl in enumerate(planes):
            stacks[k].append(pl)
    out = []
    for k in range(4):
        st = np.stack(stacks[k])
        mean = st.mean(0).ravel()
        var = st.var(0, ddof=1).ravel()
        lo, hi = np.percentile(mean, [1, 99])
        edges = np.linspace(lo, hi, bins + 1)
        idx = np.digitize(mean, edges) - 1
        xs, vs, ns = [], [], []
        for b in range(bins):
            sel = idx == b
            if sel.sum() < 200:
                continue
            # Median of a 7-dof chi-square is 0.9066 of its mean.
            xs.append(mean[sel].mean()); vs.append(np.median(var[sel]) / 0.9066); ns.append(sel.sum())
        xs, vs, ns = map(np.array, (xs, vs, ns))
        if len(xs) >= 2:
            A = np.stack([xs, np.ones_like(xs)], 1) * np.sqrt(ns)[:, None]
            S, O = np.linalg.lstsq(A, vs * np.sqrt(ns), rcond=None)[0]
        else:   # one flat level: shot noise only through the origin
            S, O = float(vs.mean() / max(xs.mean(), 1e-6)), 0.0
        out.append(dict(channel="RGB"[cfa[k]], S=float(S), O=float(max(O, 0.0)), levels=len(xs),
                        mean_range=[float(lo), float(hi)]))
    return out


def fit_shading(burst_dir):
    files = load_burst(burst_dir)[:8]
    acc = None
    for p in files:
        raw, m = load_dng(p)
        planes, cfa = channels(raw, m)
        acc = planes if acc is None else [a + b for a, b in zip(acc, planes)]
    acc = [a / len(files) for a in acc]
    grids = []
    for a in acc:
        h, w = a.shape
        g = np.zeros((GRID_H, GRID_W), np.float32)
        for j in range(GRID_H):
            for i in range(GRID_W):
                y0, y1 = int(j * h / GRID_H), int((j + 1) * h / GRID_H)
                x0, x1 = int(i * w / GRID_W), int((i + 1) * w / GRID_W)
                g[j, i] = np.median(a[y0:y1, x0:x1])
        grids.append(g)
    centre = [g[GRID_H // 2, GRID_W // 2] for g in grids]
    gains = [centre[k] / np.maximum(g, 1e-6) for k, g in enumerate(grids)]
    names = ["RGB"[c] for c in cfa]
    gi = [k for k, n in enumerate(names) if n == "G"]
    green = (grids[gi[0]] + grids[gi[-1]]) * 0.5
    r = grids[names.index("R")] / np.maximum(green, 1e-6)
    b = grids[names.index("B")] / np.maximum(green, 1e-6)
    rc, bc = r[GRID_H // 2, GRID_W // 2], b[GRID_H // 2, GRID_W // 2]
    corners = [(0, 0), (0, -1), (-1, 0), (-1, -1)]
    colour = [dict(rg=float(r[c] / rc), bg=float(b[c] / bc)) for c in corners]
    worst = max(max(abs(c["rg"] - 1), abs(c["bg"] - 1)) for c in colour)
    return dict(grid=[GRID_W, GRID_H], cfa=names, gain=[g.round(4).tolist() for g in gains],
                vignette_stops=float(np.log2(max(float(gains[gi[0]].max()), 1e-6))),
                corner_colour=colour, worst_corner_colour_pct=float(worst * 100))


def parse_tag(d):
    parts = os.path.basename(os.path.normpath(d)).split("_")   # a5_<kind>_<lens>_<iso>
    return parts[1], parts[2], int(parts[3])


def run(root):
    res = dict(noise={}, shading={})
    for d in sorted(glob.glob(os.path.join(root, "a5_*_*_*"))):
        kind, lens, iso = parse_tag(d)
        if kind == "grey":
            res["noise"].setdefault(lens, {})[str(iso)] = fit_noise(d)
            n = res["noise"][lens][str(iso)]
            print(f"noise  lens {lens} ISO {iso:5d}: " + "  ".join(f"{c['channel']} S={c['S']:.2e} O={c['O']:.2e}" for c in n))
        elif kind == "white":
            sh = fit_shading(d)
            res["shading"].setdefault(lens, {})[str(iso)] = sh
            print(f"shade  lens {lens} ISO {iso:5d}: vignette {sh['vignette_stops']:.2f} stops, "
                  f"worst corner colour error {sh['worst_corner_colour_pct']:.1f}%")
    out = os.path.join(root, "a5_calibration.json")
    with open(out, "w") as f:
        json.dump(res, f, indent=1)
    print("wrote", out)
    return res


def selftest():
    import tempfile
    from ab_compare import write_dng
    rng = np.random.default_rng(3)
    tmp = tempfile.mkdtemp()
    S_true, O_true = 3e-4, 4e-6
    H, W = 600, 800
    yy, xx = np.mgrid[0:H, 0:W]
    ramp = 0.1 + 0.6 * xx / W                                   # several grey levels in one frame
    d = os.path.join(tmp, "a5_grey_0_800"); os.makedirs(d)
    for i in range(8):
        x = ramp + rng.normal(0, 1, ramp.shape) * np.sqrt(S_true * ramp + O_true)
        write_dng(os.path.join(d, f"{i + 1:04d}.dng"), np.clip(64 + x * 959, 0, 1023).astype(np.uint16))
    r2 = ((xx - W / 2) ** 2 + (yy - H / 2) ** 2) / (W / 2) ** 2
    d2 = os.path.join(tmp, "a5_white_0_100"); os.makedirs(d2)
    for i in range(8):
        flat = 0.6 * (1 - 0.5 * r2)                             # about 1.7 stops at the corner grid cells
        x = flat + rng.normal(0, 0.003, flat.shape)
        write_dng(os.path.join(d2, f"{i + 1:04d}.dng"), np.clip(64 + x * 959, 0, 1023).astype(np.uint16))
    res = run(tmp)
    fitted = res["noise"]["0"]["800"][0]
    ok_noise = abs(fitted["S"] / S_true - 1) < 0.15
    sh = res["shading"]["0"]["100"]
    ok_shade = 1.4 < sh["vignette_stops"] < 2.0 and sh["worst_corner_colour_pct"] < 3
    print(f"selftest: S fitted {fitted['S']:.2e} (true {S_true:.1e}), vignette {sh['vignette_stops']:.2f} stops")
    print("PASS" if ok_noise and ok_shade else "FAIL")
    return 0 if ok_noise and ok_shade else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("root", nargs="?")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest or not a.root:
        sys.exit(selftest())
    run(a.root)
