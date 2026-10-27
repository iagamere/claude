package com.abdo.ps4monitor

/*
 * Design DNA, pure Kotlin (no android.* / compose imports) so it can be unit-tested on a plain JVM.
 *
 * A theme is no longer "9 colours". It is a stack of independent layers:
 *   colours (a dark set + an optional hand-made light set), shape, border, divider, surface, texture, ornaments,
 *   typography, spacing density, hero composition, tab style and progress style.
 * Components (DnaKit.kt) read the layers they need; a layer can be changed without touching the others.
 * The 'appearance' (light / dark / system) is NOT part of the DNA: it only picks which colour set is shown.
 */

enum class Corner { ROUND, CUT, SQUARE, SLANT }
enum class BorderKind { NONE, SINGLE, DOUBLE, BRACKETS, GLOW, FLOURISH }
enum class RuleKind { HAIRLINE, BRUSH, DIAMOND, GLOW, TECH, STRIPES, WAVE, PIXEL, PITCH }
enum class SurfaceKind { SOLID, GRADIENT, GLASS }
enum class TextureKind { NONE, PAPER, PARTICLES, GRID, DIAGONAL, HEX, STARS, PIXELS }
enum class OrnamentKind {
    SEIGAIHA_BAND, GINKGO, BRUSH_SWEEP, SPORES, LIGHT_RAYS,
    PITCH_LINES, SPEED_STRIPES, CHECKER_STRIP, HEX_GRID, CIRCUIT, SCRATCHES, HALFTONE, PIXEL_BLOCKS, STARFIELD, CONTOURS, WAVES
}
enum class HeroKind { FADE, FRAMED }
enum class TabKind { PILL, UNDERLINE, CUT, SLANT }
enum class ProgressKind { BAR, BRUSH, TICKS, GLOW, STRIPED }
/** DISPLAY = Bebas Neue, ROMAN = Cinzel, TECH = Orbitron, PIXEL = Press Start 2P (all SIL OFL, bundled); the others are system faces. */
enum class FontKind { DEFAULT, SANS, SERIF, CONDENSED, DISPLAY, ROMAN, TECH, PIXEL }

/** [scale] multiplies the nominal radius of rounded corners; [radius] is the fixed radius (dp) of square corners. */
data class ShapeDna(val corner: Corner = Corner.ROUND, val scale: Float = 1f, val radius: Int = 4)
data class BorderDna(val kind: BorderKind = BorderKind.SINGLE, val width: Float = 1f)
data class TextureDna(val kind: TextureKind = TextureKind.NONE, val intensity: Float = 0f, val onCards: Boolean = false)
/** [density] scales how many elements are drawn (hard-capped in the drawing code), [alpha] their opacity. */
data class OrnamentDna(val kind: OrnamentKind, val density: Float = 1f, val alpha: Float = 0.5f)
/** [tracking] is letter spacing in em (ignored for Arabic, where it would break the joined letters). */
data class TypeDna(val title: FontKind = FontKind.DEFAULT, val body: FontKind = FontKind.DEFAULT, val titleWeight: Int = 0, val tracking: Float = 0f)

data class Dna(
    val shape: ShapeDna = ShapeDna(),
    val border: BorderDna = BorderDna(),
    val rule: RuleKind = RuleKind.HAIRLINE,
    val surface: SurfaceKind = SurfaceKind.GRADIENT,
    val texture: TextureDna = TextureDna(),
    val ornaments: List<OrnamentDna> = emptyList(),
    val type: TypeDna = TypeDna(),
    val spacing: Float = 1f,
    val hero: HeroKind = HeroKind.FADE,
    val tabs: TabKind = TabKind.PILL,
    val progress: ProgressKind = ProgressKind.BAR,
) {
    /** Every layer as a flat list of "layer=value" strings: used to compare two designs structurally (never contains a colour). */
    fun signature(): List<String> = buildList {
        add("corner=${shape.corner}"); add("radius=${if (shape.corner == Corner.SQUARE) shape.radius else (shape.scale * 10).toInt()}")
        add("border=${border.kind}"); add("rule=$rule"); add("surface=$surface"); add("texture=${texture.kind}"); add("textureCards=${texture.onCards}")
        add("ornaments=${ornaments.map { it.kind }.sorted()}"); add("titleFont=${type.title}"); add("bodyFont=${type.body}")
        add("tracking=${type.tracking > 0.02f}"); add("spacing=${(spacing * 10).toInt()}"); add("hero=$hero"); add("tabs=$tabs"); add("progress=$progress")
    }
    companion object {
        val CLASSIC = Dna()
        /** Fraction of layers that differ (0 = identical structure, 1 = nothing in common). */
        fun distance(a: Dna, b: Dna): Double {
            val x = a.signature(); val y = b.signature()
            return x.indices.count { x[it] != y[it] }.toDouble() / x.size
        }
    }
}

