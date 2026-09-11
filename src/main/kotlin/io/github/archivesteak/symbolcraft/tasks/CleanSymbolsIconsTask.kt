package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.tasks.internal.GeneratedFileCleaner
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Deletes everything `generateSymbolCraftIcons` and `generateSymbolCraftSymbolSets` produced.
 *
 * Exposed to consumers as `cleanSymbolCraftIcons`. Source files are only deleted when they carry
 * the SymbolCraft header. Compatible with the configuration cache.
 */
abstract class CleanSymbolsIconsTask : DefaultTask() {

    @get:Internal abstract val packageName: Property<String>

    /** Compose source output directory. */
    @get:Internal abstract val outputDirectory: DirectoryProperty

    /** Asset catalog holding the `.symbolset` bundles. */
    @get:Internal abstract val swiftUICatalogDirectory: DirectoryProperty

    /** Generated `Symbols.swift`, wherever it was written. */
    @get:Internal abstract val swiftUISourceFile: RegularFileProperty

    /** Deletes all generated files. */
    @TaskAction
    fun clean() {
        val cleaner = GeneratedFileCleaner(logger)
        var deletedCount = 0

        outputDirectory.orNull?.asFile?.let { dir ->
            deletedCount += cleaner.cleanGeneratedKotlin(dir, packageName.get())
        }
        swiftUICatalogDirectory.orNull?.asFile?.let { catalog ->
            deletedCount += cleaner.cleanSymbolSets(catalog)
        }
        swiftUISourceFile.orNull?.asFile?.let { file ->
            if (cleaner.deleteGeneratedSource(file)) deletedCount++
        }

        logger.lifecycle("Cleaned $deletedCount generated icon files")
    }
}
