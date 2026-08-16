import cx.mia.lucent.laymark.build.PurityCheckTask

plugins {
    id("laymark.java-conventions")
    alias(libs.plugins.moddev)
}

description = "Vanilla implementation: world creation, options, readiness barriers, frame and GPU sampling."

neoForge {
    version = libs.versions.neoforge.get()
}

dependencies {
    implementation(project(":core"))
}

/**
 * This module may import Minecraft. It may not import a loader.
 *
 * NeoForge is unavoidably on the compile classpath -- ModDevGradle supplies Minecraft through
 * it -- so the seam is enforced by checking what the compiled bytecode actually references
 * rather than by what is available to it. The frame *quantity* comes from vanilla here; the
 * *trigger* belongs to a loader module.
 */
val purityCheck = tasks.register<PurityCheckTask>("purityCheck") {
    group = "verification"
    description = "Fails if this module references a mod loader."

    classDirectories.from(
        layout.buildDirectory.dir("classes/java/main"),
        layout.buildDirectory.dir("classes/java/test"),
    )
    forbiddenPackages = listOf("net.neoforged", "net.fabricmc")
    stamp = layout.buildDirectory.file("reports/purity/${project.name}.txt")

    dependsOn(tasks.named("classes"), tasks.named("testClasses"))
}

tasks.named("check") { dependsOn(purityCheck) }
