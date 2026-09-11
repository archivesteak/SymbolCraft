package io.github.archivesteak.symbolcraft.plugin

import io.github.archivesteak.symbolcraft.SymbolCraftDefaults
import io.github.archivesteak.symbolcraft.model.LocalIconConfig
import io.github.archivesteak.symbolcraft.tasks.CleanSymbolsCacheTask
import io.github.archivesteak.symbolcraft.tasks.CleanSymbolsIconsTask
import io.github.archivesteak.symbolcraft.tasks.DownloadSymbolSvgsTask
import io.github.archivesteak.symbolcraft.tasks.GenerateComposeIconsTask
import io.github.archivesteak.symbolcraft.tasks.GenerateSymbolSetsTask
import io.github.archivesteak.symbolcraft.tasks.ValidateSymbolsConfigTask
import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetGenerationCoordinator
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider

/**
 * Gradle plugin entry point registered as `io.github.archivesteak.symbolcraft`.
 *
 * Apply it to the project that owns the icon declaration. In a Kotlin Multiplatform app with native
 * SwiftUI that is the shared module Xcode builds; in a shared-Compose-UI app it is the Compose
 * module. The plugin registers three cacheable tasks:
 * - `downloadSymbolCraftSvgs` collects every SVG once into `build/symbolcraft/svgs`
 * - `generateSymbolCraftIcons` writes Compose `ImageVector` sources
 * - `generateSymbolCraftSymbolSets` writes an Xcode asset catalog of `.symbolset` bundles plus
 *   `Symbols.swift`
 *
 * Outputs default to the project's `build/` directory and never touch another module's tree. When a
 * Kotlin plugin is applied to the same project the Compose sources are added to
 * [SymbolCraftExtension.composeSourceSet] automatically, and every `embedAndSign*ForXcode` task
 * depends on the symbol catalog. Other projects can consume either output through the
 * `io.github.archivesteak.symbolcraft.compose` / `.apple` plugins.
 */
