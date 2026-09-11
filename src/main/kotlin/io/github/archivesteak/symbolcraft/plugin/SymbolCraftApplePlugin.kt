package io.github.archivesteak.symbolcraft.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Consumer plugin registered as `io.github.archivesteak.symbolcraft.apple`.
 *
 * Apply it to the Kotlin Multiplatform project whose framework Xcode embeds, when the icons are
 * declared in a different project:
 * ```kotlin
 * plugins { id("io.github.archivesteak.symbolcraft.apple") }
 * dependencies { symbolCraft(project(":symbols")) }
 * ```
 *
 * Every `embedAndSign*AppleFrameworkForXcode` task of this project then depends on the producer's
 * symbol catalog, so the catalog is generated during the same Gradle run Xcode already makes.
 */
class SymbolCraftApplePlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val symbols =
            SymbolCraftVariants.resolvable(
                project,
                SymbolCraftVariants.APPLE_RESOLVABLE,
                SymbolCraftVariants.APPLE_SYMBOLS,
            )
        XcodeTasks.dependOnForEmbed(project, symbols)
    }
}
