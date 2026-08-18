plugins {
    id("laymark.pure-module")
}

description = "Benchmark policy: state machine, plans, results, run identity. No Minecraft, no loader."

dependencies {
    // Minecraft already ships this exact version, so on the mod side it costs the measured
    // process nothing. The runner shades it. See the note in gradle/libs.versions.toml.
    api(libs.gson)
}

// One canonical copy of the exhaustive, commented config reference. Both shipped entry points
// contain core: the runner shades it and the mod bundles it, so both install these exact bytes.
tasks.processResources {
    from(rootProject.file("docs/laymark-reference.jsonc"))
}