/**
 * How much decoration a design may show: 0 minimal, 1 balanced (the default), 2 rich (exactly as designed). Only the AMOUNT of ornament changes
 * (fewer / fainter motifs, fainter textures, calmer borders); shape, tabs, fonts and the rest of the component language stay.
 */
fun Dna.restrained(level: Int): Dna {
    if (level >= 2) return this
    val minimal = level <= 0
    val orn = ornaments.take(if (minimal) 1 else 2).map { it.copy(alpha = it.alpha * (if (minimal) 0.55f else 0.8f), density = it.density * (if (minimal) 0.6f else 0.85f)) }
    val tx = texture.copy(intensity = texture.intensity * (if (minimal) 0.4f else 0.7f), onCards = !minimal && texture.onCards && texture.kind == TextureKind.PAPER)
    val bd = if (minimal) border.copy(kind = when (border.kind) { BorderKind.DOUBLE, BorderKind.BRACKETS, BorderKind.FLOURISH -> BorderKind.SINGLE; else -> border.kind }, width = minOf(border.width, 1f)) else border
    return copy(ornaments = orn, texture = tx, border = bd)
}

/** The nine colour roles of one appearance (ARGB ints). */
data class ColorSet(val bg: Int, val bg2: Int, val surface: Int, val accent: Int, val accent2: Int, val text: Int, val text2: Int, val border: Int, val progress: Int)

object Colors {
    private fun opaque(x: Int) = x or (0xFF shl 24)
    private fun darker(a: Int, b: Int) = if (ColorMath.luminance(a) <= ColorMath.luminance(b)) a else b
    private fun brighter(a: Int, b: Int) = if (ColorMath.luminance(a) > ColorMath.luminance(b)) a else b
    fun isLight(c: ColorSet) = ColorMath.luminance(c.bg) > 0.4

    /** Pushes text and accents (towards white on a dark UI, towards black on a light one) until they are readable on the surfaces they sit on. */
    fun readable(c: ColorSet): ColorSet {
        val bg = opaque(c.bg); val bg2 = opaque(c.bg2); val sf = opaque(c.surface)
        val lightUi = ColorMath.luminance(bg) > 0.5
        val worst = if (lightUi) darker(sf, bg) else brighter(sf, bg)
        fun fix(fg: Int, min: Double): Int {
            var x = opaque(fg); var n = 0
            while (ColorMath.contrast(x, worst) < min && n++ < 40) x = ColorMath.blend(x, if (lightUi) 0xFF000000.toInt() else 0xFFFFFFFF.toInt(), 0.08f)
            return x
        }
        return c.copy(bg = bg, bg2 = bg2, surface = sf, text = fix(c.text, 7.0), text2 = fix(c.text2, 4.5),
            accent = fix(c.accent, 3.0), accent2 = fix(c.accent2, 3.0), progress = fix(c.progress, 3.0), border = opaque(c.border))
    }

