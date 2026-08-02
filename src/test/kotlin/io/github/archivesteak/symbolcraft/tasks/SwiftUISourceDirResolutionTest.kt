package io.github.archivesteak.symbolcraft.tasks

import io.github.archivesteak.symbolcraft.tasks.internal.SymbolSetGenerationCoordinator
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class SwiftUISourceDirResolutionTest {

    private val projectDir = createTempDirectory("symbolcraft-project").toFile()

    @Test
    fun `plain folder output keeps Symbols swift next to the bundles`() {
        val outputDir = File(projectDir, "GeneratedSymbols")
        assertEquals(
            outputDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(null, outputDir, projectDir),
        )
    }

    @Test
    fun `xcassets output redirects Symbols swift to the catalog parent`() {
        val catalog = File(projectDir, "Assets.xcassets")
        assertEquals(
            projectDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(null, catalog, projectDir),
        )
    }

    @Test
    fun `output inside an xcassets child redirects to the catalog parent`() {
        val child = File(projectDir, "Assets.xcassets/SymbolCraft")
        assertEquals(
            projectDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(null, child, projectDir),
        )
    }

    @Test
    fun `xcassets suffix matching is case-insensitive`() {
        val catalog = File(projectDir, "Assets.XCAssets")
        assertEquals(
            projectDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(null, catalog, projectDir),
        )
    }

    @Test
    fun `dir named exactly dot-xcassets redirects to its parent`() {
        val catalog = File(projectDir, ".xcassets")
        assertEquals(
            projectDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(null, catalog, projectDir),
        )
    }

    @Test
    fun `explicit relative path resolves against the project directory`() {
        val outputDir = File(projectDir, "Assets.xcassets")
        assertEquals(
            File(projectDir, "Sources/Generated"),
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(
                "Sources/Generated",
                outputDir,
                projectDir,
            ),
        )
    }

    @Test
    fun `explicit absolute path wins over everything`() {
        val absolute = createTempDirectory("symbolcraft-swift-abs").toFile()
        val outputDir = File(projectDir, "Assets.xcassets")
        assertEquals(
            absolute,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir(
                absolute.absolutePath,
                outputDir,
                projectDir,
            ),
        )
    }

    @Test
    fun `blank configured path falls back to derivation`() {
        val catalog = File(projectDir, "Assets.xcassets")
        assertEquals(
            projectDir,
            SymbolSetGenerationCoordinator.resolveSwiftSourceDir("  ", catalog, projectDir),
        )
    }
}
