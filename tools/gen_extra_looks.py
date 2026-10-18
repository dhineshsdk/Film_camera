#!/usr/bin/env python3
"""Append the "no datasheet" stock profiles (Batch G) to the 120-look database.

Every extra look is derived from a real datasheet look (the parent) by a small set of
sensitometric operations on its density rows, so the LUT, header and index stay in sync.
Idempotent: only the first 120 base rows/entries are read, extras are rebuilt from them.

  python3 tools/gen_extra_looks.py
"""
import re, struct, sys
import numpy as np
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "phase1-skeleton/src/main"
BIN = ROOT / "assets/s24_master_120_looks.bin"
TSV = ROOT / "assets/looks_index.tsv"
HDR = ROOT / "cpp/Master120LooksDatabase.h"
BASE = 120
COLS = 256
CATS = ["COLOR_NEGATIVE_C41", "MOTION_PICTURE_ECN2", "COLOR_REVERSAL_SLIDE", "BLACK_AND_WHITE",
        "PRINT_STOCK_AND_PAPER", "DIGITAL_CINEMA_CAMERA", "FUJIFILM_SIMULATION", "CREATIVE_INSTANT_EXPIRED"]

# id, display name, parent id, box ISO, ops. Category follows the parent unless 'cat' is given.
# ops: c contrast about mid grey, s colour saturation, sh per-layer exposure shift in log10 E
# (R,G,B; + = film behaves as if it saw less light on that layer), fog = shadow fade (density
# bump in the toe, black point kept: the shader subtracts base fog, so a flat offset would vanish),
# tint per-layer density multiplier, g grain x, h halation x, hal = absolute halation.
# The printed colour-negative path (C-41 / ECN-2 / Fuji sims) re-neutralises the grey axis and
# removes the black, so casts, fade and tints die there. Any look that relies on them is moved to
# category 7 (scanned straight, no print stage); toned B&W also gets a luminance matrix.
L = []
def add(i, n, p, iso, **o): L.append((i, n, p, iso, o))

