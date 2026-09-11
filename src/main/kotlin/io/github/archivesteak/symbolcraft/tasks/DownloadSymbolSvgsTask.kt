package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.download.SvgDownloader
import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import io.github.archivesteak.symbolcraft.tasks.internal.DownloadCoordinator
import io.github.archivesteak.symbolcraft.tasks.internal.DownloadResult
import io.github.archivesteak.symbolcraft.tasks.internal.GeneratedFileCleaner
import io.github.archivesteak.symbolcraft.utils.PathUtils
import java.io.File
import kotlinx.coroutines.runBlocking
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fetches every SVG the configuration asks for into one workspace: `<outputDir>/<libraryId>/`.
 *
 * Exposed to consumers as `downloadSymbolCraftSvgs`. Both generators (`generateSymbolCraftIcons`
 * and `generateSymbolCraftSymbolSets`) read this directory, so an icon is never downloaded twice no
 * matter how many outputs are enabled. Remote fetches go through the TTL-based [SvgDownloader]
 * cache; local icons are copied in.
 */
@CacheableTask
abstract class DownloadSymbolSvgsTask : DefaultTask() {

    @get:Internal abstract val extension: Property<SymbolCraftExtension>

    /** Hash of every icon request; a changed icon list means a different workspace. */
    @get:Input
    val downloadHash: String
        get() = extension.get().getDownloadHash()

    /**
     * Contents of every local SVG referenced via `localIcons { }`. Declared as an input so that
     * editing a checked-in SVG invalidates up-to-date checks and build-cache keys; remote SVGs are
     * instead covered by the downloader's TTL cache. ABSOLUTE sensitivity because local icon paths
     * are machine-specific anyway.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val localSvgFiles: ConfigurableFileCollection

    /** SVG cache location (relative to the build directory or absolute); not an output. */
    @get:Internal abstract val cacheDirectory: Property<String>

    @get:Internal abstract val projectBuildDir: Property<String>

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** Downloads (or copies) every requested SVG, failing the build if any icon is missing. */
    @TaskAction
    fun download() = runBlocking {
        val ext = extension.get()
        val config = ext.getIconsConfig()
        val workspace = outputDir.get().asFile
        val cacheBaseDir =
            PathUtils.resolveCacheDirectory(cacheDirectory.get(), projectBuildDir.get())
        val svgCacheDir = File(cacheBaseDir, "svg-cache")

        logger.lifecycle(
            "Collecting SVGs for ${config.values.sumOf { it.size }} icon request(s)..."
        )
        logger.debug("Cache directory: ${cacheBaseDir.absolutePath}")

        val downloader =
            SvgDownloader(
                cacheDirectory = svgCacheDir.absolutePath,
                cacheEnabled = ext.cacheEnabled.get(),
                maxRetries = ext.maxRetries.get(),
                retryDelayMs = ext.retryDelayMs.get(),
                logger = { message -> logger.debug(message) },
            )
        val coordinator = DownloadCoordinator(logger)

        try {
            val stats = coordinator.execute(downloader, config, workspace)

            val failures = stats.results.filterIsInstance<DownloadResult.Failed>()
            if (failures.isNotEmpty()) {
                val details =
                    failures.joinToString("\n") {
                        "  - ${it.iconName} (${it.config.libraryId}): ${it.error}"
                    }
                throw GradleException(
                    "SymbolCraft could not fetch ${failures.size} icon(s):\n$details\n" +
                        "Check the icon names and network access, then rerun."
                )
            }

            if (ext.cacheEnabled.get() && shouldCleanCache(cacheBaseDir)) {
                GeneratedFileCleaner(logger).cleanUnusedCache(svgCacheDir, config)
            }
            coordinator.logCacheStatistics(downloader)
        } finally {
            try {
                downloader.cleanup()
            } catch (cleanupException: Exception) {
                logger.warn("Warning: Failed to cleanup downloader: ${cleanupException.message}")
            }
        }
    }

    /**
     * Shared caches (outside the consumer build directory) are intentionally preserved because
     * multiple projects or IDE syncs may reuse them concurrently.
     */
    private fun shouldCleanCache(cacheBaseDir: File): Boolean {
        val isInsideBuildDir =
            PathUtils.isCacheInsideBuildDir(cacheBaseDir, File(projectBuildDir.get()))
        if (!isInsideBuildDir) {
            logger.lifecycle("Cache cleanup skipped: using shared cache outside build directory")
            logger.lifecycle("   Cache location: ${cacheBaseDir.canonicalFile.absolutePath}")
        }
        return isInsideBuildDir
    }
}
