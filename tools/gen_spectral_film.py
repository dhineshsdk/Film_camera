#!/usr/bin/env python3
"""Build assets/spectral_film.bin: the spectral film model's data (Phase 3, spektrafilm method).

For every look whose stock has a datasheet profile (spektrafilm JSON format, either from the
spektrafilm project or digitised from the manufacturer sheet) this writes the stock's spectral
data plus the few constants the device needs to rebuild the per-stock lookup tables itself
(SpectralFilm.h does that on the phone in a few tens of ms when the stock changes):

  camera   scene ACEScg -> smooth non-negative spectrum (spectral upsampling, regularised least
           squares, tabulated over ACEScg chromaticity) -> integrated against the three layer
           log-sensitivities -> per-layer log exposure. Exposure is homogeneous in intensity, so a
           33 x 33 chromaticity table times r+g+b covers every input.
  negative layer log exposure -> layer density through the datasheet characteristic curves.
  print    colour negatives: CMY dye densities -> spectral transmittance -> halogen enlarger with
           Y/M dichroic filters -> paper layer exposures (Beer-Lambert) -> paper curves -> paper
           dye densities -> reflectance under D50 -> XYZ -> sRGB, paper white = 1. The filters and
           print exposure are solved here so mid grey prints neutral at 18 %.
           slides: dye densities -> transmittance on a D50 light box -> sRGB, scanned at 0.8.
           B&W: panchromatic / ortho layer exposure -> silver density -> neutral paper print.

Also renders preview sheets through the same maths (numpy) when --preview is given.

  python3 tools/gen_spectral_film.py [--profiles DIR] [--preview OUTDIR]
"""
import argparse, json, struct, sys, warnings
from pathlib import Path
import numpy as np

warnings.filterwarnings("ignore")
import colour  # noqa: E402  (colour-science, data only)
from scipy.optimize import lsq_linear, least_squares  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "phase1-skeleton/src/main/assets"
OUT = ASSETS / "spectral_film.bin"
TSV = ASSETS / "looks_index.tsv"
DEFAULT_PROFILES = Path("/mnt/project-files/Camera-App-notes/datasheet-data")

WL = np.arange(380.0, 781.0, 5.0)            # 81 samples, the profiles' grid
NW = WL.size
CAM_N = 33                                     # chromaticity grid (u = r/s, v = g/s)
CURVE_N = 256                                  # log exposure samples over [-3, 4]
LOGE_MIN, LOGE_MAX = -3.0, 4.0
MID_GREY = 0.18

TYPE_NEG, TYPE_SLIDE, TYPE_BW = 0, 1, 2
SLIDE_MID_DENSITY = 1.0                        # green density of a mid-grey slide
SLIDE_SCAN = 0.8                               # scanner contrast on transmittance (kSlideScan)

# ---------------------------------------------------------------- colorimetry
SHAPE = colour.SpectralShape(380, 780, 5)
CMF = colour.MSDS_CMFS["CIE 1931 2 Degree Standard Observer"].copy().align(SHAPE).values  # (81, 3)
D50 = colour.SDS_ILLUMINANTS["D50"].copy().align(SHAPE).values
D65 = colour.SDS_ILLUMINANTS["D65"].copy().align(SHAPE).values
ACESCG = colour.RGB_COLOURSPACES["ACEScg"]
M_AP1_XYZ = np.array(ACESCG.matrix_RGB_to_XYZ)
M_XYZ_SRGB = np.array(colour.RGB_COLOURSPACES["sRGB"].matrix_XYZ_to_RGB)
M_BRADFORD = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])
D65_XYZ = np.array([0.95047, 1.0, 1.08883])


def blackbody(t):
    lam = WL * 1e-9
    b = 1.0 / (lam ** 5 * (np.exp(1.4388e-2 / (lam * t)) - 1.0))
    return b / b[np.argmin(np.abs(WL - 560))]


