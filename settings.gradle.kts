pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

dependencyResolutionManagement {
    // Deliberately not FAIL_ON_PROJECT_REPOS. ModDevGradle registers its own project-level
    // repositories (Mojang's libraries among them) to fetch and patch Minecraft, and it owns
    // that lifecycle -- forcing it through settings would mean tracking its internals.
    repositories {
        mavenCentral()
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
    }
}

rootProject.name = "laymark"

// core            pure policy: state machine, plans, results, run identity
// minecraft-common vanilla implementation; may import Minecraft, may not import a loader
// neoforge        bootstrap, lifecycle, mod inventory, frame trigger
// fabric          interface-only placeholder; not built against Fabric, not published in 0.x
// runner          desktop side; depends on core, must not see Minecraft
include(
    "core",
    "minecraft-common",
    "neoforge",
    "fabric",
    "runner",
)