# --- CineStill / cine stocks run as stills ----------------------------------------------------
add("cinestill_50d", "CineStill 50 Daylight", "kodak_vision3_50d", 50, hal=.30, c=1.02)
add("cinestill_400d", "CineStill 400 Daylight", "kodak_vision3_250d", 400, hal=.32, g=1.35, c=1.05)
add("cinestill_800t", "CineStill 800 Tungsten", "kodak_vision3_500t", 800, hal=.55, g=1.3, c=1.08, sh=(.18, 0, -.26))
add("cinestill_850d", "CineStill 850 Daylight", "kodak_vision3_500t", 800, hal=.50, sh=(.04, 0, -.06), g=1.4, c=1.1)
add("cinestill_bwxx", "CineStill BwXX", "kodak_double_x_5222", 250, h=1.6)
add("kodak_vision3_500t_bleach", "Vision3 500T Bleach Bypass", "kodak_vision3_500t", 500, c=1.28, s=.55, fog=.03, g=1.2)
add("kodak_vision3_250d_bleach", "Vision3 250D Bleach Bypass", "kodak_vision3_250d", 250, c=1.25, s=.55, fog=.03)
add("kodak_vision3_500t_push1", "Vision3 500T Push +1", "kodak_vision3_500t", 1000, c=1.10, sh=(0, 0, .03), g=1.3)
add("kodak_vision3_200t_tungsten_day", "Vision3 200T Daylight No 85", "kodak_vision3_200t", 200, sh=(.10, .02, -.14))
add("kodak_ektachrome_64t", "Kodak Ektachrome 64T", "kodak_ektachrome_100", 64, sh=(-.08, 0, .10), c=1.03)
add("kodak_ektachrome_160t", "Kodak Ektachrome 160T", "kodak_ektachrome_100d", 160, sh=(-.09, 0, .12), g=1.3)
# --- Kodak consumer / pro colour negative -----------------------------------------------------
add("kodak_colorplus_200", "Kodak ColorPlus 200", "kodak_gold_200", 200, c=1.03, s=.96)
add("kodak_gold_100", "Kodak Gold 100", "kodak_gold_200", 100, g=.8, s=1.04)
add("kodak_gold_400", "Kodak Gold 400", "kodak_ultramax_400", 400, s=.97)
add("kodak_ultramax_800", "Kodak Ultramax 800", "kodak_portra_800", 800, c=1.12, s=1.15, sh=(-.02, 0, .03))
add("kodak_pro_image_100", "Kodak Pro Image 100", "kodak_portra_160", 100, c=1.1, s=1.05)
add("kodak_portra_160nc", "Kodak Portra 160NC", "kodak_portra_160", 160, c=.94, s=.88)
add("kodak_portra_160vc", "Kodak Portra 160VC", "kodak_portra_160", 160, c=1.08, s=1.2)
add("kodak_portra_100t", "Kodak Portra 100T", "kodak_portra_160", 100, sh=(-.09, 0, .12), g=.85)
add("kodak_portra_400_push1", "Portra 400 Push +1", "kodak_portra_400", 800, c=1.10, g=1.25)
add("kodak_portra_400_push2", "Portra 400 Push +2", "kodak_portra_400", 1600, c=1.20, g=1.5, fog=.02)
add("kodak_portra_400_pull1", "Portra 400 Pull -1", "kodak_portra_400", 200, c=.92, s=.93, g=.8)
add("kodak_ektar_100_push1", "Ektar 100 Push +1", "kodak_ektar_100", 200, c=1.10, g=1.2)
add("kodak_kodacolor_vr_200", "Kodacolor VR 200", "kodak_gold_200", 200, c=.96, s=.9, sh=(-.02, 0, .04), g=1.15)
add("kodak_kodacolor_vr_400", "Kodacolor VR 400", "kodak_ultramax_400", 400, c=.95, s=.9, g=1.15)
add("kodak_color_neg_ii_5247", "Kodak Color Negative II 5247", "kodak_exr_50d", 100, c=.96, s=.9, fog=.02, g=1.2)
add("kodak_max_versatility_800", "Kodak Max Versatility 800", "kodak_portra_800", 800, c=1.05, s=1.08)
# --- Fuji / Agfa / Konica consumer negative ---------------------------------------------------
add("fuji_superia_100", "Fujicolor Superia 100", "fuji_superia_reala", 100, c=1.05, s=1.05, sh=(0, 0, -.02))
add("fuji_superia_200", "Fujicolor Superia 200", "fujifilm_c200", 200, s=1.05, sh=(0, 0, -.02))
add("fuji_superia_premium_400", "Fujicolor Superia Premium 400", "fujifilm_xtra_400", 400, c=.97, s=.95)
add("fuji_superia_800", "Fujicolor Superia 800", "fuji_superia_1600", 800, c=.97, g=.8)
add("fuji_fujicolor_100", "Fujicolor 100", "fujifilm_c200", 100, g=.8, c=1.02)
add("fuji_industrial_100", "Fujicolor Industrial 100", "fujifilm_c200", 100, c=1.1, sh=(0, 0, .04), s=.95)
add("fuji_press_800", "Fujicolor Press 800", "fuji_superia_1600", 800, c=1.02, sh=(-.03, 0, .04), g=.85)
add("agfa_vista_plus_200", "Agfa Vista Plus 200", "agfa_vista_200", 200, s=1.08, c=1.04)
add("agfa_vista_plus_400", "Agfa Vista Plus 400", "agfa_vista_400", 400, s=1.08, c=1.04)
add("konica_centuria_400", "Konica Centuria 400", "konica_centuria_super_200", 400, g=1.3, c=1.04)
add("konica_vx_400", "Konica VX 400", "konica_centuria_super_200", 400, c=1.08, s=1.1, g=1.25)
# --- Harman / Lomography / creative colour ----------------------------------------------------
add("harman_phoenix_200", "Harman Phoenix 200", "kodak_gold_200", 200, c=1.22, s=1.05, sh=(-.20, .06, .20),
    fog=.10, tint=(1.0, .96, .80), g=1.8, h=1.6)
