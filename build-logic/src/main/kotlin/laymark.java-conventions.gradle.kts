plugins {
    `java-library`
}

val javaVersion = providers.gradleProperty("javaVersion").get().toInt()
val junitVersion = providers.gradleProperty("junitVersion").get()

group = "cx.mia.lucent"
version = providers.gradleProperty("laymarkVersion").get()

// Artifacts are laymark-<module>; the Gradle project names stay short.
base.archivesName = "laymark-${project.name}"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = javaVersion
    // -Werror is deliberately not set: it turns an upstream deprecation into a broken build
    // on someone else's release schedule.
    options.compilerArgs.add("-Xlint:all,-serial,-processing")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(junitVersion)
        }
    }
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
