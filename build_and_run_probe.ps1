param(
    [string]$PackageName = "com.s24film.probe",
    [string]$OutputFile = "probe_normal.json"
)

$ErrorActionPreference = "Stop"

$sdk = "C:\Users\SDK\AppData\Local\Android\Sdk"
$buildTools = "$sdk\build-tools\34.0.0"
$platform = "$sdk\platforms\android-34"
$adb = "$sdk\platform-tools\adb.exe"
$javaBin = "C:\Program Files\Java\jdk-21.0.10\bin"

Write-Host "=== Building Phase 0 Probe APK for Package: $PackageName ===" -ForegroundColor Cyan

# 1. Prepare build directory
$buildDir = "phase0-probe\build"
if (Test-Path $buildDir) { Remove-Item -Recurse -Force $buildDir }
New-Item -ItemType Directory -Force -Path "$buildDir\classes" | Out-Null

# 2. Write dynamic AndroidManifest.xml
$manifestContent = @"
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PackageName">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.HIGH_SAMPLING_RATE_SENSORS" />

    <application
        android:label="S24 Probe ($PackageName)"
        android:theme="@android:style/Theme.DeviceDefault">
        <activity
            android:name="com.s24film.probe.MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"@
Set-Content -Path "phase0-probe\AndroidManifest_gen.xml" -Value $manifestContent

# 3. Compile resources with aapt2
Write-Host "Compiling resources with aapt2..."
& "$buildTools\aapt2.exe" compile --dir "phase0-probe\src\main\res" -o "$buildDir\res.zip"

# 4. Link APK
Write-Host "Linking APK with aapt2..."
& "$buildTools\aapt2.exe" link -I "$platform\android.jar" "$buildDir\res.zip" --manifest "phase0-probe\AndroidManifest_gen.xml" --min-sdk-version 34 --target-sdk-version 34 -o "$buildDir\unaligned.apk" --auto-add-overlay

# 5. Compile Java
Write-Host "Compiling Java with javac..."
& "$javaBin\javac.exe" -source 17 -target 17 -cp "$platform\android.jar" -d "$buildDir\classes" "phase0-probe\src\main\java\com\s24film\probe\MainActivity.java"

# 6. DEX with d8
Write-Host "Creating classes.dex with d8..."
$classFiles = (Get-ChildItem -Path "$buildDir\classes" -Filter "*.class" -Recurse).FullName
& cmd /c """$buildTools\d8.bat"" --min-api 34 --lib ""$platform\android.jar"" --output ""$buildDir"" $classFiles"

# 7. Add classes.dex to APK
Write-Host "Adding classes.dex to unaligned APK..."
& "$javaBin\jar.exe" uf "$buildDir\unaligned.apk" -C "$buildDir" "classes.dex"

# 8. Zipalign
Write-Host "Aligning APK..."
& "$buildTools\zipalign.exe" -f -p 4 "$buildDir\unaligned.apk" "$buildDir\aligned.apk"

# 9. Sign APK
Write-Host "Signing APK with apksigner..."
& cmd /c """$buildTools\apksigner.bat"" sign --ks ""phase0-probe\debug.keystore"" --ks-pass pass:android ""$buildDir\aligned.apk"""

Write-Host "=== Build Successful: $buildDir\aligned.apk ===" -ForegroundColor Green

# 10. Install on phone
Write-Host "Installing on phone with adb install -r -g..." -ForegroundColor Cyan
& "$adb" install -r -g "$buildDir\aligned.apk"

# 11. Clear logcat & Launch
& "$adb" logcat -c
Write-Host "Launching $PackageName/com.s24film.probe.MainActivity..." -ForegroundColor Cyan
& "$adb" shell am start -n "$PackageName/com.s24film.probe.MainActivity"

# 12. Wait for probe to run
Write-Host "Waiting 6 seconds for probe to complete..." -ForegroundColor Yellow
Start-Sleep -Seconds 6

# 13. Pull probe_result.json
Write-Host "Retrieving probe_result.json from phone..." -ForegroundColor Cyan
$pulled = $false

# Pull from external files dir
$remotePath = "/sdcard/Android/data/$PackageName/files/probe_result.json"
& "$adb" pull "$remotePath" "$OutputFile"
if ((Test-Path "$OutputFile") -and ((Get-Item "$OutputFile").Length -gt 100)) {
    $pulled = $true
} else {
    Write-Host "Trying adb exec-out run-as..."
    & "$adb" exec-out run-as $PackageName cat files/probe_result.json > "$OutputFile"
    if ((Test-Path "$OutputFile") -and ((Get-Item "$OutputFile").Length -gt 100)) {
        $pulled = $true
    }
}

if (-not $pulled) {
    Write-Host "Retrieving report from logcat..." -ForegroundColor Yellow
    $log = & "$adb" logcat -d -s S24Probe
    $startIdx = $log.IndexOf("JSON_REPORT_BEGIN")
    $endIdx = $log.IndexOf("JSON_REPORT_END")
    if ($startIdx -ge 0 -and $endIdx -gt $startIdx) {
        $jsonStr = $log.Substring($startIdx + 17, $endIdx - ($startIdx + 17)).Trim()
        Set-Content -Path "$OutputFile" -Value $jsonStr
        $pulled = $true
    }
}

if ($pulled -and (Test-Path "$OutputFile") -and ((Get-Item "$OutputFile").Length -gt 100)) {
    Write-Host "SUCCESS! Probe report saved to $OutputFile ($((Get-Item $OutputFile).Length) bytes)" -ForegroundColor Green
} else {
    Write-Error "Failed to retrieve probe output!"
}
