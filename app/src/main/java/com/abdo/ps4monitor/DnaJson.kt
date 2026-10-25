package com.abdo.ps4monitor
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON of the non-colour layers (and of a light colour set). Strict on purpose: unknown enum names fall back to the neutral value of the layer,
 * numbers are clamped, the ornament list is capped, nothing else in the file is read. The same format is used for storage, export / import and
 * for the files the visual analyser will produce.
 */
object DnaJson {
    fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
    fun parseHex(s: String): Int? {
        var h = s.trim().removePrefix("#")
        if (h.length == 3) h = h.map { "$it$it" }.joinToString("")
        if (h.length != 6 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return (0xFF000000L or h.toLong(16)).toInt()
    }
    private inline fun <reified E : Enum<E>> en(o: JSONObject, k: String, def: E): E =
        runCatching { enumValueOf<E>(o.optString(k, def.name)) }.getOrDefault(def)
    private fun f(o: JSONObject, k: String, def: Float, lo: Float, hi: Float) = o.optDouble(k, def.toDouble()).toFloat().let { if (it.isNaN()) def else it }.coerceIn(lo, hi)

    fun colors(c: ColorSet): JSONObject = JSONObject().put("bg", hex(c.bg)).put("bg2", hex(c.bg2)).put("surface", hex(c.surface))
        .put("accent", hex(c.accent)).put("accent2", hex(c.accent2)).put("text", hex(c.text)).put("text2", hex(c.text2))
        .put("border", hex(c.border)).put("progress", hex(c.progress))

    /** null when any of the nine colours is missing or invalid (the theme then simply derives its light colours). */
    fun colorsOrNull(o: JSONObject): ColorSet? {
        fun h(k: String) = parseHex(o.optString(k, ""))
        return ColorSet(h("bg") ?: return null, h("bg2") ?: return null, h("surface") ?: return null, h("accent") ?: return null, h("accent2") ?: return null,
            h("text") ?: return null, h("text2") ?: return null, h("border") ?: return null, h("progress") ?: return null)
    }

    fun toJson(d: Dna): JSONObject = JSONObject()
        .put("corner", d.shape.corner.name).put("cornerScale", d.shape.scale.toDouble()).put("cornerRadius", d.shape.radius)
        .put("border", d.border.kind.name).put("borderWidth", d.border.width.toDouble())
        .put("rule", d.rule.name).put("surface", d.surface.name)
        .put("texture", d.texture.kind.name).put("textureIntensity", d.texture.intensity.toDouble()).put("textureCards", d.texture.onCards)
        .put("ornaments", JSONArray(d.ornaments.map { JSONObject().put("kind", it.kind.name).put("density", it.density.toDouble()).put("alpha", it.alpha.toDouble()) }))
        .put("titleFont", d.type.title.name).put("bodyFont", d.type.body.name).put("titleWeight", d.type.titleWeight).put("tracking", d.type.tracking.toDouble())
        .put("spacing", d.spacing.toDouble()).put("hero", d.hero.name).put("tabs", d.tabs.name).put("progress", d.progress.name)

    fun fromJson(o: JSONObject): Dna {
        val orn = o.optJSONArray("ornaments")
        val list = if (orn == null) emptyList() else (0 until minOf(orn.length(), 6)).mapNotNull { i ->
            val x = orn.optJSONObject(i) ?: return@mapNotNull null
            val k = runCatching { enumValueOf<OrnamentKind>(x.optString("kind")) }.getOrNull() ?: return@mapNotNull null
            OrnamentDna(k, f(x, "density", 1f, 0f, 1.5f), f(x, "alpha", 0.5f, 0f, 1f))
        }.distinctBy { it.kind }
        return Dna(
            shape = ShapeDna(en(o, "corner", Corner.ROUND), f(o, "cornerScale", 1f, 0.5f, 1.6f), o.optInt("cornerRadius", 4).coerceIn(0, 8)),
            border = BorderDna(en(o, "border", BorderKind.SINGLE), f(o, "borderWidth", 1f, 0.5f, 2f)),
            rule = en(o, "rule", RuleKind.HAIRLINE), surface = en(o, "surface", SurfaceKind.GRADIENT),
            texture = TextureDna(en(o, "texture", TextureKind.NONE), f(o, "textureIntensity", 0f, 0f, 1f), o.optBoolean("textureCards", false)),
            ornaments = list,
            type = TypeDna(en(o, "titleFont", FontKind.DEFAULT), en(o, "bodyFont", FontKind.DEFAULT), o.optInt("titleWeight", 0).let { if (it == 0) 0 else it.coerceIn(300, 900) }, f(o, "tracking", 0f, 0f, 0.3f)),
            spacing = f(o, "spacing", 1f, 0.85f, 1.25f), hero = en(o, "hero", HeroKind.FADE), tabs = en(o, "tabs", TabKind.PILL), progress = en(o, "progress", ProgressKind.BAR))
    }
}
