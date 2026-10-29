package com.abdo.ps4monitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing and spoiler-conscious trimming of encyclopedia data. The JSON below has the same shape as the Wikipedia / Wikidata API replies (no network in tests). */
class GameFactsTest {
    private val wikipedia = """{"query":{"pages":{"123":{"title":"Example Quest","extract":"Example Quest is a 2020 action-adventure game developed by Alpha Studio and published by Beta Games. Set in a frozen kingdom, the player controls a courier who must carry a sealed letter across the mountains (see below). The courier dies at the end of the story. Players explore, trade and fight bandits along the way. The game received positive reviews from critics and sold two million copies.","pageprops":{"wikibase_item":"Q42"},"langlinks":[{"lang":"ar","*":"كويست مثال"}]}}}}"""
    private val claims = """{"entities":{"Q42":{"claims":{
        "P136":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q1"}}}},{"rank":"deprecated","mainsnak":{"datavalue":{"value":{"id":"Q9"}}}}],
        "P404":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q2"}}}}],
        "P178":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q3"}}}}],
        "P123":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q4"}}}}],
        "P400":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q5"}}}},{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q6"}}}}],
        "P852":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q7"}}}}],
        "P908":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"id":"Q8"}}}}],
        "P577":[{"rank":"normal","mainsnak":{"datavalue":{"value":{"time":"+2020-09-18T00:00:00Z","precision":11}}}},{"rank":"normal","mainsnak":{"datavalue":{"value":{"time":"+2020-11-01T00:00:00Z","precision":11}}}},{"rank":"normal","mainsnak":{"datavalue":{"value":{"time":"+2020-00-00T00:00:00Z","precision":9}}}}]}}}}"""
    private val labels = """{"entities":{"Q1":{"labels":{"en":{"value":"action-adventure game"}}},"Q2":{"labels":{"en":{"value":"single-player video game"}}},"Q3":{"labels":{"en":{"value":"Alpha Studio"}}},"Q4":{"labels":{"en":{"value":"Beta Games"}}},"Q5":{"labels":{"en":{"value":"Xbox One"}}},"Q6":{"labels":{"en":{"value":"PlayStation 4"}}},"Q7":{"labels":{"en":{"value":"Mature 17+"}}},"Q8":{"labels":{"en":{"value":"PEGI 18"}}}}}"""

    @Test fun pageIsParsedWithIdAndArabicLink() {
        val p = GameFactsParser.parsePage(wikipedia)!!
        assertEquals("Example Quest", p.title); assertEquals("Q42", p.qid); assertEquals("كويست مثال", p.arTitle); assertTrue(p.extract.startsWith("Example Quest is"))
        assertEquals(null, GameFactsParser.parsePage("""{"query":{}}"""))
    }
    @Test fun premiseKeepsTheSetupAndDropsSpoilersAndReception() {
        val s = GameFactsParser.premise(GameFactsParser.parsePage(wikipedia)!!.extract)
        assertTrue(s, s.contains("frozen kingdom")); assertTrue(s, s.contains("explore, trade"))
        assertTrue(s, !s.contains("dies")); assertTrue(s, !s.contains("sold")); assertTrue(s, !s.contains("(see below)")); assertTrue(s, !s.contains("developed by"))
        assertEquals("", GameFactsParser.premise("")); assertTrue(GameFactsParser.premise("x ".repeat(900)).length <= 470)
    }
    @Test fun factsAreBuiltFromTheClaims() {
        val page = GameFactsParser.parsePage(wikipedia)!!
        val c = GameFactsParser.parseClaims(claims, "Q42")
        assertEquals(listOf("Q1"), c.ids("P136"))                    // the deprecated statement is ignored
        val f = GameFactsParser.build(page, c, GameFactsParser.parseLabels(labels, "en"), page.extract, "en", "https://en.wikipedia.org/wiki/Example_Quest", 5L)
        assertEquals(listOf("action-adventure game"), f.genres); assertEquals(listOf("Alpha Studio"), f.developers); assertEquals(listOf("Beta Games"), f.publishers)
        assertEquals("PlayStation 4", f.platforms.first()); assertEquals(listOf("ESRB Mature 17+", "PEGI 18"), f.ratings)
        assertEquals("2020-09-18", f.release); assertTrue(!f.isEmpty())
    }
    @Test fun datesAndLabelsBehave() {
        assertEquals("2017-02-24", GameFactsParser.formatTime("+2017-02-24T00:00:00Z", 11)); assertEquals("2017-02", GameFactsParser.formatTime("+2017-02-00T00:00:00Z", 10))
        assertEquals("2017", GameFactsParser.formatTime("+2017-00-00T00:00:00Z", 9)); assertEquals(null, GameFactsParser.formatTime("nope", 11))
        assertEquals("2019-05-01", GameFactsParser.earliest(listOf("2020", "2019-05-01", "2019-06")))
        assertEquals("الاسم", GameFactsParser.parseLabels("""{"entities":{"Q1":{"labels":{"ar":{"value":"الاسم"},"en":{"value":"Name"}}}}}""", "ar")["Q1"])
        assertEquals("Name", GameFactsParser.parseLabels("""{"entities":{"Q1":{"labels":{"en":{"value":"Name"}}}}}""", "ar")["Q1"])
    }
    @Test fun factsSurviveStorageAndEmptyOnesAreDetected() {
        val f = GameFacts("T", "S", listOf("g"), listOf("m"), listOf("d"), listOf("p"), listOf("pl"), listOf("ESRB M"), "Series", "2020-01-02", "https://x", "ar", 9L)
        assertEquals(f, GameFacts.fromJson(org.json.JSONObject(f.toJson().toString())))
        assertTrue(GameFacts("T", "", emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null, null, null, "en").isEmpty())
    }
    @Test fun arabicLeadIsFilteredToo() {
        val s = GameFactsParser.premise("لعبة مغامرات تدور أحداثها في مملكة متجمدة يسيطر فيها اللاعب على ساعٍ يحمل رسالة مختومة عبر الجبال. يموت الساعي في نهاية القصة كلها. تلقت اللعبة إشادة من النقاد وحققت مبيعات عالية.")
        assertTrue(s, s.contains("مملكة متجمدة")); assertTrue(s, !s.contains("يموت")); assertTrue(s, !s.contains("النقاد"))
    }