# Enlarger: 3200 K halogen through a KG3 heat-absorbing glass (spektrafilm's TH-KG3).
KG3 = 1.0 / (1.0 + np.exp((WL - 705.0) / 18.0))
ENLARGER = blackbody(3200.0) * KG3
# Dichroic filter densities per unit filter value (Kodak CC / 100 style): yellow absorbs blue,
# magenta absorbs green. Smooth band edges like interference filters.
FILTER_Y = 1.0 / (1.0 + np.exp((WL - 505.0) / 9.0))
FILTER_M = 1.0 / (1.0 + np.exp((WL - 590.0) / 9.0)) - 1.0 / (1.0 + np.exp((WL - 495.0) / 9.0))
FILTER_M = np.clip(FILTER_M, 0.0, None)


def adapt_matrix(src_white_xyz, dst_white_xyz):
    s = M_BRADFORD @ src_white_xyz
    d = M_BRADFORD @ dst_white_xyz
    return np.linalg.inv(M_BRADFORD) @ np.diag(d / s) @ M_BRADFORD


# ---------------------------------------------------------------- spectral upsampling
def upsample_grid():
    """Smooth non-negative spectra for every ACEScg chromaticity on a CAM_N x CAM_N grid.

    min ||CMF^T s - XYZ||^2 * w + lambda ||D2 s||^2, s >= 0. Points outside the u + v <= 1
    triangle (and colours outside the spectral locus) get the closest reachable spectrum, so
    bilinear lookups near the edge stay sane.
    """
    A = CMF.T * 5.0 / 100.0                               # (3, 81)
    D2 = np.zeros((NW - 2, NW))
    for i in range(NW - 2):
        D2[i, i:i + 3] = [1.0, -2.0, 1.0]
    w, lam = 1e3, 1.0
    M = np.vstack([np.sqrt(w) * A, np.sqrt(lam) * D2])
    grid = np.zeros((CAM_N, CAM_N, NW))
    for j in range(CAM_N):          # v
        for i in range(CAM_N):      # u
            u, v = i / (CAM_N - 1), j / (CAM_N - 1)
            if u + v > 1.0:
                t = u + v
                u, v = u / t, v / t
            rgb = np.array([u, v, max(1.0 - u - v, 0.0)])
            xyz = M_AP1_XYZ @ rgb
            b = np.concatenate([np.sqrt(w) * xyz, np.zeros(NW - 2)])
            grid[j, i] = lsq_linear(M, b, bounds=(0.0, np.inf), method="bvls").x
    return grid


# ---------------------------------------------------------------- profiles
def load_profile(path):
    d = json.load(open(path))
    D = d["data"]
    info = d["info"]
    f = lambda k: np.array(D[k], dtype=float) if D.get(k) is not None else None
    return dict(name=path.stem, info=info, logsens=f("log_sensitivity"), cd=f("channel_density"),
                base=f("base_density"), mid=f("midscale_neutral_density"),
                loge=f("log_exposure"), curves=f("density_curves"))


def fill_curve(x, y):
    """Characteristic curve on the [-3, 4] grid: measured where digitised, held flat below the
    toe, and rolled off above the last point with the end slope decaying (a soft shoulder)."""
    ok = ~np.isnan(y)
    xs, ys = x[ok], y[ok]
    out = np.interp(x, xs, ys)
    lo, hi = xs[0], xs[-1]
    n = min(8, len(xs) - 1)
    slope = (ys[-1] - ys[-1 - n]) / max(xs[-1] - xs[-1 - n], 1e-6)
    tau = 0.6
    above = x > hi
    out[above] = ys[-1] + slope * tau * (1.0 - np.exp(-(x[above] - hi) / tau))
    out[x < lo] = ys[0]
    return out


def fill_spectrum(y, default=0.0):
    ok = ~np.isnan(y)
    if not ok.any():
        return np.full_like(y, default)
    return np.interp(WL, WL[ok], y[ok], left=0.0 if default == 0.0 else y[ok][0], right=0.0)


def resample_curves(p):
    x = np.linspace(LOGE_MIN, LOGE_MAX, CURVE_N)
    c = np.stack([fill_curve(p["loge"], p["curves"][:, k]) for k in range(3)], axis=1)
    return np.stack([np.interp(x, p["loge"], c[:, k]) for k in range(3)], axis=1)


def sensitivity(p):
    """Linear layer sensitivities, scaled jointly: the paper's relative layer speeds are what
    balance it for an orange-masked negative. (A film's own scale cancels in the camera stage.)"""
    s = np.nan_to_num(10.0 ** p["logsens"], nan=0.0)
    return s / max(s.max(), 1e-12)


