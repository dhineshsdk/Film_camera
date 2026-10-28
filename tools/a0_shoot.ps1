# Stage A0: shoot one golden-scene RAW burst on the phone, then pull it to .\bursts\<tag>
#   .\tools\a0_shoot.ps1 -Tag day_1x -Lens 0            (lens ids: 0 = 1x, 2 = 0.6x, 6 = 3x, 1 = front)
#   .\tools\a0_shoot.ps1 -Tag portrait_3x -Lens 6 -Portrait
# Hold still (tripod or a rest) when the script says SHOOT. The app must be open and in photo mode.
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][ValidateSet('0', '1', '2', '6')][string]$Lens,
    [int]$Frames = 8,
    [switch]$Portrait
)
$adb = "C:\Users\SDK\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }
function Send($cmdArgs) { & $adb shell am broadcast -a com.s24film.camera.DEBUG @cmdArgs | Out-Null }

Send @('--es', 'cmd', 'lens', '--es', 'id', $Lens)
Send @('--es', 'cmd', 'portrait', '--ez', 'on', $(if ($Portrait) { 'true' } else { 'false' }))
Start-Sleep -Seconds 3                      # let the new lens settle and auto exposure converge
Write-Host "SHOOT $Tag (hold still)"
Send @('--es', 'cmd', 'rawburst', '--ei', 'n', "$Frames", '--es', 'tag', $Tag)
$remote = "/sdcard/DCIM/S24Film/a0/$Tag"
for ($i = 0; $i -lt 40; $i++) {             # burst.json is written last, after every DNG
    Start-Sleep -Milliseconds 750
    if ((& $adb shell "ls $remote/burst.json 2>/dev/null") -match 'burst.json') { break }
}
New-Item -ItemType Directory -Force bursts | Out-Null
& $adb pull $remote "bursts/" | Out-Null
Write-Host "pulled bursts\$Tag ($((Get-ChildItem bursts\$Tag -Filter *.dng).Count) DNGs)"
