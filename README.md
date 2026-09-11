# SymbolCraft

![GitHub Release](https://img.shields.io/github/v/release/archivesteak/SymbolCraft)

A Gradle plugin for Kotlin Multiplatform projects that generates icons on demand from multiple icon libraries (Material Symbols, Bootstrap Icons, Heroicons, local SVGs, any URL template) — as Compose `ImageVector` code for Android/Desktop, and as custom SF Symbol `.symbolset` catalogs for native SwiftUI.

- One declaration, two outputs: the same SVG becomes a Compose `ImageVector` and a `.symbolset`, downloaded once
- Lives in the module both builds already compile: apply it to your shared Kotlin module, and the Android build and the Xcode build each produce the output they need
- On-demand generation: only the icons you declare, instead of bundling Material Icons Extended (11.3 MB)
- Outputs stay in `build/`; nothing is written into another module's source tree
- Smart caching: 7-day SVG cache; three cacheable, configuration-cache-compatible tasks
- Parallel downloads via Kotlin coroutines, with configurable retries and exponential backoff
- Deterministic output: no timestamps, normalized floats — same input, same bytes
- Full Material Symbols style support: weight (100–700), variant (outlined/rounded/sharp), fill state
- Flexible naming: PascalCase, camelCase, snake_case, kebab-case, custom transformers
- Compose Preview generation (configurable annotation class)
- Per-icon platform targeting: `swiftUIOnly()` / `composeOnly()`
- Local SVG support: convert checked-in SVGs with glob include/exclude patterns

## Installation

SymbolCraft is published to **GitHub Packages** (not Maven Central or the Plugin Portal).

1. Add the repository in `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/archivesteak/SymbolCraft")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
        gradlePluginPortal()
    }
}
```

GitHub Packages requires authentication even for public packages. Set in `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=YOUR_PAT_WITH_READ_PACKAGES
```

Transitive dependencies (e.g. `svg-to-compose`) resolve from `mavenCentral()`, so keep it in your dependency repositories.

2. Apply the plugin:

```toml
# libs.versions.toml
[plugins]
symbolCraft = { id = "io.github.archivesteak.symbolcraft", version = "0.8.0" }
```

## Where to apply it

Apply the plugin to **the module both platform builds compile**. That module owns the icon declaration, and each build produces the output it needs from the same declaration:

| Project layout | Apply to | Compose sources go to | Symbol catalog is produced by |
|---|---|---|---|
| Shared Kotlin logic + Compose on Android + **SwiftUI on iOS** (KMP wizard default) | `shared` | a non-iOS source set, e.g. `androidMain` (`composeSourceSet`) | Xcode's "Compile Kotlin Framework" phase, when it embeds the `shared` framework |
| Shared Compose UI on every platform | `composeApp` | `commonMain` (default) | the same phase, when it embeds the `composeApp` framework |
| Icons in their own module | `:symbols` (any project) | consumers via the `.compose` plugin | consumers via the `.apple` plugin |

Never apply it to an Android-only UI module and point it at the iOS app: that module never takes part in the iOS build, so the iOS assets would only ever change when someone builds the Android app.

```kotlin
// shared/build.gradle.kts
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.symbolCraft)
}

kotlin {
    androidTarget(); jvm(); iosArm64(); iosSimulatorArm64()
    sourceSets {
        androidMain.dependencies { implementation(libs.compose.ui) } // ImageVector lives here
    }
}

symbolCraft {
    packageName.set("com.example.icons")
    composeSourceSet.set("androidMain")   // keep Compose out of the iOS framework
    swiftUI { enabled.set(true) }

    materialSymbol("home") { standardWeights() }
    materialSymbol("airplay") { style(weight = 400); swiftUIOnly() }
}
```

The Android app gets the icons through its normal `implementation(project(":shared"))`. The iOS app gets the catalog through the Xcode setup described below.

## Outputs

All outputs default to the applying project's `build/` directory and are exclusive task outputs, so Gradle's build cache and up-to-date checks work:

| Output | Task | Default location |
|---|---|---|
| SVG workspace | `downloadSymbolCraftSvgs` | `build/symbolcraft/svgs/<library>/` |
| Compose sources | `generateSymbolCraftIcons` | `build/generated/symbolcraft/compose/` |
| SF Symbol catalog | `generateSymbolCraftSymbolSets` | `build/generated/symbolcraft/swiftui/SymbolCraft.xcassets/` |
| Swift helper | `generateSymbolCraftSymbolSets` | `build/generated/symbolcraft/swiftui/Symbols.swift` |

When a Kotlin (or Android) plugin is applied to the same project, the Compose output is added to `composeSourceSet` automatically (`commonMain` for multiplatform, `main` otherwise), and Kotlin compilation depends on generation. Every `embedAndSign*AppleFrameworkForXcode` task of the project depends on the catalog.

`outputDirectory` and `swiftUI.outputDirectory` remain available if you want committed output instead (relative paths resolve against the project directory). Point the SwiftUI one at a dedicated child folder of your app's `.xcassets`; `Symbols.swift` is then written to the catalog's parent, because Xcode never compiles sources stored inside an asset catalog.

## Xcode setup (SwiftUI)

The catalog is generated by the same Gradle run Xcode already makes to embed the Kotlin framework, so the run-script phase from the [Kotlin direct-integration docs](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html) stays as it is. Then, once:

1. **Add the generated files as references** (File > Add Files, uncheck "Copy items"): `shared/build/generated/symbolcraft/swiftui/SymbolCraft.xcassets` and `Symbols.swift`. Xcode compiles asset catalogs and sources from any path.
2. **Declare them as outputs of the "Compile Kotlin Framework" script phase** (Output Files): `$(SRCROOT)/../shared/build/generated/symbolcraft/swiftui/SymbolCraft.xcassets` and `.../Symbols.swift`. Xcode needs this for any input produced by a script; without it a fresh clone fails with "Build input file cannot be found".

The Kotlin docs already require that script phase to sit before Compile Sources, so both files exist before actool and swiftc run. IDE-driven iOS builds (Android Studio, Fleet) skip the script and run the embed task through Gradle directly, which produces the catalog too. See `example/iosApp/iosApp.xcodeproj` for a project set up this way.

In Swift:

```swift
Image(symbol: .homeOutlined)                    // sizes by font point size
GeneratedSymbol.homeOutlined.image(boxSize: 24) // exact 24x24 pt box
```

`.symbolset` glyphs size by font, not by box. The generated `Symbols.swift` exposes `GeneratedSymbol.pointScale` (= 1 / (1.7 × 0.7 × scaleFactor)) and the `image(boxSize:)` helper to convert an artwork box to the right font size.

### SwiftUI options

```kotlin
swiftUI {
    enabled.set(true)                 // default: false
    scaleFactor.set(1.0)              // default: 1.0
    generateSwiftEnum.set(true)       // default: true
    // outputDirectory.set("../iosApp/iosApp/Assets.xcassets/SymbolCraft")  // committed mode
    // swiftSourceOutputDirectory.set("../iosApp/iosApp/Generated")          // override
}
```

Material weights map to real SF weight columns (W400->Regular, W500->Medium, W700->Bold, …): each `(icon, variant, fill)` combination becomes one `.symbolset` with the full 27-variant grid — configured weights use genuine downloaded glyphs, the rest are derived per Apple's relative sizing. External/local icons produce Regular-only sets.

## Icons in a separate module

If the declaration should live outside the shared module, apply the producer there and consume it with the companion plugins. Consumers never reference the producer's tasks: the outputs are shared as Gradle variants, which also keeps the setup valid under Isolated Projects.

```kotlin
// symbols/build.gradle.kts — the declaration, no Kotlin plugin needed
plugins { id("io.github.archivesteak.symbolcraft") }
symbolCraft { swiftUI { enabled.set(true) }; materialSymbol("home") { standardWeights() } }

// androidApp/build.gradle.kts — compiles the Compose sources
plugins { id("io.github.archivesteak.symbolcraft.compose") }
dependencies { symbolCraft(project(":symbols")) }
symbolCraftCompose { sourceSet.set("main") } // optional

// shared/build.gradle.kts — the module Xcode embeds: catalog is produced before embedding
plugins { id("io.github.archivesteak.symbolcraft.apple") }
dependencies { symbolCraft(project(":symbols")) }
```

## Declaring icons

```kotlin
symbolCraft {
    packageName.set("com.example.icons")
    generatePreview.set(false)                          // @Preview functions per icon
    // previewAnnotationClass.set("androidx.compose.ui.tooling.preview.Preview")

    naming { pascalCase() }                             // camelCase(), snakeCase(), kebabCase(), customTransformer(...)

    materialSymbol("search") { standardWeights() }      // 400, 500, 700 outlined
    materialSymbol("home") {
        weights(400, 500, variant = SymbolVariant.ROUNDED)
        bothFills(weight = 400)
    }
    materialSymbol("person") { allVariants(weight = SymbolWeight.W500) }

    externalIcons("abacus", "ab-testing", libraryName = "mdi") {
        urlTemplate = "https://esm.sh/@mdi/svg@latest/svg/{name}.svg"
    }
    externalIcons("home", "search", libraryName = "official") {
        urlTemplate = "https://esm.sh/@material-symbols/svg-400@latest/rounded/{name}{fill}.svg"
        styleParam("fill") { values("", "-fill") }      // Cartesian product of style values
    }

    localIcons("brand") {
        directory = "icons"                             // relative to the project directory
        include("**/*.svg")
        exclude("legacy/**")
    }
}
```

Generated Compose code:

```kotlin
import com.example.icons.icons.materialsymbols.Icons as MaterialSymbols
import com.example.icons.icons.materialsymbols.icons.HomeW400Rounded

Icon(imageVector = MaterialSymbols.HomeW400Rounded, contentDescription = "Home")
Icon(imageVector = HomeW400Rounded, contentDescription = "Home")
```

## Per-icon platform targeting

Some icons only make sense on one platform — `airplay` is an Apple-only concept, so a Compose `ImageVector` for it would just pollute the Android build. Every builder supports `swiftUIOnly()` and `composeOnly()`:

```kotlin
materialSymbol("airplay") { style(weight = 400); swiftUIOnly() }   // .symbolset only
materialSymbol("home") { style(weight = 400); composeOnly() }      // Kotlin only
```

Works on `materialSymbol`, `externalIcon(s)`, and `localIcons`. The default is both platforms. An icon marked `swiftUIOnly()` while SwiftUI output is disabled generates nothing; `validateSymbolCraftConfig` warns about such icons.

## Gradle tasks

| Task | Description |
|---|---|
| `downloadSymbolCraftSvgs` | Collect every configured SVG once (fails the build naming any icon it cannot fetch) |
| `generateSymbolCraftIcons` | Generate Compose sources (auto-wired before Kotlin compilation) |
| `generateSymbolCraftSymbolSets` | Generate the SF Symbol catalog (auto-wired before Xcode embedding; skipped unless `swiftUI.enabled`) |
| `cleanSymbolCraftIcons` | Delete generated sources, bundles and `Symbols.swift` |
| `cleanSymbolCraftCache` | Delete the SVG cache and workspace |
| `validateSymbolCraftConfig` | Validate the configuration |

```bash
./gradlew generateSymbolCraftIcons generateSymbolCraftSymbolSets --rerun-tasks   # force regeneration
./gradlew generateSymbolCraftIcons --info                                           # verbose logging
```

## Caching

- SVG cache lives in `build/symbolcraft-cache/svg-cache/` (7-day TTL, per-library isolation, metadata with timestamp/URL/hash) and is removed by `./gradlew clean`.
- With a relative `cacheDirectory`, stale cache entries are pruned automatically. With an absolute path (shared cache across projects), automatic cleanup is skipped to avoid cross-project conflicts.
- All three generation tasks are `@CacheableTask` and configuration-cache compatible; unchanged configurations are skipped entirely. Keep the default `build/` output locations for that: Gradle disables caching for a task whose output directory contains files it did not produce.

## Troubleshooting

- **Icon not found** — the download task fails and names the icon; check it in the [Material Symbols browser](https://marella.github.io/material-symbols/demo/).
- **Unresolved reference to a generated icon** — `composeSourceSet` must name a source set the compiling target uses (`./gradlew validateSymbolCraftConfig`, then check the source set name in the error).
- **Xcode: "Build input file cannot be found: …/Symbols.swift"** — add the generated files as Output Files of the Kotlin run-script phase (see Xcode setup).
- **Stale icons or cache weirdness** — `./gradlew cleanSymbolCraftCache`, then rerun with `--rerun-tasks`.
- **Configuration-cache errors** — rerun with `--no-configuration-cache` to confirm, and report an issue.
- **GitHub Packages 401** — `gpr.user`/`gpr.key` missing or the PAT lacks `read:packages`.
- Debug: `--info`, `--debug`, `--stacktrace`.

## Example app

`example/` is a Kotlin Multiplatform app in the native-UI layout: `shared` (Kotlin logic + the icon declaration, embedded by Xcode as the `Shared` framework), `composeApp` (Compose UI for Android and Desktop), and `iosApp` (SwiftUI, listing every generated symbol):

```bash
cd example
./gradlew :shared:generateSymbolCraftIcons :shared:generateSymbolCraftSymbolSets
./gradlew :composeApp:run              # Desktop
./gradlew :composeApp:assembleDebug    # Android
open iosApp/iosApp.xcodeproj           # iOS (macOS)
```

CI builds the iOS app with `xcodebuild` on macOS and validates the generated catalog with `actool` on every push.

## Contributing

```bash
./gradlew build                  # build + tests (TestKit, including a real Kotlin plugin download)
./gradlew ktfmtFormat            # format before committing (CI runs ktfmtCheck)
```

Issues and PRs welcome at [github.com/archivesteak/SymbolCraft](https://github.com/archivesteak/SymbolCraft). API docs (Dokka): `./gradlew dokkaGeneratePublicationHtml` -> `build/dokka/html/index.html`.

## License

Apache 2.0 — see [LICENSE](LICENSE). Fork of [kingsword09/SymbolCraft](https://github.com/kingsword09/SymbolCraft), which credits Google's Material Symbols, marella/material-symbols, and DevSrSouza/svg-to-compose.
