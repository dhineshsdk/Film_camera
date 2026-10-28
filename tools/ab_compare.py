#!/usr/bin/env python3
"""Stage A0: render two builds on the same RAW bursts and compare noise, sharpness, clipping.

Bursts come from the phone:  adb shell am broadcast -a com.s24film.camera.DEBUG \
    --es cmd rawburst --ei n 8 --es tag day_1x      (see tools/A0-GOLDEN-SCENES.md)

    python tools/ab_compare.py bursts/day_1x bursts/night_1x --a single --b mean
    python tools/ab_compare.py bursts/* --a single --b "cmd:python my_build.py {burst} {out}"

A renderer is either a built-in name (single = frame 1, mean = average of all frames, no
alignment, so use a tripod or a stable rest) or  cmd:<command>  where {burst} is the burst
folder and {out} a .npy path the command must write: float32 H x W x 3 linear RGB, 0..1,
white-balanced. Needs numpy and Pillow only. `--selftest` runs on a synthetic burst.
"""
import argparse, json, os, struct, subprocess, sys, tempfile
import numpy as np
from PIL import Image

# ---------------------------------------------------------------- DNG reading (uncompressed 16-bit)
TYPES = {1: ("B", 1), 2: ("c", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 6: ("b", 1), 7: ("B", 1),
         8: ("h", 2), 9: ("i", 4), 10: ("ii", 8), 11: ("f", 4), 12: ("d", 8)}


def _ifd(buf, off, e):
    n = struct.unpack_from(e + "H", buf, off)[0]
    tags = {}
    for i in range(n):
        tag, typ, cnt, val = struct.unpack_from(e + "HHII", buf, off + 2 + 12 * i)
        fmt, size = TYPES.get(typ, ("B", 1))
        total = size * cnt
        at = off + 2 + 12 * i + 8 if total <= 4 else val
        if typ in (5, 10):
            v = struct.unpack_from(e + fmt[0] * (2 * cnt), buf, at)
            v = [v[2 * k] / max(v[2 * k + 1], 1) for k in range(cnt)]
        elif typ == 2:
            v = buf[at:at + cnt]
        else:
            v = list(struct.unpack_from(e + fmt * cnt, buf, at))
        tags[tag] = v
    return tags


def load_dng(path):
    """Returns (raw uint16 HxW minus nothing, meta dict)."""
    buf = open(path, "rb").read()
    e = "<" if buf[:2] == b"II" else ">"
    ifds = [_ifd(buf, struct.unpack_from(e + "I", buf, 4)[0], e)]
    for sub in ifds[0].get(330, []):
        ifds.append(_ifd(buf, sub, e))
    cand = [t for t in ifds if t.get(258, [0])[0] == 16 and 273 in t and t.get(259, [1])[0] == 1]
    if not cand:
        raise ValueError(f"{path}: no uncompressed 16-bit raw IFD")
    t = max(cand, key=lambda x: x[256][0] * x[257][0])
    w, h = t[256][0], t[257][0]
    data = b"".join(buf[o:o + c] for o, c in zip(t[273], t[279]))
    raw = np.frombuffer(data, dtype=e + "u2", count=w * h).reshape(h, w).astype(np.uint16)
    # CFA pattern codes 0=R 1=G 2=B, 2x2 in raster order
    cfa = list(t.get(33422, ifds[0].get(33422, [0, 1, 1, 2])))[:4]
    root = {**ifds[0], **t}
    bl = root.get(50714, [0])
    black = np.array(bl[:4] if len(bl) >= 4 else [bl[0]] * 4, dtype=np.float32).reshape(2, 2)
    white = float(root.get(50717, [1023])[0])
    neutral = np.array(root.get(50728, [1, 1, 1]), dtype=np.float32)
    return raw, dict(cfa=cfa, black=black, white=white, neutral=neutral)


def quad_rgb(raw, m):
    """Half-resolution linear RGB (one 2x2 cell -> one pixel), black-subtracted, 0..1, white-balanced."""
    f = raw.astype(np.float32)
    h, w = (f.shape[0] // 2) * 2, (f.shape[1] // 2) * 2
    f = f[:h, :w]
    f = (f - np.tile(m["black"], (h // 2, w // 2))) / (m["white"] - m["black"].mean())
    cells = {"R": [], "G": [], "B": []}
    for k, c in enumerate(m["cfa"]):
        cells["RGB"[c]].append(f[k // 2::2, k % 2::2])
    rgb = np.stack([np.mean(cells[c], axis=0) for c in "RGB"], axis=-1)
    gains = m["neutral"].max() / np.maximum(m["neutral"], 1e-3)   # as-shot neutral -> gains
    gains = gains / gains[1]
    return np.clip(rgb * gains, 0, None)


# ---------------------------------------------------------------- renderers
def load_burst(d):
    files = sorted(f for f in os.listdir(d) if f.lower().endswith(".dng"))
    if not files:
        raise SystemExit(f"{d}: no .dng files")
    return [os.path.join(d, f) for f in files]


def render_single(d):
    raw, m = load_dng(load_burst(d)[0])
    return quad_rgb(raw, m)


def render_mean(d):
    acc = None
    files = load_burst(d)
    for p in files:
        raw, m = load_dng(p)
        r = quad_rgb(raw, m)
        acc = r if acc is None else acc + r
    return acc / len(files)


def render_cmd(spec, d):
    out = tempfile.mktemp(suffix=".npy")
    cmd = spec.replace("{burst}", d).replace("{out}", out)
    subprocess.run(cmd, shell=True, check=True)
    arr = np.load(out).astype(np.float32)
    os.remove(out)
    return arr


def render(spec, d):
    if spec == "single": return render_single(d)
    if spec == "mean": return render_mean(d)
    if spec.startswith("cmd:"): return render_cmd(spec[4:], d)
    raise SystemExit(f"unknown renderer {spec!r}")


# ---------------------------------------------------------------- metrics
def srgb(x):
    x = np.clip(x, 0, 1)
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(x, 1 / 2.4) - 0.055)


def luma8(rgb):
    return srgb(rgb @ np.array([0.2126, 0.7152, 0.0722], np.float32)) * 255.0


def block_mean(a, b=16):
    h, w = (a.shape[0] // b) * b, (a.shape[1] // b) * b
    return a[:h, :w].reshape(h // b, b, w // b, b).mean(axis=(1, 3))


def conv3(a, k):
    p = np.pad(a, 1, mode="edge")
    out = np.zeros_like(a)
    for i in range(3):
        for j in range(3):
            if k[i][j]: out += k[i][j] * p[i:i + a.shape[0], j:j + a.shape[1]]
    return out


def metrics(rgb):
    y = luma8(rgb)
    # Immerkaer noise estimate per pixel, then the flattest quarter of 16x16 blocks (8-bit code values).
    n = np.abs(conv3(y, [[1, -2, 1], [-2, 4, -2], [1, -2, 1]])) * np.sqrt(np.pi / 2) / 6
    bm = np.sort(block_mean(n).ravel())
    noise = float(bm[: max(1, len(bm) // 4)].mean())
    # Edge strength: p99 of gradient magnitude after a 3x3 box blur (keeps noise from faking sharpness).
    s = conv3(y, [[1 / 9] * 3] * 3)
    gx, gy = np.gradient(s)
    sharp = float(np.percentile(np.hypot(gx, gy), 99))
    mx = rgb.max(axis=-1)
    return dict(noise=noise, sharp=sharp, clip_hi=float((mx >= 0.995).mean() * 100),
                clip_lo=float((y <= 1.0).mean() * 100))


def to_common(a, b):
    """Box-resize both renders to the smaller size so metrics compare like with like."""
    h, w = min(a.shape[0], b.shape[0]), min(a.shape[1], b.shape[1])
    def rs(x):
        if x.shape[:2] == (h, w): return x
        return np.stack([np.asarray(Image.fromarray(x[..., c].astype(np.float32), "F").resize((w, h), Image.BOX))
                         for c in range(3)], axis=-1)
    return rs(a), rs(b)


def burst_stats(d):
    """Temporal noise over the burst (green pixels, DN) and raw clipping, independent of any build."""
    files = load_burst(d)[:8]
    gs, sat = [], 0.0
    for p in files:
        raw, m = load_dng(p)
        f = raw.astype(np.float32)
        sat = max(sat, float((f >= m["white"] * 0.98).mean() * 100))
        gs.append(f[1::2, ::2][:512, :512] if m["cfa"][2] == 1 else f[::2, 1::2][:512, :512])
    tn = float(np.std(np.stack(gs), axis=0).mean()) if len(gs) > 1 else float("nan")
    return dict(frames=len(load_burst(d)), temporal_sigma_dn=tn, raw_clip_pct=sat)


def save_side_by_side(a, b, path):
    def img(x):
        im = Image.fromarray((srgb(x) * 255 + 0.5).astype(np.uint8))
        s = 800 / im.width
        return im.resize((800, max(1, int(im.height * s))), Image.LANCZOS) if s < 1 else im
    ia, ib = img(a), img(b)
    out = Image.new("RGB", (ia.width + ib.width + 8, max(ia.height, ib.height)), (30, 30, 30))
    out.paste(ia, (0, 0)); out.paste(ib, (ia.width + 8, 0))
    out.save(path)


def pct(a, b):
    return (b - a) / a * 100 if a else float("nan")


def compare(bursts, spec_a, spec_b, outdir):
    os.makedirs(outdir, exist_ok=True)
    rows = []
    print(f"{'burst':<22}{'metric':<10}{'A':>9}{'B':>9}{'B vs A':>9}   verdict")
    for d in bursts:
        name = os.path.basename(os.path.normpath(d))
        ra, rb = to_common(render(spec_a, d), render(spec_b, d))
        ma, mb = metrics(ra), metrics(rb)
        info = burst_stats(d)
        save_side_by_side(ra, rb, os.path.join(outdir, f"{name}_A_vs_B.png"))
        for k, better, label in (("noise", "lower", "noise"), ("sharp", "higher", "sharpness"),
                                 ("clip_hi", "lower", "clip hi %"), ("clip_lo", "lower", "clip lo %")):
            d_pct = pct(ma[k], mb[k])
            tol = 5.0 if k in ("noise", "sharp") else 0.05
            delta = (mb[k] - ma[k]) if k.startswith("clip") else d_pct
            good = (delta < -tol) if better == "lower" else (delta > tol)
            bad = (delta > tol) if better == "lower" else (delta < -tol)
            v = "B better" if good else "B worse" if bad else "same"
            print(f"{name:<22}{label:<10}{ma[k]:9.3f}{mb[k]:9.3f}{delta:+8.1f}{'%' if not k.startswith('clip') else 'pt'}   {v}")
            rows.append(dict(burst=name, metric=k, a=ma[k], b=mb[k], delta=delta, verdict=v))
        print(f"{'':<22}raw: {info['frames']} frames, temporal sigma {info['temporal_sigma_dn']:.2f} DN, "
              f"raw clip {info['raw_clip_pct']:.3f}%")
    with open(os.path.join(outdir, "ab_results.json"), "w") as f:
        json.dump(dict(a=spec_a, b=spec_b, rows=rows), f, indent=1)
    print(f"\nwrote {outdir}/ab_results.json and *_A_vs_B.png")


# ---------------------------------------------------------------- synthetic burst for --selftest
def write_dng(path, raw, black=64, white=1023):
    h, w = raw.shape
    data = raw.astype("<u2").tobytes()
    ents = [(256, 4, 1, w), (257, 4, 1, h), (258, 3, 1, 16), (259, 3, 1, 1), (262, 3, 1, 32803),
            (273, 4, 1, 0), (277, 3, 1, 1), (278, 4, 1, h), (279, 4, 1, len(data)),
            (50714, 3, 1, black), (50717, 3, 1, white)]
    off = 8 + 2 + 12 * len(ents) + 4
    ents = [(t, ty, c, off if t == 273 else v) for t, ty, c, v in ents]
    out = b"II*\0" + struct.pack("<I", 8) + struct.pack("<H", len(ents))
    for t, ty, c, v in ents: out += struct.pack("<HHII", t, ty, c, v)
    open(path, "wb").write(out + struct.pack("<I", 0) + data)


def selftest():
    rng = np.random.default_rng(1)
    d = tempfile.mkdtemp()
    h, w = 256, 256
    yy, xx = np.mgrid[0:h, 0:w]
    scene = 200 + 500 * (xx > w // 2) + 1.0 * yy          # flat halves with one hard edge
    scene[: h // 8, : w // 8] = 1100                     # clipped corner
    for i in range(8):
        write_dng(os.path.join(d, f"{i + 1:04d}.dng"), np.clip(scene + rng.normal(0, 12, scene.shape), 0, 1023))
    compare([d], "single", "mean", os.path.join(d, "out"))
    ra, rb = render("single", d), render("mean", d)
    ma, mb = metrics(ra), metrics(rb)
    assert mb["noise"] < ma["noise"] * 0.6, (ma, mb)       # 8-frame mean must be clearly quieter
    assert abs(mb["sharp"] - ma["sharp"]) / ma["sharp"] < 0.5
    assert ma["clip_hi"] > 0.5
    print("selftest ok")


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("bursts", nargs="*", help="burst folders (each holds 0001.dng ...)")
    ap.add_argument("--a", default="single"); ap.add_argument("--b", default="mean")
    ap.add_argument("--out", default="ab_out"); ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest: selftest()
    elif a.bursts: compare(a.bursts, a.a, a.b, a.out)
    else: ap.error("give burst folders or --selftest")