    /** A light appearance derived from a dark colour set: same hues, inverted lightness relationships. Used when a theme has no hand-made light set. */
    fun deriveLight(d: ColorSet): ColorSet {
        val hb = ColorMath.toHsl(d.bg); val h = hb[0]
        val s = (hb[1] * 0.8f).coerceIn(0.06f, 0.40f)
        fun tone(argb: Int, startL: Float, sat: Float?): Int {
            val hsl = ColorMath.toHsl(argb); val ss = sat ?: hsl[1]
            var l = startL; var x = ColorMath.fromHsl(hsl[0], ss, l)
            while (ColorMath.contrast(x, ColorMath.fromHsl(h, s * 0.6f, 0.985f)) < 4.5 && l > 0.05f) { l -= 0.02f; x = ColorMath.fromHsl(hsl[0], ss, l) }
            return x
        }
        val light = ColorSet(
            bg = ColorMath.fromHsl(h, s, 0.955f), bg2 = ColorMath.fromHsl(h, s, 0.925f), surface = ColorMath.fromHsl(h, s * 0.6f, 0.985f),
            accent = tone(d.accent, 0.46f, null), accent2 = tone(d.accent2, 0.42f, null),
            text = ColorMath.fromHsl(h, 0.30f, 0.09f), text2 = ColorMath.fromHsl(h, 0.20f, 0.33f),
            border = ColorMath.fromHsl(h, s * 0.7f, 0.80f), progress = tone(d.progress, 0.46f, null))
        return readable(light)
    }

    /** Human-readable list of the contrast rules a colour set breaks (empty = fine). Used by the editor warning and by the tests. */
    fun audit(c: ColorSet): List<String> = buildList {
        fun need(name: String, fg: Int, bg: Int, min: Double) { val r = ColorMath.contrast(fg or (0xFF shl 24), bg or (0xFF shl 24)); if (r < min) add("$name %.2f < %.1f".format(r, min)) }
        need("text/bg", c.text, c.bg, 7.0); need("text/surface", c.text, c.surface, 7.0)
        need("text2/bg", c.text2, c.bg, 4.5); need("text2/surface", c.text2, c.surface, 4.5)
        need("accent/bg", c.accent, c.bg, 3.0); need("accent/surface", c.accent, c.surface, 3.0)
        need("accent2/surface", c.accent2, c.surface, 3.0); need("progress/surface", c.progress, c.surface, 3.0)
        if (ColorMath.contrast(c.surface, c.bg) < 1.04 && ColorMath.contrast(c.border, c.surface) < 1.15) add("surface and border are indistinguishable from the background")
    }
}

/** A built-in theme: colours (dark + optional light) and the DNA, with no UI dependency. */
class PresetSpec(val id: String, val name: String, val dark: ColorSet, val light: ColorSet?, val dna: Dna)

object DnaPresets {
    private fun c(h: Long) = (0xFF000000L or h).toInt()
    private fun cs(bg: Long, bg2: Long, sf: Long, a: Long, a2: Long, tx: Long, tx2: Long, bd: Long, pr: Long = a) =
        ColorSet(c(bg), c(bg2), c(sf), c(a), c(a2), c(tx), c(tx2), c(bd), c(pr))
    private fun legacy(id: String, n: String, bg: Long, bg2: Long, sf: Long, a: Long, a2: Long, tx: Long, tx2: Long, bd: Long) =
        PresetSpec(id, n, cs(bg, bg2, sf, a, a2, tx, tx2, bd), null, Dna.CLASSIC)

    /** Ghost of Tsushima: ivory paper, sumi ink, muted gold. Square double-ruled cards, brush dividers, wide-tracked sans titles over serif text. */
    val GHOST_DNA = Dna(
        shape = ShapeDna(Corner.SQUARE, 1f, 3), border = BorderDna(BorderKind.DOUBLE, 0.8f), rule = RuleKind.BRUSH, surface = SurfaceKind.SOLID,
        texture = TextureDna(TextureKind.PAPER, 0.8f, onCards = true),
        ornaments = listOf(OrnamentDna(OrnamentKind.GINKGO, 0.8f, 0.6f), OrnamentDna(OrnamentKind.BRUSH_SWEEP, 1f, 0.55f), OrnamentDna(OrnamentKind.SEIGAIHA_BAND, 1f, 0.35f)),
        type = TypeDna(FontKind.SANS, FontKind.SERIF, 500, 0.06f), spacing = 1.12f, hero = HeroKind.FRAMED, tabs = TabKind.UNDERLINE, progress = ProgressKind.BRUSH)

