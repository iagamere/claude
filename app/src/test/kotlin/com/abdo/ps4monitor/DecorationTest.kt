package com.abdo.ps4monitor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decoration level keeps designs professional: fewer, fainter motifs, but the same component language. */
class DecorationTest {
    private val neutral by lazy { ArtAnalyzer.analyze(listOf(Raster(8, 8, IntArray(64) { if (it % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }))) }
    private fun allDesigns(): List<Dna> = Tag.values().map { DnaGenerator.generate(neutral, mapOf(it to 0.9f)).dna } + DnaPresets.ALL.map { it.dna } + Dna.CLASSIC

    @Test fun richIsTheDesignAsDesignedAndClassicNeverChanges() {
        for (d in allDesigns()) assertEquals(d, d.restrained(2))
        for (lv in 0..2) assertEquals(Dna.CLASSIC, Dna.CLASSIC.restrained(lv))
    }
    @Test fun lessDecorationMeansFewerAndFainterMotifs() {
        for (d in allDesigns()) {
            val m = d.restrained(0); val b = d.restrained(1)
            assertTrue(m.ornaments.size <= 1); assertTrue(b.ornaments.size <= 2); assertTrue(m.ornaments.size <= b.ornaments.size && b.ornaments.size <= d.ornaments.size)
            d.ornaments.firstOrNull()?.let { o -> assertTrue(m.ornaments[0].alpha <= b.ornaments[0].alpha && b.ornaments[0].alpha <= o.alpha) }
            assertTrue(m.texture.intensity <= b.texture.intensity && b.texture.intensity <= d.texture.intensity)
            assertTrue(!m.texture.onCards); assertTrue(b.border.kind != BorderKind.NONE || d.border.kind == BorderKind.NONE)
            assertTrue(m.border.kind in listOf(BorderKind.NONE, BorderKind.SINGLE, BorderKind.GLOW)); assertTrue(m.border.width <= 1f)
        }
    }
    @Test fun theComponentLanguageSurvivesEveryLevel() {
        for (d in allDesigns()) for (lv in 0..1) {
            val r = d.restrained(lv)
            assertEquals(d.shape, r.shape); assertEquals(d.rule, r.rule); assertEquals(d.surface, r.surface); assertEquals(d.type, r.type)
            assertEquals(d.tabs, r.tabs); assertEquals(d.progress, r.progress); assertEquals(d.hero, r.hero); assertEquals(d.spacing, r.spacing); assertEquals(d.texture.kind, r.texture.kind)
        }
    }
    @Test fun theBalancedLevelKeepsAtMostTwoMotifsOnTheBusiestDesigns() {
        val ghost = DnaPresets.GHOST_DNA; assertEquals(3, ghost.ornaments.size)
        assertEquals(listOf(OrnamentKind.GINKGO, OrnamentKind.BRUSH_SWEEP), ghost.restrained(1).ornaments.map { it.kind })      // the most characteristic motifs come first
        assertEquals(listOf(OrnamentKind.GINKGO), ghost.restrained(0).ornaments.map { it.kind })
        assertEquals(BorderKind.SINGLE, ghost.restrained(0).border.kind); assertEquals(BorderKind.DOUBLE, ghost.restrained(1).border.kind)
    }
    @Test fun trackingIsCalmerSoTitlesTakeLessRoom() {
        for (d in allDesigns()) assertTrue("tracking ${d.type.tracking}", d.type.tracking <= 0.06f)
    }
    @Test fun allDesignsStillRoundTripThroughJson() {      // needs org.json (added as a test dependency)
        for (d in allDesigns()) assertEquals(d, DnaJson.fromJson(JSONObject(DnaJson.toJson(d).toString())))
    }
}
