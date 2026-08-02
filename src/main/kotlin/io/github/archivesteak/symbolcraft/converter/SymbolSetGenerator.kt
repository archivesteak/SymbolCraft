package io.github.archivesteak.symbolcraft.converter

import io.github.archivesteak.symbolcraft.SymbolCraftDefaults
import io.github.archivesteak.symbolcraft.model.IconConfig
import io.github.archivesteak.symbolcraft.model.MaterialSymbolsConfig
import io.github.archivesteak.symbolcraft.model.SymbolWeight
import io.github.archivesteak.symbolcraft.model.tempSvgFileName
import java.io.File
import java.util.Locale

/**
 * Generates custom SF Symbol `.symbolset` bundles from downloaded SVG files.
 *
 * The emitted SVG follows Apple's "Template v.2.0" structure (3300×2200 canvas with `Notes`,
 * `Guides` and `Symbols` groups), the same structure produced by the SF Symbols app's classic
 * export and accepted by Xcode's asset catalog importer. All 27 weight/scale variant groups are
 * emitted: variants backed by a real downloaded SVG (e.g. Material Symbols weights) use the actual
 * glyph outlines, while missing weights are derived from the nearest available glyph using Apple's
 * relative weight sizing, and missing scales follow the cap-height ratio. This mirrors the
 * behaviour of proven generators (EvanBacon/create-symbol, Cookpad's converter).
 *
 * The class is intentionally free of Gradle types so it can be unit-tested in isolation.
 */
class SymbolSetGenerator(private val logger: (String) -> Unit = {}) {

    /** A generated `.symbolset` bundle. */
    data class SymbolSetResult(val symbolSetName: String, val directory: File)

    internal data class ParsedSvg(val viewBox: ViewBox, val paths: List<SvgPath>)

    /** One `<path>` element: geometry plus the fill attributes that affect rendering. */
    internal data class SvgPath(val d: String, val fillRule: String?, val clipRule: String?)

    internal data class ViewBox(
        val x: Double,
        val y: Double,
        val width: Double,
        val height: Double,
    )

    /** One glyph source: the SF weight column it natively belongs to plus its SVG file. */
    private data class GlyphSource(val sfWeight: String, val svgFile: File)

    private data class SymbolSetSpec(val name: String, val glyphs: List<GlyphSource>)

    /**
     * Generates `.symbolset` bundles for one icon library.
     *
     * @param libraryId identifier of the library being processed (used for logging only)
     * @param configs icon configurations of this library, keyed by icon name
     * @param libraryTempDir directory holding the downloaded SVGs for this library
     * @param outputDirectory root directory where `.symbolset` folders are written
     * @param nameTransformer naming transformer shared with the Kotlin generator
     * @param scaleFactor user multiplier on top of the default cap-height fit
     * @return generated bundles, sorted by symbol set name for deterministic downstream output
     */
    fun generateLibrary(
        libraryId: String,
        configs: Map<String, List<IconConfig>>,
        libraryTempDir: File,
        outputDirectory: File,
        nameTransformer: IconNameTransformer,
        scaleFactor: Double,
    ): List<SymbolSetResult> {
        val specs = buildSpecs(configs, libraryTempDir, nameTransformer)

        // Two configs can sanitize to the same asset name ("a b" vs "a-b"); the second bundle
        // would silently overwrite the first on disk. Fail fast with an actionable message.
        val duplicates = specs.groupingBy { it.name }.eachCount().filterValues { it > 1 }
        require(duplicates.isEmpty()) {
            "Duplicate symbol set names in library $libraryId: ${duplicates.keys.joinToString()}. " +
                "Rename the colliding icons or adjust the naming configuration."
        }

        val results = mutableListOf<SymbolSetResult>()

        specs.forEach { spec ->
            val dir = File(outputDirectory, "${spec.name}.symbolset")
            dir.mkdirs()

            val glyphSvgs =
                spec.glyphs.associate { glyph -> glyph.sfWeight to parseSvg(glyph.svgFile) }

            val svgContent = buildSymbolSetSvg(spec.name, glyphSvgs, scaleFactor)
            File(dir, "${spec.name}.svg").writeText(svgContent)
            File(dir, "Contents.json").writeText(buildContentsJson(spec.name))

            results += SymbolSetResult(spec.name, dir)
            logger("      Generated ${spec.name}.symbolset (${glyphSvgs.size} real glyph(s))")
        }

        return results.sortedBy { it.symbolSetName }
    }

