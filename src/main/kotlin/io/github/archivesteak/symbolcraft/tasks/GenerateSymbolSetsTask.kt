package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import io.github.archivesteak.symbolcraft.tasks.internal.GeneratedFileCleaner
import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetGenerationCoordinator
import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetRequest
import io.github.archivesteak.symbolcraft.tasks.internal.toTransformer
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns the downloaded SVG workspace into an Xcode asset catalog of custom SF Symbol `.symbolset`
 * bundles plus the optional `Symbols.swift` helper.
 *
 * Exposed to consumers as `generateSymbolCraftSymbolSets`. It runs before any
 * `embedAndSign*AppleFrameworkForXcode` task of the same project, and is offered to other projects
 * through the `symbolCraftAppleSymbolsElements` configuration. Skipped unless `swiftUI.enabled` is
 * true.
 */
@CacheableTask
abstract class GenerateSymbolSetsTask : DefaultTask() {

    @get:Internal abstract val extension: Property<SymbolCraftExtension>

    /** Hash of everything that influences the symbol sets (icons, naming, SwiftUI settings). */
    @get:Input
    val swiftUIHash: String
        get() = extension.get().getSwiftUIHash()

    /** SVG workspace produced by `downloadSymbolCraftSvgs`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val svgDir: DirectoryProperty

    /** Asset catalog (or catalog child folder) that receives the `.symbolset` bundles. */
    @get:OutputDirectory abstract val catalogDir: DirectoryProperty

    /** `Symbols.swift` location; absent when `generateSwiftEnum` is false. */
    @get:Optional @get:OutputFile abstract val swiftFile: RegularFileProperty

    /** Regenerates every symbol set from scratch. */
    @TaskAction
    fun generate() {
        val ext = extension.get()
        val swiftUI = ext.swiftUIConfig
        val catalog = catalogDir.get().asFile
        val swift = swiftFile.orNull?.asFile

        val cleaner = GeneratedFileCleaner(logger)
        cleaner.cleanSymbolSets(catalog)
        if (swift != null && swift.parentFile != catalog) {
            cleaner.deleteGeneratedSource(swift)
        }

        val request =
            SymbolSetRequest(
                config = ext.getIconsConfig(),
                svgDir = svgDir.get().asFile,
                catalogDir = catalog,
                swiftFile = swift,
                nameTransformer = ext.namingConfig.toTransformer(),
                scaleFactor = swiftUI.scaleFactor.get(),
            )
        SymbolSetGenerationCoordinator(logger).generate(request)
    }
}
