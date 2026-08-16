<#
Decisive probe for laymark issue #21.

Takes the REAL command line captured from a Modrinth App launch and replays it with
only these changes:
  - every auth argument removed (--accessToken / --uuid / --xuid / --clientId)
  - Modrinth's own wrapper removed (-javaagent:theseus.jar, com.modrinth.theseus.MinecraftLaunch,
    and the -Dmodrinth.internal.* properties) so we launch FML directly, as Laymark's runner would
  - -Djava.awt.headless=true so a fatal FML error prints instead of opening a modal dialog

Everything else -- classpath, JVM flags, natives, asset index, log config -- is byte-identical
to what the launcher used.

Never prints the captured token.
#>
param(
  [string] $Captured    = (Join-Path $env:TEMP "modrinth-launch-cmdline.txt"),
  [int]    $WaitSeconds = 180,
  [switch] $KeepAuth,           # control run: replay WITH the real auth values
  # Pass placeholder auth values instead of omitting the flags. jopt-simple only
  # checks that --accessToken is PRESENT; singleplayer never validates it.
  [switch] $DummyAuth,
  [string] $QuickPlayWorld = "",
  # Point at a throwaway empty directory to launch with zero mods, isolating auth
  # from any mod-loading problem in the real pack.
  [string] $GameDirOverride = "",
  # Leave the game running when the probe finishes, so a human can drive it.
  [switch] $NoKill
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $Captured)) { throw "no captured command line at $Captured" }

$cmd  = Get-Content $Captured -Raw
$toks = [regex]::Matches($cmd, '"[^"]*"|\S+') | ForEach-Object { $_.Value.Trim('"') }

$exe  = $toks[0]
$rest = $toks[1..($toks.Count-1)]

$authFlags = @('--accessToken','--uuid','--xuid','--clientId')
$out = New-Object System.Collections.Generic.List[string]

for ($i = 0; $i -lt $rest.Count; $i++) {
  $t = $rest[$i]

  if (-not $KeepAuth -and $t -in $authFlags) { $i++; continue }        # drop flag and its value
  if ($t -like '-javaagent:*theseus.jar')    { continue }              # drop Modrinth's agent
  if ($t -like '-Dmodrinth.internal.*')      { continue }              # drop its IPC wiring
  if ($t -eq 'com.modrinth.theseus.MinecraftLaunch') { continue }      # drop its wrapper main class

  $out.Add($t)
}

# theseus.jar also sits on the classpath; strip that single entry, keep everything else.
for ($i = 0; $i -lt $out.Count; $i++) {
  if ($out[$i] -eq '-cp' -or $out[$i] -eq '--class-path') {
    $entries = $out[$i+1] -split ';' | Where-Object { $_ -and $_ -notmatch 'theseus\.jar$' }
    $out[$i+1] = ($entries -join ';')
    Write-Host "classpath entries      : $($entries.Count)"
    break
  }
}

if ($GameDirOverride) {
  New-Item -ItemType Directory -Force -Path $GameDirOverride | Out-Null
  New-Item -ItemType Directory -Force -Path (Join-Path $GameDirOverride "mods") | Out-Null
  $gi = [array]::IndexOf($out.ToArray(), '--gameDir')
  if ($gi -ge 0) { $out[$gi+1] = $GameDirOverride; Write-Host "gameDir overridden     : $GameDirOverride (no mods)" }
}

if ($DummyAuth) {
  # Conventional offline UUID: version-3 UUID of "OfflinePlayer:<name>". It must be
  # deterministic -- player data in a save is keyed by UUID, so a random placeholder
  # would give each arm different player state.
  $nameIdx = [array]::IndexOf($out.ToArray(), '--username')
  $player  = if ($nameIdx -ge 0) { $out[$nameIdx+1] } else { 'LaymarkProbe' }
  $md5   = [System.Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$player"))
  $md5[6] = ($md5[6] -band 0x0f) -bor 0x30      # version 3
  $md5[8] = ($md5[8] -band 0x3f) -bor 0x80      # RFC 4122 variant
  $uuid  = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''

  $out.Add('--accessToken'); $out.Add('0')
  $out.Add('--uuid');        $out.Add($uuid)
  $out.Add('--xuid');        $out.Add('0')
  $out.Add('--clientId');    $out.Add('0')
  Write-Host "offline identity       : $player / $uuid"
}

$argv = @('-Djava.awt.headless=true') + $out.ToArray()
if ($QuickPlayWorld) { $argv += @('--quickPlaySingleplayer', $QuickPlayWorld) }

$authMode = if ($KeepAuth) { 'KEPT (real token)' } elseif ($DummyAuth) { 'PLACEHOLDER VALUES  <-- the experiment' } else { 'REMOVED' }
Write-Host "auth arguments         : $authMode"
Write-Host "argv tokens            : $($argv.Count)"

# Java argfiles treat backslash as an escape INSIDE quotes only. Blanket-converting
# to forward slashes broke -Dlog4j.configurationFile (log4j parsed "C:" as a URL
# protocol), so: leave unquoted tokens alone, and double the backslashes when quoting.
$argFile = Join-Path $env:TEMP "laymark-replay-args.txt"
Set-Content -Path $argFile -Value ($argv | ForEach-Object {
  if ($_ -match '\s') { '"' + ($_ -replace '\\','\\\\') + '"' } else { $_ }
}) -Encoding ASCII

$gameDir = ($argv[[array]::IndexOf($argv,'--gameDir') + 1])
$outFile = Join-Path $env:TEMP "laymark-replay-stdout.txt"
$errFile = Join-Path $env:TEMP "laymark-replay-stderr.txt"

$proc = Start-Process -FilePath $exe -ArgumentList "@$argFile" -WorkingDirectory $gameDir `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile -PassThru
Write-Host "launched pid $($proc.Id); watching up to $WaitSeconds s`n"

$markers = @(
  'Setting user',
  'Backend library: LWJGL',
  'Narrator library',
  'OpenAL initialized',
  'Sound engine started',
  'Starting integrated minecraft server',
  'Preparing spawn area',
  'Time elapsed'
)
$seen = @{}
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline) {
  if ($proc.HasExited) { break }
  $txt = Get-Content $outFile -Raw -ErrorAction SilentlyContinue
  if ($txt) {
    foreach ($m in $markers) { if ($txt -match $m -and -not $seen[$m]) { $seen[$m] = $true; Write-Host "  [reached] $m" } }
    # Terminate on a decision rather than on the timeout: a fatal loading error keeps
    # the LWJGL error screen open forever, and a successful boot sits at the title screen
    # forever. Either way, waiting out the clock wastes the whole budget.
    if ($txt -match 'MissingRequiredOptionsException|ModLoadingException|Error loading mods') {
      Write-Host "`nDECIDED: fatal loading error"; break
    }
    if ($seen['Sound engine started'] -or $seen['Narrator library']) {
      Write-Host "`nDECIDED: reached the title screen"; break
    }
  }
  Start-Sleep -Milliseconds 1500
}

if ($proc.HasExited) { Write-Host "`nPROCESS EXITED, code $($proc.ExitCode)" }
elseif ($NoKill)     { Write-Host "`nLEAVING IT RUNNING (pid $($proc.Id)) -- kill it yourself when done" }
else                 { Write-Host "`nSTILL RUNNING after $WaitSeconds s"; $proc.Kill() }

Write-Host "`nmilestones reached: $($seen.Keys.Count) / $($markers.Count)"
Write-Host "`n--- stdout tail ---"
Get-Content $outFile -Tail 25 -ErrorAction SilentlyContinue


