# Performance benchmark harness: source-backed design notes

Research snapshot: 2026-08-16. The installed NeoForge pack contains Spark
1.10.173 and Chunky 1.5.4. The recommendations below are based only on upstream
project documentation, source code, or specifications. Cross-loader findings
and the exact Fabric tuple are in
[the loader-portability research](./loader-portability-research.md).

## Conclusion

Build the harness as a shared Minecraft runtime with thin NeoForge and Fabric
ports plus an external run controller, but do **not** fork Spark initially.
Spark answers “where did JVM/CPU time go?” It does not answer “how long did the
GPU execute this frame?” or “when did the frame reach the display?” Those are
separate measurements with different clocks and failure modes.

The useful measurement stack is:

| Channel | Preferred measurement | What it answers |
| --- | --- | --- |
| JVM/CPU attribution | Spark client execution profile | Which Java/native call stacks occupied sampled client or worker threads? |
| Application frame pacing | Harness monotonic frame timestamps | What were frame-time median, tails, and stutters as experienced by the game loop? |
| GPU execution | OpenGL timer-query ring in the harness | How long did the GPU take to complete the bracketed GL work? |
| Windows presentation | PresentMon, targeted by the Minecraft PID | Which frames were presented/displayed/dropped, and what GPU/display latency did Windows observe? |
| Chunk preparation | Chunky API progress plus Minecraft-side readiness checks | How long did deterministic region generation/streaming take, and when was the test scene actually ready? |

Keep all channels in separate artifacts and join them with a harness-generated
run ID, scenario ID, and monotonic start/end markers. A combined viewer can be
added later without coupling data collection to a Spark fork.

## 1. What Spark 1.10.x measures

