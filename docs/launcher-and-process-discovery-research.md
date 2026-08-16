# Launching an instance and finding its JVM: source-backed design notes

Research snapshot: 2026-08-16. Resolves
[*Determine how the runner launches and identifies the Minecraft process*](https://github.com/lucent-mc/laymark/issues/7).

All Modrinth App claims are checked against the tagged source of release
`v0.17.10` (annotated tag `13d1f0a5`, commit
[`457254f2102005f315ca000f21c8fee0b3b9c722`](https://github.com/modrinth/code/tree/457254f2102005f315ca000f21c8fee0b3b9c722)),
not from memory. Several claims are additionally verified empirically against
the Modrinth App 0.17.10 installed on this machine (`ProductVersion 0.17.10`,
`app_metadata.app_version = 0.17.10`); those are marked **[measured]**.

`docs/benchmark-harness-plan.md` and `docs/benchmark-harness-handoff.md` were
not used as sources.

## Conclusion

Launch by spawning the Modrinth App executable with a `modrinth://` deep-link
URL as its single argument, and identify the JVM by an in-game handshake in
which the Laymark mod reports its own PID. Do not try to identify the process
by scanning for `javaw.exe`, and do not treat the spawned process as the game.

Concretely:

1. **Launch.** `"%LOCALAPPDATA%\Modrinth App\Modrinth App.exe"
   "modrinth://launch/instance/<instance-id>"`, with no query string. This is
   the exact mechanism Modrinth App itself uses for its "create desktop
   shortcut" feature, so it is a real interface rather than a guess — but it is
   *undocumented*, so pin it with a startup self-check.
2. **Identify.** The runner opens a loopback listener, writes a run manifest
   (run ID, port, nonce) into `<instance>/config/laymark/`, and waits for the
   mod to connect and report `ProcessHandle.current().pid()`. Everything else —
   process-tree walking, command-line matching, working-directory matching — is
   a *corroborating* signal and a pre-handshake failure detector, not the
   primary identification.
3. **Watch.** After the handshake, hold a Win32 process handle
   (`OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION)`) so the PID
   cannot be recycled underneath the watcher, and distinguish exit / crash /
   hang from exit code + crash artefacts + heartbeat deadline.
4. **Fallback.** Keep a launcher-agnostic direct-JVM launcher behind the same
   seam. Modrinth App's own on-disk metadata is a complete launch descriptor;
   the only thing it does not give you for free is a Minecraft account. This
   fallback is *also* what makes Prism/ATLauncher/GDLauncher support additive
   later.

The single largest risk is not technical difficulty; it is that the deep link
is **fire-and-forget**. The process the runner spawns tells it nothing: no exit
code, no error, no PID. Every failure mode (not installed, no account signed
in, instance quarantined, already running) manifests as an in-app toast the
runner cannot see. The design must therefore be *observational* — the runner
decides that a launch succeeded because it observed evidence, not because a
command returned success.

## 1. What launch entry points Modrinth App 0.17.10 actually has

### 1.1 There is no CLI

Modrinth App is a Tauri 2 application. Its dependency list contains no
`tauri-plugin-cli` and no argument parser
([`apps/app/Cargo.toml` L25–33](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/Cargo.toml#L25-L33)).
On Windows in release builds it is linked as a GUI subsystem binary
(`#![cfg_attr(all(not(debug_assertions), target_os = "windows"), windows_subsystem = "windows")]`,
[`apps/app/src/main.rs` L1–4](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/main.rs#L1-L4)),
so it has no console and cannot report anything on stdout/stderr.

The source is explicit that arguments are not a CLI: the comment above `main`
reads *"if Tauri app is called with arguments, then those arguments will be
treated as commands ie: deep links or filepaths for .mrpacks"*
([`main.rs` L114–116](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/main.rs#L114-L116)),
and the argument reader is commented *"Tauri is not CLI, we use arguments as
path to file to call"*
([`apps/app/src/api/utils.rs` L160–174](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/utils.rs#L160-L174)).

### 1.2 The one external interface is `argv[1]`

Exactly one argument is honoured, and it is interpreted by
`theseus::handler::parse_command`
([`packages/app-lib/src/api/handler.rs` L113–144](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/handler.rs#L113-L144)):

- a string beginning `modrinth://` is parsed as a deep link;
- anything else is treated as a filesystem path and accepted only if it ends
  in `.mrpack`;
- anything else is rejected with an in-app warning.

The deep-link grammar is a flat match on the first path segment
([`handler.rs` L17–111](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/handler.rs#L17-L111)):

| URL | Effect |
| --- | --- |
| `modrinth://mod/{id}` | install a project |
| `modrinth://version/{id}` | install a specific version |
| `modrinth://modpack/{id}` | install a modpack |
| `modrinth://server/{id}` | open a server project and start the play flow |
| `modrinth://share/{invite_id}` | accept a shared-instance invite |
| `modrinth://launch/instance/{id}` | **launch an instance** |

The launch form accepts two optional, mutually exclusive query parameters,
`server` and `singleplayer_world`; supplying both is a hard error
([`handler.rs` L53–99](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/handler.rs#L53-L99)).

### 1.3 The URL reaches the app on both cold and warm start

**Cold start.** On mount, the frontend calls the `utils|get_opening_command`
command exactly once
([`apps/app-frontend/src/App.vue` L658](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/App.vue#L658)),
which on non-macOS reads `std::env::args_os().nth(1)` and parses it
([`utils.rs` L160–174](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/utils.rs#L160-L174)).

**[measured]** In this machine's launcher log, a cold start with no argument
logs `theseus_gui::api::utils: opening command None`, confirming that code path
runs on every cold start
(`%APPDATA%\ModrinthApp\launcher_logs\session_20260814_224157.log`).

**Warm start.** The app registers `tauri_plugin_single_instance` (2.3.4) and
forwards `args[1]` from a second launch to `handle_command`
([`main.rs` L162–175](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/main.rs#L162-L175)).
On Windows the plugin's mechanism is a named mutex plus `WM_COPYDATA` to a
hidden message window, after which **the second process calls
`std::process::exit(0)`**
([`tauri-plugin-single-instance` 2.3.4, `platform_impl/windows.rs`](https://docs.rs/tauri-plugin-single-instance/2.3.4/src/tauri_plugin_single_instance/platform_impl/windows.rs.html)).

**[measured]** Spawning
`"C:\Users\mia\AppData\Local\Modrinth App\Modrinth App.exe" "modrinth://launch/instance/local:00000000-0000-0000-0000-000000000000"`
against the already-running app returned **exit code 0 after 181 ms**, and the
running app logged:

```
2026-08-16T04:35:55.302  INFO theseus_gui: Handling command-line deep link
2026-08-16T04:35:55.303  INFO theseus_gui::api::utils: handle command: modrinth://launch/instance/local:00000000-0000-0000-0000-000000000000
```

(A deliberately nonexistent instance ID was used so that nothing launched.)
This confirms argv plumbing, the URL grammar, and — importantly — that the
spawned process exits 0 whether or not the launch succeeds.

### 1.4 The URI-scheme registration is irrelevant to us

`tauri-plugin-deep-link` (2.4.3) registers the `modrinth` scheme with
`HKCU\Software\Classes\modrinth\shell\open\command` set to `"<exe>" "%1"`
([plugin source](https://docs.rs/tauri-plugin-deep-link/2.4.3/src/tauri_plugin_deep_link/lib.rs.html)),
with the scheme itself declared in
[`apps/app/tauri.conf.json` L69–78](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/tauri.conf.json#L69-L78).

**[measured]** On this machine that key reads exactly
`"C:\Users\mia\AppData\Local\Modrinth App\Modrinth App.exe" "%1"`, and there is
no `HKLM` counterpart.

The consequence: going *through* the shell (`ShellExecute("modrinth://…")`)
and spawning the exe with the URL as `argv[1]` are the same operation. The
runner should spawn the exe directly — it avoids depending on the registration
existing, avoids `%1` quoting rules, and lets the runner locate the executable
deterministically instead of asking the shell.

### 1.5 Modrinth App itself uses this exact mechanism

This is the decisive evidence that the deep link is a supported path rather
than an internal accident. `create_instance_shortcut` builds
`modrinth://launch/instance/{id}` with optional `server` /
`singleplayer_world`
([`apps/app/src/api/shortcuts/mod.rs` L26–80](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/shortcuts/mod.rs#L26-L80)),
and on Windows writes an `IShellLinkW` `.lnk` whose **target is
`std::env::current_exe()` and whose arguments are that URL**
([`shortcuts/windows.rs` L21–96](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/shortcuts/windows.rs#L21-L96)).
The instance page passes `instance.value.id` with no world target
([`pages/instance/layout.vue` L661](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/pages/instance/layout.vue#L661)).

### 1.6 …but it is not documented

A search of `docs.modrinth.com` and `support.modrinth.com` finds no
specification of the `modrinth://` scheme or of `launch/instance`. The Modrinth
docs site covers the web API and contributing to Theseus, not an app-level
external interface
([docs.modrinth.com](https://docs.modrinth.com/),
[Modrinth App help collection](https://support.modrinth.com/en/collections/7804910-modrinth-app)).

Treat the deep link as a **real but unversioned** interface. It is stable
enough to build on (it backs a shipped user-facing feature) but Laymark must
detect breakage rather than assume it.

## 2. Can an instance be launched with no singleplayer world target?

**Yes, and that is the default.** Omit the query string entirely.

`server` and `singleplayer_world` are both `Option<String>` and default to
`None` ([`handler.rs` L57–87](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/handler.rs#L57-L87)).
The frontend dispatch is a plain three-way branch that falls through to a bare
`run(e.id)` when neither is present
([`App.vue` L1143–1152](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/App.vue#L1143-L1152)).

That reaches `theseus::instance::run(instance_id, QuickPlayType::None)`
([`packages/app-lib/src/api/instance/run.rs` L14–49](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/instance/run.rs#L14-L49)).
`QuickPlayType::None` suppresses every Quick Play argument: the `--quickPlay*`
game arguments are only substituted for the `Singleplayer`/`Server` variants
([`launcher/args.rs` L390–415](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/args.rs#L390-L415)),
and the feature rules that gate them evaluate false
([`launcher/mod.rs` L84–99](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L84-L99)).

The game therefore starts at the title screen, which is what Laymark's harness
wants: it creates and enters its own disposable world in-process.

## 3. Can it be launched without writing to Modrinth App's database?

Split the question, because it has two different answers.

**Does *Laymark* have to write to `app.db`? No.** The only thing Laymark needs
from the database is the instance identity, and that is a read. `instances.id`
is a synthetic `local:<uuid>` string
([`create_instance.rs` L65](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/instances/commands/create_instance.rs#L65)),
with `path` (the folder name under `profiles/`) carrying a `UNIQUE` constraint
([migration `20260611120000_instances-content-foundation.sql` L1–23](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/migrations/20260611120000_instances-content-foundation.sql#L1-L23)).
So the runner can map a configured instance directory to an instance ID with a
single read-only query, exactly as Inlay already does
(`C:\Users\mia\Development\lucent-mc\inlay\src\lib\instance-metadata.ts`,
`currentModrinthRows` / `detectModrinthDatabaseAt`, which open `app.db` with
`{ readOnly: true }`).

**[measured]** Read-only against a copy of this machine's `app.db`:

```
local:93557cf6-04f8-44e1-83a7-768758b4919e  path="Lucent Optimisations"  neoforge 26.1.2.95 / MC 26.1.2  install_stage=installed
local:932f781e-304c-4879-8418-af96c37c793a  path="Fabulously Optimized"  fabric 0.19.3 / MC 26.2
```

Note the path/name mismatch (`Lucent Optimisations` vs `Lucent Optimizations`)
— resolve the instance by `path`, never by display name.

**Does *Modrinth App* write to its own database when launching? Yes,
unavoidably.** A deep-link launch causes at minimum: `instances.last_played`
([`launcher/mod.rs` L1103–1108](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L1103-L1108)),
periodic and final `recent_time_played` accumulation
([`state/process.rs` L752–788](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L752-L788)),
and playtime submission to Modrinth's analytics endpoint on exit
([`run.rs` L313–383](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/instance/run.rs#L313-L383)).
It may also rewrite `<instance>/options.txt` if force-fullscreen or a resolution
override is set
([`launcher/mod.rs` L1062–1101](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L1062-L1101)).

That is Modrinth's business, not a violation of Laymark's rule — but two
consequences bite:

- **Playtime and analytics pollution.** Every benchmark arm reports playtime to
  Modrinth against every installed project. A benchmark sweep of N arms
  inflates Mia's playtime stats. This is cosmetic but real.
- **`options.txt` may be rewritten between arms**, which would silently change
  a graphics setting mid-experiment. Laymark should snapshot and assert
  `options.txt` around every arm regardless of launcher.

**[measured]** This machine's instance has launch overrides set including
`force_fullscreen` and `game_resolution 1920x1080`, so this path is live here,
not hypothetical.

### 3.1 Where Laymark may safely write inside the instance

Modrinth App runs a recursive, 1-second-debounced watcher over the whole
instances directory
([`state/instances/watcher.rs` L21–180](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/instances/watcher.rs#L21-L180)).
Its behaviour by location:

| Path touched | Modrinth's reaction |
| --- | --- |
| `<instance>/mods/**` (any project-type folder) | `sync_content_files` — **writes to `app.db`** |
| `<instance>/crash-reports/*.txt` | runs `crash_task` |
| `<instance>/saves/*/level.dat` | `WorldUpdated`, may drop attached world data |
| `<instance>/saves/**`, `<instance>/config/**` | **no event emitted** |
| anything else | `Synced` event only |

So the handshake manifest belongs under **`<instance>/config/laymark/`**: it is
the only location that provokes no launcher reaction at all. Mod-directory
swaps unavoidably provoke a content sync — that is a constraint for the
mod-stack materialization ticket, not this one, but it is worth recording here.

## 4. What the deep link does *not* give you

This is the part the design has to absorb.

- **No exit code.** The spawned process exits 0 in the warm-start case
  regardless of outcome (**[measured]**, §1.3), because it exits before the
  primary instance has even parsed the URL.
- **No error channel.** Every launch failure is surfaced as a Vue toast via
  `.catch(handleError)`
  ([`App.vue` L1143–1152](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/App.vue#L1143-L1152)).
- **No PID.** `ProcessMetadata` — the only launch result the app models — is
  `{ uuid, instance_id, instance_path, instance_name, start_time }`, with no
  PID
  ([`state/process.rs` L282–289](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L282-L289)).
  The `Child` is held in an in-memory `DashMap` and never persisted
  ([`state/process.rs` L83–234](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L83-L234)).
- **No external API.** The `process_get_all` / `process_kill` / `process_wait_for`
  commands exist only as Tauri IPC to the app's own webview
  ([`apps/app/src/api/process.rs`](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/process.rs)).
  There is no socket, no named pipe, no HTTP surface.
- **A `processes` table exists in `app.db` and is a trap.** It has exactly the
  columns Laymark would want (`pid, start_time, name, executable, instance_id,
  post_exit_command`) — **[measured]** — but no code in 0.17.10 reads or writes
  it. It survives only as a table carried forward by migrations
  ([`20260619120000_finish-instance-profile-migration.sql` L22–53](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/migrations/20260619120000_finish-instance-profile-migration.sql#L22-L53));
  a repository-wide search for `FROM processes` / `INTO processes` in
  `packages/app-lib/src` and the compiled `.sqlx` query cache returns nothing.
  **Do not build on this table.**

### 4.1 Preconditions that make a deep-link launch silently do nothing

Each of these must be checked by Laymark *before* launching, because none of
them is observable afterwards:

| Precondition | Source |
| --- | --- |
| The instance ID must exist; `getInstance` returning null aborts silently | [`App.vue` L1144–1145](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/App.vue#L1144-L1145) |
| The instance must not be quarantined | [`run.rs` L27–37](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/instance/run.rs#L27-L37) (also readable from `instance_quarantines`) |
| A Minecraft account must be signed in, else `NoCredentialsError` | [`run.rs` L44–47](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/instance/run.rs#L44-L47) |
| `install_stage` must be `Installed` — the deep link calls `run` directly and does **not** trigger an install | [`launcher/mod.rs` L803–817](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L803-L817) |
| A NeoForge instance must have a resolvable loader version | [`launcher/mod.rs` L840–846](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L840-L846) |
| The instance must not already be running | [`launcher/mod.rs` L927–936](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L927-L936) |

The last one is a gift, not a problem: Modrinth App refuses to start a second
process for an instance that already has one. That guarantees at most one
Minecraft per instance, which materially simplifies identification.

Note also that the mandatory account check means the *runner cannot benchmark
on a machine with no signed-in account* via this path — a real constraint for
CI, and an argument for the fallback in §7.

## 5. Identifying the correct JVM process

### 5.1 What the launched process looks like

From `launch_minecraft`
([`launcher/mod.rs` L788–1180](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L788-L1180)):

- The executable is Modrinth's managed Java binary, which on Windows is
  **`javaw.exe`**, not `java.exe`
  ([`util/jre.rs` L228–230](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/util/jre.rs#L228-L230)).
  **[measured]** on this machine:
  `%APPDATA%\ModrinthApp\meta\java_versions\zulu25.36.15-ca-jre25.0.4-win_x64\bin\javaw.exe`.
- The **working directory is the instance directory**
  ([`launcher/mod.rs` L1048](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L1048)).
- The **main class is `com.modrinth.theseus.MinecraftLaunch`**, with the game's
  real main class as its first argument
  ([`launcher/mod.rs` L1026–1028](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L1026-L1028)).
  There is only ever **one** JVM: the shim is a main class in the same process,
  not a child process.
- The command line carries `-javaagent:<temp>/theseus.jar` and
  `-Dmodrinth.internal.ipc.host` / `-Dmodrinth.internal.ipc.port` pointing at a
  per-launch loopback RPC port
  ([`launcher/args.rs` L171–188](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/args.rs#L171-L188);
  server in [`util/rpc.rs`](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/util/rpc.rs)).
- Its **parent is the Modrinth App process** — spawned via `tokio::process`
  with piped stdio
  ([`state/process.rs` L118–122](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L118-L122)) —
  *unless* a wrapper hook is configured, in which case the wrapper is the child
  and `javaw.exe` is a grandchild
  ([`launcher/mod.rs` L904–923](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L904-L923)).
  Wrapper/pre-launch/post-exit hooks are user-settable per instance and
  globally (`settings.hook_wrapper`, `instance_launch_overrides`), so any
  parent-based logic must tolerate an extra level.

### 5.2 What a Java 25 runner can actually see on Windows

**[measured]** — `ProcessHandle` probe run under Zulu 25 against the live
Modrinth App process (PID 5320):

```
command      = Optional[C:\Users\mia\AppData\Local\Modrinth App\Modrinth App.exe]
commandLine  = Optional.empty
arguments    = Optional.empty
startInstant = Optional[2026-08-14T20:41:57.368Z]
user         = Optional[APHRODITE\mia]
parent       = Optional[7172]
children     = [22988:...\msedgewebview2.exe]
descendants  = 12
```

This matches the JDK source: the Windows `info0` implementation populates
`command`, `startTime`, `user` and CPU time, and **never** assigns the
`commandLine` or `arguments` fields — their field IDs are looked up and then
unused
([`ProcessHandleImpl_win.c` (jdk-25-ga)](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c)).
Working directory is not exposed by `ProcessHandle` on any platform.

So, out of the box, a Java runner on Windows can match on **executable path,
parent PID, start time and user** — but *not* on command line and *not* on
working directory. Both of those are exactly the discriminators that would
distinguish "the javaw for instance A" from "the javaw for instance B".

To get them you need one of:

- **`Win32_Process` via WMI/CIM** — `CommandLine`, `ExecutablePath`,
  `ParentProcessId`, `CreationDate` are all available
  ([Win32_Process class](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process)).
  **[measured]** `Get-CimInstance Win32_Process -Filter "ProcessId = 5320"`
  returned the full quoted command line. From Java this means either shelling
  out to PowerShell (ugly, slow, and PowerShell 7 may not exist) or COM interop.
  WMI does **not** expose working directory.
- **`NtQueryInformationProcess(ProcessBasicInformation)` + `ReadProcessMemory`
  of the PEB** — the only way to read another process's command line *and*
  current directory. Microsoft documents this function as *"may be altered or
  unavailable in future versions of Windows"*, and it requires matching
  bitness. Reachable from Java 25 via the FFM API without JNI.
- **Not needing it at all** — see §5.3.

### 5.3 The reliable answer: an in-game handshake

The mod is inside the process. `ProcessHandle.current().pid()` is standard Java
and works on Windows. This makes the identification problem disappear, and it
is exactly the shape Modrinth itself uses for its own shim (loopback TCP,
port injected at launch, line-delimited JSON —
[`util/rpc.rs`](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/util/rpc.rs)).

Recommended protocol:

1. The runner binds a loopback listener on an ephemeral port and mints a run ID
   and a random nonce.
2. It writes `<instance>/config/laymark/run.json` containing
   `{ runId, port, nonce, plan… }` (see §3.1 for why `config/`).
3. It launches via the deep link.
4. The Laymark mod, at the earliest loader entrypoint, reads that file,
   connects to `127.0.0.1:<port>`, and sends
   `{ runId, nonce, pid: ProcessHandle.current().pid() }`.
5. The runner rejects any connection whose nonce does not match, records the
   PID plus `ProcessHandle.of(pid).info().startInstant()` as the identity pair,
   and only then begins the measurement phase.

This is launcher-agnostic by construction. It works identically under the
direct-JVM fallback (§7) and under Prism or ATLauncher later, which is the main
reason to prefer it over anything that reasons about Modrinth's process tree.

Because the manifest is a file in the instance directory, a stale one from a
crashed previous run is possible; the nonce plus a `writtenAt` timestamp and an
unlink-on-consume make that unambiguous.

### 5.4 The pre-handshake gap, and how to cover it

Between "runner spawned the deep link" and "mod said hello" the runner is
blind. Failures in that window (§4.1) never produce a handshake. Cover it with
a launch deadline plus three corroborating observations, in this order of
strength:

1. **`<instance>/logs/launcher_log.txt` is truncated and re-headered at launch.**
   Modrinth opens it with `truncate(true)` and writes
   `# Minecraft launcher log started at <timestamp>` followed by
   `# Instance: <path>`
   ([`state/process.rs` L150–179](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L150-L179);
   the path is `<instances>/<path>/logs/`,
   [`state/dirs.rs` L157–161](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/dirs.rs#L157-L161)).
   **[measured]** the header and the NeoForge/FML output are both present in
   this machine's file. This is a filesystem-observable "launch actually
   started" signal that needs no process APIs at all.
2. **A new `javaw.exe` appears as a descendant of the Modrinth App PID** whose
   `startInstant` is after the spawn. Cheap in pure Java; filter descendants by
   `info().command()` ending in `javaw.exe`, since the WebView2 children
   dominate the descendant set (**[measured]**: 12 descendants, all WebView2).
3. **Its `Win32_Process.CommandLine` contains the instance path** and
   `com.modrinth.theseus.MinecraftLaunch` — use only if steps 1–2 are
   ambiguous, e.g. two instances launched close together.

If the deadline expires with none of these, the runner fails the arm and — per
the map's "no CLI, never prompt" rule — records a diagnosis rather than asking
anything. `launcher_log.txt` and the app's own
`%APPDATA%\ModrinthApp\launcher_logs\session_*.log` are the two artefacts to
attach.

### 5.5 PID reuse is a real hazard here

Windows says the identifier *"is valid from the time the process is created
until the process has been terminated"*
([Process Handles and Identifiers](https://learn.microsoft.com/en-us/windows/win32/procthread/process-handles-and-identifiers)),
and `Win32_Process` is blunter: *"ProcessIDs are valid from process creation
time to process termination. Upon termination, that same numeric identifier can
be applied to a new process… you cannot use ProcessID alone to monitor a
particular process."*
([Win32_Process](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-process)).
The same page warns that `ParentProcessId` may refer to a terminated or
recycled process and recommends disambiguating with `CreationDate`.

A benchmark sweep starts and stops dozens of JVMs, so this is not theoretical.
Two mitigations, both worth taking:

- **Carry `(pid, startInstant)` as the identity**, never the bare PID. This is
  what the JDK itself does — `parent0` and `destroy0` both refuse to act when
  the recorded start time no longer matches
  ([`ProcessHandleImpl_win.c`](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c),
  [`ProcessHandleImpl.java` L374–378](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/lang/ProcessHandleImpl.java#L374-L378)).
- **Hold an open handle.** Handles *"are valid until closed, even after the
  process… has been terminated"*
  ([Process Handles and Identifiers](https://learn.microsoft.com/en-us/windows/win32/procthread/process-handles-and-identifiers)),
  which is what keeps the kernel object — and therefore the identifier — from
  being recycled while the watcher holds it. This requires FFM (§6.1).

## 6. Distinguishing exit, crash and hang from outside

### 6.1 Exit and exit code

`ProcessHandle.onExit()` works for a non-child process on Windows and is
genuinely event-driven, not polled: `waitForProcessExit0` does
`OpenProcess(SYNCHRONIZE | PROCESS_QUERY_LIMITED_INFORMATION)` then
`WaitForSingleObject(handle, INFINITE)` then `GetExitCodeProcess`
([`ProcessHandleImpl_win.c` L100–127](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/windows/native/libjava/ProcessHandleImpl_win.c#L100-L127)).
The Windows implementation never returns the `NOT_A_CHILD` sentinel (`-2`), so
the sleep-and-poll fallback in `ProcessHandleImpl.completion` is unreachable on
Windows
([`ProcessHandleImpl.java` L124–182](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/lang/ProcessHandleImpl.java#L124-L182)).

**But the exit code is discarded.** `onExit()` maps the completion with
`handleAsync((exitStatus, unusedThrowable) -> this)`
([`ProcessHandleImpl.java` L186–192](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/lang/ProcessHandleImpl.java#L186-L192)).
Java gives exit codes only for direct children, and the game is not our child.

Three ways to recover it, in order of preference:

1. **FFM (`java.lang.foreign`, standard since JDK 22) calling `OpenProcess` /
   `WaitForSingleObject` / `GetExitCodeProcess` directly.** This also gives the
   held handle from §5.5. Note `GetExitCodeProcess` returns `STILL_ACTIVE`
   (259) for a live process, and Microsoft explicitly warns against
   applications using 259 as their own exit code
   ([GetExitCodeProcess](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getexitcodeprocess)).
   The runner is our own JVM, so it can be started with
   `--enable-native-access=ALL-UNNAMED`; without it, JDK 24+ emits restricted-
   method warnings and a future release will refuse outright. (**[measured]**:
   this machine's game log already shows those warnings from C2ME.)
2. **Parse `<instance>/logs/launcher_log.txt`.** On exit, Modrinth appends
   `\n# Process exited with status: <ExitStatus>\n`
   ([`state/process.rs` L845–855](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/process.rs#L845-L855)).
   **[measured]** this machine's file ends with
   `# Process exited with status: exit code: 0`. Zero native code, but
   Modrinth-specific and format-fragile.
3. **`Win32_Process` disappearance plus `TerminationDate`** — weak; the docs
   note `TerminationDate` is `NULL` unless a handle is held open.

Recommendation: FFM as primary, `launcher_log.txt` as a corroborating record
attached to the run artefacts (it costs nothing and is human-readable).

### 6.2 Crash

Crash is a classification of an exit, not a separate event. Signals, all
observable externally:

- Non-zero exit code (§6.1).
- A new file in `<instance>/crash-reports/*.txt`. Modrinth's own watcher keys
  on exactly this
  ([`watcher.rs` L74–83](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/instances/watcher.rs#L74-L83)),
  and the directory exists on this machine.
- A JVM-level `hs_err_pid<pid>.log` in the working directory — i.e. the
  instance directory (§5.1), and named with the PID Laymark already knows.
- Handshake socket closed before the mod reported an orderly end-of-run.

The distinction Laymark actually needs is **"did this arm produce a complete,
trustworthy measurement?"** — so the run should be marked invalid unless the
mod reported orderly completion *and* the process then exited cleanly. Exit
code alone is not sufficient: Minecraft can exit 0 after a soft failure.

### 6.3 Hang

There is no reliable OS-level "this JVM is wedged" signal.
`IsHungAppWindow` exists — it reports a window that has not called
`PeekMessage` within an internal 5-second timeout — but Microsoft labels it
*"not intended for general use. It may be altered or unavailable in subsequent
versions of Windows"*, notes the 5-second criterion *"is subject to change"*,
and that ghost windows always return `TRUE`
([IsHungAppWindow](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-ishungappwindow)).
It also needs an `HWND`, which means enumerating windows by PID. It is at best
a diagnostic annotation, never a decision input.

Hang detection therefore belongs to the harness protocol, not the OS:

- a **heartbeat** on the handshake connection with a deadline, plus
- a **per-phase deadline** (launch → world ready → warmup → measure → flush),
  since a hang during world generation and a hang during measurement mean
  different things, and
- **liveness corroboration**: a process that is burning CPU
  (`ProcessHandle.info().totalCpuDuration()`, which *is* populated on Windows)
  but missing heartbeats is a different failure from one that is idle.

On timeout the runner terminates. `ProcessHandle.destroyForcibly()` maps to
`TerminateProcess` and does not kill descendants; for guaranteed teardown of
anything the game spawned, assign the game to a Windows **job object** with
`JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`. Since Windows 8 a process may belong to
multiple (nested) jobs, so joining a process the runner did not create is
legal. This needs FFM and is worth doing only if stray child processes are
observed in practice.

## 7. The fallback: launching without Modrinth App

This should exist regardless of whether the deep link works, because the map's
loader-adapter discipline implies a launcher seam too, and because §4.1's
"must be signed in, must already be installed, must not be quarantined"
preconditions are hostile to unattended runs.

### 7.1 The material is already on disk and is complete

**[measured]** `%APPDATA%\ModrinthApp\meta\versions\26.1.2-26.1.2.95\26.1.2-26.1.2.95.json`
is the merged vanilla+NeoForge manifest:

```
mainClass    net.neoforged.fml.startup.Client
javaVersion  { component: java-runtime-epsilon, majorVersion: 25 }
assetIndex   30
libraries    165
arguments.jvm   [... "--sun-misc-unsafe-memory-access=allow",
                     "--enable-native-access=ALL-UNNAMED",
                     "-Djava.library.path=${natives_directory}" ...]
arguments.game  ["--username","${auth_player_name}","--version","${version_name}",
                 "--gameDir","${game_directory}","--assetsDir","${assets_root}",
                 "--assetIndex","${assets_index_name}","--uuid","${auth_uuid}",
                 "--accessToken","${auth_access_token}", ...]
```

alongside `meta/libraries`, `meta/assets`, `meta/natives`, `meta/log_configs`
and `meta/java_versions/zulu25.36.15-ca-jre25.0.4-win_x64`. Every substitution
token is filled from data Laymark already has or can read. The argument
construction Modrinth performs is itself a readable reference implementation
([`launcher/args.rs`](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/args.rs)).

Under this route the runner spawns the JVM as its **own direct child**, which
eliminates every problem in §5 and §6 at a stroke: `Process.pid()`,
`Process.onExit()` *with* an exit code, piped stdout, and `destroy()`
semantics, all from plain Java with no native code.

### 7.2 The one real gap is authentication

`${auth_access_token}` and `${auth_uuid}` have to come from somewhere.
Modrinth stores them in `app.db` (**[measured]**: `minecraft_users(uuid,
active, username, access_token, refresh_token, expires)`, one row). Reading
another application's session tokens is a line Laymark should not cross, on
both security and ToS grounds, and refreshing them would require implementing
Microsoft/Xbox auth.

Since 0.x is singleplayer-only by the map's own scope, the honest option is
**offline-style credentials**: a fixed dummy username and a deterministic
offline UUID with a placeholder token. This is standard practice for launchers
in offline mode and is sufficient for a local world, but it is **unverified**
against MC 26.1.2 + NeoForge 26.1.2.95 — see §8.

### 7.3 Prior art for the metadata side

Inlay already resolves instance identity for five launcher families entirely
from on-disk state, without launching anything:

| Launcher | Evidence Inlay reads |
| --- | --- |
| ATLauncher | `<instance>/instance.json` → `launcher.{name,loaderVersion}`, `id` |
| Prism / PolyMC | `<instance>/mmc-pack.json` components + `instance.cfg` `name=` |
| GDLauncher Carbon | `<root>/instance.json` `game_configuration.version.{release,modloaders}` |
| Modrinth App (current) | `app.db` `instances` ⋈ `instance_content_sets`, honouring `settings.custom_dir` |
| Modrinth App (legacy) | `<instance>/profile.json` |

(`C:\Users\mia\Development\lucent-mc\inlay\src\lib\instance-metadata.ts`.)

Two things to lift verbatim: (a) Modrinth's data directory is
`%APPDATA%\ModrinthApp` but can be relocated via `settings.custom_dir` or
`THESEUS_CONFIG_DIR`, so never hard-code the profiles root — **[measured]**,
`custom_dir` is populated on this machine; (b) open `app.db` read-only and
treat an unrecognised schema as "no evidence" rather than an error.

What Inlay proves is that *instance metadata* is portable across launchers.
What it does not answer, and what Laymark would have to add per launcher, is
where the shared assets/libraries/Java live — but Laymark only needs that for
the launchers it actually supports, and 0.x needs exactly one.

## 8. What is not verified, and what would settle it

Stated plainly, because these are the load-bearing gaps:

1. **A real end-to-end launch was not performed.** The measured deep-link test
   deliberately used a nonexistent instance ID so that nothing started. What
   would settle it: spawn
   `"Modrinth App.exe" "modrinth://launch/instance/local:93557cf6-04f8-44e1-83a7-768758b4919e"`,
   confirm a `javaw.exe` appears as a descendant of the Modrinth App PID,
   confirm `<instance>/logs/launcher_log.txt` is re-headered, and confirm the
   game reaches the title screen with no world loaded.
2. **Cold start with a deep-link argument was not measured.** The code path is
   unambiguous (§1.3) and the "no argument" half of it was measured, but the
   app was already running throughout. What would settle it: close Modrinth App
   fully, spawn the exe with the URL, and check the new session log for
   `opening command Some("modrinth://…")`.
3. **Offline credentials against MC 26.1.2 / NeoForge 26.1.2.95 are
   unverified.** Whether the 26.x client accepts a placeholder access token for
   a purely singleplayer session is an empirical question about this specific
   version. What would settle it: one direct-JVM launch from the on-disk
   manifest with dummy credentials, to the title screen and into a created
   world.
4. **Deep-link stability across Modrinth App updates is unknowable.** The
   interface is undocumented (§1.6) and the app auto-updates (**[measured]**:
   this machine self-updated 0.17.7 → 0.17.10 on 14 August). Nothing settles
   this permanently; the mitigation is a startup self-check that compares the
   installed `app_metadata.app_version` against a known-good range and records
   the version in every run artefact.
5. **Whether `hide_on_process_start` / window focus perturbs measurements.**
   Modrinth minimizes its window on launch when that setting is on
   ([`launcher/mod.rs` L1110–1122](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/mod.rs#L1110-L1122));
   it is on for this machine. Whether launcher-window behaviour or focus
   changes measurably affect frame timing is a question for the measurement
   tickets, not this one, but it is a confound introduced *by the launcher* and
   is absent from the direct-JVM route.

## Decisions needed from Mia

1. **Is the Modrinth deep link the primary launch path for 0.x, with the
   direct-JVM launcher as fallback — or the reverse?**
   *Recommendation:* deep link primary, direct-JVM as an implemented fallback
   behind the same seam, not a stub.
   *Trade-off:* deep link keeps Laymark inside the launcher's world (managed
   Java, real account, no auth code to write, no risk of Laymark and Modrinth
   disagreeing about what is installed) at the cost of a fire-and-forget,
   undocumented interface and unavoidable playtime/analytics writes. Direct-JVM
   gives a real child process with a real exit code and works unattended, but
   Laymark then owns Java selection, classpath assembly, natives, asset
   resolution and credentials — and any divergence from what Modrinth would
   have run silently invalidates the comparison against the pack as users run
   it.

2. **Do you accept that benchmarking through Modrinth App reports playtime for
   every mod in the instance to Modrinth's analytics, once per benchmark arm?**
   *Recommendation:* accept for 0.x, and record it in the runner's docs.
   *Trade-off:* the alternative is going direct-JVM primary, which trades a
   cosmetic data-pollution problem for a much larger correctness surface.

3. **May the runner require a signed-in Minecraft account and a fully installed
   instance as hard preconditions, failing the run otherwise?**
   *Recommendation:* yes — check both read-only before launching and fail with a
   named diagnosis.
   *Trade-off:* this makes unattended/CI benchmarking impossible via the deep
   link, which pushes CI onto the direct-JVM route with offline credentials.
   Given the map has no CI story yet, accepting the constraint now is cheaper
   than building the offline path now.

4. **Is native code in the runner acceptable — specifically `java.lang.foreign`
   calls to `OpenProcess`/`WaitForSingleObject`/`GetExitCodeProcess`, and the
   `--enable-native-access` flag that comes with it?**
   *Recommendation:* yes, confined to one small Windows-only class behind an
   interface, with the `launcher_log.txt` parser as a pure-Java degraded mode.
   *Trade-off:* without it, the runner cannot get an exit code for a non-child
   process and cannot hold a handle to prevent PID reuse, leaving it dependent
   on parsing a Modrinth-specific log line. With it, the runner gains a
   platform-specific component that will need a Linux/macOS counterpart if
   Laymark ever leaves Windows.

5. **Where does the run manifest live: `<instance>/config/laymark/`, or outside
   the instance entirely with the port passed another way?**
   *Recommendation:* `<instance>/config/laymark/run.json`. It is the only
   in-instance location that provokes no Modrinth watcher reaction (§3.1), and
   the mod can find it with zero configuration.
   *Trade-off:* it puts Laymark state inside a directory Inlay also manages and
   that a user might sync or wipe; a stale manifest must be defended against by
   nonce and timestamp. Passing the port via an environment variable or system
   property would be cleaner, but neither is settable through the deep link
   without writing to Modrinth's database, which the design forbids.

6. **Should Laymark ship the launcher-agnostic route for other launchers
   (Prism, ATLauncher, GDLauncher) in 0.x, given Inlay already detects them?**
   *Recommendation:* no. Build the seam, support Modrinth App only, and let the
   direct-JVM fallback be Modrinth-metadata-driven for now.
   *Trade-off:* supporting more launchers widens the user base of the preview
   but multiplies the "where do assets/libraries/Java live" surface, and 0.x's
   only consumer is a Modrinth App instance. Deferring costs nothing structural
   as long as the seam exists.
