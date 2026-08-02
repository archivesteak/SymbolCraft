package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.download.SvgDownloader
import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import io.github.archivesteak.symbolcraft.tasks.internal.DownloadCoordinator
import io.github.archivesteak.symbolcraft.tasks.internal.GenerationContext
import io.github.archivesteak.symbolcraft.tasks.internal.GenerationContextFactory
import io.github.archivesteak.symbolcraft.tasks.internal.IconLibraryClassifier
import io.github.archivesteak.symbolcraft.tasks.internal.PreGenerationCleaner
import io.github.archivesteak.symbolcraft.tasks.internal.SvgConversionCoordinator
import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetGenerationCoordinator
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Gradle entry point that orchestrates the SymbolCraft pipeline.
 *
 * High-level workflow:
 * 1. Materialise a [GenerationContext] describing the user DSL configuration and filesystem layout.
 * 2. Run pre-generation housekeeping so old Kotlin sources and obsolete cache entries disappear.
 * 3. Download (or copy) requested SVG assets in parallel, recording telemetry along the way.
 * 4. Invoke the SVG -> Compose converter for each icon library and emit Kotlin sources.
 * 5. Report cache statistics and surface actionable guidance when failures occur.
 *
 * Each of those steps lives in its own collaborator under `tasks.internal`, which keeps this task
 * focused on Gradle-related aspects: wiring inputs/outputs, logging, and failure recovery.
 */
@CacheableTask
abstract class GenerateSymbolsTask : DefaultTask() {

    @get:Internal abstract val extension: Property<SymbolCraftExtension>

    @get:Input
    val symbolsConfigHash: String
        get() = extension.get().getConfigHash()

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** SwiftUI `.symbolset` output directory; only present when SwiftUI output is enabled. */
    @get:Optional @get:OutputDirectory abstract val swiftUIOutputDir: DirectoryProperty

    /** Directory where `Symbols.swift` is written; only present when SwiftUI output is enabled. */
    @get:Optional @get:OutputDirectory abstract val swiftUISourceDir: DirectoryProperty

    @get:Input abstract val cacheDirectory: Property<String>

    @get:Input abstract val projectBuildDir: Property<String>

    /**
     * Executes a full generation pass. The method runs inside `runBlocking` so downstream
     * collaborators can use coroutines without leaking into Gradle's threading model.
     */
    @TaskAction
    fun generate() = runBlocking {
        val resolvedExtension = extension.get()
        val context = buildContext(resolvedExtension)

        logGenerationStart(context)

        val iconsByLibrary = IconLibraryClassifier.groupByLibrary(context.config)
        logger.debug("Libraries found: ${iconsByLibrary.keys.joinToString()}")

        val cleaner = PreGenerationCleaner(logger)
        cleaner.clean(context)

        val downloader = setupDownloader(context)
        val downloadCoordinator = DownloadCoordinator(logger)
        val conversionCoordinator = SvgConversionCoordinator(logger)
        val symbolSetCoordinator = SymbolSetGenerationCoordinator(logger)

        try {
            downloadCoordinator.execute(downloader, context.config, context.tempDir)
            conversionCoordinator.convert(context, iconsByLibrary)
            if (context.swiftUIOutputDir != null) {
                symbolSetCoordinator.generate(context, iconsByLibrary)
            }
            downloadCoordinator.logCacheStatistics(downloader)
        } catch (e: Exception) {
            handleGenerationError(e)
        } finally {
            cleanupDownloader(downloader)
        }
    }

    /** Builds the immutable [GenerationContext] consumed by the specialised collaborators. */
    private fun buildContext(ext: SymbolCraftExtension): GenerationContext {
        val contextFactory =
            GenerationContextFactory(
                extension = ext,
                outputDir = outputDir.get().asFile,
                cacheDirectory = cacheDirectory.get(),
                projectBuildDir = projectBuildDir.get(),
                swiftUIOutputDir = swiftUIOutputDir.orNull?.asFile,
                swiftUISourceDir = swiftUISourceDir.orNull?.asFile,
            )
        return contextFactory.create()
    }

    /** Emits a friendly banner describing how many icons will be processed in this build pass. */
    private fun logGenerationStart(context: GenerationContext) {
        val totalIcons = context.config.values.sumOf { it.size }
        logger.lifecycle("Generating icons...")
        logger.lifecycle("Icons to generate: $totalIcons total")
        logger.debug("Cache directory: ${context.cacheBaseDir.absolutePath}")
    }

    /**
     * Instantiates a downloader using the configuration present in the DSL.
     *
     * The task owns the downloader lifecycle so it can close client resources (HTTP engines, cache
     * handles) even when a failure occurs.
     */
    private fun setupDownloader(context: GenerationContext): SvgDownloader {
        return SvgDownloader(
            cacheDirectory = context.svgCacheDir.absolutePath,
            cacheEnabled = context.extension.cacheEnabled.get(),
            maxRetries = context.extension.maxRetries.get(),
            retryDelayMs = context.extension.retryDelayMs.get(),
            logger = { message -> logger.debug(message) },
        )
    }

    /**
     * Augments the original exception with troubleshooting hints before rethrowing.
     *
     * Users frequently rely on build logs to diagnose issues, so the extra context (network vs
     * cache vs SVG parsing) saves them from scanning the entire stack trace.
     */
    private fun handleGenerationError(e: Exception): Nothing {
        logger.error("Generation failed: ${e.message}")
        logger.error("   Stack trace: ${e.stackTraceToString()}")

        val guidance =
            when {
                e.message?.contains("network", ignoreCase = true) == true ->
                    "Network issue detected. Check internet connection and try again."
                e.message?.contains("cache", ignoreCase = true) == true ->
                    "Cache issue detected. Try running with --rerun-tasks or clearing cache."
                e.message?.contains("SVG", ignoreCase = true) == true ->
                    "SVG processing issue. Check if the requested icons exist in Material Symbols."
                else -> "Unexpected error. Please check configuration and try again."
            }

        logger.error("   Hint: $guidance")
        throw e
    }

    /**
     * Attempts to release downloader resources. Failures are logged but ignored because the build
     * should not fail solely due to cleanup issues (e.g. a temp directory that was already
     * deleted).
     */
    private fun cleanupDownloader(downloader: SvgDownloader) {
        try {
            downloader.cleanup()
        } catch (cleanupException: Exception) {
            logger.warn("Warning: Failed to cleanup downloader: ${cleanupException.message}")
        }
    }
}