    /**
     * Builds the symbol set specifications for a library.
     *
     * Material Symbols configurations are grouped by (variant, fill): every group becomes one
     * symbol set whose weights map to genuine SF Symbols weight columns. External and local
     * configurations produce one symbol set per icon, treated as a Regular-weight glyph.
     */
    private fun buildSpecs(
        configs: Map<String, List<IconConfig>>,
        libraryTempDir: File,
        nameTransformer: IconNameTransformer,
    ): List<SymbolSetSpec> {
        val specs = mutableListOf<SymbolSetSpec>()

        val materialEntries = mutableListOf<Pair<String, MaterialSymbolsConfig>>()
        val genericEntries = mutableListOf<Pair<String, IconConfig>>()

        configs.forEach { (iconName, iconConfigs) ->
            iconConfigs.forEach { config ->
                when (config) {
                    is MaterialSymbolsConfig -> materialEntries += iconName to config
                    else -> genericEntries += iconName to config
                }
            }
        }

        // Material Symbols: group by (iconName, variant, fill) -> one symbol set per glyph style.
        materialEntries
            .groupBy { (iconName, config) -> Triple(iconName, config.variant, config.fill) }
            .toSortedMap(compareBy({ it.first }, { it.second.name }, { it.third.name }))
            .forEach { (key, entries) ->
                val (iconName, variant, fill) = key
                val base = iconName.replaceFirstChar { it.titlecase() }
                val rawName = "$base${variant.shortName}${fill.signatureSuffix}"
                val name = sanitizeAssetName(nameTransformer.transform(rawName))

                val glyphs =
                    entries
                        // A weight may appear multiple times (different grades); prefer grade 0.
                        .groupBy { it.second.weight }
                        .map { (weight, weightEntries) ->
                            val chosen =
                                weightEntries.minWith(
                                    compareBy(
                                        { kotlin.math.abs(it.second.grade) },
                                        { it.second.opticalSize },
                                    )
                                )
                            if (weightEntries.size > 1) {
                                logger(
                                    "      Warning: multiple grade/optical-size configs for " +
                                        "${iconName} W${weight.value} ${variant.pathName}; " +
                                        "using grade ${chosen.second.grade}"
                                )
                            }
                            GlyphSource(
                                sfWeight = materialToSfWeight(weight),
                                svgFile =
                                    File(libraryTempDir, tempSvgFileName(iconName, chosen.second)),
                            )
                        }
                        .sortedBy { WEIGHTS.indexOf(it.sfWeight) }

                specs += SymbolSetSpec(name, glyphs)
            }

        // External/local icons: one symbol set per icon config, Regular weight.
        genericEntries.forEach { (iconName, config) ->
            val fileName = tempSvgFileName(iconName, config)
            val name = sanitizeAssetName(nameTransformer.transform(fileName.removeSuffix(".svg")))
            specs +=
                SymbolSetSpec(
                    name = name,
                    glyphs = listOf(GlyphSource("Regular", File(libraryTempDir, fileName))),
                )
        }

        return specs.sortedBy { it.name }
    }

