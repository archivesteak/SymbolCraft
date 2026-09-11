package io.github.archivesteak.symbolcraft.plugin

import io.github.archivesteak.symbolcraft.tasks.GenerateComposeIconsTask
import io.github.archivesteak.symbolcraft.tasks.GenerateSymbolSetsTask
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Category
import org.gradle.api.tasks.TaskProvider

/**
 * Variant-aware sharing of SymbolCraft outputs between Gradle projects.
 *
 * The producer project (the one applying `io.github.archivesteak.symbolcraft`) exposes one
 * consumable configuration per output. Consumer projects declare `symbolCraft(project(":owner"))`
 * and resolve the variant they need through a resolvable configuration carrying the same
 * attributes. Resolved configurations are file collections that carry the producer task as a
 * dependency, so no project ever references another project's tasks or model, which keeps the setup
 * valid under Gradle's Isolated Projects.
 */
internal object SymbolCraftVariants {

    /** Distinguishes the two SymbolCraft outputs. */
    val OUTPUT_ATTRIBUTE: Attribute<String> =
        Attribute.of("io.github.archivesteak.symbolcraft.output", String::class.java)

    /** [Category] value that keeps SymbolCraft variants apart from library/documentation ones. */
    const val CATEGORY = "symbolcraft"

    const val COMPOSE_SOURCES = "compose-sources"
    const val APPLE_SYMBOLS = "apple-symbols"

    /** Dependency bucket shared by both consumer plugins: `symbolCraft(project(":owner"))`. */
    const val DEPENDENCY_SCOPE = "symbolCraft"

    const val COMPOSE_ELEMENTS = "symbolCraftComposeSourcesElements"
    const val APPLE_ELEMENTS = "symbolCraftAppleSymbolsElements"
    const val COMPOSE_RESOLVABLE = "symbolCraftComposeSources"
    const val APPLE_RESOLVABLE = "symbolCraftAppleSymbols"

    /** Registers the outgoing variants on the producer project. */
    fun registerProducer(
        project: Project,
        composeTask: TaskProvider<GenerateComposeIconsTask>,
        symbolSetTask: TaskProvider<GenerateSymbolSetsTask>,
    ) {
        consumable(project, COMPOSE_ELEMENTS, COMPOSE_SOURCES).outgoing.artifact(
            composeTask.flatMap { it.outputDir }
        ) {
            it.type = "directory"
            it.builtBy(composeTask)
        }
        consumable(project, APPLE_ELEMENTS, APPLE_SYMBOLS).outgoing.artifact(
            symbolSetTask.flatMap { it.catalogDir }
        ) {
            it.type = "directory"
            it.builtBy(symbolSetTask)
        }
    }

    /** Returns (creating on first use) the consumer-side dependency bucket. */
    fun dependencyScope(project: Project): Configuration =
        project.configurations.findByName(DEPENDENCY_SCOPE)
            ?: project.configurations.create(DEPENDENCY_SCOPE) { configuration ->
                configuration.description =
                    "SymbolCraft producer projects, e.g. symbolCraft(project(\":shared\"))"
                configuration.isCanBeConsumed = false
                configuration.isCanBeResolved = false
                configuration.isVisible = false
            }

    /** Creates a resolvable configuration selecting the [kind] variant of every producer. */
    fun resolvable(project: Project, name: String, kind: String): Configuration =
        project.configurations.create(name) { configuration ->
            configuration.description = "Resolved SymbolCraft $kind output"
            configuration.isCanBeConsumed = false
            configuration.isCanBeResolved = true
            configuration.isVisible = false
            configuration.extendsFrom(dependencyScope(project))
            applyAttributes(project, configuration, kind)
        }

    private fun consumable(project: Project, name: String, kind: String): Configuration =
        project.configurations.create(name) { configuration ->
            configuration.description = "SymbolCraft $kind output for other projects"
            configuration.isCanBeConsumed = true
            configuration.isCanBeResolved = false
            configuration.isVisible = false
            applyAttributes(project, configuration, kind)
        }

    private fun applyAttributes(project: Project, configuration: Configuration, kind: String) {
        configuration.attributes { attributes ->
            attributes.attribute(
                Category.CATEGORY_ATTRIBUTE,
                project.objects.named(Category::class.java, CATEGORY),
            )
            attributes.attribute(OUTPUT_ATTRIBUTE, kind)
        }
    }
}
