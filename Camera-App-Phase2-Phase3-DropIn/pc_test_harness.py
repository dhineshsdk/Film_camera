#!/usr/bin/env python3
import struct
import numpy as np
import cv2

def load_s24_dng(path):
    with open(path, "rb") as f:
        full = f.read()
    endian = "<" if full[:2] == b"II" else ">"
    _, ifd_offset = struct.unpack(endian + "HI", full[2:8])
    num_entries = struct.unpack(endian + "H", full[ifd_offset:ifd_offset+2])[0]
    pos = ifd_offset + 2
    TYPE_SIZES = {1:1, 2:1, 3:2, 4:4, 5:8, 6:1, 7:1, 8:2, 9:4, 10:8, 11:4, 12:8}
    meta = {}
    for _ in range(num_entries):
        tag, ttype, count, val_off = struct.unpack(endian + "HHII", full[pos:pos+12])
        pos += 12
        tsize = TYPE_SIZES.get(ttype, 1) * count
        raw_bytes = full[pos-4:pos-4+tsize] if tsize <= 4 else full[val_off:val_off+tsize]
        if ttype == 3:
            val = struct.unpack(endian + f"{count}H", raw_bytes)
        elif ttype == 4:
            val = struct.unpack(endian + f"{count}I", raw_bytes)
        elif ttype in (1, 7):
            val = list(raw_bytes)
        elif ttype == 5:
            rats = struct.unpack(endian + f"{count*2}I", raw_bytes)
            val = [rats[2*i]/max(rats[2*i+1],1) for i in range(count)]
        elif ttype == 10:
            rats = struct.unpack(endian + f"{count*2}i", raw_bytes)
            val = [rats[2*i]/max(rats[2*i+1],1) for i in range(count)]
        else:
            continue
        meta[tag] = val

    w = meta[256][0]
    h = meta[257][0]
    raw_offset = meta[273][0]
    cfa_pattern = meta.get(33422, [1, 2, 0, 1]) # [1,2,0,1] = GBRG, [0,1,1,2] = RGGB
    white_level = float(meta.get(50717, [1023])[0])
    as_shot_neutral = np.array(meta.get(50728, [0.959, 1.0, 0.376]), dtype=np.float32)
    fm1 = np.array(meta.get(50964, [0.624, 0.252, 0.0889, 0.2168, 0.8779, -0.0947, 0.0117, -0.4023, 1.2148]), dtype=np.float32).reshape((3, 3))
    fm2 = np.array(meta.get(50965, fm1.flatten()), dtype=np.float32).reshape((3, 3))
    raw = np.frombuffer(full, dtype=np.uint16, count=w*h, offset=raw_offset).reshape((h, w))
    return raw, w, h, cfa_pattern, white_level, as_shot_neutral, 0.5 * (fm1 + fm2)

def demosaic_exact_cfa(bayer, cfa_pattern):
    """Exact Malvar-He-Cutler / Bilinear demosaic matching our Vulkan shader's CFA indexing."""
    h, w = bayer.shape
    # Pad by 2 pixels with reflection (preserving 2x2 parity)
    pad = np.pad(bayer, ((2, 2), (2, 2)), mode="reflect")
    # Horizontal, Vertical, and Diagonal neighbor averages
    ns_avg = 0.5 * (pad[1:-3, 2:-2] + pad[3:-1, 2:-2])
    ew_avg = 0.5 * (pad[2:-2, 1:-3] + pad[2:-2, 3:-1])
    cross_avg = 0.5 * (ns_avg + ew_avg)
    diag_avg = 0.25 * (pad[1:-3, 1:-3] + pad[1:-3, 3:-1] + pad[3:-1, 1:-3] + pad[3:-1, 3:-1])

    rgb = np.zeros((h, w, 3), dtype=np.float32)
    # cfa_pattern is [c(0,0), c(1,0), c(0,1), c(1,1)] where 0=R, 1=G, 2=B
    for py in range(2):
        for px in range(2):
            c = cfa_pattern[py * 2 + px]
            c_right = cfa_pattern[py * 2 + (1 - px)]
            if c == 0: # Red pixel
                rgb[py::2, px::2, 0] = bayer[py::2, px::2]
                rgb[py::2, px::2, 1] = cross_avg[py::2, px::2]
                rgb[py::2, px::2, 2] = diag_avg[py::2, px::2]
            elif c == 2: # Blue pixel
                rgb[py::2, px::2, 0] = diag_avg[py::2, px::2]
                rgb[py::2, px::2, 1] = cross_avg[py::2, px::2]
                rgb[py::2, px::2, 2] = bayer[py::2, px::2]
            else: # Green pixel
                rgb[py::2, px::2, 1] = bayer[py::2, px::2]
                if c_right == 0: # Gr (Red is horizontal neighbor, Blue is vertical neighbor)
                    rgb[py::2, px::2, 0] = ew_avg[py::2, px::2]
                    rgb[py::2, px::2, 2] = ns_avg[py::2, px::2]
                else: # Gb (Blue is horizontal neighbor, Red is vertical neighbor)
                    rgb[py::2, px::2, 0] = ns_avg[py::2, px::2]
                    rgb[py::2, px::2, 2] = ew_avg[py::2, px::2]
    return rgb

