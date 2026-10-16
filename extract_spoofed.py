import subprocess
import json

adb = r'C:\Users\SDK\AppData\Local\Android\Sdk\platform-tools\adb.exe'
res = subprocess.run([adb, 'logcat', '-d', '-s', 'S24Probe'], capture_output=True, text=True, encoding='utf-8', errors='ignore')
log = res.stdout

start = log.find("JSON_REPORT_BEGIN")
end = log.find("JSON_REPORT_END")

if start != -1 and end != -1:
    raw_json_lines = log[start + len("JSON_REPORT_BEGIN"):end].strip().splitlines()
    cleaned_lines = []
    for line in raw_json_lines:
        # line has format: 10-15 00:22:01.821 17116 17141 I S24Probe:  ...
        idx = line.find("I S24Probe:")
        if idx != -1:
            cleaned_lines.append(line[idx + len("I S24Probe:"):])
        else:
            cleaned_lines.append(line)
    json_str = "\n".join(cleaned_lines)
    data = json.loads(json_str)
    with open('probe_spoofed.json', 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2)
    print("SUCCESS: probe_spoofed.json extracted! Length:", len(json_str), "Keys:", list(data.keys()))
else:
    print("Failed to find markers")
