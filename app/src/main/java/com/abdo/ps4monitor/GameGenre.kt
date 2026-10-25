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
