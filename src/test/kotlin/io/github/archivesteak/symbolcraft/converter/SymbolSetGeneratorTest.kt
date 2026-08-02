package io.github.archivesteak.symbolcraft.converter

import io.github.archivesteak.symbolcraft.model.ExternalIconConfig
import io.github.archivesteak.symbolcraft.model.LocalIconConfig
import io.github.archivesteak.symbolcraft.model.MaterialSymbolsConfig
import io.github.archivesteak.symbolcraft.model.SymbolFill
import io.github.archivesteak.symbolcraft.model.SymbolVariant
import io.github.archivesteak.symbolcraft.model.SymbolWeight
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Unit tests for [SymbolSetGenerator] (custom SF Symbol `.symbolset` generation). */
class SymbolSetGeneratorTest {

    private val generator = SymbolSetGenerator()

    private val materialSvg =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 -960 960 960"><path d="M240-200h120v-240h240v240h120v-360L480-740 240-560v360Z"/></svg>"""

    // ========== SVG parsing ==========

    @Test
    fun `parseSvg extracts viewBox and paths`() {
        val file = writeSvg("icon.svg", materialSvg)
        val parsed = generator.parseSvg(file)

        assertEquals(0.0, parsed.viewBox.x)
        assertEquals(-960.0, parsed.viewBox.y)
        assertEquals(960.0, parsed.viewBox.width)
        assertEquals(960.0, parsed.viewBox.height)
        assertEquals(1, parsed.paths.size)
    }

