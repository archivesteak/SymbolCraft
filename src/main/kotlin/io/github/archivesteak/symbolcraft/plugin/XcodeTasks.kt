package io.github.archivesteak.symbolcraft.plugin

import org.gradle.api.Project

/**
 * Hooks into the Kotlin Gradle plugin tasks that hand an Apple framework to Xcode.
 *
 * Xcode's "Compile Kotlin Framework" run-script phase invokes
 * `embedAndSign<Binary>AppleFrameworkForXcode`; CocoaPods integration uses `syncFramework`; Swift
 * export uses `embedSwiftExportForXcode`. IDE-driven iOS builds run the same tasks directly. Making
 * them depend on the symbol catalog guarantees the catalog exists before Xcode compiles assets and
 * Swift sources, whichever entry point is used. Matching by name with `configureEach` is the same
 * approach Compose Multiplatform resources use, and needs no Kotlin plugin classes.
 */
internal object XcodeTasks {

    fun isEmbedTask(name: String): Boolean =
        (name.startsWith("embedAndSign") && name.endsWith("AppleFrameworkForXcode")) ||
            name == "syncFramework" ||
            name == "embedSwiftExportForXcode"

    /** Makes every Xcode embed task of [project] depend on [dependency]. */
    fun dependOnForEmbed(project: Project, dependency: Any) {
        project.tasks.configureEach { task ->
            if (isEmbedTask(task.name)) {
                task.dependsOn(dependency)
            }
        }
    }
}
