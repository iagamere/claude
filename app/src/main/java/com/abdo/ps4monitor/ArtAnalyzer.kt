package com.abdo.ps4monitor

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/*
 * Local (offline, free) artwork analysis. Pure Kotlin: it works on ARGB pixel arrays, so it is unit-tested on a plain JVM with the real covers.
 *
 * What it measures (all of it on a downscaled copy, once per artwork, off the main thread):
 *   - lightness distribution, chroma distribution, negative space (flat regions), edge density, glow (bright saturated areas)
 *   - the salient accent hue(s) (chroma-weighted, so a small gold band or fire counts even if it is 1% of the pixels), and the ground tones
 *   - six style scores built ONLY from hue-independent measurements, so rotating the hues of a cover never changes its structure
 * What it can NOT do (needs a vision model or a human): recognise motifs (leaves, waves, chains), read or classify the logo typography,
 * understand meaning. Those layers come from presets, imported JSON or the optional visual analysis; the report says so.
 */

class Raster(val w: Int, val h: Int, val px: IntArray) {
    init { require(w > 0 && h > 0 && px.size == w * h) { "bad raster" } }

    /** Area-average downscale so that the longest side is at most [maxSide] (never upscales). */
    fun fit(maxSide: Int): Raster {
        val s = maxSide.toDouble() / max(w, h)
        if (s >= 1.0) return this
        val nw = max(1, (w * s).toInt()); val nh = max(1, (h * s).toInt())
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = y * h / nh; val y1 = max(y0 + 1, (y + 1) * h / nh)
            for (x in 0 until nw) {
                val x0 = x * w / nw; val x1 = max(x0 + 1, (x + 1) * w / nw)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) { val p = px[yy * w + xx]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++ }
                out[y * nw + x] = ColorMath.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
        }
        return Raster(nw, nh, out)
    }
}

/** What was measured, plus the six hue-independent style scores (0..1). */
class ArtProfile(
    val meanL: Float, val dark: Float, val light: Float, val meanC: Float, val colorful: Float, val vivid: Float, val peakC: Float,
    val edge: Float, val flat: Float, val glow: Float, val hueSpread: Int,
    val accent: Int?, val accent2: Int?, val darkTone: Int?, val lightTone: Int?,
    val styles: Map<ArtStyle, Float>,
    /** how much to trust the result: low for tiny / blank / near-uniform artwork */
    val confidence: Float,
) {
    val top: ArtStyle? get() = styles.maxByOrNull { it.value }?.takeIf { it.value >= 0.35f }?.key
    /** Flat key / value list that is stored with the generated theme and shown to the user ("why this design"). */
    fun report(): List<Pair<String, Float>> = styles.map { it.key.name to it.value } +
        listOf("light" to light, "dark" to dark, "colorful" to colorful, "flat" to flat, "edge" to edge, "confidence" to confidence)
}

enum class ArtStyle { PAPER, LUMINOUS, DRAMATIC, NEON, MINIMAL, SOFT }

object ArtAnalyzer {
    private const val ANALYSIS_SIDE = 160
    /** Bump when the analysis or the generator changes, so cached designs are recomputed. */
    const val VERSION = 3

    /** Identifies an artwork + analyser version: a cached design is reused only while this stays the same (12x12 area-averaged thumbnail, FNV-1a). */
    fun fingerprint(rasters: List<Raster>): Long {
        var h = -3750763034362895579L xor VERSION.toLong()
        for (r in rasters) {
            val t = r.fit(12); h = (h xor t.w.toLong()) * 1099511628211L; h = (h xor t.h.toLong()) * 1099511628211L
            for (p in t.px) h = (h xor (p and 0xFFFFFF).toLong()) * 1099511628211L
        }
        return h
    }
    private val lin = FloatArray(256) { val c = it / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }

    internal fun ramp(x: Float, lo: Float, hi: Float) = ((x - lo) / (hi - lo)).coerceIn(0f, 1f)

    /** CIE L*a*b* (D65) of an sRGB colour. */
    internal fun lab(argb: Int, out: FloatArray) {
        val r = lin[(argb shr 16) and 0xFF]; val g = lin[(argb shr 8) and 0xFF]; val b = lin[argb and 0xFF]
        val x = (0.4124f * r + 0.3576f * g + 0.1805f * b) / 0.95047f
        val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val z = (0.0193f * r + 0.1192f * g + 0.9505f * b) / 1.08883f
        fun f(t: Float) = if (t > 0.008856f) cbrt(t) else 7.787f * t + 16f / 116f
        val fx = f(x); val fy = f(y); val fz = f(z)
        out[0] = 116f * fy - 16f; out[1] = 500f * (fx - fy); out[2] = 200f * (fy - fz)
    }

