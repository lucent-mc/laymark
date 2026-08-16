# Minecraft 26.1.2 client surfaces: world creation, options, and readiness

Research date: 2026-08-16. Target: Minecraft 26.1.2 / Java 25 / NeoForge 26.1.2.95. Loader scope: NeoForge (Fabric seam noted where relevant).

## Conclusion

The public vanilla client surface is sufficient for everything Laymark needs. **No Access Transformer and no Mixin is required** for world creation, option mutation, effective-value readback, or readiness detection. (The frame-bracketing Mixin that `docs/loader-portability-research.md` already argues for is a separate concern and is unaffected by this ticket.)

The unvetted docs' world-creation claim is **substantially right but wrong in three specifics**, and their readback claim about `Window.isFullscreen()` is **wrong**. Beyond correcting those, the investigation surfaced four hazards that would silently corrupt benchmark results if the harness is written from the unvetted docs as-is — chiefly `FramerateLimitTracker` clamping the frame cap to 30 FPS after 60 seconds without input, which is exactly the shape of a static-camera benchmark.

## How this was verified (provenance)

Minecraft 26.1.2 no longer ships an obfuscated jar. Mojang's version manifest entry for 26.1.2 has **no `client_mappings` or `server_mappings` download** — only `client` and `server` — and the shipped `client.jar` carries real class and member names (`net/minecraft/client/Options.class`, `com/mojang/blaze3d/platform/Window.class`). Every signature and every behavioural claim below was read out of Mojang's own shipped bytecode, not from a mapping set, a wiki, or memory.

| Artifact | Source | Identity |
| --- | --- | --- |
| Version manifest | `https://piston-meta.mojang.com/mc/game/version_manifest_v2.json` | 26.1.2, `release`, releaseTime `2026-04-09T12:12:23+00:00` |
| Version JSON | `https://piston-meta.mojang.com/v1/packages/edcfd100a4856650b6e9797bac8f7fd76821979e/26.1.2.json` | `javaVersion` = `java-runtime-epsilon`, `majorVersion` 25 |
| Client jar | `https://piston-data.mojang.com/v1/objects/4e618f09a0c649dde3fdf829df443ce0b8831e65/client.jar` | sha1 `4e618f09a0c649dde3fdf829df443ce0b8831e65`, 38 113 927 bytes, sha1 re-verified locally after download |
| NeoForge | `https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml` | `<latest>`/`<release>` = `26.1.2.95` |
| NeoForge sources | `neoforge-26.1.2.95-sources.jar` from the same Maven path | includes `META-INF/accesstransformer.cfg`, `neoforge.mixins.json` |
| NeoForge patches | `github.com/neoforged/NeoForge`, branch `26.1.x`, `patches/net/minecraft/**` | via GitHub contents API |

Signatures were read with `javap -p`; bodies with Vineflower 1.11.1 (Maven Central `org/vineflower/vineflower/1.11.1`) against the untouched Mojang jar. The decompilation is corroborated independently: NeoForge's own `patches/net/minecraft/client/Options.java.patch` quotes context lines that match the decompiled body verbatim (e.g. the `boolean largeDistances = Runtime.getRuntime().maxMemory() >= 1000000000L;` line and the `this.syncWrites = Util.getPlatform() == Util.OS.WINDOWS;` line), so what is documented here is the same source NeoForge builds against.

Java 25 confirmed independently from the version JSON, matching the map's target tuple.

---

## (a) Creating a disposable seeded singleplayer world

### The entry point

Confirmed. `Minecraft#createWorldOpenFlows()` is public and constructs a fresh flows object per call:

```java
// net.minecraft.client.Minecraft
public WorldOpenFlows createWorldOpenFlows() {
   return new WorldOpenFlows(this, this.levelSource);
}
```

`WorldOpenFlows#createFreshLevel` is public. **Exact signature in 26.1.2:**

```java
// net.minecraft.client.gui.screens.worldselection.WorldOpenFlows
public void createFreshLevel(
   String levelId,
   LevelSettings levelSettings,
   WorldOptions options,
   Function<HolderLookup.Provider, WorldDimensions> dimensionsProvider,
   Screen parentScreen
)
```

### Three corrections to the unvetted docs

`docs/benchmark-harness-handoff.md` (step `CREATE_WORLD`) claims the call takes "explicit `LevelSettings`, `WorldOptions(seed, structures, bonusChest)`, data configuration, **game rules**, and dimension factory." Correcting:

1. **The data configuration is not a separate parameter.** It is the fifth component of `LevelSettings`, and `createFreshLevel` reads it back out via `levelSettings.dataConfiguration()`. Passing it separately is not possible.
2. **Game rules are not a parameter of `createFreshLevel` at all.** Only the sibling `createLevelFromExistingSettings(LevelStorageAccess, ReloadableServerResources, LayeredRegistryAccess<RegistryLayer>, LevelDataAndDimensions.WorldDataAndGenSettings, Optional<GameRules>)` takes them. See "Game rules" below.
3. **There is a fifth parameter the docs omit: `Screen parentScreen`.** It is the screen restored if datapack loading throws. A harness must pass something; `null` is what the `catch` block would hand to `Minecraft#setScreen`, which accepts `@Nullable Screen`.

### Parameter types, verified

`LevelSettings` is a record and its shape changed — difficulty and hardcore are now bundled:

```java
// net.minecraft.world.level.LevelSettings (record)
public LevelSettings(
   String levelName,
   GameType gameType,
   LevelSettings.DifficultySettings difficultySettings,
   boolean allowCommands,
   WorldDataConfiguration dataConfiguration
)

// net.minecraft.world.level.LevelSettings$DifficultySettings (record)
public DifficultySettings(Difficulty difficulty, boolean hardcore, boolean locked)
// has a public static DEFAULT
```

`WorldOptions` matches the docs' claim exactly:

```java
// net.minecraft.world.level.levelgen.WorldOptions
public WorldOptions(long seed, boolean generateStructures, boolean generateBonusChest)
public static WorldOptions defaultWithRandomSeed()
public static OptionalLong parseSeed(String)   // for parsing a config-supplied seed string
public static long randomSeed()
public long seed(); public boolean generateStructures(); public boolean generateBonusChest();
```

`WorldDataConfiguration` is a record `(DataPackConfig dataPacks, FeatureFlagSet enabledFeatures)` with a public static `DEFAULT`.

The dimension factory has a ready-made vanilla implementation that satisfies the `Function<HolderLookup.Provider, WorldDimensions>` shape as a method reference:

```java
// net.minecraft.world.level.levelgen.presets.WorldPresets
public static WorldDimensions createNormalWorldDimensions(HolderLookup.Provider)
public static WorldDimensions createFlatWorldDimensions(HolderLookup.Provider)
```

So `WorldPresets::createNormalWorldDimensions` is the normal-preset argument. `WorldPresets` also exposes `NORMAL`, `FLAT`, `LARGE_BIOMES`, `AMPLIFIED`, `SINGLE_BIOME_SURFACE`, `DEBUG` as public `ResourceKey<WorldPreset>` constants if a preset needs to be resolved from the registry instead.

### Save-slot management

All public on `LevelStorageSource` (reachable via `Minecraft#getLevelSource()`):

```java
public boolean levelExists(String)
public boolean isNewLevelIdAcceptable(String)
public Path getLevelPath(String)
public LevelStorageAccess validateAndCreateAccess(String) throws IOException, ContentValidationException
```

and on `LevelStorageSource.LevelStorageAccess`: `getLevelId()`, `deleteLevel() throws IOException`, `makeWorldBackup()`, `estimateDiskSpace()`, `checkForLowDiskSpace()`, `safeClose()`, `close()`.

This fully covers the handoff's "choose a unique save ID containing the run ID, refuse collisions" requirement without reflection: check `levelExists` / `isNewLevelIdAcceptable`, then create. Note `createFreshLevel` calls `createWorldAccess` internally, which swallows `IOException` and `ContentValidationException` by showing a toast or a warning screen and returning — it does **not** throw. A harness must pre-check the ID itself rather than relying on an exception.

### What `createFreshLevel` actually does to the calling thread

This is the single most important operational fact and the unvetted docs do not mention it. `createFreshLevel` calls `Minecraft#doWorldLoad(...)`, which **blocks the client thread in a nested render loop** until the integrated server is ready:

```java
// net.minecraft.client.Minecraft#doWorldLoad
this.singleplayerServer = MinecraftServer.spin(thread -> new IntegratedServer(...));
...
while (!this.singleplayerServer.isReady() || this.overlay != null) {
   long finishTime = Util.getNanos() + tickLengthNs;   // 1/60 s
   screen.tick();
   if (this.overlay != null) this.overlay.tick();
   this.renderFrame(false);
   this.runAllTasks();
   this.managedBlock(() -> Util.getNanos() > finishTime);
}
// then: start memory channel, connect, send ServerboundHelloPacket
this.pendingConnection = connection;
```

Consequences for the state machine:

- The call must be made **on the client thread** (`Minecraft#execute` / `submit`, or from a `ClientTickEvent` handler). It is not thread-safe to call off-thread.
- When it returns, the integrated server is ready **but the player has not joined**. Login is a packet handshake still in flight (`pendingConnection`). `Minecraft.player`, `Minecraft.level` are still null.
- Because the loop calls `renderFrame` and `runAllTasks`, any harness code queued to the client thread will execute *during* world creation. Do not assume the harness's own tick handler is quiescent across the call.
- The `LevelLoadTracker` is constructed with a `closeDelayMs` of `500L` for a new world (`LevelLoadTracker.LEVEL_LOAD_CLOSE_DELAY_MS`); zero for an existing world. This 500 ms is baked into the "world is ready" signal for fresh worlds.

### Game rules

`createFreshLevel` gives no way to set game rules. Two public paths exist after the server is up:

```java
// net.minecraft.server.MinecraftServer
public GameRules getGameRules()
public GameRules getGlobalGameRules()
public <T> void onGameRuleChanged(GameRule<T>, T)

// net.minecraft.world.level.gamerules.GameRules
public <T> T get(GameRule<T>)
public <T> void set(GameRule<T>, T, MinecraftServer)
public void setAll(GameRules, MinecraftServer)
public void setAll(GameRuleMap, MinecraftServer)
public <T> String getAsString(GameRule<T>)
```

`GameRules` exposes typed constants (`ADVANCE_TIME`, `ADVANCE_WEATHER`, `DO_DAYLIGHT_CYCLE`-equivalents, `RANDOM_TICK_SPEED`, etc.) as `public static final GameRule<T>`. For determinism, `ADVANCE_TIME` and `ADVANCE_WEATHER` are the obvious candidates. This is a post-creation, server-thread mutation, and it is a decision point (see below).

---

## (b) The `Options` surface

`Minecraft.options` is a **public final field**. `Options` has 201 public members. The surface splits three ways.

### 1. Plain mutable public fields (no callback, no validation)

`resourcePacks` (`List<String>`), `incompatibleResourcePacks`, `fullscreenVideoModeString`, `hideServerAddress`, `advancedItemTooltips`, `pauseOnLostFocus`, `overrideWidth`, `overrideHeight`, `glDebugVerbosity`, `tutorialStep`, `joinedFirstServer`, `skipMultiplayerWarning`, `hideGui`, `lastMpIp`, `smoothCamera`, `languageCode`, `onboardAccessibility`, `syncWrites`, `startedCleanly`, plus the `KeyMapping` fields.

Writing these does nothing on its own. Several are read only once, at startup (see the taxonomy table).

### 2. `OptionInstance<T>` accessors

