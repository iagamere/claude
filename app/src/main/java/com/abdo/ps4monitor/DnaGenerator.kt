package com.abdo.ps4monitor

import kotlin.math.max
import kotlin.math.min

/** The result of the local analysis: colours for both appearances + the design layers + the measurements they came from. */
class GeneratedDesign(val dark: ColorSet, val light: ColorSet, val dna: Dna, val profile: ArtProfile, val tags: Map<Tag, Float> = emptyMap()) {
    /** measurements + detected game type, stored with the design and shown as "why this design" */
    fun report(): List<Pair<String, Float>> = profile.report() + tags.map { ("tag:" + it.key.name) to it.value }
}

/** The component language of one kind of game. A null field means "let the artwork decide". */
class Kit(
    val shape: ShapeDna, val border: BorderDna, val rule: RuleKind, val texture: TextureDna, val ornaments: List<OrnamentDna>,
    val type: TypeDna, val tabs: TabKind, val progress: ProgressKind, val surface: SurfaceKind? = null, val hero: HeroKind? = null,
)

/**
 * ArtProfile -> design. Every layer is decided on its own from the measurements (never "pick a template"), so the layers combine freely:
 * an artwork can get cut corners with a glow border, or square corners with particles. Nothing here knows a game name or an id.
 * Hue only reaches the COLOUR layer; every structural decision uses hue-independent scores.
 */
object DnaGenerator {
    private fun s(p: ArtProfile, k: ArtStyle) = p.styles[k] ?: 0f
    private fun hsl(c: Int) = ColorMath.toHsl(c)

    private fun o(k: OrnamentKind, d: Float = 1f, a: Float = 0.3f) = OrnamentDna(k, d, a)
    private fun tx(k: TextureKind, i: Float, cards: Boolean = false) = TextureDna(k, i, cards)

