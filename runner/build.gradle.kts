plugins {
    id("laymark.pure-module")
    application
}

description = "Desktop runner: launch assembly, process ownership, materialization, experiments, reports."

dependencies {
    implementation(project(":core"))
    // The only third-party dependency the runner has, and it is a Swing look-and-feel: still no
    // toolkit, still one jar, still nothing loaded into the measured JVM. Hand-rolling a dark theme
    // means restyling every scroll bar and check box by hand to arrive somewhere worse.
    implementation(libs.flatlaf)
}

// The plain jar steps aside: without the classifier it and runnerJar write the SAME file, and
// whichever ran last won -- a collision Gradle 9 turns from a silent hazard into an error.
tasks.jar { archiveClassifier = "thin" }

// The artifact §3 describes: one file, `java -jar`, no start script and no lib directory. It is
// also what makes the planning window reachable by double-clicking, so it is built by `assemble`
// rather than on request.
val runnerJar =
    tasks.register<Jar>("runnerJar") {
        archiveBaseName = "laymark-runner"
        archiveClassifier = ""
        manifest {
            attributes(
                "Main-Class" to "cx.mia.lucent.laymark.runner.Main",
                "Implementation-Version" to project.version,
                // FlatLaf loads a native library for window decorations. Declared here so the JVM
                // permits it silently instead of printing four warnings above the run's output.
                "Enable-Native-Access" to "ALL-UNNAMED",
            )
        }
        from(sourceSets.main.get().output)
        // Declared, not just read: unpacking inside a `from {}` closure hides the dependency, and
        // the jar silently packages whatever ':core:jar' was last built -- which is how a stale
        // core ends up shipped beside freshly built runner classes.
        dependsOn(configurations.runtimeClasspath)
        from({
            configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }
        })
        // Dependency signatures do not survive being unpacked into another jar, and a stale one
        // makes the JVM refuse to load the classes beside it.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

tasks.named("assemble") { dependsOn(runnerJar) }

// The application plugin's script/distribution tasks scan build/libs, where runnerJar also
// writes. Gradle's validation rightly refuses an undeclared producer-consumer overlap -- without
// the ordering, a distribution could package a stale runner jar depending on execution order.
// Named explicitly rather than withType: Jar extends Zip, so a type-wide rule would make
// runnerJar depend on itself.
listOf("startScripts", "installDist", "distZip", "distTar").forEach { name ->
    tasks.named(name) { dependsOn(runnerJar) }
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