    /** Builds the complete `.symbolset` SVG document. */
    internal fun buildSymbolSetSvg(
        symbolName: String,
        glyphs: Map<String, ParsedSvg>,
        scaleFactor: Double,
    ): String {
        require(glyphs.isNotEmpty()) { "At least one glyph source is required" }
        require(scaleFactor.isFinite() && scaleFactor > 0.0) {
            "scaleFactor must be a positive finite number, was: $scaleFactor"
        }

        // Base scale: fit the glyph viewBox to the M-scale cap height, then apply the proven
        // 1.7× optical enlargement (both reference converters use it) and the user factor.
        val referenceGlyph = glyphs.getValue(nearestAvailableWeight(glyphs.keys, "Regular"))
        val baseScale =
            (CAP_HEIGHT_M / referenceGlyph.viewBox.height) * OPTICAL_SCALING * scaleFactor

        val variants = StringBuilder()
        WEIGHTS.forEachIndexed { weightIndex, weight ->
            val sourceWeight = nearestAvailableWeight(glyphs.keys, weight)
            val source = glyphs.getValue(sourceWeight)
            // Heavier SF weights are drawn slightly larger; real glyphs already encode their
            // own sizing, so only derived variants get the relative adjustment.
            val weightRatio = WEIGHT_SCALES.getValue(weight) / WEIGHT_SCALES.getValue(sourceWeight)

            SCALES.forEach { scale ->
                val guide = GUIDES.getValue(scale)
                val capHeight = guide.baseline - guide.capline
                val scaleRatio = capHeight / CAP_HEIGHT_M
                val finalScale = baseScale * weightRatio * scaleRatio

                val scaledWidth = source.viewBox.width * finalScale
                val scaledHeight = source.viewBox.height * finalScale

                val centerX = REGULAR_COLUMN_CENTER + (weightIndex - REGULAR_INDEX) * WEIGHT_SPACING
                val x = centerX - scaledWidth / 2
                val midY = (guide.baseline + guide.capline) / 2
                val y = midY - scaledHeight / 2

                val tx = x - source.viewBox.x * finalScale
                val ty = y - source.viewBox.y * finalScale

                variants.append(
                    "  <g id=\"$weight-$scale\" transform=\"matrix(${fmt(finalScale)} 0 0 " +
                        "${fmt(finalScale)} ${fmt(tx)} ${fmt(ty)})\">\n"
                )
                source.paths.forEach { path ->
                    val fillRuleAttr = path.fillRule?.let { " fill-rule=\"$it\"" } ?: ""
                    val clipRuleAttr = path.clipRule?.let { " clip-rule=\"$it\"" } ?: ""
                    variants.append("   <path d=\"${path.d}\"$fillRuleAttr$clipRuleAttr/>\n")
                }
                variants.append("  </g>\n")
            }
        }

        // Margins follow the Regular column width (Cookpad/create-symbol algorithm).
        val regularWeightRatio =
            WEIGHT_SCALES.getValue("Regular") /
                WEIGHT_SCALES.getValue(nearestAvailableWeight(glyphs.keys, "Regular"))
        val regularWidth = referenceGlyph.viewBox.width * baseScale * regularWeightRatio
        val leftMargin = REGULAR_COLUMN_CENTER - regularWidth / 2 - MARGIN_PADDING
        val rightMargin = REGULAR_COLUMN_CENTER + regularWidth / 2 + MARGIN_PADDING

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append(
                "<svg version=\"1.1\" xmlns=\"http://www.w3.org/2000/svg\" " +
                    "width=\"$TEMPLATE_WIDTH\" height=\"$TEMPLATE_HEIGHT\" " +
                    "viewBox=\"0 0 $TEMPLATE_WIDTH $TEMPLATE_HEIGHT\">\n"
            )
            append(buildNotes(symbolName))
            append(buildGuides(leftMargin, rightMargin))
            append(" <g id=\"Symbols\">\n")
            append(variants)
            append(" </g>\n")
            append("</svg>\n")
        }
    }

    private fun buildNotes(symbolName: String): String = buildString {
        append(" <g id=\"Notes\">\n")
        append(
            "  <rect height=\"$TEMPLATE_HEIGHT\" id=\"artboard\" " +
                "style=\"fill:white;opacity:1\" width=\"$TEMPLATE_WIDTH\" x=\"0\" y=\"0\"/>\n"
        )
        append(
            "  <text id=\"template-version\" " +
                "style=\"stroke:none;fill:black;font-family:sans-serif;font-size:13;text-anchor:end;\" " +
                "transform=\"matrix(1 0 0 1 3036 1933)\">Template v.2.0</text>\n"
        )
        append(
            "  <text id=\"descriptive-name\" " +
                "style=\"stroke:none;fill:black;font-family:sans-serif;font-size:13;text-anchor:end;\" " +
                "transform=\"matrix(1 0 0 1 3036 1953)\">$symbolName</text>\n"
        )
        WEIGHTS.forEachIndexed { index, weight ->
            val x = REGULAR_COLUMN_CENTER + (index - REGULAR_INDEX) * WEIGHT_SPACING
            append(
                "  <text style=\"stroke:none;fill:black;font-family:sans-serif;font-size:13;" +
                    "text-anchor:middle;\" transform=\"matrix(1 0 0 1 ${fmt(x)} 322)\">$weight</text>\n"
            )
        }
        SCALES.forEach { scale ->
            val guide = GUIDES.getValue(scale)
            val midY = (guide.baseline + guide.capline) / 2
            append(
                "  <text style=\"stroke:none;fill:black;font-family:sans-serif;font-size:13;" +
                    "text-anchor:middle;\" transform=\"matrix(1 0 0 1 200 ${fmt(midY)})\">$scale</text>\n"
            )
        }
        append(" </g>\n")
    }

    private fun buildGuides(leftMargin: Double, rightMargin: Double): String = buildString {
        append(" <g id=\"Guides\">\n")
        SCALES.forEach { scale ->
            val guide = GUIDES.getValue(scale)
            val style = "style=\"fill:none;stroke:#27AAE1;opacity:1;stroke-width:0.5;\""
            append(
                "  <line id=\"Baseline-$scale\" $style x1=\"263\" x2=\"3036\" " +
                    "y1=\"${fmt(guide.baseline)}\" y2=\"${fmt(guide.baseline)}\"/>\n"
            )
            append(
                "  <line id=\"Capline-$scale\" $style x1=\"263\" x2=\"3036\" " +
                    "y1=\"${fmt(guide.capline)}\" y2=\"${fmt(guide.capline)}\"/>\n"
            )
        }
        val marginStyle = "style=\"fill:none;stroke:#00AEEF;stroke-width:0.5;opacity:1.0;\""
        append(
            "  <line id=\"left-margin\" $marginStyle x1=\"${fmt(leftMargin)}\" " +
                "x2=\"${fmt(leftMargin)}\" y1=\"0\" y2=\"$TEMPLATE_HEIGHT\"/>\n"
        )
        append(
            "  <line id=\"right-margin\" $marginStyle x1=\"${fmt(rightMargin)}\" " +
                "x2=\"${fmt(rightMargin)}\" y1=\"0\" y2=\"$TEMPLATE_HEIGHT\"/>\n"
        )
        append(" </g>\n")
    }

    private fun buildContentsJson(symbolName: String): String =
        """
        {
          "info" : {
            "author" : "xcode",
            "version" : 1
          },
          "symbols" : [
            {
              "filename" : "$symbolName.svg",
              "idiom" : "universal"
            }
          ]
        }
        """
            .trimIndent() + "\n"

    /**
     * Generates the `Symbols.swift` helper enum covering all generated symbol sets.
     *
     * @param scaleFactor the configured SwiftUI scale factor; baked into `pointScale` so the
     *   box-size helper stays accurate when glyphs are scaled up or down.
     */
    fun generateSwiftEnumFile(
        symbolSetNames: List<String>,
        outputDirectory: File,
        scaleFactor: Double = 1.0,
    ): File {
        require(scaleFactor.isFinite() && scaleFactor > 0.0) {
            "scaleFactor must be a positive finite number, was: $scaleFactor"
        }

        // Case names must be unique AND must not shadow the helper members below
        // (pointScale, image, rawValue) or CaseIterable's synthesized allCases. Collisions get a
        // deterministic numeric suffix; the raw value always keeps the true asset name.
        val usedCaseNames = RESERVED_SWIFT_MEMBERS.toMutableSet()
        val cases =
            symbolSetNames.sorted().joinToString("\n") { name ->
                val base = swiftCaseName(name)
                var caseName = base
                var suffix = 2
                while (!usedCaseNames.add(caseName)) {
                    caseName = "$base${suffix++}"
                }
                if (caseName == name) "    case $caseName" else "    case $caseName = \"$name\""
            }

        // A `.symbolset` glyph sizes with the FONT point size, not with a fixed box. At font
        // size P the M-row capline->baseline band maps to the font cap height (≈0.7 em for SF
        // Pro), and our glyphs span OPTICAL_SCALING × scaleFactor × that band, so the artwork
        // box is P × 0.7 × 1.7 × scaleFactor. Inverting gives the box->font multiplier.
        val pointScale = 1.0 / (OPTICAL_SCALING * CAP_HEIGHT_TO_EM_RATIO * scaleFactor)

        val content = buildString {
            append(SymbolCraftDefaults.GENERATED_FILE_HEADER + "\n")
            append("import SwiftUI\n\n")
            append("/// Custom SF Symbols generated by SymbolCraft.\n")
            append("public enum GeneratedSymbol: String, CaseIterable {\n")
            append(cases)
            append("\n\n")
            append("    /// The symbol as a SwiftUI `Image`.\n")
            append("    public var image: Image { Image(rawValue) }\n")
            append("}\n\n")
            append("public extension Image {\n")
            append("    /// Creates an image from a SymbolCraft-generated symbol.\n")
            append("    init(symbol: GeneratedSymbol) {\n")
            append("        self.init(symbol.rawValue)\n")
            append("    }\n")
            append("}\n\n")
            append("public extension GeneratedSymbol {\n")
            append("    /// Multiplier converting an artwork box size (points) to the font point\n")
            append("    /// size that renders the symbol at exactly that visual size.\n")
            append("    /// Derived from the template geometry: 1 / (1.7 × 0.7 × $scaleFactor).\n")
            append("    static var pointScale: CGFloat { $pointScale }\n\n")
            append(
                "    /// The symbol rendered so its artwork fills a square of `boxSize` points,\n"
            )
            append("    /// independent of the surrounding font size.\n")
            append("    func image(boxSize: CGFloat) -> some View {\n")
            append("        Image(symbol: self)\n")
            append("            .font(.system(size: boxSize * GeneratedSymbol.pointScale))\n")
            append("    }\n")
            append("}\n")
        }

        val file = File(outputDirectory, "Symbols.swift")
        outputDirectory.mkdirs()
        file.writeText(content)
        return file
    }

    /** Parses the viewBox and path data of an SVG file. Only `<path>` shapes are supported. */
    internal fun parseSvg(svgFile: File): ParsedSvg {
        val content = svgFile.readText()

        val viewBox =
            VIEW_BOX_REGEX.find(content)?.let { match ->
                val parts = match.groupValues[1].split(Regex("[\\s,]+")).filter { it.isNotBlank() }
                if (parts.size == 4) {
                    ViewBox(
                        parts[0].toDouble(),
                        parts[1].toDouble(),
                        parts[2].toDouble(),
                        parts[3].toDouble(),
                    )
                } else null
            }
                ?: run {
                    val width = WIDTH_REGEX.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
                    val height = HEIGHT_REGEX.find(content)?.groupValues?.get(1)?.toDoubleOrNull()
                    ViewBox(0.0, 0.0, width ?: 24.0, height ?: 24.0)
                }

        val paths =
            PATH_TAG_REGEX.findAll(content)
                .map { match ->
                    val tag = match.groupValues[1]
                    if (TRANSFORM_ATTR_REGEX.containsMatchIn(tag)) {
                        logger(
                            "      Warning: <path> in ${svgFile.name} carries a transform attribute; " +
                                "it is ignored and the glyph may render shifted"
                        )
                    }
                    SvgPath(
                        d = PATH_D_REGEX.find(tag)?.groupValues?.get(1) ?: return@map null,
                        fillRule = FILL_RULE_REGEX.find(tag)?.groupValues?.get(1),
                        clipRule = CLIP_RULE_REGEX.find(tag)?.groupValues?.get(1),
                    )
                }
                .filterNotNull()
                .toList()
        require(paths.isNotEmpty()) {
            "No <path> elements found in SVG: ${svgFile.name}. " +
                "Only path-based SVGs can be converted to SF Symbols."
        }

        return ParsedSvg(viewBox, paths)
    }

    /** Finds the closest available SF weight column to [target]. */
    private fun nearestAvailableWeight(available: Set<String>, target: String): String {
        val targetIndex = WEIGHTS.indexOf(target)
        return available.minBy { kotlin.math.abs(WEIGHTS.indexOf(it) - targetIndex) }
    }

    /** Converts an asset name into a valid, deterministic symbol set name. */
    internal fun sanitizeAssetName(name: String): String {
        return name
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "Symbol" }
    }

    /** Converts a symbol set name into a valid Swift enum case name (lowerCamelCase). */
    internal fun swiftCaseName(assetName: String): String {
        val words = assetName.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
        var name =
            if (words.isEmpty()) {
                "symbol"
            } else {
                words.first().replaceFirstChar { it.lowercase() } +
                    words.drop(1).joinToString("") { word ->
                        word.replaceFirstChar { it.titlecase() }
                    }
            }
        if (name.first().isDigit()) name = "_$name"
        if (name in SWIFT_KEYWORDS) name = "`$name`"
        return name
    }

    /** Deterministic float formatting: US locale, 4 decimals max, trailing zeros trimmed. */
    private fun fmt(value: Double): String {
        val formatted = String.format(Locale.US, "%.4f", value)
        val trimmed = formatted.trimEnd('0').trimEnd('.')
        return if (trimmed == "-0") "0" else trimmed
    }

    private data class Guide(val baseline: Double, val capline: Double)

    private companion object {
        // Template canvas (Apple template v2.0, verified against real SF Symbols exports).
        const val TEMPLATE_WIDTH = 3300
        const val TEMPLATE_HEIGHT = 2200

        // Weight columns are centered around Regular; spacing verified against real exports.
        const val REGULAR_COLUMN_CENTER = 1650.0
        const val REGULAR_INDEX = 3
        const val WEIGHT_SPACING = 296.71

        // Optical enlargement shared by both reference converters (Cookpad, create-symbol).
        const val OPTICAL_SCALING = 1.7

        // SF Pro cap-height-to-em ratio; the M-row capline->baseline band maps to the font cap
        // height at render time, which is what ties .symbolset glyph size to the font point size.
        const val CAP_HEIGHT_TO_EM_RATIO = 0.7

        // Enum members generated alongside the icon cases; a colliding icon name gets suffixed.
        val RESERVED_SWIFT_MEMBERS = setOf("pointScale", "allCases", "image", "rawValue")

        const val MARGIN_PADDING = 4.5

        val WEIGHTS =
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

        val SCALES = listOf("S", "M", "L")

        // Guide positions from Apple's template, cross-checked with a Template v.5.0 export.
        val GUIDES =
            mapOf(
                "S" to Guide(baseline = 696.0, capline = 625.541),
                "M" to Guide(baseline = 1126.0, capline = 1055.54),
                "L" to Guide(baseline = 1556.0, capline = 1485.54),
            )

        val CAP_HEIGHT_M = GUIDES.getValue("M").baseline - GUIDES.getValue("M").capline

        // Relative visual sizing of SF weight columns (from create-symbol).
        val WEIGHT_SCALES =
            mapOf(
                "Ultralight" to 0.775,
                "Thin" to 0.805,
                "Light" to 0.835,
                "Regular" to 0.865,
                "Medium" to 0.895,
                "Semibold" to 0.925,
                "Bold" to 0.955,
                "Heavy" to 0.985,
                "Black" to 1.015,
            )

        val VIEW_BOX_REGEX = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']")
        val WIDTH_REGEX = Regex("<svg[^>]*\\bwidth\\s*=\\s*[\"']([^\"']+)[\"']")
        val HEIGHT_REGEX = Regex("<svg[^>]*\\bheight\\s*=\\s*[\"']([^\"']+)[\"']")

        // Path parsing keeps the whole opening tag so fill/clip rules survive; only the geometry
        // attribute `d` is mandatory. Both quote styles are accepted.
        val PATH_TAG_REGEX = Regex("<path\\b([^>]*?)/?\\s*>")
        val PATH_D_REGEX = Regex("\\bd\\s*=\\s*[\"']([^\"']*)[\"']")
        val FILL_RULE_REGEX = Regex("\\bfill-rule\\s*=\\s*[\"']([^\"']+)[\"']")
        val CLIP_RULE_REGEX = Regex("\\bclip-rule\\s*=\\s*[\"']([^\"']+)[\"']")
        val TRANSFORM_ATTR_REGEX = Regex("\\btransform\\s*=")

        val SWIFT_KEYWORDS =
            setOf(
                "associatedtype",
                "class",
                "deinit",
                "enum",
                "extension",
                "fileprivate",
                "func",
                "import",
                "init",
                "inout",
                "internal",
                "let",
                "open",
                "operator",
                "private",
                "precedencegroup",
                "protocol",
                "public",
                "rethrows",
                "static",
                "struct",
                "subscript",
                "typealias",
                "var",
                "break",
                "case",
                "catch",
                "continue",
                "default",
                "defer",
                "do",
                "else",
                "fallthrough",
                "for",
                "guard",
                "if",
                "in",
                "repeat",
                "return",
                "throw",
                "switch",
                "where",
                "while",
                "as",
                "any",
                "false",
                "is",
                "nil",
                "self",
                "Self",
                "super",
                "throws",
                "true",
                "try",
            )

        fun materialToSfWeight(weight: SymbolWeight): String =
            when (weight) {
                SymbolWeight.W100 -> "Ultralight"
                SymbolWeight.W200 -> "Thin"
                SymbolWeight.W300 -> "Light"
                SymbolWeight.W400 -> "Regular"
                SymbolWeight.W500 -> "Medium"
                SymbolWeight.W600 -> "Semibold"
                SymbolWeight.W700 -> "Bold"
            }
    }
}
