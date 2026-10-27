package com.abdo.ps4monitor

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The game's type from its name (offline, instant) and, when the user allows it, from the first sentences of its encyclopedia entry
 * (one small request per game, then cached on the phone). Only the game's NAME leaves the phone: no account, id, file or console data.
 */
object GameGenre {
    private val http by lazy { OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).connectTimeout(5, TimeUnit.SECONDS).build() }
    private const val DAY = 86_400_000L

    fun local(title: String): Map<Tag, Float> = GenreClassifier.fromTitle(GenreClassifier.cleanTitle(title))

    /** Tags found online earlier. An empty map = looked, found nothing (kept 7 days); null = never looked or too old. */
    fun cached(titleId: String): Map<Tag, Float>? {
        val s = runCatching { Store.sp.getString("th_tags_$titleId", null) }.getOrNull() ?: return null
        val parts = s.split('|'); if (parts.size < 2) return null
        val age = System.currentTimeMillis() - (parts[0].toLongOrNull() ?: return null)
        val tags = GenreClassifier.decode(parts[1])
        return if (age < (if (tags.isEmpty()) 7 else 180) * DAY) tags else null
    }
    private fun save(titleId: String, tags: Map<Tag, Float>) {
        runCatching { Store.sp.edit().putString("th_tags_$titleId", System.currentTimeMillis().toString() + "|" + GenreClassifier.encode(tags)).apply() }
    }

    /** Looks the game up online. A network failure is not cached (the next visit retries); "no entry found" is cached for a week. */
    suspend fun fetch(titleId: String, title: String): Map<Tag, Float> = withContext(Dispatchers.IO) {
        val r = runCatching { wikipediaIntro(GenreClassifier.cleanTitle(title)) }
        if (r.isFailure) return@withContext emptyMap()
        val tags = GenreClassifier.fromText(r.getOrNull())
        save(titleId, tags); tags
    }

    private fun wikipediaIntro(title: String): String? {
        if (title.isBlank()) return null
        val q = URLEncoder.encode("$title video game", "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=1&prop=extracts&exintro=1&explaintext=1&exsentences=4&redirects=1&format=json"
        val req = Request.Builder().url(url).header("User-Agent", "PS4Monitor/6.8 (personal PS4 companion app)").build()
        val body = http.newCall(req).execute().use { resp -> if (!resp.isSuccessful) throw java.io.IOException("http ${resp.code}"); resp.body?.string().orEmpty() }
        val pages = JSONObject(body).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val keys = pages.keys(); if (!keys.hasNext()) return null
        val page = pages.getJSONObject(keys.next())
        val pageTitle = page.optString("title"); val extract = page.optString("extract")
        return if (GenreClassifier.plausible(title, pageTitle, extract)) extract else null
    }
}

/**
 * General information about a game (type, age rating, release date, short premise ...): Wikipedia for the text, Wikidata for the facts.
 * One lookup per game and language, cached on the phone for 90 days (a miss for 7 days). Only the game's NAME is sent.
 */
object GameWebInfo {
    private const val DAY = 86_400_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).build() }
    class FetchResult(val facts: GameFacts?, val networkError: Boolean)
    private fun key(titleId: String, lang: String) = "th_facts_${titleId}_$lang"

    fun cached(titleId: String, lang: String): GameFacts? {
        val s = runCatching { Store.sp.getString(key(titleId, lang), null) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(s); if (o.has("none")) return null
            val f = GameFacts.fromJson(o); if (System.currentTimeMillis() - f.fetchedAt > 90 * DAY) null else f
        }.getOrNull()
    }
    fun knownMissing(titleId: String, lang: String): Boolean {
        val s = runCatching { Store.sp.getString(key(titleId, lang), null) }.getOrNull() ?: return false
        return runCatching { val o = JSONObject(s); o.has("none") && System.currentTimeMillis() - o.optLong("none") < 7 * DAY }.getOrDefault(false)
    }
    private fun markMissing(titleId: String, lang: String) { runCatching { Store.sp.edit().putString(key(titleId, lang), JSONObject().put("none", System.currentTimeMillis()).toString()).apply() } }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "PS4Monitor/6.10 (personal PS4 companion app)").build()
        return http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("http ${r.code}"); r.body?.string().orEmpty() }
    }
    private fun enc(s: String) = URLEncoder.encode(s.replace(' ', '_'), "UTF-8")

    suspend fun fetch(titleId: String, title: String, lang: String): FetchResult = withContext(Dispatchers.IO) {
        val clean = GenreClassifier.cleanTitle(title)
        try {
            val q = URLEncoder.encode("$clean video game", "UTF-8")
            val page = GameFactsParser.parsePage(get("https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=1&prop=extracts%7Cpageprops%7Clanglinks&ppprop=wikibase_item&lllang=ar&exintro=1&explaintext=1&exsentences=8&redirects=1&format=json"))
            if (page == null || !GenreClassifier.plausible(clean, page.title, page.extract)) { markMissing(titleId, lang); return@withContext FetchResult(null, false) }
            var claims = GameFactsParser.Claims(emptyMap(), emptyList()); var labels = emptyMap<String, String>()
            if (page.qid != null) {
                claims = GameFactsParser.parseClaims(get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${page.qid}&props=claims&format=json"), page.qid)
                val ids = claims.allIds().take(45)
                if (ids.isNotEmpty()) labels = GameFactsParser.parseLabels(get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=labels&languages=en%7Car&format=json"), lang)
            }
            var summary = page.extract; var url = "https://en.wikipedia.org/wiki/" + enc(page.title)
            if (lang == "ar" && page.arTitle != null) runCatching {
                val ex = GameFactsParser.parseExtract(get("https://ar.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=8&redirects=1&titles=${enc(page.arTitle)}&format=json"))
                if (ex.isNotBlank()) { summary = ex; url = "https://ar.wikipedia.org/wiki/" + enc(page.arTitle) }
            }
            val facts = GameFactsParser.build(page, claims, labels, summary, lang, url, System.currentTimeMillis())
            if (facts.isEmpty()) { markMissing(titleId, lang); FetchResult(null, false) }
            else { runCatching { Store.sp.edit().putString(key(titleId, lang), facts.toJson().toString()).apply() }; FetchResult(facts, false) }
        } catch (e: Exception) { FetchResult(null, true) }
    }
}
