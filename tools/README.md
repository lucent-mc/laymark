# tools/

One-off investigation scripts. Not part of the build, not shipped.

They exist so the experiments behind decisions in
[`../docs/laymark-0.x-spec.md`](../docs/laymark-0.x-spec.md) can be re-run rather than taken on
trust. PowerShell, Windows-only.

## `capture-launch-cmdline.ps1`

Captures the exact `java` command line a launcher uses, via WMI.

Java's own `ProcessHandle.Info.commandLine()` returns **empty** on Windows, so a pure-Java runner
cannot read another process's arguments — but WMI can. Run it, then launch an instance from the
launcher.

This is how we learned that the launcher's on-disk version JSON is *not* a complete launch
descriptor: it wraps the launch with its own agent and main class, and omits libraries the JSON
lists.

## `probe-replay-without-auth.ps1`

Replays a captured command line with controlled variations, to isolate one variable at a time.

| Flag | Effect |
| --- | --- |
| `-DummyAuth` | placeholder auth values instead of real ones |
| `-KeepAuth` | control run: keep the real token |
| `-GameDirOverride <dir>` | launch against an empty game directory, so no mods load |
| `-NoKill` | leave the game running for a human to drive |

Used to establish that `--accessToken` is a *required* option but is never *validated* for
singleplayer, so the runner needs no credentials — only placeholder flags.

It forces `-Djava.awt.headless=true`, because FML answers a fatal startup error with a **modal AWT
dialog** and parks forever on the AWT tree lock: without headless the process hangs with a one-line
log instead of failing.

## `probe-offline-launch.ps1`

The earlier, superseded attempt: builds a launch from the version JSON alone. Kept because its
failures are the evidence that the JSON is insufficient — in particular that adding NeoForge's
locally-patched client jar makes FML believe it is in a dev environment and fail.