    /** Hollow Knight: abyssal blue, cyan glow, pale bone. Large soft corners with a glowing hairline, translucent surfaces, drifting spores and light shafts. */
    val HOLLOW_DNA = Dna(
        shape = ShapeDna(Corner.ROUND, 1.3f, 4), border = BorderDna(BorderKind.GLOW, 1f), rule = RuleKind.GLOW, surface = SurfaceKind.GLASS,
        texture = TextureDna(TextureKind.PARTICLES, 0.7f, onCards = false),
        ornaments = listOf(OrnamentDna(OrnamentKind.LIGHT_RAYS, 1f, 0.14f), OrnamentDna(OrnamentKind.SPORES, 1f, 0.8f)),
        type = TypeDna(FontKind.SERIF, FontKind.DEFAULT, 700, 0.03f), spacing = 1f, hero = HeroKind.FADE, tabs = TabKind.PILL, progress = ProgressKind.GLOW)

    val DEFAULT = legacy("default", "PS4 Monitor", 0x0A1022, 0x0E1730, 0x141E3D, 0x7C6BFF, 0x4FC3F7, 0xF1F3FF, 0xA9B2D6, 0x2A3769)

    val GHOST = PresetSpec("ghost", "Ghost of Tsushima",
        dark = cs(0x131210, 0x1A1815, 0x23201B, 0xCFAE63, 0xE6DCC3, 0xF3ECDB, 0xB9AE94, 0x4D4533),
        light = cs(0xF4EEDF, 0xECE3CD, 0xFBF7EC, 0x7E6120, 0x3B3630, 0x1B1A17, 0x5A5242, 0xCDBF9B),
        dna = GHOST_DNA)

    val HOLLOW = PresetSpec("hollow", "Hollow Knight",
        dark = cs(0x050A1E, 0x0A1334, 0x111F4A, 0x62CDFF, 0xC6D6FF, 0xEAF2FF, 0xA3B6E4, 0x27407F),
        light = cs(0xE9EFFA, 0xDAE5F7, 0xF7FAFF, 0x1B4FB8, 0x0B6E96, 0x0A1230, 0x38477A, 0xB5C5E6),
        dna = HOLLOW_DNA)

    val ALL: List<PresetSpec> = listOf(
        DEFAULT, GHOST, HOLLOW,
        legacy("midnight", "Midnight", 0x0D0E10, 0x131519, 0x1B1E24, 0x9AA7B8, 0x6C7A8C, 0xEDEFF2, 0xA2A9B3, 0x2B2F37),
        legacy("aurora", "Aurora", 0x06141C, 0x0A1E29, 0x0F2B3A, 0x2DD4BF, 0x38BDF8, 0xE8F8FA, 0x9CC7CF, 0x1C4152),
        legacy("violet", "Violet Pulse", 0x0F0B1A, 0x161026, 0x201737, 0xA78BFA, 0xE879F9, 0xF3EFFF, 0xB5A9D6, 0x3A2B63),
        legacy("emerald", "Emerald Abyss", 0x050B08, 0x0A1510, 0x10201A, 0x34D399, 0x84CC16, 0xE9FBF2, 0x9CC4B0, 0x1D4032),
        legacy("crimson", "Crimson Night", 0x0E0A0B, 0x161012, 0x20171A, 0xE5484D, 0xF59E0B, 0xF8EEEE, 0xC2A6A8, 0x44262B),
        legacy("arctic", "Arctic Blue", 0x07111F, 0x0B1A30, 0x112645, 0x7DD3FC, 0x60A5FA, 0xEEF7FF, 0xA3BDD6, 0x214470),
        legacy("gold", "Solar Gold", 0x0B0A07, 0x14120C, 0x1E1B12, 0xE0B341, 0xD97706, 0xFBF6E8, 0xC4B896, 0x453D22),
        legacy("rose", "Rose Nebula", 0x120A14, 0x1B101F, 0x271629, 0xF472B6, 0xC084FC, 0xFDEEF7, 0xCDA6BF, 0x4A2849),
    )
}
