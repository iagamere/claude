package com.abdo.ps4monitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.imageio.ImageIO

/** Game type -> motifs and component language. Encyclopedia sentences below are written in the style of the opening line of an entry (no network in tests). */
class GenreTest {
    private fun load(name: String): Raster {
        val im = ImageIO.read(javaClass.getResourceAsStream("/covers/$name.png"))
        val px = IntArray(im.width * im.height); im.getRGB(0, 0, im.width, im.height, px, 0, im.width)
        return Raster(im.width, im.height, IntArray(px.size) { px[it] or (0xFF shl 24) })
    }
    private fun hue(c: Int) = ColorMath.toHsl(c)[0]
    private val ghostArt by lazy { ArtAnalyzer.analyze(listOf(load("ghost"))) }
    private val fcArt by lazy { ArtAnalyzer.analyze(listOf(load("fc24_icon"))) }

    // ---- classification
    @Test fun titlesGiveTheirKindOfGame() {
        fun top(t: String) = GenreClassifier.fromTitle(GenreClassifier.cleanTitle(t)).maxByOrNull { it.value }?.key
        assertEquals(Tag.SPORTS, top("EA SPORTS™ FC 24")); assertEquals(Tag.SPORTS, top("NBA 2K24")); assertEquals(Tag.RACING, top("Forza Horizon 5"))
        assertEquals(Tag.FIGHTING, top("TEKKEN 8")); assertEquals(Tag.HORROR, top("Resident Evil 4")); assertEquals(Tag.SHOOTER, top("Call of Duty: Modern Warfare"))
        assertEquals(Tag.PIXEL, top("Minecraft")); assertEquals(Tag.SURVIVAL, top("The Last of Us Part II")); assertEquals(Tag.SAMURAI, top("Nioh 2"))
        assertTrue(GenreClassifier.fromTitle("Hollow Knight").isEmpty())      // nothing in the name: the online entry or the artwork decides
        assertTrue(GenreClassifier.fromTitle("").isEmpty())
    }
    @Test fun entrySentencesGiveTheirKindOfGame() {
        val fc = GenreClassifier.fromText("EA Sports FC 24 is a football simulation video game developed by EA Vancouver and EA Romania and published by Electronic Arts.")
        assertTrue(fc[Tag.SPORTS]!! >= 0.7f)
        val gh = GenreClassifier.fromText("Ghost of Tsushima is a 2020 action-adventure game. Set in feudal Japan in 1274, during the first Mongol invasion, the player controls Jin Sakai, a samurai.")
        assertTrue(gh[Tag.SAMURAI]!! >= 0.7f)
        val hk = GenreClassifier.fromText("Hollow Knight is a 2017 Metroidvania action-adventure game developed and published by Team Cherry.")
        assertTrue(hk[Tag.DARK_FANTASY]!! >= 0.7f)
        assertTrue(GenreClassifier.fromText("Tekken 8 is a fighting game developed and published by Bandai Namco Entertainment.")[Tag.FIGHTING]!! >= 0.7f)
        assertTrue(GenreClassifier.fromText("The company was founded in 1990 and sells furniture.").isEmpty()); assertTrue(GenreClassifier.fromText(null).isEmpty())
    }
    @Test fun wrongEntriesAreRejected() {
        assertTrue(GenreClassifier.plausible("EA SPORTS FC 24", "EA Sports FC 24", "EA Sports FC 24 is a football video game."))
        assertTrue(!GenreClassifier.plausible("Hollow Knight", "Knight Rider", "Knight Rider is an American television series with a car."))
        assertTrue(!GenreClassifier.plausible("Hollow Knight", "Hollow Knight", "A poem about trees."))
        assertEquals("EA SPORTS FC 24", GenreClassifier.cleanTitle("EA SPORTS™ FC 24 Deluxe Edition")); assertEquals("Ghost of Tsushima", GenreClassifier.cleanTitle("Ghost of Tsushima Director's Cut"))
    }
    @Test fun mergeEncodeAndKeyBehave() {
        val m = GenreClassifier.merge(mapOf(Tag.SPORTS to 0.9f, Tag.RACING to 0.3f), mapOf(Tag.SPORTS to 0.8f, Tag.FAMILY to 0.5f))
        assertTrue(m[Tag.SPORTS]!! >= 0.95f); assertTrue(Tag.RACING !in m); assertTrue(Tag.FAMILY in m)
        assertEquals(m.keys, GenreClassifier.decode(GenreClassifier.encode(m)).keys)
        assertTrue(GenreClassifier.key(m) == GenreClassifier.key(m) && GenreClassifier.key(m) != GenreClassifier.key(emptyMap()))
        assertTrue(GenreClassifier.merge(Tag.values().associateWith { 0.9f }).size <= 4)
    }

