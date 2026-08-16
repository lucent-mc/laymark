# Audit: `benchmark-harness-plan.md` and `benchmark-harness-handoff.md`

Audit date: 2026-08-16. Resolves
[Audit the plan and handoff docs against primary sources](https://github.com/lucent-mc/laymark/issues/3).

Subjects: `benchmark-harness-plan.md` and `benchmark-harness-handoff.md`.

**Both documents were deleted on 2026-08-16 as a result of this audit** — 26 of their 149
claims were wrong and much of the remainder had been superseded. They were never committed,
so no history of them exists. This audit is therefore the sole surviving record of their
content, restating each claim with a verdict and a citation. The current design record is
the wayfinder map at <https://github.com/lucent-mc/laymark/issues/1> and its closed tickets.

Trusted inputs, not audited: [`benchmark-harness-research.md`](./benchmark-harness-research.md),
[`loader-portability-research.md`](./loader-portability-research.md),
[`luckperms-build-architecture-research.md`](./luckperms-build-architecture-research.md),
[`inlay-layer-scope-and-dependency-graph.md`](./inlay-layer-scope-and-dependency-graph.md).

## Method and sources

Every vanilla, NeoForge, Spark, and Chunky claim was checked against the **actual binaries installed
on this machine**, not against documentation or recall. Those binaries are the primary sources for
this audit and are cited below by short name:

| Short name | Path |
| --- | --- |
| **patched client jar** | `%APPDATA%/ModrinthApp/meta/libraries/net/neoforged/minecraft-client-patched/26.1.2.95/minecraft-client-patched-26.1.2.95.jar` |
| **neoforge universal jar** | `%APPDATA%/ModrinthApp/meta/libraries/net/neoforged/neoforge/26.1.2.95/neoforge-26.1.2.95-universal.jar` |
| **spark jar** | `<Lucent Optimisations profile>/mods/spark-1.10.173-neoforge.jar` |
| **chunky jar** | `<Lucent Optimisations profile>/mods/Chunky-NeoForge-1.5.4.jar` |

Disassembly was done with `javap -p -c -v` from Zulu JDK 25 (`C:\Program Files\Zulu\zulu-25`).
Minecraft 26.1 ships unobfuscated, so these names are Mojang's own.

Live upstream checks: [Mojang version manifest v2](https://piston-meta.mojang.com/mc/game/version_manifest_v2.json),
[NeoForge Maven versions API](https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge),
[Fabric Meta](https://meta.fabricmc.net/v2/versions/loader/26.1.2),
[Fabric Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.2%2B26.1.2/),
[Modrinth API](https://api.modrinth.com/v2/), and the
[`modrinth/code` releases feed](https://api.github.com/repos/modrinth/code/releases).

## Headline

**149 substantive claims audited** (74 in the plan, 75 in the handoff), each numbered `P<n>` / `H<n>`
below and each carrying an inline citation.

| Verdict | Count | Meaning |
| --- | --- | --- |
| GROUNDED | 110 | traced to a primary source that owns it; no defect found |
| WRONG | 26 | false, stale, or out of scope in whole or in part — the correction is stated inline |
| UNVERIFIABLE | 13 | no source owns it; what would settle it is stated inline |

Most of the 26 WRONG verdicts are *partial* — a claim correct in substance but false in naming, in
scope, or in one clause. Those are marked as such inline rather than discarded. Nine are outright
false or stale: P11, P55, P73, H4, H14, H40, H41, H60, H70.

**The good news is larger than expected.** Everything the two documents say about *Minecraft's own
API surface* is essentially correct: every named class, method, accessor, enum constant, and
signature exists in the patched 26.1.2.95 client jar with the shape claimed. The Spark command
string is valid down to every flag. The Chunky 1.5.4 completion caveat is not just true, it is
verifiable in the shipped bytecode.

**The bad news clusters in three places.** First, the already-known fabrications (TypeScript runner,
`protocol/` module, `bench` subcommands, implementation slices) contaminate more sections than the
ticket listed — they reach into the repository shape, the module diagram, the JSONC rationale, and
the CI plan. Second, several sections are stale rather than false: they were written before the
Modrinth slug, group ID, and NeoForge-only scope were decided. Third, and most important, there are
**four substantive technical errors that no amount of re-scoping fixes**, all found only by reading
the bytecode:

- `OptionInstance.set` silently skips the update callback when the value is unchanged, which breaks
  multi-case-per-launch preset application (§P26, §H15b);
- `WorldOpenFlows.createFreshLevel` does not accept game rules, so both state machines apply them at
  the wrong phase (§P27, §H22c);
- `GameRenderer.render` is *not* the whole frame in 26.1.2 — `GameRenderer.extract`, the framebuffer
  blit, and `RenderSystem.flipFrame` are all outside it (§P8);
- Minecraft 26.1.2 has a first-party, non-blocking GPU timer-query API that both documents are
  unaware of, and reaching past it to raw LWJGL is a choice neither document knows it is making
  (§P46).

Plus one missing public API that would fix the first error outright: **`Options.applyGraphicsPreset(GraphicsPreset)`**.

## Verdicts: `benchmark-harness-plan.md`

### §Decision

**P1. "Laymark is a standalone tool whose first consumer is Lucent Optimisations." — GROUNDED.**
Consistent with the [wayfinder map](https://github.com/lucent-mc/laymark/issues/1). Note the map
spells the pack "Lucent Optimizations" while the installed profile directory is
`Lucent Optimisations`; pick one for the record.

**P2. Two cooperating sides — a desktop runner owning pack resolution/launches/recovery, and an
in-game harness owning world creation, workloads, settings, measurement, and exit. — GROUNDED**
as a shape, consistent with research §4 and §6 and with the map.

**P3. "Fabric and NeoForge publish separate artifacts." — WRONG for 0.x.** The map fixes loader
scope at NeoForge only; Fabric is an adapter seam and a stub, with no artifact built, tested, or
published. The claim is true of the eventual 1.0 design, not of this map's destination.

**P4. Seam is a versioned `run-plan.json` plus heartbeats, raw samples, Spark profiles, and a
terminal `run-result.json`. — UNVERIFIABLE.** Pure design choice; nothing external owns it. It is
internally consistent and survives the removal of the TypeScript split (the map's all-Java runner
gets the same files from `core`).

**P5. "compose `core + minecraft-common + <loader>`" and the module non-import rules. — GROUNDED**
in [`loader-portability-research.md` §Code ownership](./loader-portability-research.md) and the
LuckPerms research.

**P6. "the exact vanilla `GameRenderer.render(DeltaTracker, boolean)` method". — GROUNDED.**
Verified in the patched client jar:

```text
public void render(net.minecraft.client.DeltaTracker, boolean);
```

**P7. "NeoForge's frame event can validate that boundary." — GROUNDED, and understated.** In the
patched client jar, `Minecraft.runTick` calls
`ClientHooks.fireRenderFramePre(deltaTracker)` at bytecode offset 229, `GameRenderer.render` at 241,
and `ClientHooks.fireRenderFramePost(deltaTracker)` at 248 — with nothing between them.
`RenderFrameEvent.Pre` and `.Post` therefore do not merely *validate* the Mixin's boundary on
NeoForge; they **are** the identical boundary. Both classes exist in the neoforge universal jar.
Since 0.x is NeoForge-only, the Mixin buys nothing in this map's scope. See
*Decisions needed from Mia* #1.

**P8. Calling `GameRenderer.render` "the whole-frame boundary". — WRONG.** In 26.1.2 a frame in
`Minecraft.runTick` also contains, *outside* that call:

- before it: `GameRenderer.extract(DeltaTracker, boolean)` (offset 204) — the render-state
  extraction pass, real per-frame CPU work — and `RenderSystem.executePendingTasks()` (216);
- after it: the `mainRenderTarget` blit, `TimerQuery.endProfile()` (298), `swapBuffers` /
  `RenderSystem.flipFrame(...)` (345), and `FramerateLimiter.limitDisplayFPS(...)` (379).

The bracket is the **render-submission** interval, not the whole frame. Two consequences: the
*cadence* series (successive HEAD timestamps) is still a correct whole-frame cadence, because
`render` is invoked exactly once per `runTick`; but the *duration* series is a partial CPU frame
time and must be named accordingly.

**P9. Fabric's public main-level render events cover a narrower interval. — GROUNDED**
([`loader-portability-research.md` §Identical whole-frame instrumentation](./loader-portability-research.md)).

**P10. "Do not fork Spark … it cannot measure asynchronous GPU execution." — GROUNDED**
(research §1, §2, and the Spark-fork subsection).

### §Build and version layout

**P11. Module list containing `runner  external TypeScript orchestration` and
`protocol  shared schemas/generated types`. — WRONG.** Confirmed fabricated and abandoned by the
map: all-Java, one Gradle build, the runner depends on `core` for one compiler-enforced run-plan
definition, and nothing is published to npm.

**P12. `core` must not import Minecraft or a loader; `minecraft-common` must not import a loader;
loader modules must not own policy. — GROUNDED** (loader-portability research, LuckPerms research).

**P13. "Minecraft 26.1.2 / Java 25 is the only bootstrap target." — GROUNDED.** The Mojang manifest
lists `26.1.2` as `type=release`, released 2026-04-09, with
`javaVersion = { component: java-runtime-epsilon, majorVersion: 25 }`.

**P14. "Do not advertise broad ranges based only on compilation … each claimed target must build
both loader artifacts and pass the shared contract suite." — GROUNDED** as policy
(loader-portability research §Test and result rules), except that "both loader artifacts" is out of
0.x scope (see P3).

### §What one benchmark case means

**P15. The three phases — ungenerated traversal, generated streaming/reload, resident render. —
GROUNDED.** These are research §3's three workloads renamed one-for-one (cold world
generation/streaming, existing-chunk streaming, warm steady rendering). The rename is an
improvement; "cold" really is ambiguous.

**P16. "not a guaranteed cold-disk test because the OS page cache cannot be reliably cleared from a
portable in-process harness." — GROUNDED by absence.** Nothing in the JDK, LWJGL, or the Minecraft
client exposes page-cache or standby-list eviction; on Windows that requires an elevated external
tool (Sysinternals RAMMap `-Et` / `EmptyStandbyList`). Stated as a limitation the harness accepts,
this is correct. *What would change it:* a runner-side elevated pre-run step, which would also make
the runner require administrator rights.

**P17. Specialized cases for ModernFix (startup), Dynamic FPS (background), FerriteCore (memory),
networking mods. — GROUNDED that these mods are in the pack** (`modernfix-neoforge-5.27.20+mc26.1.2.jar`,
`dynamic-fps-3.11.7+minecraft-26.1.0-neoforge.jar`, `ferritecore-9.0.0-neoforge.jar` are all
installed). **UNVERIFIABLE that these are the right specializations** — the mapping from mod to
scenario type is a publisher-claim-driven judgement. *What would settle it:* run each candidate
through the render suite once and confirm empirically that it produces no signal there.

**P18. "repeat a workload once without Spark sampling and once with Spark sampling … avoids making
profiler overhead part of the score." — UNVERIFIABLE.** No measurement of Spark's overhead at
`--interval 4` on this hardware is cited anywhere. The reasoning is sound but the premise is
untested, and the cost is a doubling of wall-clock per case. *What would settle it:* N control
repetitions of one scenario with and without an active sampler, compared on p50/p95/p99 and 1% low.

### §User-authored benchmark configuration

**P19. "`.layignore` already excludes `docs/`." — GROUNDED but misapplied.** The Lucent profile's
`.layignore` does contain `/docs/`. However `.layignore` governs *the pack repository's* implicit
Layer content. Under the map, `benchmarks/` lives in the standalone Laymark repo, which is not an
Inlay Layer at all, so the sentence recommends a change to the wrong file. See
*Decisions needed from Mia* #10.

**P20. "Use JSON with comments (`.jsonc`) so both Node and Java can validate the same versioned
schema." — WRONG rationale.** The Node half of the justification dies with the TypeScript runner.
JSONC for author-facing files remains defensible on its own merits (comments in a benchmark suite
are genuinely useful), but the stated reason is no longer a reason.

**P21. Presets / Worlds / Scenarios / Suites as four reusable concepts. — GROUNDED.** Every setting
named under "Presets" maps to a real public accessor verified in the patched client jar (see H16).

**P22. `"maxFps": 260` as the unlimited sentinel. — GROUNDED.** `Options.<init>` constructs
`framerateLimit` from `new OptionInstance.IntRange(10, 260)` with `Codec.intRange(10, 260)` and a
default of `120`. 260 is the range maximum, i.e. the "unlimited" end.

**P23. `"graphics": "fancy"`. — GROUNDED with a naming note.** `net.minecraft.client.GraphicsPreset`
has constants `FAST`, `FANCY`, `FABULOUS`, and `CUSTOM`. In 26.1 this is a *preset*, and `CUSTOM` is
a real fourth value the schema must accept on readback even if it is never requested.

**P24. Loader must reject unknown fields, invalid inheritance, duplicate IDs, unsafe paths,
impossible phase orders; each result embeds the fully expanded case. — UNVERIFIABLE** (design
policy), and sound.

### §In-game harness state machine

**P25. Step 1 — title-screen verification of mod-set fingerprint, versions, JVM, GPU/driver, schema.
— GROUNDED** (research §6 step 1).

**P26. Step 2 — "apply the preset on the client thread … and read every effective value back. Fail
rather than silently benchmark different settings", performed entirely before any world exists. —
WRONG on two counts.**

*(a) Server-side readbacks are unsatisfiable at this point.* The plan's own preset vocabulary
includes render distance and simulation distance, whose effective values are
`PlayerList.getViewDistance()` / `getSimulationDistance()` (both verified public in the patched
client jar). No integrated server exists at the title screen. The invariant must be "fail before
*measurement*", with server-convergence readbacks deferred to the join gate. The handoff's step 7
gets this right; the plan does not.

*(b) Re-applying an unchanged value is a no-op.* From the bytecode of
`net.minecraft.client.OptionInstance.set(T)`:

```text
validateValue(...).orElseGet(...)          -> resolved value
if (!Minecraft.getInstance().isRunning())  -> store only, no callback
if (Objects.equals(this.value, resolved))  -> return, no callback
this.value = resolved; onValueUpdate.accept(this.value);
```

So on the second and subsequent cases in one launch, `graphicsPreset().set(FANCY)` when the option
is already `FANCY` **does not run the cascade**, and every explicit override from the previous case
survives. The public fix exists and neither document mentions it:
**`Options.applyGraphicsPreset(GraphicsPreset)`** is public and unconditional — it sets the
`isApplyingGraphicsPreset` guard, calls `GraphicsPreset.apply(Minecraft)`, and clears the guard.

**P27. Step 3 — "call Minecraft's world-creation flow with the resolved seed, generator, difficulty,
gamerules, and data-pack configuration." — WRONG on gamerules.** The verified signature is:

```java
public void createFreshLevel(String, LevelSettings, WorldOptions,
                             Function<HolderLookup.Provider, WorldDimensions>, Screen)
```

and `LevelSettings` is a record of `(String levelName, GameType gameType,
LevelSettings.DifficultySettings difficultySettings, boolean allowCommands,
WorldDataConfiguration dataConfiguration, Lifecycle lifecycle)`. There is **no** game-rules
component. Only the sibling `createLevelFromExistingSettings(...)` takes an
`Optional<net.minecraft.world.level.gamerules.GameRules>` — note also that `GameRules` moved to
`net.minecraft.world.level.gamerules` in 26.1. Seed, structures, and bonus chest are correct:
`WorldOptions(long, boolean, boolean)`. Difficulty and data-pack config are correct, via
`LevelSettings`. Game rules must be applied after the integrated server exists.

**P28. Step 3 — "Do not ask Modrinth Quick Play to open a world." — GROUNDED** (research §4).

**P29. Step 4 — wait for integrated server running and accepting scheduled work, client level/player
exist, initial join complete; benchmark route far from generated spawn. — GROUNDED** (research §6
step 4).

**P30. Step 6 — "Only now start the bounded Spark capture and confirm that it is active." —
GROUNDED** (research §6 step 5).

**P31. Step 7 — invalidate the run if the target was already generated or Spark did not cover the
requested interval. — GROUNDED** as policy; the artifact-stability half is directly supported by
research §1's warning that Spark's export callbacks are asynchronous.

**P32. Step 8 — Chunky over the exact route selection, then progress-complete, outstanding chunk
work, forced save, and server quiescence. — GROUNDED**, and independently re-verified against the
1.5.4 binary (see P42–P44).

**P33. Step 10 — "An optional disconnect/reopen before this step produces the distinct generated
streaming/reload phase." — UNVERIFIABLE as written (ambiguous).** "Before this step" places it after
step 9 (make-resident), which would discard the residency step 9 just established and then require
repeating it before the resident-render capture. The handoff states the same idea with a different
and contradictory placement (see H22e). One of the two must be chosen.

**P34. Step 11 — disconnect, release the save, restore the snapshot after the last case, never
delete or overwrite a user's save. — GROUNDED.** `LevelStorageSource.LevelStorageAccess` exposes
public `deleteLevel() throws IOException`, `close()`, and `safeClose()`, so deleting only
harness-created saves is enforceable in-process.

**P35. "Each repetition that needs an ungenerated phase gets a newly created disposable world …
because measuring a cold route mutates it." — GROUNDED** (research §3 "For a true A/B cold
comparison" and §7).

### §Spark adapter

**P36. The command string
`sparkc profiler start --timeout 45 --thread * --not-combined --interval 4 --save-to-file --comment <case-id>`.
— GROUNDED, every flag verified.** From the spark jar's
`me/lucko/spark/common/command/modules/SamplerModule.class` constant pool, the recognised flags
include `--timeout`, `--thread`, `--not-combined`, `--interval`, `--save-to-file`, and `--comment`
(alongside `--regex`, `--combine-all`, `--only-ticks-over`, `--alloc`, `--alloc-live-only`,
`--force-java-sampler`). Semantics from the same bytecode:

- `--timeout` is `Arguments.intFlag("timeout")`, unit **seconds** (string `timeout seconds`), with
  the error *"…choose a value greater than 10"* and the advisory *"Consider setting a timeout value
  over 30 seconds"*. `45` is valid and above both thresholds.
- `--interval` is `Arguments.doubleFlag("interval")`, unit **milliseconds** (string
  `interval millis`). `4` matches Spark's documented execution-profiler default.
- `--comment` is `Arguments.stringFlag("comment")`, which returns a `Set<String>`. **Caveat:** the
  case ID must therefore be a single whitespace-free token.
- `me/lucko/spark/neoforge/plugin/NeoForgeClientSparkPlugin` registers the literals `sparkc` and
  `sparkclient`, confirming the command root.

**P37. "Spark accepts sequential bounded profiles and writes `.sparkprofile` artifacts." —
GROUNDED** (research §1 §Sequential profiles in one launch; `sparkprofile` appears in the shipped
`SamplerModule` constant pool).

**P38. "wait for command completion and the new file rather than sleep for an assumed duration." —
GROUNDED** (research §1: the active slot clears before the export callback finishes writing).

**P39. "Use Spark's default render/game-thread profile for readable render attribution and an
all-thread, non-combined profile for chunking or parallel-worker scenarios." — GROUNDED**
(research §1 §Client command and thread scope).

**P40. "Treat Spark's public statistics interface and command surface as the supported seams; do not
import its internal sampler implementation." — GROUNDED** (research §1 §API limitation, plus the
GPLv3/MIT licensing split).

### §Chunky adapter

**P41. `ChunkyProvider.get().getApi()`. — GROUNDED.** Verified in the chunky jar:
`ChunkyProvider.get()` returns `org.popcraft.chunky.Chunky`, and `Chunky.getApi()` returns
`org.popcraft.chunky.api.ChunkyAPI`.

**P42. "verify the supported API version". — GROUNDED.** `ChunkyAPIImpl.version()` compiles to
`iconst_0; ireturn` — the supported version is `0`, exactly as the research doc states.

**P43. "start an exact world/shape/center/radius/pattern selection". — GROUNDED.**
`ChunkyAPI.startTask(String world, String shape, double centerX, double centerZ, double radiusX,
double radiusZ, String pattern)`, plus `isRunning`, `pauseTask`, `continueTask`, `cancelTask`,
`onGenerationProgress`, `onGenerationComplete`.

**P44. "Chunky 1.5.4 can emit `GenerationCompleteEvent` before its last outstanding asynchronous
chunk operations drain." — GROUNDED, confirmed in the shipped 1.5.4 bytecode.** In
`GenerationTask.run()`: `new Semaphore(MAX_WORKING_COUNT)` is created at offset 55; the static
initializer sets `MAX_WORKING_COUNT` from the system property `chunky.maxWorkingCount` defaulting to
`50`; and after the iterator loop exits the method fires `GenerationTaskFinishEvent` (offset 421)
then `GenerationCompleteEvent` (439) and returns (461) — **with no re-acquisition of the semaphore's
permits anywhere in between**. Outstanding chunk callbacks can still be in flight. The public
`GenerationCompleteEvent` record carries only `String world()`, confirming there is no completeness
information on it.

**P45. "Use `GenerationProgressEvent.complete() == true` as the primary completion barrier." —
GROUNDED.** `GenerationProgressEvent` is a record with components
`(world, chunks, complete, progress, hours, minutes, seconds, rate, x, z)`, and it is constructed
inside the private synchronized `update(int, int, boolean)`, which is called from the per-chunk
`whenComplete` callback. It is therefore emitted strictly *after* each chunk future resolves, which
is exactly the ordering property the barrier needs.

**P46. "Treat task cancellation and exceptional chunk futures as invalid runs, not successful
completion." — GROUNDED, and the mechanism is worse than described.**
`lambda$run$1(Semaphore, ChunkCoordinate, Void, Throwable)` releases the semaphore and calls
`update(x, z, true)` **without ever reading its `Throwable` parameter** — a failed chunk still
increments progress. And `GenerationCompleteEvent` is fired on the same exit path used by
`stop(true)` (cancellation). Both hazards are real.

*New, from the same source:* `chunky.maxWorkingCount` is a JVM system property. Pinning it in the
benchmark instance's JVM args removes one source of run-to-run variance in the pregeneration phase.
Neither document mentions it.

### §Frame and GPU recorder

**P47. Per-frame CPU interval; p50/p95/p99, 1% low, 0.1% low, jank counts. — GROUNDED**
(research §2), subject to the naming correction in P8.

**P48. "GPU elapsed time using non-blocking OpenGL timestamp-query pairs when supported." —
WRONG by omission; the design reaches past a first-party API it does not know exists.**

Minecraft 26.1.2 does not hand mods a bare GL context; rendering goes through the blaze3d GPU
abstraction. All verified in the patched client jar:

- `RenderSystem.getDevice()` / `tryGetDevice()` return `com.mojang.blaze3d.systems.GpuDevice`;
- `GpuDevice.createCommandEncoder()` returns `CommandEncoder`, which exposes
  **`GpuQuery timerQueryBegin()`** and **`void timerQueryEnd(GpuQuery)`**;
- `com.mojang.blaze3d.systems.GpuQuery` is an `AutoCloseable` interface with
  **`OptionalLong getValue()`** — i.e. a non-blocking read that reports unavailability rather than
  fabricating a zero, which is precisely the property the plan asks for;
- `com.mojang.blaze3d.systems.TimerQuery` is a ready-made singleton
  (`getInstance()`, `isRecording()`, `beginProfile()`, `endProfile(): FrameProfile`) whose
  `FrameProfile` exposes `cancel()`, `isDone()`, and `get()`;
- `Minecraft.runTick` already uses it: `TimerQuery.getInstance().beginProfile()` early in the frame,
  `endProfile()` after the blit, and reads `currentFrameProfile.isDone()` / `.get()` on a **later**
  frame to feed the debug overlay — the exact deferred-read discipline the plan proposes to build;
- the only shipped backend is OpenGL (`com/mojang/blaze3d/opengl/`, 52 entries; there is no
  `blaze3d/vulkan` package), and `GlCommandEncoder.timerQueryBegin` calls
  `GL32C.glGenQueries()` then `GL32C.glBeginQuery(35007 /* GL_TIME_ELAPSED */)`, guarded by
  `IllegalStateException("A GL_TIME_ELAPSED query is already active")`.

Two things follow. The research doc's advice to prefer `GL_TIMESTAMP` pairs over `GL_TIME_ELAPSED`
is *vindicated* — vanilla itself occupies the single `GL_TIME_ELAPSED` slot whenever the GPU debug
entry is enabled. But the plan and handoff assert raw LWJGL as if it were the only option, when a
supported, backend-agnostic API exists. See *Decisions needed from Mia* #3.

**P49. "Never synchronously wait on the current frame's query, because that would serialize CPU and
GPU work." — GROUNDED** (research §2, Khronos `ARB_timer_query`), and corroborated by vanilla's own
`isDone()`-polling pattern in `runTick`.

**P50. "GPU timestamp duration does not include all compositor/display latency, so keep it distinct
from presentation time." — GROUNDED** (research §2).

**P51. "client and integrated-server tick time, heap/GC, process private bytes, and peak working
set." — SPLIT.**

- Integrated-server tick time: **GROUNDED**, with a first-party source neither document names.
  `MinecraftServer` exposes public `getAverageTickTimeNanos()`, `getTickTimesNanos()`,
  `getCurrentSmoothedTickTime()`, and `getTickTime(ResourceKey<Level>)`; the client reaches the
  server via `Minecraft.getSingleplayerServer()` / `isLocalServer()`.
- Heap/GC: **GROUNDED** via `java.lang.management`, and Spark's public API additionally exposes GC
  statistics (research §1).
- **Process private bytes and peak working set: UNVERIFIABLE.** These are Windows PSAPI counters.
  No JDK API exposes them; obtaining them requires JNA/OSHI or an external tool. Neither document
  names a mechanism. *What would settle it:* name the mechanism and say which process owns the
  sampling (see *Decisions needed from Mia* #7).

**P52. "Start with whole-frame GPU timing. Per-render-pass queries are a later diagnostic feature." —
GROUNDED** (research §2), modulo P8's naming.

**P53. "Require the shared Mixin to apply exactly once … and emit balanced start/end samples." —
GROUNDED and mechanically feasible.** `GameRenderer.render(DeltaTracker, boolean)` disassembles to a
single `return` opcode (offset 445), so `@At("RETURN")` yields exactly one injection point and
balanced pairs. *One caveat neither document states:* a `RETURN` injection does not fire when an
exception escapes the method, so the recorder needs an explicit reconciliation for the unbalanced
case rather than assuming pairing.

**P54. "The full-frame CPU and GPU brackets must be identical on NeoForge and Fabric." — WRONG for
0.x scope** (see P3), correct as a future invariant.

### §Desktop runner

**P55. `pnpm bench plan / run / optimize / report / recover`. — WRONG.** Confirmed abandoned. The
map fixes the runner as a single entrypoint with no CLI, no TUI, and no subcommands: it reads its
config and proceeds.

**P56. Runner calls `lay list --json` and `lay list --resolved --json`, reads the current Layer's
`exclusions`, restricts candidates to current-Layer content, builds dependency-safe bundles. —
GROUNDED** ([`inlay-layer-scope-and-dependency-graph.md`](./inlay-layer-scope-and-dependency-graph.md)).

**P57. "launches the Modrinth instance without a singleplayer-world target." — GROUNDED**
(research §4).

**P58. "waits on the Minecraft PID recorded by the harness, not an arbitrary timer." — GROUNDED**
(research §2 §Windows PresentMon: the harness writes its own PID into its ready marker).

**P59. "Do not write directly to Modrinth's database." — GROUNDED** (research §4; the launcher's
`app.db` is present in `%APPDATA%/ModrinthApp/`, and the instance model stores `id`/`path`/`name`
separately).

**P60. "refuses new work until an interrupted transaction is recovered." — WRONG as stated.** With
the `recover` command abandoned and the runner unable to prompt, the map requires recovery to happen
**automatically on next start**. "Refuses new work" describes a state that, under the map, nothing
can clear. Restate as: detect the journal on start, restore, then proceed — and fail the run only if
restoration itself fails.

**P61. "Prefer a dedicated Modrinth benchmark instance cloned from this Layer … Spark, Chunky, the
harness mod, and their required dependency closure are protected instrumentation." — GROUNDED**
(inlay research §Effect on the experiment strategy).

### §Layer scope and §Dependency-safe mod stacks

**P62. All eight Layer-scope rules (current Layer is `lineage.at(-1)`; ownership by
`name@versionId`; overrides and exclusions are candidates whose "off" arm is the inherited artifact;
overrides/exclusions invisible in the resolved view; no-manifest degenerates to root Layer;
auto-promotion of inherited dependencies to `always-on`; record lineage and manifest hash; never
pool across lineage revisions). — GROUNDED**, one-for-one with
[`inlay-layer-scope-and-dependency-graph.md`](./inlay-layer-scope-and-dependency-graph.md).

**P63. The three-source dependency graph (jar probe > Modrinth API > `candidates.jsonc` overrides),
with `fabric.mod.json`, `META-INF/neoforge.mods.toml`, `META-INF/jarjar/metadata.json`, SHA-512
caching, fail-closed on unrecognized descriptor shapes, environment-satisfied loader IDs, Fabric API
fan-out, Sinytra/FFAPI adapted edges, and per-edge provenance. — GROUNDED** (same document).

*Corroboration from a local artifact:* `Chunky-NeoForge-1.5.4.jar`'s `META-INF/neoforge.mods.toml`
uses the `type="required"` form (`[[dependencies.chunky]] modId="neoforge" type="required"
versionRange="[26.1.0.0-beta,)" ordering="NONE" side="BOTH"`), not the older `mandatory` boolean —
which is exactly why the inlay research says to pin the parse to the target and fail closed.

**P64. "Never infer benchmark candidates from filenames, Modrinth categories, or the Layer boundary
alone." — GROUNDED** (same document, §Classification still wins).

**P65. "An unresolvable required edge is a hard planning failure … Laymark must never schedule a
stack that will not boot." — GROUNDED** (same document).

### §Experiment strategy

**P66. Paired, randomized, repeated comparisons; loader/instrumentation/lineage tuple as stratum; no
pooling across loaders or ancestor-Layer changes. — GROUNDED** (research §7, loader-portability
§Test and result rules, inlay research §5).

**P67. Greedy forward selection against the current incumbent, then pair rescue / beam, then
leave-one-out backward ablation, then a final full-pack comparison. — GROUNDED** (research §7
§Combination search; inlay research amends the baseline definition, which the plan reflects).

**P68. "Screen all candidates with two short paired repetitions. Confirm the best few with at least
five to seven paired repetitions." — UNVERIFIABLE.** These numbers appear in no cited source and no
power analysis backs them. *What would settle it:* measure the control noise budget on this machine,
then derive n from the smallest effect worth detecting.

**P69. "A candidate showing no marginal gain against a rich inherited baseline is a legitimate and
expected result." — GROUNDED** (inlay research §5).

### §Result layout

**P70. Directory layout and the per-run metadata list (Inlay manifest and materialization
fingerprint, resolved lineage, serialized dependency graph, mod hashes, Git commit, run order, case
config, seed, hardware/driver/OS/Java, launcher settings, process priority, timestamps, warmup,
profiler state, failure reason). — GROUNDED** (research §7 §Scenario suite; inlay research
§Provenance).

**P71. "Raw samples are authoritative. Summaries and reports are rebuildable outputs." — GROUNDED**
(research §7).

**P72. `samples/*.jsonl.zst` as the sample format. — UNVERIFIABLE**, and the handoff correctly flags
it as proposed rather than fixed.

### §Implementation slices

**P73. The eight-slice list. — WRONG.** Confirmed fabricated by the ticket and the map. Slice 1
additionally names Fabric artifacts and Modrinth launches "per loader", which is out of 0.x scope
regardless of the fabrication.

**P74. "Do not begin automated mod ranking until slices 1–4 can repeat an unchanged control stack
without producing false 'improvements' outside the configured noise budget." — GROUNDED as
policy**, and worth salvaging verbatim even though the slice numbering it references is void. It is
the single most valuable sentence in either document's implementation section.

## Verdicts: `benchmark-harness-handoff.md`

### §Header and §Product goal

**H1. "Both NeoForge and Fabric are first-class targets; neither is the architectural default." —
WRONG.** The map fixes NeoForge-only scope for 0.x, with Fabric as a stub and an honest adapter
seam.

**H2. "No harness code has been written yet." — GROUNDED.** The repository contains only `docs/`.

**H3. "Create a new repository for it; do not put Java or runner source under the Lucent pack." —
GROUNDED and now done** (`lucent-mc/laymark`).

**H4. "The public Modrinth catalog had no exact display-name or slug collision on 2026-08-16;
availability is not reserved until the project is actually created." — WRONG (stale).** Per the map,
[Reserve the Laymark Modrinth slug](https://github.com/lucent-mc/laymark/issues/2) is closed: slug
`laymark`, project ID `YxLVBTmi`, currently draft/private. Identity decisions may now assume it.

### §Bootstrap compatibility matrix

Every cell was checked against a live upstream source. **All GROUNDED**, with one naming correction.

**H5. Minecraft / Java = 26.1.2 / 25. — GROUNDED.** Mojang manifest: `26.1.2`, `type=release`,
released 2026-04-09, `javaVersion.majorVersion = 25`.

**H6. NeoForge 26.1.2.95. — GROUNDED, and it is the newest 26.1.2 build.** The NeoForge Maven
versions API lists `26.1.2.0-beta` … `26.1.2.95`, with `.95` last. The jar is installed locally.

**H7. Fabric Loader 0.19.3. — GROUNDED.** `meta.fabricmc.net/v2/versions/loader/26.1.2` returns
`0.19.3` with `"stable": true` as the first entry.

**H8. Fabric API 0.155.2+26.1.2. — GROUNDED.** The Fabric Maven directory returns HTTP 200.
Corroborated locally by `forgified-fabric-api-0.155.2+26.1.2+3.5.0.jar` in the pack.

**H9. Spark 1.10.173 / 1.10.173-fabric. — GROUNDED with a naming correction.** The Modrinth API
returns, for game version 26.1.2, exactly `1.10.173-neoforge`, `1.10.173-fabric`, and
`1.10.173-forge`. The matrix's asymmetric labelling ("1.10.173" for NeoForge, "1.10.173-fabric" for
Fabric) is imprecise: the NeoForge cell should read `1.10.173-neoforge`. The installed file is
`spark-1.10.173-neoforge.jar`.

**H10. Chunky 1.5.4 (NeoForge) / 1.5.3 (Fabric), and "Chunky 1.5.4 is not currently published for
Fabric 26.1.2". — GROUNDED, exactly as claimed.** Modrinth returns `1.5.4` for `neoforge` and
`forge` only; the newest Fabric build is `1.5.3`.

**H11. Sodium 0.9.2-alpha.4 and Sodium Extra 0.9.3. — GROUNDED.** Installed as
`sodium-neoforge-0.9.2-alpha.4+mc26.1.2.jar` and `sodium-extra-neoforge-0.9.3+mc26.1.2.jar`.
(The pack also carries `reeses-sodium-options-neoforge-2.2.3+mc26.1.2.jar`, which the matrix omits
and which is a third Sodium-adjacent config surface.)

**H12. Modrinth App 0.17.10. — GROUNDED.** `modrinth/code` releases: `v0.17.10`, published
2026-08-14, is the newest.

**H13. "Minecraft 26.1.2 … is not Laymark's permanent version ceiling. Pin its exact tuple in the
first vertical slice." — GROUNDED**, and worth stating more sharply than the doc does: **26.1.2 is
no longer the current Minecraft release.** The Mojang manifest reports `latest.release = 26.2`
(snapshot `26.3-snapshot-8`), NeoForge has shipped through `26.2.0.59`, and this machine already has
the 26.2 runtime installed. The pin is a deliberate choice tracking the Lucent pack line, not a
statement about currency. See *Decisions needed from Mia* #9.

### §Repository boundary and shape

**H14. Directory tree containing `runner/  # TypeScript CLI` and
`protocol/  # versioned JSON schemas and generated Java/TS types`. — WRONG** (same as P11).

**H15. LuckPerms-shaped module split (`core`, `minecraft-common`, thin loader projects), copying the
dependency direction but not the loader/inner-jar classloader packaging. — GROUNDED**
([`luckperms-build-architecture-research.md`](./luckperms-build-architecture-research.md);
loader-portability research §Code ownership).

**H16. Ownership bullets for runner / `core` / `minecraft-common` / loader projects. — GROUNDED**
(loader-portability research §Code ownership), except that the runner bullet inherits the abandoned
language split.

**H17. The three seam interfaces (`LoaderPort`, `HarnessLifecycle`, `FrameBoundary`). —
UNVERIFIABLE (illustrative), with one concrete gap.** `LoaderPort.runClientCommand(String)` is typed
to return a `CommandResult`, but NeoForge gives only
`public static boolean ClientCommandHandler.runCommand(String)` (verified in the neoforge universal
jar). A typed success/failure result therefore has to be *synthesised* from the boolean plus
observed side effects (chat output, log lines, the `.sparkprofile` appearing). Neither document says
where the richer result comes from. *What would settle it:* specify the observation the adapter uses.

**H18. "Drive `FrameBoundary` from one shared, version-pinned client Mixin … at `HEAD`/`RETURN` of
vanilla `GameRenderer.render(DeltaTracker, boolean)`". — GROUNDED on signature and on the
single-`RETURN` property (P6, P53); WRONG on calling that interval "whole-frame" (P8); out of 0.x
scope as a *cross-loader* requirement (P3, P7).**

**H19. "Fabric's `LevelRenderEvents.START_MAIN/END_MAIN` covers only part of the world pass; it is
not equivalent to NeoForge's whole-frame `RenderFrameEvent.Pre/Post`." — GROUNDED**
(loader-portability research), and the NeoForge half is now independently confirmed against the
patched client jar (P7).

**H20. Artifact names `laymark-neoforge-mc26.1.2-<version>.jar` and
`laymark-fabric-mc26.1.2-<version>.jar`. — Half GROUNDED, half out of scope.** The NeoForge name
matches the map's decision from
[Choose the Java group ID, root package, and artifact naming](https://github.com/lucent-mc/laymark/issues/8).
The Fabric name is out of 0.x scope. The handoff also never mentions the third decided artifact,
`laymark-runner-<version>.jar`, nor the mod jar embedding the runner as an inert resource.

**H21. "Do not make the mod import Inlay internals … invoke `lay list --json` / `lay list --resolved
--json` … may read `exclusions` from `inlay.index.json`, a schema-validated public field … must also
work with no Inlay manifest." — GROUNDED** (inlay research). `inlay.index.json` is present in the
Lucent profile.

**H22. Version-addition procedure (descriptor under `build-logic`, recompile `minecraft-common`,
behavioural contracts, narrow overlay only on proven incompatibility, both artifacts, contract +
integration + hook self-check + in-game smoke, only then publish metadata). — GROUNDED**
(loader-portability research §Code ownership, final paragraph), modulo "both artifacts".

### §Decisions already made

**H23. Decisions 1–9 and 11–14. — GROUNDED**, each traceable: #1 and #5 to research §1/§6, #2 to
research §4, #3 to the verified `createFreshLevel` signature, #4 to research §5/§6, #6–#7 to
research §3/§6, #8 to research §7, #9 to research §7 §Combination search, #11 to loader-portability
§Test and result rules, #12 to the same, #13–#14 to the inlay research.

**H24. Decision 10, "Publish separate NeoForge and Fabric artifacts from one source repository. A
feature is incomplete until its shared behavior passes on both loader ports." — WRONG for 0.x**
(P3). Under the map, a feature is complete when it passes on NeoForge and the Fabric seam remains
honest.

### §Minecraft settings: no general mixin is required

**H25. "Neither loader needs to provide a special 'edit all game options' facility: the public
vanilla client surface is sufficient." — GROUNDED** (research §5; loader-portability §Settings and
world control), and confirmed by direct inspection of `Options` (H27).

**H26. "`OptionInstance.set(...)` validates, stores, and invokes the option's vanilla update callback
while the client is running." — GROUNDED but dangerously incomplete.** The verified bytecode adds a
second guard the sentence omits: the callback also requires the resolved value to **differ** from
the current one (`Objects.equals` check). See P26(b) — this is the single most consequential
correction in this audit.

**H27. The representative code block. — GROUNDED, every call verified** against
`net.minecraft.client.Options` in the patched client jar:

| Handoff call | Verified declaration |
| --- | --- |
| `minecraft.execute(...)` | `public void execute(Runnable)` (inherited from `BlockableEventLoop`) |
| `options.graphicsPreset().set(GraphicsPreset.FANCY)` | `public OptionInstance<GraphicsPreset> graphicsPreset()`; `GraphicsPreset.FANCY` exists |
| `options.renderDistance().set(16)` | `public OptionInstance<Integer> renderDistance()` |
| `options.simulationDistance().set(12)` | `public OptionInstance<Integer> simulationDistance()` |
| `options.entityDistanceScaling().set(1.0D)` | `public OptionInstance<Double> entityDistanceScaling()` |
| `options.enableVsync().set(false)` | `public OptionInstance<Boolean> enableVsync()` |
| `options.framerateLimit().set(260)` | `public OptionInstance<Integer> framerateLimit()`, `IntRange(10, 260)` |
| `options.fov().set(70)` | `public OptionInstance<Integer> fov()` |

**H28. "Apply the bundle first; explicit overrides intentionally make it CUSTOM." — GROUNDED, and
the cascade is larger than the research doc lists.** The `graphicsPreset` `OptionInstance` is
constructed with an `onValueUpdate` consumer that resolves, via `LambdaMetafactory` bootstrap
method 13, to `REF_invokeVirtual Options.applyGraphicsPreset:(GraphicsPreset)V`. That method sets
`isApplyingGraphicsPreset = true`, calls `GraphicsPreset.apply(Minecraft)`, and clears the flag.
`GraphicsPreset.apply` writes **seventeen** options:

```text
ambientOcclusion, biomeBlendRadius, cloudRange, cloudStatus, cutoutLeaves,
entityDistanceScaling, entityShadows, improvedTransparency, maxAnisotropyBit,
menuBackgroundBlurriness, mipmapLevels, particles, prioritizeChunkUpdates,
renderDistance, simulationDistance, textureFiltering, weatherRadius
```

This confirms the ordering requirement (the preset overwrites render and simulation distance, so it
must precede explicit overrides) and confirms that **applying any preset implies a texture reload**,
because it touches `mipmapLevels`, `maxAnisotropyBit`, and `textureFiltering`. The "make it CUSTOM"
behaviour is `Options.setGraphicsPresetToCustom()`, called from the individual option callbacks and
short-circuited by the `isApplyingGraphicsPreset` guard.

**H29. "Public accessors also exist for particles, clouds/range, biome blend, mipmaps, texture
filtering, GUI scale, fullscreen, entity shadows, ambient occlusion, chunk-update priority, and the
other 26.1 graphics controls." — GROUNDED.** All verified public on `Options`: `particles()`,
`cloudStatus()`, `cloudRange()`, `biomeBlendRadius()`, `mipmapLevels()`, `maxAnisotropyBit()`,
`maxAnisotropyValue()`, `textureFiltering()`, `guiScale()`, `fullscreen()`, `exclusiveFullscreen()`,
`fovEffectScale()`, plus `entityShadows`, `ambientOcclusion`, and `prioritizeChunkUpdates`
(all three referenced by `GraphicsPreset.apply`).

**H30. "Window size is public through `Window.setWindowed(width, height)`; fullscreen/video modes
have public window methods." — GROUNDED.** `com.mojang.blaze3d.platform.Window` exposes public
`setWindowed(int, int)`, `isFullscreen()`, `getWidth()`, `getHeight()`, `getScreenWidth()`,
`getScreenHeight()`, `getGuiScaledWidth()`, `getGuiScaledHeight()`, `getGuiScale()`,
`getRefreshRate()`, `updateVsync(boolean)`, `getPreferredFullscreenVideoMode()`,
`setPreferredFullscreenVideoMode(Optional<VideoMode>)`, and `changeFullscreenVideoMode()`. There is
still **no** public VSync getter, exactly as the research doc says.

### §Settings application-barrier taxonomy

**H31. The six classes themselves (`LIVE`, `RENDERER_REBUILD`, `WINDOW_RESIZE`,
`TEXTURE_OR_RESOURCE_RELOAD`, `SERVER_CONVERGENCE`, `PROCESS_RESTART`). — GROUNDED as categories.**
Each corresponds to a real mechanism in research §5's setting/side-effect matrix, and each named
member checks out:

- `RENDERER_REBUILD` — `LevelRenderer.allChanged()` and `LevelRenderer.needsUpdate()` are both
  public in the patched client jar; biome blend and cloud range map to them.
- `WINDOW_RESIZE` — `Minecraft.resizeGui()` is public; GUI scale, fullscreen, and windowed
  resolution all route through resize.
- `TEXTURE_OR_RESOURCE_RELOAD` — `Minecraft.updateMaxMipLevel(int)` and
  `Minecraft.delayTextureReload(): CompletableFuture<Void>` are both public, and
  `VideoSettingsScreen.removed()` calls exactly that pair in exactly that order (verified in
  bytecode). `Minecraft.reloadResourcePacks(): CompletableFuture<Void>` covers packs.
- `SERVER_CONVERGENCE` — `PlayerList.getViewDistance()` / `getSimulationDistance()` (and their
  setters) are public.
- `PROCESS_RESTART` — `Options.exclusiveFullscreen()` exists and is restart-required per research
  §5; Sodium's GL-context option is startup-only per research §5.

**H32. Presenting the taxonomy as a partition — one class per setting. — WRONG.** Three settings
break it:

- **Render distance** is placed under `RENDERER_REBUILD` only, but it is simultaneously a
  `SERVER_CONVERGENCE` setting: research §5 records that the integrated server polls it each server
  tick and pushes it into `PlayerList`, and the effective value is
  `Options.getEffectiveRenderDistance()` *and* `PlayerList.getViewDistance()`. A run that waits only
  for renderer convergence can begin measuring while the server is still delivering the old
  distance.
- **The graphics preset** has no place in the taxonomy at all, yet one `set` call triggers renderer
  rebuild *and* texture reload *and* server convergence, because its cascade writes
  `mipmapLevels`/`maxAnisotropyBit`/`textureFiltering` *and* `renderDistance`/`simulationDistance`
  (H28).
- **VSync and FPS limit** are unclassified, implicitly `LIVE`, but VSync's callback calls
  `Window.updateVsync` which is render-thread-affine (research §5), and the FPS limit routes through
  `FramerateLimitTracker` whose `getThrottleReason()` can override the requested value.

The fix is small: make the barrier a **set** per setting rather than a label, and require every
member of the set to be satisfied before the setting counts as applied.

**H33. "Every adapter returns both the requested/stored value and effective value. Examples:
`Options.getEffectiveRenderDistance()`, integrated-server `PlayerList` distances,
`Window.isFullscreen()`, logical and framebuffer sizes, selected resource-pack IDs, and reload
completion. Abort on mismatch." — GROUNDED, every example verified.**
`Options.getEffectiveRenderDistance()` is public (as is `setServerRenderDistance(int)`);
`PlayerList.getViewDistance()` / `getSimulationDistance()` are public; `Window.isFullscreen()`,
`getScreenWidth/Height()`, and `getWidth/Height()` are public;
`PackRepository.getSelectedIds(): Collection<String>` and `setSelected(Collection<String>)` are
public; reload completion is the `CompletableFuture<Void>` from `delayTextureReload()` /
`reloadResourcePacks()`.

**H34. "`Options.save()` persists to `options.txt` and broadcasts client options; it is not an
application barrier." — GROUNDED.** `Options.save()` and `Options.broadcastOptions()` are both
public; research §5 confirms the semantics. `Options.overrideWidth` / `overrideHeight` are public
fields and are startup overrides, as the research matrix says.

**H35. Sodium / Sodium Extra guidance (prefer vanilla `Options`; treat Sodium's own config as a
version-pinned pre-launch JSON adapter; AT/access-widener before mixin; keep adapters optional). —
GROUNDED** (research §5 §Sodium 0.9.2-alpha.4 and Sodium Extra 0.9.3; loader-portability §Settings
and world control).

### §Exact in-game order of operations

**H36. "Implement this as an explicit persisted state machine, not chained sleeps." — GROUNDED**
as policy and consistent with research §6.

**H37. The overall 17-state ordering. — GROUNDED.** States 1–14, 16 and 17 map cleanly onto research
§6's eight steps with finer granularity, and the load-bearing orderings are all preserved: settings
before world creation; world creation before join; join before any capture; cold capture before
Chunky; Chunky before residency; residency before the warm capture; close before restore. Four
specific defects follow.

**H38. State 5 (`AWAIT_PRESET`) — "read back effective values. Fail before world creation on
mismatch." — WRONG for `SERVER_CONVERGENCE` settings.** No integrated server exists before
`CREATE_WORLD`, so `PlayerList` distances cannot be read, let alone matched. State 7 (`AWAIT_JOIN`)
correctly says "wait for server view/simulation distances to converge", which means the document
contains both the bug and its fix and never reconciles them. Restate state 5's invariant as
"fail before *measurement*", and label which readbacks are deferred to state 7.

**H39. State 6 (`CREATE_WORLD`) — "call `Minecraft.createWorldOpenFlows().createFreshLevel(...)`
with explicit `LevelSettings`, `WorldOptions(seed, structures, bonusChest)`, data configuration,
game rules, and dimension factory (normal preset initially)." — MOSTLY GROUNDED, WRONG on game
rules.**

GROUNDED: `Minecraft.createWorldOpenFlows()` is public and returns `WorldOpenFlows`;
`createFreshLevel(String, LevelSettings, WorldOptions, Function<HolderLookup.Provider,
WorldDimensions>, Screen)` is public; `WorldOptions(long seed, boolean generateStructures, boolean
generateBonusChest)` is exactly right; data configuration is the `WorldDataConfiguration` component
of `LevelSettings`; and the dimension factory matches
`WorldPresets.createNormalWorldDimensions(HolderLookup.Provider)`, which is public and has exactly
the required shape.

WRONG: **game rules are not a parameter of `createFreshLevel` and not a component of
`LevelSettings`** (see P27). They must be applied after the integrated server exists — which state 8
(`STAGE_COLD`) already claims to do, making the document self-contradictory. Note also that
`createFreshLevel` returns `void` and takes a trailing `Screen`, so the harness needs a completion
signal from the join gate rather than from the call itself.

**H40. State 15 (`OPTIONAL_STREAMING_PHASE`) — "to measure generated-but-not-resident streaming,
disconnect/reopen the same save **before making it resident**." — WRONG ordering.** Its own text
places it between state 12 (`PREGENERATE`) and state 13 (`MAKE_RESIDENT`), but it is numbered after
state 14 (`STOP_WARM_CAPTURE`). As numbered, the state machine is not executable. The plan states
the same idea with a *different* placement (P33), so the two documents also disagree with each
other.

**H41. The state list is complete. — WRONG: nothing re-applies the preset per case.** State 17 (`NEXT_CASE_OR_RESTORE`)
loops back to create a new save for another repetition, but no state re-enters `APPLY_PRESET`.
Combined with the `OptionInstance.set` no-op-on-equal behaviour (P26b), a multi-case launch will
silently carry the previous case's explicit overrides into the next case. **This is a
false-result-producing bug, not a cosmetic gap.**

**H42. State 12 (`PREGENERATE`) — "Do not trust `GenerationCompleteEvent` alone in Chunky 1.5.4;
require `GenerationProgressEvent.complete()`, a server save, outstanding-work drain/quiescence, and
no task failure." — GROUNDED**, and independently re-verified against the shipped 1.5.4 bytecode
(P44–P46). This is the best-sourced paragraph in either document.

**H43. State 16 (`CLOSE_WORLD`) — "await integrated-server stop and release of the save lock." —
GROUNDED.** `LevelStorageAccess` implements `AutoCloseable` with public `close()` and `safeClose()`;
research §6 step 8 names the lock explicitly.

**H44. "A profiler-free scoring pass and a Spark-sampled diagnostic pass cannot share an untouched
cold region." — GROUNDED** (research §3 §What Chunky does not make deterministic).

### §Spark adapter (handoff)

**H45. Same command string as the plan. — GROUNDED** (P36).

**H46. "Spark's public API exposes statistics but not sampler lifecycle." — GROUNDED**
(research §1 §API limitation).

**H47. "Only one sampler may be active. Wait for completion and a stable new `.sparkprofile` before
starting another." — GROUNDED** (research §1 §Sequential profiles in one launch).

**H48. "Immediately map Spark's timestamp filename to the deterministic case/phase ID." —
GROUNDED**, and necessary: the file name is `profile-YYYY-MM-DD_HH.mm.ss.sparkprofile` (research
§1), and `--comment` does not affect it.

### §Measurement channels

**H49. "successive shared whole-frame start timestamps for application frame pacing." — GROUNDED as
a cadence source, WRONG in name.** `GameRenderer.render` is invoked exactly once per
`Minecraft.runTick`, so successive HEAD timestamps *are* a valid whole-frame cadence. The word
"whole-frame" is only wrong when applied to the start→end *duration* (P8).

**H50. "shared `GameRenderer.render` start→end CPU render duration." — GROUNDED as defined**, and
this is the correct name for the interval; the error is elsewhere in the docs where the same
interval is called whole-frame.

**H51. "p50/p95/p99, 1%/0.1% tails, deadline misses, and raw per-frame samples." — GROUNDED**
(research §2, §7).

**H52. "nonblocking OpenGL `GL_TIMESTAMP` query pairs in a ring, read several frames later." —
WRONG by omission** (P48): a first-party `GpuQuery` / `CommandEncoder.timerQueryBegin` /
`TimerQuery` API exists in 26.1.2 and is what vanilla itself uses.

**H53. "integrated-server MSPT/ticks, client ticks, heap/GC, process private bytes, and peak working
set." — SPLIT** (P51): server ticks GROUNDED via `MinecraftServer.getAverageTickTimeNanos()` /
`getTickTimesNanos()` / `getTickTime(ResourceKey<Level>)`; heap/GC GROUNDED; **private bytes and
peak working set UNVERIFIABLE** for lack of any named mechanism.

**H54. "Chunky progress and total preparation/readiness time." — GROUNDED** (P45; the progress event
carries `chunks`, `progress`, `rate`, `hours/minutes/seconds` ETA, and `x`/`z`).

**H55. "Spark `.sparkprofile` files" and "optional PresentMon CSV targeted by the exact Minecraft
PID." — GROUNDED** (research §1, §2).

**H56. "Never synchronously wait for the current GPU query or call `glFinish`." — GROUNDED**
(research §2; corroborated by vanilla's deferred `FrameProfile.isDone()` read).

**H57. "GPU duration, application frame time, and presentation/display time are different metrics
and must remain separate." — GROUNDED** (research §2 §Windows PresentMon, final paragraph).

### §Run-plan concepts and §A/B and combination strategy

**H58. Four concepts (Preset / World / Scenario / Suite) with fully expanded resolved plans and
strict rejection rules. — GROUNDED** as design; the settings enumerated all exist (H29).

**H59. All eight A/B strategy bullets. — GROUNDED** (inlay research §Effect on the experiment
strategy; research §7; loader-portability §Test and result rules), except that "Report NeoForge and
Fabric decisions separately" is moot in 0.x.

### §First implementation slice

**H60. The six-step slice. — WRONG.** Confirmed fabricated. Step 1 additionally names "CI for
Java 25 plus the TypeScript runner", step 2 names "Java/TS contract tests", and step 3 names a
Fabric instance launch — all three are dead independent of the fabrication.

**H61. The twelve acceptance criteria. — WRONG as a set (fabricated), but eight are individually
sound and worth salvaging:** ten unchanged-control runs complete without manual input; `core` has no
Minecraft/loader dependency; the frame hook applies exactly once and emits balanced start/end
samples (feasible per P53); state-machine tests use an in-memory adapter; every save ID is unique
and the target is verified untouched; no world is created until preset readback succeeds; no
measurement starts until readiness succeeds; and repeating the control produces no false
improvements outside a documented noise budget. The remaining four are loader-parity criteria that
0.x does not need. Note that "no world is created until preset readback succeeds" must be weakened
by H38.

### §Known traps

**H62. "World creation always generates spawn; 'cold' applies to an untouched far-away target." —
GROUNDED** (research §6 step 4).

**H63. "Chunky completion is not client mesh residency." — GROUNDED** (research §3, final
paragraph).

**H64. "Chunky 1.5.4's generic completion event can precede final async work and can also occur for
cancellation." — GROUNDED**, verified in the shipped bytecode (P44, P46).

**H65. "Resource reload completion, resize completion, and server-distance convergence require
explicit barriers." — GROUNDED** (research §5 matrix), subject to H32's correction that they are not
mutually exclusive.

**H66. "Multiple scenarios in one JVM share JIT, caches, GC history, and thermal state;
process-level repetitions are still required." — GROUNDED** (research §7 §Scenario suite).

**H67. "OS page cache cannot be portably cleared from the mod." — GROUNDED by absence** (P16).

**H68. "Some mods help startup, memory, networking, or background behavior and need specialized
scenarios." — GROUNDED** as a design principle (research §7 implies it; the plan's mod-specific
mapping is the UNVERIFIABLE part, P17).

**H69. "Do not mutate `inlay.index.json` while testing. Use journaled materialization changes and
guaranteed restoration in a dedicated benchmark instance." — GROUNDED** (inlay research).

### §Questions deliberately left for implementation

**H70. "Final Java package/group ID; the project and mod ID is `laymark`." — WRONG (stale).**
Decided by [issue #8](https://github.com/lucent-mc/laymark/issues/8): Gradle, group `cx.mia.lucent`,
root package `cx.mia.lucent.laymark`, artifacts `laymark-<module>`, mod ID `laymark`.

**H71. "Whether the first runner launches through Modrinth's instance-only deep link or another
supported launcher entry point." — GROUNDED as still open.** Research §4 explicitly warns that the
deep link, while source-backed in Modrinth App 0.17.10, "does not appear in the public Help Center
as a stable third-party automation contract".

**H72. "Exact client chunk/mesh readiness signal." — UNVERIFIABLE and genuinely open.** *What would
settle it:* choose an observable (section-ready counters on `LevelRenderer`, or a frame-time
stability window) and validate it against a control run where residency is known.

**H73. "Result serialization format for high-volume samples (`jsonl.zst` is proposed, not fixed)." —
UNVERIFIABLE**, correctly flagged as open.

**H74. "Whether automatic deletion of disposable worlds happens in-game after lock release or
externally after process exit. External deletion is safer for slice 1." — GROUNDED as open;
both routes exist.** `LevelStorageAccess.deleteLevel()` is public, and research §6 step 8 makes the
same recommendation.

**H75. "How shaders are adapted; shader mods own their configs and must be optional version-pinned
process adapters." — GROUNDED as open** (research §5's reasoning for Sodium generalises). No shader
mod is currently installed in the Lucent profile (`shaderpacks/` exists but the mod list contains
none), so this is untested ground.

## Corrections the primary sources supply that neither document knows

These are not verdicts on existing claims; they are facts found while auditing that materially
change the design and appear in neither document.

1. **`Options.applyGraphicsPreset(GraphicsPreset)` is public and unconditional.** It is the correct
   way to force the preset cascade regardless of the current value, and it is what the
   `graphicsPreset()` option callback itself invokes.
2. **`OptionInstance.set` skips the callback when the value is unchanged, and stores without any
   callback when `Minecraft.isRunning()` is false.** Every multi-case and every restore path has to
   account for both.
3. **Minecraft 26.1.2 ships a non-blocking GPU timer-query API**:
   `RenderSystem.getDevice().createCommandEncoder().timerQueryBegin()` / `timerQueryEnd(GpuQuery)`,
   with `GpuQuery.getValue(): OptionalLong`, plus the `TimerQuery` singleton wrapper that vanilla
   uses for the debug overlay. The OpenGL backend implements it with `GL_TIME_ELAPSED`, and there is
   no Vulkan backend in this version.
4. **Integrated-server MSPT has a first-party source**: `MinecraftServer.getAverageTickTimeNanos()`,
   `getTickTimesNanos()`, `getCurrentSmoothedTickTime()`, and per-dimension
   `getTickTime(ResourceKey<Level>)`, reachable from the client via
   `Minecraft.getSingleplayerServer()`.
5. **`chunky.maxWorkingCount` is a JVM system property** (default 50) controlling Chunky's
   outstanding-async-operation limit. Pinning it removes a source of pregeneration variance.
6. **`GameRenderer.extract(DeltaTracker, boolean)` runs outside the proposed bracket**, as does the
   framebuffer blit and `RenderSystem.flipFrame`. Any claim to measure "the frame" has to say which
   of these it includes.
7. **NeoForge's client-command bridge returns only a `boolean`**
   (`ClientCommandHandler.runCommand(String)`), so a typed `CommandResult` must be synthesised from
   observed side effects.
8. **`GameRenderer.render` has exactly one `return` opcode**, so `@At("RETURN")` is safe — but an
   escaping exception still bypasses it.
9. **26.1.2 is no longer current.** Latest release is 26.2; NeoForge has shipped `26.2.0.59`; both
   are already installed on this machine.

## Bottom line for the map

The two documents are **not** safe to cite wholesale, but they are far from worthless. Concretely:

- **Trustworthy as-is:** the Spark adapter, the Chunky adapter, the settings-mutation surface, the
  effective-value readback list, the three-phase workload taxonomy, the Layer-scope and dependency-
  graph sections, the experiment strategy, the result layout, and the known-traps list.
- **Trustworthy after a mechanical scope edit:** everything that says "both loaders", "Fabric", or
  "TypeScript".
- **Must be rewritten, not edited:** the preset-application step of both state machines (P26, H41),
  the `CREATE_WORLD` game-rules claim (P27, H39), the barrier taxonomy's partition assumption
  (H32), the GPU-measurement channel (P48, H52), the streaming-phase placement (P33, H40), the
  runner interface (P55, P60), and both implementation-slice sections (P73, H60).

Later tickets can assume these documents **for the sections marked GROUNDED above, citing this audit
rather than the documents themselves**, and must re-derive the rest.

## Decisions needed from Mia

1. **Does 0.x measure the frame boundary with a Mixin, or with NeoForge's `RenderFrameEvent.Pre/Post`?**
   *Recommendation: use the event.* It is provably the identical bracket — `ClientHooks.fireRenderFramePre`
   and `fireRenderFramePost` sit immediately either side of `GameRenderer.render` in `Minecraft.runTick`
   — and it costs no Mixin, no version-pinned descriptor, and no fail-closed self-check in a release
   where there is no second loader to keep honest. *Trade-off:* the Fabric port will introduce the
   Mixin later, and its numbers will then have to be validated against the NeoForge event, which is
   exactly the integration test the plan already prescribes. Choosing the Mixin now front-loads that
   work and keeps one code path forever, at the cost of carrying a fragile version-pinned injection
   through all of 0.x for no measurement benefit.

2. **What is the headline frame metric called, and does it include `GameRenderer.extract`?**
   *Recommendation: two channels, neither called "whole frame".* `frameCadence` = successive render
   starts (already a true whole-frame cadence, headline metric); `renderSubmitCpu` = start→end
   duration (diagnostic). *Trade-off:* leaving `extract` unmeasured hides real per-frame CPU work
   that optimization mods plausibly affect — but bracketing it needs a second hook that no NeoForge
   event provides, which would reintroduce the Mixin you avoided in #1.

3. **GPU timing: vanilla `CommandEncoder.timerQueryBegin/End`, or raw LWJGL `GL_TIMESTAMP`?**
   *Recommendation: the vanilla `CommandEncoder` API, and disable the vanilla GPU debug entry during
   runs.* It is supported, backend-agnostic, already returns `OptionalLong` (unavailable vs zero),
   and matches what the game itself does. *Trade-off:* it is `GL_TIME_ELAPSED` underneath, so it
   occupies the single active-query slot and will throw
   `IllegalStateException("A GL_TIME_ELAPSED query is already active")` if vanilla's debug overlay
   or another mod holds it. Raw `GL_TIMESTAMP` sidesteps the slot entirely but bypasses blaze3d,
   would silently break if Mojang adds a non-GL backend, and is invisible to the abstraction that
   Sodium also targets.

4. **How is the preset re-applied for case 2..n in a single launch?**
   *Recommendation: call `Options.applyGraphicsPreset(preset)` unconditionally at the start of every
   case, then re-apply explicit overrides, then read back all seventeen cascaded options plus the
   overrides.* This is the only way to defeat the `Objects.equals` short-circuit in
   `OptionInstance.set`. *Trade-off:* it forces a texture reload on every case (the cascade always
   touches `mipmapLevels`/`maxAnisotropyBit`/`textureFiltering`), lengthening each case by a reload
   barrier. The alternative — one case per process — removes JIT/GC/thermal carryover too, but
   multiplies launch overhead by the number of cases.

5. **Where do game rules get applied, given `createFreshLevel` does not accept them?**
   *Recommendation: after `AWAIT_JOIN`, through the integrated server's `GameRules`, recorded in the
   result; and delete "gamerules" from the `CREATE_WORLD` description in both documents.*
   *Trade-off:* any rule that influences world generation or first-tick behaviour will already have
   taken effect before you set it. If a scenario needs such a rule, it must go through a datapack in
   the `WorldDataConfiguration` component of `LevelSettings` instead — which is a heavier authoring
   mechanism but the only correct one.

6. **Where does the generated-but-not-resident streaming phase sit?**
   *Recommendation: between `PREGENERATE` and `MAKE_RESIDENT`, i.e. a new state 12.5, matching the
   handoff's prose rather than its numbering.* *Trade-off:* it adds a disconnect/reconnect inside
   the measured case and lengthens every case that enables it. Making it a separate case instead
   keeps each case simple but pays for pregeneration twice.

7. **Do process private bytes and peak working set stay in the 0.x measurement channel list?**
   *Recommendation: drop them from the mod; keep JVM heap and GC via `java.lang.management` in-process,
   and let the runner sample OS-level counters if they are wanted at all.* No JDK API exposes PSAPI
   counters, so keeping them in the mod means adding JNA or OSHI to the harness jar — which inflates
   exactly the startup and memory numbers Laymark reports. *Trade-off:* runner-side sampling is
   coarser and not frame-aligned, so a memory-focused scenario loses resolution.

8. **Are these two documents rewritten, or superseded and deleted?**
   *Recommendation: fold the surviving content into a rewritten `benchmark-harness-plan.md` and
   delete `benchmark-harness-handoff.md`.* Its unique content is the compatibility matrix (which
   `loader-portability-research.md` already owns and states more precisely), the barrier taxonomy
   (which belongs in the plan), and the known-traps list (worth migrating verbatim). Two documents
   with two contradictory state machines is how the streaming-phase disagreement survived.
   *Trade-off:* a rewrite is more work now than a scope-edit pass, and loses the handoff's useful
   "questions left for implementation" framing unless those questions are migrated into the map.

9. **Does 0.x stay pinned to Minecraft 26.1.2?**
   *Recommendation: yes, keep the pin — it is the Lucent pack line and the whole point is to decide
   that pack's stack.* *Trade-off:* 26.1.2 is no longer current (26.2 is the latest release,
   NeoForge is at 26.2.0.59, and both are already installed here). `GameRenderer`, blaze3d's GPU
   abstraction, and `Options` are exactly the surfaces that move between versions, so every month on
   26.1.2 widens the gap the eventual retarget has to cross — and 0.x's whole design bet is that the
   retarget is a recompile plus a narrow overlay.

10. **Do benchmark suites live in the Laymark repo or in the pack?**
    *Recommendation: the Laymark repo, and delete the `.layignore` sentence from the plan* — Laymark
    is not an Inlay Layer, so `.layignore` has no jurisdiction over `benchmarks/`. *Trade-off:*
    pack-owned suites would version with the pack lineage, which is better for auditability of a
    specific pack's results, but couples Laymark's schema releases to the pack repo and would then
    genuinely require adding `benchmarks/` to `.layignore`.

11. **Is the unsampled-plus-Spark-sampled double pass kept?**
    *Recommendation: keep it, but measure Spark's overhead once before committing.* The reasoning is
    sound and no source contradicts it, but no source supports it either, and it doubles wall-clock
    per case. *Trade-off:* if the overhead at `--interval 4` turns out to be below the noise budget,
    the second pass is pure cost and every case could carry its profile for free.

12. **What do the screening and confirmation repetition counts become?**
    *Recommendation: derive them from a measured control noise budget rather than keeping the
    unsourced "two" and "five to seven".* *Trade-off:* deriving them requires a calibration run
    before any ranking can start, which delays the first real experiment by roughly one suite's
    wall-clock; keeping the invented numbers risks either wasted repetitions or unreproducible
    accept/reject decisions.