Spark's execution profiler is a statistical **CPU call-stack sampler**. Upstream
describes two engines: async-profiler on Linux/macOS and a Java sampler using
`ThreadMXBean`; async-profiler is not supported on Windows. It samples stacks
and aggregates them into a call graph rather than tracing every call
([Spark README](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/README.md#cpu-profiler),
[Java sampler source](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/sampler/java/JavaSampler.java)).
The default execution sampling interval is 4 ms
([`SamplerMode`](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/sampler/SamplerMode.java#L27-L45)).

### Client command and thread scope

On NeoForge, Spark registers `sparkc` and `sparkclient` as client commands
([NeoForge client plugin](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-neoforge/src/main/java/me/lucko/spark/neoforge/plugin/NeoForgeClientSparkPlugin.java#L67-L74)).
The default client profile follows Minecraft's game/render thread, not every
thread
([client integration](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-minecraft/src/main/java/me/lucko/spark/minecraft/plugin/MinecraftClientSparkPlugin.java#L40-L83)).
That default is appropriate for many render-main-thread bottlenecks, but it can
miss material work in chunk builders, C2ME executors, driver helper threads, or
other worker pools. For pack comparisons, capture both:

- a default game-thread profile for readable attribution; and
- an all-thread profile (`--thread * --not-combined`) when the scenario is
  explicitly about chunk generation/streaming or parallel rendering work.

Spark accepts a duration, sampling interval, one or more thread selectors, and
thread grouping options. A timeout must be greater than 10 seconds, and Spark
warns below 30 seconds
([`SamplerModule.profilerStart`](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L158-L259)).
A suitable automated command is therefore:

```text
/sparkc profiler start --timeout 45 --thread * --not-combined --save-to-file
```

Although `--save-to-file` is not offered by tab completion for `start`, the
timeout completion path explicitly reads and honors the flag
([timeout/export path](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L275-L298)).
An alternative is to start without a timeout and end the exact harness window
with `/sparkc profiler stop --save-to-file`.

### Sequential profiles in one launch

Spark permits only one active sampler. Its container is an atomic single slot
and rejects a second active sampler
([`SamplerContainer`](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/sampler/SamplerContainer.java)).
The command layer also reports the existing foreground sampler rather than
starting another one. When a timed sampler finishes, Spark clears the active
slot via its completion future; foreground profiles can therefore run
sequentially in a single Minecraft launch
([completion handling](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L263-L298)).

Do not start the next scenario merely because its timeout elapsed. Wait until
Spark emits “save complete” or until a new `.sparkprofile` exists and its size
is stable. The active slot is cleared and export callbacks are asynchronous, so
the next profile can become startable slightly before the preceding file write
has completed.

With `--save-to-file`, Spark writes raw profile protobuf data to
`config/spark/profile-YYYY-MM-DD_HH.mm.ss.sparkprofile`; upload failures also
fall back to a local file
([export implementation](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/command/modules/SamplerModule.java#L420-L461),
[NeoForge config directory](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-neoforge/src/main/java/me/lucko/spark/neoforge/NeoForgeSparkMod.java#L38-L64),
[filename construction](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-common/src/main/java/me/lucko/spark/common/SparkPlatform.java#L271-L281)).
The official viewer accepts local `.sparkprofile` files by drag-and-drop
([Spark viewer](https://spark.lucko.me/)). The controller should immediately
rename/copy each completed file to a deterministic run/scenario name, because
Spark's own name contains only wall-clock seconds.

### API limitation

Spark's supported API exposes process/system CPU usage, TPS, MSPT, garbage
collector statistics, and placeholders. It does **not** expose sampler
start/stop/completion/export controls
([public `Spark` API](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/spark-api/src/main/java/me/lucko/spark/api/Spark.java)).
The harness should invoke the registered client command and observe the output
artifact instead of linking to Spark's internal `spark-common` sampler classes.
This keeps the integration on the supported surface and avoids maintaining an
internal-API shim. On NeoForge 26.1 the concrete call is
`ClientCommandHandler.runCommand("sparkc profiler start --timeout 45 --save-to-file")`
([NeoForge client-command execution](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/ClientCommandHandler.java#L178)).

### Spark is not a GPU profiler

Spark can identify CPU time spent preparing rendering commands and can show a
render thread blocked in a driver/swap call when that happens to be sampled. It
cannot measure shader execution, rasterization, GPU queue occupancy, a frame's
actual GPU duration, or display time. This follows directly from its sampling
inputs (`ThreadMXBean`/async-profiler) and its public metrics; neither reads GL
timer queries, GPU counters, nor presentation telemetry. Treat a Spark profile
as CPU attribution alongside, not instead of, frame/GPU measurements.

## 2. Frame-time and GPU measurement

### One whole-frame boundary on both loaders

NeoForge 26.1 provides `RenderFrameEvent.Pre` and `.Post` immediately around
`GameRenderer.render`
([event definition](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/event/RenderFrameEvent.java),
[posting hooks](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/ClientHooks.java#L880)).
Fabric 26.1.2's `LevelRenderEvents.START_MAIN/END_MAIN` is narrower: its own
documentation places the start after sky rendering and chunk uploads, and the
end before clouds, weather, late debug, and framebuffer combination
([pinned Fabric event definitions](https://github.com/FabricMC/fabric/blob/f9468776b662dd2ab7875e9cdcdf2b653171309d/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.java#L88-L223)).
Do not use those as Fabric's headline equivalent. Put one required client Mixin
around vanilla `GameRenderer.render(DeltaTracker, boolean)` in shared source and
compile it into both artifacts. On NeoForge, validate its timing/order against
`RenderFrameEvent.Pre/Post`; fail startup if the version-pinned injection does
not apply exactly once.

Record `System.nanoTime()` at the shared start and end callbacks. Successive
start timestamps provide total application-frame cadence; each start–end pair
provides CPU render-submission duration. The cadence series is the primary
source for median/p95/p99 frame
time, 1%/0.1% tails, and counts above fixed budgets such as 16.67 and 33.33 ms.
It includes CPU work plus waits imposed by the cap, VSync, or the driver, so
each scenario must state whether it is an uncapped throughput test or a capped
frame-pacing test.

For GPU execution, use LWJGL's bindings for OpenGL timer queries. The
Khronos specification defines `GL_TIME_ELAPSED`/`GL_TIMESTAMP` queries as GPU
pipeline timing: elapsed results are nanoseconds and become available
asynchronously after prior GL work has been realized
([`ARB_timer_query` overview and semantics](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt),
[LWJGL `GL33C`](https://javadoc.lwjgl.org/org/lwjgl/opengl/GL33C.html)).

Important rules for a trustworthy harness:

- Create and issue queries only on the render thread while its GL context is
  current.
- Prefer a pair of `GL_TIMESTAMP` query counters at the shared whole-frame
  start/end callbacks. Subtract the results to bracket the same rendering interval in every
  candidate. Unlike `GL_TIME_ELAPSED`, timestamp pairs do not claim the one
  active query slot for that target and are less likely to collide with another
  mod's instrumentation.
- Use a ring of query objects and consume a result several frames later after
  `GL_QUERY_RESULT_AVAILABLE`; never call `glFinish` and never block waiting at
  the end of each frame. Khronos explicitly notes that immediate waiting stalls
  the pipeline and recommends delayed collection
  ([timer-query examples](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt#L296-L385)).
- Preserve 64-bit nanosecond results. Record unsupported/zero-bit counters as
  unavailable rather than zero performance cost.
- Remember that a whole-frame GL query measures completion of the enclosed GL
  command stream, not presentation, display scanout, input latency, or work
  outside the bracket. Highly pipelined GPUs and OS scheduling still introduce
  run-to-run variance
  ([timer-query caveats](https://registry.khronos.org/OpenGL/extensions/ARB/ARB_timer_query.txt#L477-L515)).

This is enough for comparative GPU timing without implementing a general GPU
profiler. It requires OpenGL 3.3 or `ARB_timer_query`; report unsupported
contexts instead of silently substituting CPU time. Per-render-pass queries can
be added later, cautiously, because intrusive instrumentation can change the
workload.

### Windows PresentMon

Use PresentMon as the canonical external frame-presentation trace on Windows.
It consumes Windows ETW presentation data across DirectX, OpenGL, and Vulkan
and reports CPU, GPU, and display durations/latencies
([PresentMon overview](https://github.com/GameTechDev/PresentMon/blob/f57eb474371c635ff2be620c04ca47400ca1b81a/README.md)).
Its console capture can target a PID, write CSV, stop after a duration, and
terminate after the timed capture:

```text
PresentMon.exe --process_id <minecraft-pid> --output_file <scenario.csv> --timed <seconds> --terminate_after_timed --no_console_stats
```

The official CLI documents those switches and records one CSV row per rendered
and presented frame, including present mode, dropped/displayed state, CPU busy
and wait, GPU busy and wait, display latency, and displayed time
([console CLI and CSV schema](https://github.com/GameTechDev/PresentMon/blob/f57eb474371c635ff2be620c04ca47400ca1b81a/README-ConsoleApplication.md)).
Prefer PID targeting over `javaw.exe`, since another Java process would
otherwise contaminate the capture. Have the harness write its own PID into its
ready marker before the controller starts PresentMon.

PresentMon has two relevant official caveats:

- OpenGL/Vulkan applications have less presentation instrumentation;
  `CPUFramePacingStall` is always zero and `CPUFrameTime` plus dependent latency
  calculations may be slightly less accurate.
- With Hardware-Accelerated GPU Scheduling enabled, GPU execution timings can
  be later/larger than the true work duration (the project gives roughly
  0.5 ms as an example in a GPU-bound case).

Both caveats are documented in the upstream troubleshooting section
([PresentMon limitations](https://github.com/GameTechDev/PresentMon/blob/f57eb474371c635ff2be620c04ca47400ca1b81a/README.md#troubleshooting)).
Record HAGS, display mode, refresh rate, resolution, fullscreen/windowed state,
VSync, FPS cap, driver version, GPU clocks/power mode, and temperature with the
run. Keep these constant across A/B.

The OpenGL query and PresentMon are complementary. The query gives low-overhead
in-context GPU execution for the exact bracket. PresentMon tells whether the
frame was actually presented/displayed and exposes queuing/display behavior.
If they disagree, do not average them; diagnose pipeline/queue/presentation
effects.

### Why a Spark fork is not warranted

A fork would still need to implement the timer-query and/or PresentMon channels
above. Putting those values inside Spark would not make its sampling call tree
a GPU trace, and Spark's supported API does not provide a natural profiler
extension point. A separate harness also avoids tying test orchestration to
Spark internals and keeps upstream Spark replaceable.

Reconsider a fork only if a single Spark-viewer artifact is a hard product
requirement and the maintenance/licensing cost is accepted. Spark core is
GPLv3, while only `spark-api` is MIT
([upstream licensing statement](https://github.com/lucko/spark/blob/5aa77aa46b6d79772233343d3c5209dc7754ccbc/README.md#license)).
Even then, prototype an offline result merger or viewer first.

## 3. Chunky for deterministic preparation

Chunky's supported API version is currently `0`. It provides `isRunning`,
`startTask(world, shape, centerX, centerZ, radiusX, radiusZ, pattern)`, pause,
continue, cancel, and progress/completion listeners
([`ChunkyAPI`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/api/ChunkyAPI.java),
[developer API guide](https://github.com/pop4959/Chunky/wiki/Developer-API)).
On NeoForge the common provider is initialized when the integrated server
starts; `ChunkyProvider.get().getApi()` reaches the API in the current source
([provider](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/ChunkyProvider.java),
[NeoForge startup](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/neoforge/src/main/java/org/popcraft/chunky/ChunkyNeoForge.java#L50-L73)).
Register listeners before starting a task and verify `api.version() == 0`.

The equivalent command surface accepts the world/dimension, shape, block
center, and one or two radii:

```text
/chunky start <world> <shape> <centerX> <centerZ> <radiusX> [radiusZ]
```

The official reference documents this syntax
([Chunky commands](https://github.com/pop4959/Chunky/wiki/Commands)). Use a
fixed explicit pattern for every run. The default `region` pattern uses a
center-out region ordering and Hilbert traversal for locality; `concentric`
also gives a clear center-out ordering
([pattern definitions](https://github.com/pop4959/Chunky/wiki/Patterns)).

### Completion caveat in 1.5.4

Do **not** treat `GenerationCompleteEvent` alone as “every chunk future is
finished.” In the current implementation, the scheduling loop allows up to 50
outstanding asynchronous chunk operations and fires `GenerationCompleteEvent`
after the iterator loop exits without first draining that semaphore. The final
callbacks can therefore still be running
([`GenerationTask.run`](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L116-L171)).
The same event is also fired on stopped/cancelled exits, and its public payload
contains only the world identifier.

Use `GenerationProgressEvent.complete() == true` as the primary “all scheduled
items accounted for” barrier, then request/await a world save and a short
Minecraft-side quiescence/readiness barrier before beginning a warm render
scenario. The progress event exposes count, completion, percentage, ETA, rate,
and current chunk coordinates
([progress event](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/api/event/task/GenerationProgressEvent.java)).
Still inspect logs: Chunky's completion callback increments progress even when
an asynchronous operation completes exceptionally, because the throwable is
not used to reject the update
([generation callback](https://github.com/pop4959/Chunky/blob/ab45b8b3a4ada40f69fbdb3af63d2a7004ce82a1/common/src/main/java/org/popcraft/chunky/GenerationTask.java#L142-L158)).

### What Chunky does not make deterministic

Chunky does not choose the world seed or generator settings; it asks the
existing world/server to generate or load the selected chunks. The fixed seed,
Minecraft/NeoForge version, datapacks, dimensions, generator options, and
world template must therefore be owned by the harness/controller.

For a true A/B cold comparison, have A and B each create a separate fresh world
from the same fully recorded seed and world-generation inputs. Never run B on
the world that A has already modified. For the paired warm comparison,
pre-generate the same region in each candidate's own newly created world, save
it, then execute the same player/camera route.

Also distinguish three workloads:

1. **Cold world generation/streaming** — fresh region, scripted movement;
   measures integrated-server generation, IO, networking, mesh building, and
   rendering together.
2. **Existing-chunk streaming** — the same region already exists on disk, but
   client chunks/meshes are not resident.
3. **Warm steady rendering** — required chunks and client meshes have been
   loaded, the camera is settled, and no generation/pregeneration task is
   running.

Chunky is excellent for preparing workload 2 and recording generation
throughput. It does not by itself preload client render meshes, so immediately
finishing a Chunky task is not yet workload 3. Script the camera route once,
wait for client chunk/mesh readiness and stable frame timing, then begin the
warm render sample.

## 4. Modrinth App launch boundary

The installed Modrinth App version is 0.17.10, and that release intentionally
generates instance shortcuts using:

```text
modrinth://launch/instance/<internal-instance-id>?singleplayer_world=<urlencoded-world-id>
```

The official shortcut builder creates exactly this URL and makes server and
singleplayer targets mutually exclusive
([0.17.10 shortcut source](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app/src/api/shortcuts/mod.rs)).
The deep-link parser recognizes `/launch/instance/{id}`, decodes
`singleplayer_world`, and emits a launch command
([0.17.10 handler source](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/api/handler.rs#L53-L92)).
The app frontend dispatches that command to its singleplayer-world launch flow
([0.17.10 launch dispatch](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/apps/app-frontend/src/App.vue#L1142)).
The launcher passes that value into Minecraft's `${quickPlaySingleplayer}`
argument
([0.17.10 argument substitution](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/args.rs#L397)).
Modrinth marks built-in singleplayer quick play as available from snapshot
23w14a onward, so Minecraft 26.1 is within the supported range
([0.17.10 quick-play version gate](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/launcher/quick_play_version.rs#L36-L60)).

The controller may use the instance-launch part, but **must not pass
`singleplayer_world` for a benchmark run**. The harness needs to apply and
verify its settings before it creates a new deterministic world; opening an
existing save from the launcher bypasses that ordering and cannot represent
the ungenerated-chunk phase. The path component is the app's **internal
instance ID**, not necessarily the display name; the instance model stores
`id`, filesystem `path`, and `name` separately
([0.17.10 instance model](https://github.com/modrinth/code/blob/457254f2102005f315ca000f21c8fee0b3b9c722/packages/app-lib/src/state/instances/model/instance.rs#L6-L22)).
Use Modrinth App's own generated shortcut or retrieve and pin the internal ID;
do not infer it from “Lucent Optimizations.” Let Minecraft reach the title
screen and let the harness create the world in-process.

The deep link is source-backed and used by the app's shortcut feature, but it
does not appear in the public Help Center as a stable third-party automation
contract. Pin the tested Modrinth App version and keep a startup timeout/failure
path in the controller.

## 5. Runtime control of Minecraft and mod settings

### Vanilla settings need neither reflection nor mixins

Minecraft 26.1 ships unobfuscated class, method, field, and parameter names;
NeoForge documents that toolchain change in its
[26.1 release notes](https://github.com/neoforged/websites/blob/main/content/news/26.1release.md#removal-of-obfuscation).
In the exact installed Minecraft 26.1.2 / NeoForge 26.1.2.95 binaries,
`Minecraft.getInstance().options` is an `Options`, its public accessor methods
return mutable `OptionInstance<T>` objects, and `OptionInstance.set(T)` is
public. The generated 26.1.2 API surface lists the relevant
[`Options` accessors](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/Options.html)
and [`OptionInstance.get/set`](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/OptionInstance.html).
NeoForge's own 26.1 patch to `Options` changes pack and key-mapping integration,
not the option mutation contract
([NeoForge `Options` patch](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/patches/net/minecraft/client/Options.java.patch)).

The basic operation is therefore:

```java
Minecraft minecraft = Minecraft.getInstance();
minecraft.options.renderDistance().set(16);
int stored = minecraft.options.renderDistance().get();
```

`OptionInstance.set` validates the value and invokes that option's update
callback when the stored value changes while the client is running. It does
not persist by itself. A rejected ordinary range value can resolve to the
option's initial value rather than the nearest bound, so every requested value
must be read back; silently continuing would invalidate the preset.

Apply the whole transaction on the client/render thread, for example from a
client-tick handler or through `Minecraft.execute`. Several callbacks mutate
the level renderer or window synchronously, and `Window.updateVsync` explicitly
asserts the render thread. NeoForge's client tick event is posted from the
client tick hook
([`ClientTickEvent`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/main/java/net/neoforged/neoforge/event/tick/ClientTickEvent.java),
[`Window` API](https://aldak0.ru/javadoc/26.1.x/com/mojang/blaze3d/platform/Window.html)).

### Setting and side-effect matrix

| Setting | Public mutation | Required application barrier | Effective-value evidence |
| --- | --- | --- | --- |
| Render distance | `options.renderDistance().set(chunks)` | Its callback calls `LevelRenderer.needsUpdate`. In an integrated world the server polls the value each server tick and updates `PlayerList`; after changing it in-world, wait for both a server tick and client chunk convergence. `Options.save()`/`broadcastOptions()` is additionally needed only when a remote server must receive the new client view distance. | Record `renderDistance().get()` and `options.getEffectiveRenderDistance()`, which applies a remote server cap. For singleplayer also record `server.getPlayerList().getViewDistance()` after the barrier. |
| Simulation distance | `options.simulationDistance().set(chunks)` | No immediate renderer work. `IntegratedServer.tickServer` polls and applies it to `PlayerList`, so wait until the server-side value matches. | Stored option plus `server.getPlayerList().getSimulationDistance()`. |
| Graphics preset/mode | `options.graphicsPreset().set(GraphicsPreset.FAST/FANCY/FABULOUS)` | The callback applies a bundle of render distance, simulation distance, AO, clouds, particles, mipmaps, entity distance/shadows, biome blend, filtering, and other quality values. Apply it **before** explicit overrides; those overrides intentionally turn the preset to `CUSTOM`. Because mipmaps/filtering can change, use the texture-reload barrier below. [`GraphicsPreset.apply`](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/GraphicsPreset.html) exposes the cascade. | Read back the preset and every expanded setting, not just the preset enum. |
| VSync | `options.enableVsync().set(boolean)` | Callback calls `Window.updateVsync`; execute on render thread. Do not mix this with uncapped throughput tests. | Stored option plus the run's measured frame cadence. `Window` has no public VSync getter, so a successful callback/readback is the API-level check. |
| FPS limit | `options.framerateLimit().set(fps)` (`260` is vanilla “unlimited”) | Callback immediately updates `FramerateLimitTracker`. Also set inactivity throttling appropriately and keep the window focused. | `options.framerateLimit().get()` and `minecraft.getFramerateLimitTracker().getFramerateLimit()`; record its throttle reason too. |
| GUI scale | `options.guiScale().set(scale)` (`0` is auto) | Callback calls `Minecraft.resizeGui`; wait through the resize callback before sampling. | Stored requested value plus `Window.getGuiScale()` and GUI-scaled width/height. |
| Fullscreen | `options.fullscreen().set(boolean)` | Callback requests a toggle. Wait until the window applies the mode change and resize events settle. `exclusiveFullscreen()` is marked restart-required and should be a process-level preset, not changed between scenarios. | `Window.isFullscreen()`, logical `getScreenWidth/Height()`, and framebuffer `getWidth/Height()`; the latter pair is essential on HiDPI. |
| Windowed resolution | `minecraft.getWindow().setWindowed(width, height)` | Public, but it also leaves fullscreen. Invoke on the render thread and wait for resize/framebuffer callbacks. `Options.overrideWidth/overrideHeight` are startup overrides, not the runtime mechanism. | Record both logical window and framebuffer dimensions after they stop changing. |
| Fullscreen video mode | `Window.setPreferredFullscreenVideoMode(Optional<VideoMode>)`, then `changeFullscreenVideoMode()` | Select a mode exposed by the current `Monitor`; wait for mode and resize completion. | Preferred mode, `Window.getRefreshRate()`, fullscreen state, and both size pairs. |
| Entity distance | `options.entityDistanceScaling().set(double)` | Renderer reads it at runtime; callback also changes the graphics preset to `CUSTOM`. Give visibility/culling a settling window. | Stored value and deterministic entity counts/scene metadata. |
| Particles | `options.particles().set(ParticleStatus)` | Runtime-read; callback changes the graphics preset to `CUSTOM`. Reset the scenario's particle-producing world state before each repetition. | Stored enum and, if added later, emitted/rendered particle counters. |
| Clouds | `cloudStatus().set(CloudStatus)` and `cloudRange().set(blocks)` | Status is runtime-read; range marks the cloud renderer for rebuild. Wait at least one completed render after applying. | Both stored values; also pin weather, time, and dimension. |
| Biome blend | `options.biomeBlendRadius().set(radius)` | Callback calls `LevelRenderer.allChanged`, causing a chunk-render rebuild. Wait for the same mesh-readiness/stability barrier used after chunk streaming. | Stored radius and renderer-settled marker. |
| Mipmaps/filtering | `mipmapLevels().set(level)`, `maxAnisotropyBit().set(bit)`, and `textureFiltering().set(method)` | Direct setters are not the complete Video Settings “Apply” path. Mirror `VideoSettingsScreen.removed`: call `minecraft.updateMaxMipLevel(...)`, then await `minecraft.delayTextureReload()` before warm-up. Filtering also resets renderer sampling. The method is visible in the current [`VideoSettingsScreen` API](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/gui/screens/options/VideoSettingsScreen.html). | Stored values, `options.maxAnisotropyValue()`, and successful completion of the reload future. |
| FOV | `options.fov().set(degrees)` | Callback marks the level renderer for update. Pin `fovEffectScale()` as well so movement effects do not alter the camera projection. | Stored FOV and projection/camera metadata emitted with the scenario. |
| Resource packs | Change `minecraft.getResourcePackRepository().setSelected(ids)`, then call `options.updateResourcePacks(repository)` | `updateResourcePacks` records the selected IDs, saves options, and starts `minecraft.reloadResourcePacks()` when selection changed. For an explicit harness transaction, call/retain a reload future and await it before world creation. | Compare `PackRepository.getSelectedIds()` to the requested ordered set and record pack hashes. |

`Options.save()` serializes the current values to `options.txt` and then calls
`broadcastOptions()` if connected. It is persistence/network publication, not
a substitute for the callbacks and asynchronous barriers above. Snapshot the
original options and selected packs, apply scenarios in memory, restore the
snapshot on the client thread, await the same barriers, and save once at clean
shutdown. The external controller should additionally back up `options.txt`
before launch and restore it after a crash, because a crash can bypass the
in-game restoration transaction.

### Sodium 0.9.2-alpha.4 and Sodium Extra 0.9.3

The installed Sodium tag is
[`mc26.1.2-0.9.2-alpha.4`](https://github.com/CaffeineMC/sodium/tree/ae0208a97ed01d18ee7a3713a50a4870b72de856).
Sodium's supported Config API is designed for mods to register their own pages
and options. Its `ConfigState` provides constrained **read** methods to dynamic
value providers; it is not a global “find and mutate any Sodium option” API
([official Config API guide](https://github.com/CaffeineMC/sodium/wiki/CaffeineMC-Maven-%26-Config-API),
[`ConfigState`](https://github.com/CaffeineMC/sodium/blob/ae0208a97ed01d18ee7a3713a50a4870b72de856/common/src/api/java/net/caffeinemc/mods/sodium/api/config/ConfigState.java)).
Sodium's screen binds the vanilla settings above back to vanilla `Options`, so
the harness should continue to use those public vanilla accessors.

Sodium does have public Java methods named `SodiumClientMod.options()` and
`SodiumOptions.writeToDisk`, and its quality/performance/advanced fields are
public, but they live under `net.caffeinemc.mods.sodium.client`, not the
supported `net.caffeinemc.mods.sodium.api` surface
([client singleton](https://github.com/CaffeineMC/sodium/blob/ae0208a97ed01d18ee7a3713a50a4870b72de856/common/src/main/java/net/caffeinemc/mods/sodium/client/SodiumClientMod.java#L64-L72),
[`SodiumOptions`](https://github.com/CaffeineMC/sodium/blob/ae0208a97ed01d18ee7a3713a50a4870b72de856/common/src/main/java/net/caffeinemc/mods/sodium/client/gui/SodiumOptions.java)).
Writing those fields directly also bypasses the UI's apply flags/rebuild hooks,
and options such as the no-error GL context are startup-only. Treat Sodium-only
settings as a version-pinned adapter: prefer pre-launch `sodium-options.json`
generation and a separate process; if runtime changes are genuinely useful,
mirror the exact version's apply hooks, verify renderer state, and expect the
adapter to change with Sodium.

Sodium Extra similarly exposes `SodiumExtraClientMod.options()` and a mutable
`SodiumExtraGameOptions` whose public `writeChanges()` persists JSON, but these
are implementation packages rather than a separately supported API
([0.9.3 singleton](https://github.com/FlashyReese/sodium-extra/blob/56da5597c5b2cceef82bc7bab64aece0a314640b/common/src/main/java/me/flashyreese/mods/sodiumextra/client/SodiumExtraClientMod.java#L42-L48),
[config storage](https://github.com/FlashyReese/sodium-extra/blob/56da5597c5b2cceef82bc7bab64aece0a314640b/common/src/main/java/me/flashyreese/mods/sodiumextra/client/config/SodiumExtraGameOptions.java#L104-L132)).
Many fields are read live by mixins, while adaptive VSync, fullscreen recovery,
fog, and projection options have special setters or side effects in its Sodium
config integration
([0.9.3 option bindings](https://github.com/FlashyReese/sodium-extra/blob/56da5597c5b2cceef82bc7bab64aece0a314640b/common/src/main/java/me/flashyreese/mods/sodiumextra/client/config/SodiumExtraConfig.java)).
Use another version-pinned adapter and prefer process-level JSON configuration
for reproducible runs.

An accessor mixin is therefore **not needed for any vanilla setting in the
matrix**. For a third-party mod, first use its supported API; second use a
version-pinned config-file/process adapter; third, if access is the only
problem, use a narrow NeoForge Access Transformer, whose purpose is widening
class/member access
([NeoForge AT documentation](https://docs.neoforged.net/docs/advanced/accesstransformers/)).
Use an accessor mixin only when a required live object has no public/API/AT-safe
route, and an ordinary mixin only when behavior must be intercepted rather
than merely configured. Keep such adapters optional so the harness still runs
when Sodium or Sodium Extra is one of the mods being removed for an A/B test.

## 6. Exact cold-to-warm in-game state machine

The corrected benchmark flow launches the instance to the title screen and
creates its own world. Minecraft exposes a public
`Minecraft.createWorldOpenFlows()` and public
`WorldOpenFlows.createFreshLevel(...)`; the latter accepts an explicit save ID,
`LevelSettings`, `WorldOptions(seed, structures, bonusChest)`, and a dimensions
factory
([`Minecraft.createWorldOpenFlows`](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/Minecraft.html#createWorldOpenFlows()),
[`WorldOpenFlows.createFreshLevel`](https://aldak0.ru/javadoc/26.1.x/net/minecraft/client/gui/screens/worldselection/WorldOpenFlows.html#createFreshLevel(java.lang.String,net.minecraft.world.level.LevelSettings,net.minecraft.world.level.levelgen.WorldOptions,java.util.function.Function,net.minecraft.client.gui.screens.Screen)),
[`WorldOptions`](https://aldak0.ru/javadoc/26.1.x/net/minecraft/world/level/levelgen/WorldOptions.html)).

Use this state machine for every candidate process:

1. **Boot/title ready.** Wait for game load completion, no active world, no
   pending pack reload, and the harness client controller ready. Snapshot
   vanilla settings, selected resource packs, window state, and any enabled
   mod-adapter configs.
2. **Apply and verify the preset before world creation.** On the client/render
   thread, apply the graphics preset first, then explicit overrides. Apply
   window/fullscreen state, mipmap and pack reloads, and await all reload and
   resize futures/events. Read back every stored/effective value. Abort the run
   on a mismatch; do not create a world that would later be measured under a
   different preset.
3. **Create a deterministic fresh world.** Generate a unique save ID containing
   the run ID; refuse to reuse an existing directory. Call
   `createFreshLevel` with the configured seed, structures/bonus-chest flags,
   data configuration, game rules, difficulty, and explicit world-dimensions
   factory such as `WorldPresets::createNormalWorldDimensions`. Record all of
   those inputs. Do not pass a world through a Modrinth deep link.
4. **Join/readiness gate.** Wait for non-null client level, player, connection,
   and running integrated server; wait until the player is ticking and the
   server-side view and simulation distances equal the preset. The initial
   spawn area is necessarily generated during world startup, so choose cold
   benchmark coordinates far outside it. Establish a fixed spawn staging
   point, camera state, time/weather, and other scenario controls without
   visiting the target region.
5. **Cold profile.** Start Spark only after the readiness gate. Confirm its
   active marker, then teleport/move the player into the untouched target and
   run the scripted route/camera sequence. This measured interval includes
   generation, disk writes, integrated-server delivery, client streaming,
   mesh building, upload, and render stutter. Stop Spark, wait for the saved
   artifact to become stable, and close the cold measurement channels. Do not
   call Chunky before this phase.
6. **Prepare warm state.** With profiling stopped, use Chunky to pre-generate
   the configured region around the same target. Register listeners before
   starting. As established in section 3, require the progress-complete event,
   then an explicit server save and a quiescence barrier; do not trust the
   generic completion event alone. Move through the identical camera route to
   make client chunks and render meshes resident, return to the exact start
   pose, and wait for chunk/mesh activity plus frame times to stabilize.
7. **Warm profile.** Re-verify settings, server distances, resolution,
   position, yaw/pitch, selected packs, and that Chunky is idle. Start a new
   sequential Spark profile and the frame/GPU/presentation channels. Run the
   identical route for the identical duration, stop, and wait for every
   artifact to finish writing.
8. **Close and cleanly restore.** Save the world and disconnect, waiting until
   the integrated server stops and releases its `LevelStorageAccess` lock.
   Restore the settings snapshot on the client thread, await rebuild/reload
   barriers, and call `Options.save()`. A unique benchmark save may then be
   deleted through a newly acquired public `LevelStorageAccess.deleteLevel()`
   or, more robustly, by the external controller after the Minecraft process
   exits
   ([level-storage API](https://aldak0.ru/javadoc/26.1.x/net/minecraft/world/level/storage/LevelStorageSource.LevelStorageAccess.html)).

For another cold/warm repetition, create another unique fresh world from the
same seed; reopening the just-profiled world is warm-only. For another mod
stack, restart the process. If any requested setting is startup-only (for
example Sodium's GL-context choice or vanilla exclusive-fullscreen behavior),
put it in the process preset and never vary it within one launch.

## 7. Recommended experiment structure

### Scenario suite

Multiple profiles in one game launch are sensible. Define every scenario as
data containing at least:

- world-template hash, seed, dimension, XYZ, yaw, and pitch;
- route/camera motion and duration;
- resolution, fullscreen/windowed state, graphics preset, render/simulation
  distance, VSync, FPS cap, shaders/resource packs, and FOV;
- warm-up/readiness conditions and measured duration;
- expected Spark, in-process frame/GPU, PresentMon, and Chunky artifacts.

Run the entire suite automatically once the harness-created world is ready. Separate
measurement windows with an idle/cool-down/readiness barrier. Multiple
scenarios per JVM reduce operator work, but they are not independent
replicates: JIT compilation, caches, allocations, GC, and thermal state carry
forward. Repeat the suite across fresh process launches and counterbalance
scenario order where practical.

For each measured window, persist:

- exact mod filenames, hashes, resolved dependency closure, configs, Java/JVM
  arguments, launcher/driver/OS/hardware metadata;
- the `.sparkprofile`;
- per-frame application intervals and GPU query results;
- PresentMon CSV;
- Chunky progress/readiness events when applicable; and
- a small result manifest with run/scenario timestamps and validity flags.

Do not reduce the result to average FPS. At minimum report frame-time median,
p95, p99, 1%/0.1% tails, deadline misses, GPU-time distribution, displayed or
dropped frames, chunk completion/time-to-ready, CPU utilization, and the Spark
call-tree delta.

### A/B order and fresh/warm worlds

An immediate `A then B` pair is vulnerable to JIT, filesystem-cache, driver
cache, and temperature drift. Use paired repetitions with alternating or
randomized order (`AB`, `BA`, ...). A cold run gets a newly created world for
each candidate. A warm run is performed only on that candidate's own prepared
world.
Require the effect to be repeatable and larger than run-to-run noise before
accepting a mod.

Adding/removing a mod still requires a Minecraft process restart. What can be
batched in one launch is the set of positions, camera orientations, render
distances, and cold/existing/warm phases for one fixed mod set.

### Combination search

Use greedy forward selection against the **current accepted stack**, not only
against vanilla:

1. Benchmark every valid candidate closure against the same base stack.
2. Add the candidate with the largest repeatable, scenario-weighted benefit.
3. Re-benchmark every remaining candidate as `accepted stack + candidate`
   versus `accepted stack`.
4. Add only a candidate whose benefit remains positive and whose regressions
   stay inside explicit scenario budgets; repeat until none qualify.
5. Run a final leave-one-out pass (`final stack` versus `final stack - mod`) to
   expose redundancy and interactions hidden by greedy insertion order.

Store dependency libraries as part of the tested closure but score/attribute
the user-facing candidate as a unit. Where two candidates conflict or optimize
the same subsystem, record the interaction rather than treating their singleton
improvements as additive.

This design directly answers the intended questions: cold generation and
streaming, existing-chunk streaming, warm render performance, CPU attribution,
GPU execution, real presentation pacing, and whether benefits survive when
optimization mods are stacked.
