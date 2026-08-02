package io.github.archivesteak.symbolcraft.plugin

import org.gradle.api.provider.Property

/**
 * Configuration for SwiftUI output (custom SF Symbol `.symbolset` bundles).
 *
 * When enabled, SymbolCraft converts the same downloaded SVGs used for Compose generation into
 * `.symbolset` folders that can be dropped into an Xcode asset catalog, plus an optional
 * `Symbols.swift` helper enum.
 *
 * Example:
 * ```kotlin
 * symbolCraft {
 *     swiftUI {
 *         enabled.set(true)
 *         outputDirectory.set("iosApp/GeneratedSymbols")
 *     }
 * }
 * ```
 *
 * @property enabled toggles `.symbolset` generation (default: false).
 * @property outputDirectory directory where `.symbolset` folders and `Symbols.swift` are written
 *   (default: `build/generated/symbolcraft/swiftui`, relative to the project directory).
 * @property scaleFactor multiplier applied on top of the default cap-height fit (default: 1.0).
 *   Increase to make symbols appear larger relative to text.
 * @property generateSwiftEnum toggles generation of the `Symbols.swift` helper enum (default:
 *   true).
 * @property swiftSourceOutputDirectory directory where `Symbols.swift` is written (optional). When
 *   unset: if [outputDirectory] ends in `.xcassets`, the catalog's PARENT directory is used (Xcode
 *   treats asset catalogs as leaves — sources inside them are invisible to the Swift compiler, and
 *   synchronized file-system groups never descend into `.xcassets`); otherwise `Symbols.swift` is
 *   written next to the `.symbolset` bundles as before.
 */
abstract class SwiftUIConfig {
    abstract val enabled: Property<Boolean>
    abstract val outputDirectory: Property<String>
    abstract val scaleFactor: Property<Double>
    abstract val generateSwiftEnum: Property<Boolean>
    abstract val swiftSourceOutputDirectory: Property<String>

    init {
        enabled.convention(false)
        outputDirectory.convention("build/generated/symbolcraft/swiftui")
        scaleFactor.convention(1.0)
        generateSwiftEnum.convention(true)
        // No convention: unset means "derive from outputDirectory" (see KDoc above).
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