    @Test
    fun `parseSvg falls back to width and height attributes`() {
        val file =
            writeSvg(
                "icon.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" width="48" height="32"><path d="M0 0h10v10H0z"/></svg>""",
            )
        val parsed = generator.parseSvg(file)

        assertEquals(48.0, parsed.viewBox.width)
        assertEquals(32.0, parsed.viewBox.height)
    }

    @Test
    fun `parseSvg defaults to 24x24 without any sizing`() {
        val file = writeSvg("icon.svg", """<svg><path d="M0 0h10v10H0z"/></svg>""")
        val parsed = generator.parseSvg(file)

        assertEquals(24.0, parsed.viewBox.width)
        assertEquals(24.0, parsed.viewBox.height)
    }

    @Test
    fun `parseSvg extracts multiple paths`() {
        val file =
            writeSvg(
                "icon.svg",
                """<svg viewBox="0 0 24 24"><path d="M1 1h2v2H1z"/><path d="M5 5h2v2H5z"/></svg>""",
            )
        assertEquals(2, generator.parseSvg(file).paths.size)
    }

    @Test
    fun `parseSvg rejects SVGs without paths`() {
        val file =
            writeSvg(
                "icon.svg",
                """<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/></svg>""",
            )
        assertThrows(IllegalArgumentException::class.java) { generator.parseSvg(file) }
    }

    // ========== Template structure ==========

    @Test
    fun `generated SVG contains all required template elements`() {
        val svg = buildSingleGlyphSvg()

        // Notes
        assertTrue(svg.contains("id=\"artboard\""), "artboard missing")
        assertTrue(
            svg.contains("id=\"template-version\"") && svg.contains("Template v.2.0"),
            "template-version missing",
        )
        assertTrue(svg.contains("id=\"descriptive-name\""), "descriptive-name missing")

        // Guides
        listOf("Baseline-S", "Baseline-M", "Baseline-L", "Capline-S", "Capline-M", "Capline-L")
            .forEach { assertTrue(svg.contains("id=\"$it\""), "$it missing") }
        assertTrue(svg.contains("id=\"left-margin\""), "left-margin missing")
        assertTrue(svg.contains("id=\"right-margin\""), "right-margin missing")

        // All 27 weight/scale variants
        val weights =
            listOf(
                "Ultralight",
                "Thin",
                "Light",
                "Regular",
                "Medium",
                "Semibold",
                "Bold",
                "Heavy",
                "Black",
            )
        val scales = listOf("S", "M", "L")
        weights.forEach { w ->
            scales.forEach { s ->
                assertTrue(svg.contains("id=\"$w-$s\""), "variant $w-$s missing")
            }
        }

        // Group ordering required by Xcode
        val notesIdx = svg.indexOf("<g id=\"Notes\">")
        val guidesIdx = svg.indexOf("<g id=\"Guides\">")
        val symbolsIdx = svg.indexOf("<g id=\"Symbols\">")
        assertTrue(notesIdx in 0 until guidesIdx, "Notes must precede Guides")
        assertTrue(guidesIdx < symbolsIdx, "Guides must precede Symbols")
    }

    @Test
    fun `guide positions match Apple template values`() {
        val svg = buildSingleGlyphSvg()

        assertTrue(
            svg.contains(
                "id=\"Baseline-M\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"1126\" y2=\"1126\""
            )
        )
        assertTrue(
            svg.contains(
                "id=\"Capline-M\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"1055.54\" y2=\"1055.54\""
            )
        )
        assertTrue(
            svg.contains(
                "id=\"Baseline-S\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"696\" y2=\"696\""
            )
        )
        assertTrue(
            svg.contains(
                "id=\"Capline-S\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"625.541\" y2=\"625.541\""
            )
        )
        assertTrue(
            svg.contains(
                "id=\"Baseline-L\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"1556\" y2=\"1556\""
            )
        )
        assertTrue(
            svg.contains(
                "id=\"Capline-L\" style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\" x1=\"263\" x2=\"3036\" y1=\"1485.54\" y2=\"1485.54\""
            )
        )
    }

    // ========== Geometry ==========

    @Test
    fun `Regular-M variant is centered between capline and baseline`() {
        val svg = buildSingleGlyphSvg()
        val (scale, tx, ty) = extractTransform(svg, "Regular-M")

        // Glyph viewBox: 0 -960 960 960 -> vertical span in template coords:
        // [ty + (-960)*scale, ty] must be centered on (1126 + 1055.54) / 2 = 1090.77
        val centerY = ty + (-960.0) * scale + (960.0 * scale) / 2
        assertEquals(1090.77, centerY, 0.1, "glyph not vertically centered for Regular-M")

        // Horizontally centered on the Regular column (1650)
        val centerX = tx + (960.0 * scale) / 2
        assertEquals(1650.0, centerX, 0.1, "glyph not horizontally centered for Regular-M")
    }

    @Test
    fun `margins enclose the Regular glyph with padding`() {
        val svg = buildSingleGlyphSvg()
        val (scale, tx, _) = extractTransform(svg, "Regular-M")
        val glyphLeft = tx
        val glyphRight = tx + 960.0 * scale

        val leftMargin = extractLineX(svg, "left-margin")
        val rightMargin = extractLineX(svg, "right-margin")

        // Tolerance accounts for the 4-decimal rounding in the emitted SVG.
        assertEquals(glyphLeft - 4.5, leftMargin, 0.1)
        assertEquals(glyphRight + 4.5, rightMargin, 0.1)
        assertTrue(leftMargin < glyphLeft && rightMargin > glyphRight)
    }

    @Test
    fun `weight columns are evenly spaced around Regular`() {
        val svg = buildSingleGlyphSvg()
        val centers =
            listOf(
                    "Ultralight",
                    "Thin",
                    "Light",
                    "Regular",
                    "Medium",
                    "Semibold",
                    "Bold",
                    "Heavy",
                    "Black",
                )
                .map { weight ->
                    val (scale, tx, _) = extractTransform(svg, "$weight-M")
                    tx + (960.0 * scale) / 2
                }

        // All column centers must be 296.71 apart
        centers.zipWithNext().forEach { (a, b) ->
            assertEquals(296.71, b - a, 0.05, "uneven weight column spacing")
        }
        assertEquals(1650.0, centers[3], 0.1, "Regular column must sit at canvas center")
    }

    @Test
    fun `heavier derived weights render slightly larger`() {
        val svg = buildSingleGlyphSvg() // single Regular glyph; other weights are derived
        val (regularScale, _, _) = extractTransform(svg, "Regular-M")
        val (blackScale, _, _) = extractTransform(svg, "Black-M")
        val (ultralightScale, _, _) = extractTransform(svg, "Ultralight-M")

        assertTrue(blackScale > regularScale, "Black should be larger than Regular")
        assertTrue(ultralightScale < regularScale, "Ultralight should be smaller than Regular")
    }

    @Test
    fun `scales follow the cap-height ratio`() {
        val svg = buildSingleGlyphSvg()
        val (scaleS, _, _) = extractTransform(svg, "Regular-S")
        val (scaleM, _, _) = extractTransform(svg, "Regular-M")
        val (scaleL, _, _) = extractTransform(svg, "Regular-L")

        assertEquals(1.0, scaleS / scaleM, 0.001, "S and M share the same cap height")
        assertEquals(
            (1556.0 - 1485.54) / (1126.0 - 1055.54),
            scaleL / scaleM,
            0.001,
            "L must follow the cap-height ratio",
        )
    }

    @Test
    fun `real weight glyphs are not size-adjusted`() {
        // Two real glyphs (Regular + Bold): Bold-M must use its own outline at ratio 1.0
        val glyphs = mapOf("Regular" to parsedMaterial(), "Bold" to parsedMaterial())
        val svg = generator.buildSymbolSetSvg("Test", glyphs, 1.0)

        val (regularScale, _, _) = extractTransform(svg, "Regular-M")
        val (boldScale, _, _) = extractTransform(svg, "Bold-M")
        assertEquals(regularScale, boldScale, 0.0001, "real glyphs keep their native sizing")
    }

    // ========== Determinism ==========

    @Test
    fun `output is deterministic`() {
        val first = buildSingleGlyphSvg()
        val second = buildSingleGlyphSvg()
        assertEquals(first, second)
    }

    // ========== Naming ==========

    @Test
    fun `sanitizeAssetName replaces invalid characters`() {
        assertEquals("Home-Outlined", generator.sanitizeAssetName("Home Outlined"))
        assertEquals("Icon", generator.sanitizeAssetName("Icon"))
        assertEquals("Symbol", generator.sanitizeAssetName("!!!"))
        assertEquals("a_b-c.d", generator.sanitizeAssetName("a_b-c.d"))
    }

    @Test
    fun `swiftCaseName produces valid Swift identifiers`() {
        assertEquals("homeOutlined", generator.swiftCaseName("HomeOutlined"))
        assertEquals("_3dRotation", generator.swiftCaseName("3dRotation"))
        assertEquals("`repeat`", generator.swiftCaseName("repeat"))
        assertEquals("`default`", generator.swiftCaseName("default"))
        assertEquals("iconName", generator.swiftCaseName("icon-name"))
    }

    // ========== Library generation (material grouping) ==========

    @Test
    fun `material symbols group weights into one symbol set per variant and fill`() {
        val tempDir = createTempDirectory("symbolset-gen").toFile()
        val outDir = File(tempDir, "out")
        val libDir = File(tempDir, "material-symbols").apply { mkdirs() }

        val configs =
            mapOf(
                "home" to
                    listOf(
                        MaterialSymbolsConfig(SymbolWeight.W400, SymbolVariant.OUTLINED),
                        MaterialSymbolsConfig(SymbolWeight.W500, SymbolVariant.OUTLINED),
                        MaterialSymbolsConfig(SymbolWeight.W700, SymbolVariant.OUTLINED),
                        MaterialSymbolsConfig(
                            SymbolWeight.W400,
                            SymbolVariant.OUTLINED,
                            SymbolFill.FILLED,
                        ),
                        MaterialSymbolsConfig(SymbolWeight.W400, SymbolVariant.ROUNDED),
                    )
            )

        // Temp file names must match the shared tempSvgFileName used by the download phase
        listOf(
                "HomeW400Outlined.svg",
                "HomeW500Outlined.svg",
                "HomeW700Outlined.svg",
                "HomeW400OutlinedFill.svg",
                "HomeW400Rounded.svg",
            )
            .forEach { File(libDir, it).writeText(materialSvg) }

        val results =
            generator.generateLibrary(
                libraryId = "material-symbols",
                configs = configs,
                libraryTempDir = libDir,
                outputDirectory = outDir,
                nameTransformer = NameTransformerFactory.pascalCase(),
                scaleFactor = 1.0,
            )

        // 3 symbol sets: Outlined (3 weights), OutlinedFill (1), Rounded (1)
        assertEquals(
            listOf("HomeOutlined", "HomeOutlinedFill", "HomeRounded"),
            results.map { it.symbolSetName },
        )

        val outlinedSvg = File(outDir, "HomeOutlined.symbolset/HomeOutlined.svg").readText()
        assertTrue(outlinedSvg.contains("Template v.2.0"))

        val contents = File(outDir, "HomeOutlined.symbolset/Contents.json").readText()
        assertTrue(contents.contains("\"filename\" : \"HomeOutlined.svg\""))
        assertTrue(contents.contains("\"idiom\" : \"universal\""))
    }

    @Test
    fun `external icons produce one Regular symbol set each`() {
        val tempDir = createTempDirectory("symbolset-ext").toFile()
        val outDir = File(tempDir, "out")
        val libDir = File(tempDir, "external-bootstrap-icons").apply { mkdirs() }

        val configs =
            mapOf(
                "bell" to
                    listOf(
                        ExternalIconConfig(
                            libraryName = "bootstrap-icons",
                            urlTemplate = "https://esm.sh/bootstrap-icons/fill/{name}.svg",
                        )
                    )
            )

        // ExternalIconConfig.getSignature() falls back to the library name
        val signature =
            ExternalIconConfig(
                    libraryName = "bootstrap-icons",
                    urlTemplate = "https://esm.sh/bootstrap-icons/fill/{name}.svg",
                )
                .getSignature()
        File(libDir, "Bell$signature.svg").writeText(materialSvg)

        val results =
            generator.generateLibrary(
                libraryId = "external-bootstrap-icons",
                configs = configs,
                libraryTempDir = libDir,
                outputDirectory = outDir,
                nameTransformer = NameTransformerFactory.pascalCase(),
                scaleFactor = 1.0,
            )

        assertEquals(1, results.size)
        val svg =
            File(outDir, "${results[0].symbolSetName}.symbolset/${results[0].symbolSetName}.svg")
                .readText()
        assertTrue(svg.contains("id=\"Regular-M\""))
    }

    @Test
    fun `local icons use signature-only temp file names`() {
        val tempDir = createTempDirectory("symbolset-local").toFile()
        val outDir = File(tempDir, "out")
        val libDir = File(tempDir, "local-test").apply { mkdirs() }

        val config =
            LocalIconConfig(
                libraryName = "local-test",
                absolutePath = File(libDir, "source.svg").absolutePath,
                relativePath = "brand/phone-icon",
            )
        val configs = mapOf("phone-icon" to listOf(config))

        // Download phase names local temp files by sanitized signature only (no icon prefix)
        File(libDir, "BrandPhoneIcon.svg").writeText(materialSvg)

        val results =
            generator.generateLibrary(
                libraryId = "local-test",
                configs = configs,
                libraryTempDir = libDir,
                outputDirectory = outDir,
                nameTransformer = NameTransformerFactory.pascalCase(),
                scaleFactor = 1.0,
            )

        assertEquals(listOf("BrandPhoneIcon"), results.map { it.symbolSetName })
    }

    // ========== Swift enum ==========

    @Test
    fun `generateSwiftEnumFile emits valid Swift`() {
        val tempDir = createTempDirectory("symbolset-swift").toFile()
        val file =
            generator.generateSwiftEnumFile(listOf("HomeOutlined", "3dRotation", "repeat"), tempDir)
        val content = file.readText()

        assertTrue(content.startsWith("// Generated by SymbolCraft\nimport SwiftUI"))
        assertTrue(content.contains("public enum GeneratedSymbol: String, CaseIterable {"))
        assertTrue(content.contains("case homeOutlined"))
        assertTrue(content.contains("case _3dRotation = \"3dRotation\""))
        assertTrue(content.contains("case `repeat` = \"repeat\""))
        assertTrue(content.contains("public var image: Image { Image(rawValue) }"))
        assertTrue(content.contains("init(symbol: GeneratedSymbol)"))
    }

    @Test
    fun `parseSvg preserves fill-rule and clip-rule`() {
        val file =
            writeSvg(
                "fr.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                    <path d="M0 0h24v24H0z" fill-rule="evenodd" clip-rule="evenodd"/>
                </svg>""",
            )
        val parsed = generator.parseSvg(file)

        assertEquals("evenodd", parsed.paths[0].fillRule)
        assertEquals("evenodd", parsed.paths[0].clipRule)
    }

