package com.abdo.ps4monitor

import kotlin.math.max
import kotlin.math.min

/** The result of the local analysis: colours for both appearances + the design layers + the measurements they came from. */
class GeneratedDesign(val dark: ColorSet, val light: ColorSet, val dna: Dna, val profile: ArtProfile)

/**
 * ArtProfile -> design. Every layer is decided on its own from the measurements (never "pick a template"), so the layers combine freely:
 * an artwork can get cut corners with a glow border, or square corners with particles. Nothing here knows a game name or an id.
 * Hue only reaches the COLOUR layer; every structural decision uses hue-independent scores.
 */
object DnaGenerator {
    private fun s(p: ArtProfile, k: ArtStyle) = p.styles[k] ?: 0f
    private fun hsl(c: Int) = ColorMath.toHsl(c)

    fun generate(p: ArtProfile): GeneratedDesign {
        val paper = s(p, ArtStyle.PAPER); val lum = s(p, ArtStyle.LUMINOUS); val dram = s(p, ArtStyle.DRAMATIC)
        val neon = s(p, ArtStyle.NEON); val mini = s(p, ArtStyle.MINIMAL); val soft = s(p, ArtStyle.SOFT)
        val angular = max(dram, neon * 0.8f)
        val glowy = max(lum, neon)
        val sure = p.confidence >= 0.35f

        val corner = when {
            !sure -> ShapeDna()
            angular >= 0.5f -> ShapeDna(Corner.CUT, 1f, 4)
            paper >= 0.5f || mini >= 0.5f -> ShapeDna(Corner.SQUARE, 1f, 3)
            else -> ShapeDna(Corner.ROUND, (1f + 0.35f * lum + 0.15f * soft).coerceIn(0.9f, 1.4f), 4)
        }
        val border = when {
            !sure -> BorderDna()
            paper >= 0.5f -> BorderDna(BorderKind.DOUBLE, 0.8f)
            glowy >= 0.5f -> BorderDna(BorderKind.GLOW, 1f)
            angular >= 0.5f -> BorderDna(BorderKind.BRACKETS, 1f)
            mini >= 0.5f -> BorderDna(BorderKind.SINGLE, 0.75f)
            else -> BorderDna()
        }
        val rule = when {
            !sure -> RuleKind.HAIRLINE
            paper >= 0.5f -> RuleKind.BRUSH
            glowy >= 0.5f -> RuleKind.GLOW
            angular >= 0.5f -> RuleKind.TECH
            soft >= 0.5f -> RuleKind.DIAMOND
            else -> RuleKind.HAIRLINE
        }
        val surface = when {
            !sure -> SurfaceKind.GRADIENT
            glowy >= 0.5f -> SurfaceKind.GLASS
            paper >= 0.5f || mini >= 0.5f || angular >= 0.5f -> SurfaceKind.SOLID
            else -> SurfaceKind.GRADIENT
        }
        val texture = when {
            !sure -> TextureDna()
            paper >= 0.5f -> TextureDna(TextureKind.PAPER, 0.8f, onCards = true)
            neon >= 0.5f -> TextureDna(TextureKind.GRID, 0.7f)
            lum >= 0.5f -> TextureDna(TextureKind.PARTICLES, 0.7f)
            dram >= 0.5f -> TextureDna(TextureKind.PARTICLES, 0.45f)
            else -> TextureDna()
        }
        val ornaments = buildList {
            if (!sure) return@buildList
            if (lum >= 0.5f) add(OrnamentDna(OrnamentKind.LIGHT_RAYS, 1f, 0.14f))
            if (lum >= 0.4f || neon >= 0.5f) add(OrnamentDna(OrnamentKind.SPORES, 1f, 0.8f))
            else if (dram >= 0.5f) add(OrnamentDna(OrnamentKind.SPORES, 0.6f, 0.5f))
            if (paper >= 0.5f) add(OrnamentDna(OrnamentKind.BRUSH_SWEEP, 1f, 0.55f))
        }
        val type = when {
            !sure -> TypeDna()
            paper >= 0.5f -> TypeDna(FontKind.SANS, FontKind.SERIF, 500, 0.12f)
            lum >= 0.5f -> TypeDna(FontKind.SERIF, FontKind.DEFAULT, 700, 0.06f)
            dram >= 0.5f -> TypeDna(FontKind.CONDENSED, FontKind.DEFAULT, 800, 0.02f)
            neon >= 0.5f -> TypeDna(FontKind.CONDENSED, FontKind.DEFAULT, 700, 0.05f)
            mini >= 0.5f -> TypeDna(FontKind.SANS, FontKind.DEFAULT, 300, 0.04f)
            soft >= 0.5f -> TypeDna(FontKind.SANS, FontKind.DEFAULT, 600, 0f)
            else -> TypeDna()
        }
        val spacing = if (!sure) 1f else (1f + 0.35f * (p.flat - 0.25f)).coerceIn(0.92f, 1.14f)
        val hero = if (sure && (paper >= 0.5f || mini >= 0.5f)) HeroKind.FRAMED else HeroKind.FADE
        val tabs = when { !sure -> TabKind.PILL; paper >= 0.5f -> TabKind.UNDERLINE; angular >= 0.5f -> TabKind.CUT; else -> TabKind.PILL }
        val progress = when { !sure -> ProgressKind.BAR; paper >= 0.5f -> ProgressKind.BRUSH; glowy >= 0.5f -> ProgressKind.GLOW; angular >= 0.5f -> ProgressKind.TICKS; else -> ProgressKind.BAR }
        val dna = Dna(corner, border, rule, surface, texture, ornaments, type, spacing, hero, tabs, progress)

        val (dk, lt) = colors(p, paper)
        return GeneratedDesign(Colors.readable(dk), Colors.readable(lt), dna, p)
    }

