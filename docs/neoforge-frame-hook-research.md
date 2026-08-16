# NeoForge 26.1.2.95 whole-frame hook: what to bracket, and whether a Mixin is needed

Research date: 2026-08-16. Target: Minecraft 26.1.2 / Java 25 / NeoForge 26.1.2.95. Loader scope: NeoForge only for 0.x; Fabric seam noted throughout.

Companion: [`docs/minecraft-26.1.2-client-surfaces-research.md`](./minecraft-26.1.2-client-surfaces-research.md) covers the same client build for world creation, options, and readiness, and independently found the AFK framerate-throttle hazard restated in §5 below.

## Verdict

**No Mixin is needed on NeoForge, and the Mixin proposed by [`docs/loader-portability-research.md`](./loader-portability-research.md) would not have measured a whole frame anyway.**

Three findings, in order of importance:

1. **NeoForge's `RenderFrameEvent.Pre`/`.Post` and a Mixin at `HEAD`/`RETURN` of `GameRenderer.render(DeltaTracker, boolean)` bracket the *same* interval.** NeoForge's hooks are the statements immediately before and after the one and only call site of that method. The Mixin is pure redundancy on NeoForge — it adds a version-fragile injection to buy a difference of one virtual dispatch.

2. **That interval is not a whole frame on 26.1.** Mojang split client rendering into `update` → `extract` → `render`. `GameRenderer.extract(...)` builds the level and GUI render state and runs *before* `GameRenderer.render(...)`. A bracket around `render` alone misses `Minecraft.pauseIfInactive`, `ClientLevel.update`, `GameRenderer.update`, `Minecraft.pick`, all of `GameRenderer.extract` (including `LevelRenderer.extractLevel` and `extractGui`, which is where NeoForge fires `ScreenEvent.Render` and GUI-layer events), and `RenderSystem.executePendingTasks`. For a tool whose entire purpose is attributing per-frame CPU cost to mods, that is a large and mod-sensitive omission.

3. **Vanilla already publishes exactly the number Laymark wants, on a public accessor: `Minecraft.getFrameTimeNs()`.** It is latched inside `Minecraft.renderFrame(boolean)` as `Util.getNanos()` (after the main-target blit) minus `Util.getNanos()` (at the top of the `update` phase). It therefore covers `update` + `extract` + `gpuAsync` + `render` + `present`, and **excludes buffer swap and excludes the frame-limiter wait**. Mojang's own performance telemetry samples it. Reading it once per frame from NeoForge's `FlipFrameEvent` is a complete, zero-Mixin, zero-AT solution — and because `FlipFrameEvent` fires after the latch and before the frame limiter, Laymark's own sampling code sits entirely *outside* every interval it reports.

Recommended design in §6. Corrections owed to the trusted portability doc in §9.

---

## 1. Provenance

Everything below is read from shipped bytecode and from upstream source at a pinned commit. Nothing is recalled.

