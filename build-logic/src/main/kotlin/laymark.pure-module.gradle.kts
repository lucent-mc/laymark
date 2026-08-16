import cx.mia.lucent.laymark.build.PurityCheckTask

plugins {
    id("laymark.java-conventions")
}

/**
 * Applied to modules that must never see Minecraft or a loader: `core` and `runner`.
 *
 * Wired into `check`, so it runs in CI on every platform from the first commit. The spec
 * calls this out as architecture rather than hygiene, because tier-1 testing depends on it
 * and its failure mode is silent.
 */
val purityCheck = tasks.register<PurityCheckTask>("purityCheck") {
    group = "verification"
    description = "Fails if this module references Minecraft or a mod loader."

    classDirectories.from(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
    )
    forbiddenPackages = listOf(
        "net.minecraft",
        "net.neoforged",
        "net.fabricmc",
        "com.mojang",
        "org.spongepowered.asm",
    )
    stamp = layout.buildDirectory.file("reports/purity/${project.name}.txt")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
}

tasks.named("check") { dependsOn(purityCheck) }
