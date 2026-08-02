package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Task that validates the DSL configuration before generation.
 *
 * Exposed to consumers as `validateSymbolCraftConfig`.
 */
abstract class ValidateSymbolsConfigTask : DefaultTask() {
    @get:Internal abstract val extension: Property<SymbolCraftExtension>

    /** Validates the DSL configuration, failing fast with actionable messages. */
    @TaskAction
    fun validate() {
        val ext = extension.get()
        val problems = mutableListOf<String>()

        val pkg = ext.packageName.get()
        if (!pkg.matches(KOTLIN_PACKAGE_REGEX)) {
            problems +=
                "packageName '$pkg' is not a valid Kotlin package name " +
                    "(expected segments like 'com.example.icons')"
        }

        if (ext.outputDirectory.get().isBlank()) {
            problems += "outputDirectory cannot be blank"
        }

        val config = ext.getIconsConfig()
        if (config.isEmpty()) {
            problems += "No icons configured. Use symbolCraft { } in build.gradle.kts"
        }

        if (config.keys.any { it.isBlank() }) {
            problems += "Icon names cannot be blank"
        }

        val swiftUI = ext.swiftUIConfig
        if (swiftUI.enabled.get()) {
            val scaleFactor = swiftUI.scaleFactor.get()
            if (!scaleFactor.isFinite() || scaleFactor <= 0.0) {
                problems +=
                    "swiftUI.scaleFactor must be a positive, finite number (got $scaleFactor)"
            }
            if (swiftUI.outputDirectory.get().isBlank()) {
                problems += "swiftUI.outputDirectory cannot be blank when SwiftUI output is enabled"
            }
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "Invalid SymbolCraft configuration:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }

        val count = config.values.sumOf { it.size }
        logger.lifecycle("Valid configuration. Icons: ${config.size}, Total configurations: $count")
    }

    private companion object {
        private val KOTLIN_PACKAGE_REGEX =
            Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}
