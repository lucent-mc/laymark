plugins {
    id("laymark.java-conventions")
    alias(libs.plugins.moddev)
}

description = "NeoForge bootstrap, lifecycle wiring, mod inventory, and the frame trigger. No benchmark policy."

/**
 * Laymark's own modules, packaged into the mod jar.
 *
 * A loader module is a thin adapter over shared code, so a jar containing only the adapter ships a
 * dangling reference to the rest of itself. That surfaces as `NoClassDefFoundError` during mod
 * construction -- inside the game, well after the build said everything was fine.
 *
 * Non-transitive on purpose. It resolves to exactly the declared project jars and nothing else, so
 * Gson stays out: Minecraft already ships exactly the pinned version, and bundling it would add
 * classes to the process being measured for no benefit. Same reasoning that kept Kotlin and zstd
 * out of the mod.
 */
val bundled: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

neoForge {
    version = libs.versions.neoforge.get()

    runs {
        create("client") {
            client()
            // The dev loop's tier 4. Most iteration should never reach here: core and runner are
            // covered by plain JUnit in seconds.
            gameDirectory = file("run/client")
        }
    }

    mods {
        create("laymark") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":minecraft-common"))

    bundled(project(":core"))
    bundled(project(":minecraft-common"))
}

// Published artifact name carries the exact Minecraft version, so the runner can refuse an
// ambiguous match: laymark-neoforge-mc26.1.2-<version>.jar
base.archivesName = "laymark-neoforge-mc${libs.versions.minecraft.get()}"

tasks.jar {
    // dependsOn is load-bearing: zipTree() over a resolved configuration keeps the file paths but
    // drops the task dependency that produced them, so without it this runs before :core:jar and
    // fails with "Cannot expand ZIP ... as it does not exist". The closure keeps resolution lazy.
    dependsOn(bundled)
    from({ bundled.map { zipTree(it) } })

    // The runner rides inside the mod jar as an inert FILE -- deliberately not under
    // META-INF/jarjar/, which NeoForge scans and loads. Stored this way it costs disk size only:
    // no classes on the classpath, nothing in the measured JVM, and whoever has the mod has the
    // runner (§3). Extracted to the instance root on first human launch.
    into("laymark") {
        from(project(":runner").tasks.named("runnerJar"))
        rename { "runner.jar" }
    }

    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val expansions = mapOf("version" to project.version.toString())
    inputs.properties(expansions)
    filesMatching("META-INF/neoforge.mods.toml") { expand(expansions) }
}
