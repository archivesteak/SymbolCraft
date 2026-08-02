package io.github.archivesteak.symbolcraft.plugin

import io.github.archivesteak.symbolcraft.model.LocalIconConfig
import io.github.archivesteak.symbolcraft.tasks.CleanSymbolsCacheTask
import io.github.archivesteak.symbolcraft.tasks.CleanSymbolsIconsTask
import io.github.archivesteak.symbolcraft.tasks.GenerateSymbolsTask
import io.github.archivesteak.symbolcraft.tasks.ValidateSymbolsConfigTask
import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetGenerationCoordinator
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Gradle plugin entry point registered as `io.github.archivesteak.symbolcraft`.
 *
 * The plugin wires the [SymbolCraftExtension] DSL, registers generation/cleanup tasks, and ensures
 * Kotlin compilation depends on freshly generated icons.
 */
class SymbolCraftPlugin : Plugin<Project> {
    /** Installs the extension and all supporting tasks on the target [project]. */
    override fun apply(project: Project) {
        val extension = project.extensions.create("symbolCraft", SymbolCraftExtension::class.java)

        val swiftUI = extension.swiftUIConfig
        val projectDir = project.layout.projectDirectory.asFile

        // Lazily resolved SwiftUI output locations. The providers carry no value when SwiftUI
        // output is disabled, so the @Optional task outputs stay unset. Provider-based wiring
        // (instead of afterEvaluate) also honors consumer configuration applied late in build
        // script evaluation, and keeps every SwiftUI write location a declared task output.
        val symbolSetDirProvider =
            swiftUI.enabled.flatMap { enabled ->
                if (enabled) {
                    swiftUI.outputDirectory.map { configured ->
                        File(configured).let {
                            if (it.isAbsolute) it else File(projectDir, configured)
                        }
                    }
                } else {
                    project.providers.provider<File> { null }
                }
            }
        val swiftSourceDirProvider =
            symbolSetDirProvider.flatMap { symbolSetDir ->
                swiftUI.swiftSourceOutputDirectory
                    .map { configured ->
                        SymbolSetGenerationCoordinator.resolveSwiftSourceDir(
                            configured,
                            symbolSetDir,
                            projectDir,
                        )
                    }
                    .orElse(
                        project.providers.provider {
                            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(
                                null,
                                symbolSetDir,
                                projectDir,
                            )
                        }
                    )
            }

        val generateTaskProvider =
            project.tasks.register("generateSymbolCraftIcons", GenerateSymbolsTask::class.java) {
                task ->
                task.group = "symbolcraft"
                task.description = "Generate icons from configured libraries"
                task.extension.set(extension)
                task.outputDir.set(project.layout.projectDirectory.dir(extension.outputDirectory))
                task.cacheDirectory.set(extension.cacheDirectory)
                task.projectBuildDir.set(
                    project.layout.buildDirectory.map { it.asFile.absolutePath }
                )
                task.swiftUIOutputDir.fileProvider(symbolSetDirProvider)
                task.swiftUISourceDir.fileProvider(swiftSourceDirProvider)
                // Local SVG contents are task inputs: editing a checked-in SVG must re-run
                // generation. Discovery already ran at configuration time (localIcons DSL),
                // so this provider just re-reads the resolved paths.
                task.localSvgFiles.from(
                    project.providers.provider {
                        extension
                            .getIconsConfig()
                            .values
                            .flatten()
                            .filterIsInstance<LocalIconConfig>()
                            .map { it.absolutePath }
                    }
                )
            }

        project.tasks.register("cleanSymbolCraftCache", CleanSymbolsCacheTask::class.java) { task ->
            task.group = "symbolcraft"
            task.description = "Clean SymbolCraft icon cache"
            task.cacheDirectory.set(extension.cacheDirectory)
            task.projectBuildDir.set(project.layout.buildDirectory.map { it.asFile.absolutePath })
        }

        project.tasks.register("cleanSymbolCraftIcons", CleanSymbolsIconsTask::class.java) { task ->
            task.group = "symbolcraft"
            task.description = "Clean generated SymbolCraft icon files"
            task.packageName.set(extension.packageName)
            task.outputDirectory.set(project.layout.projectDirectory.dir(extension.outputDirectory))
            task.swiftUIOutputDirectory.fileProvider(symbolSetDirProvider)
            task.swiftUISourceDirectory.fileProvider(swiftSourceDirProvider)
        }

        project.tasks.register(
            "validateSymbolCraftConfig",
            ValidateSymbolsConfigTask::class.java,
        ) { task ->
            task.group = "symbolcraft"
            task.description = "Validate SymbolCraft icon configuration"
            task.extension.set(extension)
        }

        // Wire generation into every Kotlin compile task by TYPE, not by name. Task names
        // differ per Kotlin plugin flavor: `compileKotlinJvm`, `compileDebugKotlin`, but also
        // AGP built-in Kotlin / KMP tasks like `compileAndroidMain` that contain no "Kotlin"
        // at all. The task type is loaded reflectively by NAME: kotlin-gradle-plugin is a
        // compileOnly dependency, and a static class reference here would break plugin
        // application (and Gradle class decoration) in projects without a Kotlin plugin.
        val kotlinCapablePluginIds =
            listOf(
                "org.jetbrains.kotlin.jvm",
                "org.jetbrains.kotlin.multiplatform",
                "org.jetbrains.kotlin.android",
                "org.jetbrains.kotlin.js",
                "com.android.application",
                "com.android.library",
                "com.android.dynamic-feature",
                "com.android.test",
                "com.android.kotlin.multiplatform.library",
            )
        kotlinCapablePluginIds.forEach { id ->
            project.plugins.withId(id) { plugin ->
                // The Kotlin compile task type is guaranteed to be visible from the Kotlin
                // plugin's own classloader; fall back to ours for AGP built-in Kotlin setups.
                @Suppress("UNCHECKED_CAST")
                val compileToolType =
                    runCatching {
                            Class.forName(
                                "org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool",
                                true,
                                plugin.javaClass.classLoader,
                            )
                        }
                        .getOrElse {
                            runCatching {
                                    Class.forName(
                                        "org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool"
                                    )
                                }
                                .getOrNull()
                        } as Class<out org.gradle.api.Task>?
                if (compileToolType != null) {
                    project.tasks.withType(compileToolType).configureEach { task ->
                        task.dependsOn(generateTaskProvider)
                    }
                } else {
                    project.logger.info(
                        "SymbolCraft: KotlinCompileTool not visible after plugin '$id' was " +
                            "applied; Kotlin compile wiring falls back to task-name matching"
                    )
                }
            }
        }

        // Name-based fallback, only relevant when the reflective KotlinCompileTool lookup above
        // fails (it logs an info diagnostic). Generated icons are Kotlin sources, so only Kotlin
        // compile tasks need this dependency — asset/resource tasks never consume them.
        // configureEach is lazy: tasks registered after this plugin still get wired.
        project.tasks.configureEach { task ->
            val n = task.name
            if (
                n.startsWith("compile", ignoreCase = true) &&
                    n.contains("Kotlin", ignoreCase = true)
            ) {
                task.dependsOn(generateTaskProvider)
            }
        }
    }
}
