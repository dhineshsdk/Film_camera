import re

with open('phase0_dumpsys_camera.txt', 'r', encoding='utf-8') as f:
    text = f.read()

# Split by Camera HAL device device@1.1/internal/X
dev_blocks = re.split(r'== Camera HAL device device@1\.1/internal/(\d+) \(v1\.2\) static information: ==', text)

devices = {}
for i in range(1, len(dev_blocks), 2):
    dev_id = dev_blocks[i]
    content = dev_blocks[i+1]
    
    # facing
    facing_m = re.search(r'android\.lens\.facing\s*\([^\)]+\):\s*byte\[1\]\s*\n\s*\[(\d+)\]', content)
    facing_val = facing_m.group(1) if facing_m else "?"
    facing = "BACK" if facing_val == "1" else ("FRONT" if facing_val == "0" else facing_val)
    
    # focal length
    focal_m = re.search(r'android\.lens\.info\.availableFocalLengths\s*\([^\)]+\):\s*float\[\d+\]\s*\n\s*\[([^\]]+)\]', content)
    focal = focal_m.group(1).strip() if focal_m else "?"
    
    # activeArraySize
    active_m = re.search(r'android\.sensor\.info\.activeArraySize\s*\([^\)]+\):\s*int32\[4\]\s*\n\s*\[([^\]]+)\]', content)
    active = active_m.group(1).strip() if active_m else "?"
    
    # pixelArraySize
    pixel_m = re.search(r'android\.sensor\.info\.pixelArraySize\s*\([^\)]+\):\s*int32\[2\]\s*\n\s*\[([^\]]+)\]', content)
    pixel = pixel_m.group(1).strip() if pixel_m else "?"
    
    # colorFilterArrangement (0=RGGB, 1=GRBG, 2=GBRG, 3=BGGR, 4=RGB)
    cfa_m = re.search(r'android\.sensor\.info\.colorFilterArrangement\s*\([^\)]+\):\s*byte\[1\]\s*\n\s*\[(\d+)\]', content)
    cfa_val = cfa_m.group(1) if cfa_m else "?"
    cfa_names = {"0": "RGGB", "1": "GRBG", "2": "GBRG", "3": "BGGR", "4": "RGB"}
    cfa = cfa_names.get(cfa_val, cfa_val)
    
    # availableCapabilities (3 = RAW capability)
    caps_m = re.search(r'android\.request\.availableCapabilities\s*\([^\)]+\):\s*byte\[\d+\]\s*\n\s*\[([^\]]+)\]', content)
    caps = caps_m.group(1).strip() if caps_m else "?"
    has_raw = "3" in caps.split() or "RAW" in caps
    
    # Physical camera IDs
    phys_m = re.search(r'android\.logicalMultiCamera\.physicalIds\s*\([^\)]+\):\s*byte\[\d+\]\s*\n\s*\[([^\]]+)\]', content)
    phys = phys_m.group(1).strip() if phys_m else "None"
    
    # Formats / Stream configs (check if RAW10 0x25, RAW12 0x26, RAW_SENSOR 0x20 exist)
    # 0x20 = 32 (RAW_SENSOR), 0x25 = 37 (RAW10), 0x26 = 38 (RAW12), 0x36 = 54 (YCBCR_P010)
    has_raw_sensor = "0x20 " in content or "format: 32" in content or "RAW_SENSOR" in content
    has_raw10 = "0x25 " in content or "format: 37" in content or "RAW10" in content
    has_raw12 = "0x26 " in content or "format: 38" in content or "RAW12" in content
    has_p010 = "0x36 " in content or "format: 54" in content or "YCBCR_P010" in content
    
    devices[dev_id] = {
        "facing": facing,
        "focalLength": focal,
        "activeArraySize": active,
        "pixelArraySize": pixel,
        "CFA": cfa,
        "hasRAWCapability": has_raw,
        "physicalIds": phys,
        "raw_sensor": has_raw_sensor,
        "raw10": has_raw10,
        "raw12": has_raw12,
        "p010": has_p010,
    }

print(f"{'ID':<4} | {'Facing':<6} | {'Focal':<10} | {'Active Array':<18} | {'Pixel Array':<14} | {'CFA':<6} | {'RAW Cap':<7} | {'RAW_S':<5} | {'P010':<5} | {'Physical IDs'}")
print("-" * 110)
for dev_id, d in sorted(devices.items(), key=lambda x: int(x[0])):
    print(f"{dev_id:<4} | {d['facing']:<6} | {d['focalLength']:<10} | {d['activeArraySize']:<18} | {d['pixelArraySize']:<14} | {d['CFA']:<6} | {str(d['hasRAWCapability']):<7} | {str(d['raw_sensor']):<5} | {str(d['p010']):<5} | {d['physicalIds']}")

# Check concurrent ID combinations
conc_m = re.search(r'== Concurrent ID combinations:\s*(\d+)\s*==\s*\n([^=]+)', text)
if conc_m:
    print("\n=== Concurrent ID Combinations ===")
    print("Count:", conc_m.group(1))
    print(conc_m.group(2).strip())