add("harman_phoenix_ii_200", "Harman Phoenix II 200", "kodak_gold_200", 200, c=1.22, sh=(-.18, .06, .16),
    fog=.06, tint=(1.0, .95, .75), g=1.45, h=1.4)
add("lomo_color_100", "Lomography Color Negative 100", "kodak_gold_200", 100, c=1.08, s=1.15)
add("lomo_color_400", "Lomography Color Negative 400", "kodak_ultramax_400", 400, c=1.1, s=1.15, g=1.15)
add("lomo_color_800", "Lomography Color Negative 800", "fuji_superia_1600", 800, c=1.08, s=1.12, g=.9)
add("lomochrome_color_92", "LomoChrome Color '92", "kodak_ultramax_400", 400, c=1.18, s=1.35, g=1.2, h=1.5,
    sh=(-.03, 0, .05))
add("lomochrome_purple", "LomoChrome Purple", "kodak_vision3_250d", 400, c=1.1, s=1.2, sh=(.05, .28, -.20), g=1.2)
add("lomochrome_metropolis", "LomoChrome Metropolis", "kodak_portra_160", 400, c=1.30, s=.45, fog=.06,
    sh=(0, 0, -.03), g=1.3)
add("lomochrome_turquoise", "LomoChrome Turquoise", "kodak_gold_200", 400, c=1.1, s=1.1, sh=(.22, -.05, -.22), g=1.2)
add("lomo_redscale_xr", "LomoChrome Redscale XR", "kodak_gold_200", 200, c=1.15, s=.9, sh=(-.30, .12, .35),
    tint=(1.0, .8, .45), g=1.3)
add("harman_red_125", "Harman Red 125", "kodak_gold_200", 125, c=1.15, sh=(-.28, .10, .32), tint=(1.0, .82, .5), g=1.2)
add("lomo_slide_xpro_200", "Lomography X-Pro Slide 200", "fuji_provia_400x", 200, c=1.22, s=1.25, sh=(.04, -.10, .12))
# --- Instant film -----------------------------------------------------------------------------
add("polaroid_600_color", "Polaroid 600 Color", "kodak_gold_200", 640, c=.88, s=.8, fog=.12, sh=(-.08, .05, .16),
    tint=(1.0, .95, .88), g=.6, hal=.35)
add("polaroid_sx70_color", "Polaroid SX-70 Color", "kodak_ektachrome_100", 160, c=1.0, s=.85, fog=.08,
    sh=(-.05, -.02, .08), g=.5, h=1.8)
add("polaroid_itype_color", "Polaroid i-Type Color", "fujifilm_pro_400h", 640, c=1.05, s=.9, fog=.06, g=.6, h=1.6)
add("polaroid_originals_color", "Polaroid Originals Color", "kodak_portra_400", 640, c=1.05, s=.88, fog=.07, sh=(-.04, 0, .08),
    g=.6, h=1.8)
add("impossible_project_color", "Impossible Project Color", "kodak_portra_160", 640, c=.95, s=.75, fog=.12,
    sh=(-.05, .04, .1), tint=(1.0, .95, .9), g=.7, h=1.8)
add("fuji_instax_mini_color", "Fujifilm Instax Mini", "fujifilm_pro_400h", 800, c=1.15, s=1.05, fog=.05, g=.6,
    sh=(0, -.03, .02))
add("fuji_instax_wide_color", "Fujifilm Instax Wide", "fuji_superia_reala", 800, c=1.12, s=1.05, fog=.05, g=.6)
add("fuji_fp100c", "Fujifilm FP-100C (Peel-Apart)", "fuji_astia_100f", 100, c=1.08, s=.95, fog=.04, g=.5)
add("polaroid_bw_669", "Polaroid Type 667 B&W", "ilford_panf", 3000, c=1.18, fog=.07, g=.6, h=1.5)
add("fuji_instax_bw", "Fujifilm Instax Monochrome", "ilford_fp4", 800, c=1.2, fog=.06, g=.7)
add("polaroid_bw_600", "Polaroid 600 B&W", "ilford_hp5", 640, c=1.15, fog=.08, g=.6, h=1.8)
# --- Expired film -----------------------------------------------------------------------------
add("expired_portra_400_10y", "Portra 400, 10 yr Expired", "kodak_portra_400", 320, c=.92, fog=.05, sh=(.03, .0, -.06), g=1.3)
add("expired_portra_400_20y", "Portra 400, 20 yr Expired", "kodak_portra_400", 200, c=.82, s=.8, fog=.12, sh=(.08, -.02, -.14),
    g=1.7)