    /** Motifs, shapes and typography per kind of game: all of it is drawn procedurally by DnaKit, nothing is a picture. */
    fun kit(t: Tag): Kit = when (t) {
        Tag.SPORTS -> Kit(ShapeDna(Corner.SLANT, 1f, 4), BorderDna(BorderKind.SINGLE, 1.2f), RuleKind.PITCH, tx(TextureKind.DIAGONAL, 0.6f),
            listOf(o(OrnamentKind.PITCH_LINES, 1f, 0.28f), o(OrnamentKind.SPEED_STRIPES, 1f, 0.2f)), TypeDna(FontKind.DISPLAY, FontKind.DEFAULT, 0, 0.03f), TabKind.SLANT, ProgressKind.STRIPED, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.RACING -> Kit(ShapeDna(Corner.SLANT, 1f, 4), BorderDna(BorderKind.SINGLE, 1.4f), RuleKind.STRIPES, tx(TextureKind.DIAGONAL, 0.7f),
            listOf(o(OrnamentKind.SPEED_STRIPES, 1.2f, 0.3f), o(OrnamentKind.CHECKER_STRIP, 1f, 0.3f)), TypeDna(FontKind.TECH, FontKind.DEFAULT, 700, 0.03f), TabKind.SLANT, ProgressKind.STRIPED, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.FIGHTING -> Kit(ShapeDna(Corner.CUT, 1f, 4), BorderDna(BorderKind.SINGLE, 1.4f), RuleKind.TECH, tx(TextureKind.PARTICLES, 0.45f),
            listOf(o(OrnamentKind.SCRATCHES, 1f, 0.4f), o(OrnamentKind.SPORES, 0.6f, 0.5f)), TypeDna(FontKind.DISPLAY, FontKind.DEFAULT, 0, 0.03f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.SHOOTER -> Kit(ShapeDna(Corner.CUT, 0.8f, 4), BorderDna(BorderKind.SINGLE, 1.4f), RuleKind.TECH, tx(TextureKind.HEX, 0.5f),
            listOf(o(OrnamentKind.HEX_GRID, 1f, 0.22f)), TypeDna(FontKind.DISPLAY, FontKind.DEFAULT, 0, 0.03f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.HORROR -> Kit(ShapeDna(Corner.SQUARE, 1f, 2), BorderDna(BorderKind.SINGLE, 0.75f), RuleKind.BRUSH, tx(TextureKind.PAPER, 0.9f),
            listOf(o(OrnamentKind.SCRATCHES, 1.2f, 0.35f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 600, 0.03f), TabKind.UNDERLINE, ProgressKind.BRUSH, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.FANTASY -> Kit(ShapeDna(Corner.ROUND, 1f, 4), BorderDna(BorderKind.GLOW, 0.8f), RuleKind.DIAMOND, tx(TextureKind.PARTICLES, 0.5f),
            listOf(o(OrnamentKind.SPORES, 0.7f, 0.6f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 700, 0.03f), TabKind.UNDERLINE, ProgressKind.BAR, SurfaceKind.GRADIENT, HeroKind.FADE)
        Tag.DARK_FANTASY -> Kit(ShapeDna(Corner.ROUND, 1.2f, 4), BorderDna(BorderKind.GLOW, 0.8f), RuleKind.GLOW, tx(TextureKind.PARTICLES, 0.7f),
            listOf(o(OrnamentKind.LIGHT_RAYS, 1f, 0.14f), o(OrnamentKind.SPORES, 1f, 0.8f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 700, 0.03f), TabKind.PILL, ProgressKind.GLOW, SurfaceKind.GLASS, HeroKind.FADE)
        Tag.SAMURAI -> Kit(ShapeDna(Corner.SQUARE, 1f, 3), BorderDna(BorderKind.DOUBLE, 0.8f), RuleKind.BRUSH, tx(TextureKind.PAPER, 0.8f, true),
            listOf(o(OrnamentKind.GINKGO, 0.8f, 0.6f), o(OrnamentKind.BRUSH_SWEEP, 1f, 0.55f), o(OrnamentKind.SEIGAIHA_BAND, 1f, 0.35f)), TypeDna(FontKind.SANS, FontKind.SERIF, 500, 0.06f), TabKind.UNDERLINE, ProgressKind.BRUSH, SurfaceKind.SOLID, HeroKind.FRAMED)
        Tag.SCIFI -> Kit(ShapeDna(Corner.CUT, 1f, 4), BorderDna(BorderKind.GLOW, 0.8f), RuleKind.TECH, tx(TextureKind.GRID, 0.6f),
            listOf(o(OrnamentKind.CIRCUIT, 1f, 0.3f), o(OrnamentKind.HEX_GRID, 0.8f, 0.14f)), TypeDna(FontKind.TECH, FontKind.DEFAULT, 600, 0.03f), TabKind.CUT, ProgressKind.GLOW, SurfaceKind.GLASS, HeroKind.FADE)
        Tag.CYBERPUNK -> Kit(ShapeDna(Corner.CUT, 1f, 4), BorderDna(BorderKind.SINGLE, 1.4f), RuleKind.TECH, tx(TextureKind.GRID, 0.7f),
            listOf(o(OrnamentKind.CIRCUIT, 1.2f, 0.35f), o(OrnamentKind.SPEED_STRIPES, 0.6f, 0.2f)), TypeDna(FontKind.TECH, FontKind.DEFAULT, 800, 0.03f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.GLASS, HeroKind.FADE)
        Tag.SPACE -> Kit(ShapeDna(Corner.ROUND, 1.2f, 4), BorderDna(BorderKind.GLOW, 1f), RuleKind.GLOW, tx(TextureKind.STARS, 0.8f),
            listOf(o(OrnamentKind.STARFIELD, 1f, 0.7f), o(OrnamentKind.LIGHT_RAYS, 0.7f, 0.1f)), TypeDna(FontKind.TECH, FontKind.DEFAULT, 500, 0.04f), TabKind.PILL, ProgressKind.GLOW, SurfaceKind.GLASS, HeroKind.FADE)
        Tag.PIXEL -> Kit(ShapeDna(Corner.SQUARE, 1f, 0), BorderDna(BorderKind.SINGLE, 2f), RuleKind.PIXEL, tx(TextureKind.PIXELS, 0.6f),
            listOf(o(OrnamentKind.PIXEL_BLOCKS, 1f, 0.5f)), TypeDna(FontKind.PIXEL, FontKind.DEFAULT, 0, 0f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.ANIME -> Kit(ShapeDna(Corner.ROUND, 1.25f, 4), BorderDna(BorderKind.SINGLE, 1f), RuleKind.DIAMOND, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.HALFTONE, 1f, 0.28f), o(OrnamentKind.SPEED_STRIPES, 0.5f, 0.16f)), TypeDna(FontKind.SANS, FontKind.DEFAULT, 800, 0f), TabKind.PILL, ProgressKind.BAR, SurfaceKind.GRADIENT, HeroKind.FADE)
        Tag.ADVENTURE -> Kit(ShapeDna(Corner.ROUND, 1f, 4), BorderDna(BorderKind.SINGLE, 1f), RuleKind.DIAMOND, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.CONTOURS, 1f, 0.22f)), TypeDna(FontKind.SERIF, FontKind.DEFAULT, 600, 0.03f), TabKind.UNDERLINE, ProgressKind.BAR, SurfaceKind.GRADIENT, HeroKind.FADE)
        Tag.STRATEGY -> Kit(ShapeDna(Corner.SQUARE, 1f, 2), BorderDna(BorderKind.SINGLE, 1f), RuleKind.TECH, tx(TextureKind.GRID, 0.5f),
            listOf(o(OrnamentKind.HEX_GRID, 1f, 0.2f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 600, 0.03f), TabKind.UNDERLINE, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.SURVIVAL -> Kit(ShapeDna(Corner.SQUARE, 1f, 2), BorderDna(BorderKind.SINGLE, 1f), RuleKind.BRUSH, tx(TextureKind.PAPER, 0.6f),
            listOf(o(OrnamentKind.SCRATCHES, 0.8f, 0.3f), o(OrnamentKind.CONTOURS, 1f, 0.18f)), TypeDna(FontKind.DISPLAY, FontKind.DEFAULT, 0, 0.03f), TabKind.UNDERLINE, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.FAMILY -> Kit(ShapeDna(Corner.ROUND, 1.5f, 4), BorderDna(BorderKind.SINGLE, 1f), RuleKind.WAVE, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.SPORES, 1f, 0.7f)), TypeDna(FontKind.SANS, FontKind.DEFAULT, 800, 0f), TabKind.PILL, ProgressKind.BAR, SurfaceKind.GRADIENT, HeroKind.FADE)
        Tag.SOULSLIKE -> Kit(ShapeDna(Corner.SQUARE, 1f, 3), BorderDna(BorderKind.DOUBLE, 0.8f), RuleKind.DIAMOND, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.LIGHT_RAYS, 1f, 0.12f), o(OrnamentKind.SPORES, 0.8f, 0.6f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 600, 0.03f), TabKind.UNDERLINE, ProgressKind.BAR, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.MYTHIC -> Kit(ShapeDna(Corner.SQUARE, 1f, 2), BorderDna(BorderKind.SINGLE, 1.2f), RuleKind.RUNIC, tx(TextureKind.PAPER, 0.6f),
            listOf(o(OrnamentKind.RUNES, 1f, 0.32f), o(OrnamentKind.LIGHT_RAYS, 0.7f, 0.10f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 700, 0.03f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.ACTION -> Kit(ShapeDna(Corner.CUT, 0.9f, 4), BorderDna(BorderKind.SINGLE, 1.2f), RuleKind.STRIPES, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.SCRATCHES, 0.8f, 0.3f), o(OrnamentKind.LIGHT_RAYS, 0.6f, 0.12f)), TypeDna(FontKind.DISPLAY, FontKind.DEFAULT, 0, 0.03f), TabKind.CUT, ProgressKind.TICKS, SurfaceKind.SOLID, HeroKind.FADE)
        Tag.NAUTICAL -> Kit(ShapeDna(Corner.ROUND, 1.1f, 4), BorderDna(BorderKind.SINGLE, 1f), RuleKind.WAVE, tx(TextureKind.NONE, 0f),
            listOf(o(OrnamentKind.WAVES, 1f, 0.35f)), TypeDna(FontKind.ROMAN, FontKind.DEFAULT, 600, 0.03f), TabKind.UNDERLINE, ProgressKind.BAR, SurfaceKind.GRADIENT, HeroKind.FADE)
    }

    fun generate(p: ArtProfile, tags: Map<Tag, Float> = emptyMap()): GeneratedDesign {
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
            angular >= 0.5f -> BorderDna(BorderKind.SINGLE, 1.4f)
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
            paper >= 0.5f -> TypeDna(FontKind.SANS, FontKind.SERIF, 500, 0.06f)
            lum >= 0.5f -> TypeDna(FontKind.SERIF, FontKind.DEFAULT, 700, 0.03f)
            dram >= 0.5f -> TypeDna(FontKind.CONDENSED, FontKind.DEFAULT, 800, 0.02f)
            neon >= 0.5f -> TypeDna(FontKind.CONDENSED, FontKind.DEFAULT, 700, 0.03f)
            mini >= 0.5f -> TypeDna(FontKind.SANS, FontKind.DEFAULT, 300, 0.03f)
            soft >= 0.5f -> TypeDna(FontKind.SANS, FontKind.DEFAULT, 600, 0f)
            else -> TypeDna()
        }
        val spacing = if (!sure) 1f else (1f + 0.35f * (p.flat - 0.25f)).coerceIn(0.92f, 1.14f)
        val hero = if (sure && (paper >= 0.5f || mini >= 0.5f)) HeroKind.FRAMED else HeroKind.FADE
        val tabs = when { !sure -> TabKind.PILL; paper >= 0.5f -> TabKind.UNDERLINE; angular >= 0.5f -> TabKind.CUT; else -> TabKind.PILL }
        val progress = when { !sure -> ProgressKind.BAR; paper >= 0.5f -> ProgressKind.BRUSH; glowy >= 0.5f -> ProgressKind.GLOW; angular >= 0.5f -> ProgressKind.TICKS; else -> ProgressKind.BAR }
        var dna = Dna(corner, border, rule, surface, texture, ornaments, type, spacing, hero, tabs, progress)
        // the game's type decides the component language and the motifs; the artwork keeps the colours, the mood and the spacing
        // a setting (samurai, mythology, souls-like ...) is more characteristic than a generic genre, so it wins close calls
        val setting = setOf(Tag.SAMURAI, Tag.MYTHIC, Tag.SOULSLIKE, Tag.DARK_FANTASY, Tag.CYBERPUNK, Tag.SPACE, Tag.NAUTICAL, Tag.PIXEL)
        val ranked = tags.entries.sortedByDescending { it.value + (if (it.key in setting) 0.1f else 0f) }
        val primary = ranked.firstOrNull()?.takeIf { it.value >= 0.5f }
        if (primary != null) {
            val k = kit(primary.key)
            val extra = ranked.drop(1).firstOrNull { it.value >= 0.5f && kit(it.key).ornaments.isNotEmpty() }?.let { kit(it.key).ornaments.first() }
            val orn = (k.ornaments + listOfNotNull(extra)).distinctBy { it.kind }.take(3)
            dna = dna.copy(shape = k.shape, border = k.border, rule = k.rule, texture = k.texture, ornaments = orn, type = k.type, tabs = k.tabs, progress = k.progress,
                surface = k.surface ?: dna.surface, hero = k.hero ?: dna.hero)
        }

        val (dk, lt) = colors(p, paper)
        return GeneratedDesign(Colors.readable(dk), Colors.readable(lt), dna, p, tags)
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
