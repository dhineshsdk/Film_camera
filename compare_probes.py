import json

with open('probe_normal.json', 'r') as f:
    norm = json.load(f)

with open('probe_spoofed.json', 'r') as f:
    spoo = json.load(f)

print("=== PACKAGE NAMES ===")
print("Normal :", norm.get('packageName'))
print("Spoofed:", spoo.get('packageName'))

print("\n=== CAMERA ID LIST ===")
print("Normal Camera IDs :", norm.get('cameraIdList'))
print("Spoofed Camera IDs:", spoo.get('cameraIdList'))

print("\n=== IMU TEST RESULTS ===")
print("Normal Gyro Hz :", norm['imuTest'].get('gyroFrequencyHz'), "| Accel Hz:", norm['imuTest'].get('accelFrequencyHz'))
print("Spoofed Gyro Hz:", spoo['imuTest'].get('gyroFrequencyHz'), "| Accel Hz:", spoo['imuTest'].get('accelFrequencyHz'))
print("Gyro Name      :", norm['imuTest'].get('gyroName'))
print("Gyro Vendor    :", norm['imuTest'].get('gyroVendor'))

print("\n=== PER-CAMERA DETAILED BREAKDOWN (NORMAL) ===")
for c in norm['cameras']:
    cid = c['id']
    facing = c['facing']
    focal = c.get('focalLength')
    active = c.get('activeArraySize')
    cfa = c.get('cfaName')
    white = c.get('whiteLevel')
    black = c.get('blackLevelPattern')
    exp_ms = c.get('maxExposureMs')
    iso = c.get('isoRange')
    has_raw = c.get('hasRawCapability')
    phys = c.get('physicalCameraIds')
    streams = list(c.get('streamConfigurations', {}).keys())
    print(f"Cam {cid:>2} [{facing}]: Focal={focal}mm, Active={active}, CFA={cfa}, WhiteLevel={white}, Black={black}, MaxExp={exp_ms}ms, ISO={iso}, RAWCap={has_raw}, PhysIDs={phys}")
    print(f"       Formats: {streams}")
    # print sizes for RAW_SENSOR or RAW10 if present
    for fmt in ['RAW_SENSOR (32)', 'RAW10 (37)', 'RAW12 (38)', 'YCBCR_P010 (54)']:
        if fmt in c.get('streamConfigurations', {}):
            s_list = c['streamConfigurations'][fmt]
            dim_fps = [f"{s['width']}x{s['height']}@{s.get('maxFps', 0):.0f}fps" for s in s_list[:3]]
            print(f"       -> {fmt}: {dim_fps}")

print("\n=== PER-CAMERA DETAILED BREAKDOWN (SPOOFED) ===")
for c in spoo['cameras']:
    cid = c['id']
    facing = c['facing']
    focal = c.get('focalLength')
    active = c.get('activeArraySize')
    cfa = c.get('cfaName')
    white = c.get('whiteLevel')
    black = c.get('blackLevelPattern')
    exp_ms = c.get('maxExposureMs')
    iso = c.get('isoRange')
    has_raw = c.get('hasRawCapability')
    phys = c.get('physicalCameraIds')
    streams = list(c.get('streamConfigurations', {}).keys())
    print(f"Cam {cid:>2} [{facing}]: Focal={focal}mm, Active={active}, CFA={cfa}, WhiteLevel={white}, Black={black}, MaxExp={exp_ms}ms, ISO={iso}, RAWCap={has_raw}, PhysIDs={phys}")
    print(f"       Formats: {streams}")

print("\n=== CONCURRENT CAMERA COMBINATIONS ===")
print("Normal :", norm.get('concurrentCameraCombinations'))
print("Spoofed:", spoo.get('concurrentCameraCombinations'))
