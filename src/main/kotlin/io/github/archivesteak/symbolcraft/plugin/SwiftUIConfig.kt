package io.github.archivesteak.symbolcraft.plugin

import org.gradle.api.provider.Property

/**
 * Configuration for SwiftUI output (custom SF Symbol `.symbolset` bundles).
 *
 * When enabled, the `generateSymbolCraftSymbolSets` task converts the same downloaded SVGs used for
 * Compose generation into an Xcode asset catalog full of `.symbolset` bundles, plus an optional
 * `Symbols.swift` helper enum. The catalog is produced whenever an Apple framework is embedded for
 * Xcode from this project (or from a project that consumes it through the
 * `io.github.archivesteak.symbolcraft.apple` plugin).
 *
 * Example:
 * ```kotlin
 * symbolCraft {
 *     swiftUI {
 *         enabled.set(true)
 *     }
 * }
 * ```
 *
 * @property enabled toggles `.symbolset` generation (default: false).
 * @property outputDirectory the asset catalog to write `.symbolset` bundles into. Unset means
 *   `build/generated/symbolcraft/swiftui/SymbolCraft.xcassets`; a relative path resolves against
 *   the project directory. Point it at a dedicated child folder of an app's own `.xcassets` if you
 *   prefer committed output.
 * @property scaleFactor multiplier applied on top of the default cap-height fit (default: 1.0).
 *   Increase to make symbols appear larger relative to text.
 * @property generateSwiftEnum toggles generation of the `Symbols.swift` helper enum (default:
 *   true).
 * @property swiftSourceOutputDirectory directory where `Symbols.swift` is written (optional). When
 *   unset: if [outputDirectory] IS an `.xcassets` bundle or lives INSIDE one (matched
 *   case-insensitively), the catalog's PARENT directory is used, because Xcode treats asset
 *   catalogs as leaves and never compiles sources stored inside them; otherwise `Symbols.swift` is
 *   written next to the `.symbolset` bundles.
 */
abstract class SwiftUIConfig {
    abstract val enabled: Property<Boolean>
    abstract val outputDirectory: Property<String>
    abstract val scaleFactor: Property<Double>
    abstract val generateSwiftEnum: Property<Boolean>
    abstract val swiftSourceOutputDirectory: Property<String>

    init {
        enabled.convention(false)
        scaleFactor.convention(1.0)
        generateSwiftEnum.convention(true)
        // No conventions for the directories: unset means "derive" (see KDoc above).
    }

    /** Stable signature used in the Gradle up-to-date / build-cache key. */
    internal fun snapshotSignature(): String {
        return "SwiftUIConfig(" +
            "enabled=${enabled.orNull}," +
            "outputDirectory='${outputDirectory.orNull}'," +
            "scaleFactor=${scaleFactor.orNull}," +
            "generateSwiftEnum=${generateSwiftEnum.orNull}," +
            "swiftSourceOutputDirectory='${swiftSourceOutputDirectory.orNull}'" +
            ")"
    }
}
