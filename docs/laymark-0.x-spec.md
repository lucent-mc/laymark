# Laymark 0.x — specification

Status: **complete**. Every decision below was reached and recorded on the
[wayfinder map](https://github.com/lucent-mc/laymark/issues/1) and its twenty closed tickets,
which hold the reasoning, the rejected alternatives, and what each cost. This document is the
implementable summary; the tickets are the argument.

Supporting evidence lives in `docs/*-research.md`. Those are source-backed investigations and
remain authoritative for *facts*. This document is authoritative for *decisions*.

Two earlier documents — `benchmark-harness-plan.md` and `benchmark-harness-handoff.md` — were
deleted after an audit found 26 of their 149 claims wrong. They were never committed, so
[`plan-and-handoff-audit.md`](./plan-and-handoff-audit.md) is the sole surviving record of them.

---

## 1. What Laymark is

Laymark answers one question for a modpack developer: **which optimization mods should I keep?**

It runs repeatable A/B experiments across a mod list, measures far more than frame rate, and
produces auditable evidence.

**Laymark reports; it never decides.** It does not accept or reject a mod. It emits raw
measurements and a human-readable report, and a person chooses what to ship. Internal *promotion*
of a winner into the next round's baseline is forward-selection mechanics, not a recommendation.

**Laymark is a development-time tool and never a distribution artifact.** It must not appear in a
shipped modpack. Its presence in an instance is itself the signal that the instance is used to
build and benchmark a pack rather than to play it — so there is no dedicated benchmark instance,
no marker file and no detection logic.

It must work with **no Inlay CLI and no `inlay.index.json`**. Inlay's files are a shortcut that
avoids Modrinth API roundtrips, never a dependency.

## 2. Scope

| Axis | 0.x |
| --- | --- |
| Minecraft | 26.1.2 exactly |
| Java | 25 (stock; no JetBrains Runtime) |
| Loader | NeoForge 26.1.2.95 only |
| Fabric | interface-only stub, not built or published |
| Platforms | Windows, macOS, Linux |
| Environment | singleplayer client |
| Instrumentation | Spark 1.10.173, Chunky 1.5.4 |

**Out of scope:** a Fabric artifact; other Minecraft versions; dedicated-server and multiplayer
benchmarking; benchmarking content owned by ancestor Inlay Layers (protected baseline by design).

**Strata — results are never pooled or ranked across these:** platform and architecture; loader
and its exact dependency tuple; Inlay lineage revision; Spark sampler engine; scenario-list
revision.

## 3. Identity and artifacts

Gradle, one build. Group `cx.mia.lucent`, root package `cx.mia.lucent.laymark`, artifacts
`laymark-<module>`. NeoForge mod ID `laymark`.

Two artifacts from one build, driven by a **single version property**:

- **`laymark-neoforge-mc26.1.2-<version>.jar`** — the mod, dropped into the instance under test.
- **`laymark-runner-<version>.jar`** — shaded, executable, run with `java -jar`.

Both go to **GitHub Releases only**. Laymark is **not published to a mod host**: it is development
tooling rather than something anyone installs in a modpack they play, and it is largely
AI-authored, which Modrinth's content rules (6.1, 6.2) do not permit for a published project.

The **mod jar embeds the runner** as an inert resource, at a path NeoForge does not scan —
explicitly *not* under `META-INF/jarjar/`. Stored that way it costs disk size only: no classes on
the classpath, nothing loaded into the measured JVM, and whoever has the mod has the runner. The
embedding is **one-directional**; the runner embeds nothing.

## 4. Modules

| Module | May import | Must not import |
| --- | --- | --- |
| `core` | nothing game-related | Minecraft, any loader |
| `minecraft-common` | Minecraft, LWJGL | any loader |
| `neoforge` | NeoForge | — |
| `fabric` | — (interface-only placeholder) | — |
| `runner` | `core` | Minecraft, any loader |
| `build-logic` | — | — |

`core` holds policy: the state machine, plans, results, run identity. `minecraft-common` holds the
vanilla implementation: world creation, options, readiness barriers, frame and GPU sampling.
`neoforge` holds only bootstrap, lifecycle wiring, mod inventory and the **frame trigger**.

**`core` purity is CI-gated from the first commit.** This is architecture, not hygiene: tier-1
testing exists only while `core` is free of Minecraft and loader imports, and one accidental import
silently degrades the fastest development loop with nothing to signal it.

**No Minecraft-version seam.** `minecraft-common` compiles against 26.1.2 directly. Version stays
an axis of the *build and artifacts* — target descriptor in `build-logic`, version in artifact
names, exact version in every result — not of the code structure, until a second target produces
evidence about where the boundary actually falls.

**The loader seam:** the measured *quantity* comes from vanilla; the *trigger* comes from the
loader adapter. NeoForge supplies `FlipFrameEvent`; Fabric has no whole-frame event and will need a
Mixin, so the trigger contract must be expressible by a Mixin-driven implementation, not only by
event subscription. The build must tolerate a Mixin in the Fabric module while NeoForge has none.

## 5. The runner

No *interactive* CLI: invocation arguments are fine, but there is no TUI, no subcommand tree, and
**no prompting**. Every ambiguity is resolved by config or fails the run. There is **no
resumability** — an interrupted run restores state and starts over.

### 5.1 Launching the game

**Direct JVM launch. No deep links, in either form.** The runner constructs its own invocation and
owns the child process, which gives PID, exit code and lifecycle directly. Deep links give none of
those, and only direct launch can pin heap size and GC flags — otherwise an uncontrolled confound
sits beneath every comparison.

**The launcher's on-disk version JSON is not a complete launch descriptor.** Verified by
experiment (issue #21):

- The launcher wraps the launch with its own agent and main class, and omits libraries the JSON
  lists.
- **Do not add NeoForge's locally-patched client jar to the classpath.** Doing so makes FML believe
  it is in a dev environment and fail.
- The runner **must** pass `-Djava.awt.headless=true`. On a fatal startup error FML otherwise opens
  a modal AWT dialog and the main thread parks on the AWT tree lock: the process never exits and
  writes one log line. That is a hang, not a failure, and would consume an entire scenario timeout.

### 5.2 Credentials

**None required.** Laymark never connects to online servers. `--accessToken` *is* a required
`jopt-simple` option on Minecraft's `Main` — omitting it fails with
`MissingRequiredOptionsException` — but the value is never **validated** for singleplayer.

The runner passes placeholders: `--accessToken 0`, `--xuid 0`, `--clientId 0`, and a
**deterministic offline UUID** — the version-3 UUID of `"OfflinePlayer:<username>"`. Determinism is
required, not cosmetic: player data in a save is keyed by UUID, so a random placeholder would give
each arm different player state. Username and UUID are pinned like the seed.

Verified end to end with the full modded pack, through world creation and entry.

### 5.3 Mod-stack materialization

```text
<instance>/
  laymark-runner-<version>.jar   the runner itself, double-clickable
  config/laymark.json            the scenario config: hand-authored, THE plan
  mods/              participants only: the baseline floor and all candidates
  .laymark/          Laymark's working state: withheld mods, staged scenes, results
```

**`config/laymark.json` is the single source of what a run measures**, and it is hand-authored —
the runner never writes it. Runner and harness resolve the same document (the runner for
scheduling and timeouts, the harness for execution), so there is no separate plan file to drift
from it; the run id and output directory, the only run-shaped facts the config cannot carry, pass
on the launch command line, and the fully resolved plan is archived beside the results. Everything
Laymark produces or caches lives under **`.laymark/`**, dot-prefixed so launchers, pack tooling and
Inlay all read it as internal state rather than authored content.

The runner sits at the **instance root**. It is the file someone opens, and the first place they
look for it is the folder they already have open.

- Candidates toggle by rename, `foo.jar` ↔ `foo.jar.disabled`, the convention launchers already use.
- Non-participants move to `laymark/withheld/` at the start and are restored at the end.
- **Loader-agnostic by construction**: NeoForge's `ModsFolderLocator` filters on `endsWith(".jar")`
  and Fabric's directory scan does the same, so a `.jar.disabled` file is invisible to both. This
  is plain filesystem work with no loader-specific code, and it works on Fabric unchanged.
- **Recovery is a recorded initial state, not an operation journal.** Capture once at the start;
  recovery is restoring to it. If disk does not match the recorded state at startup, restore first.
- **Pre-launch verification is the real guard.** Before every launch, confirm `mods/` matches the
  intended arm by presence and hash. A rename that silently failed would otherwise produce a
  perfectly plausible run of the wrong stack.
- `inlay.index.json` is never mutated. Laymark needs a `.layignore` entry so it is not reconciled
  into the Layer and shipped — documented guidance for Inlay users, never enforced.

### 5.3b Runner GUI

The runner has a **GUI**, alongside the headless mode unattended runs use. It opens two ways:
**`--gui`** attaches a window to the run the arguments already describe, and **launching with no
arguments at all** — double-clicking the jar — opens the planning view first.

"No interactive CLI" stands. The planning view is the one place Laymark's GUI configures anything,
and it exists so the tool can be opened by someone who does not have the flags memorised; every
choice it offers has a flag equivalent and both reach the same experiment. Once **Start** is
pressed the window only observes and controls, and Start becomes Pause.

- **Planning**: the instance (launcher profile and version), the capture window, repeats per arm,
  render distance, and a **checklist of the mods currently installed**. A checked mod becomes a
  candidate, so the baseline is the pack with every candidate withheld and each arm is "with it"
  against "without it" — not against the pack as found.
- **Status**: current state, progress as **`18/28 arms in 4/7 runs`** (arms are launches, runs are
  selection rounds — §8.1), elapsed time, and estimated time remaining. The estimate is
  extrapolated from the arms that have finished and is blank until one has.
- **Pause / resume / stop.** Pause takes effect **at the next run boundary, never inside one** —
  suspending a game mid-capture contaminates the window, so pausing means "finish the current run,
  then hold". Stop is immediate: the current game is killed, the instance restored, and the report
  written from the runs that completed.
- **Now running**: the arm in flight and what it changes relative to the baseline stack, the
  baseline it is measured against, and the scenario currently capturing. Those three, and **no
  summary statistics for the run in flight** — no single live number distinguishes a real
  improvement from noise, and one shown beside the grid would be read as the answer. The paired
  comparison is the answer.
- **The candidate list**, every candidate with its state (queued / running / done / failed).
  Read-only once started: the plan decides what runs, so there is nothing here to add, remove,
  reorder or re-sort mid-experiment.
- **The selection grid**: one column per round, candidates ranked within the column, each shown with
  **its band as well as its percentage**, the round's winner marked — and shown as the **next
  column's baseline**, so the grid reads as the greedy selection it depicts.
- **The log**, the runner's console output teed into the window, so the operator never has to leave
  it for the terminal.

With a window attached the process **does not exit on a failing verdict**. The exit code has no
audience there, and taking the process down closes the report someone opened the window to read.

Swing, in the runner's process and never the game's, so the GUI cannot end up beneath a published
number. Its one dependency is **FlatLaf**, a look-and-feel: still no toolkit, still one jar, and
still nothing loaded into the measured JVM. Restyling every scroll bar and check box by hand would
be more code arriving somewhere worse.

### 5.4 Runner ↔ harness protocol

A dedicated **loopback socket**. The runner binds `127.0.0.1` on an ephemeral port *before*
launching, and passes the port and a nonce via `-Dlaymark.*` on the command line it already
constructs — so port discovery and the startup race both disappear.

- The mod connects and sends **token, protocol version and its own PID** as the first frame.
  Loopback is reachable by any local process, so the nonce is the access control.
- **Newline-delimited JSON**, chosen so the wire format and the durable format are byte-identical:
  the runner appends each received line straight into `events.jsonl`. Every frame is persisted on
  arrival, keeping crash forensics with the process that survives.
- **Protocol version is separate from the product version and exact-matched** at handshake.
- **One-way after the handshake.** Plans are fully resolved before launch; the correct abort is
  killing the process and rolling back the transaction.
- **Connection failure fails the run** rather than proceeding unmonitored.
- The **launch facts** are `-Dlaymark.*` system properties — port, token, run id, output directory
  — which cannot be stale the way a leftover run file could. The mod defines the contract; the
  runner satisfies it.
- The **scenarios come from `config/laymark.json`** (§5.3), which both sides resolve; the resolved
  plan is archived beside the results, never written into the instance.

Bind `127.0.0.1` explicitly rather than `0.0.0.0` to avoid the Windows Firewall prompt.

### 5.5 Result layout

```text
<output-root>/<experiment-id>/
  experiment.json
  runs/<run-id>/
    plan.json
    environment.json
    events.jsonl
    result.json
    samples/*.jsonl.gz
    spark/*.sparkprofile
    logs/
```

`<output-root>` is configured and lives **outside** the instance. Samples are JSONL + **gzip**, not
zstd: zstd needs a native library inside the measured process, which contradicts the reasoning that
kept Kotlin out. gzip ships in the JDK.

## 6. The in-game harness

### 6.1 Phases and barriers

Four measured phases. Each has both a positive precondition (we are set up) and, for two of them, a
**negative** one (the work has not already happened).

| Phase | Measures | Positive | Negative |
| --- | --- | --- | --- |
| **Spawn generation** | world creation → join | fresh save | — |
| **Ungenerated traversal** | generation + everything downstream | joined, staged, controlled | target **never generated** |
| **Generated, not resident** | client-side streaming | chunks verified on disk | client holds **no built sections** |
| **Resident render** | steady render path | composite predicate below | — |

Spawn generation is a phase, not overhead: mods target spawn-chunk generation specifically.

**Only phase 3 means "everything finished."** Its barrier is a composite predicate — client loaded,
loading screen dismissed, all render sections built, client view distance converged to the effective
render distance, all stable for N consecutive ticks, with Laymark's own timeout that **fails the
run**.

**Vanilla `LevelLoadTracker` is not used alone.** Its 30-second escape hatch reports ready on an
unbuilt world with only a log line, and fires *preferentially on the slowest runs* — so it would
hand back an unbuilt world exactly when loading was hardest. The resulting error is not noise; it is
bias correlated with the independent variable, which repetition cannot remove.

**The negative preconditions are where the danger is.** A phase-2 run whose target was already
generated completes normally and reports *flatteringly good* numbers. Both negative preconditions
therefore fail the run rather than degrade it.

**The barrier is part of the apparatus.** `hasRenderedAllSections()` is a `LevelRenderer` question,
and renderer-replacing mods reimplement that subsystem. Every result records which conditions were
checked, how long each took to satisfy, and how many ticks to stabilise — so "did both arms start
from comparable states" is answerable after the fact instead of assumed.

**Phase 3 is not a cold-disk test.** After Chunky writes region files they are in the OS page cache
and a mod cannot portably evict it. Name it client-side streaming.

**Chunky is precondition machinery only.** It writes chunks to disk server-side and does nothing to
make the client load or mesh them, so "Chunky complete" is never a client readiness signal. The
completion barrier is `GenerationProgressEvent.complete()`, not `GenerationCompleteEvent`, which
fires on loop exit without joining in-flight futures and on *every* exit path including cancellation.
Chunky's `whenComplete` discards throwables and still counts the chunk done — it reports 100% success
even if every chunk failed — so independent sample-verification of the generated footprint is
required, not optional.

### 6.2 Client control

- **Windowed only, default 1600×900.** No fullscreen, so no GLFW verification is needed
  (`Window.isFullscreen()` returns the *requested* flag, not the effective one). Named bias: a
  smaller window is less GPU-bound, so CPU-side differences show more clearly — right for the mods
  being targeted, but fill-rate-bound mods will measure weaker here than they perform at 1440p.
- **Mandatory, non-configurable overrides:** `pauseOnLostFocus` false, `inactivityFpsLimit`
  `MINIMIZED`, framerate limit off, vsync off. The inactivity throttle is separate from the FPS
  limit and triggers on absence of **input**, not loss of focus — a static-camera benchmark
  generates no input while perfectly focused, and would be silently throttled to 30 FPS at the
  sixty-second mark.
- **`Options.applyGraphicsPreset` is called unconditionally at the start of every scenario.**
  `OptionInstance.set` skips the update callback when the value is unchanged, so re-applying a
  preset within one launch is otherwise a no-op and the previous scenario's overrides survive.
  `OptionInstance.set` also silently substitutes the option's **default** for an out-of-range value.
- A scenario may **name** a `GraphicsPreset`; the result records the **read-back expanded** option
  set and hashes run identity over that, because presets resolve differently across GPU vendors and
  operating systems.
- **Game rules are applied post-join** through the integrated server — `createFreshLevel` does not
  accept them. `ADVANCE_TIME` and `ADVANCE_WEATHER` off, `spawnChunkRadius` 0, mob spawning off.
- **Positioning:** creative game mode with flight, teleport to the far target, pitch 90, input
  suppressed. World spawn is left alone — setting it to the target would generate the target during
  world creation and destroy the phase-2 precondition. No join-path modification: phase 1 includes
  the join. The teleport **is** the measured event and may not complete synchronously, so the
  capture must bracket the arrival rather than sit inside a blocking call.
- Altitude and FOV are the frustum levers; verified in practice at Y=500 with Quake Pro FOV covering
  a 32-chunk render distance.

### 6.3 Measurement channels

**Every scenario always records the same channel set.** The stop condition selects only which
channel is the **scored** metric; everything else is recorded and reported as diagnostic. A
three-day run must never end with the realisation that the interesting channel was not captured.

| Channel | Source | Scored |
| --- | --- | --- |
| **Frame interval** | flip to flip, timestamped at `FlipFrameEvent` | **yes** |
| Render call | vanilla `Minecraft.getFrameTimeNs()` | no |
| Submit phase | `RenderFrameEvent.Pre`/`.Post` bracket | no |
| GPU execution time | vanilla `TimerQuery`, harvested asynchronously | no |
| Integrated-server MSPT and TPS | Spark statistics | no |
| GC | Spark statistics | no |
| Heap | `Runtime`, read at each end — `spark-api` exposes no heap | no |
| Work performed | rendered sections, client chunks, server chunks, at each end | no |
| Time per chunk | derived | for completion targets |
| Throttle reason, per frame | `FramerateLimitTracker` | no |
| Completion time | — | for completion targets |

**The three CPU timings nest, and the gaps between them are the point.** A mod that grows the
interval without touching the render call has moved its cost into the client tick or the swap,
which points somewhere entirely different than one that grows both.

**The headline is the interval between flips, not `getFrameTimeNs()`.** This corrects the original
decision on issue #5. That field is assigned from a timer started at `Minecraft#runTick` line 1365
— *after* the frame's client ticks have already run at line 1308, along with sound, toasts and
input — and is read before `flipFrame`, so it also excludes the buffer swap and the limiter wait.
It is therefore blind to client-tick cost entirely, which is precisely where a large class of mods
spends its time. Measured on a real run it read 53% of the true interval: low by enough to look
like a fast machine rather than a broken measurement. It is retained as its own channel, where
that narrowness is the useful property.

**No Mixin is needed on NeoForge.** `GameRenderer.render` is **not** a whole frame on 26.1 —
`extract` precedes it, where NeoForge patches its GUI hooks — so both a Mixin and a
`RenderFrameEvent` bracket the wrong interval, identically. Timestamping at `FlipFrameEvent` also
puts Laymark's own sampling code *outside* every interval it reports. `FlipFrameEvent` implies a
NeoForge floor of build 26.1.2.73, comfortably below the pinned 26.1.2.95.

**Work counters travel with every timing.** A mod that draws fewer sections posts better frame
times honestly, and whether that is an optimisation or a downgrade is a judgement only a reader
with the work counts can make. This is also what makes a mod that deliberately changes the
workload — culling, most obviously — a valid comparison rather than a cheat: the change is visible
instead of being inferred from a suspiciously good number.

**Never block on a GPU query and never call `glFinish`.** A timer query resolves several frames
after the work it timed; reading it eagerly means waiting for the GPU, which changes the thing
being measured. Outstanding queries sit in a bounded queue and are harvested once `isDone()` is
already true, and the last few frames' queries are cancelled at the end of a window rather than
waited for. Timer queries are a driver capability: if anything about them fails, the channel
switches itself off for the run and records why, rather than failing a run over a diagnostic.

**The throttle reason is recorded per frame** and analysed after the capture, so the hot path
performs only a field read. A window that begins throttled is refused outright, since there is no
measurement to qualify; a throttle that engages partway through leaves real samples with an
artificial ceiling in the middle of them, and is flagged for the reader to weigh.

**Laymark is designed around Spark and reimplements none of it.** Spark **statistics** are always
on: MSPT, TPS and GC come from `spark-api` — `SparkProvider.get()` and the `mspt()`, `tps()` and
`gc()` accessors — rather than from timing that Laymark does itself. Spark does this well, and a
second implementation living beside it would be a second thing to be wrong.

`spark-api` is `compileOnly`. It is never bundled: the installed Spark mod supplies the
implementation at runtime, and shipping a copy would put a second one in the process being
measured. Every access is guarded, because Spark may be absent and `SparkProvider.get()` throws
before Spark has enabled. An absent Spark leaves the statistics channels empty and flags the run;
it does not fail it.

Two limits of that API, recorded because they change how the numbers read. Its statistics are
**rolling windows**, not capture-scoped aggregates — the shortest are `MillisPerTick.SECONDS_10`
and `TicksPerSecond.SECONDS_10` — so read at the end of a longer capture they describe its tail
rather than the whole. The window length is stored beside the figures so a reader is told which
period they cover instead of assuming it matches the capture. (This corrects
`spark-chunky-control-surfaces-research.md`, which lists only the minute-and-longer windows for
MSPT; the published jar has `SECONDS_10`.) GC is exempt — Spark reports cumulative totals, so that
one really is differenced across the capture.

Second limit: `spark-api` exposes no heap figure at all, so heap alone is read from `Runtime`. GC
still comes from Spark.

The Spark **profiler** is an opt-in diagnostic pass whose metrics can never reach a score:
Spark ships no async-profiler binary for Windows and falls back to a sampler whose overhead scales
with thread count — that is, with the thing being compared. `samplerEngine` is recorded in every
result and results are never compared across engines.

Spark control: `config/spark/activity.json` is the sole authoritative artifact binding — "wait for
command completion" does not exist, since Spark discards the future and Brigadier returns before
anything happens. Laymark manages `config/spark/config.json` to force `backgroundProfiler: false`,
which is **enabled by default on the integrated server** and would contaminate every run.

## 7. Scenarios

A single config file with a **`scenarios[]` array**. Each scenario is self-contained:

- **Metadata** — stable id, `dependsOn`, duration or completion stop condition, `repetitions`
- **Game settings** to ensure or override
- **World** — seed, newly generated or reused
- **Content** — Sponge Schematic v3 (`.schem`) references with origin, and entity data
- Laymark ships a **default scenario set**; a custom config path is an invocation argument

Scene geometry uses **Sponge Schematic v3**, the actual cross-tool standard (WorldEdit and FAWE read
and write it; Litematica exports to it). Parsing is nearly free — `minecraft-common` already imports
Minecraft, so `NbtIo` and `BlockStateParser` suffice and no third-party dependency enters the
measured process. **Entities are part of the format**, so a whole scene is one file.

**Placement goes through vanilla APIs, never the command system** — commands are modifiable by mods,
so building a scene through `/fill` and `/summon` would let a candidate under test alter scene
construction between arms. Entity UUIDs in the file are regenerated deterministically or stripped;
mobs need `NoAI` and `PersistenceRequired`. A `DataVersion` mismatch is detected and refused or
fixed, never placed blindly. A missing palette entry is a hard fail, not a substitution. Placed
block and entity counts are verified against the file before any capture.

**The rule for adding scenes:** a scenario earns its place only if the mod class it targets can
actually differentiate on it. A scene where vanilla already handles the case measures nothing and
scores every candidate neutral.

Two scenes to start:

- **Chunk generation / loading / rendering.** New world, teleport far from spawn, Y≈500, wide FOV,
  pitch 90. Geometry and chunk throughput; blind to culling and fill-rate mods. Straight-down is a
  **validity requirement**, not a coverage optimisation: chunk loading is view-dependent (verified
  empirically), so camera direction and the stack jointly determine how much work a scenario
  performs, and pointing down normalises every horizontal direction so both arms are asked for the
  same work.
- **Entity culling.** Large pen, player centred, animals at fixed coordinates with `NoAI`, and
  **solid barriers inside the pen** so part of the herd is in-frustum but occluded. Vanilla already
  frustum-culls what is behind the camera, so entities merely behind the player measure nothing;
  occluded-but-in-frustum entities are the work only a culling mod elides.

## 8. Experiment model

### 8.1 Vocabulary

**arm → pass → scenario**: parallel, then sequential, then sequential.

- **Arm** — an A/B arm, mod off or mod on. Runs as its own process.
- **Pass** — one full traversal of the scenario list within an arm.
- **Scenario** — one entry in the `scenarios[]` array.

### 8.2 Run structure

Each arm is **three full scenario sequences**:

1. **Acclimation (`A`)** — a full baseline run with full instrumentation whose measurements are
   discarded. Measured rather than skipped, because the instrumentation itself needs warming: frame
   sampling, GPU queries and socket emission are code that JITs and buffers that allocate. It warms
   state that survives process death — OS page cache, GPU driver shader caches, mod on-disk caches.
   Baseline only, once at the start. *Accepted cost:* a candidate with its own shader cache pays that
   once inside its first measured run, biasing against renderer-class mods; higher `repetitions`
   dilute it.
2. **Cold pass** — fresh JVM.
3. **Warm pass** — the same sequence again, behind 5+ scenarios of JIT, heap growth and GC.

Both measured passes are kept as data; the JVM-warmth delta is a result, not a cost.

**One process per arm.** All scenarios run in one launch — arm switches force a relaunch regardless,
since mods load at startup. Contamination is symmetric, so **array position is part of a scenario's
identity**: scenario 1 runs against a colder JVM than scenario 5, and reordering or inserting one
invalidates comparison against earlier results. Every result records the scenario-list revision.

Between phase-1 repetitions the save and all its region files are **deleted**, which frees their
cached pages.

### 8.3 Scheduling

Three independent axes:

- **Round template** — slot symbols re-expanded each round: `A` acclimation, `B` a baseline run, `C`
  one round-robin pass over remaining candidates, `Cn` blocked repetition (each candidate `n` times
  consecutively), `C,C` two round-robin passes. Example: `B,C,C,B,C2` expands to 22 runs for five
  candidates.
- **`--baseline-interval N`** (default 5) — a floor on baseline frequency: no more than N candidate
  runs may pass without a `B`. A fixed template does not scale; at 20 candidates a single baseline
  would sit hours from the last candidate run. `B` slots **are** the drift checks, and a tighter
  interval localises drift to a smaller window, so only those runs are voided.
- **`repetitions`** in the scenario schema — how many times a scenario repeats within one arm run.

### 8.4 Candidate selection

**The invocation names the candidates** — a comma-separated mod list. There is no classification
file and no role taxonomy. **Inlay layer scope becomes validation, not selection:** given a named
mod, report whether it is current-Layer, inherited, an override or an exclusion, and what the off-arm
actually means.

**Baseline, two modes by flag:** *parent only* (every current-Layer mod disabled, including unlisted
ones) or *pack minus listed* (unlisted current-Layer mods remain).

**A candidate is an atomic bundle** — itself plus its **exclusive** dependencies, being the
transitive required closure minus anything already guaranteed present. Composition depends on
baseline mode and is **recomputed every round** as the baseline grows: if X and Y both need L and X
is promoted, Y's bundle shrinks to `Y` alone. The report must distinguish a score moving because of
a genuine interaction from a score moving because a bundle shrank. If an *inherited* mod requires a
named candidate, the run fails with that explanation.

The dependency graph is built from three sources in precedence order: **JAR metadata probe**
(`fabric.mod.json`, `META-INF/neoforge.mods.toml`, jar-in-jar payloads — authoritative, offline,
cached by SHA-512, fail-closed on an unrecognised descriptor), **Modrinth API** (identity and
publisher claims; claims lose to probes), and **explicit overrides**. Incompatibility is a
first-class edge. Every edge records its source.

**Greedy forward selection, nothing eliminated.** Each round tests all remaining candidates against
the current baseline, ranks them, and promotes the single best. Because nothing is eliminated, every
candidate is re-measured against each new baseline — which is why combination-only benefits surface
without a pair-rescue phase, and why **the per-round ranking history is output rather than scratch**.

**Stop when the best remaining candidate regresses.** Margin-of-error candidates keep being
promoted, which is required: a combination-only mod presents as margin-of-error, not as a regression.

**Conflicts branch the selection** into diverging stacks. Branches share their prefix and the bound
is conflict clusters rather than candidate count, so this does not meaningfully explode. Report the
projected run count before starting.

**Promotion order matters for runtime too:** completion-target scenarios finish faster on a better
stack, so promoting the biggest improver first makes all remaining work cheaper. Selection is still
by weighted score — runtime must not influence which stack is recommended.

Cost: a comparison is `2 arms × 3 passes × S scenarios`; selection over N mods is `N(N+1)/2`
comparisons, reduced by the baseline interval. Long runs are accepted; there is no screening tier.

## 9. Statistics and trust

**Noise is run-to-run variation with nothing changed** — machine-specific and metric-specific, so it
never ships as a constant. It is estimated from **the baseline runs already scheduled**; no dedicated
calibration phase, because the baseline is the slowest arm.

- **Student's t, not the normal distribution, at low n.** The standard-deviation estimate is itself
  uncertain, and assuming normal produces intervals too narrow — which manufactures exactly the false
  positives the gates exist to prevent.
- **Frame-time tails are not normal.** p99 and 1% lows are right-skewed by construction and are
  **report-only guardrails**, not gated metrics.
- **Pairing does much of the work**: back-to-back arms share thermal state, so the difference is far
  better behaved than either measurement alone.
- **Placeholder floor: 3%**, until measured spread replaces it. Explicitly a guess.

**Persistent machine profile** in `~/.laymark/`, keyed by hardware fingerprint and scenario, storing
relative variance. Local only, no telemetry. **Stored variance may only widen intervals, never narrow
them** — so staleness after a driver update is harmless by construction, and the worst case is a
missed small gain rather than a recommendation built on wobble. Machine-change detection falls out
free.

**Drift is derived, not invented:** flagged when a baseline moves further than its own control
history predicts, and localised to the window between two `B` runs.

**Parity, correctly split:**

- **Stimulus parity** is a hard gate — same world, seed, position, camera, duration, and the same
  *requested* settings with *effective* values read back.
- **Output equivalence** is the real correctness gate, catching "faster because it rendered less".
  Screenshot comparison at canonical poses with a perceptual tolerance, plus visible-section counts.
- **Work performed is a recorded observable and never a gate.** Performance mods change the workload
  by design — Entity Culling doing less work for an identical picture is the product, not a defect.

**Validity is two things:** a run can be internally sound and still be incomparable to its pair. A
void comparison is reported as void rather than silently producing a number.

**Contamination policy, two tiers.** **Hard-fail** on anything Laymark can set and verify that then
drifts — a preset value reverted by another mod, the inactivity throttle firing mid-capture, Spark's
background profiler enabled, a preset not re-applied between scenarios. This makes preset
verification a **runtime invariant re-checked during a capture**, not a one-time gate, because the
mods Laymark measures are exactly the population that rewrites rendering settings.
**Record-and-flag** only what is environmental and unfixable, marking those results formally
incomparable rather than refusing to produce them.

**Evidence 0.x must produce:**

1. **False-positive self-test** — schedule `B,B,B,B`: four identical baseline runs must report no
   difference. If Laymark claims one identical run beat another, nothing else it says is trustworthy.
2. **Failure injection against the fail-closed tier** — a candidate that crashes, a preset that fails
   readback, a preset that drifts mid-capture, a phase-2 target already generated, a stimulus
   mismatch, the inactivity throttle firing, Spark's background profiler left on. That tier never
   executes during a successful run, which makes it the code most likely to be quietly broken.

**Operating assumption:** the developer hands the instance to Laymark and leaves it focused and in
the foreground, and **the machine is otherwise quiet during a measured run**. Game-streaming tools
are the sharp case — they encode the framebuffer on the GPU being measured — and an RDP disconnect
can background the game, which is what the inactivity throttle fires on. Start a run, then
disconnect entirely.

## 10. Reporting

Three layers: **raw samples** (authoritative), a **JSON summary** derived from them, and a
**Markdown report** derived from the same raw data, never from the summary. One report per selection
run, with per-comparison detail as appendix sections.

**Uncertainty is four plain-language bands, with numbers alongside:**

| Band | Meaning |
| --- | --- |
| **Improved** | real, and above the practical floor |
| **Negligible** | real, but below the configured floor |
| **No measurable difference** | within noise |
| **Regressed** | real and negative |

"A 2.1% improvement, interval −0.3% to 4.5%" is precisely the sentence most readers misread as a
2.1% improvement, which is why the band leads.

**The cut is shown as cumulative effect per position, leading, with marginal beneath.** Marginal
effects do not sum cleanly across a stack, so presenting only marginals invites the wrong arithmetic.
Laymark presents the shape of diminishing returns and asserts no threshold.

Report structure: headline (what, against which baseline, on what hardware, elapsed) → the ordered
stack → per-round ranking history → why scores moved between rounds → per-scenario results with
regressions named rather than averaged away → validity annotations → branch points → environment and
provenance.

Because a human decides, **every reason a number might be untrustworthy must reach them**.

## 11. Development

**Four tiers, and most iteration should never launch Minecraft:**

1. **Plain JUnit, no Minecraft, no loader** (seconds) — all of `core` and the whole `runner`.
2. **Headless modded JUnit** (tens of seconds) — ModDevGradle's `unitTest`.
3. **Headless GameTest server** (~a minute).
4. **Full client with Spark and Chunky** (minutes).

Contract fixtures stay at tier 1; tier 2 merely *runs* them, so they remain loader-agnostic when
Fabric arrives.

In-process reload within tier 4 is a bonus, not the plan. Stock JDK 25 redefines method bodies only —
**any added or removed lambda is structural** and forces a relaunch. **No JetBrains Runtime:** no
user is ever told to install or point Minecraft at a different JRE, and a differently-behaving JIT
must never end up beneath a published number.

- **Mixin hot-swap agent is opt-in** behind a Gradle property. Largely moot for 0.x since no Mixin
  is needed on NeoForge; it matters when Fabric lands.
- **Spark and Chunky are pinned by Modrinth version ID plus hash verification** — not by version
  number, which can resolve differently. This is what makes "instrumentation is identical across
  arms" provable. Compile-time APIs come from their own Maven repositories.
- **`MOD_CLASSES` / `fml.modFolders` are recorded and must be identical across every arm and pass**;
  the run is void if they change mid-flight. They are not dev-gated, so anything able to set them can
  inject code into a benchmarked instance.
- **The plan source is the only difference between dev and production**: the runner supplies it over
  the socket in production, a file path supplies it in development. No dev-only mode, no
  loader-specific flag.
- **IntelliJ's "Build and run using" must be set to IntelliJ IDEA.** ModDevGradle only points the
  running game at incremental compiler output in that mode; otherwise every edit pays a full module
  recompile. Documented rather than enforced, because non-delegated builds occasionally diverge and
  Laymark uses data generation.
- `--quickPlaySingleplayer` makes a relaunch cheap: the world and options survive on disk, the
  process does not.

## 12. CI and release

**Matrix: Windows, macOS, Linux.** Java builds identically everywhere, but the runner does
substantial filesystem and process work that does not — moving files, renaming suffixes, owning a
child process, binding loopback — and platform is already a stratum.

CI runs **tiers 1–3**, enforces the `core`/`runner` purity gate, and verifies the pinned
instrumentation hashes. The tier-1 wall-clock budget is a warning, not a failure.

**A release additionally requires a manual in-game check on real hardware** — the `B,B,B,B`
false-positive self-test and one failure-injection pass. Tier 4 needs a real display and GPU, and a
self-hosted GPU runner was rejected for a specific reason: **a machine running CI is a machine whose
thermal and load state is unsuitable for the benchmarking it also does.**

Publication is **manual for 0.x** and **GitHub Releases only** — see §3 for why there is no mod-host
listing. Per release: changelog, and the compatibility matrix updated with the exact tuple.

## 13. Known limitations

- **Terrain-generation mods must not be combined with other candidates** — same seed stops meaning
  same world, so the arms run different terrain. Not detected.
- **No resumability.** An interrupted selection run starts over.
- **Acclimation is baseline-only**, so a candidate with its own shader cache is measured slightly
  unfavourably on its first run.
- **Phase 3 is client-side streaming, not a cold-disk test** — the OS page cache cannot be portably
  evicted.
- **Frame distributions from completion-target scenarios come from unequal windows**; `time per
  chunk` is the duration-independent comparable quantity.

## 14. Open questions

Carried on the map's *Not yet specified* section:

1. The in-game state machine's transitions and what it journals.
2. Whether process private bytes, peak working set, and a Windows-only PresentMon adapter earn a
   place.
3. Specialized scenarios for mods that are not render-bound — startup, memory, networking, background.
4. Whether chunk loading is view-dependent in vanilla 26.1.2 or only under specific mods.
5. Whether the runner needs anything from Inlay it cannot get today.

Plus one measurable unknown: an invalid access token means failed session, profile and skin lookups
at startup, and startup is a measured phase. Quantify with a `-DummyAuth` versus `-KeepAuth`
comparison in the same clean room.
