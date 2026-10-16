import os
import sys
import subprocess
import time
import json

ADB = r"C:\Users\SDK\AppData\Local\Android\Sdk\platform-tools\adb.exe"
APK_PATH = r"c:\Users\SDK\Downloads\Camera-App\phase0-lab-bench\build\aligned.apk"
REMOTE_JSON = "/sdcard/DCIM/S24Film/phase0_lab_results.json"
LOCAL_JSON = r"c:\Users\SDK\Downloads\Camera-App\phase0_lab_results.json"
LOCAL_REPORT = r"c:\Users\SDK\Downloads\Camera-App\phase0_complete_device_laboratory_report.md"

def run_adb(cmd, timeout=30):
    full_cmd = [ADB] + cmd
    res = subprocess.run(full_cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=timeout)
    return res

print("=== 1. Installing com.s24film.labbench on Galaxy S24+ ===")
res = run_adb(["install", "-r", "-g", APK_PATH], timeout=60)
print(res.stdout.strip())
if res.returncode != 0:
    print("[ERROR] Failed to install APK:", res.stderr)
    sys.exit(1)

print("=== 2. Granting runtime permissions ===")
run_adb(["shell", "pm", "grant", "com.s24film.labbench", "android.permission.CAMERA"])
run_adb(["shell", "pm", "grant", "com.s24film.labbench", "android.permission.RECORD_AUDIO"])
run_adb(["shell", "appops", "set", "com.s24film.labbench", "MANAGE_EXTERNAL_STORAGE", "allow"])

print("=== 3. Clearing Logcat ===")
run_adb(["shell", "logcat", "-c"])

# Remove previous remote JSON if any
run_adb(["shell", f"rm -f {REMOTE_JSON}"])

print("=== 4. Launching MainActivity ===")
res = run_adb(["shell", "am", "start", "-n", "com.s24film.labbench/.MainActivity"])
print(res.stdout.strip())

print("\n=== 5. Monitoring Live Laboratory Execution ===")
logcat_proc = subprocess.Popen([ADB, "shell", "logcat", "-v", "time", "-s", "S24LabBench:I", "S24LabBench_VK:I"],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace')

start_time = time.time()
benchmark_completed = False

while True:
    line = logcat_proc.stdout.readline()
    if not line:
        if logcat_proc.poll() is not None:
            break
        time.sleep(0.1)
        continue

    line_str = line.strip()
    if line_str:
        print(line_str)

    if "BENCHMARK_COMPLETE" in line_str:
        benchmark_completed = True
        print("\n[+] Detected BENCHMARK_COMPLETE signal!")
        break

    if "FATAL ERROR" in line_str or "[ERROR] Benchmark failed" in line_str:
        print("\n[-] Detected failure signal in logs!")
        break

    # Safety timeout: 180 seconds
    if time.time() - start_time > 180:
        print("\n[WARNING] Benchmark reached 180s timeout!")
        break

logcat_proc.terminate()

print("\n=== 6. Pulling Results JSON from Device ===")
time.sleep(2)
# Try primary DCIM
res = run_adb(["pull", REMOTE_JSON, LOCAL_JSON])
print(res.stdout.strip())

if not os.path.exists(LOCAL_JSON) or os.path.getsize(LOCAL_JSON) == 0:
    app_ext = "/sdcard/Android/data/com.s24film.labbench/files/phase0_lab_results.json"
    print(f"[*] Trying fallback app external path: {app_ext}")
    res = run_adb(["pull", app_ext, LOCAL_JSON])
    print(res.stdout.strip())

if os.path.exists(LOCAL_JSON) and os.path.getsize(LOCAL_JSON) > 0:
    size = os.path.getsize(LOCAL_JSON)
    print(f"[SUCCESS] Pulled {LOCAL_JSON} ({size} bytes)")
else:
    print(f"[ERROR] Could not pull {LOCAL_JSON}")
