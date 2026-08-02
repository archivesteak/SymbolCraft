package io.github.archivesteak.symbolcraft.tasks.internal

import io.github.archivesteak.symbolcraft.model.IconConfig
import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import io.github.archivesteak.symbolcraft.utils.PathUtils
import java.io.File

/**
 * Materialises a [GenerationContext] instance from the lazily evaluated Gradle extension state.
 *
 * This factory is intentionally side-effect free: apart from resolving absolute directories it does
 * not mutate the filesystem. That makes it safe to invoke both in production tasks and in TestKit
 * scenarios where we want to inspect the computed paths without triggering downloads.
 */
internal class GenerationContextFactory(
    private val extension: SymbolCraftExtension,
    private val outputDir: File,
    private val cacheDirectory: String,
    private val projectBuildDir: String,
) {

    fun create(): GenerationContext {
        val cacheBaseDir = PathUtils.resolveCacheDirectory(cacheDirectory, projectBuildDir)

        val swiftUIOutputDir =
            if (extension.swiftUIConfig.enabled.get()) {
                val configured = extension.swiftUIConfig.outputDirectory.get()
                val file = File(configured)
                if (file.isAbsolute) file else File(extension.projectDirectory.get(), configured)
            } else {
                null
            }

        return GenerationContext(
            extension = extension,
            config = extension.getIconsConfig(),
            packageName = extension.packageName.get(),
            cacheBaseDir = cacheBaseDir,
            tempDir = File(cacheBaseDir, "temp-svgs"),
            svgCacheDir = File(cacheBaseDir, "svg-cache"),
            outputDir = outputDir,
            projectBuildDir = projectBuildDir,
            swiftUIOutputDir = swiftUIOutputDir,
        )
    }
}

/**
 * Snapshot of the complete environment required to process a symbol generation pass.
 *
 * Breaking this out of [GenerateSymbolsTask] allows downstream collaborators to depend on a small,
 * immutable object rather than the heavyweight Gradle task API. This drastically simplifies unit
 * testing and keeps our pipeline code agnostic of Gradle internals.
 */
internal data class GenerationContext(
    val extension: SymbolCraftExtension,
    val config: Map<String, List<IconConfig>>,
    val packageName: String,
    val cacheBaseDir: File,
    val tempDir: File,
    val svgCacheDir: File,
    val outputDir: File,
    val projectBuildDir: String,
    val swiftUIOutputDir: File?,
)