    /** Colours: the dark set follows the artwork's dark tones and salient accent; the light set follows its real light tones when it has any. */
    private fun colors(p: ArtProfile, paper: Float): Pair<ColorSet, ColorSet> {
        val accent = p.accent ?: p.lightTone ?: p.darkTone ?: 0xFF7C6BFF.toInt()
        val ah = hsl(accent)
        val accentSat = ah[1].coerceIn(0.28f, 0.88f)
        val dt = p.darkTone?.let { hsl(it) }
        val groundH = if (dt != null && dt[1] > 0.08f) dt[0] else ah[0]
        val groundS = ((dt?.get(1) ?: 0.2f) * 1.0f).coerceIn(0.06f, 0.6f)
        val lightH = p.lightTone?.let { hsl(it)[0] } ?: groundH
        val accent2 = p.accent2?.let { hsl(it) }
        val a2 = when {
            accent2 != null -> ColorMath.fromHsl(accent2[0], accent2[1].coerceIn(0.3f, 0.85f), 0.66f)
            p.lightTone != null && paper >= 0.3f -> ColorMath.fromHsl(lightH, 0.35f, 0.86f)
            else -> ColorMath.fromHsl(ah[0] + 38f, accentSat * 0.8f, 0.66f)
        }
        val acc = ColorMath.fromHsl(ah[0], accentSat, 0.64f)
        val dark = ColorSet(
            bg = ColorMath.fromHsl(groundH, groundS, 0.05f), bg2 = ColorMath.fromHsl(groundH, groundS, 0.085f), surface = ColorMath.fromHsl(groundH, groundS * 0.9f, 0.13f),
            accent = acc, accent2 = a2, text = ColorMath.fromHsl(groundH, 0.2f, 0.95f), text2 = ColorMath.fromHsl(groundH, 0.15f, 0.72f),
            border = ColorMath.fromHsl(groundH, groundS * 0.8f, 0.26f), progress = acc)
        val light = if (p.lightTone != null && p.light >= 0.12f) {
            val lh = hsl(p.lightTone); val ls = lh[1].coerceIn(0.1f, 0.6f)
            val inkH = if (dt != null && dt[1] > 0.08f) dt[0] else ah[0]
            val accL = ColorMath.fromHsl(ah[0], accentSat, 0.38f)
            ColorSet(
                bg = ColorMath.fromHsl(lh[0], ls, 0.945f), bg2 = ColorMath.fromHsl(lh[0], ls, 0.91f), surface = ColorMath.fromHsl(lh[0], ls * 0.7f, 0.975f),
                accent = accL, accent2 = ColorMath.fromHsl(inkH, 0.12f, 0.24f), text = ColorMath.fromHsl(inkH, 0.25f, 0.09f), text2 = ColorMath.fromHsl(inkH, 0.15f, 0.33f),
                border = ColorMath.fromHsl(lh[0], ls * 0.8f, 0.79f), progress = accL)
        } else Colors.deriveLight(Colors.readable(dark))
        return dark to light
    }

    /** The finished theme: colours for both appearances + design layers. The id carries the title so it can be cached per game. */
    fun toPreset(id: String, name: String, g: GeneratedDesign) = PresetSpec(id, name, g.dark, g.light, g.dna)
}