    @Test
    fun `generated symbol set svg emits fill-rule attributes`() {
        val file =
            writeSvg(
                "fr.svg",
                """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
                    <path d="M0 0h24v24H0z" fill-rule="evenodd"/>
                </svg>""",
            )
        val svg =
            generator.buildSymbolSetSvg("Test", mapOf("Regular" to generator.parseSvg(file)), 1.0)

        assertTrue(svg.contains("<path d=\"M0 0h24v24H0z\" fill-rule=\"evenodd\"/>"))
    }

    @Test
    fun `buildSymbolSetSvg rejects non-positive scaleFactor`() {
        assertThrows(IllegalArgumentException::class.java) {
            generator.buildSymbolSetSvg("Test", mapOf("Regular" to parsedMaterial()), 0.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            generator.buildSymbolSetSvg("Test", mapOf("Regular" to parsedMaterial()), -1.5)
        }
    }

    @Test
    fun `generateSwiftEnumFile rejects non-positive scaleFactor`() {
        val tempDir = createTempDirectory("symbolset-swift").toFile()
        assertThrows(IllegalArgumentException::class.java) {
            generator.generateSwiftEnumFile(listOf("Home"), tempDir, -1.0)
        }
    }

    @Test
    fun `generateSwiftEnumFile creates missing output directories`() {
        val dir = File(createTempDirectory("symbolset-swift").toFile(), "nested/Sources")
        val file = generator.generateSwiftEnumFile(listOf("Home"), dir)

        assertTrue(file.exists())
        assertTrue(file.readText().contains("case home"))
    }

    @Test
    fun `generateSwiftEnumFile dedupes colliding and reserved case names`() {
        val tempDir = createTempDirectory("symbolset-swift").toFile()
        val file =
            generator.generateSwiftEnumFile(
                listOf("Foo-Bar", "FooBar", "PointScale", "AllCases"),
                tempDir,
            )
        val content = file.readText()

        // Sorted order: AllCases, Foo-Bar, FooBar, PointScale — first claim wins the base name.
        assertTrue(content.contains("case allCases2 = \"AllCases\""))
        assertTrue(content.contains("case fooBar = \"Foo-Bar\""))
        assertTrue(content.contains("case fooBar2 = \"FooBar\""))
        assertTrue(content.contains("case pointScale2 = \"PointScale\""))
    }

    @Test
    fun `generateLibrary fails fast on duplicate symbol set names`() {
        val tempDir = createTempDirectory("symbolset-dup").toFile()
        val libDir = File(tempDir, "test").apply { mkdirs() }
        val config =
            ExternalIconConfig(libraryName = "test", urlTemplate = "https://example.com/{name}.svg")
        // "a b" and "a-b" sanitize to the same asset name.
        val configs = mapOf("a b" to listOf(config), "a-b" to listOf(config))

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                generator.generateLibrary(
                    libraryId = "test",
                    configs = configs,
                    libraryTempDir = libDir,
                    outputDirectory = File(tempDir, "out"),
                    nameTransformer = NameTransformerFactory.pascalCase(),
                    scaleFactor = 1.0,
                )
            }
        assertTrue(error.message.orEmpty().contains("Duplicate symbol set names"))
    }

