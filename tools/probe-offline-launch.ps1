<#
Probe for laymark issue #21: does Minecraft 26.1.2 boot to a playable state in a
production-style launch with NO access token?

Builds a launch command from Modrinth App's on-disk descriptor -- the same route
Laymark's runner will use -- and deliberately omits --accessToken/--uuid/--xuid/--clientId.

Usage:  pwsh -File probe-offline-launch.ps1 [-WaitSeconds 90] [-QuickPlayWorld "<save folder>"]
#>
param(
  [string] $ModrinthRoot = (Join-Path $env:APPDATA "ModrinthApp"),
  [string] $Profile      = "Lucent Optimisations",
  [string] $VersionId    = "26.1.2-26.1.2.95",
  [string] $PlayerName   = "LaymarkProbe",
  [int]    $WaitSeconds  = 90,
  [string] $QuickPlayWorld = "",
  # Forces FML to report startup failures on the console instead of a modal AWT
  # dialog, which otherwise blocks the process forever waiting for a human.
  [switch] $Headless
)

$ErrorActionPreference = "Stop"

$gameDir  = Join-Path $ModrinthRoot "profiles\$Profile"
$verDir   = Join-Path $ModrinthRoot "meta\versions\$VersionId"
$verJson  = Join-Path $verDir "$VersionId.json"
$verJar   = Join-Path $verDir "$VersionId.jar"
$libDir   = Join-Path $ModrinthRoot "meta\libraries"
$natDir   = Join-Path $ModrinthRoot "meta\natives\$VersionId"
$assetDir = Join-Path $ModrinthRoot "meta\assets"

foreach ($p in @($gameDir,$verJson,$verJar,$libDir,$natDir,$assetDir)) {
  if (-not (Test-Path $p)) { throw "missing: $p" }
}

$v = Get-Content $verJson -Raw | ConvertFrom-Json

# --- rule evaluation (windows/x64) ---------------------------------------
function Test-Rules($rules) {
  if (-not $rules) { return $true }
  $allowed = $false
  foreach ($r in $rules) {
    $matches = $true
    if ($r.os) {
      if ($r.os.name -and $r.os.name -ne "windows") { $matches = $false }
      if ($r.os.arch -and $r.os.arch -ne "x86_64")  { $matches = $false }
    }
    if ($r.features) { $matches = $false }   # demo / resolution / quickPlay: opt out
    if ($matches) { $allowed = ($r.action -eq "allow") }
  }
  return $allowed
}

# --- classpath ------------------------------------------------------------
$cp = New-Object System.Collections.Generic.List[string]
$skipped = 0
foreach ($lib in $v.libraries) {
  if (-not (Test-Rules $lib.rules)) { $skipped++; continue }
  $rel = $lib.downloads.artifact.path
  if (-not $rel) { continue }
  $full = Join-Path $libDir ($rel -replace '/','\')
  if (Test-Path $full) { $cp.Add($full) } else { Write-Warning "missing library: $rel" }
}
# The version JSON is NOT a complete launch descriptor. NeoForge's patched client
# jar is produced locally by the installer and added to the classpath by the launcher;
# it appears nowhere in the JSON's libraries. Without it FML claims the unmodified
# vanilla jar to prevent loading and then reports "minecraft is not installed".
$nfVersion = ($VersionId -split '-')[-1]
$patched = Join-Path $libDir "net\neoforged\minecraft-client-patched\$nfVersion\minecraft-client-patched-$nfVersion.jar"
if (Test-Path $patched) { $cp.Add($patched); Write-Host "patched client jar     : found" }
else { Write-Warning "patched client jar NOT found at $patched" }

# vanilla jar deliberately omitted: FML claims it to prevent loading#$cp.Add($verJar)

$cpValue = ($cp | ForEach-Object { $_ -replace '\\','/' }) -join ';'

# --- jvm args -------------------------------------------------------------
$jvm = New-Object System.Collections.Generic.List[string]
foreach ($a in $v.arguments.jvm) {
  if ($a -is [string]) { $jvm.Add($a) }
  elseif (Test-Rules $a.rules) { foreach ($x in @($a.value)) { $jvm.Add($x) } }
}

# --- game args: EVERY placeholder except the auth ones --------------------
$game = New-Object System.Collections.Generic.List[string]
$i = 0
$raw = @($v.arguments.game | Where-Object { $_ -is [string] })
while ($i -lt $raw.Count) {
  $tok = $raw[$i]
  # deliberately omit the auth flags and their values -- this is the experiment
  if ($tok -in @('--accessToken','--uuid','--xuid','--clientId')) { $i += 2; continue }
  $game.Add($tok); $i++
}

$subs = @{
  '${auth_player_name}'   = $PlayerName
  '${version_name}'       = $VersionId
  '${game_directory}'     = $gameDir
  '${assets_root}'        = $assetDir
  '${assets_index_name}'  = "$($v.assetIndex.id)"
  '${version_type}'       = "$($v.type)"
  '${natives_directory}'  = $natDir
  '${library_directory}'  = $libDir
  '${classpath}'          = $cpValue
  '${classpath_separator}'= ';'
  '${launcher_name}'      = 'laymark-probe'
  '${launcher_version}'   = '0.0.0'
}
function Expand-Tokens($list) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($t in $list) { $s = $t; foreach ($k in $subs.Keys) { $s = $s.Replace($k, $subs[$k]) }; $out.Add($s) }
  return ,$out.ToArray()   # comma keeps it an array; bare return unrolled it into one joined value
}
$jvm  = Expand-Tokens $jvm
$game = Expand-Tokens $game

