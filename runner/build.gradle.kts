plugins {
    id("laymark.pure-module")
    application
}

description = "Desktop runner: launch assembly, process ownership, materialization, experiments, reports."

dependencies {
    implementation(project(":core"))
}

// The runner assembles launch commands for a mod loader, so loader class names appear in its
// data -- a descriptor's mainClass, and a test fixture containing one. It still must not import
// them, which the class-reference check enforces. Reflection is not a risk here either: those
// classes are not on the runner's classpath to begin with.
tasks.named<cx.mia.lucent.laymark.build.PurityCheckTask>("purityCheck") {
    checkStringLiterals = false
}

application {
    mainClass = "cx.mia.lucent.laymark.runner.Main"
}
