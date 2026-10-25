package com.abdo.ps4monitor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Theme engine. One representation ([AppTheme], ARGB ints) is shared by built-in presets, user profiles and the
 * palettes generated from a game's cover, so every component only ever reads tokens and never hardcodes colours.
 */
@Immutable
data class AppTheme(
    val id: String, val name: String, val builtin: Boolean,
    val bg: Int, val bg2: Int, val surface: Int,
    val accent: Int, val accent2: Int,
    val text: Int, val text2: Int, val border: Int, val progress: Int,
    /** custom/preset profiles only: keep the profile's background but take the current game's accents */
    val gameAccents: Boolean = false,
    /** the preset this profile was copied from (for "restore original values") */
    val basePreset: String? = null,
    /** the non-colour layers of the design (shape, border, divider, texture, ornaments, typography ...) */
    val dna: Dna = Dna.CLASSIC,
    /** hand-made light colours; null = derived automatically from the dark ones (see [Colors.deriveLight]) */
    val light: ColorSet? = null,
) {
    val dark: ColorSet get() = ColorSet(bg, bg2, surface, accent, accent2, text, text2, border, progress)
    /** the colours of one appearance, as stored (light falls back to the derived set) */
    fun colorSet(light: Boolean): ColorSet = if (!light) dark else (this.light ?: Colors.deriveLight(dark))
    /** this theme with the colours of one appearance replaced; the other appearance and every other layer are untouched */
    fun withSet(light: Boolean, c: ColorSet): AppTheme =
        if (light) copy(light = c) else copy(bg = c.bg, bg2 = c.bg2, surface = c.surface, accent = c.accent, accent2 = c.accent2, text = c.text, text2 = c.text2, border = c.border, progress = c.progress)
}

/** The tokens the UI needs beyond what Material has a slot for. */
@Immutable
data class Tokens(
    val bg: Color, val bg2: Color, val surface: Color, val accent: Color, val accent2: Color,
    val text: Color, val text2: Color, val muted: Color, val border: Color, val progress: Color,
    val success: Color, val warning: Color, val error: Color, val onAccent: Color,
)
val LocalTokens = compositionLocalOf { ThemeEngine.tokens(ThemeEngine.DEFAULT) }

enum class ThemeMode { DEFAULT, PRESET, CUSTOM, DYNAMIC }

object ThemeEngine {
    private fun fromSpec(p: PresetSpec) = AppTheme(p.id, p.name, true, p.dark.bg, p.dark.bg2, p.dark.surface, p.dark.accent, p.dark.accent2,
        p.dark.text, p.dark.text2, p.dark.border, p.dark.progress, dna = p.dna, light = p.light)

    /** App default: the deep navy / indigo look of the redesign. */
    val DEFAULT = fromSpec(DnaPresets.DEFAULT)
    val PRESETS: List<AppTheme> = DnaPresets.ALL.map { fromSpec(it) }
    fun preset(id: String?) = PRESETS.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ state (observable)
    var mode by mutableStateOf(ThemeMode.DEFAULT); private set
    var presetId by mutableStateOf("default"); private set
    var activeProfileId by mutableStateOf<String?>(null); private set
    var profiles by mutableStateOf<List<AppTheme>>(emptyList()); private set
    /** optional: keep the chosen global theme but take the current game's accent colours */
    var applyGameAccents by mutableStateOf(false); private set

    /** The saved choice, without any game-specific adaptation. */
    val base: AppTheme get() = when (mode) {
        ThemeMode.PRESET -> preset(presetId) ?: DEFAULT
        ThemeMode.CUSTOM -> profiles.firstOrNull { it.id == activeProfileId } ?: DEFAULT
        else -> DEFAULT
    }