    @Test
    fun `generateSwiftEnumFile emits pointScale and box-size helper`() {
        val tempDir = createTempDirectory("symbolset-swift").toFile()
        val file = generator.generateSwiftEnumFile(listOf("HomeOutlined"), tempDir)
        val content = file.readText()

        // 1 / (1.7 × 0.7 × 1.0) ≈ 0.84 — the box->font multiplier for default scaling.
        assertTrue(content.contains("static var pointScale: CGFloat { 0.8403361344537815 }"))
        assertTrue(content.contains("func image(boxSize: CGFloat) -> some View"))
        assertTrue(content.contains(".font(.system(size: boxSize * GeneratedSymbol.pointScale))"))
    }

    @Test
    fun `generateSwiftEnumFile bakes the configured scaleFactor into pointScale`() {
        val tempDir = createTempDirectory("symbolset-swift").toFile()
        val file =
            generator.generateSwiftEnumFile(listOf("HomeOutlined"), tempDir, scaleFactor = 2.0)
        val content = file.readText()

        // 1 / (1.7 × 0.7 × 2.0) ≈ 0.42 — larger glyphs need a smaller font for the same box.
        assertTrue(content.contains("static var pointScale: CGFloat { 0.42016806722689076 }"))
        assertTrue(content.contains("1 / (1.7 × 0.7 × 2.0)"))
    }

