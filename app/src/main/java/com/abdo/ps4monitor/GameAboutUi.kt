package com.abdo.ps4monitor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private sealed interface AboutState {
    object Loading : AboutState
    object Off : AboutState
    object Missing : AboutState
    object Offline : AboutState
    class Ready(val facts: GameFacts) : AboutState
}

private fun formatRelease(iso: String?, lang: String): String? {
    if (iso == null) return null
    val loc = Locale(lang)
    return runCatching {
        when (iso.length) {
            10 -> LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(loc))
            7 -> YearMonth.parse(iso).format(DateTimeFormatter.ofPattern("MMMM yyyy", loc))
            else -> iso
        }
    }.getOrDefault(iso)
}

@Composable private fun FactRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(k, Modifier.weight(0.36f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, Modifier.weight(0.64f), style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * "About the game": type, age rating, release date, developer, publisher, players, platforms and a short premise (spoiler-filtered), from open encyclopedia data.
 * Needs the online lookup (Settings > Theme); works from the cache without internet; shows what is missing instead of hiding the section.
 */
@Composable fun GameAbout(titleId: String, title: String) {
    val online = ThemeEngine.onlineGenre
    val lang = if (Lang.isAr) "ar" else "en"
    var retry by remember(titleId) { mutableStateOf(0) }
    val state by produceState<AboutState>(AboutState.Loading, titleId, online, lang, retry) {
        val c = GameWebInfo.cached(titleId, lang)
        if (c != null) { value = AboutState.Ready(c); return@produceState }
        if (!online) { value = AboutState.Off; return@produceState }
        if (retry == 0 && GameWebInfo.knownMissing(titleId, lang)) { value = AboutState.Missing; return@produceState }
        value = AboutState.Loading
        val r = GameWebInfo.fetch(titleId, title, lang)
        value = when { r.facts != null -> AboutState.Ready(r.facts); r.networkError -> AboutState.Offline; else -> AboutState.Missing }
    }
    DnaSurface(spacing = 10.dp) {
        SectionTitle(tr("About the game", "عن اللعبة"))
        when (val s = state) {
            AboutState.Loading -> { LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp)); Dim(tr("Looking the game up…", "جارٍ البحث عن معلومات اللعبة…")) }
            AboutState.Off -> Dim(tr("Online lookup is off. Turn it on in Settings > Theme to see the game's type, age rating, release date and a short premise.", "البحث عبر الإنترنت متوقف. فعّله من الإعدادات ← الثيم لعرض نوع اللعبة وتصنيفها العمري وتاريخ إصدارها ونبذة عنها."))
            AboutState.Missing -> { Dim(tr("No online information was found for this game.", "لم يُعثر على معلومات لهذه اللعبة على الإنترنت.")); TextButton({ retry++ }) { Lbl(tr("Try again", "حاول مجدداً")) } }
            AboutState.Offline -> { Dim(tr("Could not reach the internet.", "تعذّر الاتصال بالإنترنت.")); TextButton({ retry++ }) { Lbl(tr("Try again", "حاول مجدداً")) } }
            is AboutState.Ready -> {
                val f = s.facts; val cs = MaterialTheme.colorScheme
                if (f.summary.isNotBlank()) {
                    var more by remember(titleId) { mutableStateOf(false) }
                    Text(f.summary, Modifier.animateContentSize(), style = MaterialTheme.typography.bodyMedium, maxLines = if (more) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
                    if (f.summary.length > 230) Lbl(if (more) tr("Show less", "أقل") else tr("Read more", "المزيد"), Modifier.clickable { more = !more }, MaterialTheme.typography.labelLarge, cs.primary, FontWeight.SemiBold)
                    DnaRule(Modifier.fillMaxWidth(), subtle = true)
                }
                val rows = listOfNotNull(
                    f.genres.takeIf { it.isNotEmpty() }?.let { tr("Genre", "النوع") to it.joinToString("، ".takeIf { Lang.isAr } ?: ", ") },
                    f.ratings.takeIf { it.isNotEmpty() }?.let { tr("Age rating", "التصنيف العمري") to it.joinToString(" · ") },
                    formatRelease(f.release, lang)?.let { tr("Release date", "تاريخ الإصدار") to it },
                    f.developers.takeIf { it.isNotEmpty() }?.let { tr("Developer", "المطوّر") to it.joinToString(" / ") },
                    f.publishers.takeIf { it.isNotEmpty() }?.let { tr("Publisher", "الناشر") to it.joinToString(" / ") },
                    f.modes.takeIf { it.isNotEmpty() }?.let { tr("Players", "اللاعبون") to it.joinToString(" · ") },
                    f.series?.let { tr("Series", "السلسلة") to it },
                    f.platforms.takeIf { it.isNotEmpty() }?.let { tr("Platforms", "المنصات") to it.joinToString(" · ") },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows.forEach { (k, v) -> FactRow(k, v) } }
                val uri = LocalUriHandler.current
                Dim(tr("Source: Wikipedia and Wikidata (CC BY-SA 4.0 / CC0). The premise is shortened and filtered to avoid spoilers.", "المصدر: ويكيبيديا وويكيداتا (CC BY-SA 4.0 / CC0). النبذة مختصرة ومنقّاة لتجنّب الحرق."))
                f.pageUrl?.let { u -> TextButton({ runCatching { uri.openUri(u) } }) { Lbl(tr("Open the article", "افتح المقال")) } }
            }
        }
    }
}
