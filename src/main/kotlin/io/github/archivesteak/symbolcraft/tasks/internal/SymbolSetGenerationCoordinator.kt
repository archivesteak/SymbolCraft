package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.converter.NameTransformerFactory
import io.github.archivesteak.symbolcraft.converter.SymbolSetGenerator
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
     * @param iconsByLibrary mapping of library identifier → icon names
     */
    fun generate(context: GenerationContext, iconsByLibrary: Map<String, Set<String>>) {
        val ext = context.extension
        val swiftUI = ext.swiftUIConfig
        val outputDir = context.swiftUIOutputDir ?: return

        logger.lifecycle("🍏 Generating SwiftUI .symbolset bundles...")

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
                logger.warn("⚠️ No SVG files found for library: $libraryId (SwiftUI)")
                return@forEach
            }

            val libraryConfigs =
                context.config
                    .mapValues { (_, iconConfigs) ->
                        iconConfigs.filter { it.libraryId == libraryId }
                    }
                    .filterValues { it.isNotEmpty() }

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
                logger.error("❌ .symbolset generation failed for library $libraryId: ${e.message}")
                logger.error("   Stack trace: ${e.stackTraceToString()}")
                logger.error("   💡 Check that the downloaded SVGs are path-based and well-formed")
            }
        }

        if (swiftUI.generateSwiftEnum.get() && allSymbolSetNames.isNotEmpty()) {
            val swiftSourceDir =
                resolveSwiftSourceDir(
                    configured = swiftUI.swiftSourceOutputDirectory.orNull,
                    outputDir = outputDir,
                    projectDir =
                        ext.projectDirectory.orNull?.takeIf { it.isNotBlank() }?.let(::File),
                )
            if (swiftSourceDir != outputDir) {
                logger.lifecycle(
                    "   📁 outputDirectory is an Xcode asset catalog; writing Symbols.swift to " +
                        swiftSourceDir.absolutePath
                )
            }
            generator.generateSwiftEnumFile(
                allSymbolSetNames,
                swiftSourceDir,
                swiftUI.scaleFactor.get(),
            )
            logger.lifecycle("   📝 Generated Symbols.swift (${allSymbolSetNames.size} symbols)")
        }

        logger.lifecycle("✅ Successfully generated $totalGenerated .symbolset bundles")
    }

    companion object {
        /**
         * Decides where `Symbols.swift` lands.
         *
         * An explicit [configured] path wins (absolute, or relative to [projectDir]). Otherwise,
         * when the symbol-set [outputDir] is an `.xcassets` bundle, the Swift source must NOT go
         * inside it: Xcode treats asset catalogs as leaves, so neither the Swift compiler nor
         * synchronized file-system groups ever see sources stored there. In that case the catalog's
         * parent directory is used. Plain-folder output keeps the historical behavior of writing
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
            return if (outputDir.name.endsWith(XCODE_ASSET_CATALOG_SUFFIX)) {
                outputDir.parentFile ?: outputDir
            } else {
                outputDir
            }
        }

        private const val XCODE_ASSET_CATALOG_SUFFIX = ".xcassets"
    }
}