add("expired_gold_200_90s", "Gold 200, 1990s Expired", "kodak_gold_200", 100, c=.88, s=.85, fog=.10, sh=(.05, -.03, -.12), g=1.6)
add("expired_ektar_100", "Ektar 100, Expired", "kodak_ektar_100", 80, c=.9, fog=.06, sh=(.04, -.02, -.08), g=1.3)
add("expired_superia_400", "Superia X-TRA 400, Expired", "fujifilm_xtra_400", 250, c=.88, s=.9, fog=.08, sh=(-.05, .06, .04), g=1.5)
add("expired_vision3_500t", "Vision3 500T, Expired", "kodak_vision3_500t", 320, c=.9, fog=.08, sh=(.04, -.02, -.08), g=1.5)
add("expired_ektachrome_e100", "Ektachrome E100, Expired", "kodak_ektachrome_e100s", 80, c=.95, fog=.08,
    sh=(.08, -.10, .02), g=1.4)
add("expired_velvia_50", "Velvia 50, Expired", "fuji_velvia_50", 40, c=.92, fog=.07, sh=(.06, -.08, -.02), g=1.3)
add("expired_provia_100f", "Provia 100F, Expired", "fujifilm_provia_100f", 80, c=.94, fog=.07, sh=(.06, -.08, .02), g=1.3)
add("expired_kodachrome_64", "Kodachrome 64, Expired", "kodak_kodachrome_64", 50, c=.92, fog=.08, sh=(-.05, .04, .10), g=1.3)
add("expired_trix_400", "Tri-X 400, Expired", "kodak_trix_400", 250, c=.85, fog=.12, g=1.5)
add("expired_hp5_plus", "HP5 Plus, Expired", "ilford_hp5", 250, c=.85, fog=.10, g=1.4)
add("expired_fp4_plus", "FP4 Plus, Expired", "ilford_fp4", 80, c=.88, fog=.09, g=1.3)
# --- Cross-processing and bleach bypass -------------------------------------------------------
add("xpro_e100_in_c41", "Ektachrome E100 Cross (C-41)", "kodak_ektachrome_e100s", 100, c=1.30, s=1.3, sh=(.06, -.18, .16), g=1.2)
add("xpro_provia_in_c41", "Provia 100F Cross (C-41)", "fujifilm_provia_100f", 100, c=1.30, s=1.25, sh=(.02, -.16, .20), g=1.2)
add("xpro_velvia_in_c41", "Velvia 50 Cross (C-41)", "fuji_velvia_50", 50, c=1.25, s=1.4, sh=(.08, -.20, .12), g=1.2)
add("xpro_astia_in_c41", "Astia 100F Cross (C-41)", "fuji_astia_100f", 100, c=1.25, s=1.2, sh=(.04, -.12, .18), g=1.2)
add("xpro_precisa_in_c41", "Agfa CT Precisa Cross (C-41)", "agfa_ct_precisa_100", 100, c=1.28, s=1.2, sh=(-.10, .12, .20), g=1.2)
add("xpro_kodachrome_c41", "Kodachrome 64 Cross (C-41)", "kodak_kodachrome_64", 64, c=1.2, s=1.2, sh=(.04, -.14, .14), g=1.2)
add("xpro_portra_in_e6", "Portra 400 Cross (E-6)", "kodak_portra_400", 400, c=1.35, s=1.25, sh=(-.14, .08, -.10), g=1.3)
add("xpro_gold_in_e6", "Gold 200 Cross (E-6)", "kodak_gold_200", 200, c=1.40, s=1.3, sh=(-.18, .06, -.10), g=1.4)
add("xpro_superia_in_e6", "Superia X-TRA 400 Cross (E-6)", "fujifilm_xtra_400", 400, c=1.35, s=1.3, sh=(-.08, .12, .12), g=1.4)
add("bleach_bypass_portra_400", "Portra 400 Bleach Bypass", "kodak_portra_400", 400, c=1.30, s=.5, fog=.04, g=1.25)
add("bleach_bypass_gold_200", "Gold 200 Bleach Bypass", "kodak_gold_200", 200, c=1.28, s=.5, fog=.04, g=1.25)
add("bleach_bypass_ektar_100", "Ektar 100 Bleach Bypass", "kodak_ektar_100", 100, c=1.28, s=.55, fog=.03)
# --- Black & white: push/pull, alt brands, toning ---------------------------------------------
add("kodak_trix_400_push1", "Tri-X 400 Push +1 (800)", "kodak_trix_400", 800, c=1.10, g=1.2)
add("kodak_trix_400_push2", "Tri-X 400 Push +2 (1600)", "kodak_trix_400", 1600, c=1.22, g=1.45, fog=.02)
add("kodak_trix_400_push3", "Tri-X 400 Push +3 (3200)", "kodak_trix_400", 3200, c=1.34, g=1.75, fog=.03)
add("ilford_hp5_push1", "HP5 Plus Push +1 (800)", "ilford_hp5", 800, c=1.10, g=1.2)
add("ilford_hp5_push2", "HP5 Plus Push +2 (1600)", "ilford_hp5", 1600, c=1.22, g=1.45)
add("ilford_hp5_push3", "HP5 Plus Push +3 (3200)", "ilford_hp5", 3200, c=1.34, g=1.75, fog=.02)
add("kodak_tmax_400_push2", "T-Max 400 Push +2 (1600)", "kodak_tmax_400", 1600, c=1.2, g=1.35)
add("ilford_delta_400_push2", "Delta 400 Push +2 (1600)", "ilford_delta400", 1600, c=1.2, g=1.35)
add("kodak_trix_400_pull1", "Tri-X 400 Pull -1 (200)", "kodak_trix_400", 200, c=.9, g=.85)
add("kentmere_pan_100", "Kentmere Pan 100", "ilford_fp4", 100, c=1.06, g=1.1)
add("kentmere_pan_400", "Kentmere Pan 400", "ilford_hp5", 400, c=1.08, g=1.2)
add("rollei_retro_80s", "Rollei Retro 80S", "agfa_apx_100", 80, c=1.1, g=.9)
add("rollei_retro_400s", "Rollei Retro 400S", "agfa_apx_400", 400, c=1.1, g=1.0)
add("rollei_ir_400", "Rollei Infrared 400", "ilford_sfx200", 400, c=1.18, sh=(-.20, -.05, .08), g=1.3, h=1.8)
add("rollei_ortho_25", "Rollei Ortho 25 Plus", "ilford_ortho_plus", 25, c=1.12, g=.7)
add("rollei_superpan_200", "Rollei Superpan 200", "ilford_hp5", 200, c=1.0, g=1.0)
add("adox_chs_100", "Adox CHS 100 II", "ilford_fp4", 100, c=1.08, g=.9)
add("adox_silvermax", "Adox Silvermax 100", "kodak_tmax_100", 100, c=1.15, g=.85)
add("orwo_n74_plus", "ORWO N74 Plus", "orwo_un54", 400, g=1.2, c=1.02)
add("efke_kb25", "Efke KB25", "kodak_plus_x_125", 25, c=1.12, g=.8)
add("efke_kb100", "Efke KB100", "ilford_fp4", 100, c=1.12, g=1.1)
add("shanghai_gp3", "Shanghai GP3 100", "agfa_apx_100", 100, c=1.1, g=1.2, fog=.02)
add("lomo_lady_grey_400", "Lomography Lady Grey 400", "ilford_hp5", 400, c=.96, g=1.1)
add("lomo_earl_grey_100", "Lomography Earl Grey 100", "ilford_fp4", 100, c=1.0, g=.9)
add("lomo_berlin_kino_400", "Lomography Berlin Kino 400", "kodak_trix_400", 400, c=1.1, g=1.15)
add("kodak_panatomic_x", "Kodak Panatomic-X", "ilford_panf", 32, c=1.1, g=.6)
add("toned_selenium", "Tri-X 400, Selenium Toned", "kodak_trix_400", 400, tint=(1.0, .97, 1.08), c=1.06)
add("toned_sepia", "FP4 Plus, Sepia Toned", "ilford_fp4", 125, tint=(1.0, .86, .62), c=1.0)
add("toned_cyanotype", "Cyanotype Print", "ilford_fp4", 125, tint=(.55, .9, 1.0), c=1.12, fog=.04)
add("toned_gold", "HP5 Plus, Gold Toned", "ilford_hp5", 400, tint=(.98, 1.0, 1.1), c=1.04)
# --- Classic colour and slide ------------------------------------------------------------------
add("technicolor_3strip", "Technicolor 3-Strip", "fuji_velvia_50", 8, c=1.05, s=1.4, sh=(-.04, .02, .03), g=.5)
add("technicolor_2strip", "Technicolor 2-Strip", "kodak_kodachrome_25", 8, c=1.0, s=.8, sh=(0, 0, .30), g=.5)
add("kodachrome_40_type_a", "Kodachrome 40 Type A", "kodak_kodachrome_25", 40, sh=(-.08, 0, .12), g=.8)
add("agfachrome_1000rs", "Agfachrome 1000RS", "agfa_rsx2_200", 1000, c=.97, g=1.8)
add("sensia_100", "Fujichrome Sensia 100", "fujifilm_provia_100f", 100, c=.97, s=.95, sh=(0, 0, -.02))
add("kodak_e100vs_push1", "Ektachrome E100VS Push +1", "kodak_ektachrome_e100vs", 200, c=1.12, g=1.25)
add("provia_100f_push2", "Provia 100F Push +2", "fujifilm_provia_100f", 400, c=1.2, g=1.5)

