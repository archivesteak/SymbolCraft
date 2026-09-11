package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.converter.IconNameTransformer
import io.github.archivesteak.symbolcraft.converter.Svg2ComposeConverter
import io.github.archivesteak.symbolcraft.model.IconConfig
import io.github.archivesteak.symbolcraft.model.IconTarget
import io.github.archivesteak.symbolcraft.model.tempSvgFileName
import java.io.File
import org.gradle.api.logging.Logger

/**
 * Everything the Compose conversion needs, resolved by the owning task.
 *
 * @property config icon requests keyed by icon name
 * @property svgDir downloaded SVG workspace (`<svgDir>/<libraryId>/<icon>.svg`), owned by the
 *   download task and never written to here
 * @property stagingDir task-private scratch space for libraries that need target filtering
 * @property outputDir Kotlin source root to write into
 */
internal data class ComposeConversionRequest(
    val config: Map<String, List<IconConfig>>,
    val svgDir: File,
    val stagingDir: File,
    val outputDir: File,
    val packageName: String,
    val generatePreview: Boolean,
    val previewAnnotationClass: String,
    val nameTransformer: IconNameTransformer,
)

/**
 * Converts downloaded SVG directories into Compose-ready Kotlin sources per icon library.
 *
 * This class is intentionally decoupled from Gradle types to keep the conversion logic portable;
 * the only bridge to Gradle is the injected [Logger].
 */
internal class SvgConversionCoordinator(
    private val logger: Logger,
    private val converter: Svg2ComposeConverter = Svg2ComposeConverter(),
) {

    /**
     * Converts each library subdirectory produced by the download phase.
     *
     * @return the number of icons converted
     */
    fun convert(request: ComposeConversionRequest): Int {
        logger.lifecycle("Converting SVGs to Compose ImageVectors...")

        var totalConverted = 0
        val iconsByLibrary = IconLibraryClassifier.groupByLibrary(request.config)

        iconsByLibrary.keys.sorted().forEach { libraryId ->
            val libraryDir = request.svgDir.resolve(libraryId)
            if (!libraryDir.exists() || libraryDir.listFiles()?.isEmpty() != false) {
                logger.warn("No SVG files found for library: $libraryId")
                return@forEach
            }

            // Icons that do not target Compose (e.g. swiftUIOnly) must not reach the converter.
            // The converter consumes a whole directory, so when filtering is active the allowed
            // SVGs are staged into a task-private directory; the common all-allowed case converts
            // the download directory in place with zero copies.
            val composeFileNames =
                request.config
                    .flatMap { (iconName, configs) ->
                        configs
                            .filter {
                                it.libraryId == libraryId && IconTarget.COMPOSE in it.targets
                            }
                            .map { tempSvgFileName(iconName, it) }
                    }
                    .toSet()

            if (composeFileNames.isEmpty()) {
                logger.lifecycle("   Skipping library $libraryId for Compose: no COMPOSE targets")
                return@forEach
            }

            val conversionDir =
                if (libraryDir.listFiles()?.all { it.name in composeFileNames } == true) {
                    libraryDir
                } else {
                    val staging = request.stagingDir.resolve(libraryId)
                    staging.deleteRecursively()
                    staging.mkdirs()
                    libraryDir
                        .listFiles()
                        ?.filter { it.isFile && it.name in composeFileNames }
                        ?.forEach { it.copyTo(File(staging, it.name), overwrite = true) }
                    logger.lifecycle(
                        "   Excluding ${(libraryDir.listFiles()?.size ?: 0) - composeFileNames.size} SwiftUI-only icon(s) from Compose output"
                    )
                    staging
                }

            val librarySubdir =
                when {
                    libraryId == "material-symbols" -> "materialsymbols"
                    libraryId.startsWith("external-") -> libraryId.removePrefix("external-")
                    else -> libraryId
                }

            logger.lifecycle("   Converting library: $libraryId -> icons/$librarySubdir/")

            try {
                converter.convertDirectory(
                    inputDirectory = conversionDir,
                    outputDirectory = request.outputDir,
                    packageName = request.packageName,
                    generatePreview = request.generatePreview,
                    previewAnnotationClass = request.previewAnnotationClass,
                    accessorName = "Icons",
                    allAssetsPropertyName = "AllIcons",
                    librarySubdir = librarySubdir,
                    nameTransformer = request.nameTransformer,
                )
                val iconCount = conversionDir.listFiles()?.size ?: 0
                totalConverted += iconCount
                logger.lifecycle("      Converted $iconCount icons")
            } catch (e: Exception) {
                // Rethrow after logging: swallowing the failure would let the task succeed with
                // partial output, and the cacheable task would then cache that broken state.
                logger.error("SVG conversion failed for library $libraryId: ${e.message}")
                logger.error("   Stack trace: ${e.stackTraceToString()}")
                when {
                    e.message?.contains("directory", ignoreCase = true) == true ->
                        logger.error(
                            "   Hint: directory issue - check input/output directories exist and are writable"
                        )
                    e.message?.contains("package", ignoreCase = true) == true ->
                        logger.error(
                            "   Hint: package issue - check packageName is a valid Kotlin package identifier"
                        )
                    e.message?.contains("SVG", ignoreCase = true) == true ->
                        logger.error(
                            "   Hint: SVG parsing issue - some downloaded SVG files may be malformed"
                        )
                    else ->
                        logger.error(
                            "   Hint: unexpected conversion error: ${e.javaClass.simpleName}"
                        )
                }
                throw e
            }
        }

        logger.lifecycle("Successfully converted $totalConverted icons total")
        return totalConverted
    }
}