if ($QuickPlayWorld) { $game.Add('--quickPlaySingleplayer'); $game.Add($QuickPlayWorld) }

# Build explicitly: $( @('x') ) unrolls to a scalar string, and string + array is
# string concatenation, which silently collapsed the whole command line into one token.
$argv = @()
if ($Headless) { $argv += '-Djava.awt.headless=true' }
$argv += $jvm
$argv += $v.mainClass
$argv += $game

Write-Host "argv tokens            : $($argv.Count)"
Write-Host "libraries on classpath : $($cp.Count)  (skipped by rules: $skipped)"
Write-Host "main class             : $($v.mainClass)"
Write-Host "auth flags passed      : NONE  <-- the experiment"
if ($QuickPlayWorld) { Write-Host "quickPlaySingleplayer  : $QuickPlayWorld" }

$log = Join-Path $gameDir "logs\latest.log"
if (Test-Path $log) { Remove-Item $log -Force -ErrorAction SilentlyContinue }

$outFile = Join-Path $env:TEMP "laymark-probe-stdout.txt"
$errFile = Join-Path $env:TEMP "laymark-probe-stderr.txt"

# PowerShell's Start-Process mangles array arguments (it concatenated adjacent
# tokens without separators). Hand java a JVM argument file instead: one argument
# per line, forward slashes, quoted when it contains a space.
$argFile = Join-Path $env:TEMP "laymark-probe-args.txt"
$lines = $argv | ForEach-Object {
  $s = $_ -replace '\\','/'
  if ($s -match '\s') { '"' + $s + '"' } else { $s }
}
Set-Content -Path $argFile -Value $lines -Encoding ASCII

$proc = Start-Process -FilePath "java" -ArgumentList "`"@$argFile`"" -WorkingDirectory $gameDir `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile -PassThru
Write-Host "launched pid $($proc.Id); watching for $WaitSeconds s"

$markers = @('Setting user','Backend library: LWJGL','OpenAL initialized','Sound engine started','Created:.*minecraft:textures','Starting integrated minecraft server','Preparing spawn area','Time elapsed')
$seen = @{}
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
  if ($proc.HasExited) { break }
  if (Test-Path $outFile) {
    $txt = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
    foreach ($m in $markers) { if ($txt -match $m -and -not $seen[$m]) { $seen[$m] = $true; Write-Host "  [marker] $m" } }
  }
  Start-Sleep -Milliseconds 1500
}

$exited = $proc.HasExited
if ($exited) { Write-Host "PROCESS EXITED early, code $($proc.ExitCode)" }
else { Write-Host "still running after $WaitSeconds s"; $proc.Kill(); Start-Sleep -Seconds 2 }

Write-Host "`n--- stdout tail ---"
if (Test-Path $outFile) { Get-Content $outFile -Tail 40 }
Write-Host "`n--- stderr tail ---"
if (Test-Path $errFile) { Get-Content $errFile -Tail 25 }
