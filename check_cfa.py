import re

with open('phase0_dumpsys_camera.txt', 'r', encoding='utf-8') as f:
    text = f.read()

# Let's inspect block 0
blocks = re.findall(r'== Camera HAL device device@1\.1/internal/0 \(v1\.2\) static information: ==(.*?)== Camera HAL device device@1\.1/internal/0 \(v1\.2\) dumpState: ==', text, re.S)
if blocks:
    b0 = blocks[0]
    # search for colorFilterArrangement
    for line in b0.splitlines():
        if 'colorFilterArrangement' in line or 'sensor.info' in line or 'streamConfiguration' in line or 'availableStreamConfigurations' in line:
            print(line)

print("\n--- Let's find colorFilterArrangement in all blocks ---")
matches = re.finditer(r'Camera HAL device device@1\.1/internal/(\d+).*?android\.sensor\.info\.colorFilterArrangement[^\n]*\n\s*\[(\d+)\]', text, re.S)
for m in matches:
    print(f"Device {m.group(1)}: CFA = {m.group(2)}")