def dye_set(p, donor):
    """CMY dye spectral densities and base density. Datasheets that publish only the mid-scale
    neutral get the dye spectra of the nearest spektrafilm stock (same maker's chemistry)."""
    cd = p["cd"]
    if cd is None or np.isnan(cd).sum() > cd.size // 2:
        cd = donor["cd"]
    cd = np.stack([fill_spectrum(cd[:, k]) for k in range(3)], axis=1)
    cd = np.clip(cd, 0.0, None)
    base = p["base"] if p["base"] is not None else donor["base"]
    if base is None or np.isnan(base).all():
        base = donor["base"]
    base = fill_spectrum(base, default=1.0)
    return cd, np.clip(base, 0.0, None)


# ---------------------------------------------------------------- model (mirrored in SpectralFilm.h)
def interp_curve(curves, loge):
    x = (np.asarray(loge) - LOGE_MIN) / (LOGE_MAX - LOGE_MIN) * (CURVE_N - 1)
    x = np.clip(x, 0.0, CURVE_N - 1.0)
    i0 = np.floor(x).astype(int)
    i1 = np.minimum(i0 + 1, CURVE_N - 1)
    t = x - i0
    return curves[i0] * (1 - t) + curves[i1] * t


class Paper:
    def __init__(self, p, view):
        self.sens = sensitivity(p)
        self.curves = resample_curves(p)
        self.cd, self.base = dye_set(p, p)
        self.view = view
        white = (10.0 ** -self.base * view) @ CMF
        self.white = white / white[1]
        self.m_out = M_XYZ_SRGB @ adapt_matrix(self.white, D65_XYZ)
        self.norm = (10.0 ** -self.base * view) @ CMF[:, 1]


def reflect_to_srgb(paper, dens_spec):
    """Spectral density (..., 81) of a print or slide -> linear sRGB, base white = 1."""
    xyz = (10.0 ** -dens_spec * paper.view) @ CMF / paper.norm
    return xyz @ paper.m_out.T


def print_neg(neg_d, film, paper, params):
    """Negative layer densities (..., 3) -> linear sRGB on paper (white = 1)."""
    logk, fy, fm = params
    t = 10.0 ** -(neg_d @ film["cd"].T + film["base"])                 # (..., 81)
    light = ENLARGER * 10.0 ** -(fy * FILTER_Y + fm * FILTER_M)
    h = (t * light) @ paper.sens                                        # (..., 3)
    loge = np.log10(np.maximum(h, 1e-12)) + logk
    pd = np.stack([interp_curve(paper.curves[:, k], loge[..., k]) for k in range(3)], axis=-1)
    return reflect_to_srgb(paper, pd @ paper.cd.T + paper.base)


def slide_rgb(d, film, view_paper):
    return reflect_to_srgb(view_paper, d @ film["cd"].T + film["base"])


def solve_print(film, paper):
    dmid = np.array([interp_curve(film["curves"][:, k], 0.0) for k in range(3)])

    def res(p):
        rgb = print_neg(dmid, film, paper, p)
        return np.log10(np.maximum(rgb, 1e-6)) - np.log10(MID_GREY)

    best = None
    for logk0 in (-1.0, 0.0, 1.0, 2.0):
        r = least_squares(res, x0=[logk0, 0.5, 0.5], bounds=([-6, 0, 0], [6, 3, 3]))
        if best is None or r.cost < best.cost:
            best = r
    return best.x, float(np.sqrt(2 * best.cost / 3))


def bw_paper_table(film, paper):
    """B&W negative printed on a neutral paper with the colour paper's green-layer curve."""
    dmax = float(film["curves"][:, 1].max())
    dn = np.linspace(0.0, dmax, CURVE_N)
    dmid = float(interp_curve(film["curves"][:, 1], 0.0))
    p0 = float(interp_curve(paper.curves[:, 1], -10.0))

    def y_of(dneg, logk):
        pd = interp_curve(paper.curves[:, 1], logk - dneg)
        return 10.0 ** -(pd - p0)

    lo, hi = -6.0, 6.0
    for _ in range(80):                       # bisection: mid grey -> 18 %
        m = 0.5 * (lo + hi)
        if y_of(dmid, m) > MID_GREY:
            lo = m          # print too light: more exposure
        else:
            hi = m
    return y_of(dn, 0.5 * (lo + hi)), dmax