def process_phase2_acescg(raw, cfa_pattern, white_level, as_shot_neutral, forward_matrix):
    bayer = np.clip(raw.astype(np.float32) / white_level, 0.0, 1.0)
    rgb = demosaic_exact_cfa(bayer, cfa_pattern)

    # Note: In DNG 1.4 spec, ForwardMatrix maps unit [0..1] white-balanced camera RGB -> XYZ(D50).
    # However, under extreme monochrome/colored LED room light, AsShotNeutral can have huge B gain (2.66x).
    # In "Neutral WB" mode we damp extreme chroma gains smoothly; in "Film-True" we use D55 daylight balance.
    wb_gains = as_shot_neutral[1] / np.maximum(as_shot_neutral, 1e-4)
    # Clamp extreme single-channel LED spikes so ambient mood is preserved without nuclear blue/red clipping
    wb_gains = np.clip(wb_gains, 0.65, 1.85)
    rgb_wb = rgb * wb_gains.reshape((1, 1, 3))

    M_XYZ_D50_to_ACEScg = np.array([
        [ 1.7048586, -0.3217799, -0.2367812],
        [-0.6723159,  1.6129003,  0.0177516],
        [ 0.0110566, -0.0082800,  0.9886214]
    ], dtype=np.float32)
    M_cam_to_acescg = M_XYZ_D50_to_ACEScg @ forward_matrix

    acescg = np.einsum("ij,hwj->hwi", M_cam_to_acescg, rgb_wb)
    acescg = np.maximum(acescg, 0.0)

    luma = 0.2722 * acescg[..., 0] + 0.6741 * acescg[..., 1] + 0.0537 * acescg[..., 2]
    exp_scale = 0.22 / max(float(np.percentile(luma, 60)), 1e-4)
    acescg *= exp_scale

    # Rotate 90 degrees CW for upright portrait orientation
    acescg = np.rot90(acescg, k=3)
    return acescg

def richards_hd(log_e, d_min, d_max, gamma, x0, nu, polarity_s=1.0):
    span = np.maximum(d_max - d_min, 1e-4)
    k = (gamma / span) * np.power(1.0 + nu, (1.0 + nu) / nu)
    arg = np.clip(-polarity_s * k * (log_e - x0), -40.0, 40.0)
    return d_min + span / np.power(1.0 + nu * np.exp(arg), 1.0 / nu)

def acescg_to_srgb_u8(acescg):
    M_AP1_to_sRGB = np.array([
        [ 1.7050515, -0.6217921, -0.0832594],
        [-0.1302564,  1.1408048, -0.0105484],
        [-0.0240033, -0.1289690,  1.1529723]
    ], dtype=np.float32)
    srgb = np.maximum(np.einsum("ij,hwj->hwi", M_AP1_to_sRGB, acescg), 0.0)
    # ACES Filmic shoulder
    a, b, c, d, e = 2.51, 0.03, 2.43, 0.59, 0.14
    mapped = np.clip((srgb * (a * srgb + b)) / (srgb * (c * srgb + d) + e), 0.0, 1.0)
    disp = np.power(mapped, 1.0 / 2.2)
    return np.clip(disp * 255.0, 0, 255).astype(np.uint8)

