package com.abdo.ps4monitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/**
 * The analyser and the generator on real artwork (384 px copies in src/test/resources/covers): a light ivory key art, a dark red-hot key art,
 * a blue painted key art and a small cover icon of the same painted game.
 */
class ArtAnalyzerTest {
    private fun load(name: String): Raster {
        val im = ImageIO.read(javaClass.getResourceAsStream("/covers/$name.png"))
        val px = IntArray(im.width * im.height); im.getRGB(0, 0, im.width, im.height, px, 0, im.width)
        return Raster(im.width, im.height, IntArray(px.size) { px[it] or (0xFF shl 24) })
    }
    private fun design(r: Raster) = DnaGenerator.generate(ArtAnalyzer.analyze(listOf(r)))
    private fun hue(c: Int) = ColorMath.toHsl(c)[0]
    private fun hueNear(c: Int, centre: Float, tol: Float): Boolean { val d = Math.abs(hue(c) - centre) % 360f; return minOf(d, 360f - d) <= tol }
    private fun rotateHue(r: Raster, deg: Float) = Raster(r.w, r.h, IntArray(r.px.size) { val h = ColorMath.toHsl(r.px[it]); ColorMath.fromHsl(h[0] + deg, h[1], h[2]) })

    private val ghost by lazy { design(load("ghost")) }
    private val tekken by lazy { design(load("tekken")) }
    private val hollow by lazy { design(load("hollow")) }
    private val hollowIcon by lazy { design(load("hollow_icon")) }

    // ---- what each artwork is recognised as (hue-independent measurements)
    @Test fun lightIvoryArtIsInkAndPaper() {
        assertEquals(ArtStyle.PAPER, ghost.profile.top)
        val d = ghost.dna
        assertEquals(Corner.SQUARE, d.shape.corner); assertEquals(RuleKind.BRUSH, d.rule); assertEquals(TextureKind.PAPER, d.texture.kind)
        assertEquals(HeroKind.FRAMED, d.hero); assertEquals(TabKind.UNDERLINE, d.tabs); assertEquals(ProgressKind.BRUSH, d.progress)
        assertTrue("light appearance keeps the real paper: ${ghost.light.bg.toString(16)}", ColorMath.luminance(ghost.light.bg) > 0.85)
        assertTrue("muted gold accent, hue ${hue(ghost.dark.accent)}", hueNear(ghost.dark.accent, 45f, 25f))
    }
    @Test fun darkHotArtIsDramaticAndAngular() {
        assertEquals(ArtStyle.DRAMATIC, tekken.profile.top)
        val d = tekken.dna
        assertEquals(Corner.CUT, d.shape.corner); assertEquals(BorderKind.BRACKETS, d.border.kind); assertEquals(RuleKind.TECH, d.rule)
        assertEquals(TabKind.CUT, d.tabs); assertEquals(ProgressKind.TICKS, d.progress); assertEquals(FontKind.CONDENSED, d.type.title)
        assertTrue("warm red / pink accent, hue ${hue(tekken.dark.accent)}", hueNear(tekken.dark.accent, 350f, 30f))
    }
    @Test fun bluePaintedArtIsLuminous() {
        assertEquals(ArtStyle.LUMINOUS, hollow.profile.top)
        val d = hollow.dna
        assertEquals(BorderKind.GLOW, d.border.kind); assertEquals(SurfaceKind.GLASS, d.surface); assertEquals(ProgressKind.GLOW, d.progress)
        assertTrue(d.ornaments.any { it.kind == OrnamentKind.SPORES } && d.ornaments.any { it.kind == OrnamentKind.LIGHT_RAYS })
        assertTrue("blue accent, hue ${hue(hollow.dark.accent)}", hueNear(hollow.dark.accent, 215f, 35f))
    }