    /**
     * [rasters] are the available artworks of one game (e.g. the wide banner and the square cover), with [weights] (same length).
     * Never throws: blank, tiny or transparent images give a low-confidence neutral profile.
     */
    fun analyze(rasters: List<Raster>, weights: List<Float> = rasters.map { 1f }): ArtProfile {
        val parts = rasters.mapIndexedNotNull { i, r -> if (r.w < 4 || r.h < 4) null else r.fit(ANALYSIS_SIDE) to weights.getOrElse(i) { 1f } }
        if (parts.isEmpty()) return neutral()
        val wsum = parts.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-3f)
        var meanL = 0f; var dark = 0f; var light = 0f; var meanC = 0f; var colorful = 0f; var vivid = 0f; var peakC = 0f
        var edge = 0f; var flat = 0f; var glow = 0f; var spread = 0f; var spanL = 0f
        val per = parts.map { (r, w) -> Measured(r).also { m ->
            val k = w / wsum
            meanL += m.meanL * k; dark += m.dark * k; light += m.light * k; meanC += m.meanC * k; colorful += m.colorful * k; vivid += m.vivid * k; peakC += m.peakC * k
            edge += m.edge * k; flat += m.flat * k; glow += m.glow * k; spanL += (m.p95 - m.p5) * k
        } }
        // salient hues: chroma-weighted histogram over all artworks
        val cThr = max(8f, peakC * 0.5f)
        val hw = DoubleArray(36); val hr = DoubleArray(36); val hg = DoubleArray(36); val hb = DoubleArray(36)       // bright + colourful pixels
        val aw = DoubleArray(36); val ar = DoubleArray(36); val ag = DoubleArray(36); val ab = DoubleArray(36)       // any colourful pixel (fallback)
        val dr = DoubleArray(3); var dn = 0.0; val lr = DoubleArray(3); var ln = 0.0
        val tmp = FloatArray(3)
        parts.forEachIndexed { i, (r, w) ->
            val k = (w / wsum).toDouble() / (r.w * r.h)
            for (p in r.px) {
                lab(p, tmp); val l = tmp[0]; val c = hypot(tmp[1], tmp[2])
                if (c >= cThr && l in 18f..92f) {
                    var h = Math.toDegrees(Math.atan2(tmp[2].toDouble(), tmp[1].toDouble())); if (h < 0) h += 360.0
                    val bin = (h / 10.0).toInt().coerceIn(0, 35); val wt = (c * c).toDouble() * k
                    aw[bin] += wt; ar[bin] += ColorMath.red(p) * wt; ag[bin] += ColorMath.green(p) * wt; ab[bin] += ColorMath.blue(p) * wt
                    if (l >= 50f) { hw[bin] += wt; hr[bin] += ColorMath.red(p) * wt; hg[bin] += ColorMath.green(p) * wt; hb[bin] += ColorMath.blue(p) * wt }
                }
                if (l < 22f) { dr[0] += ColorMath.red(p) * k; dr[1] += ColorMath.green(p) * k; dr[2] += ColorMath.blue(p) * k; dn += k }
                else if (l > 78f) { lr[0] += ColorMath.red(p) * k; lr[1] += ColorMath.green(p) * k; lr[2] += ColorMath.blue(p) * k; ln += k }
            }
        }
        if (hw.sum() < aw.sum() * 0.05) { aw.copyInto(hw); ar.copyInto(hr); ag.copyInto(hg); ab.copyInto(hb) }
        fun smooth(i: Int) = hw[(i + 35) % 36] * 0.5 + hw[i] + hw[(i + 1) % 36] * 0.5
        fun binColor(i: Int): Int { var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
            for (d in -1..1) { val j = (i + d + 36) % 36; r += hr[j]; g += hg[j]; b += hb[j]; w += hw[j] }
            return if (w <= 0.0) 0 else ColorMath.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt()) }
        var best = -1; for (i in 0 until 36) if (smooth(i) > 0.0 && (best < 0 || smooth(i) > smooth(best))) best = i
        var second = -1
        if (best >= 0) for (i in 0 until 36) {
            val d = min(abs(i - best), 36 - abs(i - best)); if (d < 5 || smooth(i) < smooth(best) * 0.3) continue
            if (second < 0 || smooth(i) > smooth(second)) second = i
        }
        spread = if (best < 0) 0f else 1f + (if (second >= 0) 1f else 0f)
        var third = -1
        if (second >= 0) for (i in 0 until 36) {
            val d1 = min(abs(i - best), 36 - abs(i - best)); val d2 = min(abs(i - second), 36 - abs(i - second)); if (d1 < 5 || d2 < 5 || smooth(i) < smooth(best) * 0.3) continue
            if (third < 0 || smooth(i) > smooth(third)) third = i
        }
        if (third >= 0) spread = 3f
        val accent = if (best >= 0) binColor(best) else null
        val accent2 = if (second >= 0) binColor(second) else null
        val darkTone = if (dn > 0.04) ColorMath.rgb((dr[0] / dn).toInt(), (dr[1] / dn).toInt(), (dr[2] / dn).toInt()) else null
        val lightTone = if (ln > 0.04) ColorMath.rgb((lr[0] / ln).toInt(), (lr[1] / ln).toInt(), (lr[2] / ln).toInt()) else null

        // style scores: hue-independent on purpose
        val paper = ramp(light, 0.2f, 0.45f) * (1f - ramp(meanC, 14f, 28f)) * (1f - ramp(colorful, 0.05f, 0.2f))
        val luminous = ramp(colorful, 0.25f, 0.5f) * (1f - ramp(light, 0.3f, 0.55f)) * (1f - ramp(dark, 0.75f, 0.9f))
        val dramatic = ramp(dark, 0.45f, 0.7f) * (1f - ramp(colorful, 0.15f, 0.35f)) * ramp(peakC, 22f, 40f)
        val neon = ramp(vivid, 0.12f, 0.3f) * ramp(dark, 0.3f, 0.55f) * ramp(spread, 1.5f, 2.5f)
        val minimal = ramp(flat, 0.45f, 0.7f) * (1f - ramp(edge, 2.5f, 5f)) * (1f - ramp(meanC, 25f, 40f))
        val soft = ramp(light, 0.3f, 0.5f) * ramp(meanC, 14f, 26f) * (1f - ramp(edge, 5f, 9f))
        val flatImage = 1f - ramp(spanL, 10f, 35f)                    // almost no tonal range: nothing to learn from
        val conf = ((1f - flatImage) * ramp(sqrt(parts.sumOf { it.first.w.toDouble() * it.first.h }).toFloat(), 8f, 48f)).coerceIn(0f, 1f)
        return ArtProfile(meanL, dark, light, meanC, colorful, vivid, peakC, edge, flat, glow, spread.toInt(), accent, accent2, darkTone, lightTone,
            mapOf(ArtStyle.PAPER to paper, ArtStyle.LUMINOUS to luminous, ArtStyle.DRAMATIC to dramatic, ArtStyle.NEON to neon, ArtStyle.MINIMAL to minimal, ArtStyle.SOFT to soft), conf)
    }

    private fun neutral() = ArtProfile(40f, 0.4f, 0.1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, null, null, null, null, ArtStyle.values().associateWith { 0f }, 0f)

    /** Per-artwork measurements. */
    private class Measured(r: Raster) {
        val meanL: Float; val dark: Float; val light: Float; val meanC: Float; val colorful: Float; val vivid: Float; val peakC: Float
        val edge: Float; val flat: Float; val glow: Float; val p5: Float; val p95: Float
        init {
            val n = r.w * r.h; val l = FloatArray(n); val c = FloatArray(n); val t = FloatArray(3)
            var opaque = 0
            for (i in 0 until n) { val p = r.px[i]; lab(p, t); l[i] = t[0]; c[i] = hypot(t[1], t[2]); if (ColorMath.alpha(p) >= 128) opaque++ }
            val sl = l.sortedArray(); val sc = c.sortedArray()
            fun q(a: FloatArray, f: Float) = a[(f * (a.size - 1)).toInt()]
            p5 = q(sl, 0.05f); p95 = q(sl, 0.95f); peakC = q(sc, 0.99f)
            meanL = l.average().toFloat(); meanC = c.average().toFloat()
            dark = l.count { it < 25f }.toFloat() / n; light = l.count { it > 75f }.toFloat() / n
            colorful = c.count { it > 30f }.toFloat() / n; vivid = c.count { it > 55f }.toFloat() / n
            glow = (0 until n).count { l[it] > 55f && c[it] > 38f }.toFloat() / n
            // gradient magnitude of a 3x3-blurred luminance
            val b = FloatArray(n)
            for (y in 0 until r.h) for (x in 0 until r.w) {
                var s = 0f; var k = 0
                for (dy in -1..1) for (dx in -1..1) { val yy = (y + dy).coerceIn(0, r.h - 1); val xx = (x + dx).coerceIn(0, r.w - 1); s += l[yy * r.w + xx]; k++ }
                b[y * r.w + x] = s / k
            }
            val mag = FloatArray(n); var sum = 0.0
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val x0 = max(0, x - 1); val x1 = min(r.w - 1, x + 1); val y0 = max(0, y - 1); val y1 = min(r.h - 1, y + 1)
                val gx = (b[y * r.w + x1] - b[y * r.w + x0]) / (x1 - x0).coerceAtLeast(1); val gy = (b[y1 * r.w + x] - b[y0 * r.w + x]) / (y1 - y0).coerceAtLeast(1)
                val m = hypot(gx, gy); mag[y * r.w + x] = m; sum += m
            }
            edge = (sum / n).toFloat()
            // negative space: 8x8 blocks with almost no gradient
            val bs = 8; val bw = r.w / bs; val bh = r.h / bs; var flatBlocks = 0
            for (by in 0 until bh) for (bx in 0 until bw) {
                var s = 0f
                for (yy in 0 until bs) for (xx in 0 until bs) s += mag[(by * bs + yy) * r.w + bx * bs + xx]
                if (s / (bs * bs) < 1.5f) flatBlocks++
            }
            flat = if (bw * bh == 0) 0f else flatBlocks.toFloat() / (bw * bh)
        }
    }
}
