package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.plugin.SymbolCraftExtension
import io.github.archivesteak.symbolcraft.tasks.internal.ComposeConversionRequest
import io.github.archivesteak.symbolcraft.tasks.internal.GeneratedFileCleaner
import io.github.archivesteak.symbolcraft.tasks.internal.SvgConversionCoordinator
import io.github.archivesteak.symbolcraft.tasks.internal.toTransformer
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns the downloaded SVG workspace into Compose `ImageVector` Kotlin sources.
 *
 * Exposed to consumers as `generateSymbolCraftIcons`. The output directory is offered to other
 * projects through the `symbolCraftComposeSourcesElements` configuration and, when a Kotlin plugin
 * is applied to the same project, added to the configured source set automatically.
 */
@CacheableTask
abstract class GenerateComposeIconsTask : DefaultTask() {

    @get:Internal abstract val extension: Property<SymbolCraftExtension>

    /**
     * Hash of everything that influences the generated Kotlin (icons, package, naming, previews).
     */
    @get:Input
    val composeHash: String
        get() = extension.get().getComposeHash()

    /** SVG workspace produced by `downloadSymbolCraftSvgs`. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val svgDir: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    /** Regenerates every Compose source from scratch. */
    @TaskAction
    fun generate() {
        val ext = extension.get()
        val output = outputDir.get().asFile

        GeneratedFileCleaner(logger).cleanGeneratedKotlin(output, ext.packageName.get())
        output.mkdirs()

        val request =
            ComposeConversionRequest(
                config = ext.getIconsConfig(),
                svgDir = svgDir.get().asFile,
                stagingDir = temporaryDir,
                outputDir = output,
                packageName = ext.packageName.get(),
                generatePreview = ext.generatePreview.get(),
                previewAnnotationClass = ext.previewAnnotationClass.get(),
                nameTransformer = ext.namingConfig.toTransformer(),
            )
        SvgConversionCoordinator(logger).convert(request)
        logger.lifecycle("Compose sources written to ${output.absolutePath}")
    }
}