    // ---- the three designs are different designs, not recoloured copies
    @Test fun threeArtworksGiveThreeDifferentDesigns() {
        val all = listOf(ghost.dna, tekken.dna, hollow.dna)
        for (i in all.indices) for (j in i + 1 until all.size) assertTrue("designs $i/$j: ${Dna.distance(all[i], all[j])}", Dna.distance(all[i], all[j]) >= 0.5)
    }
    @Test fun structureIsIndependentOfHue() {
        // rotate every hue of each artwork: the colours must change, the design layers must not
        for (n in listOf("ghost", "tekken", "hollow")) {
            val base = load(n); val a = design(base)
            for (deg in listOf(90f, 180f, 270f)) {
                val b = design(rotateHue(base, deg))
                assertTrue("$n +$deg: structure moved by ${Dna.distance(a.dna, b.dna)}", Dna.distance(a.dna, b.dna) <= 0.2)
            }
        }
        val c = design(rotateHue(load("hollow"), 180f))
        assertTrue("accent hue must follow the artwork", !hueNear(c.dark.accent, hue(hollow.dark.accent), 60f))
    }
    @Test fun coverIconAndKeyArtOfTheSameGameAgree() {
        assertTrue("icon vs key art: ${Dna.distance(hollow.dna, hollowIcon.dna)}", Dna.distance(hollow.dna, hollowIcon.dna) <= 0.25)
        assertEquals(hollow.profile.top, hollowIcon.profile.top)
    }
    @Test fun bannerAndIconCanBeAnalysedTogether() {
        val g = DnaGenerator.generate(ArtAnalyzer.analyze(listOf(load("hollow"), load("hollow_icon")), listOf(0.6f, 0.4f)))
        assertEquals(ArtStyle.LUMINOUS, g.profile.top)
    }

    // ---- readability, both appearances, every artwork
    @Test fun generatedColoursAreReadableInBothAppearances() {
        for ((n, g) in listOf("ghost" to ghost, "tekken" to tekken, "hollow" to hollow, "icon" to hollowIcon)) {
            assertTrue("$n dark ${Colors.audit(g.dark)}", Colors.audit(g.dark).isEmpty()); assertTrue("$n light ${Colors.audit(g.light)}", Colors.audit(g.light).isEmpty())
            assertTrue(!Colors.isLight(g.dark)); assertTrue(Colors.isLight(g.light))
        }
    }

    // ---- robustness: never throws, always a valid readable design
    @Test fun blankAndHostileImagesGiveValidNeutralDesigns() {
        val rnd = java.util.Random(7)
        val cases = listOf(
            Raster(64, 64, IntArray(64 * 64) { 0xFF000000.toInt() }), Raster(64, 64, IntArray(64 * 64) { 0xFFFFFFFF.toInt() }), Raster(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() }),
            Raster(64, 64, IntArray(64 * 64) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }), Raster(2, 2, intArrayOf(-1, -16777216, -65536, -16711936)),
            Raster(1, 1000, IntArray(1000) { 0xFF336699.toInt() }), Raster(64, 64, IntArray(64 * 64)),
        )
        for ((i, r) in cases.withIndex()) {
            val g = design(r)
            assertTrue("case $i dark ${Colors.audit(g.dark)}", Colors.audit(g.dark).isEmpty()); assertTrue("case $i light ${Colors.audit(g.light)}", Colors.audit(g.light).isEmpty())
        }
        assertTrue(design(cases[2]).profile.confidence < 0.35f)
        assertEquals(Dna.CLASSIC.signature().size, design(cases[0]).dna.signature().size)
        assertTrue(DnaGenerator.generate(ArtAnalyzer.analyze(emptyList())).profile.confidence == 0f)
    }
    @Test fun unsureAnalysisKeepsTheNeutralDesign() { assertEquals(Dna(), design(Raster(64, 64, IntArray(64 * 64) { 0xFF808080.toInt() })).dna) }

    // ---- cache + speed
    @Test fun analysisIsDeterministicAndFingerprintIsStable() {
        val r = listOf(load("tekken"))
        assertEquals(design(r[0]).dna, design(r[0]).dna); assertEquals(design(r[0]).dark, design(r[0]).dark)
        assertEquals(ArtAnalyzer.fingerprint(r), ArtAnalyzer.fingerprint(listOf(load("tekken"))))
        assertTrue(ArtAnalyzer.fingerprint(r) != ArtAnalyzer.fingerprint(listOf(load("hollow"))))
    }
    @Test fun analysisIsFastEnough() {
        val r = load("hollow"); design(r)                       // warm-up
        val t0 = System.nanoTime(); repeat(5) { design(r) }; val ms = (System.nanoTime() - t0) / 5e6
        assertTrue("$ms ms per artwork", ms < 1500)
    }
}