# Near-clone variants (tone/colour within ~0.1 of the parent) get a clearer character.
PUNCH = {
    "cinestill_bwxx": dict(c=1.14, g=1.25, hal=.35), "lomo_earl_grey_100": dict(c=.88),
    "rollei_superpan_200": dict(c=1.14), "kodak_gold_100": dict(c=1.12, s=1.18), "kodak_gold_400": dict(c=1.14, s=1.12),
    "orwo_n74_plus": dict(c=1.12), "fuji_superia_200": dict(c=1.16, s=1.12), "cinestill_50d": dict(c=1.12, s=1.1),
    "agfachrome_1000rs": dict(c=.85, s=.88), "fuji_fujicolor_100": dict(c=1.16, s=1.12), "fuji_fp100c": dict(c=1.2, s=.85),
    "sensia_100": dict(c=.86, s=.84), "kodak_colorplus_200": dict(c=1.14, s=.88), "fuji_superia_800": dict(c=.86, s=.9),
    "konica_centuria_400": dict(c=1.16, s=1.1), "lomo_lady_grey_400": dict(c=.84), "agfa_vista_plus_200": dict(c=1.14, s=1.16),
    "agfa_vista_plus_400": dict(c=1.14, s=1.16), "fuji_superia_premium_400": dict(c=.88, s=.84),
    "fuji_press_800": dict(c=1.12), "kentmere_pan_100": dict(c=1.14),
}
L[:] = [(i, n, p, iso, {**o, **PUNCH.get(i, {})}) for i, n, p, iso, o in L]

