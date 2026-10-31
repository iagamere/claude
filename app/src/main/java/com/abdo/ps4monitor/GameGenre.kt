package com.abdo.ps4monitor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** The game's type from its NAME (offline, instant). The online type comes from [GameWebInfo], the same lookup that fills the About section. */
object GameGenre {
    fun local(title: String): Map<Tag, Float> = GenreClassifier.fromTitle(GenreClassifier.cleanTitle(title))
}

/**
 * General information about a game (type, age rating, release date, short premise ...): Wikipedia for the text, Wikidata for the facts.
 * One lookup per game and language, cached on the phone for 90 days (a miss for 7 days). Only the game's NAME is sent.
 */
object GameWebInfo {
    private const val DAY = 86_400_000L
    private val http by lazy { OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).build() }
    class FetchResult(val facts: GameFacts?, val networkError: Boolean)
    private fun key(titleId: String, lang: String) = "th_facts2_${titleId}_$lang"      // v2: entries of the first version could describe a series instead of the game

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
    /** Game types found by the online lookup (any language): empty = looked and found nothing, null = never looked. */
    fun tags(titleId: String): Map<Tag, Float>? {
        for (l in listOf("en", "ar")) cached(titleId, l)?.let { return GenreClassifier.decode(it.tags) }
        return if (knownMissing(titleId, "en") || knownMissing(titleId, "ar")) emptyMap() else null
    }
    // the game page (rememberGameTheme) and the Details tab (GameAbout) can both ask for the same game+language at once; a single
    // in-flight request is shared instead of firing the lookup twice, which matters most on a slow connection.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<FetchResult>>()

    private fun markMissing(titleId: String, lang: String) { runCatching { Store.sp.edit().putString(key(titleId, lang), JSONObject().put("none", System.currentTimeMillis()).toString()).apply() } }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "PS4Monitor/6.10 (personal PS4 companion app)").build()
        return http.newCall(req).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("http ${r.code}"); r.body?.string().orEmpty() }
    }
    private fun enc(s: String) = URLEncoder.encode(s.replace(' ', '_'), "UTF-8")

    suspend fun fetch(titleId: String, title: String, lang: String): FetchResult {
        val k = key(titleId, lang)
        val d = inFlight.computeIfAbsent(k) { scope.async { fetchNow(titleId, title, lang) } }
        return try { d.await() } finally { inFlight.remove(k, d) }
    }

    private suspend fun fetchNow(titleId: String, title: String, lang: String): FetchResult = withContext(Dispatchers.IO) {
        val clean = GenreClassifier.cleanTitle(title)
        try {
            val q = URLEncoder.encode("$clean video game", "UTF-8")
            val pages = GameFactsParser.parsePages(get("https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$q&gsrlimit=6&prop=extracts%7Cpageprops%7Clanglinks&ppprop=wikibase_item&lllang=ar&lllimit=20&exlimit=6&exintro=1&explaintext=1&exsentences=8&redirects=1&format=json"))
            val page = GameFactsParser.pick(pages, clean)       // the page of THIS game, not the series or another game of it
            if (page == null) { markMissing(titleId, lang); return@withContext FetchResult(null, false) }
            var claims = GameFactsParser.Claims(emptyMap(), emptyList()); var labels = emptyMap<String, String>(); var labelsEn = emptyMap<String, String>()
            if (page.qid != null) {
                claims = GameFactsParser.parseClaims(get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${page.qid}&props=claims&format=json"), page.qid)
                val ids = claims.allIds().take(45)
                if (ids.isNotEmpty()) {
                    val body = get("https://www.wikidata.org/w/api.php?action=wbgetentities&ids=${ids.joinToString("%7C")}&props=labels&languages=en%7Car&format=json")
                    labels = GameFactsParser.parseLabels(body, lang); labelsEn = GameFactsParser.parseLabels(body, "en")
                }
            }
            var summary = page.extract; var url = "https://en.wikipedia.org/wiki/" + enc(page.title)
            if (lang == "ar" && page.arTitle != null) runCatching {
                val ex = GameFactsParser.parseExtract(get("https://ar.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&exsentences=8&redirects=1&titles=${enc(page.arTitle)}&format=json"))
                if (ex.isNotBlank()) { summary = ex; url = "https://ar.wikipedia.org/wiki/" + enc(page.arTitle) }
            }
            val facts = GameFactsParser.build(page, claims, labels, summary, lang, url, System.currentTimeMillis(), labelsEn)
            if (facts.isEmpty()) { markMissing(titleId, lang); FetchResult(null, false) }
            else { runCatching { Store.sp.edit().putString(key(titleId, lang), facts.toJson().toString()).apply() }; FetchResult(facts, false) }
        } catch (e: Exception) { FetchResult(null, true) }
    }
}