Everything graphics-relevant. The private field is exposed through a no-arg accessor of the same name: `options.renderDistance()`, `options.simulationDistance()`, `options.framerateLimit()`, `options.graphicsPreset()`, `options.cloudStatus()`, `options.cloudRange()`, `options.weatherRadius()`, `options.cutoutLeaves()`, `options.vignette()`, `options.improvedTransparency()`, `options.ambientOcclusion()`, `options.chunkSectionFadeInTime()`, `options.prioritizeChunkUpdates()`, `options.mipmapLevels()`, `options.maxAnisotropyBit()`, `options.textureFiltering()`, `options.biomeBlendRadius()`, `options.entityDistanceScaling()`, `options.entityShadows()`, `options.particles()`, `options.guiScale()`, `options.fov()`, `options.gamma()`, `options.enableVsync()`, `options.fullscreen()`, `options.exclusiveFullscreen()`, `options.inactivityFpsLimit()`, `options.menuBackgroundBlurriness()`, and the rest.

**Note the 26.1 rename: `GraphicsStatus` is gone.** The enum is now `net.minecraft.client.GraphicsPreset { FAST, FANCY, FABULOUS, CUSTOM }`, reached through `options.graphicsPreset()` or the convenience `options.applyGraphicsPreset(GraphicsPreset)`. Any code or doc referring to `Options#graphics()` / `GraphicsStatus` is stale.

### 3. What `set(...)` actually does

The unvetted handoff says `set` "validates, stores, and invokes the option's vanilla update callback while the client is running." That is directionally correct and materially incomplete. The real body:

```java
// net.minecraft.client.OptionInstance#set
public void set(final T value) {
   T newValue = this.values.validateValue(value).orElseGet(() -> {
      LOGGER.error("Illegal option value {} for {}", value, this.caption.getString());
      return this.initialValue;                       // <-- NOT the requested value, NOT a clamp
   });
   if (!Minecraft.getInstance().isRunning()) {
      this.value = newValue;                          // <-- callback SKIPPED entirely
   } else {
      if (!Objects.equals(this.value, newValue)) {    // <-- callback skipped if unchanged
         this.value = newValue;
         this.onValueUpdate.accept(this.value);
      }
   }
}
```

Three consequences the harness must design around:

- **An out-of-range value silently becomes the option's default, not a clamp and not an error.** `OptionInstance.IntRange#validateValue` and `OptionInstance.Enum#validateValue` return `Optional.empty()` when out of range, and the `orElseGet` substitutes `initialValue`. Only `ClampingLazyMaxIntRange` (used solely by `guiScale`) actually clamps. So requesting render distance 32 on a JVM with `maxMemory() < 1_000_000_000` yields **12**, the default — not 16, not 32. There is a `LOGGER.error`, and nothing else. This alone justifies the handoff's "read every effective value back; fail rather than silently benchmark different settings."
- **Setting an option to the value it already holds does not fire its callback.** A harness that relies on the callback for a side effect (e.g. `allChanged()`) after a no-op set will not get it.
- **Before `Minecraft#isRunning()` is true, `set` stores without side effects.** `running` is `private volatile boolean` and true for the duration of `Minecraft#run()`. Practically, the harness is always inside `run()` by the time it acts, but an option written during mod construction gets no callback.

`get()` returns the stored value; `values()` returns the `ValueSet<T>` so a harness can interrogate legal bounds *before* setting — `OptionInstance.IntRange` is a record exposing `minInclusive()` / `maxInclusive()`. **Pre-validating against `values()` is the correct way to avoid the silent-default trap.**

### Thread affinity

Option mutation must happen on the client/render thread. `options.enableVsync().set(...)` reaches `Window#updateVsync`, whose first statement is `RenderSystem.assertOnRenderThread()`. `options.fullscreen().set(...)`, `options.guiScale().set(...)` and the `LevelRenderer` callbacks are equally thread-bound. Use `Minecraft#execute(Runnable)` / `submit(...)` (both public, inherited from `BlockableEventLoop`), or run inside a `ClientTickEvent` handler.

### Persistence

`Options#save()` and `Options#load()` are public, as is `Options#getFile()`. `Options#dumpOptionsForReport()` returns a sorted `String` of every option — useful for embedding the exact settings in a benchmark result artifact, and cheaper to audit than reading each accessor.

---

## Live vs deferred: the taxonomy

Derived from the `onValueUpdate` consumer of each `OptionInstance` and from `VideoSettingsScreen`, which is where vanilla actually applies the deferred ones.

