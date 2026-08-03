package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.converter.NameTransformerFactory
import io.github.archivesteak.symbolcraft.converter.SymbolSetGenerator
import io.github.archivesteak.symbolcraft.model.IconTarget
import java.io.File
import org.gradle.api.logging.Logger

/**
 * Generates custom SF Symbol `.symbolset` bundles from the downloaded SVG directories.
 *
 * Runs after [SvgConversionCoordinator] and reuses the same temp SVG workspace, so enabling SwiftUI
 * output never triggers additional downloads. Intentionally decoupled from Gradle types apart from
 * the injected [Logger].
 */
internal class SymbolSetGenerationCoordinator(private val logger: Logger) {

    /**
     * Generates symbol sets for every library that produced SVGs, then writes the aggregated
     * `Symbols.swift` helper when enabled.
     *
     * @param context shared generation context holding output directories and DSL configuration
     * @param iconsByLibrary mapping of library identifier -> icon names
     */
    fun generate(context: GenerationContext, iconsByLibrary: Map<String, Set<String>>) {
        val ext = context.extension
        val swiftUI = ext.swiftUIConfig
        val outputDir = context.swiftUIOutputDir ?: return

        logger.lifecycle("Generating SwiftUI .symbolset bundles...")

        val generator = SymbolSetGenerator { message -> logger.lifecycle(message) }

        val nameTransformer =
            if (ext.namingConfig.transformer.isPresent) {
                ext.namingConfig.transformer.get()
            } else {
                NameTransformerFactory.fromConvention(
                    convention = ext.namingConfig.namingConvention.get(),
                    suffix = ext.namingConfig.suffix.get(),
                    prefix = ext.namingConfig.prefix.get(),
                    removePrefix = ext.namingConfig.removePrefix.get(),
                    removeSuffix = ext.namingConfig.removeSuffix.get(),
                )
            }

        val allSymbolSetNames = mutableListOf<String>()
        var totalGenerated = 0

        iconsByLibrary.keys.forEach { libraryId ->
            val libraryTempDir = context.tempDir.resolve(libraryId)
            if (!libraryTempDir.exists() || libraryTempDir.listFiles()?.isEmpty() != false) {
                logger.warn("No SVG files found for library: $libraryId (SwiftUI)")
                return@forEach
            }

            val libraryConfigs =
                context.config
                    .mapValues { (_, iconConfigs) ->
                        iconConfigs.filter {
                            it.libraryId == libraryId && IconTarget.SWIFTUI in it.targets
                        }
                    }
                    .filterValues { it.isNotEmpty() }

            if (libraryConfigs.isEmpty()) {
                logger.debug("   Skipping library $libraryId for SwiftUI: no SWIFTUI targets")
                return@forEach
            }

            try {
                val results =
                    generator.generateLibrary(
                        libraryId = libraryId,
                        configs = libraryConfigs,
                        libraryTempDir = libraryTempDir,
                        outputDirectory = outputDir,
                        nameTransformer = nameTransformer,
                        scaleFactor = swiftUI.scaleFactor.get(),
                    )
                allSymbolSetNames += results.map { it.symbolSetName }
                totalGenerated += results.size
            } catch (e: Exception) {
                // Rethrow after logging: continuing would produce partial SwiftUI output while
                // the task (and the Gradle build cache) records success.
                logger.error(".symbolset generation failed for library $libraryId: ${e.message}")
                logger.error("   Stack trace: ${e.stackTraceToString()}")
                logger.error(
                    "   Hint: check that the downloaded SVGs are path-based and well-formed"
                )
                throw e
            }
        }

        if (swiftUI.generateSwiftEnum.get() && allSymbolSetNames.isNotEmpty()) {
            val swiftSourceDir =
                context.swiftUISourceDir
                    ?: resolveSwiftSourceDir(
                        configured = swiftUI.swiftSourceOutputDirectory.orNull,
                        outputDir = outputDir,
                        projectDir = ext.projectDir,
                    )
            if (swiftSourceDir != outputDir) {
                logger.lifecycle(
                    "   outputDirectory is inside an Xcode asset catalog; writing " +
                        "Symbols.swift to ${swiftSourceDir.absolutePath}"
                )
            }
            generator.generateSwiftEnumFile(
                allSymbolSetNames,
                swiftSourceDir,
                swiftUI.scaleFactor.get(),
            )
            logger.lifecycle("   Generated Symbols.swift (${allSymbolSetNames.size} symbols)")
        }

        logger.lifecycle("Successfully generated $totalGenerated .symbolset bundles")
    }

    companion object {
        /**
         * Decides where `Symbols.swift` lands.
         *
         * An explicit [configured] path wins (absolute, or relative to [projectDir]). Otherwise,
         * when the symbol-set [outputDir] IS an `.xcassets` bundle or lives INSIDE one (e.g. a
         * dedicated `Assets.xcassets/SymbolCraft` child), the Swift source must not go there: Xcode
         * treats asset catalogs as leaves, so neither the Swift compiler nor synchronized
         * file-system groups ever see sources stored inside. In that case the catalog's parent
         * directory is used. Plain-folder output keeps the historical behavior of writing
         * `Symbols.swift` next to the `.symbolset` bundles.
         */
        internal fun resolveSwiftSourceDir(
            configured: String?,
            outputDir: File,
            projectDir: File?,
        ): File {
            if (!configured.isNullOrBlank()) {
                val file = File(configured)
                return when {
                    file.isAbsolute -> file
                    projectDir != null -> File(projectDir, configured)
                    else -> file
                }
            }
            var cursor: File? = outputDir
            while (cursor != null) {
                if (cursor.name.lowercase().endsWith(XCODE_ASSET_CATALOG_SUFFIX)) {
                    return cursor.parentFile ?: outputDir
                }
                cursor = cursor.parentFile
            }
            return outputDir
        }

        private const val XCODE_ASSET_CATALOG_SUFFIX = ".xcassets"
    }
}
