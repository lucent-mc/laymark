# Laymark loader-portability research

Research date: 2026-08-16. Target: Minecraft 26.1.2 / Java 25.

## Conclusion

Laymark should publish separate NeoForge and Fabric artifacts from one repository, with benchmark policy in pure shared code and vanilla control in a shared Minecraft-version module. It does not need reflection or loader-specific code to change normal Minecraft settings. The only loader adapters needed initially are entry/lifecycle wiring, installed-mod inventory, and client-command execution for Spark.

The one deliberate cross-loader Mixin should bracket the same vanilla `GameRenderer.render(DeltaTracker, boolean)` method in both artifacts. This gives Laymark one definition of “whole frame.” NeoForge's `RenderFrameEvent.Pre/Post` already brackets that call, but Fabric's `LevelRenderEvents.START_MAIN/END_MAIN` brackets only the main world pass. Using the two public event pairs as if they were equivalent would produce differently scoped measurements.

Do not compare or pool absolute NeoForge and Fabric scores. Loader ID/version, loader API version, Laymark artifact, and instrumentation versions are part of the experimental stratum. Cross-loader CI proves behavioral and schema parity, not performance equality.

## Compatibility tuple

The initial pinned matrix should be:

| Component | NeoForge fixture | Fabric fixture |
| --- | --- | --- |
| Minecraft | 26.1.2 | 26.1.2 |
| Java | 25 | 25 |
| Loader | NeoForge 26.1.2.95 | Fabric Loader 0.19.3 |
| Loader API | included with NeoForge | Fabric API 0.155.2+26.1.2 |
| Spark | 1.10.173 | 1.10.173-fabric |
| Chunky | 1.5.4 for the current Lucent fixture | 1.5.3 |

