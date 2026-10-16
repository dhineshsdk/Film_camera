import re

with open('phase0_dumpsys_camera.txt', 'r', encoding='utf-8') as f:
    text = f.read()

dev_blocks = re.split(r'== Camera HAL device device@1\.1/internal/(\d+) \(v1\.2\) static information: ==', text)

print(f"{'ID':<4} | {'Sensor Name':<12} | {'CFA':<6} | {'Active Array':<18} | {'Max Exposure (ms)':<18} | {'Min Exposure (us)':<18}")
print("-" * 90)

for i in range(1, len(dev_blocks), 2):
    dev_id = dev_blocks[i]
    content = dev_blocks[i+1]
    
    # sensorName bytes
    sname_m = re.search(r'samsung\.android\.sensor\.info\.sensorName\s*\([^\)]+\):\s*byte\[\d+\]\s*\n\s*\[([^\]]+)\]', content)
    if sname_m:
        raw_bytes = [int(b) for b in sname_m.group(1).split() if b != '0']
        sensor_name = "".join(chr(b) for b in raw_bytes)
    else:
        sensor_name = "Unknown"
        
    # CFA
    cfa_m = re.search(r'android\.sensor\.info\.colorFilterArrangement\s*\([^\)]+\):\s*byte\[1\]\s*\n\s*\[([^\]]+)\]', content)
    cfa = cfa_m.group(1).strip() if cfa_m else "?"
    
    # activeArraySize
    active_m = re.search(r'android\.sensor\.info\.activeArraySize\s*\([^\)]+\):\s*int32\[4\]\s*\n\s*\[([^\]]+)\]', content)
    active = active_m.group(1).strip() if active_m else "?"
    
    # exposureTimeRange
    exp_m = re.search(r'android\.sensor\.info\.exposureTimeRange\s*\([^\)]+\):\s*int64\[2\]\s*\n\s*\[([^\]]+)\]', content)
    if exp_m:
        exps = [int(x) for x in exp_m.group(1).split()]
        min_exp_us = exps[0] / 1000.0
        max_exp_ms = exps[1] / 1000000.0
    else:
        min_exp_us = "?"
        max_exp_ms = "?"
        
    print(f"{dev_id:<4} | {sensor_name:<12} | {cfa:<6} | {active:<18} | {str(max_exp_ms):<18} | {str(min_exp_us):<18}")