# ---------------------------------------------------------------- stock records
def classify(p):
    i = p["info"]
    if i.get("channel_model") == "bw" or "bw" in str(i.get("channel_model")):
        return TYPE_BW
    return TYPE_SLIDE if i["type"] == "positive" else TYPE_NEG


def donor_for(name, donors):
    if name.startswith("fuji_eterna"):
        return donors["kodak_vision3_250d"]
    if name.startswith("fuji"):
        return donors["fujifilm_pro_400h"]
    return donors["kodak_portra_400"]


def slide_offset(curves):
    """Log exposure shift that puts mid grey at SLIDE_MID_DENSITY on the green layer."""
    x = np.linspace(LOGE_MIN, LOGE_MAX, CURVE_N)
    g = curves[:, 1]
    # g decreases with exposure; find x where g == target
    idx = np.where(np.diff(np.sign(g - SLIDE_MID_DENSITY)))[0]
    if len(idx) == 0:
        return 0.0
    k = idx[0]
    t = (g[k] - SLIDE_MID_DENSITY) / max(g[k] - g[k + 1], 1e-9)
    return float(x[k] + t * (x[k + 1] - x[k]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profiles", type=Path, default=DEFAULT_PROFILES)
    ap.add_argument("--preview", type=Path)
    args = ap.parse_args()

    looks = {}
    for line in open(TSV, encoding="utf-8"):
        f = line.rstrip("\n").split("\t")
        if len(f) >= 5:
            looks[f[2]] = (int(f[0]), int(f[1]))

    profiles = {}
    for sub in ("spektrafilm-profiles", "datasheet-profiles"):
        for path in sorted((args.profiles / sub).glob("*.json")):
            profiles[path.stem] = load_profile(path)

    donors = {k: profiles[k] for k in ("kodak_portra_400", "fujifilm_pro_400h", "kodak_vision3_250d")}
    papers = {"kodak_portra_endura": Paper(profiles["kodak_portra_endura"], D50),
              "kodak_2383": Paper(profiles["kodak_2383"], D65)}
    paper_ids = list(papers)

    print("upsampling spectra ...", file=sys.stderr)
    spectra = upsample_grid()

    stocks = []
    for name, p in profiles.items():
        if name not in looks:
            continue
        look_idx, cat = looks[name]
        if cat == 4 or p["info"].get("support") == "paper" or p["info"].get("stage") == "printing":
            continue
        typ = classify(p)
        rec = dict(name=name, look=look_idx, type=typ, sens=sensitivity(p), curves=resample_curves(p))
        if typ == TYPE_BW:
            s = rec["sens"].mean(axis=1, keepdims=True)
            rec["sens"] = np.repeat(s / s.max(), 3, axis=1)
            rec["curves"] = np.repeat(rec["curves"][:, 1:2], 3, axis=1)
            rec["cd"] = np.zeros((NW, 3))
            rec["base"] = np.zeros(NW)
        else:
            rec["cd"], rec["base"] = dye_set(p, donor_for(name, donors))
        rec["paper"] = -1
        rec["params"] = np.zeros(3)
        rec["offset"] = 0.0
        rec["fit"] = 0.0
        if typ == TYPE_NEG:
            pid = "kodak_2383" if cat == 1 else "kodak_portra_endura"
            rec["paper"] = paper_ids.index(pid)
            rec["params"], rec["fit"] = solve_print(rec, papers[pid])
        elif typ == TYPE_SLIDE:
            rec["offset"] = slide_offset(rec["curves"])
            dmid = np.array([interp_curve(rec["curves"][:, k], rec["offset"]) for k in range(3)])
            rec["params"][0] = float(slide_rgb(dmid, rec, slide_paper(rec)) @ np.array([0.2126, 0.7152, 0.0722]))
        else:
            rec["bw_table"], rec["bw_dmax"] = bw_paper_table(rec, papers["kodak_portra_endura"])
        stocks.append(rec)
        print(f"  {name:32s} look {look_idx:3d} type {typ} paper {rec['paper']:2d} "
              f"params {np.round(rec['params'], 3)} offset {rec['offset']:+.3f} fit {rec['fit']:.4f}",
              file=sys.stderr)

    write_bin(spectra, papers, paper_ids, stocks)
    if args.preview:
        preview(args.preview, spectra, papers, paper_ids, stocks)


def slide_paper(film):
    """Viewing model for a slide: its own base is the white."""
    v = type("S", (), {})()
    v.view = D50
    white = (10.0 ** -film["base"] * D50) @ CMF
    v.white = white / white[1]
    v.m_out = M_XYZ_SRGB @ adapt_matrix(v.white, D65_XYZ)
    v.norm = (10.0 ** -film["base"] * D50) @ CMF[:, 1]
    return v


# ---------------------------------------------------------------- binary asset
# Layout (little endian, float32 unless noted):
#   'SPF1' u32 version u32 nStocks u32 nPapers u32 camN u32 nw u32 curveN u32 reserved
#   spectra[camN][camN][nw]            upsampled spectrum per (v, u) chromaticity cell
#   cmf[nw][3]  d50[nw]  d65[nw]  enlarger[nw]  filterY[nw]  filterM[nw]
#   m_xyz_to_srgb_d65[9]
#   papers: { name[32] sens[nw][3] curves[curveN][3] cd[nw][3] base[nw] view[nw] mOut[9] norm }
#   stocks: { name[32] i32 look i32 type i32 paper f32 params[3] f32 offset
#             sens[nw][3] curves[curveN][3] cd[nw][3] base[nw] bwTable[curveN] f32 bwDmax
#             slideOut[9] slideNorm }   (slides: D50 light box, own base = white)
VERSION = 1


def write_bin(spectra, papers, paper_ids, stocks):
    out = bytearray()
    f32 = lambda a: np.asarray(a, dtype="<f4").tobytes()
    name32 = lambda s: s.encode("ascii")[:31].ljust(32, b"\0")
    out += b"SPF1" + struct.pack("<7I", VERSION, len(stocks), len(papers), CAM_N, NW, CURVE_N, 0)
    out += f32(spectra)
    out += f32(CMF) + f32(D50) + f32(D65) + f32(ENLARGER) + f32(FILTER_Y) + f32(FILTER_M)
    out += f32(M_XYZ_SRGB @ np.eye(3))
    for pid in paper_ids:
        pp = papers[pid]
        out += name32(pid) + f32(pp.sens) + f32(pp.curves) + f32(pp.cd) + f32(pp.base) + f32(pp.view)
        out += f32(pp.m_out) + f32([pp.norm])
    for s in stocks:
        out += name32(s["name"]) + struct.pack("<3i", s["look"], s["type"], s["paper"])
        out += f32(s["params"]) + f32([s["offset"]])
        out += f32(s["sens"]) + f32(s["curves"]) + f32(s["cd"]) + f32(s["base"])
        out += f32(s.get("bw_table", np.zeros(CURVE_N))) + f32([s.get("bw_dmax", 0.0)])
        vp = slide_paper(s) if s["type"] == TYPE_SLIDE else None
        out += f32(vp.m_out if vp else np.eye(3)) + f32([vp.norm if vp else 1.0])
    OUT.write_bytes(bytes(out))
    print(f"wrote {OUT} ({len(out)} bytes, {len(stocks)} stocks)", file=sys.stderr)


# ---------------------------------------------------------------- LUT build (reference for the C++)
PRINT_N = 32


def build_luts(s, spectra, papers, paper_ids):
    """Per-stock tables exactly as SpectralFilm.h builds them on the phone."""
    tex = spectra @ s["sens"]                                   # (camN, camN, 3)
    # neutral reference = the (1/3, 1/3) chromaticity (ACES white), bilinear on the grid
    ref = bilinear(tex, 1.0 / 3.0, 1.0 / 3.0)
    cam = tex / (3.0 * ref)
    lut = dict(cam=cam, curves=s["curves"], type=s["type"], offset=s["offset"])
    if s["type"] == TYPE_NEG:
        paper = papers[paper_ids[s["paper"]]]
        dmax = s["curves"].max(axis=0) * 1.05
        g = [np.linspace(0, dmax[k], PRINT_N) for k in range(3)]
        D = np.stack(np.meshgrid(g[0], g[1], g[2], indexing="ij"), axis=-1)  # [c][m][y]
        rgb = print_neg(D.reshape(-1, 3), s, paper, s["params"])
        ramp = np.linspace(-4.0, 4.0, NEUTRAL_N)
        dn = np.stack([interp_curve(s["curves"][:, k], ramp) for k in range(3)], axis=-1)
        rgb = neutral_balance(rgb, print_neg(dn, s, paper, s["params"]))
        lut.update(print=srgb_oetf(np.clip(rgb, 0, 1)).reshape(PRINT_N, PRINT_N, PRINT_N, 3), dmax=dmax)
    elif s["type"] == TYPE_SLIDE:
        dmax = s["curves"].max(axis=0) * 1.05
        g = [np.linspace(0, dmax[k], PRINT_N) for k in range(3)]
        D = np.stack(np.meshgrid(g[0], g[1], g[2], indexing="ij"), axis=-1)
        vp = slide_paper(s)
        lin = reflect_to_srgb(vp, D.reshape(-1, 3) @ s["cd"].T + s["base"])
        dmid = np.array([interp_curve(s["curves"][:, k], s["offset"]) for k in range(3)])
        ymid = float(reflect_to_srgb(vp, dmid @ s["cd"].T + s["base"]) @ np.array([0.2126, 0.7152, 0.0722]))
        lin = MID_GREY * (np.maximum(lin, 0) / ymid) ** SLIDE_SCAN
        ramp = np.linspace(-4.0, 4.0, NEUTRAL_N) + s["offset"]
        dn = np.stack([interp_curve(s["curves"][:, k], ramp) for k in range(3)], axis=-1)
        g = reflect_to_srgb(vp, dn @ s["cd"].T + s["base"])
        lin = neutral_balance(lin, MID_GREY * (np.maximum(g, 0) / ymid) ** SLIDE_SCAN)
        lut.update(print=srgb_oetf(np.clip(lin, 0, 1)).reshape(PRINT_N, PRINT_N, PRINT_N, 3), dmax=dmax)
    else:
        lut.update(bw=s["bw_table"], dmax=np.full(3, s["bw_dmax"]))
    return lut


LUMA = np.array([0.2126, 0.7152, 0.0722])
NEUTRAL_N = 65


def neutral_balance(lin, grey_ramp):
    """Keep the grey axis grey: the print of a neutral scale (grey_ramp, linear sRGB, dark to
    light) gives a per-channel correction indexed by output luminance, applied to every entry.
    Colours keep the stock's rendering; only the crossover casts of the neutral scale go."""
    y = grey_ramp @ LUMA
    keep = (y > 1e-6) & (y >= np.maximum.accumulate(y)) & np.concatenate([[True], np.diff(y) > 1e-7])
    y, g = y[keep], grey_ramp[keep]
    corr = np.clip(y[:, None] / np.maximum(g, 1e-7), 0.05, 20.0)
    yl = np.maximum(lin @ LUMA, 0.0)
    c = np.stack([np.interp(yl, y, corr[:, k]) for k in range(3)], axis=-1)
    return lin * c


def bilinear(tex, u, v):
    x, y = u * (CAM_N - 1), v * (CAM_N - 1)
    i0, j0 = int(np.floor(x)), int(np.floor(y))
    i1, j1 = min(i0 + 1, CAM_N - 1), min(j0 + 1, CAM_N - 1)
    tx, ty = x - i0, y - j0
    return ((tex[j0, i0] * (1 - tx) + tex[j0, i1] * tx) * (1 - ty)
            + (tex[j1, i0] * (1 - tx) + tex[j1, i1] * tx) * ty)


def srgb_oetf(x):
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(np.maximum(x, 0), 1 / 2.4) - 0.055)


def srgb_eotf(x):
    return np.where(x <= 0.04045, x / 12.92, ((x + 0.055) / 1.055) ** 2.4)


def apply_luts(lut, acescg):
    """Scene-linear ACEScg (H, W, 3) -> display sRGB-encoded (H, W, 3), like the shader."""
    rgb = np.maximum(acescg, 1e-6)
    s = rgb.sum(-1, keepdims=True)
    uv = rgb[..., :2] / s
    x = uv[..., 0] * (CAM_N - 1)
    y = uv[..., 1] * (CAM_N - 1)
    i0 = np.clip(np.floor(x).astype(int), 0, CAM_N - 2)
    j0 = np.clip(np.floor(y).astype(int), 0, CAM_N - 2)
    tx, ty = (x - i0)[..., None], (y - j0)[..., None]
    cam = lut["cam"]
    t = ((cam[j0, i0] * (1 - tx) + cam[j0, i0 + 1] * tx) * (1 - ty)
         + (cam[j0 + 1, i0] * (1 - tx) + cam[j0 + 1, i0 + 1] * tx) * ty)
    loge = np.log10(np.maximum(s * t, 1e-9) / MID_GREY) + lut["offset"]
    d = np.stack([interp_curve(lut["curves"][:, k], loge[..., k]) for k in range(3)], axis=-1)
    if lut["type"] == TYPE_BW:
        x = np.clip(d[..., 1] / lut["dmax"][1], 0, 1) * (CURVE_N - 1)
        yv = np.interp(x, np.arange(CURVE_N), lut["bw"])
        return srgb_oetf(np.clip(np.repeat(yv[..., None], 3, -1), 0, 1))
    return trilinear(lut["print"], np.clip(d / lut["dmax"], 0, 1) * (PRINT_N - 1))


def trilinear(L, p):
    i0 = np.clip(np.floor(p).astype(int), 0, PRINT_N - 2)
    t = p - i0
    out = 0
    for dx in (0, 1):
        for dy in (0, 1):
            for dz in (0, 1):
                w = ((t[..., 0] if dx else 1 - t[..., 0]) * (t[..., 1] if dy else 1 - t[..., 1])
                     * (t[..., 2] if dz else 1 - t[..., 2]))
                out = out + w[..., None] * L[i0[..., 0] + dx, i0[..., 1] + dy, i0[..., 2] + dz]
    return out


def preview(outdir, spectra, papers, paper_ids, stocks):
    from PIL import Image
    outdir.mkdir(parents=True, exist_ok=True)
    m_srgb_ap1 = np.linalg.inv(M_AP1_XYZ) @ np.linalg.inv(M_XYZ_SRGB)
    # Test chart: a log grey ramp over 10 stops plus hue sweeps at three saturations.
    w, h = 512, 256
    xs = np.linspace(-5, 5, w)
    ramp = MID_GREY * 2.0 ** xs
    rows = [np.repeat(ramp[None, :, None], 3, -1).repeat(48, 0)]
    hue = np.linspace(0, 1, w, endpoint=False)
    for sat, val in ((0.35, 0.3), (0.7, 0.25), (0.95, 0.2)):
        hsv = np.stack([hue, np.full(w, sat), np.full(w, 1.0)], -1)
        import colorsys
        rgb = np.array([colorsys.hsv_to_rgb(*c) for c in hsv]) * val / 0.5
        rows.append(np.repeat(srgb_eotf(np.clip(rgb, 0, 1))[None], 69, 0) * 2.5)
    chart = np.concatenate(rows, 0)[:h]
    chart_ap1 = chart @ m_srgb_ap1.T
    photo = None
    for cand in ("/mnt/project-files/wide_look24.jpg",):
        if Path(cand).exists():
            im = Image.open(cand).convert("RGB")
            im.thumbnail((384, 512))
            photo = srgb_eotf(np.asarray(im) / 255.0) @ m_srgb_ap1.T
    tiles = []
    for s in stocks:
        lut = build_luts(s, spectra, papers, paper_ids)
        a = apply_luts(lut, chart_ap1)
        tile = (np.clip(a, 0, 1) * 255).astype(np.uint8)
        Image.fromarray(tile).save(outdir / f"{s['name']}_chart.png")
        if photo is not None:
            b = apply_luts(lut, photo)
            Image.fromarray((np.clip(b, 0, 1) * 255).astype(np.uint8)).save(outdir / f"{s['name']}_photo.jpg")
        tiles.append(tile)
    print(f"previews in {outdir}", file=sys.stderr)


if __name__ == "__main__":
    main()
