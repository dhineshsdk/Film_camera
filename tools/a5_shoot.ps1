# Stage A5: calibration shots for noise curves and lens shading, pulled to .\calib\<kind>_<lens>_<iso>
#   .\tools\a5_shoot.ps1 -Kind grey  -Lens 0      # grey card filling the frame, defocused, even light
#   .\tools\a5_shoot.ps1 -Kind white -Lens 1      # plain white wall or a sheet of paper, even light
# Run it once per lens (0 = 1x, 2 = 0.6x, 6 = 3x, 1 = front) and kind. The phone rests on something
# still; nothing in the frame should move. Each ISO is one 8-frame RAW burst (A0 rawburst).
param(
    [Parameter(Mandatory = $true)][ValidateSet('grey', 'white')][string]$Kind,
    [Parameter(Mandatory = $true)][ValidateSet('0', '1', '2', '6')][string]$Lens,
    [int[]]$Isos = @(50, 100, 200, 400, 800, 1600, 3200, 6400)
)
$adb = "C:\Users\SDK\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
function Send($cmdArgs) { & $adb shell am broadcast -a com.s24film.camera.DEBUG @cmdArgs | Out-Null }

Send @('--es', 'cmd', 'lens', '--es', 'id', $Lens)
Start-Sleep -Seconds 3
if ($Kind -eq 'white') { $Isos = @(100, 800) }          # shading barely depends on ISO; two checks
foreach ($iso in $Isos) {
    $tag = "a5_${Kind}_${Lens}_${iso}"
    Send @('--es', 'cmd', 'iso', '--ei', 'iso', "$iso")
    Start-Sleep -Seconds 2                               # auto shutter settles at the fixed ISO
    Write-Host "Burst $tag"
    Send @('--es', 'cmd', 'rawburst', '--ei', 'n', '8', '--es', 'tag', $tag)
    $remote = "/sdcard/DCIM/S24Film/a0/$tag"
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 750
        $ls = & $adb shell ls $remote 2>$null
        if ($ls -match 'burst.json') { break }
    }
    New-Item -ItemType Directory -Force -Path "calib" | Out-Null
    & $adb pull $remote "calib\$tag" | Out-Null
}
Send @('--es', 'cmd', 'iso', '--ei', 'iso', '0')
Write-Host "Done. Next: python tools\a5_calibrate.py calib"