Fabric's official metadata currently marks Loader 0.19.3 stable for 26.1.2 ([Fabric Meta response](https://meta.fabricmc.net/v2/versions/loader/26.1.2)), and the official Maven repository publishes Fabric API 0.155.2+26.1.2 ([artifact directory](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.2%2B26.1.2/)). The official Fabric guides target Minecraft 26.1.2 and Java 25 ([developer guides](https://docs.fabricmc.net/develop/index), [26.1 porting guide](https://docs.fabricmc.net/develop/porting/index)). Minecraft 26.1 is unobfuscated, so both builds use Mojang's official vanilla names; Fabric Loom explicitly uses its non-obfuscated plugin for 26.1 and later ([Loom reference](https://docs.fabricmc.net/develop/loom/)).

The Modrinth API lists Spark 1.10.173 for Fabric 26.1.2 ([filtered version response](https://api.modrinth.com/v2/project/spark/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%2226.1.2%22%5D)). Chunky's releases are asymmetric: 1.5.4 is NeoForge/Forge-only while 1.5.3 has Fabric and NeoForge builds ([Chunky versions](https://modrinth.com/plugin/chunky/versions)). This is not a reason to introduce different orchestration semantics: both ports use Chunky's loader-neutral API, check its API version, and record the exact implementation version. A conformance fixture may pin 1.5.3 on both loaders; the current Lucent NeoForge fixture may retain 1.5.4.

## Code ownership

Use a Gradle multi-project repository with this dependency direction:

```text
core                            pure Java: plans, state machine, results, statistics
  ^
minecraft-common                vanilla/LWJGL implementation and shared Mixin
  ^                         ^
neoforge                    fabric
            thin entrypoints, loader adapters, and artifact assembly

runner               external TypeScript process/pack orchestrator
protocol             schemas and generated Java/TypeScript types
build-logic          conventions and exact compatibility-target descriptors
```

This is intentionally LuckPerms-shaped. Its build declares separate `common`, `common:minecraft`, `fabric`, and `neoforge` projects ([pinned settings](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/settings.gradle#L20-L45)); Fabric and NeoForge each package the shared modules and provide their own bootstrap implementations ([Fabric build](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/build.gradle#L1-L68), [NeoForge build](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/build.gradle#L1-L31)). The detailed comparison is in [the LuckPerms architecture research](./luckperms-build-architecture-research.md).

Compile the `minecraft-common` implementation into each loader artifact. Keep `core` a real Minecraft- and loader-free Java library. Direct thin adapters are preferable to adding a third cross-loader abstraction dependency: there are only a few loader-owned capabilities, and two implementations plus an in-memory test implementation make the seam concrete.

The `core` runtime owns:

- the persisted benchmark state machine and all timeout/failure policy;
- resolved plans, protocol events/results, experiment identity, and artifact naming;
- Spark command intent and Chunky preparation intent without importing either mod's implementation;
- scoring-neutral raw measurement models.

The `minecraft-common` implementation owns:

- vanilla `Minecraft`, `Options`, `OptionInstance`, `Window`, world creation, integrated-server readiness, player/camera control, and effective-value checks;
- Spark command grammar and artifact discovery;
- Chunky's common API orchestration;
- whole-frame CPU timestamps and OpenGL timestamp-query rings;
- the exact version-pinned Mixin and other vanilla descriptors.

The loader adapter owns:

- startup, client-ready/tick/stopping delivery;
- loader ID/version and installed-mod inventory;
- executing a registered client command and returning typed success/failure;
- locating loader-specific metadata needed for the environment record.

Minecraft 26.1.2 / Java 25 is the first and only initial target, not the intended lifetime limit. Future support should add exact target descriptors, first attempt to recompile and behavior-test `minecraft-common`, and introduce a narrow source overlay or new Minecraft-port implementation only where vanilla behavior requires it. A final jar always composes one core revision, one verified Minecraft implementation, and one loader adapter. Never put version conditionals in `core`, never package several incompatible Minecraft implementations into a universal jar, and never publish a compatibility range until every claimed tuple passes both loader builds and in-game contract smoke tests.

Fabric exposes client lifecycle and tick callbacks in `ClientLifecycleEvents` and `ClientTickEvents` ([pinned 26.1.2 lifecycle API](https://github.com/FabricMC/fabric/tree/f9468776b662dd2ab7875e9cdcdf2b653171309d/fabric-lifecycle-events-v1/src/client/java/net/fabricmc/fabric/api/client/event/lifecycle/v1)). Its public client-command API exposes the active Brigadier dispatcher after joining a server ([pinned `ClientCommands`](https://github.com/FabricMC/fabric/blob/f9468776b662dd2ab7875e9cdcdf2b653171309d/fabric-command-api-v2/src/client/java/net/fabricmc/fabric/api/client/command/v2/ClientCommands.java#L71-L80)). The tracer bullet should prove the exact command-source invocation through an integration test rather than importing Fabric's `impl` package. If the public dispatcher is insufficient, isolate a version-pinned shim in the Fabric port; do not leak it into shared policy.

## Settings and world control

No loader API is required for normal settings. On the client/render thread, shared code uses `Minecraft.getInstance().options`, public `OptionInstance.set(...)` accessors, `Window` methods, and the vanilla reload/rebuild paths. The important problem is not field access but waiting for each setting's effect: renderer rebuild, resource reload, framebuffer resize, integrated-server distance convergence, or process restart. The benchmark plan already classifies and verifies those barriers.

Use a narrow NeoForge Access Transformer or Fabric class tweaker only if an otherwise suitable vanilla member is inaccessible. Fabric documents class tweakers as the access-level mechanism for vanilla classes ([Fabric class tweakers](https://docs.fabricmc.net/develop/class-tweakers/index)); NeoForge documents Access Transformers for the corresponding purpose ([NeoForge AT documentation](https://docs.neoforged.net/docs/advanced/accesstransformers/)). Neither should replace a public setter, and neither changes behavior. A Mixin is appropriate only for an actual hook such as the identical frame boundary.

Fresh world creation and readiness are also vanilla concerns. Both ports should call the same `Minecraft.createWorldOpenFlows().createFreshLevel(...)` flow only after preset readback succeeds, then wait on the same client and integrated-server state. The launcher starts the instance only; it does not select a save that cannot exist yet.

## Identical whole-frame instrumentation

NeoForge 26.1's `RenderFrameEvent.Pre/Post` is posted immediately around `GameRenderer.render(...)` ([event definition](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/event/RenderFrameEvent.java), [posting hook](https://github.com/neoforged/NeoForge/blob/ad038e822a142901aeb33b0eedde0a892588b662/src/client/java/net/neoforged/neoforge/client/ClientHooks.java#L880)). Fabric's `START_MAIN` occurs after sky drawing and chunk uploads, while `END_MAIN` occurs before clouds, weather, late debug, and framebuffer combination ([pinned Fabric event definitions](https://github.com/FabricMC/fabric/blob/f9468776b662dd2ab7875e9cdcdf2b653171309d/fabric-rendering-v1/src/client/java/net/fabricmc/fabric/api/client/rendering/v1/level/LevelRenderEvents.java#L88-L223)). Those are useful pass-level diagnostics but are not a full-frame equivalent.

Therefore use one client-only, required Mixin in shared source:

- inject at `HEAD` and normal `RETURN` of the exact 26.1.2 `GameRenderer.render(DeltaTracker, boolean)` descriptor;
- feed both callbacks into the same common recorder;
- require the injection to apply and fail startup self-checks if it does not;
- pin it to the Minecraft compatibility tuple and re-audit on every supported Minecraft version;
- on NeoForge integration tests, assert that its samples align with `RenderFrameEvent.Pre/Post` within hook overhead;
- keep Fabric `LevelRenderEvents` available only for later pass-level diagnostics.

Both loaders support Mixin configuration: Fabric declares client Mixin configs in `fabric.mod.json` ([Fabric mod metadata](https://docs.fabricmc.net/develop/loader/fabric-mod-json#mixins)), and NeoForge declares them in its mod metadata ([NeoForge mod files](https://docs.neoforged.net/docs/gettingstarted/modfiles/#mixin-configuration-properties)). A single shared injection also avoids two subtly different GPU query intervals.

## Spark and Chunky adapters

Spark remains diagnostic CPU instrumentation, not the source of GPU timings. Preserve the same `sparkc profiler start ...` command grammar in shared code and let each loader port execute that already-registered client command. Confirm active capture and the stable `.sparkprofile` artifact instead of relying on chat text or a sleep. Spark's public API does not expose full sampler lifecycle, so importing its private sampler implementation would create a worse compatibility seam than the command adapter.

Chunky publishes a common developer API obtained from `ChunkyProvider`, with listener and selection/task surfaces ([Chunky developer API](https://github.com/pop4959/Chunky/wiki/Developer-API), [source repository](https://github.com/pop4959/Chunky)). Keep this integration in `minecraft-common` when the same API artifact resolves for both loader builds. Put only provider bootstrap differences in a loader module if compilation proves that necessary. The existing 1.5.4 completion caveat remains relevant to the NeoForge fixture; all versions should require progress completion, a save, outstanding-work drain, and server quiescence rather than trusting a generic completion event alone.

## Test and result rules

CI must build both artifacts and run the same contract fixture through NeoForge, Fabric, and an in-memory adapter. Required parity means:

- the same resolved plan reaches the same semantic state transitions;
- settings are applied before world creation and profiler startup follows join readiness;
- failure codes, event ordering, and result schemas match;
- each artifact records loader and instrumentation identity;
- the full-frame hook applies exactly once and emits balanced start/end samples.

It does **not** mean equal timings. Every A/B pair and every greedy stacking round stays within one exact tuple: Minecraft, loader, loader API, Laymark build, Spark, Chunky, JVM, mod set, and machine environment. A candidate can be accepted for NeoForge and rejected for Fabric; the report should show two decisions rather than averaging them into one.