    // ========== Helpers ==========

    private fun writeSvg(name: String, content: String): File {
        val dir = createTempDirectory("symbolset-parse").toFile()
        return File(dir, name).apply { writeText(content) }
    }

    private fun parsedMaterial() = generator.parseSvg(writeSvg("m.svg", materialSvg))

    private fun buildSingleGlyphSvg(): String {
        val glyphs = mapOf("Regular" to parsedMaterial())
        return generator.buildSymbolSetSvg("Test", glyphs, 1.0)
    }

    private fun extractTransform(svg: String, variantId: String): Triple<Double, Double, Double> {
        val regex =
            Regex(
                "<g id=\"$variantId\" transform=\"matrix\\(([\\d.-]+) 0 0 ([\\d.-]+) ([\\d.-]+) ([\\d.-]+)\\)\">"
            )
        val match = regex.find(svg) ?: fail("transform for $variantId not found")
        assertEquals(match.groupValues[1], match.groupValues[2], "non-uniform scale")
        return Triple(
            match.groupValues[1].toDouble(),
            match.groupValues[3].toDouble(),
            match.groupValues[4].toDouble(),
        )
    }

    private fun extractLineX(svg: String, lineId: String): Double {
        val regex = Regex("<line id=\"$lineId\"[^>]*x1=\"([\\d.-]+)\"")
        val match = regex.find(svg) ?: fail("line $lineId not found")
        return match.groupValues[1].toDouble()
    }
}