def emulate_stock(acescg, stock_name):
    img = acescg.copy()

    if stock_name == "Phase 2: Linear ACEScg":
        return acescg_to_srgb_u8(img)

    if stock_name == "CineStill 800T":
        over = np.maximum(img - 0.45, 0.0)
        hal_r = cv2.GaussianBlur(over[..., 0], (0, 0), sigmaX=16.0)
        hal_g = cv2.GaussianBlur(over[..., 1], (0, 0), sigmaX=6.0)
        img[..., 0] += 0.95 * hal_r + 0.10 * hal_g
        img[..., 1] += 0.25 * hal_r + 0.35 * hal_g
        img *= np.array([0.94, 0.99, 1.14], dtype=np.float32)

    # Log10 scene exposure relative to middle grey (0.18)
    log_e = np.log10(np.maximum(img / 0.18, 1e-5))

    if stock_name == "Kodak Portra 400":
        # Negative H&D curve (fitted from spektrafilm-profiles/kodak_portra_400.json)
        d_neg = richards_hd(log_e,
            np.array([0.0, 0.0, 0.0]), np.array([2.1131, 1.9024, 2.2929]),
            np.array([0.6670, 0.6025, 0.7065]), np.array([0.0, 0.0, 0.0]),
            np.array([0.85, 0.85, 0.85]), 1.0)
        # Print / Scanner inversion: Normalize net density around mid-grey D_mid
        d_mid = richards_hd(np.zeros((1, 1, 3)),
            np.array([0.0, 0.0, 0.0]), np.array([2.1131, 1.9024, 2.2929]),
            np.array([0.6670, 0.6025, 0.7065]), np.array([0.0, 0.0, 0.0]),
            np.array([0.85, 0.85, 0.85]), 1.0)
        # Positive print exposure logE_print = -(d_neg - d_mid); positive paper density rises with logE_print
        # So transmitted/reflected positive light I_out = 0.18 * 10^(+gamma_print * (d_neg - d_mid))!
        # WAIT: In a negative, higher scene light -> HIGHER d_neg -> LESS light hits print paper -> LOWER paper density -> HIGHER reflected print light!
        # That's why I_pos = 0.18 * 10^(+gamma_scan * (d_neg - d_mid))!
        pos = 0.20 * np.power(10.0, np.array([1.45, 1.58, 1.38]) * (d_neg - d_mid))
        return acescg_to_srgb_u8(pos)

    elif stock_name == "Kodak Ektar 100":
        d_neg = richards_hd(log_e,
            np.array([0.0, 0.0, 0.0]), np.array([1.9833, 1.8866, 2.4469]),
            np.array([0.7026, 0.6145, 0.7025]), np.array([0.0, 0.0, 0.0]),
            np.array([0.95, 0.95, 0.95]), 1.0)
        d_mid = richards_hd(np.zeros((1, 1, 3)),
            np.array([0.0, 0.0, 0.0]), np.array([1.9833, 1.8866, 2.4469]),
            np.array([0.7026, 0.6145, 0.7025]), np.array([0.0, 0.0, 0.0]),
            np.array([0.95, 0.95, 0.95]), 1.0)
        delta_d = d_neg - d_mid
        M_dir = np.array([[1.20, -0.10, -0.10], [-0.08, 1.18, -0.10], [-0.08, -0.10, 1.18]], dtype=np.float32)
        delta_d = np.einsum("ij,hwj->hwi", M_dir, delta_d)
        pos = 0.20 * np.power(10.0, np.array([1.65, 1.78, 1.52]) * delta_d)
        return acescg_to_srgb_u8(pos)

    elif stock_name == "Kodak Tri-X 400 (B&W)":
        bw_e = 0.245 * img[..., 0] + 0.612 * img[..., 1] + 0.143 * img[..., 2]
        blur_e = cv2.GaussianBlur(bw_e, (0, 0), sigmaX=2.5)
        bw_e = np.maximum(bw_e + 0.35 * (bw_e - blur_e), 1e-5)
        log_bw = np.log10(bw_e / 0.18)[..., None]
        d_neg = richards_hd(log_bw, 0.0, 1.9471, 0.65, 0.0, 0.80, 1.0)
        d_mid = richards_hd(0.0, 0.0, 1.9471, 0.65, 0.0, 0.80, 1.0)
        rng = np.random.default_rng(42)
        grain = rng.normal(0.0, 0.028, size=d_neg.shape).astype(np.float32)
        norm_d = np.clip(d_neg / 1.9471, 0.0, 1.0)
        d_neg = np.clip(d_neg + grain * (4.0 * norm_d * (1.0 - norm_d) + 0.08), 0.0, 1.9471)
        pos = 0.20 * np.power(10.0, 1.75 * (d_neg - d_mid))
        return acescg_to_srgb_u8(np.repeat(pos, 3, axis=2))

    elif stock_name in ("Kodak Vision3 500T + 2383", "CineStill 800T"):
        d_neg = richards_hd(log_e,
            np.array([0.0, 0.0, 0.0]), np.array([1.8379, 1.7490, 2.0273]),
            np.array([0.5403, 0.4773, 0.5960]), np.array([0.0, 0.0, 0.0]),
            np.array([0.85, 0.85, 0.85]), 1.0)
        d_mid = richards_hd(np.zeros((1, 1, 3)),
            np.array([0.0, 0.0, 0.0]), np.array([1.8379, 1.7490, 2.0273]),
            np.array([0.5403, 0.4773, 0.5960]), np.array([0.0, 0.0, 0.0]),
            np.array([0.85, 0.85, 0.85]), 1.0)
        # Print through Kodak 2383 (gamma ~ 3.1 on negative gamma ~ 0.54 -> system gamma ~ 1.67)
        pos = 0.20 * np.power(10.0, np.array([1.72, 1.88, 1.58]) * (d_neg - d_mid))
        luma = (0.27 * pos[..., 0] + 0.67 * pos[..., 1] + 0.06 * pos[..., 2])[..., None]
        shadow_weight = np.clip(1.0 - luma * 3.5, 0.0, 1.0)
        pos = pos * (1.0 - 0.12 * shadow_weight) + shadow_weight * np.array([0.003, 0.011, 0.018])
        return acescg_to_srgb_u8(pos)