    fun init(sp: android.content.SharedPreferences) {
        mode = runCatching { ThemeMode.valueOf(sp.getString("th_mode", "DEFAULT")!!) }.getOrDefault(ThemeMode.DEFAULT)
        presetId = sp.getString("th_preset", "default") ?: "default"
        activeProfileId = sp.getString("th_profile", null)
        applyGameAccents = sp.getBoolean("th_gameaccent", false)
        profiles = runCatching { parseList(sp.getString("th_profiles", "[]")!!) }.getOrDefault(emptyList())
        if (mode == ThemeMode.CUSTOM && profiles.none { it.id == activeProfileId }) mode = ThemeMode.DEFAULT
    }
    private fun sp() = Store.sp
    fun chooseMode(m: ThemeMode) { mode = m; sp().edit().putString("th_mode", m.name).apply() }
    fun selectPreset(id: String) { if (preset(id) == null) return; presetId = id; chooseMode(ThemeMode.PRESET); sp().edit().putString("th_preset", id).apply() }
    fun selectProfile(id: String) { if (profiles.none { it.id == id }) return; activeProfileId = id; chooseMode(ThemeMode.CUSTOM); sp().edit().putString("th_profile", id).apply() }
    fun setGameAccents(b: Boolean) { applyGameAccents = b; sp().edit().putBoolean("th_gameaccent", b).apply() }
    fun resetToDefault() { presetId = "default"; chooseMode(ThemeMode.DEFAULT); sp().edit().putString("th_preset", "default").apply() }

    // ------------------------------------------------------------------ profiles
    private fun persist() = sp().edit().putString("th_profiles", toJson(profiles).toString()).apply()
    fun newId() = "u" + java.lang.Long.toString(System.nanoTime(), 36)
    /** Saves a user profile (a built-in preset is never overwritten: it is copied under a new id by the caller). */
    fun saveProfile(t: AppTheme, activate: Boolean) {
        val s = sanitize(t.copy(builtin = false, name = t.name.trim().ifBlank { "My theme" }.take(32)))
        profiles = if (profiles.any { it.id == s.id }) profiles.map { if (it.id == s.id) s else it } else profiles + s
        persist(); if (activate) selectProfile(s.id)
    }
    fun duplicate(t: AppTheme): AppTheme { val d = t.copy(id = newId(), name = t.name.take(26) + " copy", builtin = false, basePreset = if (t.builtin) t.id else t.basePreset); saveProfile(d, false); return d }
    fun deleteProfile(id: String) {
        profiles = profiles.filter { it.id != id }; persist()
        if (activeProfileId == id) { activeProfileId = null; sp().edit().remove("th_profile").apply(); if (mode == ThemeMode.CUSTOM) chooseMode(ThemeMode.DEFAULT) }
    }
    fun editableCopy(t: AppTheme) = if (t.builtin) t.copy(id = newId(), name = t.name + " (mine)", builtin = false, basePreset = t.id) else t

