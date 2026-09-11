# SymbolCraft Example Application

A Kotlin Multiplatform app in the layout the KMP wizard generates for "shared logic + native UI",
demonstrating the **SymbolCraft** Gradle plugin on every platform.

## Overview

- **`shared`** — Kotlin logic (`Greeting`) and the **icon declaration**. SymbolCraft is applied
  here. Xcode embeds this module as the `Shared` framework.
- **`composeApp`** — Compose UI for Android and Desktop. Gets the generated `ImageVector`s
  through its dependency on `:shared`.
- **`iosApp`** — native SwiftUI app. Uses the generated `SymbolCraft.xcassets` catalog and
  `Symbols.swift` (`Image(symbol: .homeOutlined)`).

Icons come from Material Symbols (weights, variants, fills), external URL templates (MDI,
esm.sh Material Symbols, Simple Icons) and checked-in local SVGs (`shared/icons/`).

## Version Baseline

- **SymbolCraft**: 0.8.1 (included build of the parent directory)
- **Compose Multiplatform**: 1.11.1
- **Kotlin**: 2.3.21

## Project Structure

```
example/
├── shared/                          # Icon declaration + shared logic, embedded by Xcode
│   ├── build.gradle.kts             # symbolCraft { ... }
│   ├── icons/                       # local SVGs (localIcons("local-test"))
│   ├── src/commonMain/kotlin/       # Greeting.kt
│   └── build/generated/symbolcraft/ # generated output (not committed)
│       ├── compose/                 # ImageVectors, joined to the composeMain source set
│       └── swiftui/
│           ├── SymbolCraft.xcassets # .symbolset bundles
│           └── Symbols.swift        # GeneratedSymbol enum + Image(symbol:)
├── composeApp/                      # Compose UI (Android + Desktop), depends on :shared
└── iosApp/                          # SwiftUI app; references the two generated files above
```

## How the outputs reach each platform

- **Android / Desktop**: `shared/build.gradle.kts` sets `composeSourceSet.set("composeMain")`, an
  intermediate source set shared by the Android and JVM compilations only, so the iOS framework
  never contains Compose. Compiling `:shared` runs `generateSymbolCraftIcons` first.
- **iOS**: the Xcode target's "Compile Kotlin Framework" phase runs
  `./gradlew :shared:embedAndSignAppleFrameworkForXcode`, which depends on
  `generateSymbolCraftSymbolSets`. The Xcode project references
  `../shared/build/generated/symbolcraft/swiftui/SymbolCraft.xcassets` and `Symbols.swift` and
  declares both as Output Files of that script phase.

## Getting Started

### Prerequisites

- **JDK 17** or higher
- **Android SDK** (for the Android app)
- **Xcode 16** (for iOS, macOS only)

### Generate icons

```bash
./gradlew :shared:generateSymbolCraftIcons :shared:generateSymbolCraftSymbolSets
```

Builds do this on their own; the explicit run is useful to inspect the output.

### Run

```bash
./gradlew :composeApp:run              # Desktop
./gradlew :composeApp:assembleDebug    # Android APK
./gradlew :composeApp:installDebug     # Android device/emulator
open iosApp/iosApp.xcodeproj           # iOS: pick a simulator, Run
```

From the terminal on macOS, the same build CI runs:

```bash
cd iosApp
xcodebuild -project iosApp.xcodeproj -target iosApp -configuration Debug -sdk iphonesimulator \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

## Using Generated Icons

Compose (any module depending on `:shared`):

```kotlin
import io.github.archivesteak.example.icons.materialsymbols.Icons as MaterialSymbols
import io.github.archivesteak.example.icons.materialsymbols.icons.SearchW400Outlined
import io.github.archivesteak.example.icons.official.Icons as OfficialIcons

Icon(imageVector = SearchW400Outlined, contentDescription = "Search")
Icon(imageVector = MaterialSymbols.HomeW400OutlinedFill, contentDescription = "Home")
Icon(imageVector = OfficialIcons.HomeFill, contentDescription = "Official home")
```

SwiftUI (`iosApp/iosApp/ContentView.swift`):

```swift
Image(symbol: .homeOutlined)
GeneratedSymbol.homeOutlined.image(boxSize: 24)
List(GeneratedSymbol.allCases, id: \.self) { Image(symbol: $0) }
```

## Development Tasks

```bash
./gradlew :shared:validateSymbolCraftConfig
./gradlew :shared:cleanSymbolCraftIcons
./gradlew :shared:cleanSymbolCraftCache
./gradlew build
```

### Troubleshooting

- **Unresolved icon reference in composeApp** — run `./gradlew :shared:generateSymbolCraftIcons`
  and check the output under `shared/build/generated/symbolcraft/compose`.
- **Xcode: "Build input file cannot be found"** — the generated files must stay listed as Output
  Files of the "Compile Kotlin Framework" phase (see `project.pbxproj`).
- **iOS build fails in the script phase** — run the Gradle task by hand for the full log:
  `./gradlew :shared:generateSymbolCraftSymbolSets --stacktrace`.

## Learn More

- [SymbolCraft Documentation](../README.md)
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform/get-started.html)
- [Direct integration with Xcode](https://kotlinlang.org/docs/multiplatform/multiplatform-direct-integration.html)
- [Material Symbols](https://fonts.google.com/icons)

## License

This example is part of the SymbolCraft project and is licensed under Apache 2.0.
