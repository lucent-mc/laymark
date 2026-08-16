package cx.mia.lucent.laymark.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build if compiled classes reference packages the module is forbidden to see.
 *
 * This is architecture, not hygiene. Tier-1 testing -- plain JUnit, no Minecraft, no loader,
 * seconds rather than minutes -- exists only while `core` and `runner` stay free of game and
 * loader imports. A single accidental import silently degrades the fastest development loop
 * with nothing to signal it, which is precisely the kind of failure this project keeps
 * designing against.
 *
 * Detection is a byte scan of each class file's constant pool for the package name in **both**
 * forms:
 *
 *  - `net/minecraft` -- how a class reference is encoded, so this catches an ordinary import.
 *  - `net.minecraft` -- how a string literal is encoded, so this catches a reflective leak such
 *    as `Class.forName("net.minecraft.client.Minecraft")`, which no import check would see.
 *
 * A dotted match is treated as a violation rather than an exception: a module with no business
 * importing Minecraft has no business naming its packages either.
 */
@CacheableTask
abstract class PurityCheckTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    /** Package names in dotted form, for example `net.minecraft`. */
    @get:Input
    abstract val forbiddenPackages: ListProperty<String>

    @get:OutputFile
    abstract val stamp: RegularFileProperty

    @TaskAction
    fun check() {
        val packages = forbiddenPackages.get()
        // Both encodings: slash for class references, dot for string literals.
        val needles = packages.flatMap { listOf(it.replace('.', '/'), it) }
        val violations = mutableListOf<String>()
        var scanned = 0

        classDirectories.asFileTree.matching { include("**/*.class") }.visit {
            if (!isDirectory) {
                scanned++
                val bytes = file.readBytes()
                for (needle in needles) {
                    if (bytes.containsAscii(needle)) {
                        violations += "  $path references $needle"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            val forbidden = packages.joinToString()
            throw GradleException(
                buildString {
                    appendLine("$path must not reference: $forbidden")
                    appendLine()
                    violations.sorted().forEach { appendLine(it) }
                    appendLine()
                    append(
                        "This module is kept pure so it can be tested without launching Minecraft. " +
                            "Move the offending code to minecraft-common or a loader module."
                    )
                }
            )
        }

        logger.lifecycle("purity ok: $scanned classes, none reference ${packages.joinToString()}")
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok $scanned\n")
    }
}

private fun ByteArray.containsAscii(needle: String): Boolean {
    if (needle.isEmpty() || needle.length > size) return false
    val first = needle[0].code.toByte()
    outer@ for (i in 0..size - needle.length) {
        if (this[i] != first) continue
        for (j in 1 until needle.length) {
            if (this[i + j] != needle[j].code.toByte()) continue@outer
        }
        return true
    }
    return false
}

