plugins {
    id("laymark.pure-module")
    application
}

description = "Desktop runner: launch assembly, process ownership, materialization, experiments, reports."

dependencies {
    implementation(project(":core"))
}

application {
    mainClass = "cx.mia.lucent.laymark.runner.Main"
}