    // ------------------------------------------------------------------ validation / contrast
    /** Parses #RGB / #RRGGBB / RRGGBB. Anything else is rejected (null). */
    fun parseHex(s: String): Int? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF000000L or h.toLong(16)).toInt()
    }
    fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** Makes a user theme safe: text and accents are pushed until they are readable on the surfaces they sit on (both appearances). */
    fun sanitize(t: AppTheme): AppTheme {
        val d = Colors.readable(t.dark)
        return t.withSet(false, d).copy(light = t.light?.let { Colors.readable(it) })
    }

    /** Contrast problems of one appearance of a theme, for the editor warning. */
    fun warnings(t: AppTheme, light: Boolean): List<String> = Colors.audit(t.colorSet(light))

    // ------------------------------------------------------------------ appearance (light / dark) is independent of the theme
    /** The theme as it is shown in the given appearance: its own colour set, with every other layer kept. */
    fun resolve(t: AppTheme, dark: Boolean): AppTheme = if (dark) t else t.withSet(false, t.colorSet(true))

    // ------------------------------------------------------------------ tokens -> Material
    fun tokens(t: AppTheme): Tokens {
        val lightUi = ColorMath.luminance(t.bg) > 0.4
        val onAcc = if (ColorMath.contrast(t.accent, 0xFF0B0B10.toInt()) >= ColorMath.contrast(t.accent, 0xFFFFFFFF.toInt())) 0xFF0B0B10.toInt() else 0xFFFFFFFF.toInt()
        return Tokens(Color(t.bg), Color(t.bg2), Color(t.surface), Color(t.accent), Color(t.accent2), Color(t.text), Color(t.text2),
            Color(ColorMath.blend(t.text2, t.bg, 0.35f)), Color(t.border), Color(t.progress),
            if (lightUi) Color(0xFF1B7F3A) else Color(0xFF4ADE80), if (lightUi) Color(0xFF9A5B00) else Color(0xFFFBBF24), if (lightUi) Color(0xFFC62828) else Color(0xFFFF6B7A), Color(onAcc))
    }
    fun scheme(t: AppTheme): ColorScheme {
        val k = tokens(t)
        val hi = Color(ColorMath.blend(t.surface, t.text, 0.08f)); val lo = Color(ColorMath.blend(t.bg, t.surface, 0.5f))
        return if (ColorMath.luminance(t.bg) > 0.4) lightColorScheme(
            primary = k.accent, onPrimary = k.onAccent, primaryContainer = Color(ColorMath.blend(t.surface, t.accent, 0.28f)), onPrimaryContainer = k.text,
            secondary = k.accent2, onSecondary = k.onAccent, secondaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.18f)), onSecondaryContainer = k.text,
            tertiary = k.accent2, onTertiary = k.onAccent, tertiaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.22f)), onTertiaryContainer = k.text,
            background = k.bg, onBackground = k.text, surface = k.bg, onSurface = k.text, onSurfaceVariant = k.text2,
            surfaceVariant = k.surface, surfaceTint = k.accent,
            surfaceContainerLowest = k.bg, surfaceContainerLow = lo, surfaceContainer = k.bg2, surfaceContainerHigh = k.surface, surfaceContainerHighest = hi,
            outline = Color(ColorMath.blend(t.border, t.text2, 0.35f)), outlineVariant = k.border,
            error = k.error, onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
        )
        else darkColorScheme(
            primary = k.accent, onPrimary = k.onAccent, primaryContainer = Color(ColorMath.blend(t.surface, t.accent, 0.28f)), onPrimaryContainer = k.text,
            secondary = k.accent2, onSecondary = k.onAccent, secondaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.18f)), onSecondaryContainer = k.text,
            tertiary = k.accent2, onTertiary = k.onAccent, tertiaryContainer = Color(ColorMath.blend(t.surface, t.accent2, 0.22f)), onTertiaryContainer = k.text,
            background = k.bg, onBackground = k.text, surface = k.bg, onSurface = k.text, onSurfaceVariant = k.text2,
            surfaceVariant = k.surface, surfaceTint = k.accent,
            surfaceContainerLowest = k.bg, surfaceContainerLow = lo, surfaceContainer = k.bg2, surfaceContainerHigh = k.surface, surfaceContainerHighest = hi,
            outline = Color(ColorMath.blend(t.border, t.text2, 0.35f)), outlineVariant = k.border,
            error = k.error, onError = Color(0xFF2B0A0E), errorContainer = Color(0xFF4A1A22), onErrorContainer = Color(0xFFFFD9DD),
        )
    }

    // ------------------------------------------------------------------ game themes
    private val gameCache = ConcurrentHashMap<String, AppTheme>()
    private val gameFp = ConcurrentHashMap<String, Long>()
    private val genReports = ConcurrentHashMap<String, List<Pair<String, Float>>>()
    fun cachedGame(titleId: String): AppTheme? = gameCache[titleId]
    /** value of [assign] meaning "the design generated from this game's own artwork" */
    const val COVER = "cover"
    /** the measurements behind the generated design of this game (for the "why this design" panel) */
    fun reportFor(titleId: String): List<Pair<String, Float>>? = genReports[titleId]

    /** The design generated from this game's artwork, if it was already computed for exactly this artwork + analyser version (memory, then storage). */
    fun generatedFor(titleId: String, fp: Long): AppTheme? {
        gameCache[titleId]?.let { if (gameFp[titleId] == fp) return it }
        val s = runCatching { sp().getString("th_gen_$titleId", null) }.getOrNull() ?: return null
        return runCatching {
            val o = JSONObject(s)
            if (o.optLong("fp") != fp) return null
            val t = fromJson(o.getJSONObject("theme"), true)?.copy(id = "game:$titleId", name = "Auto design", builtin = true) ?: return null
            gameCache[titleId] = t; gameFp[titleId] = fp
            o.optJSONObject("report")?.let { r -> genReports[titleId] = r.keys().asSequence().map { it to r.getDouble(it).toFloat() }.toList() }
            t
        }.getOrNull()
    }
    /** Turns a generated design into a theme, caches it in memory and in storage (colours, design layers and the measurements), keyed by the artwork fingerprint. */
    fun storeGenerated(titleId: String, fp: Long, g: GeneratedDesign): AppTheme {
        val d = g.dark
        val t = sanitize(AppTheme("game:$titleId", "Auto design", true, d.bg, d.bg2, d.surface, d.accent, d.accent2, d.text, d.text2, d.border, d.progress, dna = g.dna, light = g.light))
        val rep = g.profile.report()
        gameCache[titleId] = t; gameFp[titleId] = fp; genReports[titleId] = rep
        runCatching { sp().edit().putString("th_gen_$titleId", JSONObject().put("fp", fp).put("theme", toJson(t)).put("report", JSONObject().also { r -> rep.forEach { (k, v) -> r.put(k, v.toDouble()) } }).toString()).apply() }
        return t
    }

    /**
     * Builds a coordinated theme from a cover analysis (see [CoverPalette.analyze]): background and surfaces are very dark
     * tints of the dominant hue, the accent is the dominant hue lifted to a readable lightness, the second accent is the
     * cover's second hue (or a shifted hue when the cover has only one). Computed once per game and cached.
     */
    fun fromCover(titleId: String, a: CoverAnalysis): AppTheme = gameCache.getOrPut(titleId) {
        val hsl = ColorMath.toHsl(a.primary); val h = hsl[0]
        val sat = (hsl[1] * (0.2f + 0.8f * minOf(1f, a.colorfulness / 0.25f))).coerceIn(0.12f, 0.75f)
        val h2 = a.secondary?.let { ColorMath.toHsl(it)[0] } ?: (h + 38f)
        val s2 = a.secondary?.let { ColorMath.toHsl(it)[1].coerceIn(0.35f, 0.8f) } ?: sat
        sanitize(AppTheme("game:$titleId", "Game", true,
            bg = ColorMath.fromHsl(h, (sat * 0.55f).coerceIn(0.08f, 0.45f), 0.045f),
            bg2 = ColorMath.fromHsl(h, (sat * 0.55f).coerceIn(0.08f, 0.45f), 0.075f),
            surface = ColorMath.fromHsl(h, (sat * 0.50f).coerceIn(0.08f, 0.42f), 0.115f),
            accent = ColorMath.fromHsl(h, (sat + 0.15f).coerceIn(0.3f, 0.9f), 0.64f),
            accent2 = ColorMath.fromHsl(h2, s2, 0.66f),
            text = ColorMath.fromHsl(h, 0.25f, 0.96f), text2 = ColorMath.fromHsl(h, 0.18f, 0.74f),
            border = ColorMath.fromHsl(h, (sat * 0.5f).coerceIn(0.1f, 0.4f), 0.24f),
            progress = ColorMath.fromHsl(h, (sat + 0.15f).coerceIn(0.3f, 0.9f), 0.64f)))
    }

    /** Per game: a theme the user assigned to this title (preset or profile id), or null = automatic. */
    fun assignedId(titleId: String): String? = runCatching { sp().getString("th_game_$titleId", null) }.getOrNull()
    fun assign(titleId: String, id: String?) { sp().edit().apply { if (id == null) remove("th_game_$titleId") else putString("th_game_$titleId", id) }.apply(); assignedTick++ }
    /** bumped on every change so the game page recomposes */
    var assignedTick by mutableStateOf(0); private set
    fun byId(id: String?): AppTheme? = if (id == null) null else (preset(id) ?: profiles.firstOrNull { it.id == id })
    fun assignedTheme(titleId: String): AppTheme? { assignedTick; val id = assignedId(titleId); return if (id == COVER) cachedGame(titleId) else byId(id) }

    /**
     * The theme the game pages use. Order: a theme the user assigned to this game; the game's own in Dynamic mode; the saved theme
     * (optionally with the game's accents); otherwise the saved theme. The game page never changes any other screen.
     */
    fun forGame(game: AppTheme?, titleId: String = ""): AppTheme {
        assignedTheme(titleId)?.let { return it }
        val b = base
        if (game == null) return b
        return when {
            mode == ThemeMode.DYNAMIC -> game
            (mode == ThemeMode.PRESET || mode == ThemeMode.CUSTOM) && applyGameAccents -> b.copy(accent = game.accent, accent2 = game.accent2, progress = game.progress)
            else -> b
        }
    }

    // ------------------------------------------------------------------ JSON (storage + export/import)
    private fun toJson(l: List<AppTheme>) = JSONArray(l.map { toJson(it) })
    fun toJson(t: AppTheme): JSONObject = JSONObject().put("v", 2).put("id", t.id).put("name", t.name)
        .put("bg", hex(t.bg)).put("bg2", hex(t.bg2)).put("surface", hex(t.surface)).put("accent", hex(t.accent)).put("accent2", hex(t.accent2))
        .put("text", hex(t.text)).put("text2", hex(t.text2)).put("border", hex(t.border)).put("progress", hex(t.progress))
        .put("gameAccents", t.gameAccents).put("base", t.basePreset ?: JSONObject.NULL)
        .put("dna", DnaJson.toJson(t.dna)).put("light", t.light?.let { DnaJson.colors(it) } ?: JSONObject.NULL)
    private fun parseList(s: String): List<AppTheme> { val a = JSONArray(s); return (0 until a.length()).mapNotNull { fromJson(a.getJSONObject(it), keepId = true) } }
    /** Strict import: every colour must be a valid hex colour, the name is trimmed, nothing else in the file is read. */
    fun fromJson(o: JSONObject, keepId: Boolean): AppTheme? {
        val name = o.optString("name", "").trim().take(32).ifBlank { return null }
        val bg = parseHex(o.optString("bg", "")) ?: return null
        val bg2 = parseHex(o.optString("bg2", "")) ?: return null
        val sf = parseHex(o.optString("surface", "")) ?: return null
        val ac = parseHex(o.optString("accent", "")) ?: return null
        val ac2 = parseHex(o.optString("accent2", "")) ?: return null
        val tx = parseHex(o.optString("text", "")) ?: return null
        val tx2 = parseHex(o.optString("text2", "")) ?: return null
        val bd = parseHex(o.optString("border", "")) ?: return null
        val pr = parseHex(o.optString("progress", "")) ?: return null
        val t = AppTheme(if (keepId) o.optString("id", "").ifBlank { newId() } else newId(), name, false, bg, bg2, sf, ac, ac2, tx, tx2, bd, pr,
            o.optBoolean("gameAccents", false), o.optString("base", "").takeIf { preset(it) != null },
            dna = o.optJSONObject("dna")?.let { DnaJson.fromJson(it) } ?: Dna.CLASSIC, light = o.optJSONObject("light")?.let { DnaJson.colorsOrNull(it) })
        return sanitize(t)
    }
    fun importJson(text: String): AppTheme? {
        if (text.length > 8000) return null
        val o = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return null
        val t = fromJson(o, keepId = false) ?: return null
        saveProfile(t, false); return t
    }
}