    private fun pg(title: String, idx: Int, extract: String) = """"$idx":{"title":"$title","index":$idx,"extract":"$extract"}"""
    private val godOfWar = """{"query":{"pages":{${pg("God of War (franchise)", 1, "God of War is an action-adventure video game series, the first era of which was loosely based on Greek mythology.")},${pg("God of War (2005 video game)", 2, "God of War is a 2005 action-adventure game developed by Santa Monica Studio.")},${pg("God of War (2018 video game)", 3, "God of War is a 2018 action-adventure game developed by Santa Monica Studio and published by Sony Interactive Entertainment. Unlike previous games it is rooted in Norse mythology.")},${pg("God of War Ragnarök", 4, "God of War Ragnarök is a 2022 action-adventure game developed by Santa Monica Studio.")}}}}"""

    @Test fun theGamePageIsChosenNotTheSeriesNorAnOlderGame() {
        val pages = GameFactsParser.parsePages(godOfWar)
        assertEquals(listOf(1, 2, 3, 4), pages.map { it.index })
        assertEquals("God of War (2018 video game)", GameFactsParser.pick(pages, "God of War")!!.title)
        assertEquals("God of War Ragnarök", GameFactsParser.pick(pages, "God of War Ragnarök")!!.title)
        assertEquals(null, GameFactsParser.pick(listOf(pages[0]), "God of War"))                       // only the series page: better nothing than the wrong game
        assertEquals(null, GameFactsParser.pick(pages, "Completely Different Name"))
        assertEquals("Example Quest", GameFactsParser.pick(listOf(GameFactsParser.parsePage(wikipedia)!!), "Example Quest")!!.title)
    }
    @Test fun gameTypesComeFromTheEntryAndTheGenreLabels() {
        val t = GenreClassifier.fromGenreLabels(listOf("hack and slash", "action role-playing game", "soulslike video game", "association football video game"))
        assertTrue(t[Tag.ACTION]!! >= 0.8f); assertTrue(t[Tag.FANTASY]!! >= 0.5f); assertTrue(t[Tag.SOULSLIKE]!! >= 0.9f); assertTrue(t[Tag.SPORTS]!! >= 0.9f)
        val page = GameFactsParser.pick(GameFactsParser.parsePages(godOfWar), "God of War")!!
        val f = GameFactsParser.build(page, GameFactsParser.parseClaims(claims, "Q42"), GameFactsParser.parseLabels(labels, "en"), page.extract, "en", null, 1L)
        val tags = GenreClassifier.decode(f.tags)
        assertTrue(f.tags, tags[Tag.MYTHIC]!! >= 0.7f)
    }
    @Test fun aJsonNullIsNeverShownAsTheWordNull() {
        val f = GameFacts.fromJson(org.json.JSONObject("""{"t":"T","s":"S","se":null,"rel":null,"u":null,"l":"en","ts":1,"tg":null}"""))
        assertEquals(null, f.series); assertEquals(null, f.release); assertEquals(null, f.pageUrl); assertEquals("", f.tags)
        val g = GameFacts.fromJson(org.json.JSONObject("""{"t":"T","se":"null","rel":"null"}"""))
        assertEquals(null, g.series); assertEquals(null, g.release)
    }
}
