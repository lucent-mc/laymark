plugins {
    id("laymark.pure-module")
    application
}

description = "Desktop runner: launch assembly, process ownership, materialization, experiments, reports."

dependencies {
    implementation(project(":core"))
}

// The runner assembles launch commands for a mod loader, so loader names appear throughout its
// data: a descriptor's mainClass, and library paths like net/neoforged/neoforge/...-universal.jar.
// A byte scan cannot tell a file path from a class reference -- both are slash-separated UTF-8 in
// the constant pool -- so this module is checked differently:
//
//   - string literals are not checked at all (dotted form), since mainClass arrives as data;
//   - only main classes are scanned, because test fixtures embed real launcher descriptors.
//
// What still holds, and is the point of the gate: the shipped classes must not *import* Minecraft
// or a loader, so the runner stays testable without either.
tasks.named<cx.mia.lucent.laymark.build.PurityCheckTask>("purityCheck") {
    checkStringLiterals = false
    classDirectories.setFrom(layout.buildDirectory.dir("classes/java/main"))
}

application {
    mainClass = "cx.mia.lucent.laymark.runner.Main"
}
