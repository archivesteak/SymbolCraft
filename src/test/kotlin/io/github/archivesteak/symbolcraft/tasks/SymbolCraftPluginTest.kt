package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.model.ExternalIconConfig
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * TestKit coverage for the producer plugin (single-project use), the consumer plugins
 * (multi-project use), and the Kotlin source-set wiring.
 */
@OptIn(ExperimentalPathApi::class)
class SymbolCraftPluginTest {

    private lateinit var projectDir: Path

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("symbolcraft-testkit")
    }

    @AfterTest
    fun tearDown() {
        if (::projectDir.isInitialized) {
            projectDir.toFile().deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Single project: Compose output
    // ---------------------------------------------------------------------------------------

    @Test
    fun `local icons with custom library name generate compose sources`() {
        createSettings("symbolcraft-local-icons")
        createBuildScript(
            """
                cacheEnabled.set(false)
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/telephone-svgrepo-com.svg")
        writeSvg("src/icons/brand/marketing/mark.svg")

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":downloadSymbolCraftSvgs")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val libraryDir = generatedDir("icons/brand")
        assertTrue(libraryDir.toFile().exists(), "Expected icons/brand directory to exist")

        val generatedIcons = kotlinFiles(libraryDir)
        assertTrue(generatedIcons.isNotEmpty(), "Expected generated icon files for local library")

        val iconNames = generatedIcons.map { it.nameWithoutExtension }
        assertTrue(
            iconNames.any { it == "BrandTelephoneSvgrepoCom" },
            "Generated names: $iconNames",
        )
        assertTrue(
            iconNames.none { it.contains("BrandTelephoneSvgrepoComBrandTelephoneSvgrepoCom") },
            "Generated names: $iconNames",
        )

        val telephoneFile =
            generatedIcons.first { it.nameWithoutExtension == "BrandTelephoneSvgrepoCom" }
        val iconContent = telephoneFile.readText()
        assertTrue(iconContent.contains("package com.test.symbols.icons.brand"))
        assertTrue(iconContent.contains("ImageVector"))
    }

    @Test
    fun `local icons default to library id local when omitted`() {
        createSettings("symbolcraft-local-default")
        createBuildScript(
            """
                cacheEnabled.set(false)
                localIcons {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/ui/search.svg")

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val libraryDir = generatedDir("icons/local")
        assertTrue(
            libraryDir.toFile().exists(),
            "Expected icons/local directory for default library id",
        )

        val generatedIcons = kotlinFiles(libraryDir)
        assertTrue(
            generatedIcons.isNotEmpty(),
            "Expected generated icon files in default library directory",
        )
        assertTrue(
            generatedIcons.first().readText().contains("package com.test.symbols.icons.local")
        )
    }

    @Test
    fun `generated previews default to unified android x preview annotation`() {
        createSettings("symbolcraft-preview-default")
        createBuildScript(
            """
                cacheEnabled.set(false)
                generatePreview.set(true)
                localIcons {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/home.svg")

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val iconContent = firstGeneratedIconContent("icons/local")
        assertTrue(
            iconContent.contains("import androidx.compose.ui.tooling.preview.Preview"),
            iconContent,
        )
        assertTrue(
            !iconContent.contains("import org.jetbrains.compose.ui.tooling.preview.Preview"),
            iconContent,
        )
        assertTrue(iconContent.contains("\n@Preview\n"), iconContent)
    }

    @Test
    fun `generated previews can use custom preview annotation class`() {
        createSettings("symbolcraft-preview-custom")
        createBuildScript(
            """
                cacheEnabled.set(false)
                generatePreview.set(true)
                previewAnnotationClass.set("com.test.preview.IconPreview")
                localIcons {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/home.svg")

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val iconContent = firstGeneratedIconContent("icons/local")
        assertTrue(iconContent.contains("import com.test.preview.IconPreview"), iconContent)
        assertTrue(
            !iconContent.contains("import androidx.compose.ui.tooling.preview.Preview"),
            iconContent,
        )
        assertTrue(iconContent.contains("\n@IconPreview\n"), iconContent)
    }

    @Test
    fun `local and remote icons generate when remote svg is cached`() {
        createSettings("symbolcraft-mixed")
        createBuildScript(
            """
                cacheEnabled.set(true)
                cacheDirectory.set("symbolcraft-cache")
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
                materialSymbol("home") {
                    style()
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/logo.svg")
        seedMaterialSymbolsCache(iconName = "home")

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val localDir = generatedDir("icons/brand")
        val remoteDir = generatedDir("icons/materialsymbols")

        assertTrue(localDir.toFile().exists(), "Expected icons/brand directory to exist")
        assertTrue(remoteDir.toFile().exists(), "Expected icons/materialsymbols directory to exist")

        val remoteIcons = kotlinFiles(remoteDir)
        assertTrue(kotlinFiles(localDir).isNotEmpty(), "Expected generated local icon files")
        assertTrue(remoteIcons.isNotEmpty(), "Expected generated remote icon files from cache")

        val remoteContent = remoteIcons.first().readText()
        assertTrue(remoteContent.contains("package com.test.symbols.icons.materialsymbols"))
        assertTrue(remoteContent.contains("ImageVector"))
    }

    @Test
    fun `external icons reuse seeded cache alongside local icons`() {
        createSettings("symbolcraft-external")
        createBuildScript(
            """
                cacheEnabled.set(true)
                cacheDirectory.set("symbolcraft-cache")
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
                externalIcon("globe", libraryName = "brandcdn") {
                    urlTemplate = "https://static.example.com/icons/{name}.svg"
                    styleParam("variant", "default")
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/logo.svg")
        val externalConfig =
            ExternalIconConfig(
                libraryName = "brandcdn",
                urlTemplate = "https://static.example.com/icons/{name}.svg",
                styleParams = mapOf("variant" to "default"),
            )
        seedExternalIconCache(iconName = "globe", config = externalConfig)

        val result = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)

        val externalDir = generatedDir("icons/brandcdn")
        assertTrue(generatedDir("icons/brand").toFile().exists(), "Expected icons/brand directory")
        assertTrue(externalDir.toFile().exists(), "Expected icons/brandcdn directory to exist")

        val externalIcons = kotlinFiles(externalDir)
        assertTrue(externalIcons.isNotEmpty(), "Expected generated external icon files from cache")
        val externalContent = externalIcons.first().readText()
        assertTrue(externalContent.contains("package com.test.symbols.icons.brandcdn"))
        assertTrue(externalContent.contains("ImageVector"))
    }

    @Test
    fun `subsequent mixed run is up to date with unchanged inputs`() {
        createSettings("symbolcraft-mixed-up-to-date")
        createBuildScript(
            """
                cacheEnabled.set(true)
                cacheDirectory.set("symbolcraft-cache")
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
                materialSymbol("home") {
                    style()
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/logo.svg")
        seedMaterialSymbolsCache(iconName = "home")

        val firstRun = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, firstRun.task(":generateSymbolCraftIcons")?.outcome)

        val secondRun = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.UP_TO_DATE, secondRun.task(":downloadSymbolCraftSvgs")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, secondRun.task(":generateSymbolCraftIcons")?.outcome)
    }

    @Test
    fun `editing a local svg re-runs download and generation`() {
        createSettings("symbolcraft-local-edit")
        createBuildScript(
            """
                cacheEnabled.set(false)
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/logo.svg")

        val firstRun = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, firstRun.task(":generateSymbolCraftIcons")?.outcome)

        val secondRun = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.UP_TO_DATE, secondRun.task(":generateSymbolCraftIcons")?.outcome)

        // Local SVG contents are inputs of the download task: changing only the file (not the
        // DSL) must invalidate the workspace and therefore the generated sources.
        writeSvg("src/icons/brand/logo.svg", pathData = "M4 4h16v16H4z")

        val thirdRun = runGradle("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.SUCCESS, thirdRun.task(":downloadSymbolCraftSvgs")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, thirdRun.task(":generateSymbolCraftIcons")?.outcome)
    }

    @Test
    fun `a remote icon that cannot be fetched fails the build by name`() {
        createSettings("symbolcraft-download-failure")
        createBuildScript(
            """
                cacheEnabled.set(false)
                maxRetries.set(1)
                externalIcon("nope", libraryName = "dead") {
                    urlTemplate = "https://127.0.0.1:9/{name}.svg"
                }
            """
                .trimIndent()
        )

        val result = runGradleAndFail("generateSymbolCraftIcons")
        assertEquals(TaskOutcome.FAILED, result.task(":downloadSymbolCraftSvgs")?.outcome)
        assertTrue(result.output.contains("could not fetch 1 icon(s)"), result.output)
        assertTrue(result.output.contains("nope (external-dead)"), result.output)
    }

    // ---------------------------------------------------------------------------------------
    // Single project: SwiftUI output
    // ---------------------------------------------------------------------------------------

    @Test
    fun `platform targets route icons to compose or swiftui only`() {
        createSettings("symbolcraft-targets")
        createBuildScript(
            """
                cacheEnabled.set(true)
                cacheDirectory.set("symbolcraft-cache")
                swiftUI {
                    enabled.set(true)
                    outputDirectory.set("build/generated/symbolsets")
                    generateSwiftEnum.set(true)
                }
                localIcons(libraryName = "brand") {
                    directory = "src/icons"
                }
                materialSymbol("home") {
                    style()
                    composeOnly()
                }
                materialSymbol("airplay") {
                    style()
                    swiftUIOnly()
                }
            """
                .trimIndent()
        )

        writeSvg("src/icons/brand/logo.svg")
        seedMaterialSymbolsCache(iconName = "home")
        seedMaterialSymbolsCache(iconName = "airplay")

        val result = runGradle("generateSymbolCraftIcons", "generateSymbolCraftSymbolSets")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftSymbolSets")?.outcome)

        val kotlinFiles = kotlinFiles(generatedDir("icons/materialsymbols")).map { it.name }
        assertTrue(
            kotlinFiles.any { it.startsWith("Home") },
            "composeOnly icon must emit Compose sources: $kotlinFiles",
        )
        assertTrue(
            kotlinFiles.none { it.startsWith("Airplay") },
            "swiftUIOnly icon must not emit Compose sources: $kotlinFiles",
        )

        val symbolSets = symbolSetNames(projectDir.resolve("build/generated/symbolsets"))
        assertTrue(
            "AirplayOutlined.symbolset" in symbolSets,
            "swiftUIOnly icon must emit a symbol set: $symbolSets",
        )
        assertTrue(
            symbolSets.none { it.startsWith("Home") },
            "composeOnly icon must not emit a symbol set: $symbolSets",
        )
        assertTrue(
            symbolSets.any { it.startsWith("BrandLogo") },
            "default targets emit both platforms: $symbolSets",
        )

        // A plain (non-.xcassets) output folder keeps Symbols.swift next to the bundles.
        val swiftSource =
            projectDir.resolve("build/generated/symbolsets/Symbols.swift").toFile().readText()
        assertTrue(swiftSource.contains("airplayOutlined"), swiftSource)
        assertTrue(!swiftSource.contains("homeOutlined"), swiftSource)
    }

    @Test
    fun `default output locations live under build and form an xcassets catalog`() {
        createSettings("symbolcraft-defaults")
        writeProjectFile(
            "build.gradle.kts",
            """
                plugins {
                    id("io.github.archivesteak.symbolcraft")
                }

                symbolCraft {
                    packageName.set("com.test.symbols")
                    cacheEnabled.set(false)
                    swiftUI { enabled.set(true) }
                    localIcons(libraryName = "brand") {
                        directory = "src/icons"
                    }
                }

                // Stand-in for the Kotlin Gradle plugin task Xcode's run-script phase invokes.
                tasks.register("embedAndSignAppleFrameworkForXcode") {
                    doLast { println("EMBEDDING FRAMEWORK") }
                }
            """
                .trimIndent(),
        )
        writeSvg("src/icons/brand/logo.svg")

        val result = runGradle("generateSymbolCraftIcons", "embedAndSignAppleFrameworkForXcode")
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftIcons")?.outcome)
        assertEquals(TaskOutcome.SUCCESS, result.task(":generateSymbolCraftSymbolSets")?.outcome)
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":embedAndSignAppleFrameworkForXcode")?.outcome,
        )
        val order = result.output.lines()
        val symbolSetsLine = order.indexOfFirst { it.contains(":generateSymbolCraftSymbolSets") }
        val embedLine = order.indexOfFirst { it.contains(":embedAndSignAppleFrameworkForXcode") }
        assertTrue(symbolSetsLine in 0 until embedLine, "symbol sets must precede the embed task")

        val composeDir = projectDir.resolve("build/generated/symbolcraft/compose")
        assertTrue(
            kotlinFiles(composeDir.resolve("com/test/symbols/icons/brand")).isNotEmpty(),
            "Compose sources must default to build/generated/symbolcraft/compose",
        )

        val catalog = projectDir.resolve("build/generated/symbolcraft/swiftui/SymbolCraft.xcassets")
        assertTrue(catalog.resolve("Contents.json").toFile().isFile, "catalog manifest missing")
        assertTrue(
            "BrandLogo.symbolset" in symbolSetNames(catalog),
            symbolSetNames(catalog).toString(),
        )

        // The Swift helper never lives inside the catalog: Xcode treats catalogs as leaves.
        val swift = projectDir.resolve("build/generated/symbolcraft/swiftui/Symbols.swift")
        assertTrue(swift.toFile().isFile, "Symbols.swift must sit next to the catalog")
        assertTrue(!catalog.resolve("Symbols.swift").toFile().exists())
        assertTrue(swift.readText().contains("case brandLogo"), swift.readText())
    }

    @Test
    fun `symbol set generation is skipped while swiftui output is disabled`() {
        createSettings("symbolcraft-swiftui-disabled")
        createBuildScript(
            """
                cacheEnabled.set(false)
                localIcons {
                    directory = "src/icons"
                }
            """
                .trimIndent()
        )
        writeSvg("src/icons/home.svg")

        val result = runGradle("generateSymbolCraftSymbolSets")
        assertEquals(TaskOutcome.SKIPPED, result.task(":generateSymbolCraftSymbolSets")?.outcome)
        assertTrue(
            !projectDir.resolve("build/generated/symbolcraft/swiftui").toFile().exists(),
            "nothing may be written while disabled",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Multi project: consumer plugins
    // ---------------------------------------------------------------------------------------

    @Test
    fun `compose consumer receives generated sources through the symbolCraft configuration`() {
        writeMultiProject()

        val result = runGradle(":app:listSymbolCraftSources")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":symbols:downloadSymbolCraftSvgs")?.outcome,
            "producer download must run before the consumer task",
        )
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":symbols:generateSymbolCraftIcons")?.outcome,
            "producer generation must run before the consumer task",
        )
        assertTrue(
            result.task(":symbols:generateSymbolCraftSymbolSets") == null,
            "compose consumer must not trigger the SwiftUI output",
        )
        val expectedDir =
            projectDir.resolve("symbols/build/generated/symbolcraft/compose").toFile().canonicalPath
        assertTrue(result.output.contains("SOURCE_DIR=$expectedDir"), result.output)
        assertTrue(result.output.contains("SOURCE_FILE=BrandLogo.kt"), result.output)
    }

    @Test
    fun `apple consumer generates the producer catalog before embedding the framework`() {
        writeMultiProject()

        val result = runGradle(":shared:embedAndSignDebugAppleFrameworkForXcode")
        assertEquals(
            TaskOutcome.SUCCESS,
            result.task(":symbols:generateSymbolCraftSymbolSets")?.outcome,
            "producer catalog must be generated before the embed task",
        )
        assertTrue(
            result.task(":symbols:generateSymbolCraftIcons") == null,
            "apple consumer must not trigger the Compose output",
        )
        val catalog =
            projectDir.resolve("symbols/build/generated/symbolcraft/swiftui/SymbolCraft.xcassets")
        assertTrue(
            "BrandLogo.symbolset" in symbolSetNames(catalog),
            symbolSetNames(catalog).toString(),
        )
    }

    // ---------------------------------------------------------------------------------------
    // Kotlin source-set wiring (reflective, no Kotlin classes on the plugin classpath)
    // ---------------------------------------------------------------------------------------

    @Test
    fun `kotlin jvm project gets generated sources in its main source set`() {
        writeProjectFile(
            "settings.gradle.kts",
            """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                rootProject.name = "symbolcraft-kotlin-wiring"
            """
                .trimIndent(),
        )
        writeProjectFile(
            "build.gradle.kts",
            """
                plugins {
                    id("org.jetbrains.kotlin.jvm") version "$KOTLIN_VERSION"
                    id("io.github.archivesteak.symbolcraft")
                }

                repositories {
                    mavenCentral()
                }

                symbolCraft {
                    packageName.set("com.test.symbols")
                    cacheEnabled.set(false)
                    localIcons {
                        directory = "src/icons"
                    }
                }

                tasks.register("printSymbolCraftSourceDirs") {
                    val dirs = kotlin.sourceSets.getByName("main").kotlin.srcDirs
                    doLast { dirs.forEach { println("SRC_DIR=" + it.canonicalPath) } }
                }
            """
                .trimIndent(),
        )
        writeSvg("src/icons/home.svg")

        val expectedDir =
            projectDir.resolve("build/generated/symbolcraft/compose").toFile().canonicalPath
        val dirs = runGradle("printSymbolCraftSourceDirs")
        assertTrue(dirs.output.contains("SRC_DIR=$expectedDir"), dirs.output)

        // The source directory carries the producer task, so compilation depends on it.
        val dryRun = runGradle("compileKotlin", "--dry-run")
        val lines = dryRun.output.lines()
        val generate = lines.indexOfFirst { it.startsWith(":generateSymbolCraftIcons") }
        val compile = lines.indexOfFirst { it.startsWith(":compileKotlin") }
        assertTrue(
            generate in 0 until compile,
            "generation must precede compileKotlin:\n${dryRun.output}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private fun writeMultiProject() {
        writeProjectFile(
            "settings.gradle.kts",
            """
                rootProject.name = "symbolcraft-multi"
                include(":symbols", ":app", ":shared")
            """
                .trimIndent(),
        )
        writeProjectFile(
            "symbols/build.gradle.kts",
            """
                plugins {
                    id("io.github.archivesteak.symbolcraft")
                }

                symbolCraft {
                    packageName.set("com.test.symbols")
                    cacheEnabled.set(false)
                    swiftUI { enabled.set(true) }
                    localIcons(libraryName = "brand") {
                        directory = "src/icons"
                    }
                }
            """
                .trimIndent(),
        )
        writeSvg("symbols/src/icons/brand/logo.svg")
        writeProjectFile(
            "app/build.gradle.kts",
            """
                plugins {
                    id("io.github.archivesteak.symbolcraft.compose")
                }

                dependencies {
                    "symbolCraft"(project(":symbols"))
                }

                // No Kotlin plugin here: consume the resolved configuration directly, the way the
                // plugin adds it to a Kotlin source set.
                tasks.register("listSymbolCraftSources") {
                    val sources = configurations["symbolCraftComposeSources"]
                    inputs.files(sources)
                    doLast {
                        sources.files.forEach { dir ->
                            println("SOURCE_DIR=" + dir.canonicalPath)
                            dir.walkTopDown().filter { it.isFile }.forEach { println("SOURCE_FILE=" + it.name) }
                        }
                    }
                }
            """
                .trimIndent(),
        )
        writeProjectFile(
            "shared/build.gradle.kts",
            """
                plugins {
                    id("io.github.archivesteak.symbolcraft.apple")
                }

                dependencies {
                    "symbolCraft"(project(":symbols"))
                }

                // Stand-in for the Kotlin Gradle plugin task Xcode's run-script phase invokes.
                tasks.register("embedAndSignDebugAppleFrameworkForXcode") {
                    doLast { println("EMBEDDING FRAMEWORK") }
                }
            """
                .trimIndent(),
        )
    }

    private fun createSettings(projectName: String) {
        writeProjectFile("settings.gradle.kts", """rootProject.name = "$projectName"""")
    }

    private fun createBuildScript(configBody: String) {
        writeProjectFile("build.gradle.kts", buildScript(configBody))
    }

    private fun generatedDir(suffix: String): Path =
        projectDir.resolve("build/generated/symbols/com/test/symbols/$suffix")

    private fun kotlinFiles(dir: Path): List<File> =
        dir.toFile().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun symbolSetNames(dir: Path): List<String> =
        dir.toFile().listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()

    private fun firstGeneratedIconContent(suffix: String): String {
        val generatedIcon =
            generatedDir(suffix).resolve("icons").toFile().walkTopDown().first {
                it.isFile && it.extension == "kt"
            }
        return generatedIcon.toPath().readText()
    }

    private fun writeProjectFile(relativePath: String, content: String) {
        val target = projectDir.resolve(relativePath)
        target.parent?.createDirectories()
        target.writeText(content.trimIndent() + "\n")
    }

    private fun writeSvg(
        relativePath: String,
        pathData: String = "M12 2a10 10 0 1 1-0.001 20.001A10 10 0 0 1 12 2z",
    ) {
        val target = projectDir.resolve(relativePath)
        target.parent?.createDirectories()
        target.writeText(testSvg(pathData))
    }

    private fun testSvg(pathData: String): String =
        """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                <path d="$pathData" fill="currentColor"/>
            </svg>
        """
            .trimIndent()

    private fun buildScript(configBody: String): String =
        """
        plugins {
            id("io.github.archivesteak.symbolcraft")
        }

        symbolCraft {
            packageName.set("com.test.symbols")
            outputDirectory.set("build/generated/symbols")
            $configBody
        }
    """
            .trimIndent()

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withArguments(*arguments, "--stacktrace")
            .withPluginClasspath()
            .withTestKitDir(TEST_KIT_DIR)
            .forwardOutput()

    private fun runGradle(vararg arguments: String): BuildResult = runner(*arguments).build()

    private fun runGradleAndFail(vararg arguments: String): BuildResult =
        runner(*arguments).buildAndFail()

    private fun seedMaterialSymbolsCache(iconName: String) {
        val cacheDir = projectDir.resolve("build/symbolcraft-cache/svg-cache")
        cacheDir.createDirectories()

        val cacheKey = "${sanitize(iconName)}_material-symbols_400_outlined_unfilled"
        val svgContent = testSvg("M12 2a10 10 0 1 1-0.001 20.001A10 10 0 0 1 12 2z")
        val cacheFile = cacheDir.resolve("$cacheKey.svg")
        val metaFile = cacheDir.resolve("$cacheKey.meta")
        val url =
            "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/$iconName/default/24px.svg"
        val hash = sha256(svgContent)

        cacheFile.writeText(svgContent)
        metaFile.writeText("${System.currentTimeMillis()}\n$url\n$hash")
    }

    private fun seedExternalIconCache(iconName: String, config: ExternalIconConfig) {
        val cacheDir = projectDir.resolve("build/symbolcraft-cache/svg-cache")
        cacheDir.createDirectories()

        val cacheKey = config.getCacheKey(iconName)
        val svgContent = testSvg("M12 2a10 10 0 1 1-0.001 20.001A10 10 0 0 1 12 2z")
        val cacheFile = cacheDir.resolve("$cacheKey.svg")
        val metaFile = cacheDir.resolve("$cacheKey.meta")
        val url = config.buildUrl(iconName)
        val hash = sha256(svgContent)

        cacheFile.writeText(svgContent)
        metaFile.writeText("${System.currentTimeMillis()}\n$url\n$hash")
    }

    private fun sha256(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun sanitize(iconName: String): String =
        iconName
            .replace("/", "_")
            .replace("\\", "_")
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifEmpty { "icon" }

    private companion object {
        /** Kotlin Gradle plugin version resolved from the Plugin Portal for the wiring test. */
        const val KOTLIN_VERSION = "2.0.0"

        /**
         * Gradle user home for TestKit builds. Kept under the plugin's own build directory (the
         * test worker's working directory) instead of the system temp dir so downloaded plugins
         * survive between runs and `clean` removes them.
         */
        val TEST_KIT_DIR: File = File("build/testkit").absoluteFile
    }
}
