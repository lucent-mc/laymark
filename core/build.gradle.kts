plugins {
    id("laymark.pure-module")
}

description = "Benchmark policy: state machine, plans, results, run identity. No Minecraft, no loader."

dependencies {
    // Minecraft already ships this exact version, so on the mod side it costs the measured
    // process nothing. The runner shades it. See the note in gradle/libs.versions.toml.
    api(libs.gson)
}
