# Laymark developer-loop research

Research date: 2026-08-16. Target: Minecraft 26.1.2 / Java 25 / NeoForge 26.1.2.95. Primary dev OS: Windows.

Resolves [Determine the developer loop: IDE-launched dev instance and change-to-feedback path](https://github.com/lucent-mc/laymark/issues/14).

## Conclusion

There is no single hot-reload story. There is a four-tier loop, and the cheapest tier covers most of Laymark:

1. **Plain JUnit, no Minecraft, no loader** (seconds). All of `core` and the whole `runner` module. Largest and cheapest win; this is where most Laymark iteration should happen.
2. **Headless modded JUnit** (tens of seconds). ModDevGradle's `neoForge { unitTest { enable() } }` boots FML and Minecraft classes with no client window.
3. **Headless GameTest server** (~a minute). The `gameTestServer` run launches, runs registered gametests, exits.
4. **Full client with Spark and Chunky present** (minutes). Only for things that need a rendering client.

Within tier 4, in-process reload is narrow and should be treated as a bonus, not the plan:

- **Method-body edits to Laymark's own non-Mixin classes** hot-swap on a stock JDK 25 JVM. Anything structural — new method, new field, changed signature, changed hierarchy, *and therefore any added or removed lambda* — does not.
- **Mixin re-application at runtime is real, and better than its reputation, but constrained.** The hot-swap agent ships inside the exact Mixin build NeoForge 26.1.2 uses, and NeoForge's Mixin wiring does construct the hot-swapper. On a stock JVM it can only change the *bodies* of already-merged handlers. It can never change a Mixin's target classes, interfaces, inner classes, or priority.
- **JetBrains Runtime genuinely lifts the JVM half of that ceiling**, and JBR 25 exists and is current. It is provisionable as a Gradle toolchain. The catch is that *Minecraft itself* must run on JBR with an explicit flag; the IDE's own runtime is irrelevant.
- **Everything else forces a relaunch**: new/changed registrations, new Mixin classes, new mods on the classpath, `neoforge.mods.toml` changes, and any change to mod metadata.

Two ergonomics matter more than any hot-swap trick, and both are cheap:

- **Set IntelliJ's "Build and run using" to *IntelliJ IDEA*, not Gradle.** ModDevGradle reads that setting and, only when it is set to IDEA, points the running game's mod folders at IDEA's incremental compiler output. Otherwise every edit pays a full Gradle module recompile. This is verified in ModDevGradle's source and is documented nowhere obvious (section 1a).
- **Use Quick Play.** `--quickPlaySingleplayer <world>` is a vanilla 26.1.2 client argument, so a relaunch goes straight back into the benchmark world. Combined with a persistent `runs/client` directory, that is the real "survive a relaunch" mechanism: **the world and options survive on disk; the process does not.**

Laymark should design for **relaunch being cheap and deterministic**, not for hot-swap being clever.

## Verification basis

Claims below are checked against pinned, published artifacts rather than documentation prose wherever possible. Two sources were cross-checked independently and agreed, including on the Chunky trap in section 2.

| Component | Pinned artifact | How verified |
| --- | --- | --- |
| NeoForge | `26.1.2.95` | Latest in [the NeoForged Maven metadata](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml); pinned by the official [MDK-26.1.2 `gradle.properties`](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/gradle.properties) (`neo_version=26.1.2.95`) |
| Gradle plugin | ModDevGradle (`net.neoforged.moddev`) `2.0.143` | [MDK-26.1.2 `build.gradle`](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/build.gradle); present in [the moddev-gradle Maven metadata](https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/maven-metadata.xml). NeoGradle also exists ([Getting Started](https://docs.neoforged.net/docs/gettingstarted/)) but is not what the 26.1.2 MDK uses. |
| FancyModLoader | **`11.0.15`** | Read from `config.json` inside `neoforge-26.1.2.95-userdev.jar`, which declares `net.neoforged.fancymodloader:loader:11.0.15`. The jar was downloaded from `https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/11.0.15/loader-11.0.15.jar` and decompiled with `javap`. |
| Mixin | `net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7` | [NeoForge `projects/neoforge/build.gradle` @ tag `26.1.2`](https://github.com/neoforged/NeoForge/blob/26.1.2/projects/neoforge/build.gradle) line 135 declares `libraries("net.fabricmc:sponge-mixin:${project.mixin_version}")`; version from [`gradle.properties`](https://github.com/neoforged/NeoForge/blob/26.1.2/gradle.properties). NeoForge uses **Fabric's fork**, not upstream Sponge's build. Jar downloaded from `maven.fabricmc.net` and inspected. |
| DevLaunch | `net.neoforged:DevLaunch:1.0.2` | ModDevGradle `RunUtils.DEV_LAUNCH_GAV` |
| Minecraft | `26.1.2`, Java 25 | [Mojang piston-meta 26.1.2 manifest](https://piston-meta.mojang.com/v1/packages/edcfd100a4856650b6e9797bac8f7fd76821979e/26.1.2.json) — `"javaVersion": {"component": "java-runtime-epsilon", "majorVersion": 25}` |
| Spark | `1.10.173-neoforge` | [Modrinth version API](https://api.modrinth.com/v2/project/spark/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%2226.1.2%22%5D); jar downloaded and SHA-1 matched |
| Chunky | `1.5.4` NeoForge build, Modrinth version ID `EyCqftOK` | [Modrinth version API](https://api.modrinth.com/v2/project/chunky/version?loaders=%5B%22neoforge%22%5D&game_versions=%5B%2226.1.2%22%5D); jar downloaded and SHA-1 matched — see the trap in section 2 |

**Note on version numbers.** NeoForge's own repo `gradle.properties` at tag `26.1.2` says `fancy_mod_loader_version=11.0.5`. That is the tag base, not what build `.95` ships. The authoritative answer for a specific NeoForge build is its userdev `config.json`, which gives **11.0.15**. All FML bytecode claims below were verified against `loader-11.0.15.jar`.

**Note on source permalinks.** Neither ModDevGradle nor FancyModLoader tags releases; both use gradleutils' `<tag>.<commits-since-tag>` scheme. The corresponding commits are ModDevGradle `40955a9c6172682c036729025f6a6937eaba9827` for 2.0.143 and FancyModLoader `e8c54b8a04241b0400148e8745e4e209726f4439` for loader 11.0.15. Where a link below points at `main`, the claim was independently confirmed against the shipped jar and that is stated.

## 1. The build plugin and what it generates

The official 26.1.2 template uses `net.neoforged.moddev` 2.0.143 ([MDK build.gradle](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle/blob/main/build.gradle)). Laymark should use ModDevGradle.

The MDK's `neoForge { runs { ... } }` block declares `client`, `server`, `gameTestServer`, and `data`, plus `configureEach` for shared logging, and a `mods { }` block binding the mod ID to a source set:

```groovy
neoForge {
    version = project.neo_version
    runs {
        client { client(); systemProperty 'neoforge.enabledGameTestNamespaces', project.mod_id }
        server { server(); programArgument '--nogui'; ... }
        gameTestServer { type = "gameTestServer"; ... }
        data { clientData(); programArguments.addAll '--mod', project.mod_id, '--all', ... }
        configureEach { systemProperty 'forge.logging.markers', 'REGISTRIES'; logLevel = org.slf4j.event.Level.DEBUG }
    }
    mods { "${mod_id}" { sourceSet(sourceSets.main) } }
}
```

Per-run options are `gameDirectory`, `programArguments`/`programArgument()`, `jvmArguments`/`jvmArgument()`, `systemProperties`/`systemProperty()`, `environment()`, `logLevel`, `ideName`, `disableIdeRun()`, `sourceSet`, `loadedMods`, `taskBefore`, and `devLogin` ([ModDevGradle README](https://github.com/neoforged/ModDevGradle/blob/main/README.md)). `programArguments` is what carries Quick Play (section 5).

Java toolchain is 25, as the MDK comments: "Mojang ships Java 25 to end users in 26.1.2, so mods should target Java 25."

**Tasks created:** `runClient`, `runServer`, `runData`, `runGameTestServer` (one `run<Name>` per declared run), `createLaunchScripts`, and internally `prepare<Name>Run`, `create<Name>LaunchScript`, `createMinecraftArtifacts`, `downloadAssets`, and the IDE-sync aggregator **`neoForgeIdeSync`**. There is no `idePostSync` task in ModDevGradle — that name appears only in its README as *another* plugin's task that causes a "Task `idePostSync` not found" error when migrating.

**The launched main class is DevLaunch, not FML directly.** `RunUtils.DEV_LAUNCH_MAIN_CLASS = "net.neoforged.devlaunch.Main"` and `DEV_LAUNCH_GAV = "net.neoforged:DevLaunch:1.0.2"`. `net.neoforged.fml.startup.Client` is the *first line of the program-arguments file*, which DevLaunch expands and reflectively invokes. `Client.main` in turn starts FML and calls `net.minecraft.client.main.Main`, passing through whatever program arguments FML did not consume ([`Client.java`](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/startup/Client.java)). That pass-through is why vanilla arguments such as `--quickPlaySingleplayer` reach the game.

The effective command line is:

```
java @build/moddev/clientRunVmArgs.txt -Dfml.modFolders=... net.neoforged.devlaunch.Main @build/moddev/clientRunProgramArgs.txt
```

The JVM-args file content comes from the NeoForge **userdev config** (`config.json` inside `neoforge-26.1.2.95-userdev.jar`), not from ModDevGradle: `--sun-misc-unsafe-memory-access=allow`, `--enable-native-access=ALL-UNNAMED`, `--add-opens java.base/java.lang.invoke=ALL-UNNAMED`, `--add-exports jdk.naming.dns/com.sun.jndi.dns=java.naming`. Its `modules` list is empty and it sets `"features": {"noLegacyClasspath": true}`. **There is no `-javaagent` anywhere in it.**

**Cosmetic gotcha worth pre-empting:** ModDevGradle 2.0.143's generated Minecraft version list tops out at `26.1-snapshot-2`, so resolving NeoForge `26.1.2.95` takes the unknown-version branch and logs *"Parsed unknown MC version 26.1.2 … Using capabilities of latest known Minecraft version"*. This is benign — the capabilities it falls back to (`javaVersion=25`, `splitDataRuns=true`, `legacyClasspath=false`) are the correct ones — but it looks alarming. ModDevGradle 2.0.144 updates the list.

### 1a. The IntelliJ run is a plain Application run — and one IDE setting decides the loop

**This is the most consequential detail in the whole loop.** ModDevGradle builds an IntelliJ `Application` run configuration (via JetBrains' `gradle-idea-ext` plugin) rather than a Gradle-delegated run ([`IntelliJIntegration.java`](https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/internal/IntelliJIntegration.java)):

```java
appRun.setModuleName(getIntellijModuleName(project, sourceSet));
appRun.setWorkingDirectory(run.getGameDirectory().get().getAsFile().getAbsolutePath());
var modFoldersProvider = getModFoldersProvider(project, outputDirectory, run.getLoadedMods(), null);
appRun.setJvmArgs(... + RunUtils.escapeJvmArg(modFoldersProvider.getArgument()));
appRun.setMainClass(RunUtils.DEV_LAUNCH_MAIN_CLASS);
```

Because it is an ordinary JVM launch, the debugger attaches natively. (`RunGameTask`'s own javadoc makes the Gradle-side intent explicit too: "By extending JavaExec, we allow IntelliJ to automatically attach a debugger to the forked JVM.")

**Where `fml.modFolders` points depends on IDEA's "Build and run using" setting.** `IntelliJOutputDirectoryValueSource` is documented as: "Checks the IntelliJ project files for the setting that determines whether 1) the build is delegated and 2) the configured output directory... **Delegated builds use Gradles output directories, while non-delegated builds default to subdirectories of `out/`**" ([source](https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/internal/IntelliJOutputDirectoryValueSource.java)). It returns `null` — meaning "fall back to Gradle's output" — unless `.idea/gradle.xml` has `delegatedBuild` equal to `"false"`:

```java
var delegatedBuild = evaluateXPath(gradleXml, IDEA_DELEGATED_BUILD_XPATH);
if (!"false".equals(delegatedBuild)) { return null; }
```

When non-null, `getModFoldersProvider` resolves each source set to `<ideaOut>/production/classes` and `<ideaOut>/production/resources`.

| IDEA "Build and run using" | `fml.modFolders` points at | Recompile on Ctrl+F9 | Loop quality |
| --- | --- | --- | --- |
| **Gradle** (IDEA's default) | Gradle's `build/classes/java/main` + `build/resources/main` | A Gradle build; per JetBrains, "Gradle and Maven recompile the whole module" ([IDEA docs](https://www.jetbrains.com/help/idea/altering-the-program-s-execution-flow.html)) | Works, but every edit pays a full module recompile |
| **IntelliJ IDEA** (`delegatedBuild=false`) | IDEA's `out/production/{classes,resources}` | IDEA's incremental compiler — "only recompiles the changed files" (same source) | **The fast loop. Set this.** |

Two gating preconditions, both easy to trip:

1. Run configurations are generated **only during a Gradle sync from inside IDEA** (`IdeDetection.isIntelliJSync()`, i.e. `-Didea.sync.active=true`), never from a terminal `./gradlew`.
2. `obtain()` reads `.idea/gradle.xml` from disk, so **after flipping the delegation setting you must re-sync Gradle** for the emitted `-Dfml.modFolders` to change.

Incidentally, ModDevGradle's own README uses `-XX:+AllowEnhancedClassRedefinition` as its example JVM argument — the JetBrains Runtime flag from section 6c. It is not a plugin feature; it is just an example.

### 1b. No `-javaagent`, and `Instrumentation` is reliably *null* in a game run

Neither ModDevGradle nor DevLaunch nor the userdev config adds a `-javaagent`. More importantly, **the game entrypoint does not use FML's self-attach fallback at all.** `Entrypoint` calls `DevAgent.getInstrumentation()` and passes the result straight to `FMLLoader.create(Instrumentation, StartupArgs)`. Verified in the shipped `loader-11.0.15.jar`:

```
77: invokestatic  // Method net/neoforged/fml/startup/DevAgent.getInstrumentation:()Ljava/lang/instrument/Instrumentation;
82: invokestatic  // Method net/neoforged/fml/loading/FMLLoader.create:(Ljava/lang/instrument/Instrumentation;Lnet/neoforged/fml/startup/StartupArgs;)Lnet/neoforged/fml/loading/FMLLoader;
```

`DevAgent.getInstrumentation()` returns a static field populated only by `premain`/`agentmain`. With no `-javaagent`, it is `null`, and `null` is explicitly tolerated — `FMLLoader` guards with `if (instrumentation != null)`.

`InstrumentationHelper`, with its spawn-a-child-process self-attach and ByteBuddy fallback ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/startup/InstrumentationHelper.java)), is reached only from the **JUnit** path (`FMLLoader.create(StartupArgs)`), not from `runClient`.

The single consequence of a null `Instrumentation` in a client run is that `ClassLoadingGuardian` — a developer safety net that fails fast when a mod or Minecraft class is loaded by the wrong classloader — is not installed. Nothing functional breaks. It also conveniently removes a possible interference source for hot-swap.

Practical consequence: to use the Mixin hot-swap agent you add `-javaagent:<sponge-mixin jar>` yourself via `jvmArgument(...)`. That agent carries its own `Instrumentation` and does not depend on FML's.

## 2. Getting Spark and Chunky into a dev run

FancyModLoader registers exactly four candidate locators, unconditionally, in `FMLLoader.runDiscovery()`:

```java
additionalLocators.add(new GameLocator());
additionalLocators.add(new InDevFolderLocator());
additionalLocators.add(new InDevJarLocator());
additionalLocators.add(new ModsFolderLocator());
```

**(a) On the runtime classpath — authoritative.** `InDevJarLocator` scans the classpath for any jar containing `META-INF/neoforge.mods.toml` and adds it as a mod ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/moddiscovery/locators/InDevJarLocator.java); the constant is `JarModsDotTomlModFileReader.MODS_TOML`, matching [the NeoForge mod-files docs](https://docs.neoforged.net/docs/gettingstarted/modfiles/)). It also picks up game libraries via the `Type` manifest attribute. Note it requires `Files.isRegularFile` — **jars only**, not exploded directories.

The jar reaches the classpath because `runClient` adds `sourceSet.getRuntimeClasspath()`, and the IDEA run config inherits the module's runtime classpath via `setModuleName`.

The MDK documents the idiom with JEI as the example — API `compileOnly`, full artifact in `localRuntime`, "so that we do not publish a dependency on it". `localRuntime` is not a ModDevGradle feature; it is a plain user-declared configuration (`configurations { runtimeClasspath.extendsFrom localRuntime }`) whose only purpose is keeping the dependency out of the published POM.

**(b) In the run's `mods/` directory.** `ModsFolderLocator`'s no-arg constructor uses `FMLPaths.MODSDIR`, i.e. `<gameDir>/mods`, listing `*.jar` sorted case-insensitively ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/moddiscovery/locators/ModsFolderLocator.java)). The userdev client args contain no `--gameDir`, so the game directory is the process working directory, which ModDevGradle sets to `run.gameDirectory`. Prefer this when the point of the exercise is to mirror a real instance layout — which is Laymark's actual domain — because it is byte-identical to what a user has.

**Do not use (a) and (b) for the same jar.** Deduplication is by path only, and the Gradle cache copy and the `mods/` copy are different paths, so you get a duplicate-mod error.

**(c) `additionalRuntimeClasspath` — dead on 26.1.2, and it throws.** ModDevGradle computes `hasLegacyClasspath(versionIndex) { return versionIndex > MC_1_21_9_INDEX; }`, so anything newer than 1.21.9 has `legacyClasspath == false` (independently confirmed by the userdev config's `"noLegacyClasspath": true`). When false, ModDevGradle creates the configurations purely to reject you:

> "Tried to add a dependency to configuration %s, but there is no additional classpath anymore for Minecraft %s. Add the dependency to a standard configuration such as implementation or runtimeOnly."

This matches the README: "As of Minecraft 1.21.9, external dependencies do not need special handling anymore to be loaded in runs." FML dropped the separate legacy-classpath file in favour of putting everything on `java.class.path`, so plain `runtimeOnly`/`localRuntime` now reaches the game.

### Recommended snippet, with verified coordinates

Modrinth's Maven is officially documented at `https://api.modrinth.com/maven`, including the `exclusiveContent` filter on group `maven.modrinth` ([Modrinth Maven help article](https://support.modrinth.com/en/articles/8801191-modrinth-maven)).

```groovy
repositories {
    exclusiveContent {
        forRepository { maven { name = "Modrinth"; url = "https://api.modrinth.com/maven" } }
        filter { includeGroup "maven.modrinth" }
    }
}

configurations { runtimeClasspath.extendsFrom localRuntime }   // already in the MDK

dependencies {
    localRuntime "maven.modrinth:spark:1.10.173-neoforge"
    localRuntime "maven.modrinth:chunky:EyCqftOK"   // Modrinth version ID, NOT "1.5.4"
}
```

### Verified trap: `maven.modrinth:chunky:1.5.4` silently loads nothing

Chunky publishes several loader builds under the same Modrinth `version_number`, and the Maven bridge picks one arbitrarily. Downloading each and comparing SHA-1 against the Modrinth version API:

| Coordinate | SHA-1 of resolved jar | Contents | Verdict |
| --- | --- | --- | --- |
| `maven.modrinth:chunky:1.5.4` | `7146c551c12f3e044fdf46a7ff30709ee19417d6` | `META-INF/mods.toml` only, declaring `modLoader="javafml"`, `modId="forge"`, `loaderVersion="[38,)"` | **Forge build.** Never discovered by FML |
| `maven.modrinth:chunky:EyCqftOK` | `eec0049466be9dca966a7446df116c97ce000410` — matches the API's NeoForge 1.5.4 file | `META-INF/neoforge.mods.toml` | **Correct** |
| `maven.modrinth:spark:1.10.173-neoforge` | `c4e339e787aa32949fd56848d26cc7bbfe812ef6` — matches the API | `META-INF/neoforge.mods.toml` | **Correct** — Spark disambiguates the loader in its own version string |

The failure mode is nasty in the classpath case: the Forge jar resolves, downloads, sits on the classpath, and is simply never discovered, so the game starts and Chunky is just absent. In the `mods/` case FML at least rejects it explicitly, via `IncompatibleModReason.MINECRAFT_FORGE(filePresent("META-INF/mods.toml"))`.

**Recommendation: pin Modrinth version IDs, not version numbers, and assert expected SHA-1s via Gradle dependency verification.** That is worth doing regardless — Laymark's credibility rests on knowing exactly which instrumentation binaries were in a run. A quick sanity check for any third-party mod is `unzip -l <jar> | grep neoforge.mods.toml`; if a jar ships only `META-INF/mods.toml` it is a Forge mod and nothing will load it on NeoForge 26.1.2.

## 3. `fml.modFolders`: the mechanism, and it is *not* dev-only

**Producer.** ModDevGradle's `ModFoldersProvider` emits `<modid>%%<absolutePath>` entries joined by `File.pathSeparator` (`;` on Windows), as `"-Dfml.modFolders=%s".formatted(...)` ([`RunUtils.java`](https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/internal/RunUtils.java)). It is not written into the args file; it is a live command-line argument (a `CommandLineArgumentProvider` for Gradle runs, an inline JVM arg for IDEA runs).

**Consumer.** `InDevFolderLocator` reads the environment variable `MOD_CLASSES`, falling back to the system property ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/moddiscovery/locators/InDevFolderLocator.java)):

```java
var modFolders = Optional.ofNullable(System.getenv("MOD_CLASSES"))
        .orElse(System.getProperty("fml.modFolders", ""));
```

Note **`MOD_CLASSES` wins over `fml.modFolders`.** The 26.1.2.95 userdev config does declare `"env": {"MOD_CLASSES": "{source_roots}"}`, but ModDevGradle deliberately ignores run-type env vars on modern versions and only sets `MOD_CLASSES` for Minecraft ≤ 1.20.5.

Each entry splits on the literal `%%` into `<modid>%%<path>`; entries without `%%` are grouped under `"defaultmodid"`. Entries sharing a mod ID are grouped into one `VirtualJarManifestEntry` and handed to the pipeline as a single composite `JarContents`. This is how a multi-project Gradle build presents `core`'s classes, `minecraft-common`'s classes, `neoforge`'s classes, *and* their `resources` outputs as **one** mod file — exactly the shape Laymark needs. The locator returns `HIGHEST_SYSTEM_PRIORITY` so grouped entries are claimed before other locators can grab the individual directories.

**Scope: it works in production too.** This is the folklore-prone part, so it was checked at the bytecode level in the shipped `loader-11.0.15.jar`. `FMLLoader` does hold a `production` flag and logs `PROD`/`DEV`, but that flag does not gate locator registration:

```
private net.neoforged.fml.loading.FMLLoader$DiscoveryResult runDiscovery();
    Code:
         0: ldc_w  #1042   // String Discovering mods...
        17: new    #1050   // class .../locators/GameLocator
        29: new    #1053   // class .../locators/InDevFolderLocator
        41: new    #1056   // class .../locators/InDevJarLocator
        53: new    #1059   // class .../locators/ModsFolderLocator
        64: new    #1062   // class .../moddiscovery/ModDiscoverer
```

Straight-line code, no branch instructions between the list construction and the `ModDiscoverer` call. `ModDiscoverer` itself performs no production filtering; it just calls `ServiceLoaderUtil.loadEarlyServices(launchContext, IModFileCandidateLocator.class, additionalModFileLocators)`. And the shipped `InDevFolderLocator.class` constant pool confirms the names verbatim: `MOD_CLASSES` via `System.getenv`, `fml.modFolders` via `System.getProperty`, delimiter `%%`.

**Conclusion:** on NeoForge 26.1.2.95 / FML 11.0.15, `-Dfml.modFolders=modid%%/path/to/classes` is honoured in a production launch as well as a dev launch. The `InDev` name describes intent, not an enforced restriction. In production it normally finds nothing because nothing sets the property.

**Why Laymark should care.** Laymark's runner materialises mod sets into a real instance and launches it — a production launch. So the runner *could* inject a locally built harness into a production instance without installing a jar, which is a legitimate debug/self-test option. It is also a supply-chain surface: anything that can set `MOD_CLASSES` in the launch environment can inject code into a benchmarked instance. Since Laymark's value proposition is a trustworthy, auditable instrumentation closure, **the runner should record whether `MOD_CLASSES`/`fml.modFolders` was set for a run and refuse to treat such a run as an auditable result** unless explicitly configured to.

## 4. Mixin at runtime: what is real, and what is folklore

### 4a. The agent exists and ships in the exact build NeoForge uses

`net.fabricmc:sponge-mixin:0.17.0+mixin.0.8.7` was downloaded and inspected. Its `META-INF/MANIFEST.MF` contains:

```
Implementation-Version: 0.17.0+mixin.0.8.7
Implementation-Vendor: https://fabricmc.net
Premain-Class: org.spongepowered.tools.agent.MixinAgent
Agent-Class: org.spongepowered.tools.agent.MixinAgent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

and the jar contains `org/spongepowered/tools/agent/MixinAgent.class`, `MixinAgent$Transformer.class`, and `MixinAgentClassLoader.class`. The build script confirms this is deliberate — those manifest attributes are commented `// for hotswap agent`, and the shadow jar includes every source set except `example`, `test`, and `modularityDummy`, which includes `agent` ([FabricMC/Mixin `build.gradle` @ `0.17.0+mixin.0.8.7`](https://github.com/FabricMC/Mixin/blob/0.17.0%2Bmixin.0.8.7/build.gradle)).

**The sponge-mixin jar is itself a usable java agent.** Enable it with `-javaagent:<path to sponge-mixin-0.17.0+mixin.0.8.7.jar>`. Its `premain` sets `mixin.hotSwap=true` itself and captures `Instrumentation` ([`MixinAgent.java`](https://github.com/FabricMC/Mixin/blob/0.17.0%2Bmixin.0.8.7/src/agent/java/org/spongepowered/tools/agent/MixinAgent.java)).

### 4b. NeoForge's Mixin wiring does construct the hot-swapper

Not obvious, because NeoForge does not use Mixin's ModLauncher integration. It installs its own `FMLMixinService` and applies Mixins through a `ClassProcessor` inside FML's `TransformingClassLoader` ([`MixinFacade.java`](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/mixin/MixinFacade.java), [`FMLMixinClassProcessor.java`](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/mixin/FMLMixinClassProcessor.java)). The chain nevertheless reaches the stock transformer:

- `FMLMixinService.offer(IMixinInternal)` does `this.mixinTransformer = mixinTransformerFactory.createTransformer();` ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/loading/mixin/FMLMixinService.java));
- `MixinTransformer.Factory implements IMixinTransformerFactory`, and the `MixinTransformer` constructor calls `this.hotSwapper = this.initHotSwapper(environment);` ([`MixinTransformer.java` @ pinned tag](https://github.com/FabricMC/Mixin/blob/0.17.0%2Bmixin.0.8.7/src/main/java/org/spongepowered/asm/mixin/transformer/MixinTransformer.java));
- `initHotSwapper` returns `null` unless `environment.getOption(Option.HOT_SWAP)` — the `mixin.hotSwap` system property, `HOT_SWAP("hotSwap")` in [`MixinEnvironment.java`](https://github.com/FabricMC/Mixin/blob/0.17.0%2Bmixin.0.8.7/src/main/java/org/spongepowered/asm/mixin/MixinEnvironment.java) — is set; otherwise it reflectively loads `org.spongepowered.tools.agent.MixinAgent` and constructs it with the transformer.

The integration point is intact on NeoForge. **Verified by reading source, not by running it** — see 4d.

### 4c. What the agent genuinely supports

`MixinAgent.Transformer.transform` has two branches:

1. **The redefined class is a Mixin.** It calls `classTransformer.reload(...)`, which returns the Mixin's target classes, then for each target re-runs full Mixin application over the *original* target bytecode and calls `instrumentation.redefineClasses(...)`. On failure it returns `ERROR_BYTECODE` (`new byte[]{1}`), deliberately causing a class-format error so the IDE shows a failure rather than silently doing nothing.
2. **The redefined class is anything else.** It runs `classTransformer.transformClassBytes(...)` on the incoming bytes — re-applying Mixins to a hot-swapped target class. **This is important and under-appreciated: without the agent, hot-swapping a Mixin-targeted class replaces it with untransformed compiler output and silently drops every Mixin applied to it.** With the agent attached, the transformations are re-applied.

Mixin's own hard limits are explicit in `MixinInfo.Reloaded.validateChanges` ([source](https://github.com/FabricMC/Mixin/blob/0.17.0%2Bmixin.0.8.7/src/main/java/org/spongepowered/asm/mixin/transformer/MixinInfo.java)), which throws `MixinReloadException` on:

- "Cannot change inner classes"
- "Cannot change interfaces"
- "Cannot change soft interfaces"
- "Cannot change target classes"
- "Cannot change mixin priority"

On top of Mixin's rules, the JVM's own redefinition rules still apply to step 1's `redefineClasses` call on the *target*. That is the real ceiling (section 6a).

**The practical boundary for Laymark's one Mixin** (the `GameRenderer.render(DeltaTracker, boolean)` frame bracket) on a **stock** JVM: editing the *body* of the existing `@Inject` handler is reloadable. Adding a second `@Inject`, changing the injection point, or retargeting is not — Mixin merges an injector's handler into the target as a new method, and stock HotSpot forbids adding methods.

**Non-obvious composition: JBR lifts the JVM constraint but not Mixin's.** `validateChanges` blocks changes to target classes, interfaces, inner classes, and priority — none of which is affected by adding an injector. So on JBR with `-XX:+AllowEnhancedClassRedefinition`, `reloadMixin` succeeds *and* the resulting `redefineClasses` on the target, which now adds a method, is permitted (section 6c). **Adding a new `@Inject` to an existing Mixin should therefore be hot-reloadable on JBR and not on a stock JDK.** This is a deduction from two independently verified mechanisms, not something either project documents. **Settled by:** doing it once on each JVM.

### 4d. What was not verified, and what would settle it

1. **Classloader identity of `MixinAgent`.** The agent's `Instrumentation` lives in a `static` field set by `premain` in whichever `MixinAgent` class the *system* classloader defined. `MixinTransformer` finds the agent with `Class.forName("org.spongepowered.tools.agent.MixinAgent")` from its own defining loader. In a dev run sponge-mixin is on the Gradle runtime classpath (app classloader), so those should be the same class — but FML documents this exact hazard for its own agent, using `Class.forName(..., ClassLoader.getSystemClassLoader())` and commenting "our copy of DevAgent may not be the same" ([`InstrumentationHelper.java`](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/startup/InstrumentationHelper.java)). **Settled by:** attaching `-javaagent:<sponge-mixin jar>` to `runClient` and checking the log for Mixin's `"Attempting to load Hot-Swap agent"` *without* a following `"Hot-swap agent could not be loaded"`.
2. **Whether FML's `ClassProcessor` pipeline leaves Mixin's target-bytecode registry populated.** `MixinAgent.reApplyMixins` needs `MixinAgentClassLoader.getOriginalTargetBytecode(...)`, populated via `hotSwapper.registerTargetClass(...)` during normal application. Since FML routes application through the stock `MixinTransformer`, this should hold. **Settled by:** an actual hot-swap of a Mixin body in a `runClient` session.
3. **Whether JVMTI redefinition behaves for classes loaded by FML's `TransformingClassLoader` inside a separate module layer.** Nothing in the source suggests a problem, and `ClassLoadingGuardian` is not even installed by default (section 1b), but this is not determinable from source. **Settled by:** the same empirical test.

**Recommendation:** do not put Mixin hot-swap on the critical path. Make it opt-in behind a Gradle property, and design Laymark's frame instrumentation so the *policy* it feeds lives in `core` and is unit-testable without the Mixin at all.

## 5. Automatic rebuild and relaunch, and what survives

**There is no auto-rebuild-and-relaunch feature, in ModDevGradle or FML.** A search across ModDevGradle's source, README, and breaking-changes doc for "continuous" returns nothing, and there is no in-process reload feature anywhere in either project.

**Gradle continuous build does not fill the gap.** `--continuous` / `-t` "automatically re-executes the build with the exact same set of tasks... when file inputs change", and its documented limitations include that "changes to task configuration, or any other change to the build model, are effectively ignored" and that "Gradle only watches for changes to files inside the project directory" ([Gradle continuous build docs](https://docs.gradle.org/current/userguide/continuous_builds.html)). `RunGameTask extends JavaExec`, so `gradlew -t runClient` would in principle re-run on input change — but that means **killing and relaunching the whole game**, and continuous build waits for the current build to finish, which `runClient` does not do until the game exits. **Inference, not documented fact. Settled by:** running it once. It is not a good loop either way.

The nearest supported thing is `taskBefore`, whose own javadoc warns: "This also slows down running through your IDE since it will first execute Gradle to run the requested tasks, and then run the actual game." In IDEA it becomes a `gradleTask` before-run entry.

**What survives a relaunch is on-disk state in the run's game directory**, which persists between runs: the `saves/` world, `options.txt`, `config/`, `mods/`. Nothing in the JVM survives.

**Quick Play is what makes relaunch cheap.** Minecraft 26.1.2's own launcher manifest declares `--quickPlaySingleplayer`, `--quickPlayMultiplayer`, `--quickPlayRealms`, and `--quickPlayPath` ([Mojang piston-meta 26.1.2](https://piston-meta.mojang.com/v1/packages/edcfd100a4856650b6e9797bac8f7fd76821979e/26.1.2.json)), and NeoForge's patch of `net.minecraft.client.main.Main` at tag `26.1.2` still constructs `new GameConfig.QuickPlayData(quickPlayLogPath, quickPlayVariant)` ([`Main.java.patch`](https://github.com/neoforged/NeoForge/blob/26.1.2/patches/net/minecraft/client/main/Main.java.patch)), so the feature is intact under NeoForge. Because FML forwards unconsumed program arguments to vanilla `Main`, this lands you in the world:

```groovy
runs {
    client {
        client()
        programArgument '--quickPlaySingleplayer'
        programArgument 'laymark-dev'
    }
}
```

The same NeoForge patch also auto-enables offline developer mode when no access token is passed in dev, so no account plumbing is needed.

One related correction worth recording, because it is an easy hour to lose: **`-Dfml.earlyWindowControl` is not a thing.** `earlyWindowControl` is an `fml.toml` config key (`FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL`), and `FMLConfig` has no `System.getProperty` override path at all. To suppress the early loading window, edit `<gameDir>/config/fml.toml`. It is also auto-skipped in headless mode.

**Recommended relaunch loop:** an IntelliJ `runClient` configuration with a Gradle build as its before-launch step, Quick Play into a pinned dev world, and a Laymark dev-only flag that auto-executes a canned run plan on world join. That reduces "observe the effect of a change" to one keystroke and one wait, with no menu navigation and no manual world selection. This is worth more than any hot-swap trick.

## 6. Standard JVM HotSwap versus enhanced class redefinition

### 6a. Stock HotSpot: method bodies and nothing else

The normative restriction is in the JVM TI spec, not the `java.lang.instrument` Javadoc — the latter delegates ("The supported class file changes are described in JVM TI RedefineClasses", [`Instrumentation` Javadoc, Java 25](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/Instrumentation.html)). The [JVM TI spec for Java 25](https://docs.oracle.com/en/java/javase/25/docs/specs/jvmti.html), *Redefine Classes*:

> The redefinition may change method bodies, the constant pool and attributes (unless explicitly prohibited). **The redefinition must not add, remove or rename fields or methods, change the signatures of methods, change modifiers, or change inheritance.** The redefinition must not change the `NestHost`, `NestMembers`, `Record`, or `PermittedSubclasses` attributes. These restrictions may be lifted in future versions.

| Change | Stock HotSpot | JVM TI error |
| --- | --- | --- |
| Method body | **allowed** | — |
| Constant pool / attributes | **allowed** | — |
| Add a method | rejected | `..._METHOD_ADDED` |
| Remove a method | rejected | `..._METHOD_DELETED` |
| Add / remove / rename a field | rejected | `..._SCHEMA_CHANGED` |
| Change a method signature | rejected | added + deleted |
| Change superclass or interfaces | rejected | `..._HIERARCHY_CHANGED` |
| Change class or method modifiers | rejected | `..._CLASS_MODIFIERS_CHANGED` / `..._METHOD_MODIFIERS_CHANGED` |

Three consequences matter more than the table:

- **Running frames keep the old code.** "If a redefined method has active stack frames, those active frames continue to run the bytecodes of the original method. The redefined method will be used on new invokes." For Laymark this bites directly: the benchmark state machine's driving loop is long-lived, so editing the method that *is* the loop changes nothing until it is re-entered.
- **No re-initialisation.** "Redefining a class does not cause its initializers to be run. The values of static variables will remain as they were prior to the call", and "Instances of the redefined class are not affected." A change to a `static final` table or to a constructor's field defaults does not reach existing objects.
- **`canRedefineClasses` and `canRetransformClasses` are not two power levels.** The JVM TI `RetransformClasses` section carries identical restriction wording. The difference is the *input* — retransformation re-runs the transformer chain from the initial class file bytes — not which changes are legal.

**Adding or removing a lambda breaks HotSwap.** Flagged as inference: javac desugars each lambda body into a synthetic method on the enclosing class, so adding one adds a method, which the quoted rule forbids. Editing an existing lambda's body is fine. No single primary document states this composition. **Settled by:** trying it once.

### 6b. Nothing in JDK 25 relaxes this

The only JEP that ever proposed to is [JEP 159: Enhanced Class Redefinition](https://openjdk.org/jeps/159) — "Enhance the class redefinition capabilities of the HotSpot VM to support, at runtime, the addition of supertypes and the addition and removal of methods and fields" — status **Closed / Withdrawn** ([JDK-8046149](https://bugs.openjdk.org/browse/JDK-8046149)). There is no successor, draft or candidate.

Dynamic agent loading still works in JDK 25. [JEP 451](https://openjdk.org/jeps/451) (Delivered in 21) introduced only a *warning*: "In JDK 21, the dynamic loading of agents is allowed but the JVM issues a warning... **In some future release**, the dynamic loading of agents will be disallowed by default." The flag remains `product(bool, EnableDynamicAgentLoading, true, ...)` in HotSpot at tag `jdk-25-ga`, and the JDK 25 `java.lang.instrument` [package spec](https://docs.oracle.com/en/java/javase/25/docs/api/java.instrument/java/lang/instrument/package-summary.html) describes `-XX:+EnableDynamicAgentLoading` as an opt-in that "suppresses the warning". The [JDK 25 release notes](https://www.oracle.com/java/technologies/javase/25-relnote-issues.html) contain no dynamic-agent note.

Two corrections to widely repeated beliefs, both relevant to reading FML's code:

- ***Self*-attach was disabled by default in JDK 9, not 21.** JEP 451 states: "JDK 9 and later releases prevent code from connecting to the current JVM by default. (Such connections can be enabled via `-Djdk.attach.allowAttachSelf=true`.)" That is why FML's `SelfAttach` spawns a *child* process that attaches back to its parent.
- **That child-process trick is not an exemption from JEP 451 — it is JEP 451's motivating example.** Verbatim: "However, that measure has proven insufficient: Some libraries now spawn a second JVM which connects to the first and loads the agent there." It still works on 25; it is explicitly on notice.

Also worth flagging for the future: [JEP 483 (AOT Class Loading & Linking, JDK 24)](https://openjdk.org/jeps/483) requires that runs "must not use JVMTI agents that can arbitrarily rewrite classfiles using ClassFileLoadHook", and silently ignores the AOT cache otherwise. Laymark measures startup time. If a future Minecraft or benchmarked mod stack relies on an AOT cache, **an attached agent would silently disable it and corrupt the very number Laymark reports.** That is a strong argument for never attaching a hot-swap agent to a measured run — only to a dev run.

### 6c. JetBrains Runtime enhanced redefinition: real, shipping for Java 25, usable

Verified in JBR's own source tree rather than from documentation:

- `src/hotspot/share/prims/jvmtiEnhancedRedefineClasses.cpp` exists on the `jbr25` branch ([source](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/src/hotspot/share/prims/jvmtiEnhancedRedefineClasses.cpp)).
- The gate is a **product** flag defaulting to `false`, in [`jbr25/src/hotspot/share/runtime/globals.hpp`](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/src/hotspot/share/runtime/globals.hpp): `product(bool, AllowEnhancedClassRedefinition, false, "Allow enhanced class redefinition beyond swapping method bodies")`.
- JBR's README: "**Enhanced class re-definition** with the DCEVM technology... **this feature needs to be explicitly enabled with `-XX:+AllowEnhancedClassRedefinition`**" ([README](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/.github/README.md)).
- **A JDK 25-based JBR exists and is current**: tag `jbr-release-25.0.4b508.27`, published 2026-08-03 ([releases](https://github.com/JetBrains/JetBrainsRuntime/releases)), notes "Rebase JBR25 on top of OpenJDK 25.0.4". There is no JBR 26.

Reading `calculate_redefinition_flags` against `Klass::RedefinitionFlags` in [`jbr25/.../klass.hpp`](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/src/hotspot/share/oops/klass.hpp), the only structural rejection remaining in the whole 2683-line file is `RemoveSuperType` → `JVMTI_ERROR_UNSUPPORTED_REDEFINITION_HIERARCHY_CHANGED`. Adding and removing methods, adding/removing/retyping fields, adding a superclass, adding *or removing* an interface, changing class modifiers, and defining brand-new classes mid-session are all supported. This matches HotswapAgent upstream: "almost any structural class change on hotswap (with an exception to a hierarchy change)" ([HotswapAgent README](https://github.com/HotswapProjects/HotswapAgent/blob/master/README.md)).

What enhanced redefinition does **not** fix: active frames still run old code, static initialisers still do not re-run, and new fields get default values with no constructor or initialiser executed.

**Decisive constraint: the JVM running Minecraft must be JBR — the IDE's runtime is irrelevant.** IntelliJ detects the capability purely by inspecting the launched process's VM parameters (`DefaultDebugEnvironment.hasEnhancedClassRedefinitionEnabled` returns true only when the run configuration carries `-XX:+AllowEnhancedClassRedefinition`), and its automatic flag-injection path (`DevkitHotReloadDcevm.kt`) is guarded on the run configuration's JDK being JBR *and* on IDE-plugin-development main classes ([intellij-community](https://github.com/JetBrains/intellij-community)). **For a Gradle-launched Minecraft you must supply the flag yourself**, and a non-JBR JDK will refuse to start with `Unrecognized VM option`.

That is straightforward, because JBR is a first-class Gradle toolchain vendor:

```groovy
java.toolchain {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.JETBRAINS
}
```

`JvmVendorSpec.JETBRAINS` is "A constant for using JetBrains Runtime as the JVM vendor", `@Incubating`, since Gradle 8.4 ([Javadoc](https://docs.gradle.org/current/javadoc/org/gradle/jvm/toolchain/JvmVendorSpec.html)); JETBRAINS appears in the toolchain auto-detection ordering ([Gradle toolchains](https://docs.gradle.org/current/userguide/toolchains.html)); and foojay serves it — `{"name":"JetBrains","api_parameter":"jetbrains",...}` in [the disco distributions list](https://api.foojay.io/disco/v3.0/distributions), with JBR 25 packages `"release_status":"ga"` and `"directly_downloadable":true`.

Two caveats: foojay serves only the `jbrsdk_jcef` flavour (~620 MB, Chromium bundled), and **it is not primary-source verified that that flavour honours the flag** — though the flag is a `product` flag on the branch all flavours build from. **Settled by:** `bin/java -XX:+AllowEnhancedClassRedefinition -version` on a foojay-provisioned JBR 25; failure is immediate and unambiguous.

### 6d. What IntelliJ actually documents

IDEA has one canonical HotSwap page ([Altering the program's execution flow](https://www.jetbrains.com/help/idea/altering-the-program-s-execution-flow.html)), stating the limits in the same terms as the JVM TI spec:

> **HotSwap limitations.** Due to VM design, HotSwap has the following limitations: it is only available if a method body is modified. Changing signatures is not supported. Adding and removing class members is not supported. If the modified method is already in the call stack, the changes will take effect only after the program exits the modified method... the frame is displayed as obsolete.

Settings live at **Settings | Build, Execution, Deployment | Debugger | HotSwap**: `Reload classes after compilation` (Always / Ask / Never), `Build project before reloading classes`, `Suggest HotSwap in the editor when the code is modified`. The same page notes: "The scope of recompilation depends on the build tool. For example, **Gradle and Maven recompile the whole module**, whereas IntelliJ IDEA's build system only recompiles the changed files." That is the basis for the recommendation in section 1a.

**Documentation gap worth recording:** no IntelliJ help page contains the phrase "Enhanced class redefinition", and no IDEA doc connects JetBrains Runtime to HotSwap; the docs only gesture at "the Dynamic Code Evolution VM". The mechanism is entirely the VM flag. Note also that the JDI capabilities that would let a debugger *detect* enhanced redefinition — `canAddMethod()` and `canUnrestrictedlyRedefineClasses()` — are deprecated since JDK 15 with the note "A JVM TI based JDWP back-end will never set this capability to true" ([JDI `VirtualMachine`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jdi/com/sun/jdi/VirtualMachine.html)). There is no handshake; it is flag-in, flag-out.

## 7. Iteration that never launches Minecraft

This is the largest and cheapest win, and Laymark's agreed architecture already sets it up.

| Tier | Mechanism | Needs | Rough cost |
| --- | --- | --- | --- |
| 1 | Plain JUnit on `core` and `runner` | Nothing. No Minecraft, no loader, no ModDevGradle | Seconds |
| 2 | ModDevGradle modded unit tests | `neoForge { unitTest { enable(); testedMod = mods.laymark } }` | Tens of seconds |
| 3 | `gameTestServer` run | Registered gametests; headless; exits on completion | ~a minute |
| 4 | `runClient` | Full client, GPU, Spark, Chunky | Minutes |

Tier 2 is first-class: ModDevGradle exposes a `UnitTest` DSL with `enable()`, `testedMod`, and `loadedMods`, documenting that "the compiled classes from `src/test/java` and the resources from `src/test/resources` will be added to that mod at runtime" ([`UnitTest.java`](https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/dsl/UnitTest.java)). FancyModLoader has the matching hook, `JUnitGameBootstrapper`, "executed when FML is bootstrapped in a unit testing context... when mods want a mod-loading environment in their JUnit tests" ([source](https://github.com/neoforged/FancyModLoader/blob/main/loader/src/main/java/net/neoforged/fml/startup/JUnitGameBootstrapper.java)). This is also the one path that uses FML's self-attaching `InstrumentationHelper` (section 1b), so expect the JEP 451 warning there and not in `runClient`.

Tier 3 exists in the stock MDK: the `gameTestServer` run "launches GameTestServer and runs all registered gametests, then exits".

### How much of Laymark this covers

A line-count figure is not available and would be fabricated: the repository currently contains only `docs/` and no source. What *can* be counted is the responsibility inventory the grounded architecture research already fixed ([`loader-portability-research.md`](./loader-portability-research.md)).

Of the **13 named in-game responsibilities**, **4 are in `core` and are entirely Minecraft-free**:

- the persisted benchmark state machine and all timeout/failure policy;
- resolved plans, protocol events/results, experiment identity, and artifact naming;
- Spark command *intent* and Chunky preparation *intent*, without importing either mod;
- scoring-neutral raw measurement models.

The other 9 (5 in `minecraft-common`, 4 in the loader adapter) need the game. **But the entire `runner` module is additionally Minecraft-free** and, per the map, owns config resolution, jar probing for the dependency graph, Modrinth metadata, the `lay list --json` shell-out, mod-directory transactions and automatic recovery, the selection procedure, and report generation. That is a large module with no Minecraft dependency whatsoever.

The honest statement: **everything except the `minecraft-common` implementation and the two thin loader adapters is tier-1 testable.** The four heaviest and most bug-prone parts of Laymark — the state machine, the statistics and selection procedure, the dependency-graph construction, and the crash-recovery transaction — are all tier 1. The parts that genuinely require tier 4 are narrow: the frame/GPU bracket, the settings-effect barriers, and world/readiness sequencing.

### Making it stay true

- **A CI gate that fails the build if `core` or `runner` gains a compile or runtime dependency on Minecraft, NeoForge, Fabric, Spark, or Chunky.** A Gradle configuration assertion, not a convention.
- **The in-memory adapter is a first-class artifact, not test scaffolding.** The map already calls for a three-way contract fixture (NeoForge, Fabric, in-memory). Keep it in a test-fixtures source set so the same fixture runs at tier 1.
- **A tier-1 wall-clock budget.** If `core` + `runner` tests stop finishing in single-digit seconds, the loop has already been lost.

## 8. Notes for the future Fabric port

Nothing here paints the Fabric seam into a corner, provided three things hold:

1. **Do not let the dev-run mechanism leak into shared code.** `fml.modFolders`, `InDevJarLocator`, and `ModsFolderLocator` are NeoForge/FML concepts. Fabric's equivalents exist and are structurally analogous but differently spelled: Fabric Loader defines `PATH_GROUPS = "fabric.classPathGroups"`, commented "class path groups to map multiple class path entries to a mod (paths separated by path separator, groups by double path separator)" ([`SystemProperties.java`](https://github.com/FabricMC/fabric-loader/blob/master/src/main/java/net/fabricmc/loader/impl/util/SystemProperties.java)) — same job as `fml.modFolders`, different delimiter convention. Loom provides a `modLocalRuntime` configuration for third-party mod jars, which Modrinth's Maven article also documents. The build should expose one property per loader; `core` and `runner` should never know which was used.
2. **The Mixin hot-swap boundary is already loader-neutral.** Fabric ships the same `net.fabricmc:sponge-mixin` artifact NeoForge 26.1.2 uses, so the agent, its `-javaagent` switch, and every constraint in 4c are literally identical on both loaders. The reload boundary is a property of the shared Mixin build, not of the loader.
3. **Quick Play is vanilla.** `--quickPlaySingleplayer` is parsed by `net.minecraft.client.main.Main` and works identically under Fabric. The dev-loop ergonomics port for free.

The one thing to watch: **tier 2 is ModDevGradle-specific.** Loom's modded-test integration is a different mechanism. **Do not write Laymark's contract fixture against ModDevGradle's unit-test integration.** Write it against plain JUnit at tier 1 with the in-memory adapter, and let each loader's modded-test integration run *the same fixture* as an additional harness. If the fixture only exists inside `neoForge { unitTest { } }`, the Fabric port will have to rewrite it.

## 9. Summary: the loop and its exact boundaries

| Change | What it takes |
| --- | --- |
| Body of a method in `core` or `runner` | **No launch at all.** Re-run the JUnit test (tier 1) |
| Body of a method in `minecraft-common` or the loader adapter | **HotSwap** in a running client (stock JDK), if the frame is not on the stack |
| Body of an existing Mixin `@Inject` handler | **HotSwap** with `-javaagent:<sponge-mixin jar>` (stock JDK) |
| New method, new field, changed signature, added/removed lambda | **Relaunch** on stock JDK; **HotSwap** on JBR 25 with `-XX:+AllowEnhancedClassRedefinition` |
| New `@Inject`/injection-point change in an existing Mixin | **Relaunch** on stock JDK; expected to HotSwap on JBR (unverified — 4c) |
| Changed Mixin targets, interfaces, inner classes, or priority | **Relaunch always.** Blocked by Mixin itself, not the JVM |
| New Mixin class, or new entry in a Mixin config | **Relaunch** |
| Changed superclass removal | **Relaunch always.** Rejected even by JBR |
| Registrations, events, mod metadata, `neoforge.mods.toml` | **Relaunch** |
| New/changed third-party mod dependency (Spark, Chunky version bump) | **Gradle build + relaunch**; re-sync in IDEA so run configs regenerate |
| Change to the `runs { }` block, `mods { }` block, or any build logic | **Gradle sync + relaunch.** Continuous build explicitly ignores build-model changes |
| Switching IDEA's "Build and run using" | **Gradle re-sync required** before `fml.modFolders` changes (1a) |

## 10. What could not be verified

Listed once, with the artifact that would settle each. None of these blocks a decision; they are all cheap empirical checks to run in the first hour of having a working project.

1. **Mixin hot-swap end-to-end on NeoForge.** The plumbing is verified by source (4a–4b); the round trip is not. Settled by attaching the agent and editing a Mixin body once.
2. **Whether JVMTI redefinition behaves for classes in FML's `TransformingClassLoader` module layer**, on stock JDK 25 and on JBR. Settled by one HotSwap attempt on each.
3. **Whether foojay's `jbrsdk_jcef` flavour honours `-XX:+AllowEnhancedClassRedefinition`.** Settled by `bin/java -XX:+AllowEnhancedClassRedefinition -version`.
4. **Whether lambdas' spun hidden classes are modifiable at all.** The JVMTI spec says "some implementation defined classes are never modifiable" but does not name hidden classes. Settled by an `IsModifiableClass` call — though the practical answer (adding a lambda adds a method, so it fails anyway) does not depend on it.
5. **`gradlew -t runClient` behaviour with a long-lived task.** Inferred, not documented. Settled by running it.
6. **Where IntelliJ persists ModDevGradle's generated run configurations** (`.idea/runConfigurations/*.xml` vs `.idea/workspace.xml` vs `.run/`). ModDevGradle hands JetBrains' `gradle-idea-ext` plugin a JSON model; materialisation is IDEA-side and outside all NeoForged repos. Settled by `git status` after a sync. This matters only for deciding what to commit to version control.
7. **Spark's own Maven coordinates on `maven.lucko.me`.** Not reachable during this research from one vantage point; the Modrinth artifact was byte-verified instead, which is sufficient. Settled by `curl https://maven.lucko.me/me/lucko/spark-neoforge/maven-metadata.xml`.
8. **Chunky and Spark versions beyond those pinned here.** Verified against Modrinth on 2026-08-16 for `game_versions` containing `26.1.2`. Re-check before pinning.

## Decisions needed from Mia

1. **Should the dev toolchain be JetBrains Runtime 25 rather than a stock JDK 25?**
   *Recommendation:* yes for the dev client run only, via a Gradle property that is off by default, and **never** for a run that produces a published benchmark number.
   *Trade-off:* JBR lifts almost every structural HotSwap restriction (6c) and would meaningfully shorten the tier-4 loop. But it is a ~620 MB toolchain download, `JvmVendorSpec.JETBRAINS` is `@Incubating`, and — decisively — the JVM under measurement would no longer be the JVM users run. Laymark's entire output is JVM-sensitive timing. Mixing the two would be a correctness bug, not a convenience.

2. **Should the Mixin hot-swap agent be enabled by default in `runClient`?**
   *Recommendation:* no. Opt-in behind a Gradle property.
   *Trade-off:* enabled by default, it makes Mixin body edits reloadable and — more valuably — stops ordinary HotSwap from silently stripping Mixins off a hot-swapped target class (4c). Against that: it is an unverified path on NeoForge (4d), it attaches a `ClassFileLoadHook` agent which would disable any future AOT cache (6b), and it perturbs the process Laymark exists to measure.

3. **How should Spark and Chunky be pinned?**
   *Recommendation:* Modrinth **version IDs** (`maven.modrinth:chunky:EyCqftOK`) plus Gradle dependency verification asserting the SHA-1s recorded in section 2.
   *Trade-off:* version IDs are opaque and unreadable in a build file, and need a comment. Version numbers are readable and, for Chunky, **silently wrong** — verified. Given Laymark publishes auditable claims about which instrumentation binaries were present, opaque-but-exact wins. Mitigate readability with a comment carrying the human version.

4. **Should Laymark ship a dev-only "auto-run a canned plan on world join" flag?**
   *Recommendation:* yes, gated on a system property that is absent from release builds, combined with `--quickPlaySingleplayer`.
   *Trade-off:* it is the single biggest reduction in tier-4 wall time, turning relaunch into one keystroke. Cost is a code path that exists only in dev, which must be provably inert in a measured run — so it needs to be a first-class part of the state machine's entry conditions in `core`, not an `if (DEV)` sprinkled in the mod.

5. **Should the presence of `MOD_CLASSES`/`fml.modFolders` invalidate a measured run?**
   *Recommendation:* always record it in the environment record; refuse to mark a run auditable when it is set, unless config explicitly opts in.
   *Trade-off:* it is genuinely useful for debugging a production instance without installing a jar (section 3), and it costs one field and one check to make safe. Not doing this leaves an undetectable code-injection path into the instrumentation closure Laymark claims to protect.

6. **Should Laymark adopt ModDevGradle's modded-JUnit integration (tier 2) in 0.x, given the Fabric port needs a different mechanism?**
   *Recommendation:* yes, but keep the contract fixture itself at tier 1 and let tier 2 merely *run* it.
   *Trade-off:* tier 2 is real value — it catches registration and loader-adapter bugs in tens of seconds rather than minutes. The risk is writing the fixture *against* it and painting the Fabric seam into a corner (section 8). The discipline costs nothing if applied from the first test.

7. **What is the enforced tier-1 budget, and is the CI gate on `core`/`runner` purity in scope for 0.x?**
   *Recommendation:* enforce the dependency gate from the first commit; set the wall-clock budget as a warning, not a failure, until there is enough code to calibrate.
   *Trade-off:* the dependency gate is cheap and catches the failure that would quietly destroy the whole loop. A hard time budget set before any code exists would be an invented number, and the map's standing preference is against those.

8. **Should the contributor setup require IntelliJ's "Build and run using: IntelliJ IDEA"?**
   *Recommendation:* document it as required for the fast loop, and have the build log a warning when it detects `delegatedBuild != false` during an IDEA sync.
   *Trade-off:* it is the difference between an incremental recompile and a full Gradle module recompile per edit (1a), and it is invisible and undocumented upstream. Against: non-delegated builds occasionally diverge from Gradle's own compilation (annotation processors, generated resources), and Laymark uses data generation. If that bites, the answer is to keep delegation on and accept the slower loop — which is why this should be documented guidance, not an enforced setting.