| Artifact | Identity | How used |
| --- | --- | --- |
| Minecraft 26.1.2 client jar | `https://piston-data.mojang.com/v1/objects/4e618f09a0c649dde3fdf829df443ce0b8831e65/client.jar`, 38 113 927 bytes, reached from [version manifest v2](https://launchermeta.mojang.com/mc/game/version_manifest_v2.json) → [`26.1.2.json`](https://piston-meta.mojang.com/v1/packages/edcfd100a4856650b6e9797bac8f7fd76821979e/26.1.2.json) (`javaVersion.majorVersion` = 25) | `javap -p -c` (JDK 25.0.4). 26.1 ships unobfuscated, so class/member names in the jar are the real ones. |
| NeoForge 26.1.2.95 | commit `ad038e822a142901aeb33b0eedde0a892588b662`, established from the published [`neoforge-26.1.2.95-changelog.txt`](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.1.2.95/neoforge-26.1.2.95-changelog.txt) (top entry `26.1.2.95 Backport to 26.1.x: Re-implement entity fluid interaction patches (#3303)`) matching the head of branch `26.1.x`. `26.1.2.95` is `<latest>`/`<release>` in [maven-metadata.xml](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml). | Event sources and `patches/` verbatim. |
| Fabric API | pinned ref `f9468776b662dd2ab7875e9cdcdf2b653171309d`, the same ref [`loader-portability-research.md`](./loader-portability-research.md) cites | Tree listing to establish absence of a whole-frame event. |
| Mixin | [FabricMC/Mixin](https://github.com/FabricMC/Mixin) — the fork NeoForge's `[[mixins]] behaviorVersion` property points at ([NeoForge modfiles docs](https://docs.neoforged.net/docs/gettingstarted/modfiles/#mixin-configuration-properties)) | `@At` injection-point semantics. |
| GLFW | [window guide](https://www.glfw.org/docs/latest/window_guide.html), [input guide](https://www.glfw.org/docs/latest/input_guide.html) | Swap-interval and timer semantics. |

Bytecode citations below give the class, the method, and the bytecode offset, so they can be re-derived with `javap -p -c -cp client.jar <class>`.

**The NeoForge documentation site does not document `RenderFrameEvent` or `FlipFrameEvent` at all.** A code search of `neoforged/Documentation` returns no hits for `RenderFrameEvent`, and the `docs/` tree has no rendering-events or game-loop page. The NeoForge source is therefore the only authority, and it is the authority used here.

---

## 2. What NeoForge 26.1.2.95 actually offers for a whole frame

### `net.neoforged.neoforge.client.event.RenderFrameEvent` — `.Pre` and `.Post`

Exact names, from [`RenderFrameEvent.java`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/event/RenderFrameEvent.java):

- `RenderFrameEvent` (abstract, extends `net.neoforged.bus.api.Event`), with nested `public static class Pre` and `public static class Post`.
- Payload: `DeltaTracker getPartialTick()`. Not cancellable. Client-physical-side only.

Fired from [`ClientHooks.fireRenderFramePre/Post`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/ClientHooks.java#L878-L899) onto `NeoForge.EVENT_BUS` (the game bus, not the mod bus).

The posting site is in [`patches/net/minecraft/client/Minecraft.java.patch`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/patches/net/minecraft/client/Minecraft.java.patch), hunk `@@ -1346,7 +_,9 @@`:

```
+        net.neoforged.neoforge.client.ClientHooks.fireRenderFramePre(this.deltaTracker);
         this.gameRenderer.render(this.deltaTracker, advanceGameTime);
+        net.neoforged.neoforge.client.ClientHooks.fireRenderFramePost(this.deltaTracker);
```

Two stale javadoc references worth knowing about, since both would mislead a reader who trusts them over the patch:

- `RenderFrameEvent`'s own javadoc says the frame is rendered via `GameRenderer#render(float, long, boolean)` — a signature that does not exist on 26.1.2 (see §4). It is a leftover from a pre-`DeltaTracker` Minecraft.
- `ClientHooks` says the hooks are called "in `Minecraft#runTick(boolean)`". They are in `Minecraft.renderFrame(boolean)`, which `runTick` calls. Confirmed from bytecode: `Minecraft.runTick(Z)V` invokes `renderFrame:(Z)V` at offset 420, and `GameRenderer.render` is invoked only inside `renderFrame` at offset 234.

### `net.neoforged.neoforge.client.event.FlipFrameEvent`

From [`FlipFrameEvent.java`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/event/FlipFrameEvent.java): empty payload, not cancellable, `NeoForge.EVENT_BUS`, logical client only. Fired at the very end of `RenderSystem.flipFrame(TracyFrameCapture)`, per [`patches/com/mojang/blaze3d/systems/RenderSystem.java.patch`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/patches/com/mojang/blaze3d/systems/RenderSystem.java.patch):

```
         dynamicUniforms.reset();
         Minecraft.getInstance().levelRenderer.endFrame();
+        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.FlipFrameEvent());
```

**Version floor:** `FlipFrameEvent` was added in build **26.1.2.73** ("Add flip frame event for managing per-frame GPU resources and rotating custom dynamic uniforms. (#3197)", per the 26.1.2.95 changelog). Depending on it means Laymark's `neoforge.mods.toml` must declare `versionRange` at or above `[26.1.2.73,)`; the pinned target `26.1.2.95` satisfies it.

### What is *not* a whole-frame bracket

- `RenderLevelStageEvent.*` — sub-stages of the level pass only (`AfterSky`, `AfterOpaqueBlocks`, `AfterTranslucent*`, `AfterWeather`, `AfterLevel`), fired from the `LevelRenderer` and `GameRenderer` patches.
- `ExtractLevelRenderStateEvent` — a **single** event fired inside the level extraction path ([`LevelRenderer.java.patch`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/patches/net/minecraft/client/renderer/LevelRenderer.java.patch)), not a Pre/Post pair. It cannot bracket the extract phase.
- `FrameGraphSetupEvent`, `SubmitCustomGeometryEvent`, `RenderGuiEvent`, `ScreenEvent.Render.*` — narrower still.
- `ClientTickEvent` — per tick, not per frame.

**There is no NeoForge event that starts before `GameRenderer.update`/`extract`.** `RenderFrameEvent.Pre` is the earliest per-frame hook NeoForge offers.

---

## 3. What a frame actually is on 26.1.2

From `javap -p -c` on `net/minecraft/client/Minecraft.class`, method `renderFrame(Z)V` (single `return`, at offset 513; no exception table). Sequence with profiler labels:

| Offset | Work | Profiler section |
| --- | --- | --- |
| 4 | `push("update")` | `update` |
| 17 | `deltaTracker.advanceRealTime(Util.getMillis())` | |
| 84–87 | `TimerQuery.beginProfile()` — only if F3 GPU-utilization entry enabled or metrics recording | |
| **100** | **`long t0 = Util.getNanos()`** ← start of vanilla's `frameTimeNs` | |
| 106–166 | `pauseIfInactive()`, `Window.updateFullscreenIfChanged()`, `ClientLevel.update()`, `GameRenderer.update(DeltaTracker, boolean)`, `pick(float)` | |
| 170 | `popPush("extract")` | `extract` |
| 189 | `GameRenderState.framerateLimit = framerateLimitTracker.getFramerateLimit()` | |
| 204 | **`GameRenderer.extract(DeltaTracker, boolean)`** | |
| 208–220 | `popPush("gpuAsync")`, `RenderSystem.executePendingTasks()`, `pop()` | `gpuAsync` |
| — | *NeoForge posts `RenderFrameEvent.Pre` here* | |
| **234** | **`GameRenderer.render(DeltaTracker, boolean)`** | (pushes `render` internally) |
| — | *NeoForge posts `RenderFrameEvent.Post` here* | |
| 238 | `push("present")` | `present` |
| 266 | `mainRenderTarget.blitToScreen()` — skipped if `windowRenderState.isMinimized` | |
| **270–276** | **`this.frameTimeNs = Util.getNanos() - t0`** ← latch | |
| 287 | `TimerQuery.endProfile()` | |
| 294 | `popPush("swapBuffers")` | `swapBuffers` |
| 313–324 | Tracy `upload()` / `capture(...)` if enabled | |
| **331** | **`RenderSystem.flipFrame(tracyFrameCapture)`** → `GpuDevice.presentFrame()` → `GLFW.glfwSwapBuffers(handle)`; then `dynamicUniforms.reset()`, `LevelRenderer.endFrame()`, then *NeoForge posts `FlipFrameEvent`* | |
| 335 | `popPush("frameLimiter")` | `frameLimiter` |
| **365** | **`FramerateLimiter.limitDisplayFPS(limit)`** — only if `GameRenderState.framerateLimit < 260` | |
| 369 | `popPush("fpsUpdate")`; `frames++` | `fpsUpdate` |
| 387–423 | `long t = Util.getNanos(); long d = t - lastNanoTime;` → `savedCpuDuration = d` (when TimerQuery active), `DebugScreenOverlay.logFrameDuration(d)`, `lastNanoTime = t` | |
| 427–462 | `gpuUtilization = frameProfile.get() * 100.0 / savedCpuDuration` | |

Inside `GameRenderer` (`javap` on `net/minecraft/client/renderer/GameRenderer.class`):

- `extract(DeltaTracker, boolean)` calls `extractWindow()`, `extractOptions()`, `LightmapRenderStateExtractor.extract(...)`, `extractCamera(...)`, **`LevelRenderer.extractLevel(...)`**, **`extractGui(DeltaTracker, boolean, boolean)`**. NeoForge's `ClientHooks.drawScreen` (which drives `ScreenEvent.Render.Pre/Post` and GUI layers) is patched into the `extractGui` path, not the render path — see the `profiler.push("screen")` hunk in [`GameRenderer.java.patch`](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/patches/net/minecraft/client/renderer/GameRenderer.java.patch).
- `render(DeltaTracker, boolean)` clears the main target, updates `GlobalSettingsUniform`, renders the lightmap, pushes `"world"` → `renderLevel(...)`, runs the post chain, then pushes `"gui"` → `GuiRenderer.render(...)` + `endFrame()`, then `SubmitNodeStorage.endFrame()`, `FeatureRenderDispatcher.endFrame()`, `CrossFrameResourcePool.endFrame()`. Exactly one `return`, at offset 445.

### Interval comparison

| Bracket | update / extract (incl. GUI extract) | GPU-async tasks | GUI **submission** | main-target blit | buffer swap (`glfwSwapBuffers`) | frame-limiter wait |
| --- | --- | --- | --- | --- | --- | --- |
| `RenderFrameEvent.Pre` → `.Post` | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Mixin `@At("HEAD")`/`@At("RETURN")` on `GameRenderer.render` | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| **`Minecraft.getFrameTimeNs()`** | ✓ | ✓ | ✓ | ✓ | ✗ | ✗ |
| Mixin `HEAD`→`RETURN` on `Minecraft.renderFrame(Z)` | ✓ | ✓ | ✓ | ✓ | ✓ | **✓ (bad)** |
| `FlipFrameEvent` → next `FlipFrameEvent` | ✓ | ✓ | ✓ | ✓ | ✓ | **✓ (bad)** |

Rows 1 and 2 are the same interval, which answers the ticket's central comparison directly.

---

## 4. Does the signature the trusted doc names still exist?

**Yes.** `net.minecraft.client.renderer.GameRenderer` declares `public void render(net.minecraft.client.DeltaTracker, boolean)`, descriptor `(Lnet/minecraft/client/DeltaTracker;Z)V`. `docs/loader-portability-research.md`'s named target is correct; `RenderFrameEvent`'s javadoc (`render(float, long, boolean)`) is not.

Two facts make Mixin-vs-event equivalence exact rather than approximate:

- **Exactly one caller.** Scanning every class in the client jar for a reference to `GameRenderer.render` yields a single invocation, at `Minecraft.renderFrame` offset 234. A Mixin on the callee therefore fires at exactly the sites NeoForge's hooks fire at — no extra call paths on either side.
- **Exactly one `RETURN`.** `GameRenderer.render` has a single `return` opcode. Mixin's `RETURN` injection point "searches for RETURN opcodes in the target method… every RETURN opcode will be returned and thus every natural exit from the method except for exception throws" ([`BeforeReturn.java`](https://github.com/FabricMC/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/points/BeforeReturn.java)); `HEAD` "returns the first instruction in the target method body… only returns a single insn in all circumstances" ([`MethodHead.java`](https://github.com/FabricMC/Mixin/blob/master/src/main/java/org/spongepowered/asm/mixin/injection/points/MethodHead.java)). So one `HEAD` fire and one `RETURN` fire per call.

The residual difference between the two brackets is one `invokevirtual` plus the callee's profiler `push("render")`. Both behave identically if `render` throws: neither emits an end sample (Mixin `RETURN` excludes exception throws; NeoForge's `fireRenderFramePost` is not in a `finally`). The self-check in §8 must therefore count unbalanced pairs rather than assume balance.

**Fragility note.** The Mixin would be pinned to a private-to-`Minecraft` call graph that Mojang has already reshaped once in this version line: the `render(float, long, boolean)` → `render(DeltaTracker, boolean)` change is visible in NeoForge's own stale javadoc, and the `update`/`extract`/`render` split is new pipeline architecture. NeoForge absorbs those churn events on Laymark's behalf; a Mixin does not.

---

## 5. Frame limiter, vsync, and where the bracket must sit

### The limiter is a spin-plus-park, and it is *after* the latch

`net.minecraft.client.FramerateLimiter.limitDisplayFPS(int)` (bytecode): computes `target = lastFrameTime + 1_000_000_000L / limit`, then loops on `remaining = target - System.nanoTime()`. While `remaining > averageOvershootNs + 500_000`, it calls `LockSupport.parkNanos(remaining - averageOvershootNs - 500_000)` and adapts `averageOvershootNs` (EWMA, 0.1/0.9, clamped to ≤ 2 ms). For the final sub-millisecond it **busy-spins on `Thread.onSpinWait()`**. So the limiter both parks *and* burns CPU on the render thread.

It is invoked at `renderFrame` offset 365, i.e. **after** `frameTimeNs` is latched at offset 276 and **after** `FlipFrameEvent`. Any bracket that ends at or before `FlipFrameEvent` excludes it. Any bracket that spans `renderFrame` end-to-end, or measures the interval between consecutive `FlipFrameEvent`s, includes it and is therefore a frame-*pacing* measurement, not a CPU-cost measurement.

It only runs at all when `GameRenderState.framerateLimit < 260` (260 is the "unlimited" sentinel).

### Vsync blocks inside the swap, which is also outside the latch

`RenderSystem.flipFrame` → `GpuDevice.presentFrame()`; the GL implementation `com.mojang.blaze3d.opengl.GlDevice.presentFrame()` is exactly `GLFW.glfwSwapBuffers(windowHandle)`. GLFW: "If the interval is zero, the swap will take place immediately when `glfwSwapBuffers` is called without waiting for a refresh. Otherwise at least interval retraces will pass between each buffer swap," and the guide notes an interval of zero is useful for benchmarking because it avoids measuring vertical-retrace wait ([window guide](https://www.glfw.org/docs/latest/window_guide.html)). `Window.updateVsync(boolean)` calls `RenderSystem.getDevice().setVsync(b)`.

Caveat that cannot be settled from source: with vsync on, drivers commonly apply back-pressure by blocking the *next* frame's first GL call rather than the swap, so vsync stalls can leak into `frameTimeNs` even though the swap itself is outside the bracket. **Run benchmarks with vsync off and the framerate limit unlimited**; do not rely on the bracket to exclude vsync. (`docs/minecraft-26.1.2-client-surfaces-research.md` §readback notes there is no public effective-value readback for vsync — it is request-only — so this must be asserted from the option value plus observed pacing, not confirmed from the driver.)

### Effective-cap throttling will silently corrupt an unattended run

`FramerateLimitTracker.getFramerateLimit()` returns, by `getThrottleReason()`:

| Reason | Effective cap | Trigger |
| --- | --- | --- |
| `NONE` | configured limit | — |
| `WINDOW_ICONIFIED` | **10** | `Window.isIconified()` |
| `LONG_AFK` | **10** | > 600 000 ms since last input |
| `SHORT_AFK` | **min(limit, 30)** | > 60 000 ms since last input |
| `OUT_OF_LEVEL_MENU` | **60** | no `level`, or a `screen`/`overlay` is present |

`latestInputTime` is refreshed only by `FramerateLimitTracker.onInputReceived()`, called only from `KeyboardHandler` and `MouseHandler`. `Options.inactivityFpsLimit` defaults to `InactivityFpsLimit.AFK`. A static-camera benchmark that drives the camera programmatically will therefore be clamped to 30 FPS at the 60-second mark. This corroborates the same finding in `docs/minecraft-26.1.2-client-surfaces-research.md`; both were derived independently from the same bytecode. `Minecraft.getFramerateLimitTracker()`, `getFramerateLimit()`, `getThrottleReason()`, and `isHeavilyThrottled()` are all public, so this is observable per frame without any hook.

### The two clocks vanilla itself keeps

- `Minecraft.frameTimeNs` (public getter `getFrameTimeNs()`): CPU frame time, latched as described. Sampled by `net.minecraft.client.telemetry.events.PerformanceMetricsEvent.takeSample()` alongside `Minecraft.getFps()` and heap usage — this is Mojang's own frame-cost metric.
- `Minecraft.savedCpuDuration` / `DebugScreenOverlay.logFrameDuration(...)`: the *full* interval between consecutive `fpsUpdate` points, **including** swap and limiter. This is what the F3 frame chart plots, and it is exactly the "naive frame interval" the ticket warns about. Do not use it for CPU cost.

**Clock identity.** `Util.getNanos()` is `Util.timeSource.getAsLong()`; on the client `Util.timeSource` is assigned in the `Minecraft` constructor from `RenderSystem.initBackendSystem(...)` → `GLX._initGlfw(...)`, whose supplier is `(long)(GLFW.glfwGetTime() * 1.0E9)`. `Minecraft.destroy()` restores `System::nanoTime`. So `frameTimeNs` is on the GLFW timer ("the number of seconds since the library was initialized with `glfwInit`"; "platform-specific time sources… typically have micro- or nanosecond resolution" — [GLFW input guide](https://www.glfw.org/docs/latest/input_guide.html)), **not** `System.nanoTime()`. `FramerateLimiter`, by contrast, uses `System.nanoTime()` directly. If Laymark takes its own timestamps and mixes them with `frameTimeNs`, use `Util.getNanos()` for both so all arithmetic is in one clock.

---

## 6. Recommended bracket for 0.x

**Primary metric — no Mixin, no AT:**

1. Subscribe to `FlipFrameEvent` on `NeoForge.EVENT_BUS` (client only).
2. In the handler, read `Minecraft.getFrameTimeNs()` and append it to a preallocated `long[]` ring buffer along with a monotonic frame counter.
3. Also record, per frame or per window, `Minecraft.getFramerateLimitTracker().getThrottleReason()`, `getFramerateLimit()`, `Minecraft.getFps()`, and `Window.isIconified()`/`isFocused()`, so a throttled or backgrounded capture is detectable after the fact.

Why `FlipFrameEvent` rather than `RenderFrameEvent.Post`: `frameTimeNs` is latched at offset 276, *after* `RenderFrameEvent.Post` fires. Sampling in `Post` reads the previous frame's value (usable, but off-by-one and easy to get wrong); sampling in `FlipFrameEvent` reads the current frame's. Crucially, `FlipFrameEvent` fires after the latch and before the next frame's latch begins, so **Laymark's sampling code is outside every interval it reports** — the reported number is not perturbed by the act of reporting it. That property is not available from any bracket built out of Laymark's own timestamps.

**Optional secondary metric — submit-phase decomposition, still no Mixin:** take `Util.getNanos()` in `RenderFrameEvent.Pre` and `.Post`. `submitNs = post - pre` is the `GameRenderer.render` cost; `frameTimeNs - submitNs` is update + extract + gpuAsync + present. This *does* place two `getNanos()` calls and two bus dispatches inside the measured interval, so it perturbs the primary metric by roughly tens to low hundreds of nanoseconds per frame. See the decision in §10.

**Do not** end a CPU bracket at `FlipFrameEvent`-to-`FlipFrameEvent`, and do not Mixin `Minecraft.renderFrame` end-to-end: both include the limiter spin/park.

**Frames that are not benchmark frames.** `Minecraft.renderFrame(Z)V` is invoked from four sites: `runTick` (offset 420) and, to force a repaint, from `doWorldLoad` (offset 296), `disconnect` (offset 155), and `setScreenAndShow` (offset 19). All four produce a full hook fire under every scheme above, including a Mixin. The harness must gate accumulation on an explicit "capture open" phase rather than treating every hook fire as a benchmark sample.

---

## 7. Keeping the Fabric seam honest

Verified at the pinned Fabric ref `f9468776b662dd2ab7875e9cdcdf2b653171309d`: `fabric-rendering-v1`'s client API contains `level/LevelRenderEvents.java`, `level/LevelExtractionEvents.java`, HUD and registry surfaces — and **no class with `Frame` in its name anywhere under `src/client/java/net/fabricmc/fabric/api/`**. `fabric-lifecycle-events-v1` exposes only `ClientLifecycleEvents`, `ClientTickEvents`, `ClientLevelEvents`, `ClientChunkEvents`, `ClientEntityEvents`, `ClientBlockEntityEvents`. Fabric has no public whole-frame bracket, confirming the portability doc's premise about Fabric even as its NeoForge conclusion needs revising.

**The seam that avoids the corner:** make the measured *quantity* vanilla's, and make the loader adapter responsible only for the *trigger*.

```
interface FrameSampler {            // minecraft-common
    void onFrameComplete();         // called exactly once per frame, after the latch
}

interface FrameTrigger {            // loader adapter seam
    void install(FrameSampler sampler);
}
```

- NeoForge: `FlipFrameEvent` → `sampler.onFrameComplete()`. No Mixin.
- Fabric (later): one client Mixin `@Inject(method = "renderFrame(Z)V", at = @At("RETURN"))` on `net.minecraft.client.Minecraft`. `renderFrame` is private, which Mixin handles fine, and it has **exactly one `return`** (offset 513, no exception table) — so `RETURN` fires exactly once per frame. It is after the limiter, which does not matter, because the sampler reads a value that was latched at offset 276.
- Both read `Minecraft.getFrameTimeNs()`.

**Can the two loaders be guaranteed to measure the identical interval?** Yes, by construction, *if* the number comes from vanilla rather than from loader-hook timestamps. The hook then decides only *when the value is read*, never *what it measures*; both loaders read a value produced by the same vanilla bytecode. This is a stronger guarantee than the shared-Mixin plan offered, because it does not depend on two Mixin configs applying identically. If instead Laymark timestamps the boundaries itself, the loaders' hook positions differ (NeoForge event outside the call vs. Fabric Mixin inside the method) and identity becomes an empirical claim to be re-proved on every version.

The secondary submit-phase metric does not port for free: Fabric has no `RenderFrameEvent` equivalent and would need a second Mixin on `GameRenderer.render`. Treat it as a NeoForge-only diagnostic, or accept a Fabric Mixin for it later. Either way it is additive, which is the property the map asks for.

---

## 8. Runtime self-check: once per frame, balanced samples

None of these need a Mixin.

1. **Nesting/balance.** Keep an `int depth`. `RenderFrameEvent.Pre` → `depth++`; `.Post` → `depth--`. Assert `depth == 1` inside the handlers and `depth == 0` at `FlipFrameEvent`. A non-zero depth at `FlipFrameEvent` means either re-entrancy (a mod re-entering `GameRenderer.render`) or an exception escaping `render`. Count these; do not silently drop them.
2. **Frame-count cross-check against vanilla's own counter.** `Minecraft.frames` is incremented once per `renderFrame` (offset 379) and published to the static `fps` field once per wall-clock second (offsets 465–504); `Minecraft.getFps()` exposes it. Laymark's samples accumulated over the same one-second window must equal `getFps()` exactly. Any deviation means the hook is not firing once per frame. This is the strongest available check because it compares against a counter Laymark does not own.
3. **Thread identity.** Assert `RenderSystem.isOnRenderThread()` (or `Minecraft.getInstance().isSameThread()`) in the handler. A sample from another thread is a bug, not a frame.
4. **Latch freshness.** `frameTimeNs` is a field, not a queue. If two consecutive `FlipFrameEvent` fires read the identical `long`, the trigger fired twice for one frame. Equal values are possible but improbable at nanosecond granularity; treat a run of ≥2 identical consecutive values as suspicious and cross-check with (2).
5. **Sanity bound.** Assert `sum(frameTimeNs) ≤ elapsed wall clock` over the capture. Violation means double-counting.
6. **Environment invariants per frame.** `getThrottleReason() == NONE` and `getFramerateLimit() >= 260`; see `docs/minecraft-26.1.2-client-surfaces-research.md` open question 6 for the per-frame-vs-boundary sampling trade-off, which applies verbatim here.
7. **Startup assertion that the hook exists.** With no Mixin there is nothing to "fail to apply", but the mods.toml `versionRange` floor for `FlipFrameEvent` should be asserted at startup anyway: if the first N frames after client-ready produce zero samples, fail the run loudly rather than reporting an empty capture. This replaces the "require the injection to apply" self-check the portability doc specified.

---

## 9. Corrections owed to `docs/loader-portability-research.md`

That doc is trusted and its Fabric analysis holds. Three statements in its "Identical whole-frame instrumentation" section need revising:

1. *"NeoForge's `RenderFrameEvent.Pre/Post` already brackets that call"* — **correct**, and verified at the exact build. But the doc treats "brackets `GameRenderer.render`" and "whole frame" as synonyms. On 26.1 they are not, because of the `update`/`extract`/`render` split.
2. *"use one client-only, required Mixin in shared source… inject at `HEAD` and normal `RETURN` of the exact 26.1.2 `GameRenderer.render(DeltaTracker, boolean)` descriptor"* — the descriptor is right, but on NeoForge this Mixin is **exactly redundant** with `RenderFrameEvent.Pre/Post`, and on both loaders it brackets a sub-interval of the frame.
3. *"on NeoForge integration tests, assert that its samples align with `RenderFrameEvent.Pre/Post` within hook overhead"* — this test would pass trivially and prove nothing, since the two hooks are adjacent statements around the same call.

The doc's underlying goal — "one definition of *whole frame*" shared by both loaders — is better served by `Minecraft.getFrameTimeNs()`, which is vanilla, loader-neutral, public, wider than the proposed bracket, already excludes swap and limiter, and is what Mojang's own telemetry reports.

The doc's `minecraft-common` ownership list should drop "the exact version-pinned Mixin" for 0.x and keep "whole-frame CPU timestamps" (now: whole-frame CPU *readings*). Nothing else in the doc's module layout changes.

---

## 10. Decisions needed from Mia

1. **Should the headline per-frame CPU number be vanilla's `Minecraft.getFrameTimeNs()`, or timestamps Laymark takes itself?**
   *Recommendation:* vanilla's `getFrameTimeNs()`. *Trade-off:* it is wider than any hook Laymark could build without a Mixin, it excludes swap and limiter by construction, Mojang's own telemetry uses it, and sampling it from `FlipFrameEvent` puts Laymark's code outside the interval it reports. The costs: it is on the GLFW timer rather than `System.nanoTime()`, it is a single opaque `long` with no sub-phase breakdown, and it is a vanilla implementation detail Mojang could re-scope in a future version without it being an API break (though the public getter and telemetry use make that unlikely). Taking our own timestamps buys control and decomposition but requires a Mixin to start the bracket before `GameRenderer.update`, which is the exact fragility this ticket set out to remove.

2. **Ship the optional submit-phase decomposition (`RenderFrameEvent.Pre`/`.Post` with `Util.getNanos()`) in 0.x, or defer it?**
   *Recommendation:* ship it, but record it as a separate, clearly-labelled channel and keep it behind a config flag that defaults to on. *Trade-off:* it is the only cheap way to tell "this mod costs in extract" from "this mod costs in submit", which is exactly the kind of attribution a mod-stack selection tool wants. But it adds two `Util.getNanos()` calls and two event dispatches *inside* the primary measured interval, perturbing the headline number by roughly tens to low hundreds of nanoseconds per frame — small against a 5–15 ms frame, but non-zero and asymmetric with a run that has it disabled. If any A/B pair may be compared across the flag, the flag must be part of the experimental stratum.

3. **Do we accept a hard NeoForge floor of `26.1.2.73` (for `FlipFrameEvent`), or avoid it by sampling `getFrameTimeNs()` in `RenderFrameEvent.Pre` and accepting a one-frame lag?**
   *Recommendation:* accept the floor and declare `versionRange="[26.1.2.95,)"` in `neoforge.mods.toml`, matching the map's pinned tuple rather than the true minimum. *Trade-off:* the floor costs nothing today (the target build is .95) and buys same-frame sampling plus a trigger that sits outside the measured interval. Pinning to `.95` rather than `.73` additionally prevents anyone running Laymark against an untested older build; the cost is that a user on `26.1.2.90` is refused even though the code would work.

4. **Should Laymark refuse to run when vsync is on or the framerate limit is set, or merely record it?**
   *Recommendation:* refuse — fail the run with a clear message — because vsync back-pressure can leak into `frameTimeNs` through driver-side blocking that no bracket can exclude, and there is no public readback to confirm the driver honoured a vsync-off request. *Trade-off:* refusing means Laymark cannot benchmark a pack "as the user actually plays it" with vsync on, which is arguably the more realistic scenario for a frame-pacing question. A softer alternative is to refuse only for the CPU-cost metric and permit vsync for a separately-labelled pacing metric.

5. **Does the Fabric stub in 0.x need a compiling `FrameTrigger` implementation, or only the interface?**
   *Recommendation:* interface plus an in-memory test implementation only; no Fabric Mixin is written in 0.x. *Trade-off:* writing the Fabric Mixin now would prove the seam is real and would catch a design flaw early, but the map explicitly scopes Fabric to a stub, and an unbuilt, untested Mixin in the tree is a maintenance liability that rots against Fabric API churn. The mitigation for not writing it: this document records the exact target (`Minecraft.renderFrame(Z)V`, `@At("RETURN")`, single return instruction) so the later port is transcription rather than research.

6. **Should the once-per-second `getFps()` cross-check (§8 item 2) be a hard run-failure or a recorded warning?**
   *Recommendation:* hard failure during a capture window, warning outside it. *Trade-off:* a mismatch means the frame hook is not firing once per frame, which invalidates every number in the run — that is worth failing on. But a mod that forces extra repaints (or a screen transition landing inside the window) could produce a benign one-off mismatch, and a hard failure would abort an otherwise long, expensive run. The middle option is to fail only if the mismatch exceeds a threshold fraction of frames in the window.
