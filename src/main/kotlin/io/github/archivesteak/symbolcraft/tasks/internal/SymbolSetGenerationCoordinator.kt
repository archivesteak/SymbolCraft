package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.converter.IconNameTransformer
import io.github.archivesteak.symbolcraft.converter.SymbolSetGenerator
import io.github.archivesteak.symbolcraft.model.IconConfig
import io.github.archivesteak.symbolcraft.model.IconTarget
import java.io.File
import org.gradle.api.logging.Logger

/**
 * Everything the `.symbolset` generation needs, resolved by the owning task.
 *
 * @property config icon requests keyed by icon name
 * @property svgDir downloaded SVG workspace (`<svgDir>/<libraryId>/<icon>.svg`), read only
 * @property catalogDir asset catalog (or catalog child folder) receiving the `.symbolset` bundles
 * @property swiftFile destination of the `Symbols.swift` helper, or null to skip it
 */
internal data class SymbolSetRequest(
    val config: Map<String, List<IconConfig>>,
    val svgDir: File,
    val catalogDir: File,
    val swiftFile: File?,
    val nameTransformer: IconNameTransformer,
    val scaleFactor: Double,
)

/**
 * Generates custom SF Symbol `.symbolset` bundles from the downloaded SVG directories.
 *
 * Reuses the download task's SVG workspace, so enabling SwiftUI output never triggers additional
 * downloads. Intentionally decoupled from Gradle types apart from the injected [Logger].
 */
internal class SymbolSetGenerationCoordinator(private val logger: Logger) {

    /**
     * Generates symbol sets for every library that produced SVGs, writes the catalog manifest, and
     * emits the aggregated `Symbols.swift` helper when requested.
     *
     * @return the number of generated symbol sets
     */
    fun generate(request: SymbolSetRequest): Int {
        logger.lifecycle("Generating SwiftUI .symbolset bundles...")

        val generator = SymbolSetGenerator { message -> logger.lifecycle(message) }
        val iconsByLibrary = IconLibraryClassifier.groupByLibrary(request.config)

        request.catalogDir.mkdirs()
        File(request.catalogDir, "Contents.json").writeText(CATALOG_CONTENTS_JSON)

        val allSymbolSetNames = mutableListOf<String>()
        var totalGenerated = 0

        iconsByLibrary.keys.sorted().forEach { libraryId ->
            val libraryDir = request.svgDir.resolve(libraryId)
            if (!libraryDir.exists() || libraryDir.listFiles()?.isEmpty() != false) {
                logger.warn("No SVG files found for library: $libraryId (SwiftUI)")
                return@forEach
            }

            val libraryConfigs =
                request.config
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
                        libraryTempDir = libraryDir,
                        outputDirectory = request.catalogDir,
                        nameTransformer = request.nameTransformer,
                        scaleFactor = request.scaleFactor,
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

        val swiftFile = request.swiftFile
        if (swiftFile != null) {
            generator.generateSwiftEnumFile(
                allSymbolSetNames.sorted(),
                swiftFile.parentFile,
                request.scaleFactor,
            )
            logger.lifecycle(
                "   Generated ${swiftFile.name} (${allSymbolSetNames.size} symbols) at ${swiftFile.absolutePath}"
            )
        }

        logger.lifecycle(
            "Successfully generated $totalGenerated .symbolset bundles in ${request.catalogDir.absolutePath}"
        )
        return totalGenerated
    }

    companion object {
        /** Manifest Xcode writes at the root of every asset catalog and catalog folder. */
        internal const val CATALOG_CONTENTS_JSON =
            "{\n  \"info\" : {\n    \"author\" : \"xcode\",\n    \"version\" : 1\n  }\n}\n"

        /**
         * Decides where `Symbols.swift` lands.
         *
         * An explicit [configured] path wins (absolute, or relative to [projectDir]). Otherwise,
         * when the symbol-set [outputDir] IS an `.xcassets` bundle or lives INSIDE one (e.g. a
         * dedicated `Assets.xcassets/SymbolCraft` child), the Swift source must not go there: Xcode
         * treats asset catalogs as leaves, so neither the Swift compiler nor synchronized
         * file-system groups ever see sources stored inside. In that case the catalog's parent
         * directory is used. Plain-folder output keeps `Symbols.swift` next to the `.symbolset`
         * bundles.
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