    // ---- every kind of game gets its own motifs and component language
    @Test fun everyKindOfGameHasItsOwnReadableDesign() {
        val none = DnaGenerator.generate(ghostArt)
        val sigs = HashSet<List<String>>(); val designs = ArrayList<Dna>()
        for (t in Tag.values()) {
            val g = DnaGenerator.generate(ghostArt, mapOf(t to 0.9f))
            assertTrue("$t dark ${Colors.audit(g.dark)}", Colors.audit(g.dark).isEmpty()); assertTrue("$t light ${Colors.audit(g.light)}", Colors.audit(g.light).isEmpty())
            assertTrue("$t has motifs", g.dna.ornaments.isNotEmpty())
            assertTrue("$t differs from artwork-only: ${Dna.distance(g.dna, none.dna)}", g.dna != none.dna)
            sigs.add(g.dna.signature()); designs.add(g.dna)
        }
        assertTrue("distinct designs: ${sigs.size}", sigs.size >= 19)
        var sum = 0.0; var n = 0; var minD = 1.0
        for (i in designs.indices) for (j in i + 1 until designs.size) { val d = Dna.distance(designs[i], designs[j]); sum += d; n++; minD = minOf(minD, d) }
        assertTrue("average distance ${sum / n}", sum / n >= 0.55); assertTrue("closest pair $minD", minD >= 0.1)
    }
    @Test fun sameArtworkDifferentTypeKeepsColoursButChangesTheDesign() {
        val sports = DnaGenerator.generate(ghostArt, mapOf(Tag.SPORTS to 0.9f)); val samurai = DnaGenerator.generate(ghostArt, mapOf(Tag.SAMURAI to 0.9f))
        assertEquals(sports.dark, samurai.dark); assertEquals(sports.light, samurai.light)
        assertTrue("distance ${Dna.distance(sports.dna, samurai.dna)}", Dna.distance(sports.dna, samurai.dna) >= 0.7)
    }
    @Test fun aLightFootballCoverIsNotTheSamuraiDesignInGreen() {
        val fc = DnaGenerator.generate(fcArt, GenreClassifier.fromTitle("EA SPORTS FC 24"))
        val d = fc.dna
        assertEquals(Corner.SLANT, d.shape.corner); assertEquals(RuleKind.PITCH, d.rule); assertEquals(TabKind.SLANT, d.tabs); assertEquals(ProgressKind.STRIPED, d.progress)
        assertEquals(FontKind.DISPLAY, d.type.title); assertTrue(d.ornaments.any { it.kind == OrnamentKind.PITCH_LINES })
        val h = hue(fc.dark.accent); assertTrue("green accent from the cover, hue $h", h in 90f..170f)
        assertTrue(ColorMath.luminance(fc.light.bg) > 0.85)
        val samuraiLook = DnaGenerator.generate(ghostArt, mapOf(Tag.SAMURAI to 0.9f)).dna
        assertTrue("distance ${Dna.distance(d, samuraiLook)}", Dna.distance(d, samuraiLook) >= 0.8)
        assertTrue(Colors.audit(fc.dark).isEmpty() && Colors.audit(fc.light).isEmpty())
    }
    @Test fun weakOrMissingTypeFallsBackToTheArtwork() {
        assertEquals(DnaGenerator.generate(ghostArt).dna, DnaGenerator.generate(ghostArt, mapOf(Tag.RACING to 0.3f)).dna)
        assertEquals(DnaGenerator.generate(ghostArt).dna, DnaGenerator.generate(ghostArt, emptyMap()).dna)
    }
    @Test fun typeNeverBreaksTheOrnamentBudget() {
        for (t in Tag.values()) { val d = DnaGenerator.generate(ghostArt, mapOf(t to 0.9f, Tag.ADVENTURE to 0.6f, Tag.FAMILY to 0.55f)).dna; assertTrue(d.ornaments.size <= 3); assertTrue(d.ornaments.map { it.kind }.distinct().size == d.ornaments.size) }
    }

    @Test fun eldenRingStyleGamesGetTheirOwnAusterineProfile() {
        val entry = "Elden Ring is a 2022 action role-playing game developed by FromSoftware and published by Bandai Namco Entertainment. The game, directed by Hidetaka Miyazaki, with worldbuilding provided by fantasy writer George R. R. Martin, was released for PlayStation 4. Set in the Lands Between, players control a customizable player character."
        val tags = GenreClassifier.fromText(entry)
        assertTrue(tags[Tag.SOULSLIKE]!! >= 0.8f); assertTrue(tags[Tag.FANTASY]!! >= 0.5f)
        val d = DnaGenerator.generate(ghostArt, GenreClassifier.merge(tags)).dna
        assertEquals(Corner.SQUARE, d.shape.corner); assertEquals(BorderKind.DOUBLE, d.border.kind); assertEquals(RuleKind.DIAMOND, d.rule)
        assertEquals(TabKind.UNDERLINE, d.tabs); assertEquals(HeroKind.FADE, d.hero); assertEquals(SurfaceKind.SOLID, d.surface); assertEquals(FontKind.ROMAN, d.type.title)
        val darkFantasy = DnaGenerator.generate(ghostArt, mapOf(Tag.DARK_FANTASY to 0.9f)).dna; val samurai = DnaGenerator.generate(ghostArt, mapOf(Tag.SAMURAI to 0.9f)).dna
        assertTrue("vs dark fantasy ${Dna.distance(d, darkFantasy)}", Dna.distance(d, darkFantasy) >= 0.5); assertTrue("vs samurai ${Dna.distance(d, samurai)}", Dna.distance(d, samurai) >= 0.5)
    }
    @Test fun mythologyGamesGetRunesAndASettingWinsOverAGenericGenre() {
        val tags = GenreClassifier.merge(GenreClassifier.fromText("God of War is a 2018 action-adventure game. It is rooted in Norse mythology."), GenreClassifier.fromGenreLabels(listOf("hack and slash")))
        assertTrue(Tag.MYTHIC in tags && Tag.ACTION in tags)
        val d = DnaGenerator.generate(ghostArt, tags).dna
        assertEquals(RuleKind.RUNIC, d.rule); assertTrue(d.ornaments.any { it.kind == OrnamentKind.RUNES }); assertEquals(Corner.SQUARE, d.shape.corner)
    }
}
