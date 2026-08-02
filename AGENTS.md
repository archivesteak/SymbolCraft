# SymbolCraft - Developer Guide

## Project Overview

**SymbolCraft** is a Gradle plugin for Kotlin Multiplatform projects that generates icons on-demand from multiple icon libraries (Material Symbols, Bootstrap Icons, Heroicons, etc.).

- **Version**: v0.6.1
- **Status**: ✅ Published to GitHub Packages (fork of [kingsword09/SymbolCraft](https://github.com/kingsword09/SymbolCraft), not on Maven Central / Plugin Portal)
- **Language**: Kotlin 2.0.0
- **Minimum Gradle version**: 8.0+
- **Repository**: https://github.com/archivesteak/SymbolCraft

### Core Features

- 🚀 **Multiple icon libraries** - Material Symbols, Bootstrap Icons, Heroicons, custom URL templates
- 🍏 **SwiftUI output** - Generate custom SF Symbol `.symbolset` bundles from the same SVGs (real per-weight glyphs mapped to SF weight columns)
- 💾 **Smart caching** - 7-day SVG cache, supports relative/absolute paths
- ⚡ **Parallel downloads** - Kotlin coroutines with configurable retry mechanism
- 🎯 **Deterministic builds** - Git-friendly deterministic code generation
- 🏷️ **Flexible naming** - Multiple naming conventions (PascalCase, camelCase, snake_case, etc.)
- 👀 **Compose Previews** - Auto-generate @Preview functions

---

## Tech Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Kotlin | 2.0.0 | Core language |
| Gradle | 8.0+ | Build system |
| Kotlin Coroutines | 1.8.1 | Parallel downloads |
| Ktor Client | 2.3.12 | HTTP client |
| Kotlinx Serialization | - | JSON serialization |
| svg-to-compose | 0.1.0 | SVG conversion library (io.github.kingsword09 fork of DevSrSouza/svg-to-compose) |

---

## Project Structure

```
SymbolCraft/
├── build.gradle.kts                    # Plugin build configuration
├── gradle.properties                   # Gradle configuration
├── settings.gradle.kts                 # Gradle settings
├── libs.versions.toml                  # Version catalog
│
├── src/main/kotlin/io/github/archivesteak/symbolcraft/
│   ├── plugin/                         # Gradle plugin core
│   │   ├── SymbolCraftPlugin.kt        # Plugin entry point, task registration
│   │   ├── SymbolCraftExtension.kt     # DSL configuration interface
│   │   ├── SwiftUIConfig.kt            # SwiftUI (.symbolset) output configuration
│   │   └── NamingConfig.kt             # Naming configuration
│   │
│   ├── tasks/                          # Gradle tasks
│   │   ├── GenerateSymbolsTask.kt      # Core generation task (@CacheableTask)
│   │   ├── CleanSymbolsCacheTask.kt    # Cache cleanup task
│   │   ├── CleanSymbolsIconsTask.kt    # Generated-files cleanup task
│   │   └── ValidateSymbolsConfigTask.kt # Configuration validation task
│   │
│   ├── download/                       # Download module
│   │   └── SvgDownloader.kt            # Smart SVG downloader (parallel coroutines + retry)
│   │
│   ├── converter/                      # Conversion module
│   │   ├── Svg2ComposeConverter.kt     # SVG to Compose converter
│   │   ├── SymbolSetGenerator.kt       # SVG to .symbolset (custom SF Symbols) generator
│   │   └── IconNameTransformer.kt      # Icon naming transformer
│   │
│   ├── model/                          # Data models
│   │   └── IconConfig.kt               # Icon configuration interface and implementations
│   │
│   └── utils/                          # Utilities
│       └── PathUtils.kt                # Path utilities
│
├── example/                            # Example project (Compose Multiplatform)
│   ├── composeApp/                     # Main application
│   │   ├── src/
│   │   │   ├── androidMain/           # Android platform code
│   │   │   ├── iosMain/               # iOS platform code
│   │   │   ├── jvmMain/               # Desktop platform code
│   │   │   └── commonMain/            # Common code
│   │   │       ├── generated/symbols/ # Generated icons source root
│   │   │       ├── kotlin/
│   │   │       └── composeResources/
│   │   └── build.gradle.kts            # Uses the SymbolCraft plugin
│   └── iosApp/                         # iOS app
│
├── reference/                          # Development reference material (gitignored, not shipped)
├── README.md                           # User documentation
└── AGENTS.md                           # This file (developer guide)
```

---

## Core Components

### 1. **SymbolCraftPlugin** (plugin entry point)
**Location**: `src/main/kotlin/io/github/archivesteak/symbolcraft/plugin/SymbolCraftPlugin.kt`

**Responsibilities**:
- Registers the `symbolCraft` DSL extension
- Registers Gradle tasks:
  - `generateSymbolCraftIcons` - Generate all configured icons
  - `cleanSymbolCraftCache` - Clean the SVG cache
  - `cleanSymbolCraftIcons` - Clean generated icon files
  - `validateSymbolCraftConfig` - Validate configuration
- Automatically adds task dependencies: icons are generated before Kotlin compilation.
  Wiring is **type-based**: once any Kotlin-capable plugin is applied (`org.jetbrains.kotlin.jvm` /
  `multiplatform` / `android`, `com.android.application` / `library` /
  `com.android.kotlin.multiplatform.library`), every `KotlinCompileTool` task depends on the
  generation task. This covers AGP built-in Kotlin / KMP compile tasks like `compileAndroidMain`
  whose names contain no "Kotlin" — name matching alone missed them. The task type is loaded
  reflectively (`Class.forName`) because kotlin-gradle-plugin is `compileOnly` and a static
  reference breaks plugin application/class decoration in non-Kotlin projects. A name-based
  fallback (`compile*Kotlin*`, metadata, `merge*Assets`, `process*Resources`) remains as backup.

**Key code**:
```kotlin
class SymbolCraftPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("symbolCraft", SymbolCraftExtension::class.java)

        val generateTask = project.tasks.register("generateSymbolCraftIcons", GenerateSymbolsTask::class.java) {
            // Configure task...
        }

        // Type-based wiring: every Kotlin compile task depends on generation
        kotlinCapablePluginIds.forEach { id ->
            project.plugins.withId(id) { plugin ->
                val type = Class.forName(
                    "org.jetbrains.kotlin.gradle.tasks.KotlinCompileTool",
                    true, plugin.javaClass.classLoader,
                )
                project.tasks.withType(type).configureEach { it.dependsOn(generateTask) }
            }
        }
    }
}
```

---

### 2. **SymbolCraftExtension** (DSL configuration)
**Location**: `src/main/kotlin/.../plugin/SymbolCraftExtension.kt`

**Responsibilities**:
- Provides a user-friendly DSL API
- Manages configuration for multiple icon libraries (Material Symbols, external libraries, local SVGs)
- Convenience configuration methods:
  - `materialSymbol()` / `materialSymbols()` - Configure Material Symbols icons
  - `externalIcon()` / `externalIcons()` - Configure external library icons
  - `localIcons()` - Configure checked-in local SVG files
  - `swiftUI {}` - Configure SwiftUI `.symbolset` output (see component 9)
  - `naming {}` - Configure naming rules

**Configuration options**:
```kotlin
abstract class SymbolCraftExtension {
    abstract val packageName: Property<String>              // Package name
    abstract val outputDirectory: Property<String>          // Output directory
    abstract val cacheEnabled: Property<Boolean>            // Cache toggle
    abstract val cacheDirectory: Property<String>           // Cache directory
    abstract val generatePreview: Property<Boolean>         // Generate previews
    abstract val maxRetries: Property<Int>                  // Max retry attempts
    abstract val retryDelayMs: Property<Long>               // Retry delay

    val namingConfig: NamingConfig                          // Naming configuration
    val swiftUIConfig: SwiftUIConfig                        // SwiftUI output configuration

    // Builder classes
    // MaterialSymbolsBuilder - Material Symbols configuration
    // ExternalIconBuilder - External icon configuration
    // LocalIconsBuilder - Local SVG configuration
}
```

---

### 3. **GenerateSymbolsTask** (core generation task)
**Location**: `src/main/kotlin/.../tasks/GenerateSymbolsTask.kt`

**Responsibilities**:
- Parses user configuration (Material Symbols + external libraries + local icons)
- Downloads SVG files in parallel (Kotlin coroutines)
- Applies naming transformation rules
- Invokes the converter to generate Compose ImageVector code
- Optionally invokes the `.symbolset` generator when SwiftUI output is enabled
- Manages caching and incremental builds
- Cleans unused cache files (relative-path caches)

**Features**:
- `@CacheableTask` - Supports Gradle task caching
- Configuration-cache compatible - Uses the Provider API, avoids accessing Project at execution time
- Smart cache cleanup - Enabled for relative paths, skipped for absolute paths
- Configurable retry - maxRetries and retryDelayMs

**Key flow**:
```
Parse config → Clean old files → Parallel SVG download → Naming transform →
Convert to Compose → Generate .symbolset (optional) → Clean unused cache → Statistics
```

---

### 4. **SvgDownloader** (smart downloader)
**Location**: `src/main/kotlin/.../download/SvgDownloader.kt`

**Responsibilities**:
- Downloads SVG files from multiple sources (Material Symbols, external URLs)
- Manages a 7-day TTL cache
- Supports parallel downloads (Kotlin coroutines)
- Cache metadata management (timestamp, URL, hash)
- Configurable retry mechanism

**Features**:
- Cache hit detection
- Automatic expiry cleanup
- Progress tracking
- Configurable error retry (exponential backoff)

---

### 5. **Svg2ComposeConverter** (SVG converter)
**Location**: `src/main/kotlin/.../converter/Svg2ComposeConverter.kt`

**Responsibilities**:
- Converts SVG to Compose ImageVector using the `svg-to-compose` library
- Generates deterministic code (removes timestamps, normalizes floats)
- Optionally generates Compose Preview functions
- Generates the `__Icons.kt` accessor object

**Output files**:
```
{packageName}/icons/materialsymbols/
├── SearchW400Outlined.kt       # Single icon
├── HomeW500RoundedFill.kt
└── ...
```

---

### 6. **IconConfig** (icon configuration interface)
**Location**: `src/main/kotlin/.../model/IconConfig.kt`

**Responsibilities**:
- Defines the common interface for icon library configurations
- Supports multi-library extension

**Main implementations**:
- `MaterialSymbolsConfig` - Material Symbols configuration
  - Contains: SymbolWeight, SymbolVariant, SymbolFill enums
  - Uses the official Google Fonts CDN
- `ExternalIconConfig` - External icon configuration
  - Supports URL templates + style parameters
  - Supports multi-value parameters (Cartesian product)
- `LocalIconConfig` - Checked-in local SVG files

**Interface methods**:
```kotlin
interface IconConfig {
    val libraryId: String
    fun buildUrl(iconName: String): String
    fun getCacheKey(iconName: String): String
    fun getSignature(): String
}
```

---

### 7. **NamingConfig** (naming configuration)
**Location**: `src/main/kotlin/.../plugin/NamingConfig.kt`

**Responsibilities**:
- Provides icon class-name transformation configuration
- Supports presets and custom transformers

**Preset naming rules**:
- `pascalCase()` - PascalCase (default)
- `camelCase()` - camelCase
- `snakeCase()` - snake_case / SCREAMING_SNAKE
- `kebabCase()` - kebab-case
- `lowerCase()` / `upperCase()` - all lower/upper case
- `customTransformer()` - Custom logic

**Configuration options**:
```kotlin
abstract class NamingConfig {
    abstract val namingConvention: Property<NamingConvention>
    abstract val suffix: Property<String>
    abstract val prefix: Property<String>
    abstract val removePrefix: Property<String>
    abstract val removeSuffix: Property<String>
    abstract val transformer: Property<IconNameTransformer>
}
```

---

### 8. **IconNameTransformer** (naming transformer)
**Location**: `src/main/kotlin/.../converter/IconNameTransformer.kt`

**Responsibilities**:
- Executes the concrete naming transformation logic
- Supports multiple naming conventions
- Provides extension points for user customization

**Core methods**:
```kotlin
abstract class IconNameTransformer {
    abstract fun transform(fileName: String): String
    open fun getSignature(): String  // Used for cache signatures
}
```

---

### 9. **SwiftUI output (.symbolset / custom SF Symbols)**

**Components**:
- `plugin/SwiftUIConfig.kt` - `swiftUI { }` DSL configuration (enabled, outputDirectory, scaleFactor, generateSwiftEnum; disabled by default)
- `converter/SymbolSetGenerator.kt` - Pure Kotlin generator (no Gradle types, unit-testable)
- `tasks/internal/SymbolSetGenerationCoordinator.kt` - Pipeline collaborator; reuses the download phase's temp SVGs, so no extra downloads are triggered

**Format essentials** (Apple template v2.0 structure, cross-validated against a real export):
- Each `.symbolset` folder contains `Name.svg` (`Notes`/`Guides`/`Symbols` groups on a 3300×2200 canvas) and `Contents.json`
- `#artboard` and `#template-version` inside `#Notes` must be preserved, otherwise Xcode ignores the margins
- Reference implementations: `EvanBacon/create-symbol` (template v2.0, 27 variants, guide constants, 1.7× optical scaling, 4.5 margin padding); Cookpad's converter script
- Reference material is cloned into `reference/` (gitignored): swiftdraw, create-symbol, rime (a real Template v.5.0 export), upstream (the upstream repository)

**Weight mapping**: Material weight → SF weight column: W100→Ultralight … W700→Bold. Symbol sets are grouped by (iconName, variant, fill); every symbol set always contains the full 27-variant grid — configured weights use genuine glyph outlines, the rest are derived from the nearest weight via `WEIGHT_SCALES` relative sizing, and S/L scales follow the cap-height ratio. External/local icons produce Regular-only symbol sets.

**Output**:
- Geometry: baseScale = (CapHeightM / viewBox height) × 1.7 × scaleFactor; glyphs vertically centered between Capline-M and Baseline-M, laid out horizontally by weight column (center 1650, spacing 296.71); `left-margin`/`right-margin` adjusted to Regular column width ±4.5
- `Symbols.swift`: `GeneratedSymbol` enum + `Image(symbol:)` convenience initializer (Swift keyword escaping, leading-digit handling) + **fixed-size helper** `image(boxSize:)` and `GeneratedSymbol.pointScale` — `.symbolset` glyphs size by font point size, not by box; pointScale = 1 / (1.7 × 0.7 × scaleFactor) (SF Pro cap ratio 0.7, optical scaling 1.7, configured scaleFactor baked in at generation time) converts an artwork box size to the required font size

**Limitations**: Only `<path>`-based SVGs are supported (Material/Bootstrap/Heroicons all qualify); Xcode import cannot be validated on Windows, so structural correctness is guarded by unit tests.

---

## Development Workflow

### Local development and testing

1. **Modify plugin code**
   ```bash
   # Edit source files under src/main/kotlin/
   vim src/main/kotlin/io/github/archivesteak/symbolcraft/tasks/GenerateSymbolsTask.kt
   ```

2. **Publish to local Maven**
   ```bash
   ./gradlew publishToMavenLocal
   ```

3. **Test in the example project**
   ```bash
   cd example
   ./gradlew generateSymbolCraftIcons --info
   ./gradlew :composeApp:run  # Desktop
   ```

4. **Clean and rebuild**
   ```bash
   ./gradlew clean build
   ```

---

## Build and Release Process

### 1. Local build
```bash
./gradlew build                    # Build the plugin
./gradlew test                     # Run tests
./gradlew publishToMavenLocal      # Publish to local Maven
```

### 2. Publish to the Gradle Plugin Portal
```bash
./gradlew publishPlugins           # Requires API key configuration
```

### 2.5 Publish to GitHub Packages (no Sonatype namespace verification required)
```bash
# Local publish (requires a PAT with read:packages / write:packages)
./gradlew publishAllPublicationsToGitHubPackagesRepository \
  -Pgpr.user=GITHUB_USERNAME \
  -Pgpr.key=GITHUB_PAT \
  -Pgpr.repository=owner/repo   # Optional; defaults to the GITHUB_REPOSITORY env var or archivesteak/SymbolCraft
```

- In CI, the `publish-github-packages` job in `.github/workflows/ci.yml` publishes automatically (uses the built-in `GITHUB_TOKEN` with `packages: write` permission).
- ⚠️ Consumers need a token with `read:packages` even to read public packages (a GitHub Packages limitation).

### 3. Publish to Maven Central
```bash
./gradlew publishToMavenCentral    # Requires signing configuration
```

**Configuration requirements**:
- `gradle.properties` or environment variables:
  - `SIGNING_KEY` - GPG signing key
  - `SIGNING_PASSWORD` - Signing password
  - `mavenCentralUsername` - Maven Central username
  - `mavenCentralPassword` - Maven Central password
  - `gpr.user` / `gpr.key` - GitHub Packages credentials (optional)

---

## Cache Mechanism in Detail

### Cache architecture

1. **SVG download cache** (`build/symbolcraft-cache/svg-cache/`)
   - TTL: 7 days
   - Contains: SVG files + JSON metadata
   - Metadata fields: `timestamp`, `url`, `hash`
   - Per-library cache isolation (via libraryId)

2. **Gradle task cache**
   - Change detection based on the configuration hash
   - `@CacheableTask` annotation support

3. **Configuration cache**
   - Uses the Provider API
   - Avoids accessing Project at execution time

### Cache path support

**Relative paths (default)**:
```kotlin
cacheDirectory.set("symbolcraft-cache")  // → build/symbolcraft-cache/
```
- ✅ Automatically cleans unused cache
- ✅ Project isolation
- ✅ `./gradlew clean` removes it automatically

**Absolute paths (shared cache)**:
```kotlin
// Unix/Linux/macOS
cacheDirectory.set("/var/tmp/symbolcraft")  // → /var/tmp/symbolcraft/
// Windows
cacheDirectory.set("""C:\Temp\SymbolCraft""")
```
- ✅ Shared across projects
- ⚠️ Automatic cleanup skipped (to avoid conflicts)

---

## Testing Status

### Current state
- ✅ `IconNameTransformerTest` - Naming transformation
- ✅ `MaterialSymbolsConfigTest` - Material Symbols configuration model
- ✅ `LocalIconsBuilderTest` - Local SVG discovery (⚠️ currently failing on Windows due to glob handling - pre-existing upstream issue)
- ✅ `GenerateSymbolsTaskTest` - TestKit integration tests (⚠️ same pre-existing local-icons issue)
- ✅ `SymbolSetGeneratorTest` - `.symbolset` generation (20 cases: template structure, guide constants, geometry centering, weight mapping, determinism, name sanitization)

---

## TODOs and Improvement Directions

### 🔴 High priority

1. **Improve error handling**
   - ✅ Done: Configurable retry mechanism (maxRetries, retryDelayMs)
   - [ ] More detailed, categorized error messages
   - [ ] Upfront configuration validation (avoid runtime errors)

2. **Performance monitoring**
   - [ ] Generation time statistics
   - [ ] Download speed statistics
   - [ ] Cache hit-rate reports

### 🟡 Medium priority

3. **Feature enhancements**
   - ✅ Done: Multi-library support (Material Symbols + external libraries)
   - ✅ Done: Flexible naming configuration (NamingConfig)
   - ✅ Done: SwiftUI output (custom SF Symbols `.symbolset`)
   - [ ] Icon search (CLI)
   - [ ] Icon usage analysis reports

4. **Developer experience**
   - ✅ Done: Dokka V2 documentation configuration
   - [ ] More KDoc comments
   - [ ] Video tutorials / GIF demos
   - [ ] Project templates

5. **Example extensions**
   - ✅ Done: Compose Multiplatform example (Android + iOS + Desktop)
   - ✅ Done: Example `.symbolset` generation into `example/iosApp/GeneratedSymbols`
   - [ ] Pure Android example
   - [ ] Best-practices guide

### 🟢 Low priority

6. **Ecosystem tools**
   - [ ] IntelliJ IDEA plugin (visual configuration)
   - [ ] Gradle configuration wizard
   - [ ] Icon browser GUI

---

## Dependency Management

### Core dependencies

```kotlin
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-cio:2.3.12")
    implementation("io.github.kingsword09:svg-to-compose:0.1.0")

    compileOnly("org.gradle:gradle-api")
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin")
}
```

### Version update strategy

- Check for dependency updates regularly: `./gradlew dependencyUpdates`
- Test compatibility of new versions
- Keep Kotlin and Gradle versions in sync

---

## Common Development Tasks

### Adding a new Gradle task

1. Register the task in `SymbolCraftPlugin.kt`
2. Create a task class extending `DefaultTask` in `tasks/`
3. Mark the execution method with `@TaskAction`
4. Configure task inputs/outputs for incremental build support

### Adding a new configuration option

1. Add a `Property<T>` in `SymbolCraftExtension.kt`
2. Read the configuration in `GenerateSymbolsTask.kt`
3. Update the configuration hash (`getConfigHash()`)
4. Update all documentation (README.md, AGENTS.md)

### Adding support for a new icon library

1. Create a new `IconConfig` implementation in `model/IconConfig.kt`
2. Implement the required methods: `buildUrl()`, `getCacheKey()`, `getSignature()`
3. Add the corresponding DSL method in `SymbolCraftExtension.kt`
4. Update documentation and examples

### Modifying SVG download logic

Edit `src/main/kotlin/.../download/SvgDownloader.kt`:
- Change CDN URLs
- Adjust cache strategy
- Enhance error handling

### Modifying code generation

Edit `src/main/kotlin/.../converter/Svg2ComposeConverter.kt`:
- Adjust output format
- Modify preview generation
- Customize file naming

### Running code formatting

- `./gradlew ktfmtFormat`: Format all Kotlin sources with ktfmt.
- `./gradlew ktfmtCheck`: Verify formatting against ktfmt rules; wired into the `check` pipeline.

### CI formatting strategy

- GitHub Actions runs `./gradlew ktfmtCheck` at the very start of the `build` workflow; non-compliant formatting fails fast and blocks subsequent jobs.
- Git hooks are not enforced locally by default. Run `./gradlew ktfmtFormat` (auto-fix) or `./gradlew ktfmtCheck` (verify only) before committing to avoid CI failures.

---

## Debugging Tips

### Enable verbose logging
```bash
./gradlew generateSymbolCraftIcons --info       # Info level
./gradlew generateSymbolCraftIcons --debug      # Debug level
./gradlew generateSymbolCraftIcons --stacktrace # Stack traces
```

### Disable configuration cache (for debugging)
```bash
./gradlew generateSymbolCraftIcons --no-configuration-cache
```

### Force task re-execution
```bash
./gradlew generateSymbolCraftIcons --rerun-tasks
```

### View task dependencies
```bash
./gradlew generateSymbolCraftIcons --dry-run
```

### View generated files
```bash
# View generated Kotlin files
find . -path "*/generated/symbols/*" -name "*.kt"

# Check cache status
du -sh build/symbolcraft-cache/
```

---

## Git Workflow

### Branch strategy
- `main` - Stable release branch
- `develop` - Development branch (if present)
- `feature/*` - Feature branches
- `fix/*` - Fix branches

### Commit conventions (recommended)
```
<type>(<scope>): <subject>

Types:
- feat: New feature
- fix: Bug fix
- docs: Documentation
- style: Code formatting
- refactor: Refactoring
- test: Tests
- chore: Build/tooling

Examples:
feat(downloader): add retry mechanism for failed downloads
fix(cache): resolve path issues on Windows
docs(readme): update installation guide
```

---

## Contributor Guide

### Getting started

1. Fork the repository to your GitHub account
2. Clone it locally:
   ```bash
   git clone https://github.com/YOUR_USERNAME/SymbolCraft.git
   cd SymbolCraft
   ```

3. Configure the upstream remote:
   ```bash
   git remote add upstream https://github.com/archivesteak/SymbolCraft.git
   ```

### Development flow

1. Create a feature branch
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. Develop and test
   ```bash
   ./gradlew build
   ./gradlew publishToMavenLocal
   cd example && ./gradlew generateSymbolCraftIcons
   ```

3. Commit your changes
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   ```

4. Push and create a Pull Request
   ```bash
   git push origin feature/your-feature-name
   ```

### Pull Request checklist

- [ ] Code follows Kotlin coding conventions
- [ ] Relevant documentation added/updated
- [ ] Tests added/updated (if applicable)
- [ ] Local tests pass
- [ ] Example project runs correctly
- [ ] PR description is clear

---

## Resources

### Official resources
- **GitHub repository**: https://github.com/archivesteak/SymbolCraft
- **GitHub Packages**: https://github.com/archivesteak/SymbolCraft/packages
- **Upstream (original)**: https://github.com/kingsword09/SymbolCraft

### Related tools
- **Material Symbols browser**: https://marella.github.io/material-symbols/demo/
- **Material Symbols official**: https://fonts.google.com/icons
- **svg-to-compose library**: https://github.com/DevSrSouza/svg-to-compose

### Documentation
- **User documentation**: [README.md](README.md)
- **Developer documentation**: [AGENTS.md](AGENTS.md) (this file)

---

## Contact

- **Maintainer**: [@archivesteak](https://github.com/archivesteak)
- **Email**: archivesteak@gmail.com
- **Issue tracker**: [GitHub Issues](https://github.com/archivesteak/SymbolCraft/issues)

---

## Changelog

### v0.6.1 (latest)
- 🔧 **Compile-task wiring fix**: generation is now wired into every `KotlinCompileTool` task by type (loaded reflectively), fixing builds where the Kotlin compile task name contains no "Kotlin" — e.g. AGP 9.x built-in Kotlin / KMP modules (`compileAndroidMain`), which previously failed with "uses this output of task ':generateSymbolCraftIcons' without declaring a dependency".
- 📐 **SwiftUI fixed-size helper**: generated `Symbols.swift` now includes `GeneratedSymbol.pointScale` (= 1 / (1.7 × 0.7 × scaleFactor)) and `image(boxSize:)`, so a symbol can be rendered in an exact point box (e.g. `GeneratedSymbol.homeOutlined.image(boxSize: 24)`) instead of only sizing by font.
- 🧪 **Tests**: 2 new `SymbolSetGeneratorTest` cases (pointScale value, scaleFactor baking).

### v0.6.0
- 🍏 **SwiftUI output**: New `swiftUI { }` DSL generating custom SF Symbol `.symbolset` bundles (template v2.0, full 27 weight/scale variant grid) from the same downloaded SVGs; Material weights map to genuine SF weight columns; optional `Symbols.swift` helper enum.
- 📦 **GitHub Packages publishing**: New `GitHubPackages` Maven repository target (`publishAllPublicationsToGitHubPackagesRepository`) plus a `publish-github-packages` CI job — no Sonatype namespace verification required.
- 🧪 **Tests**: New `SymbolSetGeneratorTest` (20 cases: template structure, guide constants, geometry centering, weight mapping, determinism, name sanitization).
- 📝 **Docs**: AGENTS.md rewritten in English; README_ZH.md removed.

### v0.5.0
- ⚠️ **Breaking change**: Built-in `materialSymbol()` / `materialSymbols()` filled Material Symbols names changed from `...fill1` to `...Fill`, avoiding leaking the Google Fonts URL suffix into the Kotlin API.
- 📚 **Multi-source docs**: README gained configuration examples for built-in Material Symbols, external CDN/npm SVG packages, multi-variant external sources, and local SVGs.
- 🧪 **Example sync**: example regenerated filled Material Symbols and updated references such as `HomeW400OutlinedFill` and `SettingsW500RoundedFill`.

### v0.4.0
- 👀 **Compose Preview configuration**: New `previewAnnotationClass`, supporting the modern Compose Multiplatform default AndroidX preview annotation as well as the legacy JetBrains one.
- 🧹 **Example source root adjustment**: example generated-icons directory moved to `src/commonMain/generated/symbols`, reducing IDE package-path warnings.
- 🔗 **Example external source update**: the official Material Symbols external source switched to esm.sh with a `-fill` variant configuration.

### v0.3.1
- 🛡️ **Security hardening**: Blocked XXE and path-traversal attacks in external SVGs, added content-type and size validation, and fully sanitized dangerous path characters.
- ♻️ **Task split**: `GenerateSymbolsTask` split into smaller steps; more readable log output and groundwork for unit tests.
- 📚 **Documentation**: Documented key constants and default-value design to help contributors understand the configuration quickly.

### v0.3.0
- 🔄 **Multi-variant external icons**: `styleParam { values(...) }` supports Cartesian-product combinations; one declaration generates multiple external icon variants.
- ⚡ **Exponential backoff retry**: The SVG downloader supports exponential backoff, more robust under unstable networks.
- 🔗 **Official CDN**: Material Symbols switched to the official Google Fonts CDN by default for availability and freshness.
- ⚙️ **Configuration cache fix**: Resolved Gradle configuration-cache serialization issues, improving incremental-build compatibility.
- 🏷️ **Naming transformation rewrite**: Rewrote IconNameTransformer; naming configuration is more flexible and reliable.

### v0.2.1
- 🔥 **Major refactor**: Plugin renamed to SymbolCraft (from MaterialSymbolsPlugin)
- 🎉 **Multi-library support**: Material Symbols + Bootstrap Icons + Heroicons + custom URLs
- 🏷️ **Flexible naming**: PascalCase, camelCase, snake_case and more
- ⚡ **Configurable retry**: Added maxRetries and retryDelayMs
- 📚 **Dokka V2**: Full API documentation generation
- 📦 **New DSL**: externalIcon/externalIcons methods
- 🧹 **Updated cache**: symbolcraft-cache directory (from material-symbols-cache)
- 📝 **Documentation**: Updated all READMEs and the developer guide

### v0.1.2
- 🎉 Absolute-path cache configuration support
- 🧹 Smart cache cleanup (skips shared caches)
- 📝 Documentation updates

### v0.1.1
- 🐛 Fixed example preview rendering errors
- ♻️ Refactored SymbolWeight into an enum
- 📦 Absolute-path support for the cache directory

### v0.1.0
- 🚀 Initial release
- ✅ Core functionality complete
- 📚 Complete documentation
- 🎨 Example project

---

**Last updated**: 2026-08-02
**Documentation version**: 3.0.0
