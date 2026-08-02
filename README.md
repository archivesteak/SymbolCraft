# SymbolCraft

![GitHub Release](https://img.shields.io/github/v/release/archivesteak/SymbolCraft)

A Gradle plugin for Kotlin Multiplatform projects that generates icons on demand from multiple icon libraries (Material Symbols, Bootstrap Icons, Heroicons, local SVGs, any URL template) — as Compose `ImageVector` code, and optionally as custom SF Symbol `.symbolset` bundles for SwiftUI.

- On-demand generation: only the icons you declare, instead of bundling Material Icons Extended (11.3 MB)
- Smart caching: 7-day SVG cache with automatic invalidation; relative (project-local) or absolute (shared) cache paths
- Parallel downloads via Kotlin coroutines, with configurable retries and exponential backoff
- Deterministic output: no timestamps, normalized floats — same input, same bytes
- Full Material Symbols style support: weight (100–700), variant (outlined/rounded/sharp), fill state
- Flexible naming: PascalCase, camelCase, snake_case, kebab-case, custom transformers
- Compose Preview generation (configurable annotation class)
- SwiftUI output: custom SF Symbols with real per-weight glyphs mapped to SF weight columns, plus a `Symbols.swift` helper enum
- Gradle task cache and configuration-cache compatible; wires itself ahead of Kotlin compilation
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
symbolCraft = { id = "io.github.archivesteak.symbolcraft", version = "0.6.4" }
```

```kotlin
plugins {
    alias(libs.plugins.symbolCraft)
}
```

## Quick start

```kotlin
symbolCraft {
    packageName.set("com.app.symbols")
    outputDirectory.set("src/commonMain/kotlin")

    materialSymbol("home") {
        bothFills(weight = 400)                  // filled + unfilled
        style(weight = 500, variant = SymbolVariant.ROUNDED)
    }

    materialSymbols("search", "settings") {
        standardWeights()                        // 400, 500, 700
    }

    externalIcons("bell", "calendar", libraryName = "bootstrap-icons") {
        urlTemplate = "https://esm.sh/bootstrap-icons@latest/icons/{name}.svg"
    }

    localIcons(libraryName = "brand") {
        directory = "design/exported"
        include("brand/**/*.svg")
    }
}
```

Generate and use:

```bash
./gradlew generateSymbolCraftIcons
```

```kotlin
import com.app.symbols.icons.materialsymbols.Icons
import com.app.symbols.icons.materialsymbols.icons.HomeW400Outlined

Icon(imageVector = Icons.HomeW400Outlined, contentDescription = "Home")
```

Generated Material Symbols file names follow `{Name}W{Weight}{Variant}{Fill}.kt`, e.g. `SearchW400Outlined.kt`, `HomeW500RoundedFill.kt`.

## Configuration reference

```kotlin
symbolCraft {
    packageName.set("com.app.symbols")           // required
    outputDirectory.set("src/commonMain/kotlin") // required
    cacheEnabled.set(true)                       // default: true
    cacheDirectory.set("symbolcraft-cache")      // default: build/symbolcraft-cache
    generatePreview.set(false)                   // default: false
    previewAnnotationClass.set("androidx.compose.ui.tooling.preview.Preview")
    maxRetries.set(3)                            // default: 3
    retryDelayMs.set(1000)                       // default: 1000 ms

    naming {
        pascalCase()                // default; also camelCase(), snakeCase(), kebabCase(),
                                    // lowerCase(), upperCase(), snakeCase(uppercase = true)
        suffix.set("Icon")          // optional: prefix, suffix, removePrefix, removeSuffix
        customTransformer(object : IconNameTransformer() {   // advanced
            override fun transform(fileName: String) = fileName.uppercase() + "Icon"
        })
    }
}
```

### Material Symbols styles

- `weight`: 100–700 (`SymbolWeight.W100`…`W700`, or plain Int)
- `variant`: `SymbolVariant.OUTLINED` (default), `ROUNDED`, `SHARP`
- `fill`: `SymbolFill.UNFILLED` (default), `FILLED`

Convenience methods inside `materialSymbol("...") { }`:

| Method | Adds |
|---|---|
| `style(weight, variant, fill)` | one style combination |
| `weights(400, 500, ...)` | several weights, one variant/fill |
| `standardWeights()` | 400, 500, 700 |
| `allVariants(weight = 400)` | outlined + rounded + sharp |
| `bothFills(weight = 500)` | unfilled + filled |

Filled Material Symbols generated by the built-in DSL are named `...Fill` (since 0.5.0; previously `...fill1`).

### External sources with variants

`urlTemplate` must be a full `https://` URL; `{name}` and any `{key}` declared via `styleParam` are substituted. Multiple values produce the Cartesian product:

```kotlin
externalIcons("home", "search", libraryName = "heroicons") {
    urlTemplate = "https://cdn.jsdelivr.net/npm/heroicons@latest/24/{style}/{name}.svg"
    styleParam("style") { values("outline", "solid") }
}
```

### Local SVGs

```kotlin
localIcons(libraryName = "brand") {
    directory = "src/commonMain/composeResources/files/icons"
    include("**/*.svg")     // default
    exclude("draft/**")
}
```

## SwiftUI output (custom SF Symbols)

The same SVGs can also become custom SF Symbol `.symbolset` bundles — Dynamic Type, rendering modes (monochrome/hierarchical/palette), text alignment, iOS 13+.

```kotlin
swiftUI {
    enabled.set(true)                                 // default: false
    outputDirectory.set("iosApp/Assets.xcassets/SymbolCraft")
    scaleFactor.set(1.0)                              // default: 1.0
    generateSwiftEnum.set(true)                       // default: true
    // swiftSourceOutputDirectory.set("iosApp/Sources/Generated")  // optional override
}
```

Pointing `outputDirectory` into your `.xcassets` (a **dedicated child folder**, as above) makes the bundles compile automatically via a synchronized group — no drag-into-Xcode step. Xcode treats asset catalogs as leaves, so `Symbols.swift` is written to the catalog's parent directory instead (or wherever `swiftSourceOutputDirectory` says).

Material weights map to real SF weight columns (W400->Regular, W500->Medium, W700->Bold, …): each `(icon, variant, fill)` combination becomes one `.symbolset` with the full 27-variant grid — configured weights use genuine downloaded glyphs, the rest are derived per Apple's relative sizing. External/local icons produce Regular-only sets.

In Swift:

```swift
Image(symbol: .homeOutlined)                    // sizes by font point size
GeneratedSymbol.homeOutlined.image(boxSize: 24) // exact 24x24 pt box
```

`.symbolset` glyphs size by font, not by box. The generated `Symbols.swift` exposes `GeneratedSymbol.pointScale` (= 1 / (1.7 × 0.7 × scaleFactor)) and the `image(boxSize:)` helper to convert an artwork box to the right font size.

## Gradle tasks

| Task | Description |
|---|---|
| `generateSymbolCraftIcons` | Generate all configured icons (auto-wired before Kotlin compilation) |
| `cleanSymbolCraftCache` | Clean cached SVG files |
| `cleanSymbolCraftIcons` | Clean generated icon files |
| `validateSymbolCraftConfig` | Validate the configuration |

```bash
./gradlew generateSymbolCraftIcons --rerun-tasks   # force regeneration
./gradlew generateSymbolCraftIcons --info          # verbose logging
```

## Caching

- SVG cache lives in `build/symbolcraft-cache/svg-cache/` (7-day TTL, per-library isolation, metadata with timestamp/URL/hash) and is removed by `./gradlew clean`.
- With a relative `cacheDirectory`, stale cache entries are pruned automatically. With an absolute path (shared cache across projects), automatic cleanup is skipped to avoid cross-project conflicts.
- The generation task is `@CacheableTask` and configuration-cache compatible; unchanged configurations are skipped entirely.

Recommended `.gitignore` entries (adjust to your package):

```gitignore
**/icons/
**/__Icons.kt
```

## Troubleshooting

- **Icon not found** — check the name in the [Material Symbols browser](https://marella.github.io/material-symbols/demo/).
- **Stale icons or cache weirdness** — `./gradlew cleanSymbolCraftCache` or `./gradlew clean`, then rerun with `--rerun-tasks`.
- **Configuration-cache errors** — rerun with `--no-configuration-cache` to confirm, and report an issue.
- **GitHub Packages 401** — `gpr.user`/`gpr.key` missing or the PAT lacks `read:packages`.
- Debug: `--info`, `--debug`, `--stacktrace`.

## Example app

`example/` is a Compose Multiplatform app (Android, iOS, Desktop) demonstrating Material Symbols, external sources, local SVGs, and SwiftUI output into `iosApp/GeneratedSymbols`:

```bash
cd example
./gradlew generateSymbolCraftIcons
./gradlew :composeApp:run        # Desktop
```

## Contributing

```bash
./gradlew build                  # build + tests
./gradlew publishToMavenLocal    # then test in example/
./gradlew ktfmtFormat            # format before committing (CI runs ktfmtCheck)
```

Issues and PRs welcome at [github.com/archivesteak/SymbolCraft](https://github.com/archivesteak/SymbolCraft). API docs (Dokka): `./gradlew dokkaGeneratePublicationHtml` -> `build/dokka/html/index.html`.

## License

Apache 2.0 — see [LICENSE](LICENSE). Fork of [kingsword09/SymbolCraft](https://github.com/kingsword09/SymbolCraft), which credits Google's Material Symbols, marella/material-symbols, and DevSrSouza/svg-to-compose.
