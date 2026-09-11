# SymbolCraft - Developer Guide

## Project Overview

**SymbolCraft** is a Gradle plugin for Kotlin Multiplatform projects that generates icons on-demand from multiple icon libraries (Material Symbols, Bootstrap Icons, Heroicons, etc.) as Compose `ImageVector` sources and as custom SF Symbol `.symbolset` catalogs for native SwiftUI.

- **Version**: v0.8.1
- **Status**: Published to GitHub Packages (fork of [kingsword09/SymbolCraft](https://github.com/kingsword09/SymbolCraft), not on Maven Central / Plugin Portal)
- **Language**: Kotlin 2.0.0
- **Minimum Gradle version**: 8.0+
- **Repository**: https://github.com/archivesteak/SymbolCraft

### Design in one paragraph

The plugin is applied to **the module both platform builds compile** (the shared Kotlin module in a
native-UI KMP app, the Compose module in a shared-UI app). Three cacheable tasks share one SVG
workspace: `downloadSymbolCraftSvgs` -> `generateSymbolCraftIcons` (Compose) and
`generateSymbolCraftSymbolSets` (SwiftUI). All outputs live in the applying project's `build/`.
Consumption is wired by build mechanics, not by paths: the Compose output joins a Kotlin source set
of the same project (or is shared to another project as a Gradle variant), and the symbol catalog
is a dependency of the Kotlin Gradle plugin's `embedAndSign*AppleFrameworkForXcode` tasks, so the
Gradle run Xcode already makes produces it. The Xcode project references the two generated files
and declares them as outputs of the Kotlin run-script phase. v0.8.1 (the first published build of
this design) replaced the previous design,
in which one task in the Compose UI module wrote into a sibling iOS app folder and was triggered
only by that module's Kotlin compilation — the iOS build could never regenerate its own assets.

### Core Features

- **Multiple icon libraries** - Material Symbols, Bootstrap Icons, Heroicons, custom URL templates, local SVGs
- **SwiftUI output** - Custom SF Symbol `.symbolset` catalog from the same SVGs (real per-weight glyphs mapped to SF weight columns) plus `Symbols.swift`
- **Per-icon platform targeting** - `swiftUIOnly()` / `composeOnly()` per icon declaration
- **Variant-aware sharing** - `io.github.archivesteak.symbolcraft.compose` / `.apple` consumer plugins for multi-module layouts
- **Smart caching** - 7-day SVG cache, relative/absolute cache paths, three `@CacheableTask`s
- **Parallel downloads** - Kotlin coroutines with configurable retry mechanism; missing icons fail the build by name
- **Deterministic builds** - Git-friendly deterministic code generation
- **Flexible naming** - PascalCase, camelCase, snake_case, kebab-case, custom transformers
- **Compose Previews** - Auto-generate @Preview functions

---

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.0 | Core language |
| Gradle | 8.0+ (wrapper 8.10.2) | Build system |
| Kotlin Coroutines | 1.8.1 | Parallel downloads |
| Ktor Client | 2.3.12 | HTTP client |
| svg-to-compose | 0.1.0 | SVG conversion library (io.github.kingsword09 fork of DevSrSouza/svg-to-compose) |

`kotlin-gradle-plugin` is a `compileOnly` dependency but **no plugin class references Kotlin plugin
types**: Kotlin/Android source sets are reached reflectively (see `ComposeSourceWiring`), because
those plugins may sit on a different class loader than SymbolCraft.

---

## Project Structure

```
SymbolCraft/
├── build.gradle.kts                    # Plugin build: three plugin ids, publishing
├── gradle/libs.versions.toml           # Version catalog
│
├── src/main/kotlin/io/github/archivesteak/symbolcraft/
│   ├── SymbolCraftDefaults.kt          # Constants: default output paths, task group, file header
│   │
│   ├── plugin/
│   │   ├── SymbolCraftPlugin.kt        # Producer plugin: extension, tasks, variants, local wiring
│   │   ├── SymbolCraftComposePlugin.kt # Consumer plugin `.compose` (+ symbolCraftCompose { } DSL)
│   │   ├── SymbolCraftApplePlugin.kt   # Consumer plugin `.apple`
│   │   ├── SymbolCraftVariants.kt      # Consumable/resolvable configurations + attributes
│   │   ├── ComposeSourceWiring.kt      # Adds generated sources to a Kotlin/Android source set (reflective)
│   │   ├── XcodeTasks.kt               # Matches embedAndSign*AppleFrameworkForXcode / syncFramework
│   │   ├── SymbolCraftExtension.kt     # symbolCraft { } DSL, config hashes
│   │   ├── SwiftUIConfig.kt            # swiftUI { } DSL
│   │   ├── NamingConfig.kt             # naming { } DSL
│   │   └── samples/LocalIconsSamples.kt
│   │
│   ├── tasks/
│   │   ├── DownloadSymbolSvgsTask.kt   # @CacheableTask: SVG workspace (build/symbolcraft/svgs)
│   │   ├── GenerateComposeIconsTask.kt # @CacheableTask: Compose sources
│   │   ├── GenerateSymbolSetsTask.kt   # @CacheableTask: SymbolCraft.xcassets + Symbols.swift
│   │   ├── CleanSymbolsCacheTask.kt
│   │   ├── CleanSymbolsIconsTask.kt
│   │   ├── ValidateSymbolsConfigTask.kt
│   │   └── internal/                   # Pipeline collaborators (no Gradle task API)
│   │       ├── DownloadCoordinator.kt / DownloadModels.kt
│   │       ├── SvgConversionCoordinator.kt   # ComposeConversionRequest -> Kotlin sources
│   │       ├── SymbolSetGenerationCoordinator.kt # SymbolSetRequest -> catalog (+ resolveSwiftSourceDir)
│   │       ├── GeneratedFileCleaner.kt       # Header-guarded deletion of previous outputs
│   │       ├── IconLibraryClassifier.kt
│   │       └── Naming.kt                     # NamingConfig.toTransformer()
│   │
│   ├── download/SvgDownloader.kt       # Parallel coroutine downloader + TTL cache
│   ├── converter/                      # Svg2ComposeConverter, SymbolSetGenerator, IconNameTransformer
│   ├── model/IconConfig.kt             # IconConfig, IconTarget(s), Material/External/Local configs
│   └── utils/PathUtils.kt
│
├── example/                            # KMP app, native-UI layout (see example/README.md)
│   ├── shared/                         # Icon declaration + logic; Xcode embeds it as `Shared`
│   ├── composeApp/                     # Compose UI, Android + Desktop, depends on :shared
│   └── iosApp/                         # SwiftUI app referencing the generated catalog
│
├── reference/                          # Development reference material (gitignored, not shipped)
├── README.md                           # User documentation
└── AGENTS.md                           # This file (developer guide)
```

---

## Core Components

### 1. **SymbolCraftPlugin** (producer, id `io.github.archivesteak.symbolcraft`)

**Location**: `src/main/kotlin/io/github/archivesteak/symbolcraft/plugin/SymbolCraftPlugin.kt`

- Registers the `symbolCraft` extension and the tasks below.
- Resolves output locations lazily (providers, no `afterEvaluate`): unset DSL values fall back to
  `build/…`, relative values resolve against the project directory.
- `SymbolCraftVariants.registerProducer` exposes two consumable configurations:
  `symbolCraftComposeSourcesElements` (artifact = Compose output dir) and
  `symbolCraftAppleSymbolsElements` (artifact = catalog dir). Attributes: `Category = symbolcraft`
  plus `io.github.archivesteak.symbolcraft.output = compose-sources | apple-symbols`. The `Category`
  attribute keeps `apiElements`/`runtimeElements` of a Kotlin producer out of the match.
- Same-project wiring: `ComposeSourceWiring.wire(...)` adds the Compose output provider to
  `composeSourceSet` after evaluation; `XcodeTasks.dependOnForEmbed` makes every Xcode embed task
  depend on the symbol-set task.

**Tasks**

| Task | Type | Inputs | Outputs |
|---|---|---|---|
| `downloadSymbolCraftSvgs` | `DownloadSymbolSvgsTask` | `getDownloadHash()`, local SVG contents | `build/symbolcraft/svgs/<libraryId>/` |
| `generateSymbolCraftIcons` | `GenerateComposeIconsTask` | `getComposeHash()`, SVG workspace | `build/generated/symbolcraft/compose/` (or `outputDirectory`) |
| `generateSymbolCraftSymbolSets` | `GenerateSymbolSetsTask` | `getSwiftUIHash()`, SVG workspace | `…/swiftui/SymbolCraft.xcassets/` (`@OutputDirectory`) + `Symbols.swift` (`@Optional @OutputFile`); `onlyIf(swiftUI.enabled)` |
| `cleanSymbolCraftIcons` / `cleanSymbolCraftCache` / `validateSymbolCraftConfig` | | | |

Rules worth keeping:
- **Never declare a shared user folder as `@OutputDirectory`.** `Symbols.swift` is an
  `@OutputFile`; the catalog parent (an app source folder in committed mode) is not an output.
  Gradle disables caching for a task whose output directory contains foreign files, and a
  build-cache restore cleans output directories.
- The generators never write into the download task's workspace; Compose filtering stages into the
  task's `temporaryDir`.
- Every generated source starts with `SymbolCraftDefaults.GENERATED_FILE_HEADER`; cleaners refuse
  to delete files without it.

### 2. **Consumer plugins**

- `SymbolCraftComposePlugin` (`io.github.archivesteak.symbolcraft.compose`): creates the
  `symbolCraft` dependency bucket and the resolvable `symbolCraftComposeSources`, then wires it as
  a source directory (`symbolCraftCompose { sourceSet }`, default `commonMain`/`main`). A resolved
  configuration is a file collection carrying the producer task as a dependency, so Kotlin
  compilation waits for generation without any cross-project task reference (Isolated-Projects
  safe).
- `SymbolCraftApplePlugin` (`io.github.archivesteak.symbolcraft.apple`): resolvable
  `symbolCraftAppleSymbols`; every Xcode embed task of the project depends on it.

### 3. **ComposeSourceWiring** (reflective source-set access)

Runs after project evaluation (the DSL must be complete to know the source set name). Looks for the
`kotlin` extension first (KGP, KMP, AGP's KMP library plugin), then `android` (AGP built-in
Kotlin), calls `getSourceSets().findByName(name).getKotlin()` reflectively and `srcDir(sources)`.
Default source set: `commonMain` when `org.jetbrains.kotlin.multiplatform` or
`com.android.kotlin.multiplatform.library` is applied, else `main`. Fails with the list of existing
source sets when the configured name does not exist.

### 4. **XcodeTasks**

Matches `embedAndSign*AppleFrameworkForXcode`, `syncFramework` (CocoaPods) and
`embedSwiftExportForXcode` by name with `tasks.configureEach`, the same approach Compose
Multiplatform resources use. Covers both entry points: Xcode's run-script phase and IDE-driven
builds (`OVERRIDE_KOTLIN_BUILD_IDE_SUPPORTED=YES`), which run the embed task through Gradle.

### 5. **SymbolCraftExtension** (DSL)

- `outputDirectory` (unset = `build/generated/symbolcraft/compose`), `composeSourceSet` (unset =
  derived), `packageName`, `generatePreview`, `previewAnnotationClass`, cache and retry settings.
- `naming { }`, `swiftUI { }` (`enabled` default false, `outputDirectory` unset = build catalog,
  `scaleFactor`, `generateSwiftEnum`, `swiftSourceOutputDirectory`).
- Icon builders: `materialSymbol(s)`, `externalIcon(s)`, `localIcons`, `iconConfig(s)`; all
  support `swiftUIOnly()` / `composeOnly()`.
- Hashes: `getDownloadHash()` (icons + targets), `getComposeHash()`, `getSwiftUIHash()`; each task
  declares only the hash it depends on.

### 6. **Pipeline collaborators** (`tasks/internal`)

- `DownloadCoordinator`: parallel remote fetch / local copy into `<workspace>/<libraryId>/`;
  returns `DownloadStats`; the task throws when any result is `Failed`.
- `SvgConversionCoordinator.convert(ComposeConversionRequest)`: per-library conversion, COMPOSE
  target filtering (staging dir), `Svg2ComposeConverter`.
- `SymbolSetGenerationCoordinator.generate(SymbolSetRequest)`: writes the catalog root
  `Contents.json`, per-library `.symbolset` bundles (SWIFTUI targets), `Symbols.swift`.
  `resolveSwiftSourceDir(configured, outputDir, projectDir)` keeps the Swift file out of
  `.xcassets` trees.
- `GeneratedFileCleaner`: header-guarded deletion of Kotlin/Swift sources, `.symbolset` bundles,
  unused cache entries.

### 7. **SwiftUI output format**

**Format essentials** (Apple template v2.0 structure, cross-validated against a real export):
- Each `.symbolset` folder contains `Name.svg` (`Notes`/`Guides`/`Symbols` groups on a 3300×2200 canvas) and `Contents.json`; the catalog root carries Xcode's `Contents.json`
- `#artboard` and `#template-version` inside `#Notes` must be preserved, otherwise Xcode ignores the margins
- Reference implementations: `EvanBacon/create-symbol` (template v2.0, 27 variants, guide constants, 1.7× optical scaling, 4.5 margin padding); Cookpad's converter script
- Reference material is cloned into `reference/` (gitignored)

**Weight mapping**: Material weight -> SF weight column: W100->Ultralight … W700->Bold. Symbol sets are grouped by (iconName, variant, fill); every symbol set always contains the full 27-variant grid — configured weights use genuine glyph outlines, the rest are derived from the nearest weight via `WEIGHT_SCALES` relative sizing, and S/L scales follow the cap-height ratio. External/local icons produce Regular-only symbol sets.

**Output**:
- Geometry: baseScale = (CapHeightM / viewBox height) × 1.7 × scaleFactor; glyphs vertically centered between Capline-M and Baseline-M, laid out horizontally by weight column (center 1650, spacing 296.71); `left-margin`/`right-margin` adjusted to Regular column width ±4.5
- `Symbols.swift`: `GeneratedSymbol` enum + `Image(symbol:)` convenience initializer (Swift keyword escaping, leading-digit handling) + `image(boxSize:)` and `GeneratedSymbol.pointScale` = 1 / (1.7 × 0.7 × scaleFactor)

**Validation**: only `<path>`-based SVGs are supported (Material/Bootstrap/Heroicons all qualify).
Structural correctness is guarded by unit tests; the CI `ios-build` job (macOS) compiles the
example catalog with `xcrun actool` and builds the SwiftUI example app with `xcodebuild`, which is
the end-to-end proof.

---

## Xcode integration contract

For an app consuming the default (build-directory) output, the iOS project needs, once:
1. File references (no copy) to `<producer>/build/generated/symbolcraft/swiftui/SymbolCraft.xcassets`
   and `Symbols.swift`, in the Resources and Sources phases respectively.
2. Both paths listed as **Output Files** of the "Compile Kotlin Framework" run-script phase, so
   Xcode knows the script produces them ("Build input file cannot be found" otherwise).
3. The stock script from the Kotlin direct-integration docs, running
   `./gradlew :<producer or apple consumer>:embedAndSignAppleFrameworkForXcode`, placed before
   Compile Sources.

`example/iosApp/iosApp.xcodeproj/project.pbxproj` is the reference (`SymbolCraft (generated)`
group, `A1000000000000000000*` object ids).

---

## Development Workflow

1. **Modify plugin code** under `src/main/kotlin/`.
2. **Run the tests**: `./gradlew test` (TestKit builds; the Kotlin-wiring test downloads
   `org.jetbrains.kotlin.jvm` into `build/testkit`, the pinned TestKit Gradle home).
3. **Try the example** (the example uses `includeBuild("..")`, no publish needed):
   ```bash
   cd example
   ./gradlew :shared:generateSymbolCraftIcons :shared:generateSymbolCraftSymbolSets
   ./gradlew :composeApp:run              # Desktop
   ./gradlew :composeApp:assembleDebug    # Android
   ```
4. **iOS** (macOS only): `open example/iosApp/iosApp.xcodeproj` or the `xcodebuild` command from
   `.github/workflows/ci.yml`.
5. **Format**: `./gradlew ktfmtFormat` (CI runs `ktfmtCheck` first). Note: a KDoc containing
   `/*` (for example a glob like `dir/*.svg` in backticks) opens a nested comment and breaks ktfmt.

---

## Build and Release Process

### 1. Local build
```bash
./gradlew build                    # Build the plugin + tests
./gradlew publishToMavenLocal      # Publish to local Maven
```

### 2. Release

Bump `version` in `build.gradle.kts` (and `symbolcraft` in `example/gradle/libs.versions.toml`,
the docs' version mentions), then commit on `main` with a message starting with
`chore(release): vX.Y.Z`. CI (`.github/workflows/ci.yml`) verifies the version, runs `build`,
`example-test`, `validate-plugin` and `ios-build`, creates the GitHub Release and publishes to
GitHub Packages (`publish-github-packages`, built-in `GITHUB_TOKEN`).

### 3. Publish to the Gradle Plugin Portal / Maven Central

Both jobs are **dormant on this fork** (no secrets); gate variables `ENABLE_GRADLE_PORTAL` /
`ENABLE_MAVEN_CENTRAL`. Manual GitHub Packages publish:

```bash
./gradlew publishAllPublicationsToGitHubPackagesRepository \
  -Pgpr.user=GITHUB_USERNAME -Pgpr.key=GITHUB_PAT
```

Consumers need a token with `read:packages` even to read public packages.

**Never reuse a version number.** GitHub Packages refuses re-uploads, and the publish job skips
versions the served `maven-metadata.xml` already lists. `0.8.0` is such a burned number: a build
was uploaded on 2026-08-13, long before the v0.8.0 redesign commit, so the redesign shipped as
v0.8.1. The `validate-plugin` job prints the versions GitHub Packages actually serves for the
plugin and its three marker artifacts on every run.

---

## Cache Mechanism in Detail

1. **SVG download cache** (`build/symbolcraft-cache/svg-cache/`): 7-day TTL, SVG + JSON metadata
   (`timestamp`, `url`, `hash`), per-library isolation. Declared `@Internal` on the download task
   (it influences neither the cache key nor the outputs).
2. **SVG workspace** (`build/symbolcraft/svgs/`): the download task's output; both generators take
   it as a `@PathSensitive(RELATIVE) @InputDirectory`.
3. **Gradle task cache**: each task declares only the configuration hash it depends on; outputs are
   exclusive directories/files under `build/`.
4. **Configuration cache**: Provider API throughout; tasks hold the extension as an `@Internal`
   property and never touch `Project` at execution time.

Relative `cacheDirectory` values resolve under `build/` and are pruned automatically; absolute
values are shared caches and never pruned.

---

## Testing Status

- `SymbolCraftPluginTest` (TestKit, 15 cases): local/remote/external icons, previews, up-to-date
  and local-SVG invalidation, download failure by name, platform targets, default `build/` layout
  with `.xcassets` catalog and `Symbols.swift` placement, same-project embed-task ordering,
  SwiftUI disabled -> task skipped, **multi-project** compose consumer (variant resolution) and
  apple consumer (embed dependency), **Kotlin JVM wiring** against a real `org.jetbrains.kotlin.jvm`
  2.0.0 (source dir added, `compileKotlin` ordered after generation).
- `SymbolSetGeneratorTest` - `.symbolset` generation (template structure, geometry, weight
  mapping, determinism, Swift enum emission)
- `SwiftUISourceDirResolutionTest` - `Symbols.swift` placement rules
- `IconTargetsDslTest`, `LocalIconsBuilderTest`, `MaterialSymbolsConfigTest`,
  `IconNameTransformerTest`

TestKit builds use `build/testkit` as Gradle user home (`GradleRunner.withTestKitDir`) so plugin
downloads persist between runs and stay on the project's drive.

---

## Common Development Tasks

### Adding a configuration option
1. Add a `Property<T>` in `SymbolCraftExtension.kt` (or `SwiftUIConfig.kt`).
2. Include it in the relevant hash (`getComposeHash()` / `getSwiftUIHash()`), never in all three.
3. Read it in the task that needs it; pass it through the request data class.
4. Update README.md, AGENTS.md and the example.

### Adding a new icon library
1. Implement `IconConfig` in `model/IconConfig.kt` (`buildUrl`, `getCacheKey`, `getSignature`, `targets`).
2. Add the DSL method in `SymbolCraftExtension.kt`.
3. Update documentation and examples.

### Changing what is generated
- Compose: `converter/Svg2ComposeConverter.kt`; SwiftUI: `converter/SymbolSetGenerator.kt`.
- Anything that changes bytes must keep the output deterministic.

---

## Debugging Tips

```bash
./gradlew generateSymbolCraftIcons --info
./gradlew generateSymbolCraftSymbolSets --debug --stacktrace
./gradlew generateSymbolCraftIcons --no-configuration-cache
./gradlew generateSymbolCraftIcons generateSymbolCraftSymbolSets --rerun-tasks
./gradlew :composeApp:compileKotlinJvm --dry-run       # confirms generation precedes compilation
./gradlew :shared:outgoingVariants                      # shows the two SymbolCraft variants
```

---

## Git Workflow

- `main` - stable release branch; feature branches `feature/*`, fixes `fix/*`
- Commit convention: `<type>(<scope>): <subject>` (`feat`, `fix`, `docs`, `refactor`, `test`, `chore`); breaking changes use `!`.

---

## Contributor Guide

1. Fork, clone, `git remote add upstream https://github.com/archivesteak/SymbolCraft.git`
2. Branch, change, `./gradlew ktfmtFormat build`
3. Try the example on at least one platform; describe iOS verification in the PR if you have a Mac
4. Open a PR against `main`
