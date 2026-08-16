# LuckPerms build architecture: lessons for Laymark

Research date: 2026-08-16. LuckPerms source inspected at commit [`9b4fe67791e899778162f18df7c8a4df0fa58c77`](https://github.com/LuckPerms/LuckPerms/tree/9b4fe67791e899778162f18df7c8a4df0fa58c77).

## Conclusion

Laymark should adopt LuckPerms' main architectural idea: most behavior lives in real common modules, while each loader module translates loader lifecycle and services into common abstractions and produces its own artifact. Do not copy LuckPerms' packaging wholesale, and do not treat it as a multi-Minecraft-version build system. The inspected LuckPerms revision compiles its Minecraft-facing common module and loader modules against one Minecraft version (26.2), then advertises a compatible runtime range where appropriate.

For Laymark, start with exactly Minecraft 26.1.2 and two artifacts. Keep the domain engine loader- and Minecraft-free, keep the 26.1.2 vanilla implementation shared between Fabric and NeoForge, and make the two loader projects adapters. Add a Minecraft-version seam only when a second supported version proves where vanilla APIs differ.

## What LuckPerms actually does

LuckPerms is one Gradle multi-project build. Its settings declare `api`, `common`, smaller common submodules, and separate platform projects including `fabric`, `forge`, and `neoforge`; several platforms also have a thin `loader` child project ([`settings.gradle`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/settings.gradle#L20-L45)). The repository's own README describes `common` as the implementation shared by platform modules ([README project layout](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/README.md#L233-L239)).

The important dependency direction is:

```text
api
 ^
common                 platform-neutral implementation
 ^
common:minecraft       shared vanilla-Minecraft implementation
 ^              ^
fabric          neoforge             loader-specific implementations
                    ^
             neoforge:loader         thin outer bootstrap/package
```

`common:minecraft` applies Fabric Loom only to obtain a Minecraft compile classpath and depends on `common`; its Java sources use vanilla `net.minecraft` types but no Fabric APIs ([module build](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/common/minecraft/build.gradle), [shared Minecraft plugin base](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/common/minecraft/src/main/java/me/lucko/luckperms/common/minecraft/MinecraftLuckPermsPlugin.java#L48)). Using Loom here is a build-classpath choice, not evidence that the shared code belongs to Fabric.

The Fabric project applies Loom, registers Fabric dependencies, and shades `common`, `common:minecraft`, and `common:placeholders` into the Fabric artifact ([`fabric/build.gradle`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/build.gradle#L1-L68)). Its bootstrap implements Fabric's server initializer and converts Fabric lifecycle callbacks, loader metadata, directories, and logging into shared LuckPerms services ([`LPFabricBootstrap`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/src/main/java/me/lucko/luckperms/fabric/LPFabricBootstrap.java#L53-L218)).

The NeoForge implementation applies ModDevGradle and also shades `common` plus `common:minecraft` ([`neoforge/build.gradle`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/build.gradle#L1-L31)). Its bootstrap performs the equivalent translation from NeoForge events, `ModList`, and `FMLPaths` into the shared abstractions ([`LPNeoForgeBootstrap`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/src/main/java/me/lucko/luckperms/neoforge/LPNeoForgeBootstrap.java#L63-L243)). This is the adapter pattern Laymark should copy: common code defines the semantics; loader code performs registration and translation.

The command seam is a particularly clear example. A shared Minecraft class implements command behavior against a supplied Brigadier dispatcher ([`MinecraftCommandExecutor`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/common/minecraft/src/main/java/me/lucko/luckperms/common/minecraft/command/MinecraftCommandExecutor.java#L57-L77)); the Fabric adapter only connects it to `CommandRegistrationCallback` ([`FabricCommandExecutor`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/src/main/java/me/lucko/luckperms/fabric/FabricCommandExecutor.java#L28-L38)), while the NeoForge adapter connects the same behavior to `RegisterCommandsEvent` ([`NeoForgeCommandExecutor`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/src/main/java/me/lucko/luckperms/neoforge/NeoForgeCommandExecutor.java#L28-L40)). Laymark should aim for adapters this small.

LuckPerms additionally builds its substantive NeoForge implementation as an inner shaded jar, then has `neoforge:loader` embed and instantiate it through a custom classloader ([implementation jar](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/build.gradle#L26-L56), [outer loader packaging](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/loader/build.gradle#L59-L74), [loader entrypoint](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/loader/src/main/java/me/lucko/luckperms/neoforge/loader/NeoForgeLoaderPlugin.java#L41-L67)). Laymark does not presently need this extra classloader boundary. It should use normal loader packaging unless a concrete dependency-isolation problem appears.

## How versions and toolchains are parameterized

LuckPerms centralizes Gradle plugin versions in a version catalog, including Loom, ModDevGradle, Shadow, and licensing plugins ([`gradle/libs.versions.toml`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/gradle/libs.versions.toml)). Root conventions set ordinary subprojects to Java release 11, while the current Minecraft-facing modules override the release to Java 25 ([root build](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/build.gradle#L10-L20), [`common:minecraft`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/common/minecraft/build.gradle#L5-L15)). It registers the Foojay resolver but does not declare a Gradle Java toolchain; CI explicitly installs Java 25 ([settings](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/settings.gradle#L15-L17), [CI](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/.github/workflows/ci.yml#L26-L36)). Laymark can improve reproducibility by declaring its Java 25 toolchain as well as using a matching CI JDK.

Its game dependency parameterization is not fully uniform:

- `common:minecraft` and Fabric hardcode Minecraft 26.2; Fabric also hardcodes its loader and Fabric API versions in its build script ([shared module](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/common/minecraft/build.gradle#L13-L16), [Fabric dependencies](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/build.gradle#L32-L48)).
- NeoForge reads `minecraftVersion` and `neoForgeVersion` from that project's `gradle.properties` ([properties](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/gradle.properties), [build use](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/neoforge/build.gradle#L15-L17)).
- Fabric's metadata declares `minecraft >=26.1`, even though compilation happens once against 26.2 ([`fabric.mod.json`](https://github.com/LuckPerms/LuckPerms/blob/9b4fe67791e899778162f18df7c8a4df0fa58c77/fabric/src/main/resources/fabric.mod.json#L32-L43)). This is a declared runtime compatibility range, not separate compilation or testing for every version in the range.

Therefore LuckPerms is good evidence for shared-code/platform-adapter composition, but not a template for simultaneously compiling a Cartesian matrix of Minecraft versions and loaders.

The LuckPerms 26.1-to-26.2 update changed only the shared Minecraft/Fabric dependency declarations and NeoForge/Forge properties, not Java sources ([version bump commit](https://github.com/LuckPerms/LuckPerms/commit/1324c229fda01228a1f0b055a30ae220031612a7)). That is a useful example of reusing one implementation across a compatible version line, but it is not proof that every runtime behavior remained suitable for a benchmark harness.

## Recommended Laymark module layout

For the first implementation, keep the layout small and explicit:

```text
core/                 pure Java plans, state machine, schemas, statistics
minecraft-common/     Minecraft 26.1.2 vanilla control and shared Mixins
fabric/               Fabric entrypoint and LoaderPort adapter
neoforge/             NeoForge entrypoint and LoaderPort adapter
runner/               external experiment/process controller
protocol/             shared JSON schemas and generated types
```

Gradle dependency direction should be one-way:

```text
fabric   -> minecraft-common -> core
neoforge -> minecraft-common -> core
```

Neither `core` nor `minecraft-common` may import Fabric or NeoForge packages. `fabric` and `neoforge` may contain only loader registration, loader metadata/mod inventory, loader-owned command dispatch, and any unavoidable integration shim. The benchmark state machine, settings/world control, Spark grammar, Chunky policy, frame/GPU recorders, result model, and acceptance rules remain shared.

Like LuckPerms, each loader project should own its build plugin and final artifact. Unlike LuckPerms' NeoForge packaging, Laymark should initially embed ordinary common Gradle dependencies using the loader's supported jar packaging instead of Shadow plus a custom classloader. Keep dependency/plugin versions in a root version catalog and keep the compatibility tuple in one authoritative Gradle data structure rather than splitting hardcoded versions across project files.

## Minecraft-version policy

The first supported and tested target is **Minecraft 26.1.2**, because that is the Lucent pack baseline. The initial metadata should claim only the compatibility range actually exercised by CI; do not copy LuckPerms' broad lower-bound declaration. Laymark touches unstable client surfaces—world creation, settings application barriers, frame injection, render/GPU state, Spark command integration, and Chunky readiness—so “it loads” is not sufficient compatibility evidence.

Do not create speculative per-version modules yet. Instead:

1. Pin one target tuple for each loader: Minecraft 26.1.2, Java 25, exact loader/API, Spark, and Chunky versions.
2. Compile `minecraft-common` once against 26.1.2 and consume it from both loader projects, matching LuckPerms' shared Minecraft module pattern.
3. Put unstable vanilla calls behind a small shared `MinecraftPort`/capability boundary even though 26.1.2 has only one implementation. This seam is for Minecraft versions; `LoaderPort` remains separately responsible for loaders.
4. When adding the second Minecraft version, first try recompiling the shared implementation against the new tuple. If source-compatible, add a versioned Gradle target/test fixture without duplicating source. If not, add the smallest version-specific `MinecraftPort` implementation or source overlay.
5. Publish artifacts with both loader and Minecraft compatibility in their identity and record the exact tuple in every benchmark result. Never silently widen metadata after only a compile check.

A future repository may therefore grow toward:

```text
core/
minecraft-common/             shared source and contracts
minecraft-versions/
  mc26.1/                     only the compatibility code that differs
  mc-next/
loaders/
  fabric/
  neoforge/
```

Do not multiply every module by every patch version. Group versions only after behavioral conformance tests show they share the same implementation; split a version line when vanilla signatures or semantics diverge. This preserves the LuckPerms-style deep common module while acknowledging that Laymark's client/render instrumentation is more version-sensitive than LuckPerms' server permission logic.

## Concrete acceptance rule

For any supported Minecraft version, both Fabric and NeoForge artifacts must execute the same resolved benchmark plan and produce schema-equivalent events/results. Loader conformance does not imply equal performance, and Minecraft-version conformance does not make scores comparable across versions. A/B comparisons remain inside one exact Minecraft/loader/instrumentation tuple.
