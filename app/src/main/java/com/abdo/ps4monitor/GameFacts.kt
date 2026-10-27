package com.abdo.ps4monitor

import org.json.JSONArray
import org.json.JSONObject

/**
 * General information about a game from open encyclopedia data (Wikipedia text + Wikidata facts). Every field is optional: a game that has no entry
 * simply shows less. Pure Kotlin + org.json (no Android), so the parsing rules are unit-tested.
 */
data class GameFacts(
    val title: String, val summary: String, val genres: List<String>, val modes: List<String>, val developers: List<String>, val publishers: List<String>,
    val platforms: List<String>, val ratings: List<String>, val series: String?, val release: String?, val pageUrl: String?, val lang: String, val fetchedAt: Long = 0L,
) {
    fun isEmpty() = summary.isBlank() && genres.isEmpty() && developers.isEmpty() && publishers.isEmpty() && release == null && ratings.isEmpty()

    fun toJson(): JSONObject = JSONObject().put("t", title).put("s", summary).put("g", JSONArray(genres)).put("m", JSONArray(modes)).put("d", JSONArray(developers))
        .put("p", JSONArray(publishers)).put("pl", JSONArray(platforms)).put("r", JSONArray(ratings)).put("se", series ?: JSONObject.NULL).put("rel", release ?: JSONObject.NULL)
        .put("u", pageUrl ?: JSONObject.NULL).put("l", lang).put("ts", fetchedAt)

    companion object {
        private fun list(o: JSONObject, k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until minOf(a.length(), 12)).map { a.optString(it) }.filter { it.isNotBlank() } } ?: emptyList()
        fun fromJson(o: JSONObject): GameFacts = GameFacts(o.optString("t"), o.optString("s"), list(o, "g"), list(o, "m"), list(o, "d"), list(o, "p"), list(o, "pl"), list(o, "r"),
            o.optString("se", "").ifBlank { null }, o.optString("rel", "").ifBlank { null }, o.optString("u", "").ifBlank { null }, o.optString("l", "en"), o.optLong("ts"))
    }
}

object GameFactsParser {
    class Page(val title: String, val extract: String, val qid: String?, val arTitle: String?)
    class Claims(val byProperty: Map<String, List<String>>, val releaseDates: List<String>) {
        fun ids(p: String) = byProperty[p].orEmpty()
        fun allIds(): List<String> = byProperty.values.flatten().distinct()
    }

    // Wikidata properties
    const val GENRE = "P136"; const val MODE = "P404"; const val DEVELOPER = "P178"; const val PUBLISHER = "P123"; const val PLATFORM = "P400"
    const val ESRB = "P852"; const val PEGI = "P908"; const val SERIES = "P179"; const val RELEASE = "P577"
    private val itemProps = listOf(GENRE, MODE, DEVELOPER, PUBLISHER, PLATFORM, ESRB, PEGI, SERIES)

    /** Wikipedia `generator=search` response (extracts + pageprops + langlinks) -> the single best page, or null. */
    fun parsePage(body: String): Page? {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys(); if (!keys.hasNext()) return null
        val p = pages.getJSONObject(keys.next())
        val qid = p.optJSONObject("pageprops")?.optString("wikibase_item", "")?.takeIf { it.startsWith("Q") }
        val ar = p.optJSONArray("langlinks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("lang") == "ar" }?.optString("*", "")?.ifBlank { null } }
        return Page(p.optString("title"), p.optString("extract"), qid, ar)
    }

    /** Arabic (or any) extract response: the first page's extract. */
    fun parseExtract(body: String): String {
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return ""
        val keys = pages.keys(); return if (keys.hasNext()) pages.getJSONObject(keys.next()).optString("extract") else ""
    }

    /** Wikidata `wbgetentities&props=claims` response -> the claims we use. Deprecated statements are ignored. */
    fun parseClaims(body: String, qid: String): Claims {
        val claims = JSONObject(body).optJSONObject("entities")?.optJSONObject(qid)?.optJSONObject("claims") ?: return Claims(emptyMap(), emptyList())
        fun statements(p: String): List<JSONObject> = claims.optJSONArray(p)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("rank") != "deprecated" } } ?: emptyList()
        val items = HashMap<String, List<String>>()
        for (p in itemProps) {
            val ids = statements(p).mapNotNull { it.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value")?.optString("id")?.takeIf { id -> id.startsWith("Q") } }.distinct()
            if (ids.isNotEmpty()) items[p] = ids
        }
        val dates = statements(RELEASE).mapNotNull { st ->
            val v = st.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.optJSONObject("value") ?: return@mapNotNull null
            formatTime(v.optString("time"), v.optInt("precision", 9))
        }
        return Claims(items, dates)
    }