class SymbolCraftPlugin : Plugin<Project> {
    /** Installs the extension and all supporting tasks on the target [project]. */
    override fun apply(project: Project) {
        val extension = project.extensions.create("symbolCraft", SymbolCraftExtension::class.java)
        val swiftUI = extension.swiftUIConfig
        val layout = project.layout
        val projectDir = layout.projectDirectory.asFile

        // Output locations. Unset DSL values fall back to build/; relative values resolve against
        // the project directory. Providers keep every location lazy, so DSL configuration applied
        // late in build-script evaluation is honored without afterEvaluate.
        val composeOutputDir: Provider<File> =
            extension.outputDirectory
                .map { resolve(projectDir, it) }
                .orElse(
                    layout.buildDirectory.map {
                        it.asFile.resolve(SymbolCraftDefaults.COMPOSE_OUTPUT_DIR)
                    }
                )
        val catalogDir: Provider<File> =
            swiftUI.outputDirectory
                .map { resolve(projectDir, it) }
                .orElse(
                    layout.buildDirectory.map {
                        it.asFile
                            .resolve(SymbolCraftDefaults.SWIFTUI_OUTPUT_DIR)
                            .resolve(SymbolCraftDefaults.SWIFTUI_CATALOG_NAME)
                    }
                )
        val swiftFile: Provider<File> =
            swiftUI.generateSwiftEnum.flatMap { generate ->
                if (generate) {
                    swiftUI.swiftSourceOutputDirectory.orElse("").zip(catalogDir) {
                        configured,
                        catalog ->
                        File(
                            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(
                                configured.ifBlank { null },
                                catalog,
                                projectDir,
                            ),
                            SymbolCraftDefaults.SWIFT_SOURCE_NAME,
                        )
                    }
                } else {
                    project.providers.provider<File> { null }
                }
            }
        val svgWorkspace = layout.buildDirectory.dir(SymbolCraftDefaults.SVG_OUTPUT_DIR)
        val buildDirPath = layout.buildDirectory.map { it.asFile.absolutePath }

        val downloadTask =
            project.tasks.register("downloadSymbolCraftSvgs", DownloadSymbolSvgsTask::class.java) {
                task ->
                task.group = SymbolCraftDefaults.TASK_GROUP
                task.description = "Download (or copy) every configured SVG into one workspace"
                task.extension.set(extension)
                task.outputDir.set(svgWorkspace)
                task.cacheDirectory.set(extension.cacheDirectory)
                task.projectBuildDir.set(buildDirPath)
                // Local SVG contents are task inputs: editing a checked-in SVG must re-run the
                // download. Discovery already ran at configuration time (localIcons DSL), so this
                // provider just re-reads the resolved paths.
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

        val composeTask =
            project.tasks.register(
                "generateSymbolCraftIcons",
                GenerateComposeIconsTask::class.java,
            ) { task ->
                task.group = SymbolCraftDefaults.TASK_GROUP
                task.description = "Generate Compose ImageVector sources from the configured icons"
                task.extension.set(extension)
                task.svgDir.set(downloadTask.flatMap { it.outputDir })
                task.outputDir.fileProvider(composeOutputDir)
            }

        val symbolSetTask =
            project.tasks.register(
                "generateSymbolCraftSymbolSets",
                GenerateSymbolSetsTask::class.java,
            ) { task ->
                task.group = SymbolCraftDefaults.TASK_GROUP
                task.description =
                    "Generate an Xcode asset catalog of custom SF Symbol .symbolset bundles"
                task.extension.set(extension)
                task.svgDir.set(downloadTask.flatMap { it.outputDir })
                task.catalogDir.fileProvider(catalogDir)
                task.swiftFile.fileProvider(swiftFile)
                task.onlyIf(
                    "swiftUI output is disabled; set symbolCraft { swiftUI { enabled.set(true) } }"
                ) {
                    swiftUI.enabled.get()
                }
            }

        project.tasks.register("cleanSymbolCraftCache", CleanSymbolsCacheTask::class.java) { task ->
            task.group = SymbolCraftDefaults.TASK_GROUP
            task.description = "Delete the SymbolCraft SVG cache and workspace"
            task.cacheDirectory.set(extension.cacheDirectory)
            task.projectBuildDir.set(buildDirPath)
            task.svgWorkspace.set(svgWorkspace)
        }

        project.tasks.register("cleanSymbolCraftIcons", CleanSymbolsIconsTask::class.java) { task ->
            task.group = SymbolCraftDefaults.TASK_GROUP
            task.description =
                "Delete generated Compose sources, .symbolset bundles and Symbols.swift"
            task.packageName.set(extension.packageName)
            task.outputDirectory.fileProvider(composeOutputDir)
            task.swiftUICatalogDirectory.fileProvider(catalogDir)
            task.swiftUISourceFile.fileProvider(swiftFile)
        }

        project.tasks.register(
            "validateSymbolCraftConfig",
            ValidateSymbolsConfigTask::class.java,
        ) { task ->
            task.group = SymbolCraftDefaults.TASK_GROUP
            task.description = "Validate the SymbolCraft icon configuration"
            task.extension.set(extension)
        }

        // Other projects: symbolCraft(project(":this")) through the compose / apple plugins.
        SymbolCraftVariants.registerProducer(project, composeTask, symbolSetTask)

        // This project: generated Compose sources join the Kotlin source set, and any Apple
        // framework handed to Xcode from here brings the symbol catalog with it.
        ComposeSourceWiring.wire(
            project,
            extension.composeSourceSet,
            composeTask.flatMap { it.outputDir },
            warnIfNoKotlin = false,
        )
        XcodeTasks.dependOnForEmbed(project, symbolSetTask)
    }

    private fun resolve(projectDir: File, path: String): File =
        File(path).let { if (it.isAbsolute) it else File(projectDir, path) }
}
