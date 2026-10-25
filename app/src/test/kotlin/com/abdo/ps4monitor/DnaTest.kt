package com.abdo.ps4monitor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Pure-JVM tests of the theme engine's rules: contrast in both appearances, structural difference between designs, and JSON round trips. */
class DnaTest {
    private fun both(p: PresetSpec): List<Pair<String, ColorSet>> =
        listOf("dark" to Colors.readable(p.dark), "light" to Colors.readable(p.light ?: Colors.deriveLight(p.dark)))

    @Test fun everyPresetIsReadableInBothAppearances() {
        for (p in DnaPresets.ALL) for ((name, set) in both(p)) {
            val problems = Colors.audit(set)
            assertTrue("${p.id} $name: $problems", problems.isEmpty())
        }
    }
    @Test fun appearancesAreReallyLightAndDark() {
        for (p in DnaPresets.ALL) { val b = both(p); assertTrue("${p.id} dark", !Colors.isLight(b[0].second)); assertTrue("${p.id} light", Colors.isLight(b[1].second)) }
    }
    @Test fun handMadeLightSetsAreReadableBeforeAnyAutomaticFix() {
        for (p in DnaPresets.ALL) { p.light?.let { assertTrue("${p.id}: ${Colors.audit(it)}", Colors.audit(it).isEmpty()) }; assertTrue("${p.id}: ${Colors.audit(p.dark)}", Colors.audit(p.dark).isEmpty()) }
    }
    @Test fun derivedLightIsReadableForAnyDarkColourSet() {
        val rnd = java.util.Random(42)
        repeat(600) {
            fun c() = ColorMath.fromHsl(rnd.nextFloat() * 360f, rnd.nextFloat(), 0.02f + rnd.nextFloat() * 0.18f)
            fun a() = ColorMath.fromHsl(rnd.nextFloat() * 360f, 0.2f + rnd.nextFloat() * 0.8f, 0.4f + rnd.nextFloat() * 0.4f)
            val dark = ColorSet(c(), c(), c(), a(), a(), 0xFFFFFFFF.toInt(), 0xFFBBBBBB.toInt(), c(), a())
            val light = Colors.deriveLight(dark)
            assertTrue("light derived from $dark -> ${Colors.audit(light)}", Colors.audit(light).isEmpty())
            assertTrue(Colors.isLight(light))
        }
    }
    @Test fun readableRepairsHostileUserColours() {
        val hostile = ColorSet(0xFF101010.toInt(), 0xFF101010.toInt(), 0xFF101010.toInt(), 0xFF111111.toInt(), 0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF141414.toInt(), 0xFF101010.toInt(), 0xFF111111.toInt())
        val fixed = Colors.readable(hostile)
        assertTrue(Colors.audit(fixed).none { it.startsWith("text") || it.startsWith("accent") || it.startsWith("progress") })
        val lightHostile = ColorSet(0xFFF0F0F0.toInt(), 0xFFF0F0F0.toInt(), 0xFFF2F2F2.toInt(), 0xFFEEEEEE.toInt(), 0xFFEEEEEE.toInt(), 0xFFEFEFEF.toInt(), 0xFFEFEFEF.toInt(), 0xFFF0F0F0.toInt(), 0xFFEEEEEE.toInt())
        assertTrue(Colors.audit(Colors.readable(lightHostile)).none { it.startsWith("text") || it.startsWith("accent") || it.startsWith("progress") })
    }

    // ---- the design is more than colours
    @Test fun ghostAndHollowKnightDifferInMostLayers() {
        val d = Dna.distance(DnaPresets.GHOST_DNA, DnaPresets.HOLLOW_DNA)
        assertTrue("distance $d", d >= 0.8)
        val a = DnaPresets.GHOST_DNA; val b = DnaPresets.HOLLOW_DNA
        assertTrue(a.shape.corner != b.shape.corner || a.shape.scale != b.shape.scale)
        for ((x, y) in listOf(a.border.kind to b.border.kind, a.rule to b.rule, a.texture.kind to b.texture.kind, a.tabs to b.tabs.takeIf { it != a.tabs }!!,
            a.progress to b.progress, a.hero to b.hero.takeIf { it != a.hero }!!, a.surface to b.surface)) assertTrue("$x vs $y", x.toString() != y.toString())
    }
    @Test fun signatureNeverContainsAColour() {
        // two themes with identical colours and different designs differ; identical designs with different colours do not: the comparison is blind to colour
        val same = ColorSet(1, 2, 3, 4, 5, 6, 7, 8, 9)
        val g = PresetSpec("x", "x", same, same, DnaPresets.GHOST_DNA); val h = PresetSpec("y", "y", same, same, DnaPresets.HOLLOW_DNA)
        assertTrue(Dna.distance(g.dna, h.dna) > 0.8)
        val g2 = PresetSpec("z", "z", DnaPresets.HOLLOW.dark, null, DnaPresets.GHOST_DNA)
        assertEquals(0.0, Dna.distance(g.dna, g2.dna), 0.0)
    }
    @Test fun builtInDesignsAreNotColourVariantsOfEachOther() {
        val designs = DnaPresets.ALL.map { it.dna }.distinct()
        assertTrue("need at least 3 structurally different designs", designs.size >= 3)
        for (i in designs.indices) for (j in i + 1 until designs.size) assertTrue("designs $i/$j too similar", Dna.distance(designs[i], designs[j]) >= 0.5)
    }

    // ---- storage format
    @Test fun dnaSurvivesAJsonRoundTrip() {
        for (d in listOf(Dna.CLASSIC, DnaPresets.GHOST_DNA, DnaPresets.HOLLOW_DNA)) assertEquals(d, DnaJson.fromJson(JSONObject(DnaJson.toJson(d).toString())))
    }
    @Test fun lightColoursSurviveAJsonRoundTrip() {
        val l = DnaPresets.GHOST.light!!
        assertEquals(l, DnaJson.colorsOrNull(JSONObject(DnaJson.colors(l).toString())))
    }
    @Test fun hostileJsonIsContained() {
        val o = JSONObject("""{"corner":"NOPE","cornerScale":99,"border":"GLOW","borderWidth":-5,"ornaments":[{"kind":"BAD"},{"kind":"GINKGO","density":50,"alpha":9}],"spacing":"x","titleWeight":99999,"tracking":7}""")
        val d = DnaJson.fromJson(o)
        assertEquals(Corner.ROUND, d.shape.corner); assertTrue(d.shape.scale <= 1.6f); assertTrue(d.border.width >= 0.5f)
        assertEquals(1, d.ornaments.size); assertTrue(d.ornaments[0].density <= 1.5f && d.ornaments[0].alpha <= 1f)
        assertTrue(d.spacing in 0.85f..1.25f); assertTrue(d.type.titleWeight <= 900); assertTrue(d.type.tracking <= 0.3f)
        assertNotNull(DnaJson.parseHex("#abc"))
        assertEquals(null, DnaJson.colorsOrNull(JSONObject("""{"bg":"#000000"}""")))
    }
}