def main():
    raw, w, h, cfa, wl, neutral, fm = load_s24_dng("S24Film/IMG_20261015_004850_312.dng")
    acescg = process_phase2_acescg(raw, cfa, wl, neutral, fm)
    small_aces = cv2.resize(acescg, (900, 1200), interpolation=cv2.INTER_AREA)

    stocks = [
        "Phase 2: Linear ACEScg",
        "Kodak Portra 400",
        "Kodak Ektar 100",
        "Kodak Tri-X 400 (B&W)",
        "Kodak Vision3 500T + 2383",
        "CineStill 800T"
    ]
    panels = []
    for st in stocks:
        rgb_u8 = emulate_stock(small_aces, st)
        bgr = cv2.cvtColor(rgb_u8, cv2.COLOR_RGB2BGR)
        cv2.rectangle(bgr, (0, 0), (900, 58), (15, 15, 15), -1)
        cv2.putText(bgr, st, (22, 40), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 230, 118), 2, cv2.LINE_AA)
        panels.append(bgr)

    row1 = np.hstack(panels[:3])
    row2 = np.hstack(panels[3:])
    sheet = np.vstack([row1, row2])
    out_file = "/working_dir/c_87faf49fd9184e31/artifacts/file_generation/ttl=63d/output/S24_Phase2_Phase3_DNG_Contact_Sheet.png"
    cv2.imwrite(out_file, sheet)
    print("Saved corrected Contact Sheet to:", out_file)

if __name__ == "__main__":
    main()