# ---------------------------------------------------------------------------------------------
def parse_header(text):
    ents = {}
    for m in re.finditer(r'^    \{(\d+), LookCategory::(\w+), "(\w+)", "([^"]*)", (.*)\},$', text, re.M):
        if int(m.group(1)) < BASE:
            ents[m.group(3)] = (int(m.group(1)), m.group(2), m.group(4), m.group(5))
    return ents

def floats(s):
    return [float(x) for x in re.findall(r'-?\d+\.\d+', s)]

SPLIT_LIMIT = 0.20
CAST_GAIN = 1.5
CAST_LIMIT = 0.30

def gam_of(ent):
    return np.array(floats(ent[3])[6:9])

def creative_look(cat, o):
    return CATS.index(cat) in (0, 1, 3, 6) and any(o.get(k) for k in ("sh", "fog", "tint"))

def grey_split(row, gam):
    """Largest per-channel deviation (log10) of the scanned neutral ramp, logE -2.5..+1.5."""
    slide = row[0, 1] > row[-1, 1]
    x = np.arange(int(COLS * (3 - 2.5) / 6), int(COLS * (3 + 1.5) / 6))
    mid = 0.5 * (row[127, :3] + row[128, :3])
    lg = (row[x, :3] - mid) / np.maximum(gam, .2) * (-0.8 if slide else 1.0)
    return float(np.abs(lg - lg.mean(axis=1, keepdims=True)).max())

