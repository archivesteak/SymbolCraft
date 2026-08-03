package io.github.archivesteak.symbolcraft.plugin

import io.github.archivesteak.symbolcraft.model.IconConfig
import io.github.archivesteak.symbolcraft.model.IconTarget
import io.github.archivesteak.symbolcraft.model.IconTargets
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Unit tests for per-icon platform targeting (swiftUIOnly / composeOnly DSL). */
@OptIn(ExperimentalPathApi::class)
class IconTargetsDslTest {

    private lateinit var projectDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        projectDir = createTempDirectory("symbolcraft-targets-test")
    }

    @AfterTest
    fun tearDown() {
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun `configs default to all targets`() {
        val builder = MaterialSymbolsBuilder()
        builder.style()

        assertEquals(IconTargets.ALL, builder.effectiveConfigs().single().targets)
    }

    @Test
    fun `swiftUIOnly applies regardless of call order`() {
        val before = MaterialSymbolsBuilder()
        before.swiftUIOnly()
        before.style()

        val after = MaterialSymbolsBuilder()
        after.style()
        after.swiftUIOnly()

        assertEquals(IconTargets.SWIFTUI_ONLY, before.effectiveConfigs().single().targets)
        assertEquals(IconTargets.SWIFTUI_ONLY, after.effectiveConfigs().single().targets)
    }

    @Test
    fun `composeOnly applies to every configured style`() {
        val builder = MaterialSymbolsBuilder()
        builder.standardWeights()
        builder.composeOnly()

        assertEquals(3, builder.effectiveConfigs().size)
        builder.effectiveConfigs().forEach { assertEquals(IconTargets.COMPOSE_ONLY, it.targets) }
    }

    @Test
    fun `external builder applies targets to single and cartesian configs`() {
        val single = ExternalIconBuilder("lib")
        single.urlTemplate = "https://example.com/{name}.svg"
        single.swiftUIOnly()

        val cartesian = ExternalIconBuilder("lib")
        cartesian.urlTemplate = "https://example.com/{style}/{name}.svg"
        cartesian.styleParam("style") { values("a", "b") }
        cartesian.composeOnly()

        assertEquals(IconTargets.SWIFTUI_ONLY, single.build().single().targets)
        assertEquals(2, cartesian.build().size)
        cartesian.build().forEach { assertEquals(IconTargets.COMPOSE_ONLY, it.targets) }
    }

    @Test
    fun `local builder applies targets to discovered icons`() {
        val iconsDir = projectDir.resolve("icons").createDirectories()
        iconsDir.resolve("logo.svg").writeText("<svg/>")

        val builder = LocalIconsBuilder(projectDir.toAbsolutePath().toString())
        builder.directory = "icons"
        builder.swiftUIOnly()

        val configs = builder.build("local")
        assertEquals(1, configs.size)
        assertEquals(IconTargets.SWIFTUI_ONLY, configs.values.single().targets)
    }

    @Test
    fun `custom IconConfig implementations default to all targets`() {
        val custom =
            object : IconConfig {
                override val libraryId = "custom"

                override fun buildUrl(iconName: String) = "https://example.com/$iconName.svg"

                override fun getCacheKey(iconName: String) = iconName

                override fun getSignature() = "Custom"
            }

        assertEquals(IconTargets.ALL, custom.targets)
        assertEquals(setOf(IconTarget.COMPOSE, IconTarget.SWIFTUI), IconTargets.ALL)
    }
}
