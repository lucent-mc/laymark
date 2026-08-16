<#
Captures the exact java command line Modrinth App uses to launch an instance.

Java's own ProcessHandle.Info.commandLine() returns empty on Windows, but WMI
reads another process's command line fine -- which is how Laymark's runner can
learn the classpath assembly the version JSON does not describe.

Run this, then hit Launch in Modrinth App.
#>
param(
  [string] $Match       = "Lucent Optimisations",
  [int]    $TimeoutSecs = 300,
  [string] $OutFile     = (Join-Path $env:TEMP "modrinth-launch-cmdline.txt")
)

$deadline = (Get-Date).AddSeconds($TimeoutSecs)
Write-Host "watching for a java process matching '$Match' (up to $TimeoutSecs s)..."

while ((Get-Date) -lt $deadline) {
  $procs = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" -ErrorAction SilentlyContinue
  foreach ($p in $procs) {
    if ($p.CommandLine -and $p.CommandLine -match [regex]::Escape($Match)) {
      # ignore our own probe
      if ($p.CommandLine -match 'laymark-probe') { continue }
      Set-Content -Path $OutFile -Value $p.CommandLine -Encoding UTF8
      Write-Host "CAPTURED pid $($p.ProcessId), $($p.CommandLine.Length) chars -> $OutFile"
      exit 0
    }
  }
  Start-Sleep -Milliseconds 700
}
Write-Host "TIMEOUT: no matching java process appeared"
exit 1