def neutralise(row, gam):
    """Give all three layers the same gamma-normalised curve (the print stage would have done this)."""
    mid = 0.5 * (row[127, :3] + row[128, :3])
    g = np.maximum(gam, .2)
    n = ((row[:, :3] - mid) / g).mean(axis=1, keepdims=True)
    out = row.copy()
    out[:, :3] = mid + n * g
    return out

def transform(row, o):
    d = row.copy()
    mid = 0.5 * (COLS - 1)
    sh = o.get("sh", (0, 0, 0))
    if any(sh):
        x = np.arange(COLS, dtype=np.float64)
        for k in range(3):
            # +sh: this layer sees less light, so its curve moves to higher exposure
            d[:, k] = np.interp(x - sh[k] / 6.0 * (COLS - 1), x, row[:, k])
    pivot = d[int(mid), :3].copy()
    c = o.get("c", 1.0)
    d[:, :3] = pivot + c * (d[:, :3] - pivot)
    s = o.get("s", 1.0)
    if s != 1.0:
        m = d[:, :3].mean(axis=1, keepdims=True)
        d[:, :3] = m + s * (d[:, :3] - m)
    t = o.get("tint")
    if t:
        d[:, :3] *= np.array(t)
    f = o.get("fog", 0.0) * 3.0
    if f:
        tt = np.clip(np.arange(COLS) / mid, 0.0, 1.0)
        w = (np.sin(np.pi * tt) ** 1.2)[:, None]
        w[int(mid) + 1:] = 0.0
        slide = row[0, 1] > row[-1, 1]            # reversal: more light = less density, so fade subtracts
        d[:, :3] += (-f if slide else f) * w
    d[:, :3] = np.maximum(d[:, :3], row[:, :3].min(axis=0))
    return d

