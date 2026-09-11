package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.utils.PathUtils
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Deletes the SVG cache and the downloaded SVG workspace.
 *
 * Exposed to consumers as `cleanSymbolCraftCache`. Compatible with the configuration cache.
 */
abstract class CleanSymbolsCacheTask : DefaultTask() {
    @get:Internal abstract val cacheDirectory: Property<String>

    @get:Internal abstract val projectBuildDir: Property<String>

    /** Workspace produced by `downloadSymbolCraftSvgs`. */
    @get:Internal abstract val svgWorkspace: DirectoryProperty

    /** Deletes the configured cache directory and the SVG workspace. */
    @TaskAction
    fun clean() {
        val cacheBaseDir =
            PathUtils.resolveCacheDirectory(cacheDirectory.get(), projectBuildDir.get())

        logger.lifecycle("Cleaning SymbolCraft icon cache...")
        logger.lifecycle("Cache location: ${cacheBaseDir.absolutePath}")

        var deletedCount = 0

        if (cacheBaseDir.exists()) {
            val svgCacheDir = File(cacheBaseDir, "svg-cache")
            if (svgCacheDir.exists()) {
                val fileCount = svgCacheDir.listFiles()?.size ?: 0
                if (svgCacheDir.deleteRecursively()) {
                    deletedCount += fileCount
                    logger.lifecycle("   Cleaned SVG cache: $fileCount files")
                } else {
                    logger.warn(
                        "   Failed to clean SVG cache directory: ${svgCacheDir.absolutePath}"
                    )
                }
            }
            if (cacheBaseDir.listFiles()?.isEmpty() == true) {
                cacheBaseDir.delete()
                logger.lifecycle("   Removed empty cache directory")
            }
        } else {
            logger.lifecycle("No cache to clean (directory does not exist)")
        }

        svgWorkspace.orNull?.asFile?.let { workspace ->
            if (workspace.exists()) {
                val fileCount = workspace.walkTopDown().count { it.isFile }
                workspace.deleteRecursively()
                deletedCount += fileCount
                logger.lifecycle("   Cleaned SVG workspace: $fileCount files")
            }
        }

        logger.lifecycle("Total cache cleaned: $deletedCount files")
    }
}
