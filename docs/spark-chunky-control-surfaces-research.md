# Laymark Spark and Chunky control-surfaces research

Research date: 2026-08-16. Target tuple: Minecraft 26.1.2 / Java 25 / NeoForge 26.1.2.95 / Spark 1.10.173 / Chunky 1.5.4. Loader scope: NeoForge only.

## Source pins

Everything below was read from source or from the published binaries, not from memory or secondary write-ups.

| Source | Pin | How it was verified as the right pin |
| --- | --- | --- |
| Spark 1.10.173 (NeoForge) | commit [`557c199`](https://github.com/lucko/spark/commit/557c199e57fa2085d235bbc3d301ba7b0b6633e2) | Modrinth publishes `spark-1.10.173-neoforge.jar` dated 2026-06-18 ([version listing](https://api.modrinth.com/v2/project/spark/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%2226.1.2%22%5D)); `557c199` is the only spark commit on 2026-06-18 and is titled "Update to Minecraft 26.2 (#571)", matching the Modrinth version name "1.10.173 (NeoForge 26.2)". Spark does not tag point releases. |
| Chunky 1.5.4 (NeoForge) | commit [`ab45b8b`](https://github.com/pop4959/Chunky/commit/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1) | Modrinth publishes `Chunky-NeoForge-1.5.4.jar` dated 2026-07-23 ([version listing](https://api.modrinth.com/v2/project/chunky/version?loaders=%5B%22neoforge%22%5D)); `ab45b8b` (2026-07-22) is repository HEAD. Confirmed by extracting `version.properties` (`version=1.5.4`) and `META-INF/neoforge.mods.toml` (`version="1.5.4"`) from the downloaded jar, and by `javap -c` on `GenerationTask.class` and `ChunkyAPIImpl.class` matching the source at that commit byte-for-byte in the load-bearing methods. |
| NeoForge 26.1.2.95 | `neoforge-26.1.2.95-sources.jar` from [NeoForged Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.1.2.95/) | 26.1.2.95 is the newest 26.1.2 build in [`maven-metadata.xml`](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml) (published 2026-08-11). NeoForge does not git-tag releases, so the sources jar is the authority, not the `main` branch. |

Both mods are genuinely published for this tuple. Spark 1.10.173 lists `game_versions` `["26.1","26.1.1","26.1.2","26.2"]` for `loaders:["neoforge"]`; Chunky 1.5.4 lists the same set. **Chunky 1.5.4 for NeoForge 26.1.2 exists** — its `neoforge.mods.toml` declares `neoforge` `versionRange="[26.1.0.0-beta,)"` and `minecraft` `versionRange="[26.1,)"`, which 26.1.2.95 satisfies. That range is the result of the HEAD commit, literally titled "Relax version restriction on Forge/NeoForge".

---

## Part 1 — Spark 1.10.173

### 1.1 The supported public API exposes no sampler lifecycle. Confirmed.

`spark-api` at `557c199` contains exactly nine source files. The whole surface of [`Spark`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-api/src/main/java/me/lucko/spark/api/Spark.java) is:

```java
DoubleStatistic<CpuUsage> cpuProcess();
DoubleStatistic<CpuUsage> cpuSystem();
DoubleStatistic<TicksPerSecond> tps();          // nullable
GenericStatistic<DoubleAverageInfo, MillisPerTick> mspt();  // nullable
Map<String, GarbageCollector> gc();
PlaceholderResolver placeholders();
```

There is no `startProfiler`, no `Sampler`, no lifecycle of any kind. [`SparkProvider.get()`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-api/src/main/java/me/lucko/spark/api/SparkProvider.java) throws `IllegalStateException("spark has not loaded yet!")` before spark enables. Spark's own documentation agrees: the [Developer API page](https://spark.lucko.me/docs/Developer-API) lists only TPS, MSPT, CPU usage and GC, and says nothing about controlling the profiler.

The artifact is real and resolvable: `me.lucko:spark-api:0.1-SNAPSHOT` from `https://repo.lucko.me/` ([maven-metadata](https://repo.lucko.me/me/lucko/spark-api/0.1-SNAPSHOT/maven-metadata.xml), latest snapshot `0.1-20250703.200108-1`). Note it is a **snapshot that has not been republished since 2025-07-03** — this is the stable, published API surface, but it carries no release version and no semver promise.

Everything that actually drives a capture lives in `spark-common`, which is shaded into the mod jar under `me.lucko.spark.common.*` and is not published for compilation. **The command bridge is the only supported route.** Laymark should not import `SparkPlatform`, `Sampler`, or `SamplerContainer`.

### 1.2 Command syntax — the plan's line is very nearly right

The plan proposes:

```text
sparkc profiler start --timeout 45 --thread * --not-combined --interval 4 --save-to-file --comment <case-id>
```

Every one of those flags is real. From [`SamplerModule`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java) at `557c199`:

- Command aliases: `profiler`, `sampler`. Subcommands: `info`, `start`, `open`, `stop` (alias `upload`), `cancel`, `trust-viewer`.
- `start` flags: `--timeout`, `--thread` (repeatable), `--regex`, `--combine-all`, `--not-combined`, `--interval`, `--only-ticks-over`, `--force-java-sampler`, `--alloc`, `--alloc-live-only`, `--ignore-sleeping`.
- `stop`/`upload` flags: `--comment`, `--save-to-file`, `--separate-parent-calls`.
- `--thread *` maps to `ThreadDumper.ALL` = `threadBean.dumpAllThreads(false, false)`; `--not-combined` maps to `ThreadGrouper.BY_NAME`; default grouping is `BY_POOL`; default `EXECUTION` interval is 4 ms ([`SamplerMode`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/sampler/SamplerMode.java)).

Three corrections and traps that matter for a harness:

1. **`--timeout` is validated.** `timeoutSeconds <= 10` is rejected outright with a red chat message and no capture; `< 30` merely warns (`SamplerModule.java:169-180`). Laymark must never emit a bounded capture shorter than 11 s, and should treat sub-30 s captures as low-confidence.
2. **`--comment` and `--save-to-file` are read at *start* time on the `--timeout` path.** `profilerStart` computes `getExportProps(...)` and `arguments.boolFlag("save-to-file")` before registering `future.thenAcceptAsync(s -> handleUpload(...))` ([L289-296](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L289-L296)). So a single self-terminating `start --timeout N --save-to-file --comment <id>` is a complete, valid capture — the plan's single-command form works. On the explicit `stop` path the flags are read from the `stop` invocation instead.
3. **Argument parsing is space-joined, not quoted.** [`Arguments`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/Arguments.java) splits on spaces and joins each flag's trailing tokens with `" "`. A comment may contain spaces, but any token beginning with `--` starts a new flag. **Case IDs must not contain `--`.** Laymark should restrict case IDs to `[A-Za-z0-9._:-]` with no leading `-`.

The [official command docs](https://spark.lucko.me/docs/Command-Usage) corroborate the flag list and confirm `/sparkc` as the client command on Forge/Fabric-family clients. The docs additionally mention `--alloc-interval`; the source only implements `--interval` for both modes, so treat the source as authoritative.

### 1.3 The client command on NeoForge, and how a mod invokes it programmatically

Spark registers the client command from [`NeoForgeClientSparkPlugin`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-neoforge/src/main/java/me/lucko/spark/neoforge/plugin/NeoForgeClientSparkPlugin.java#L70-L73):

```java
@SubscribeEvent
public void onCommandRegister(RegisterClientCommandsEvent e) {
    registerCommands(e.getDispatcher(), this, this, "sparkc", "sparkclient");
}
```

The node shape is `literal("sparkc").executes(...).then(argument("args", greedyString()).executes(...))`, with `sparkclient` a redirect ([`MinecraftSparkPlugin.registerCommands`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-minecraft/src/main/java/me/lucko/spark/minecraft/plugin/MinecraftSparkPlugin.java)). The integrated server separately registers `/spark` with permission checks; the client command has `hasPermission() == true` unconditionally.

The programmatic route is **`net.neoforged.neoforge.client.ClientCommandHandler.runCommand(String)`**, verified present and public (not `@ApiStatus.Internal`) in the 26.1.2.95 sources jar. Its javadoc reads:

> `@param command the full command to execute, no preceding slash`
> `@return {@code false} leaves the message to be sent to the server, while {@code true} means it should be caught before LocalPlayer#sendCommand`

This matches spark's own parsing: `MinecraftClientSparkPlugin.run` calls `processArgs(context, false, "sparkc", "sparkclient")`, which requires `context.getInput().split(" ")[0]` to equal `sparkc` — i.e. **no leading slash**. So the call is:

```java
ClientCommandHandler.runCommand("sparkc profiler start --timeout 45 --thread * --not-combined --interval 4 --save-to-file --comment " + caseId);
```

Two hard preconditions, both from `ClientCommandHandler` in 26.1.2.95:

- The client dispatcher (`ClientCommandHandler.commands`) is `null` until `mergeServerCommands(...)` runs, which happens on `ClientPlayerNetworkEvent.LoggingIn` and on the server's commands packet. `runCommand` before a world is joined NPEs.
- `getSource()` dereferences `Minecraft.getInstance().player`. Must be called after the local player exists, on the client thread.

`runCommand` swallows all exceptions and reports them to chat; it returns `true` whenever the command was recognised, **regardless of whether the command did anything useful**.

### 1.4 The return value is worthless as a completion signal

This is the single biggest correction to the plan, which says "the harness should wait for command completion and the new file".

`MinecraftClientSparkPlugin.run` does:

```java
this.platform.executeCommand(createCommandSender(context.getSource()), args);
return Command.SINGLE_SUCCESS;
```

`SparkPlatform.executeCommand` delegates to [`CommandManager.executeCommand`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/CommandManager.java#L129), which returns a `CompletableFuture<Void>` and runs the actual command body on spark's own `SparkScheduledThreadPoolExecutor(4)` under a fair `ReentrantLock`. **Spark discards that future.** Brigadier returns `SINGLE_SUCCESS` before the sampler has been touched. There is no synchronous completion signal anywhere on the client command path.

Corollary: "confirm the capture is active" also cannot be done from the return value. Options, in descending order of robustness — see §1.6.

### 1.5 The artifact: where, what name, and how to bind it to a case ID

From [`handleUpload`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L419-L470):

```java
Path file = platform.resolveSaveFile("profile", "sparkprofile");
Files.write(file, output.toByteArray());
...
platform.getActivityLog().addToLog(Activity.fileActivity(resp.senderData(), System.currentTimeMillis(), "Profiler", file.toString()));
```

and [`SparkPlatform.resolveSaveFile`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/SparkPlatform.java#L270-L279):

```java
return pluginFolder.resolve(prefix + "-" + DATE_TIME_FORMATTER.format(LocalDateTime.now()) + "." + extension);
// DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")
```

`pluginFolder` is `SparkPlugin.getPluginDirectory()`, which on Minecraft is `mod.getConfigDirectory()` = `FMLPaths.CONFIGDIR.get().resolve("spark")`. So:

```text
<instance>/config/spark/profile-yyyy-MM-dd_HH.mm.ss.sparkprofile
```

The same directory holds `config/spark/config.json` and `config/spark/activity.json`.

Three consequences:

- **There is no way to choose the output path or filename.** No flag, no config key. Laymark must discover and then move/copy the file into its own result directory.
- **The name has one-second resolution and no collision handling.** Two saves within the same wall-clock second silently overwrite each other (`Files.write` with default `CREATE, TRUNCATE_EXISTING`). Laymark must serialise captures and must not assume a mapping from expected time to filename.
- **The case ID does survive into the artifact.** `--comment` is written to `SamplerMetadata.comment` in the protobuf ([`AbstractSampler.writeMetadataToProto`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/sampler/AbstractSampler.java)). The schema is in the repo at `spark-common/src/main/proto/spark/spark_sampler.proto`. Reading it back requires a protobuf parse; it is not a published artifact, so Laymark would have to vendor the `.proto`.

**Recommended deterministic mapping:** watch `config/spark/activity.json`. `Activity.fileActivity` is appended *after* `Files.write` returns, and `ActivityLog.addToLog` synchronously rewrites the whole JSON file. Each entry is `{"user":…,"time":<epochMillis>,"type":"Profiler","data":{"type":"file","value":"<absolute path>"}}` ([`Activity.serialize`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/activitylog/Activity.java)). Because Laymark issues captures one at a time, "the newest `type:"Profiler"`, `data.type:"file"` entry whose `time` is after the capture started" is an unambiguous binding to the case ID Laymark already knows. Cross-check by parsing the proto comment only if paranoia is warranted. Note `ActivityLog.save()` prunes entries via `shouldExpire()`, so treat it as a rolling log, not an archive.

### 1.6 Knowing a capture has fully settled

The full chain for `sparkc profiler stop --save-to-file --comment <id>`, all verified in source:

1. Brigadier returns immediately (§1.4).
2. On a spark scheduler thread: `profilerStop` → `unsetActiveSampler(sampler)` → `sampler.stop(false)`.
3. `stop(false)` is **synchronous with respect to the profile data**. `AsyncSampler.stop` stops the async-profiler job, calls `windowStatisticsCollector.measureNow(...)`, `currentJob.aggregate(dataAggregator)`, and `dataAggregator.close()` inline. `JavaSampler.stop` cancels the sampling task and calls `workerPool.shutdown()`.
4. `handleUpload` → `sampler.toProto(...)` → `Files.write(file, bytes)`.
5. Chat: `"Profiler stopped & save complete!"` then `"Data has been written to: <path>"`.
6. `activityLog.addToLog(...)` → `config/spark/activity.json` rewritten.

So, in order of reliability:

- **Best: a new `Profiler`/`file` entry in `config/spark/activity.json`.** Strictly ordered after the profile bytes are on disk. This is the "fully settled" barrier.
- **Second: the chat line `Data has been written to: <path>`.** Carries the exact path. Spark sends command responses to the client via `CommandResponseHandler`, prefixed with `[⚡] `, dispatched onto the client render thread when the platform type is `CLIENT`. These strings are hardcoded English, not translated, so a mod hooking client chat can match them. Brittle across spark versions but usable as a corroborating signal — and it is the *only* channel that surfaces failures such as `"The specified timeout is not long enough…"`, `"Profiler is already running!"`, or `"There isn't an active profiler running."`.
- **Worst: a raw `WatchService` on `config/spark/*.sparkprofile`.** `Files.write` is not atomic; a watcher can observe a partially written file. Only acceptable combined with one of the above, or with a size-stability poll.

There is one small unavoidable race worth recording: `JavaSampler.stop` calls `workerPool.shutdown()`, which does **not** await termination, and `JavaDataAggregator` has no flush/await hook (`DataAggregator` declares only `exportData`, `pruneData`, `getMetadata`). A handful of already-dumped-but-not-yet-inserted stack samples at the very tail of a Java-engine capture may be missing from the export. This is bounded by one sampling interval's worth of queued work and does not affect the "did the capture happen" question, but it means a Java-engine `.sparkprofile` is not a bit-exact record of the requested window.

### 1.7 Concurrency and overlap: what actually happens

Four separate mechanisms, and they do not agree with each other:

1. **Per-platform guard, silent.** `profilerStart` reads `platform.getSamplerContainer().getActiveSampler()`. If a *non-background* sampler is active it prints `profilerInfo(...)` to chat and **returns without starting anything** ([L157-171](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L157-L171)). No exception, no failure the harness can observe except the chat text. **Overlapping captures are not possible, and the second request is silently dropped.**
2. **Background pre-emption.** If the active sampler *is* a background sampler, `profilerStart` stops it first and proceeds.
3. **Container race guard.** `SamplerContainer.setActiveSampler` does `compareAndSet(null, sampler)` and throws `IllegalStateException("Attempted to set active sampler when another was already active!")` on failure.
4. **JVM-global async-profiler guard.** `AsyncProfilerJob.ACTIVE` is a `static AtomicReference` — *one job per JVM*, spanning the client and integrated-server spark platforms. `AsyncSampler.checkAlreadyRunning()` throws `UnsupportedOperationException("A profiler is already running on the server side. You need to stop it (using /spark profiler cancel) …")` when the two platform types differ.

**This last one is a landmine on Linux and macOS.** `BackgroundSamplerManager` computes `enabled = type != CLIENT && configuration.getBoolean("backgroundProfiler", type == SERVER)`. `NeoForgeClientSparkPlugin` reports `Type.CLIENT` (background disabled), but `NeoForgeServerSparkPlugin` reports `Type.SERVER` — and it is initialised on `ServerAboutToStartEvent`, which fires for the **integrated singleplayer server** too. So by default, in singleplayer:

- a background profiler starts on the integrated server as soon as the world loads;
- on Linux/macOS it holds the global async-profiler job, and every subsequent `sparkc profiler start` fails;
- on any platform it adds continuous sampling load to the very process Laymark is measuring.

**Laymark must write `"backgroundProfiler": false` into `<instance>/config/spark/config.json` and verify it, as part of instance preparation.** Both spark plugins share that file (`getPluginDirectory()` is the same for both). Consider also `"disableResponseBroadcast"` — leave it *false*, since Laymark may want the chat channel.

### 1.8 The sampling engine on Windows, and what "profiled vs unprofiled" costs

Verified from [`AsyncProfilerAccess.load`](https://github.com/lucko/spark/blob/557c199e57fa2085d235bbc3d301ba7b0b6633e2/spark-common/src/main/java/me/lucko/spark/common/sampler/async/AsyncProfilerAccess.java#L156-L170):

```java
.put("linux",  "amd64",   "linux/amd64")
.put("linux",  "aarch64", "linux/aarch64")
.put("macosx", "amd64",   "macos")
.put("macosx", "aarch64", "macos")
```

Anything else throws `UnsupportedSystemException`. Confirmed against the shipped jar: `spark-1.10.173-neoforge.jar` contains `spark-native/linux/{amd64,aarch64}/libasyncProfiler.so` and `spark-native/macos/libasyncProfiler.so` and **no Windows binary**.

So on Windows — the platform this project is being developed on — `SamplerBuilder.start` falls through to `JavaSampler`, which is a `ThreadMXBean` stack-dump sampler:

```java
this.task = this.workerPool.scheduleAtFixedRate(this, 0, this.interval, TimeUnit.MICROSECONDS);
// run(): ThreadInfo[] threadDumps = this.threadDumper.dumpThreads(this.threadBean);
```

At the default 4 ms interval with `--thread *` that is `ThreadMXBean.dumpAllThreads(false, false)` 250 times per second across every thread in a modded client. That is a materially different and much heavier instrument than async-profiler's signal-based sampling, and its cost scales with thread count — which is exactly the thing that differs between the mod stacks Laymark is comparing.

Implications for the plan's "one unsampled scoring pass, one Spark-sampled diagnostic pass" design, which is otherwise sound:

- The design is **necessary**, not optional, and more so on Windows than the plan assumes.
- Spark overhead is not a constant that can be subtracted. `dumpAllThreads` cost varies with thread count and stack depth, both of which are properties of the arm under test. Sampled and unsampled passes must never be pooled, differenced, or compared across arms as if the profiler were a fixed tax.
- The sampled pass's own frame-time numbers are contaminated and should be recorded as diagnostic-only.
- `--thread *` is significantly more expensive than the default single-thread dumper. Reserve it for the chunk-generation and parallel-worker scenarios the plan already earmarks; use spark's default (the client game thread, via `ThreadDumper.GameThread`) for render attribution.
- The engine in use is recorded in the artifact (`SamplerMetadata.samplerEngine`, plus `samplerEngineVersion` for async). Laymark's `environment.json` should record it too, and should refuse to compare captures taken with different engines.

### 1.9 Spark provenance caveat

`557c199` — the commit that produced 1.10.173 — changed **only** two build files: `spark-minecraft/build.gradle` (`com.mojang:minecraft:26.1` → `26.2`) and `spark-neoforge/build.gradle` (`neoForge.version` `26.1.0.1-beta` → `26.2.0.1-beta`). Not one line of Java differs from 1.10.172. So 1.10.173 is byte-for-byte the same *source* as the 26.1-targeted 1.10.172, compiled against Minecraft 26.2 and NeoForge 26.2.0.1-beta.

The NeoForge-side symbols spark uses (`RegisterClientCommandsEvent#getDispatcher`, `ServerAboutToStartEvent`, `ClientCommandHandler`) are all present in 26.1.2.95. The Minecraft-side symbols cannot be verified from published sources without a mapped 26.1.2 environment. Lucko's own Modrinth metadata asserts 26.1–26.2 compatibility for this single file, and 26.1 is unobfuscated so signatures are stable names, but this is the author's claim rather than something I could confirm. See "Decisions needed from Mia".

---

## Part 2 — Chunky 1.5.4

### 2.1 The API entry point — the plan's call is correct, but its provenance is not what you'd expect

`ChunkyProvider.get().getApi()` is correct on NeoForge. Verified:

- [`ChunkyProvider.get()`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/ChunkyProvider.java) returns `Chunky` and throws `IllegalStateException("Chunky is not loaded.")` when unregistered.
- [`Chunky.getApi()`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/Chunky.java) returns the `ChunkyAPI` created in the `Chunky` constructor, which also calls `ChunkyProvider.register(this)`.
- Both classes are present in the shipped `Chunky-NeoForge-1.5.4.jar` (`org/popcraft/chunky/ChunkyProvider.class`, `org/popcraft/chunky/api/ChunkyAPI.class`).

But note what the project itself considers "the API". [`common/build.gradle.kts`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/build.gradle.kts) generates javadoc with `include("org/popcraft/chunky/api/**")` and `exclude(".../ChunkyAPIImpl.java")`. `ChunkyProvider` and `Chunky` are **outside** the documented API. The [official Developer API wiki page](https://github.com/pop4959/Chunky/wiki/Developer-API) documents only the Bukkit route:

> `ChunkyAPI chunky = Bukkit.getServer().getServicesManager().load(ChunkyAPI.class);`

There is no documented acquisition path for NeoForge. `ChunkyProvider` is source-visible, shipped, and stable in practice, but it is an undocumented seam. Laymark should reflectively-tolerant-wrap it or at minimum fail closed with a clear diagnostic if it disappears.

**Dependency coordinates (verified live):** `org.popcraft:chunky-common:1.5.4` from `https://repo.codemc.io/repository/maven-public/` — the jar returns HTTP 200 and 1.5.4 is the newest entry in the [maven-metadata](https://repo.codemc.io/repository/maven-public/org/popcraft/chunky-common/maven-metadata.xml). (The wiki example still says `1.3.38`.) Declare it `compileOnly` / `provided`.

### 2.2 Version compatibility guarantee

[`ChunkyAPIImpl.version()`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/api/ChunkyAPIImpl.java#L23) returns a literal `0`. Confirmed in the shipped jar's bytecode (`iconst_0; ireturn`). The stated guarantee, verbatim from the wiki:

> "The API version will be bumped any time a breaking change is introduced to the API which may impact integrations, so it's recommended to check this and disable your integration if it encounters an unexpected version."
> "Since the API is new, breaking changes may occur with some frequency, however the goal is to reduce this as much as possible. An effort will also be made to annotate any deprecated methods for at least one release before removal."

So the contract is: gate on `version() == 0` and refuse to run otherwise. That guarantee covers the eight `ChunkyAPI` methods and the two event records only. **It explicitly does not cover `ChunkyProvider`, `Chunky`, `GenerationTask`, `GenerationTaskUpdateEvent`, or `GenerationTaskFinishEvent`**, all of which are internal.

### 2.3 Lifecycle on NeoForge, and the listener-registration hazard

From [`ChunkyNeoForge`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/neoforge/src/main/java/org/popcraft/chunky/ChunkyNeoForge.java):

- `onServerStarting(ServerStartingEvent)` constructs `new Chunky(new NeoForgeServer(this, server), new GsonConfig(FMLPaths.CONFIGDIR.get().resolve("chunky/config.json")))`. The `Chunky` constructor registers the provider. **This fires for the integrated singleplayer server.**
- `onServerStopping(ServerStoppingEvent)` calls `chunky.disable()`, which saves tasks, calls `stop(false)` on every running task, cancels the scheduler, and unregisters the provider.

Therefore: **`ChunkyProvider.get()` is valid only between `ServerStartingEvent` and `ServerStoppingEvent`, and a brand-new `Chunky` — with a brand-new `EventBus` — is created for every world load.** Laymark must re-register its listeners on every world join. There is no persistence across worlds.

Two more hazards in [`EventBus`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/event/EventBus.java):

- `subscribers` is a plain `HashMap<Class<?>, Set<Consumer<?>>>` with a plain `HashSet`, with **no synchronisation at all**. `call(...)` runs on Chunky's task threads while `subscribe(...)` would run on the main thread. Laymark must register every listener *before* calling `startTask` and never subscribe while a task is running.
- Listener exceptions are caught and `printStackTrace()`d; they do not propagate. A listener that throws will be silently ineffective.
- `call` dispatches on exact `event.getClass()`; the events are records, so this is fine.

Config directory: `GsonConfig.getDirectory()` is the parent of the config path, i.e. `<instance>/config/chunky/`. Saved task state lives in `<instance>/config/chunky/tasks/`.

### 2.4 The events: exactly when each fires, and payloads

Only two events are in the public API.

**`GenerationProgressEvent(String world, long chunks, boolean complete, float progress, long hours, long minutes, long seconds, double rate, long x, long z)`** — a record.

Fired from the private, `synchronized` `GenerationTask.update(chunkX, chunkZ, loaded)` at [L100](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L100). `update` is called from exactly two places:

- synchronously on the Chunky task thread for chunks that are skipped (outside the shape, or already generated when `force-load-existing-chunks` is false);
- from the `whenComplete` callback of each chunk future, i.e. **on whichever thread completed that future — never the main server thread by contract**.

It is rate-limited: `if (chunksLeft > 0 && timeDiff < 1e-1) return;` ([L80](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L80)) — so progress events arrive at most ~10/s while work remains, and the throttle is *bypassed* when `chunksLeft == 0`. `update` returns immediately if the task is `stopped`.

`world` is the canonical world identifier (`ServerLevel.dimension().identifier().toString()`, e.g. `minecraft:overworld`) — [`NeoForgeWorld.getName`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/neoforge/src/main/java/org/popcraft/chunky/platform/NeoForgeWorld.java#L52-L54). `progress` is a percentage (0–100), not a fraction. `hours/minutes/seconds` are the **ETA** while running and the **elapsed total** on the completing event. `rate` is a rolling chunks-per-second average over a 30 s window (`chunky.sampleInterval`).

**`GenerationCompleteEvent(String world)`** — a record with one component, the canonical world identifier.

Fired as the **last statement of `GenerationTask.run()`**, [L170](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L170), immediately after `GenerationTaskFinishEvent` (internal), on the Chunky task-pool thread.

### 2.5 What genuinely signals COMPLETE — the plan's caveat is CONFIRMED, with the exact mechanism

The plan claims `GenerationCompleteEvent` can fire before outstanding async chunk work drains, and can also fire on cancellation. **Both are true.** Here is the mechanism, from `GenerationTask.run()`:

```java
final Semaphore working = new Semaphore(MAX_WORKING_COUNT);   // L124, default 50
...
while (!stopped && chunkIterator.hasNext()) {                  // L127
    ...
    working.acquire();
    isChunkGenerated
        .thenCompose(...)                                      // getChunkAtAsync
        .whenComplete((ignored, throwable) -> {                // L156
            working.release();
            update(chunk.x(), chunk.z(), true);
        });
}
if (stopped) { ...TASK_STOPPED... } else { cancelled = true; } // L161-165
chunky.getTaskLoader().saveTask(this);
chunky.getGenerationTasks().remove(selection.world().getName());
chunky.getEventBus().call(new GenerationTaskFinishEvent(this));
chunky.getEventBus().call(new GenerationCompleteEvent(...));   // L170
```

The loop exits as soon as the iterator is exhausted. It does **not** re-acquire the remaining permits, does not join the outstanding futures, does not await anything. Up to `MAX_WORKING_COUNT` (default 50, `-Dchunky.maxWorkingCount`) chunk operations may still be in flight when `GenerationCompleteEvent` fires. Confirmed at the bytecode level in the shipped 1.5.4 jar: `GenerationTask.run` runs straight from the loop exit through `saveTask` / `Map.remove` / both `EventBus.call` invocations to `return` at offset 461, with no join.

`GenerationCompleteEvent` also fires on **every** exit path: natural exhaustion, `cancelTask` (`stop(true)`), `pauseTask` (`stop(false)`), thread interruption, and `chunkIterator.process()` returning false. It carries no success/failure flag at all.

**The correct barrier — the plan is right — is `GenerationProgressEvent.complete() == true`.** Why it is genuinely stronger:

- `progress.complete` is set only when `chunksLeft == 0`, i.e. `finishedChunks == chunkIterator.total()` ([L88-92](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L88-L92)).
- Every finish increment comes either from a synchronous skip or from a chunk future's `whenComplete`. So the event carrying `complete == true` is emitted from **inside the last outstanding chunk operation's completion callback**, after its `working.release()`. That is the true drain point.
- On NeoForge that callback sits downstream of `getChunkAtAsync`'s own chain, which already removed Chunky's loading ticket via `whenCompleteAsync(..., world.getServer())` before the outer `whenComplete` runs.
- `update` early-returns when `stopped`, so a cancelled or paused task **never** emits a `complete == true` progress event.

That last point gives Laymark a clean discriminator:

| Observation | Meaning |
| --- | --- |
| `GenerationProgressEvent.complete()==true`, then `GenerationCompleteEvent` | genuine, drained completion |
| `GenerationCompleteEvent` with no preceding `complete()==true` | cancelled, paused, interrupted, or empty selection — **invalid run** |

Two edge cases to program around:

- **Zero-chunk selection.** If `chunkIterator.total() == 0`, `update` is never called, so no progress event ever fires, but `GenerationCompleteEvent` does. Laymark's barrier must have a timeout and must treat "complete event, no progress-complete" as a failure rather than waiting forever.
- **Progress-complete is not necessarily emitted exactly once.** `progress.complete` is sticky once set; any further `update` call (only reachable if the finished count could overshoot `total()`) would emit another `complete == true` event. Laymark's listener must be idempotent.

Chunky does **not** save the world. `GenerationCompleteEvent` means "Chunky stopped working", not "the region files are written". The plan's "wait for a forced world save and server quiescence" is correct and is Laymark's own responsibility.

Also relevant: the wiki documents a `chunky.awaitTicketRemoval` system property ("wait for its own chunk tickets to be removed before considering a chunk processed"). **It is implemented only in the Bukkit platform** — `AWAIT_TICKET_REMOVAL` appears solely in `bukkit/.../BukkitWorld.java` and has no NeoForge counterpart in 1.5.4. Do not plan around it.

### 2.6 Cancellation and exceptional futures — the plan overreaches here

**Cancellation is observable**, via the discriminator in §2.5, plus `ChunkyAPI.cancelTask(world)` returning `false` when no task was registered.

But note `GenerationTask.isCancelled()` is **not** a cancellation flag. On the *natural* completion path the code does `if (stopped) {…} else { cancelled = true; }` — a task that ran to exhaustion ends with `cancelled == true`. The field means "do not resume this on restart", for `TaskLoader`. It is internal anyway, but do not reach for it.

**Exceptional chunk futures are not observable at all, and the plan's instruction to "treat exceptional chunk futures as invalid runs" cannot be implemented through Chunky's API.** Look again at L156:

```java
}).whenComplete((ignored, throwable) -> {
    working.release();
    update(chunk.x(), chunk.z(), true);
});
```

`throwable` is bound and then **completely ignored** — not logged, not counted, not surfaced on any event. A chunk whose generation future completed exceptionally still:

- increments `finishedChunks`, so it counts toward `complete == true`;
- passes `loaded = true`, so `worldState.setGenerated(chunkX, chunkZ)` marks it generated in Chunky's own `RegionCache`, poisoning subsequent skip decisions for that session.

So Chunky will report a 100 %-complete, fully "successful" generation even if every single chunk failed. **Correction to the plan: Laymark cannot rely on Chunky to report generation failure and must verify independently.** Options: an independent post-pass over the selection using the server's own chunk-status check, a region-file audit of the selection footprint, or watching the game log for generation exceptions. Whichever is chosen, it is Laymark's code, not Chunky's.

### 2.7 Argument traps in `startTask`

[`ChunkyAPIImpl.startTask`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/api/ChunkyAPIImpl.java#L33-L50) returns `false` in only two cases: the world identifier does not resolve, or a task is already registered under that exact key. Three traps follow.

1. **Shape and pattern strings are never validated.** `Selection.Builder.shape(String)` just stores the string. `ShapeFactory.getShape` has `default -> custom.getOrDefault(selection.shape(), Square::new)` — an unknown shape **silently becomes a square**. `ChunkIteratorFactory` similarly falls through to `RegionChunkIterator` for an unknown pattern. `startTask` returns `true` either way. Laymark must validate against the canonical sets itself:
   - shapes: `circle, diamond, ellipse, hexagon, oval, pentagon, rectangle, square, star, triangle` ([`ShapeType`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/shape/ShapeType.java));
   - patterns: `concentric, loop, spiral, csv, region, world` ([`PatternType`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/iterator/PatternType.java)).
   Note that for `rectangle`/`ellipse`/`oval` the pattern argument is ignored — `ChunkIteratorFactory` forces `Loop2ChunkIterator`. The plan's example `{"shape":"square","pattern":"spiral"}` is valid and honoured.
2. **Pass the canonical dimension identifier, always.** `NeoForgeServer.getWorld` accepts either `minecraft:overworld` or the bare path `overworld`, but `startTask` stores the task in the map under **the raw input string** (`generationTasks.put(world, task)`) while `GenerationTask.run` removes it under **the canonical name** (`generationTasks.remove(selection.world().getName())`). Start a task as `"overworld"` and the map entry is never removed: `isRunning("overworld")` stays `true` forever and every later `startTask("overworld", …)` returns `false` for the rest of the session. **Always pass `minecraft:overworld`.** (Both events always report the canonical name regardless, so listeners are unaffected.)
3. `radiusZ` is used only by shapes that take a secondary radius. For `square`/`circle` it is ignored.

### 2.8 Tuning knobs Laymark should know about

From the [official System Properties page](https://github.com/pop4959/Chunky/wiki/System-Properties), cross-checked against `GenerationTask` and `NeoForgeWorld` source:

| Property | Default | Implemented on NeoForge 1.5.4? | Relevance |
| --- | --- | --- | --- |
| `chunky.maxWorkingCount` | 50 | yes (`GenerationTask`) | Directly bounds the in-flight window that makes `GenerationCompleteEvent` premature. Lowering it shrinks the drain gap but throttles generation. |
| `chunky.sampleInterval` | 30 (s), floored at 30 | yes (`GenerationTask`) | Rolling window for `rate`. The plan is right to record the raw progress stream instead of trusting the final displayed rate. |
| `chunky.tickingLoadDuration` | 0 | yes (`NeoForgeWorld`) | Non-zero ticks chunks after generation (fluid settling, post-processing). Changes what "generated" means for a benchmark. Leave at 0 unless the scenario demands otherwise, and record it. |
| `chunky.updateChunkNbt` | false | yes (`NeoForgeWorld`) | Induces DFU upgrades during the status check. Irrelevant for freshly created worlds; leave false. |
| `chunky.awaitTicketRemoval` | false | **no — Bukkit only** | Do not plan around it. |

Relevant `config/chunky/config.json` keys: `continue-on-restart` (must be `false` for Laymark — otherwise `ChunkyNeoForge.onServerStarting` auto-resumes a saved task before Laymark has said anything), `force-load-existing-chunks` (`false`, so already-generated chunks are skipped), `silent`, `update-interval`.

---

## Recommended control sequence

**Instance preparation, before launch:**

- Write and verify `<instance>/config/spark/config.json` → `"backgroundProfiler": false`.
- Write and verify `<instance>/config/chunky/config.json` → `"continue-on-restart": false`, `"force-load-existing-chunks": false`.
- Record the resolved values of the `chunky.*` system properties in `environment.json`.

**Spark capture (bounded, self-terminating — preferred over start/stop):**

1. After client player login, on the client thread:
   `ClientCommandHandler.runCommand("sparkc profiler start --timeout <N≥30> --interval 4 --save-to-file --comment " + caseId)` (add `--thread * --not-combined` only for chunk/parallel scenarios).
2. Confirm the capture actually started. `runCommand`'s `true` proves nothing. Use the chat channel (`[⚡] Profiler is now running!`) as the positive signal and treat `Profiler is already running!` / any red response as a hard failure.
3. Wait for a new `type:"Profiler"`, `data.type:"file"` entry in `config/spark/activity.json` with `time >= captureStart`. That is the settled barrier; its `data.value` is the exact `.sparkprofile` path.
4. Move that file into the Laymark result directory under a Laymark-chosen name, recording the original filename, the case ID, and the sampler engine.
5. Invalidate the run if no entry appears within `timeout + margin`.

**Chunky pregeneration:**

1. On `ServerStartingEvent` (or on world join), acquire `ChunkyProvider.get().getApi()`; require `version() == 0`; abort the run otherwise.
2. Register both listeners *before* starting anything (the `EventBus` is unsynchronised). Make both listeners thread-safe and non-throwing — they run on Chunky's task threads and their exceptions are swallowed.
3. Validate the shape and pattern against `ShapeType`/`PatternType` yourself.
4. `startTask("minecraft:overworld", shape, cx, cz, rx, rz, pattern)`; a `false` return is an immediate failure.
5. Record the whole `GenerationProgressEvent` stream as raw data.
6. **Barrier: the first `GenerationProgressEvent` with `complete() == true` for that world.** A `GenerationCompleteEvent` without it means cancelled/paused/empty → invalid run.
7. Then force a full world save and wait for Minecraft-side quiescence. Chunky does neither.
8. Then independently verify the selection footprint is actually generated. Chunky cannot tell you a chunk failed.

---

## What I could not verify, and what would settle it

- **That spark 1.10.173 actually loads and runs on Minecraft 26.1.2.** The Java source is identical to 1.10.172 but is compiled against 26.2 mappings. Vanilla signature drift between 26.1.2 and 26.2 cannot be checked from published sources. *Settled by:* launching a 26.1.2 / NeoForge 26.1.2.95 instance with spark 1.10.173 and running `sparkc profiler info`; a `NoSuchMethodError`/`NoClassDefFoundError` at client setup would be immediate and unambiguous.
- **The exact behaviour of the async-profiler cross-side lock in singleplayer on Linux/macOS.** The code path is unambiguous, but I have not observed it. *Settled by:* a singleplayer smoke test on Linux with `backgroundProfiler` left at its default, asserting that `sparkc profiler start` fails, and then again with it set to `false`, asserting it succeeds.
- **Whether the spark chat strings are stable enough to parse.** They are hardcoded English literals in `SamplerModule`, not translation keys, so they are stable *within* a version but are not a contract. *Settled by:* deciding whether Laymark depends on them at all (see below).
- **The precise drain guarantee of `getChunkAtAsync` on NeoForge under a chunk-system-replacing mod** (Moonrise; `ChunkyNeoForge.ENABLE_MOONRISE_WORKAROUNDS` changes behaviour). Laymark's protected instrumentation closure probably excludes such mods, but candidate arms may not. *Settled by:* declaring chunk-system replacements out of scope for pregenerated phases, or by an empirical footprint audit after generation.
- **Whether spark writes anything else into `config/spark/` that could confuse a watcher.** `HeapAnalysisModule` uses the `heap` prefix, so `profile-*.sparkprofile` looks unique, but I did not enumerate every module. *Settled by:* filtering strictly on the activity-log `type == "Profiler"` field rather than on filenames.

---

## Corrections to the unvetted docs

Against `docs/benchmark-harness-plan.md` §"Spark adapter" and §"Chunky adapter":

| Claim | Verdict |
| --- | --- |
| `sparkc profiler start --timeout 45 --thread * --not-combined --interval 4 --save-to-file --comment <case-id>` | **Correct.** All flags real, semantics as described. `--timeout 45` is above the ≤10 rejection and the <30 warning. |
| "Spark accepts sequential bounded profiles and writes `.sparkprofile` artifacts" | **Correct**, and *sequential* is load-bearing: overlapping starts are silently dropped. |
| "The harness should wait for command completion and the new file rather than sleep" | **Half wrong.** The intent is right; "command completion" does not exist. Brigadier returns before spark does anything and the completion future is discarded. Use `activity.json`. |
| "Treat Spark's public statistics interface and command surface as the supported seams" | **Correct**, and now proven: the public API has no lifecycle at all. |
| "obtain Chunky's public API through `ChunkyProvider.get().getApi()`" | **Correct on NeoForge**, but `ChunkyProvider` is outside Chunky's documented/javadoc'd API; the wiki documents only the Bukkit `ServicesManager` route. |
| "verify the supported API version" | **Correct.** `version()` returns `0` in 1.5.4; the wiki commits to bumping it on breaking changes. |
| "Chunky 1.5.4 can emit `GenerationCompleteEvent` before its last outstanding asynchronous chunk operations drain" | **Confirmed**, with mechanism: `GenerationTask.run` fires it straight after the dispatch loop without joining up to `chunky.maxWorkingCount` (default 50) in-flight futures. |
| "Use `GenerationProgressEvent.complete() == true` as the primary completion barrier" | **Confirmed as the correct barrier**, and it is genuinely a drain barrier because it is emitted from inside the last chunk future's completion callback. |
| "then await a world save and a Minecraft-side quiescence/readiness check" | **Correct and necessary.** Chunky never saves. |
| "Treat task cancellation … as invalid runs" | **Correct and implementable**, via "complete event with no preceding progress-complete". Note `GenerationTask.isCancelled()` is *not* the signal — it is `true` after normal completion too. |
| "Treat … exceptional chunk futures as invalid runs" | **Right intent, not implementable through Chunky.** `whenComplete` discards the throwable entirely and still counts the chunk as done. Laymark must verify the footprint itself. |
| Chunky adapter, unstated | **Missing:** `startTask` silently accepts invalid shapes (→ square) and patterns (→ region); the world key must be the canonical `minecraft:overworld` or the task map leaks. |
| Spark adapter, unstated | **Missing:** on Windows spark has no async-profiler binary and falls back to a `ThreadMXBean` sampler — much heavier, and its cost scales with the arm's thread count. |
| Spark adapter, unstated | **Missing:** spark's background profiler is enabled by default on the *integrated* server in singleplayer, contaminating every run and, on Linux/macOS, blocking client captures outright. |

---

## Decisions needed from Mia

1. **Pin Spark 1.10.173 or 1.10.172 for the 26.1.2 fixture?**
   Both have byte-identical Java source; 1.10.173 is compiled against Minecraft 26.2 / NeoForge 26.2.0.1-beta, 1.10.172 against 26.1 / 26.1.0.1-beta. Lucko lists both as supporting 26.1.2.
   *Recommendation:* keep 1.10.173 as the map already specifies, but add a mandatory startup self-check that runs `sparkc profiler info` once and fails the instance closed on any linkage error. *Trade-off:* 1.10.172 is the build actually compiled against 26.1 and is strictly the lower-risk pin, but diverges from the map's stated tuple and from the loader-portability doc, and would need re-deciding when Fabric lands.

2. **Should Laymark parse spark's chat output at all, or rely solely on `activity.json`?**
   `activity.json` proves a capture *finished*; only the chat channel reveals that a capture was *refused* (`Profiler is already running!`, `The specified timeout is not long enough…`, `There isn't an active profiler running.`).
   *Recommendation:* use `activity.json` as the sole authoritative artifact binding, and hook client chat for `[⚡]`-prefixed messages purely as a fast-fail diagnostic, with a version-pinned string table that fails soft (log, don't crash) when a string stops matching. *Trade-off:* the strings are hardcoded English literals, stable within a spark version but not a contract; without them, a refused capture is only detectable as a timeout, which costs one full scenario duration per failure.

3. **Does Laymark manage `config/spark/config.json` (`backgroundProfiler: false`) itself, or require it as a documented precondition?**
   Left at its default, spark runs a background profiler on the integrated server in every singleplayer session — measurable contamination everywhere, and a hard block on client captures on Linux/macOS.
   *Recommendation:* Laymark writes and verifies it as part of instance preparation, and refuses to run if the effective value is wrong. *Trade-off:* the runner then mutates a file the user may consider theirs. Mitigate the same way as mod swaps — journal it and restore on completion. The alternative, documenting it as a precondition, is not viable given the runner can never prompt.

4. **Do we lower `chunky.maxWorkingCount` for benchmark runs?**
   It bounds the in-flight window that makes `GenerationCompleteEvent` premature (default 50).
   *Recommendation:* leave it at 50 and rely on the `GenerationProgressEvent.complete()` barrier, which is already exact. *Trade-off:* lowering it would narrow the window between "loop done" and "work drained", but that window is not something Laymark waits on anyway, and a non-default value makes generation throughput incomparable with any other measurement — actively harmful if generation throughput is ever itself the scenario under test.

5. **How hard is the independent post-generation verification?**
   Chunky cannot report failed chunks, so "pregeneration succeeded" is unverified unless Laymark checks.
   *Recommendation:* for 0.x, sample-verify — check chunk status on a deterministic subset of the selection (corners, centre, and a fixed-stride sample) rather than every chunk, and invalidate the run on any miss. *Trade-off:* a full footprint audit is exact but costs a second pass over potentially tens of thousands of chunks and would itself perturb the warmed state the next phase depends on; sampling can miss a sparse failure.

6. **Does the sampled/unsampled pass split need to be stricter than the plan states, given the Windows Java-sampler fallback?**
   Spark overhead is not a constant on Windows; it scales with the arm's thread count and stack depth.
   *Recommendation:* make it a schema-level rule — record `samplerEngine` in every result, refuse to compare or pool results across engines, and mark all metrics from a sampled pass `diagnostic: true` so they can never reach a score. *Trade-off:* one more required field and one more validity gate, and it means a Linux CI run and a Windows developer run produce results that are formally incomparable — which is correct, but will surprise.