| Class | Settings | What makes it take effect |
| --- | --- | --- |
| **Live, callback does it** | `framerateLimit` (→ `FramerateLimitTracker#setFramerateLimit`), `enableVsync` (→ `Window#updateVsync`), `guiScale` (→ `Minecraft#resizeGui`), `narrator`, `forceUnicodeFont` / `japaneseGlyphVariants` (→ `updateFontOptions`), `directionalAudio` (→ `SoundManager#reload`), `reducedDebugInfo`, `rawMouseInput`, `allowCursorChanges` | Nothing further |
| **Live next frame** | `fullscreen` | Callback only flips `Window`'s desired flag via `toggleFullScreen()`; the actual GLFW mode change happens in `Window#updateFullscreenIfChanged()`, called from `Minecraft#renderFrame`. Await a frame. |
| **Full renderer rebuild** | `ambientOcclusion`, `cutoutLeaves`, `biomeBlendRadius`, `improvedTransparency` (conditionally — see hazards) | Callback calls `LevelRenderer#allChanged()` — full section remesh. Cost is real; do not apply mid-capture. |
| **Partial renderer invalidation** | `renderDistance` (→ `LevelRenderer#needsUpdate()`), `cloudRange` (→ `CloudRenderer#markForRebuild()`), `maxAnisotropyBit` / `textureFiltering` (→ `LevelRenderer#resetSampler()`) | Callback fires, but see next row for the texture ones |
| **Texture/resource reload, NOT performed by the option** | `mipmapLevels`, `maxAnisotropyBit`, `textureFiltering` | Their callbacks do *not* reload atlases. Vanilla applies them in `VideoSettingsScreen#removed()`: `minecraft.updateMaxMipLevel(options.mipmapLevels().get()); minecraft.delayTextureReload();`. **A headless harness must call both itself.** `Minecraft#delayTextureReload()` returns `CompletableFuture<Void>` — that is the barrier. |
| **Resource reload** | resource pack selection | `Options#updateResourcePacks(PackRepository)` rewrites `resourcePacks`, calls `save()`, and calls `Minecraft#reloadResourcePacks()` **only if the selection changed**. `reloadResourcePacks()` returns `CompletableFuture<Void>` which completes after `levelRenderer.allChanged()` has run. |
| **Integrated-server convergence** | `renderDistance`, `simulationDistance` | See below — a tick-loop poll on the server, gated on not being paused. |
| **Value read at use site (no barrier)** | `vignette`, `chunkSectionFadeInTime`, `entityShadows`, `particles`, `prioritizeChunkUpdates`, `cloudStatus`, `weatherRadius`, `entityDistanceScaling`, `inactivityFpsLimit`, `fov`, `gamma`, `menuBackgroundBlurriness` | Callback is `{}` or only `setGraphicsPresetToCustom()`. Effective on the next frame that reads them. |
| **Startup-only** | `overrideWidth` / `overrideHeight` (read once in the `Minecraft` constructor to size the window), `glDebugVerbosity` (read once into `GpuDebugOptions` at device creation), `fullscreenVideoModeString` (read once into the `Window` constructor), `exclusiveFullscreen` (vanilla's own tooltip adds `options.needsRestart` when it differs from `initialExclusiveFullscreen`) | Requires a game restart. `syncWrites` is consumed when level storage is created, so effectively fixed for the life of a world. |

### Integrated-server distance convergence, exactly

```java
// net.minecraft.client.server.IntegratedServer#tickServer
this.paused = Minecraft.getInstance().isPaused() || this.getPlayerList().getPlayers().isEmpty();
...
if (this.paused) { this.tickPaused(); }
else {
   super.tickServer(haveTime);
   int serverViewDistance = Math.max(2, this.minecraft.options.renderDistance().get());
   if (serverViewDistance != this.getPlayerList().getViewDistance()) {
      this.getPlayerList().setViewDistance(serverViewDistance);
   }
   int serverSimulationDistance = Math.max(2, this.minecraft.options.simulationDistance().get());
   if (serverSimulationDistance != this.previousSimulationDistance) {
      this.getPlayerList().setSimulationDistance(serverSimulationDistance);
      this.previousSimulationDistance = serverSimulationDistance;
   }
}
```

So: the integrated server polls the client's `Options` object directly (it does not use `ClientInformation` for this), applies `Math.max(2, ...)`, and **only while unpaused**. Paused means `Minecraft#isPaused()` — which is `hasSingleplayerServer() && (screen is a pause screen || overlay is a pause screen) && !isPublished()` — or **no players joined yet**. Convergence therefore requires: a joined player, no pause screen, and at least one subsequent server tick. Waiting on it before the player has joined will hang.

`Minecraft#pauseIfInactive()` will auto-pause on focus loss after 500 ms if `options.pauseOnLostFocus` is true (default true). For an unattended benchmark run this must be set to `false`, or focus loss silently freezes the integrated server mid-measurement.

---

## (c) Effective-value readback

Verifying each accessor the unvetted handoff names:

| Handoff claim | Verdict | Actual |
| --- | --- | --- |
| `Options.getEffectiveRenderDistance()` | **Confirmed** | `public int getEffectiveRenderDistance() { return this.serverRenderDistance > 0 ? Math.min(this.renderDistance.get(), this.serverRenderDistance) : this.renderDistance.get(); }`. `serverRenderDistance` is fed by `Options#setServerRenderDistance(int)`, called from `ClientPacketListener#handleLogin` (`packet.chunkRadius()`) and `#handleSetChunkCacheRadius`. It is a genuine round-tripped value. |
| integrated-server `PlayerList` distances | **Confirmed** | `PlayerList#getViewDistance()` and `#getSimulationDistance()` are public; reach them via `Minecraft#getSingleplayerServer()` (`@Nullable IntegratedServer`) → `MinecraftServer#getPlayerList()`. |
| `Window.isFullscreen()` as an effective value | **WRONG** | `isFullscreen()` returns the private `fullscreen` field, which is the **requested** flag set by `toggleFullScreen()`. The applied state is the separate private `actuallyFullscreen`, flipped in `updateFullscreenIfChanged()`. There is no public getter for it. Effective readback options: (i) call `GLFW.glfwGetWindowMonitor(window.handle()) != 0L` — `handle()` is public and this is exactly the check `Window#setMode` itself uses; (ii) infer from `getScreenWidth()`/`getScreenHeight()` matching the monitor mode. **Recommend (i).** No AT needed. |
| framebuffer vs logical size | **Confirmed, with the naming inverted from intuition** | `Window#getWidth()` / `getHeight()` return **framebuffer** size (`framebufferWidth/Height`, updated by the GLFW framebuffer-resize callback). `Window#getScreenWidth()` / `getScreenHeight()` return the **logical window** size. `getGuiScaledWidth()` / `getGuiScaledHeight()` / `getGuiScale()` give the scaled GUI space. On a HiDPI Windows display these differ; recording both is the only honest option. |
| resource-pack IDs | **Confirmed** | `Minecraft#getResourcePackRepository()` → `PackRepository#getSelectedIds()` (`Collection<String>`), `#getSelectedPacks()`, `#getAvailableIds()`. Note NeoForge patches `Options#updateResourcePacks` to additionally skip `entry.isHidden()` packs, so `options.resourcePacks` and `getSelectedIds()` can legitimately differ under NeoForge — read the repository, not the option list. |
| reload futures | **Confirmed** | `Minecraft#reloadResourcePacks()` → `CompletableFuture<Void>`; `Minecraft#delayTextureReload()` → `CompletableFuture<Void>`. Lower level: `ReloadInstance { CompletableFuture<?> done(); float getActualProgress(); default boolean isDone(); default void checkExceptions(); }`. |

### Additional effective-value accessors worth using

- `Minecraft#getFramerateLimitTracker()` → `FramerateLimitTracker#getFramerateLimit()` (the **effective** cap after throttling) and `#getThrottleReason()` → `FramerateThrottleReason { NONE, WINDOW_ICONIFIED, LONG_AFK, SHORT_AFK, OUT_OF_LEVEL_MENU }`. Also `#isHeavilyThrottled()`.
- `LevelRenderer#getSectionStatistics()`, `#countRenderedSections()`, `#getTotalSections()`, `#getLastViewDistance()`, `#hasRenderedAllSections()` — `getLastViewDistance()` is the view distance the renderer actually built to.
- `Minecraft#getFps()`, `#getFrameTimeNs()`, `#getGpuUtilization()`.
- `MinecraftServer#getAverageTickTimeNanos()`, `#getTickTimesNanos()`, `#getCurrentSmoothedTickTime()` — public, and give integrated-server MSPT without Spark.
- `Options#dumpOptionsForReport()` for the whole-settings snapshot.
- **No effective readback exists for VSync.** `Window#updateVsync` calls `RenderSystem.getDevice().setVsync(b)` and stores a private `vsync` field with no getter; the driver may ignore the request entirely. Treat VSync as a requested-only setting and record it as such, or infer from observed frame pacing.

---

## Readiness signals

### Client boot readiness

`Minecraft#isGameLoadFinished()` is public, backed by `gameLoadFinished`, set in `onResourceLoadFinished` immediately before the initial screens are built. On NeoForge, `ClientResourceLoadFinishedEvent` fires from the head of that same method (`patches/net/minecraft/client/Minecraft.java.patch`), with `isInitial() == !gameLoadFinished` — i.e. **the event fires before `isGameLoadFinished()` flips and before the title screen exists**. NeoForge also gates `ClientTickEvent.Pre`/`Post` on `gameLoadFinished`, so the first client tick event is already past boot.

"No pending resource reload" = `Minecraft#getOverlay() == null` (a `LoadingOverlay` is present during any reload) plus `isGameLoadFinished()`. `Minecraft#reloadResourcePacks()` short-circuits and returns the existing `pendingReload` future if one is in flight, so re-requesting is safe.

The handoff's `BOOT_WAIT` → "title-ready state with no client world and no pending resource reload" maps to: `isGameLoadFinished() && getOverlay() == null && level == null`, optionally `screen instanceof TitleScreen`.

### Integrated-server readiness

`MinecraftServer#isReady()` is public. It is set to `true` at the **end of the first completed tick** of `runServer`, not when `initServer` returns. `Minecraft#doWorldLoad` already blocks on it, so by the time `createFreshLevel` returns it is true — the harness rarely needs to poll it, but it is the right signal after any other path.

`Minecraft#hasSingleplayerServer()` and `#getSingleplayerServer()` (`@Nullable`) are public.

### Player join and level readiness

26.1 has a purpose-built mechanism that did not exist in earlier versions, and it is much better than the "settle interval plus stable counters" fallback the handoff proposes.

`net.minecraft.client.multiplayer.LevelLoadTracker` is a **public class** with public `isLevelReady()`, `serverProgress()`, `hasProgress()`, `statusView()`, `tickClientLoad()`, `startClientLoad(...)`, `loadingPacketsReceived()`. Its state machine:

```
WaitingForServer
  --(ClientboundGameEventPacket.LEVEL_CHUNKS_LOAD_START)-->  WaitingForPlayerChunk
  --(levelRenderer.isSectionCompiledAndVisible(player.blockPosition()))-->  ClientLevelReady(now)
isLevelReady() == now >= readyAt + closeDelayMs      // closeDelayMs = 500 for a fresh world
```

When it flips, `ClientPacketListener` sends `ServerboundPlayerLoadedPacket` and `LevelLoadingScreen#tick` closes itself.

Two caveats:

- **`WaitingForPlayerChunk` has a 30-second timeout** (`CLIENT_WAIT_TIMEOUT_MS`) after which it reports ready anyway, logging `"Timed out while waiting for the client to load chunks, letting the player into the world anyway"`. A benchmark that trusts the signal blindly can start measuring on an unbuilt world. The timeout is not exposed programmatically; the only in-process signal is the log line. **Recommend the harness not depend on `LevelLoadTracker` alone** (see below).
- **The live `LevelLoadTracker` instance is not reachable publicly.** It is `private` in both `ClientPacketListener` and `LevelLoadingScreen`, and `ClientPacketListener` nulls it out once ready. Getting the object itself would need an AT or a Mixin.

Fortunately the *observable consequences* are all public, so no AT is needed:

| Signal | Access | Public? |
| --- | --- | --- |
| Client has reported loaded | `Minecraft#getConnection()` → `ClientPacketListener#hasClientLoaded()` | **Yes** |
| Server agrees the client loaded | `ServerPlayer#connection` → `ServerGamePacketListenerImpl#hasClientLoaded()` | **Yes** |
| Loading screen dismissed | `Minecraft.screen instanceof LevelLoadingScreen` is false | **Yes** (public field, public class) |
| Player's own section is meshed | `Minecraft.levelRenderer.isSectionCompiledAndVisible(player.blockPosition())` | **Yes** |
| All visible sections meshed | `LevelRenderer#hasRenderedAllSections()` | **Yes** |
| Renderer built to the requested distance | `LevelRenderer#getLastViewDistance()` vs `Options#getEffectiveRenderDistance()` | **Yes** |
| Level and player exist | `Minecraft.level != null`, `Minecraft.player != null` (public fields) | **Yes** |

**Recommended composite barrier for `AWAIT_WORLD`:** `player != null && level != null && getConnection().hasClientLoaded() && !(screen instanceof LevelLoadingScreen) && levelRenderer.hasRenderedAllSections() && levelRenderer.getLastViewDistance() >= options.getEffectiveRenderDistance()`, held stable for N ticks, with an explicit harness-side timeout that fails the run rather than proceeding. This is strictly stronger than vanilla's own signal, which the 30-second escape hatch makes unsafe for measurement, and it satisfies the handoff's open question "exact client chunk/mesh readiness signal" without the guesswork it anticipated.

### NeoForge lifecycle events (verified present in 26.1.2.95)

From `neoforge-26.1.2.95-sources.jar`, `net/neoforged/neoforge/client/event/`:

- `ClientResourceLoadFinishedEvent` (`isInitial()`) — fired in `Minecraft#onResourceLoadFinished`.
- `ClientTickEvent.Pre` / `.Post` — head and tail of `Minecraft#tick()`, gated on `gameLoadFinished`.
- `RenderFrameEvent.Pre` / `.Post`, `FlipFrameEvent`.
- `WindowResizeEvent(Window)` — fired inside `Minecraft#resizeGui()`, after `window.setGuiScale(...)`. Javadoc: "Fired when the Minecraft window is resizing, including GUI scale changes, game switches fullscreen mode, and the unicode font option is changed." **This is the resize-completion barrier the handoff asks for**, and it covers fullscreen transitions and GUI-scale changes too. Note it fires from `resizeGui`, which is driven off the GLFW framebuffer callback during `RenderSystem.pollEvents()` — so a resize request needs at least one pumped frame.
- `ClientPlayerNetworkEvent.LoggingIn` / `.LoggingOut` / `.Clone` — `LoggingIn` fires at login, long before the level is ready. Not a readiness signal.
- `ClientPauseChangeEvent.Pre` (cancellable) / `.Post`.
- `net.neoforged.neoforge.event.server.ServerStartedEvent` / `ServerStartingEvent` / `ServerStoppingEvent` / `ServerStoppedEvent`.
- `net.neoforged.neoforge.event.GameShuttingDownEvent` — fired from `Minecraft#stop()` when running; the right place for a final flush.

**There is no NeoForge event for "player finished loading."** That gap is covered by the vanilla `hasClientLoaded()` accessors above.

---

## Do we need an Access Transformer or a Mixin?

**No, for this ticket's scope.** Verified by reading `META-INF/accesstransformer.cfg` (374 lines) and `accesstransformergenerated.cfg` (336 lines) in the NeoForge sources jar: NeoForge widens `Options.keyMappings`, `Options$FieldAccess`, `OptionInstance.caption`, `OptionInstance.toString`, `OptionInstance$ValueSet`, `LevelRenderer.levelRenderState`, `LevelRenderer.shouldShowEntityOutlines()` — none of which Laymark needs, and none of which change anything documented here. `neoforge.mixins.json` declares only two accessor mixins (`BlockEntityTypeAccessor`, `MappedRegistryAccessor`), with an empty `client` list.

The only two things that are genuinely private are:

1. `Window.actuallyFullscreen` — worked around with `GLFW.glfwGetWindowMonitor(window.handle()) != 0L`, which is what vanilla does internally.
2. The live `LevelLoadTracker` instance — worked around with the composite public barrier above, which is a better barrier anyway.

This confirms the handoff's assertion that "neither loader needs to provide a special 'edit all game options' facility: the public vanilla client surface is sufficient," and extends it to world creation and readiness.

Also verified: NeoForge's patch to `Options.java` on branch `26.1.x` touches only keybinding serialisation/conflict contexts and the hidden-pack filter. **No option accessor or `OptionInstance` signature differs between vanilla and the NeoForge-patched sources**, so everything above is what a NeoForge mod compiles against.

---

## Benchmarking hazards found while verifying

These are not answers to the ticket's question; they are things that would silently produce wrong numbers.

### 1. `FramerateLimitTracker` clamps to 30 FPS after 60 seconds without input

The highest-impact finding. `FramerateLimitTracker#getFramerateLimit()` does not return the configured cap:

```java
return switch (this.getThrottleReason()) {
   case NONE -> this.framerateLimit;
   case WINDOW_ICONIFIED -> 10;
   case LONG_AFK -> 10;                                 // > 600_000 ms since last input
   case SHORT_AFK -> Math.min(this.framerateLimit, 30); // >  60_000 ms since last input
   case OUT_OF_LEVEL_MENU -> 60;                        // level == null and a screen/overlay is up
};
```

`SHORT_AFK`/`LONG_AFK` apply when `options.inactivityFpsLimit().get() == InactivityFpsLimit.AFK`, **which is the default**. `latestInputTime` only advances on real input (`onInputReceived`). A static-camera or scripted-camera benchmark generates no GLFW input, so after 60 seconds every run is capped at 30 FPS, and after 10 minutes at 10 FPS. Mitigations: set `options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED)`, keep the window non-iconified, and **assert `getThrottleReason() == NONE` for the entire capture window**, failing the run otherwise.

### 2. `GraphicsPreset` is hardware- and OS-dependent

`GraphicsPreset.FABULOUS` sets `improvedTransparency` to `Util.getPlatform() != OS.OSX`, and picks `TextureFilteringMethod.RGSS` on AMD vs `ANISOTROPIC` elsewhere via `GraphicsWorkarounds.get(RenderSystem.getDevice()).isAmd()`. A preset name is therefore **not** a portable definition of a settings bundle. Benchmark presets must enumerate every option explicitly and read every one back. Named presets are fine as a starting point, never as the recorded configuration.

Related: `FABULOUS` requests `renderDistance` 32. On a JVM with `maxMemory() < 1 GB` the option's range caps at 16, `validateValue` returns empty, and the value silently becomes **12**. Laymark will run with a large heap, but this is exactly the class of failure the readback gate exists to catch.

### 3. `improvedTransparency` can store without applying, and can hijack the screen

```java
if (!this.isApplyingGraphicsPreset && value && gpuWarnlistManager.willShowWarning()) {
   gpuWarnlistManager.showWarning();          // opens UnsupportedGraphicsWarningScreen
} else {
   operateOnLevelRenderer(LevelRenderer::allChanged);
   this.setGraphicsPresetToCustom();
}
```

`OptionInstance#set` stores the value *before* invoking the callback, so on a warn-listed GPU the option reads back as `true` while the renderer was never rebuilt — and a modal screen has taken over `Minecraft.screen`. Mitigation: after applying any renderer-affecting bundle, call `Minecraft.getInstance().levelRenderer.allChanged()` explicitly, and assert `Minecraft.screen` is the screen the harness expects.

### 4. `pauseOnLostFocus` freezes the integrated server

Default `true`. `Minecraft#pauseIfInactive()` opens a pause screen 500 ms after focus loss; `IntegratedServer#tickServer` then stops converging distances and stops ticking normally. Any unattended run must set `options.pauseOnLostFocus = false` (plain public field, no callback) and should assert `!Minecraft.getInstance().isPaused()` across the capture.

---

## Fabric seam notes

Everything load-bearing here is in `net.minecraft.*` / `com.mojang.blaze3d.*` and is loader-neutral, which is consistent with `docs/loader-portability-research.md`'s placement of vanilla control in `minecraft-common`. Specifically portable as-is: `createWorldOpenFlows()/createFreshLevel(...)`, all of `Options` / `OptionInstance`, `Window`, `LevelStorageSource`, `LevelRenderer`, `FramerateLimitTracker`, `MinecraftServer`/`PlayerList`, `ClientPacketListener#hasClientLoaded()`, `Minecraft#isGameLoadFinished()`, `reloadResourcePacks()`, `delayTextureReload()`.

Only four things in this document are NeoForge-specific, and each is a *notification*, not a capability:

| NeoForge | Purpose | Fabric-side equivalent needed |
| --- | --- | --- |
| `ClientResourceLoadFinishedEvent` | reload-complete notification | Fabric has resource-reload listeners; but note the vanilla `CompletableFuture` from `reloadResourcePacks()` already covers this loader-neutrally |
| `ClientTickEvent.Pre/Post` | drive the state machine | `ClientTickEvents` |
| `WindowResizeEvent` | resize-complete notification | no direct equivalent; poll `Window#isResized()` / dimensions, which is loader-neutral |
| `GameShuttingDownEvent` | final flush | `ClientLifecycleEvents.CLIENT_STOPPING` |

**Seam guidance:** define the adapter interface in terms of *the four notifications above only*, and implement the readiness barriers and all readback in `minecraft-common` using the vanilla accessors. Do not let a NeoForge event type appear in the state machine's vocabulary. In particular, do not model readiness as "a NeoForge event fired" — model it as "this predicate over public vanilla state became true," which ports for free. The one place where taking the NeoForge event would paint the Fabric seam into a corner is `WindowResizeEvent`, which has no Fabric analogue; prefer polling `Window` dimensions plus `isResized()`/`resetIsResized()` there even on NeoForge, and treat the event as an optional fast path.

---

## Corrections to the unvetted docs, consolidated

| Unvetted claim (`benchmark-harness-handoff.md`) | Status |
| --- | --- |
| `Minecraft.createWorldOpenFlows().createFreshLevel(...)` | Confirmed |
| `WorldOptions(seed, structures, bonusChest)` | Confirmed exactly |
| `LevelSettings` passed explicitly | Confirmed, but the record now takes `DifficultySettings(difficulty, hardcore, locked)` |
| "data configuration" as a separate argument | **Wrong** — it is `LevelSettings`' fifth component |
| "game rules" as an argument to `createFreshLevel` | **Wrong** — not a parameter; use `MinecraftServer#getGameRules()` post-creation |
| dimension factory | Confirmed — `Function<HolderLookup.Provider, WorldDimensions>`; use `WorldPresets::createNormalWorldDimensions` |
| (omitted) trailing `Screen parentScreen` parameter | **Missing from the docs** |
| (omitted) `createFreshLevel` blocks the client thread until the server is ready | **Missing from the docs**, and it changes the state machine |
| "most settings are mutable `OptionInstance<T>`" | Confirmed |
| "`OptionInstance.set(...)` validates, stores, and invokes the callback while running" | Confirmed but incomplete: invalid → **silent default**, unchanged → **no callback**, not running → **no callback** |
| `Window.setWindowed(width, height)` public | Confirmed |
| `Options.getEffectiveRenderDistance()` | Confirmed |
| integrated-server `PlayerList` distances | Confirmed; convergence is gated on unpaused + a joined player |
| `Window.isFullscreen()` as effective value | **Wrong** — returns the requested flag |
| logical and framebuffer sizes | Confirmed — `getScreenWidth/Height` vs `getWidth/Height` |
| selected resource-pack IDs | Confirmed — `PackRepository#getSelectedIds()` |
| reload completion futures | Confirmed — `reloadResourcePacks()`, `delayTextureReload()`, `ReloadInstance#done()` |
| `TEXTURE_OR_RESOURCE_RELOAD` covers "mipmaps/filtering/resource packs" | Confirmed, and the docs understate it: the option callbacks do **not** trigger the reload; the harness must call `updateMaxMipLevel` + `delayTextureReload` |
| "public accessors also exist for … GUI scale, fullscreen, entity shadows, ambient occlusion, chunk-update priority, and the other 26.1 graphics controls" | Confirmed. Note `GraphicsStatus` is now `GraphicsPreset` with a `CUSTOM` member, and 26.1 adds `cutoutLeaves`, `vignette`, `improvedTransparency`, `chunkSectionFadeInTime`, `weatherRadius`, `cloudRange`, `textureFiltering`, `maxAnisotropyBit`, `exclusiveFullscreen`, `inactivityFpsLimit` |
| "Exact client chunk/mesh readiness signal" listed as unresolved | **Resolved** — composite public barrier above |
| Loader must provide an options facility | Correctly rejected by the docs; now confirmed against source, and extended: no AT, no Mixin needed |

## What was not verified

- **PresentMon / GPU timestamp queries.** Out of scope for this ticket. `Minecraft#getGpuUtilization()` and `TimerQuery` exist and are used by the debug overlay, but I did not audit their accuracy or thread-safety.
- **Whether `RenderSystem.getDevice()` is safe to touch outside the render thread** in `GraphicsPreset#apply`. `Window#updateVsync` asserts render-thread, so applying options on the client thread is required regardless; I did not confirm whether `GraphicsWorkarounds.get(device)` has its own assertion.
- **Chunky and Spark integration surfaces.** Separate tickets.
- **Behaviour under other mods.** Everything here is vanilla + NeoForge with no third-party mods loaded. An optimization mod in the benchmarked stack may replace `LevelRenderer` behaviour such that `hasRenderedAllSections()` or `getLastViewDistance()` means something different. This is a real risk for a tool whose whole purpose is benchmarking renderer replacements, and it is the reason the readiness barrier should be composite and should record *which* condition satisfied it. Settling it requires running the barrier against the actual Lucent Optimizations stack — it cannot be settled from source.

---

## Decisions needed from Mia

1. **Should the readiness barrier use vanilla's `LevelLoadTracker` signal, or Laymark's own composite predicate?**
   *Recommendation:* Laymark's own composite predicate (`hasClientLoaded()` + screen dismissed + `hasRenderedAllSections()` + `getLastViewDistance() >= getEffectiveRenderDistance()`, stable for N ticks, harness timeout fails the run). *Trade-off:* vanilla's signal is one public call and is what the game itself trusts, but its 30-second escape hatch reports ready on an unbuilt world with only a log line, which would silently produce a fast, meaningless run. The composite predicate costs more code and one more thing to keep honest across Minecraft versions, and it is more likely to be perturbed by an optimization mod that replaces `LevelRenderer` — which is precisely the population Laymark measures. Choosing the composite means accepting that the barrier is itself part of the measurement apparatus and must be recorded in results.

2. **Should game rules be settable per benchmark world, given `createFreshLevel` cannot take them?**
   *Recommendation:* yes, and pin `ADVANCE_TIME` and `ADVANCE_WEATHER` off for every render-bound scenario, applied via `MinecraftServer#getGameRules().set(rule, value, server)` on the server thread immediately after the server is ready and before the readiness barrier. *Trade-off:* it adds a server-thread mutation step and a cross-thread ordering constraint to the state machine, and it makes benchmark worlds slightly non-vanilla. Not doing it means day/night and weather drift across a capture, which is a real variance source for a static-camera render benchmark. The alternative — `createLevelFromExistingSettings`, which does take `Optional<GameRules>` — requires the harness to assemble a `WorldStem` itself and is much more code.

3. **Is `pauseOnLostFocus = false` and an `inactivityFpsLimit = MINIMIZED` override acceptable as a mandatory, non-configurable part of every run?**
   *Recommendation:* yes, mandatory and non-overridable, restored on exit with the rest of the options snapshot. *Trade-off:* these are user-visible settings and Laymark is mutating them without asking; a user watching the run will see different behaviour from their normal game. But leaving them at defaults means an unattended run is capped at 30 FPS after 60 seconds and freezes entirely on focus loss, which invalidates results rather than degrading them. Making them configurable would mean shipping a configuration option whose only correct value is one specific value.

4. **Should benchmark presets be forbidden from naming a `GraphicsPreset`, or may they name one as a base and then override?**
   *Recommendation:* allow naming one as a base for authoring convenience, but always record the fully expanded, read-back option set as the run's configuration, and require the run plan's identity hash to be computed over the expanded set rather than the preset name. *Trade-off:* naming a preset is far more ergonomic for a modpack author writing a plan by hand, but `FABULOUS` resolves differently on AMD vs NVIDIA and on macOS vs Windows, so two machines running "the same" plan would be running different settings. Recording the expansion keeps ergonomics without letting the preset name become a false identity. Forbidding presets outright is simpler to reason about but makes plans verbose.

5. **How should Laymark verify effective fullscreen state — GLFW directly, or accept the requested flag?**
   *Recommendation:* call `GLFW.glfwGetWindowMonitor(Minecraft.getInstance().getWindow().handle()) != 0L`, which is the same check `Window#setMode` performs internally. *Trade-off:* it reaches around the Minecraft API to LWJGL, which is one more thing to keep working across versions and looks like a smell in review. The alternative, trusting `Window#isFullscreen()`, would let a fullscreen request that GLFW silently declined (no suitable monitor — vanilla logs "Failed to find suitable monitor for fullscreen mode" and resets the flag) pass the preset verification gate. An AT on `actuallyFullscreen` is a third option but would make the Fabric port need its own accessor mechanism for no gain.

6. **Should the frame-timing capture assert `FramerateLimitTracker#getThrottleReason() == NONE` continuously, or only sample it at capture start and end?**
   *Recommendation:* continuously, on every frame, and fail the run on any non-`NONE` sample. *Trade-off:* a per-frame check on the render thread is measurable overhead in a tool whose entire output is frame timings, and it adds a branch to the hottest path Laymark owns. Sampling only at the boundaries is nearly free but cannot distinguish "throttled for 200 ms mid-capture" from "never throttled," and the AFK transition happens exactly once, mid-run, at the 60-second mark — a boundary sample at 55 s and 65 s would catch it, but one at 0 s and 50 s would not. If per-frame is judged too expensive, the fallback is per-frame recording of the reason without comparison, and a single pass over the recorded reasons after the capture.
