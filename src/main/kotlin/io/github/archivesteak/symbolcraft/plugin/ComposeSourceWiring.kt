package io.github.archivesteak.symbolcraft.plugin

import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.provider.Provider

/**
 * Adds generated Compose sources to a Kotlin source set of the project.
 *
 * The Kotlin Gradle plugin (and the Android Gradle plugin's built-in Kotlin support) are reached
 * through their `kotlin` / `android` extension objects reflectively. That keeps SymbolCraft free of
 * a hard dependency on either plugin's classes, which matters because the plugins may sit on a
 * different class loader than SymbolCraft (for example when SymbolCraft is declared in a parent
 * build script and Kotlin only in a subproject).
 */
internal object ComposeSourceWiring {

    private val MULTIPLATFORM_PLUGIN_IDS =
        listOf("org.jetbrains.kotlin.multiplatform", "com.android.kotlin.multiplatform.library")

    /**
     * Wires [sources] (anything `SourceDirectorySet.srcDir` accepts: a directory provider, a task
     * provider, a resolvable configuration) into the source set named by [sourceSet], or a default
     * derived from the applied plugins. Runs after project evaluation so the DSL is complete.
     *
     * @param warnIfNoKotlin log a warning when neither a Kotlin nor an Android plugin is present
     */
    fun wire(project: Project, sourceSet: Provider<String>, sources: Any, warnIfNoKotlin: Boolean) {
        val action = { wireNow(project, sourceSet, sources, warnIfNoKotlin) }
        if (project.state.executed) action() else project.afterEvaluate { action() }
    }

    private fun wireNow(
        project: Project,
        sourceSet: Provider<String>,
        sources: Any,
        warnIfNoKotlin: Boolean,
    ) {
        val kotlin = project.extensions.findByName("kotlin")
        if (kotlin != null) {
            val name = sourceSet.orNull ?: defaultKotlinSourceSet(project)
            addSourceDir(project, kotlin, name, sources)
            return
        }
        val android = project.extensions.findByName("android")
        if (android != null) {
            val name = sourceSet.orNull ?: "main"
            addSourceDir(project, android, name, sources)
            return
        }
        if (warnIfNoKotlin) {
            project.logger.warn(
                "SymbolCraft: no Kotlin or Android plugin found in ${project.path}; add the " +
                    "'${SymbolCraftVariants.COMPOSE_RESOLVABLE}' configuration as a source " +
                    "directory yourself, e.g. kotlin.sourceSets.commonMain { kotlin.srcDir(" +
                    "configurations[\"${SymbolCraftVariants.COMPOSE_RESOLVABLE}\"]) }"
            )
        }
    }

    private fun defaultKotlinSourceSet(project: Project): String =
        if (MULTIPLATFORM_PLUGIN_IDS.any { project.plugins.hasPlugin(it) }) "commonMain" else "main"

    private fun addSourceDir(
        project: Project,
        extension: Any,
        sourceSetName: String,
        sources: Any,
    ) {
        val sourceSets =
            invoke(extension, "getSourceSets") as? NamedDomainObjectContainer<*>
                ?: throw GradleException(
                    "SymbolCraft: cannot read source sets of ${project.path}; set " +
                        "symbolCraft.composeSourceSet or add the generated directory manually"
                )
        val sourceSet =
            sourceSets.findByName(sourceSetName)
                ?: throw GradleException(
                    "SymbolCraft: source set '$sourceSetName' does not exist in ${project.path}. " +
                        "Set symbolCraft.composeSourceSet (or symbolCraftCompose.sourceSet) to " +
                        "one of: ${sourceSets.names.sorted().joinToString()}"
                )
        val kotlinSources = invoke(sourceSet, "getKotlin")
        if (kotlinSources is SourceDirectorySet) {
            kotlinSources.srcDir(sources)
        } else {
            val method = kotlinSources.javaClass.getMethod("srcDir", Any::class.java)
            method.isAccessible = true
            method.invoke(kotlinSources, sources)
        }
        project.logger.info(
            "SymbolCraft: generated Compose sources added to source set '$sourceSetName' of ${project.path}"
        )
    }

    private fun invoke(target: Any, methodName: String): Any {
        val method = target.javaClass.getMethod(methodName)
        method.isAccessible = true
        return method.invoke(target)
            ?: throw GradleException("SymbolCraft: $methodName returned null on $target")
    }
}