    /** "+2020-07-17T00:00:00Z", precision 11 -> "2020-07-17"; 10 -> "2020-07"; <=9 -> "2020". Invalid -> null. */
    fun formatTime(time: String, precision: Int): String? {
        val m = Regex("^[+-]?(\\d{4,})-(\\d{2})-(\\d{2})T").find(time) ?: return null
        val y = m.groupValues[1].padStart(4, '0'); val mo = m.groupValues[2]; val d = m.groupValues[3]
        return when { precision >= 11 && mo != "00" && d != "00" -> "$y-$mo-$d"; precision == 10 && mo != "00" -> "$y-$mo"; else -> y }
    }
    /** The earliest date is the original release; "2020" < "2020-07" < "2020-07-17" would sort wrongly, so compare as padded dates. */
    fun earliest(dates: List<String>): String? = dates.minByOrNull { it.padEnd(10, '0') }

    /** `wbgetentities&props=labels` response -> id -> label in [lang] (falls back to English). */
    fun parseLabels(body: String, lang: String): Map<String, String> {
        val ents = JSONObject(body).optJSONObject("entities") ?: return emptyMap()
        val out = HashMap<String, String>()
        for (id in ents.keys()) {
            val labels = ents.getJSONObject(id).optJSONObject("labels") ?: continue
            val l = labels.optJSONObject(lang)?.optString("value") ?: labels.optJSONObject("en")?.optString("value")
            if (!l.isNullOrBlank()) out[id] = l
        }
        return out
    }

    private val spoilerCue = Regex("(?i)\\b(dies|die|death|dead|killed|kills|murder\\w*|betray\\w*|twist|reveal\\w*|ending|endings|sacrific\\w*|turns out|finale|spoiler|traitor)\\b|يموت|موت|قتل|خيان|نهاية|يكشف")
    private val receptionCue = Regex("(?i)\\b(received|reviews?|critics?|acclaim\\w*|awards?|nominated|sold|sales|copies|metacritic|praised|criticism|sequel|remaster\\w*|announced|controvers\\w*)\\b|تلقت|مبيعات|نسخة|جوائز|نقاد|إشادة|الإعلان")
    private val productionLead = Regex("(?i)\\b(developed by|published by|developed and published)\\b|طورتها|نشرتها|تطوير|نشر")

    /**
     * A short, spoiler-conscious premise from the lead of an article: citations and bracketed asides are removed, sentences about the ending / deaths / twists
     * and about reception, sales or sequels are dropped, the "developed by ..." sentence is dropped when the premise is described elsewhere.
     */
    fun premise(extract: String, maxSentences: Int = 3, maxChars: Int = 460): String {
        if (extract.isBlank()) return ""
        val clean = extract.replace(Regex("\\[[^]]*]"), "").replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val sentences = clean.split(Regex("(?<=[.!?؟])\\s+")).map { it.trim() }.filter { it.length > 25 }
        val ok = sentences.filter { !spoilerCue.containsMatchIn(it) && !receptionCue.containsMatchIn(it) }
        val body = ok.filterIndexed { i, s -> !(i == 0 && productionLead.containsMatchIn(s) && ok.size >= 3) }
        var out = ""
        for (s in body.take(maxSentences)) { if ((out + " " + s).trim().length > maxChars && out.isNotEmpty()) break; out = (out + " " + s).trim() }
        if (out.length > maxChars) out = out.take(maxChars).substringBeforeLast(' ') + "…"
        return out
    }

    /** Combines everything into the facts shown to the user. */
    fun build(page: Page, claims: Claims, labels: Map<String, String>, summarySource: String, lang: String, pageUrl: String?, now: Long): GameFacts {
        fun names(p: String, limit: Int = 6) = claims.ids(p).mapNotNull { labels[it] }.distinct().take(limit)
        fun rating(p: String, tag: String) = claims.ids(p).mapNotNull { labels[it] }.map { if (it.contains(tag, ignoreCase = true)) it else "$tag $it" }.distinct().take(2)
        val platforms = names(PLATFORM, 20).sortedBy { if (it.contains("PlayStation", true) || it.contains("بلاي")) 0 else 1 }.take(7)
        return GameFacts(page.title, premise(summarySource), names(GENRE), names(MODE, 4), names(DEVELOPER, 3), names(PUBLISHER, 3), platforms,
            rating(ESRB, "ESRB") + rating(PEGI, "PEGI"), names(SERIES, 1).firstOrNull(), earliest(claims.releaseDates), pageUrl, lang, now)
    }
}