def main():
    raw = BIN.read_bytes()
    assert raw[:4] == b"S24M"
    _, rows, cols = struct.unpack("<III", raw[4:16])
    assert cols == COLS and rows >= BASE
    lut = np.frombuffer(raw[16:], dtype="<f4").reshape(rows, cols, 4)[:BASE].astype(np.float64)

    text = HDR.read_text()
    ents = parse_header(text)
    ids = {}
    for line in TSV.read_text().splitlines():
        p = line.split("\t")
        if int(p[0]) < BASE: ids[p[2]] = p
    assert len(ids) == BASE and len(ents) == BASE

    new_rows, tsv, hdr, isos = [], [], [], []
    seen = set(ids)
    for i, (lid, name, parent, iso, o) in enumerate(L):
        assert lid not in seen, lid
        seen.add(lid)
        pidx, cat, _, rest = ents[parent]
        nums = floats(rest)
        dmin, dmax, gam, mat, hal, grain = nums[0:3], nums[3:6], nums[6:9], nums[9:18], nums[18], nums[19]
        o = dict(o)
        src = lut[pidx]
        is_creative = creative_look(cat, o)
        cast = np.zeros(3)
        if is_creative:
            if CATS.index(cat) in (0, 1, 6):
                src = neutralise(src, gam_of(ents[parent]))
            # Casts go through the look's matrix (exposure scale per layer): the shader pins mid grey
            # of the *curve* to neutral, so a curve shift only shows in the toe and shoulder. Capped,
            # because there is no grey-axis correction in category 7 and big casts turn garish.
            cast = np.array(o.pop("sh", (0, 0, 0)), dtype=float)
            cast -= cast.mean()
            cast *= CAST_GAIN
            cast *= min(1.0, CAST_LIMIT / max(np.abs(cast).max(), 1e-6))
        row = transform(src, o)
        if is_creative:
            for _ in range(40):                       # tints add slope differences: keep them bounded too
                if grey_split(row, gam_of(ents[parent])) <= SPLIT_LIMIT: break
                if o.get("tint"): o["tint"] = tuple(1 + (v - 1) * 0.92 for v in o["tint"])
                row = transform(src, o)
            print("%-34s cast %s tint-split %.2f" % (lid, np.round(cast, 2).tolist(), grey_split(row, gam_of(ents[parent]))))
        row[:, 3] = lut[pidx][:, 3]
        # shift sign: increasing curve keeps its max as D-max, decreasing (slide) as its min row start
        if is_creative:
            cat = "CREATIVE_INSTANT_EXPIRED"
            if CATS.index(ents[parent][1]) == 3:
                mat = [0.2722, 0.6741, 0.0537] * 3          # panchromatic toned B&W: tint needs a colour output
            for c in range(3):                              # +cast = layer sees less light
                mat[3 * c:3 * c + 3] = [v * 10 ** (-cast[c]) for v in mat[3 * c:3 * c + 3]]
        new_dmax = [float(np.abs(row[:, k]).max()) if lut[pidx][:, :3].max() > 1.05 else dmax[k] for k in range(3)]
        gam2 = gam   # contrast comes from the stretched density; scaling gamma too would cancel it in the shader
        new_rows.append(row)
        idx = BASE + i
        hdr.append('    {%d, LookCategory::%s, "%s", "%s", {%s}, {%s}, {%s}, {%s}, %.2ff, %.2ff},' % (
            idx, cat, lid, name, ", ".join("%.3ff" % x for x in dmin), ", ".join("%.3ff" % x for x in new_dmax),
            ", ".join("%.3ff" % x for x in gam2), ", ".join("%.4ff" % x for x in mat),
            min(o.get('hal', hal * o.get('h', 1.0)), 0.6), min(grain * o.get("g", 1.0), 1.0)))
        tsv.append("%d\t%d\t%s\t%s\t%d" % (idx, CATS.index(cat), lid, name, iso))
        isos.append(iso)

    n = BASE + len(L)
    out = np.concatenate([lut, np.array(new_rows)]).astype("<f4")
    BIN.write_bytes(b"S24M" + struct.pack("<III", 2, n, COLS) + out.tobytes())
    TSV.write_text("\n".join(TSV.read_text().splitlines()[:BASE] + tsv) + "\n")

    # header: truncate any previous extras, then append
    lines = text.split("\n")
    out_l, in_tab = [], False
    for ln in lines:
        m = re.match(r'^    \{(\d+), LookCategory::', ln)
        if m and int(m.group(1)) >= BASE: continue
        out_l.append(ln)
    text = "\n".join(out_l)
    text = re.sub(r'kTotalMasterLooks = \d+;', 'kTotalMasterLooks = %d;' % n, text)
    # ISO table: base 120 values followed by the extras
    m = re.search(r'(kMasterLookBoxIso\[kTotalMasterLooks\] = \{\n)(.*?)(\n\};)', text, re.S)
    base_iso = [int(x) for x in re.findall(r'\d+', re.sub(r'//.*', '', m.group(2)))][:BASE]
    allv = base_iso + isos
    body = []
    for k in range(0, n, 10):
        body.append("    " + ", ".join("%4d" % v for v in allv[k:k + 10]) + ",  // %d-%d" % (k, min(k + 9, n - 1)))
    text = text[:m.start(2)] + "\n".join(body) + text[m.end(2):]
    # entries: append after last base entry
    marker = '"fuji_sim_sepia"'
    pos = text.index(marker)
    eol = text.index("\n", pos)
    text = text[:eol + 1] + "\n".join(hdr) + "\n" + text[eol + 1:]
    HDR.write_text(text)
    print("looks:", n, "extras:", len(L))

main()
