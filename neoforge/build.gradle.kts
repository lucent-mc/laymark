plugins {
    id("laymark.java-conventions")
    alias(libs.plugins.moddev)
}

description = "NeoForge bootstrap, lifecycle wiring, mod inventory, and the frame trigger. No benchmark policy."

neoForge {
    version = libs.versions.neoforge.get()

    runs {
        create("client") {
            client()
            // The dev loop's tier 4. Most iteration should never reach here: core and runner
            // are covered by plain JUnit in seconds.
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
}

// Published artifact name carries the exact Minecraft version, so the runner can refuse an
// ambiguous match: laymark-neoforge-mc26.1.2-<version>.jar
base.archivesName = "laymark-neoforge-mc${libs.versions.minecraft.get()}"

tasks.processResources {
    val expansions = mapOf("version" to project.version.toString())
    inputs.properties(expansions)
    filesMatching("META-INF/neoforge.mods.toml") { expand(expansions) }
}
