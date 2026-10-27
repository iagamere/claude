@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package com.abdo.ps4monitor
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.times
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The components of the Design DNA. Every component reads only the layers it needs from [LocalDna] and the colours from the Material scheme,
 * so a layer (corners, border, divider, texture, ornaments, progress, tabs, hero, typography) can change without touching the others.
 * Ornaments and textures are generated procedurally (seeded, so they never flicker), cached per size, drawn with one call per kind, and capped.
 */
val LocalDna = compositionLocalOf { Dna.CLASSIC }

/** Light / dark / system, independent of the theme. */
@Composable fun appDark(): Boolean = when (Store.theme.intValue) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }

// ------------------------------------------------------------------ shapes and typography
fun shapeOf(d: Dna, r: Int): CornerBasedShape = when (d.shape.corner) {
    Corner.ROUND -> RoundedCornerShape((r * d.shape.scale).dp)
    Corner.CUT -> CutCornerShape((r * 0.6f * d.shape.scale).coerceAtLeast(6f).dp)
    Corner.SQUARE -> RoundedCornerShape(minOf(r, d.shape.radius).dp)
    Corner.SLANT -> CutCornerShape((r * 0.5f).coerceAtLeast(6f).dp)       // stand-in for Material components; cards use [slantShape]
}
/** Sporty card: the top-left and bottom-right corners are cut on the diagonal. */
fun slantShape(r: Int): Shape = GenericShape { size, _ ->
    val c = minOf(size.height * 0.22f, r * 3.4f)
    moveTo(c, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height - c); lineTo(size.width - c, size.height); lineTo(0f, size.height); lineTo(0f, c); close()
}
private val SlantTab: Shape = GenericShape { size, _ ->
    val k = size.height * 0.3f
    moveTo(k, 0f); lineTo(size.width, 0f); lineTo(size.width - k, size.height); lineTo(0f, size.height); close()
}
@Composable fun dnaShape(r: Int): Shape { val d = LocalDna.current; return remember(d, r) { if (d.shape.corner == Corner.SLANT) slantShape(r) else shapeOf(d, r) } }
fun dnaShapes(d: Dna) = Shapes(small = shapeOf(d, 10), medium = shapeOf(d, 14), large = shapeOf(d, 18), extraLarge = shapeOf(d, 24))

private val condensedFamily: FontFamily by lazy { FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL)) }
// bundled open-licence fonts (SIL OFL, licences in assets/licenses); Arabic text falls back to the system face glyph by glyph
private val displayFamily: FontFamily by lazy { FontFamily(Font(R.font.bebas_neue)) }
private val pixelFamily: FontFamily by lazy { FontFamily(Font(R.font.press_start_2p)) }
private val romanFamily: FontFamily by lazy { FontFamily(Font(R.font.cinzel, FontWeight.Normal), Font(R.font.cinzel, FontWeight.SemiBold), Font(R.font.cinzel, FontWeight.Bold)) }
private val techFamily: FontFamily by lazy { FontFamily(Font(R.font.orbitron, FontWeight.Normal), Font(R.font.orbitron, FontWeight.SemiBold), Font(R.font.orbitron, FontWeight.Bold)) }
private fun family(k: FontKind): FontFamily = when (k) {
    FontKind.DEFAULT -> FontFamily.Default; FontKind.SANS -> FontFamily.SansSerif; FontKind.SERIF -> FontFamily.Serif; FontKind.CONDENSED -> condensedFamily
    FontKind.DISPLAY -> displayFamily; FontKind.ROMAN -> romanFamily; FontKind.TECH -> techFamily; FontKind.PIXEL -> pixelFamily
}
/** single-weight faces must never be faux-bolded */
private fun singleWeight(k: FontKind) = k == FontKind.DISPLAY || k == FontKind.PIXEL
private fun androidx.compose.ui.text.TextStyle.sc(f: Float): androidx.compose.ui.text.TextStyle =
    if (f == 1f) this else copy(fontSize = if (fontSize.isSpecified) fontSize * f else fontSize, lineHeight = if (lineHeight.isSpecified) lineHeight * f else lineHeight)
/** the wide display faces are drawn smaller so that titles take the same room as the default face, the condensed one a little larger */
private fun titleFactor(k: FontKind) = when (k) { FontKind.TECH -> 0.88f; FontKind.PIXEL -> 0.7f; FontKind.ROMAN -> 0.95f; FontKind.DISPLAY -> 1.12f; else -> 1f }
private fun scaledTypography(f: Float): Typography {
    val b = Typography(); if (f == 1f) return b
    return b.copy(displayLarge = b.displayLarge.sc(f), displayMedium = b.displayMedium.sc(f), displaySmall = b.displaySmall.sc(f),
        headlineLarge = b.headlineLarge.sc(f), headlineMedium = b.headlineMedium.sc(f), headlineSmall = b.headlineSmall.sc(f),
        titleLarge = b.titleLarge.sc(f), titleMedium = b.titleMedium.sc(f), titleSmall = b.titleSmall.sc(f),
        bodyLarge = b.bodyLarge.sc(f), bodyMedium = b.bodyMedium.sc(f), bodySmall = b.bodySmall.sc(f),
        labelLarge = b.labelLarge.sc(f), labelMedium = b.labelMedium.sc(f), labelSmall = b.labelSmall.sc(f))
}
/** All text sizes follow the user's "text size" choice; title roles take the design's face, weight and tracking; body and label roles take its body face. Arabic keeps its joined letters (no tracking). */
@Composable fun dnaTypography(d: Dna): Typography {
    val ar = Lang.isAr; val f = ThemeEngine.textFactor()
    return remember(d, ar, f) {
        val t = d.type; val base = scaledTypography(f)
        if (t.title == FontKind.DEFAULT && t.body == FontKind.DEFAULT && t.titleWeight == 0 && t.tracking == 0f) base
        else {
            val tf = family(t.title); val bf = family(t.body); val k = titleFactor(t.title)
            fun ti(s: androidx.compose.ui.text.TextStyle) = s.sc(k).copy(fontFamily = tf, fontWeight = if (t.titleWeight > 0) FontWeight(t.titleWeight) else s.fontWeight,
                letterSpacing = if (ar || t.tracking <= 0f) s.letterSpacing else t.tracking.em, fontSynthesis = if (singleWeight(t.title)) FontSynthesis.None else s.fontSynthesis)
            fun bo(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = bf, fontSynthesis = if (singleWeight(t.body)) FontSynthesis.None else s.fontSynthesis)
            base.copy(displayLarge = ti(base.displayLarge), displayMedium = ti(base.displayMedium), displaySmall = ti(base.displaySmall),
                headlineLarge = ti(base.headlineLarge), headlineMedium = ti(base.headlineMedium), headlineSmall = ti(base.headlineSmall),
                titleLarge = ti(base.titleLarge), titleMedium = ti(base.titleMedium), titleSmall = ti(base.titleSmall),
                bodyLarge = bo(base.bodyLarge), bodyMedium = bo(base.bodyMedium), bodySmall = bo(base.bodySmall),
                labelLarge = bo(base.labelLarge), labelMedium = bo(base.labelMedium), labelSmall = bo(base.labelSmall))
        }
    }
}

// ------------------------------------------------------------------ procedural drawing helpers
/** A tapered brush stroke from x0 to x1 along y = cy: thickest near the start, thinning to a point, with a slight wave and dry-brush unevenness. */
internal fun brushPath(x0: Float, x1: Float, cy: Float, maxT: Float, wave: Float, taper: Float = 0.75f, steps: Int = 28): Path {
    val top = ArrayList<Offset>(steps + 1); val bot = ArrayList<Offset>(steps + 1)
    for (i in 0..steps) {
        val t = i / steps.toFloat()
        val x = x0 + (x1 - x0) * t
        val y = cy + sin(t * 7.85f) * wave
        val lead = if (t < 0.05f) t / 0.05f * 0.8f + 0.2f else 1f
        val th = maxT * (1f - t).pow(taper) * lead * (0.88f + 0.12f * sin(t * 41f))
        top.add(Offset(x, y - th * 0.45f)); bot.add(Offset(x, y + th * 0.55f))
    }
    return Path().apply {
        moveTo(top[0].x, top[0].y)
        for (i in 1 until top.size) lineTo(top[i].x, top[i].y)
        for (i in bot.indices.reversed()) lineTo(bot[i].x, bot[i].y)
        close()
    }
}

/** Pointy-top hexagon outlines in one path (at most [cap] cells), starting at ([x0], [y0]). */
private fun hexGridPath(w: Float, h: Float, r: Float, cap: Int, x0: Float = 0f, y0: Float = 0f): Path {
    val p = Path(); val dx = r * 1.7320508f; val dy = r * 1.5f; var row = 0; var n = 0; var cy = y0
    while (cy < h + r && n < cap) {
        var cx = x0 + (if (row % 2 == 0) 0f else dx / 2f)
        while (cx < w + dx && n < cap) {
            for (k in 0 until 6) {
                val a = Math.toRadians(60.0 * k - 30.0); val px = cx + (r * cos(a)).toFloat(); val py = cy + (r * sin(a)).toFloat()
                if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
            }
            p.close(); n++; cx += dx
        }
        cy += dy; row++
    }
    return p
}

/** Static texture behind the content (paper grain, drifting particles, technical grid). Hard-capped element counts. [card] = this is a card, not the page. */
fun Modifier.dnaTexture(d: Dna, ink: Color, accent: Color, card: Boolean = false): Modifier {
    val tx = d.texture
    if (tx.kind == TextureKind.NONE || tx.intensity <= 0.01f || (card && !tx.onCards)) return this
    return drawWithCache {
        val w = size.width; val h = size.height; val px = density
        val rnd = java.util.Random(w.toInt() * 7919L + h.toInt())
        val areaDp = (w / px) * (h / px)
        when (tx.kind) {
            TextureKind.PAPER -> {
                val n = (areaDp / 70f * tx.intensity).toInt().coerceIn(24, 520)
                val specks = ArrayList<Offset>(n)
                repeat(n) { specks.add(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
                val m = (n / 3).coerceAtMost(180)
                val fibres = ArrayList<Offset>(m * 2)
                repeat(m) {
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h; val a = rnd.nextFloat() * 6.2832f; val len = (5f + rnd.nextFloat() * 9f) * px
                    fibres.add(Offset(x, y)); fibres.add(Offset(x + cos(a) * len, y + sin(a) * len))
                }
                val c1 = ink.copy(alpha = 0.07f * tx.intensity); val c2 = ink.copy(alpha = 0.05f * tx.intensity)
                onDrawBehind {
                    drawPoints(specks, PointMode.Points, c1, 1.3f * px, StrokeCap.Round)
                    drawPoints(fibres, PointMode.Lines, c2, 0.8f * px, StrokeCap.Round)
                }
            }
            TextureKind.PARTICLES -> {
                val n = (areaDp / 2600f * tx.intensity).toInt().coerceIn(6, 44)
                val xs = FloatArray(n) { rnd.nextFloat() * w }; val ys = FloatArray(n) { rnd.nextFloat() * h }
                val rs = FloatArray(n) { (0.8f + rnd.nextFloat() * 2.2f) * px }; val al = FloatArray(n) { 0.15f + rnd.nextFloat() * 0.45f }
                onDrawBehind {
                    for (i in 0 until n) {
                        drawCircle(accent.copy(alpha = al[i] * tx.intensity), rs[i], Offset(xs[i], ys[i]))
                        if (i % 5 == 0) drawCircle(accent.copy(alpha = 0.06f * tx.intensity), rs[i] * 6f, Offset(xs[i], ys[i]))
                    }
                }
            }
            TextureKind.GRID -> {
                val gap = 28f * px; val c = ink.copy(alpha = 0.05f * tx.intensity)
                onDrawBehind {
                    var x = 0f; while (x <= w) { drawLine(c, Offset(x, 0f), Offset(x, h), 0.8f * px); x += gap }
                    var y = 0f; while (y <= h) { drawLine(c, Offset(0f, y), Offset(w, y), 0.8f * px); y += gap }
                }
            }
            TextureKind.DIAGONAL -> {
                val gap = 24f * px; val c = ink.copy(alpha = 0.045f * tx.intensity)
                onDrawBehind { var x = -h; while (x < w) { drawLine(c, Offset(x, 0f), Offset(x + h, h), px); x += gap } }
            }
            TextureKind.HEX -> {
                val path = hexGridPath(w, h, 26f * px, 260); val c = ink.copy(alpha = 0.05f * tx.intensity)
                onDrawBehind { drawPath(path, c, style = Stroke(px)) }
            }
            TextureKind.STARS -> {
                val n = (areaDp / 3500f * tx.intensity).toInt().coerceIn(10, 70)
                val pts = ArrayList<Offset>(n); repeat(n) { pts.add(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
                val sparks = (0 until 6).map { Offset(rnd.nextFloat() * w, rnd.nextFloat() * h) }
                val c = ink.copy(alpha = 0.35f * tx.intensity); val c2 = accent.copy(alpha = 0.4f * tx.intensity)
                onDrawBehind {
                    drawPoints(pts, PointMode.Points, c, 1.5f * px, StrokeCap.Round)
                    for (s in sparks) { drawLine(c2, Offset(s.x - 4f * px, s.y), Offset(s.x + 4f * px, s.y), px); drawLine(c2, Offset(s.x, s.y - 4f * px), Offset(s.x, s.y + 4f * px), px) }
                }
            }
            TextureKind.PIXELS -> {
                val n = (areaDp / 6000f * tx.intensity).toInt().coerceIn(8, 40); val cell = 6f * px
                val xs = FloatArray(n) { (rnd.nextFloat() * (w / cell)).toInt() * cell }; val ys = FloatArray(n) { (rnd.nextFloat() * (h / cell)).toInt() * cell }
                val al = FloatArray(n) { 0.05f + rnd.nextFloat() * 0.07f }
                onDrawBehind { for (i in 0 until n) drawRect(accent.copy(alpha = al[i] * tx.intensity * 1.4f), Offset(xs[i], ys[i]), Size(cell - px, cell - px)) }
            }
            TextureKind.NONE -> onDrawBehind { }
        }
    }
}

/** The DNA's decorative motifs (hero artwork overlay). [only] restricts to some kinds. Nothing is drawn if the DNA has no ornaments. */
fun Modifier.dnaOrnaments(d: Dna, accent: Color, only: Set<OrnamentKind>? = null): Modifier {
    val list = d.ornaments.filter { only == null || it.kind in only }
    if (list.isEmpty()) return this
    return drawWithCache {
        val w = size.width; val h = size.height; val px = density
        val rnd = java.util.Random(w.toInt() * 131L + h.toInt())
        val draws = ArrayList<DrawScope.() -> Unit>()
        for (o in list) {
            val col = accent.copy(alpha = o.alpha.coerceIn(0f, 1f))
            when (o.kind) {
                OrnamentKind.SEIGAIHA_BAND -> {
                    val r = 13f * px; val bandH = 58f * px
                    val p = Path()
                    var row = 0
                    while (row * r * 0.5f < bandH + r && row < 8) {
                        val cy = row * r * 0.5f + r
                        var cx = if (row % 2 == 0) 0f else r
                        while (cx < w + 2 * r) {
                            for (k in 0 until 3) {
                                val rad = r * (1f - 0.3f * k)
                                p.arcTo(androidx.compose.ui.geometry.Rect(cx - rad, cy - rad, cx + rad, cy + rad), 180f, 180f, true)
                            }
                            cx += 2 * r
                        }
                        row++
                    }
                    draws.add { clipRect(0f, 0f, w, bandH) { drawPath(p, col, style = Stroke(0.9f * px)) } }
                }
                OrnamentKind.GINKGO -> {
                    val leaf = Path().apply {
                        moveTo(0f, 1f); cubicTo(-0.2f, 0.5f, -1.1f, 0.1f, -1.0f, -0.55f); quadraticBezierTo(-0.5f, -1.0f, -0.05f, -0.7f)
                        quadraticBezierTo(0.5f, -1.0f, 1.0f, -0.55f); cubicTo(1.1f, 0.1f, 0.2f, 0.5f, 0f, 1f); close()
                    }
                    val n = (7 * o.density).toInt().coerceIn(1, 10)
                    val xs = FloatArray(n) { w * (0.42f + 0.55f * rnd.nextFloat()) }; val ys = FloatArray(n) { h * (0.12f + 0.72f * rnd.nextFloat()) }
                    val sz = FloatArray(n) { (8f + rnd.nextFloat() * 11f) * px }; val rot = FloatArray(n) { rnd.nextFloat() * 360f }
                    draws.add { for (i in 0 until n) withTransform({ translate(xs[i], ys[i]); rotate(rot[i], Offset.Zero); scale(sz[i], sz[i], Offset.Zero) }) { drawPath(leaf, col) } }
                }
                OrnamentKind.BRUSH_SWEEP -> {
                    val p1 = brushPath(w * 0.34f, w * 0.92f, h * 0.935f, 5f * px, 1.2f * px)
                    val p2 = brushPath(w * 0.38f, w * 0.80f, h * 0.935f + 3f * px, 1.6f * px, 0.8f * px)
                    draws.add { drawPath(p1, col); drawPath(p2, col.copy(alpha = col.alpha * 0.6f)) }
                }
                OrnamentKind.SPORES -> {
                    val n = (26 * o.density).toInt().coerceIn(4, 40)
                    val xs = FloatArray(n) { rnd.nextFloat() * w }; val ys = FloatArray(n) { rnd.nextFloat() * h }
                    val rs = FloatArray(n) { (1f + rnd.nextFloat() * 2f) * px }; val al = FloatArray(n) { 0.3f + rnd.nextFloat() * 0.6f }
                    draws.add {
                        for (i in 0 until n) {
                            drawCircle(col.copy(alpha = col.alpha * al[i]), rs[i], Offset(xs[i], ys[i]))
                            if (i % 3 == 0) drawCircle(col.copy(alpha = 0.07f), rs[i] * 5f, Offset(xs[i], ys[i]))
                        }
                    }
                }
                OrnamentKind.PITCH_LINES -> {
                    val cx = w * 0.74f; val cy = h * 0.52f; val rr = h * 0.30f
                    val pl = Path().apply {
                        addOval(Rect(cx - rr, cy - rr, cx + rr, cy + rr)); moveTo(cx, -2f); lineTo(cx, h + 2f)
                        addRect(Rect(w * 0.93f, h * 0.22f, w * 1.06f, h * 0.82f)); addRect(Rect(w * 0.99f, h * 0.38f, w * 1.03f, h * 0.66f))
                        addOval(Rect(cx - 3f * px, cy - 3f * px, cx + 3f * px, cy + 3f * px))
                    }
                    draws.add { drawPath(pl, col, style = Stroke(1.4f * px)) }
                }
                OrnamentKind.SPEED_STRIPES -> {
                    val n = (3 * o.density).toInt().coerceIn(2, 5)
                    val sp = (0 until n).map { i ->
                        val x = w * (0.50f + 0.11f * i); val bw = w * (0.012f + 0.01f * (n - i)); val slant = h * 0.55f
                        Path().apply { moveTo(x, 0f); lineTo(x + bw, 0f); lineTo(x + bw - slant, h); lineTo(x - slant, h); close() }
                    }
                    draws.add { sp.forEachIndexed { i, p -> drawPath(p, col.copy(alpha = col.alpha * (1f - i * 0.15f))) } }
                }
                OrnamentKind.CHECKER_STRIP -> {
                    val cell = 7f * px; val x0 = w * 0.55f; val cols = ((w - x0) / cell).toInt().coerceAtMost(60)
                    val ck = Path()
                    for (c in 0 until cols) for (r in 0 until 2) if ((c + r) % 2 == 0) ck.addRect(Rect(x0 + c * cell, r * cell, x0 + (c + 1) * cell, (r + 1) * cell))
                    draws.add { drawPath(ck, col) }
                }
                OrnamentKind.HEX_GRID -> {
                    val hp = hexGridPath(w, h, 15f * px, 70, w * 0.5f, 0f)
                    draws.add { drawPath(hp, col, style = Stroke(1.1f * px)) }
                }
                OrnamentKind.CIRCUIT -> {
                    val cp = Path(); val nodes = ArrayList<Offset>()
                    repeat(6) {
                        var x = w + 4f * px; var y = h * (0.12f + 0.76f * rnd.nextFloat()); cp.moveTo(x, y)
                        repeat(4) {
                            x -= (18f + rnd.nextFloat() * 40f) * px; cp.lineTo(x, y)
                            val d = (10f + rnd.nextFloat() * 18f) * px * (if (rnd.nextBoolean()) 1f else -1f)
                            x -= abs(d); y = (y + d).coerceIn(0f, h); cp.lineTo(x, y)
                        }
                        nodes.add(Offset(x, y))
                    }
                    draws.add { drawPath(cp, col, style = Stroke(1.2f * px)); for (nd in nodes) { drawCircle(col, 3.2f * px, nd, style = Stroke(1.2f * px)); drawCircle(col, 1.2f * px, nd) } }
                }
                OrnamentKind.SCRATCHES -> {
                    val n = (3 * o.density).toInt().coerceIn(2, 5)
                    val sc = (0 until n).map { i -> Triple(brushPath(0f, h * (0.62f + 0.1f * rnd.nextFloat()), 0f, 3.2f * px, 0.5f * px, 0.35f), w * 0.62f + i * 11f * px, h * 0.18f + rnd.nextFloat() * 6f * px) }
                    draws.add { for ((p, x, y) in sc) withTransform({ translate(x, y); rotate(62f, Offset.Zero) }) { drawPath(p, col) } }
                }
                OrnamentKind.HALFTONE -> {
                    val gap = 9f * px; val x0 = w * 0.35f; val cols = ((w - x0) / gap).toInt().coerceAtLeast(1); val rows = (h / gap).toInt()
                    val buckets = Array(3) { ArrayList<Offset>() }
                    for (c in 0..cols) for (r in 0..rows) buckets[(c.toFloat() / cols * 3f).toInt().coerceIn(0, 2)].add(Offset(x0 + c * gap + (r % 2) * gap / 2f, r * gap))
                    draws.add { for (i in 0..2) drawPoints(buckets[i], PointMode.Points, col.copy(alpha = col.alpha * (0.5f + 0.25f * i)), (1.6f + 1.4f * i) * px, StrokeCap.Round) }
                }
                OrnamentKind.PIXEL_BLOCKS -> {
                    val cell = 8f * px; val n = (24 * o.density).toInt().coerceIn(6, 34)
                    val xs = FloatArray(n) { (w * 0.45f / cell + rnd.nextFloat() * (w * 0.55f / cell)).toInt() * cell }; val ys = FloatArray(n) { (rnd.nextFloat() * (h / cell)).toInt() * cell }
                    val al = FloatArray(n) { 0.3f + rnd.nextFloat() * 0.7f }
                    draws.add { for (i in 0 until n) drawRect(col.copy(alpha = col.alpha * al[i]), Offset(xs[i], ys[i]), Size(cell - px, cell - px)) }
                }
                OrnamentKind.STARFIELD -> {
                    val n = (46 * o.density).toInt().coerceIn(10, 70)
                    val stars = ArrayList<Offset>(n); repeat(n) { stars.add(Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)) }
                    val sparks = (0 until 5).map { Offset(w * (0.3f + 0.7f * rnd.nextFloat()), h * rnd.nextFloat()) }
                    draws.add {
                        drawPoints(stars, PointMode.Points, col, 1.6f * px, StrokeCap.Round)
                        for (c in sparks) { val s = 5f * px; drawLine(col, Offset(c.x - s, c.y), Offset(c.x + s, c.y), 1.1f * px); drawLine(col, Offset(c.x, c.y - s), Offset(c.x, c.y + s), 1.1f * px) }
                        drawOval(col.copy(alpha = col.alpha * 0.25f), Offset(w * 0.35f, h * 0.15f), Size(w * 0.8f, h * 0.8f), style = Stroke(px))
                    }
                }
                OrnamentKind.CONTOURS -> {
                    val cp = Path()
                    for (i in 0 until 7) {
                        val base = h * (0.1f + 0.8f * i / 6f); val ph = rnd.nextFloat() * 6.2832f; val amp = (6f + rnd.nextFloat() * 10f) * px; val wl = (70f + rnd.nextFloat() * 60f) * px
                        var x = 0f; cp.moveTo(0f, base)
                        while (x < w) { x += 6f * px; cp.lineTo(x, base + amp * sin(x / wl * 6.2832f + ph)) }
                    }
                    draws.add { drawPath(cp, col, style = Stroke(px)) }
                }
                OrnamentKind.WAVES -> {
                    val wp = Path()
                    for (i in 0 until 3) {
                        val base = h * (0.70f + 0.1f * i); val amp = (5f + 2f * i) * px; val wl = (48f + 16f * i) * px
                        var x = 0f; wp.moveTo(0f, base)
                        while (x < w) { x += 4f * px; wp.lineTo(x, base + amp * sin(x / wl * 6.2832f + i)) }
                    }
                    draws.add { drawPath(wp, col, style = Stroke(1.4f * px)) }
                }
                OrnamentKind.LIGHT_RAYS -> {
                    val brush = Brush.verticalGradient(listOf(col, Color.Transparent), 0f, h)
                    val paths = (0 until 3).map { i ->
                        val x = w * (0.30f + i * 0.20f); val bw = w * (0.05f + 0.02f * i); val shift = h * 0.30f
                        Path().apply { moveTo(x, 0f); lineTo(x + bw, 0f); lineTo(x + bw * 2.2f + shift, h); lineTo(x + shift, h); close() }
                    }
                    draws.add { for (p in paths) drawPath(p, brush) }
                }
            }
        }
        onDrawBehind { clipRect(0f, 0f, w, h) { for (f in draws) f(this) } }
    }
}

/** The card border of the DNA, drawn behind the content (never changes the layout). */
fun Modifier.dnaBorder(d: Dna, line: Color, glow: Color, shape: Shape): Modifier {
    val kind = d.border.kind
    if (kind == BorderKind.NONE) return this
    val bw = d.border.width
    return drawBehind {
        val px = density; val w = bw * px
        val outline = shape.createOutline(size, layoutDirection, this)
        when (kind) {
            BorderKind.SINGLE -> drawOutline(outline, line, style = Stroke(w * 2f))
            BorderKind.DOUBLE -> {
                drawOutline(outline, line, style = Stroke(w * 2f))
                val inset = 4f * px
                val inner = Size(size.width - 2 * inset, size.height - 2 * inset)
                if (inner.width > 0f && inner.height > 0f) translate(inset, inset) { drawOutline(shape.createOutline(inner, layoutDirection, this), line.copy(alpha = line.alpha * 0.55f), style = Stroke(w)) }
            }
            BorderKind.BRACKETS -> {
                drawOutline(outline, line.copy(alpha = line.alpha * 0.45f), style = Stroke(w * 2f))
                val len = 14f * px; val i = 3f * px; val sw = 1.6f * px; val x1 = size.width - i; val y1 = size.height - i
                drawLine(glow, Offset(i, i), Offset(i + len, i), sw); drawLine(glow, Offset(i, i), Offset(i, i + len), sw)
                drawLine(glow, Offset(x1, i), Offset(x1 - len, i), sw); drawLine(glow, Offset(x1, i), Offset(x1, i + len), sw)
                drawLine(glow, Offset(i, y1), Offset(i + len, y1), sw); drawLine(glow, Offset(i, y1), Offset(i, y1 - len), sw)
                drawLine(glow, Offset(x1, y1), Offset(x1 - len, y1), sw); drawLine(glow, Offset(x1, y1), Offset(x1, y1 - len), sw)
            }
            BorderKind.FLOURISH -> {
                drawOutline(outline, line.copy(alpha = line.alpha * 0.5f), style = Stroke(w * 2f))
                val i = 3f * px; val len = 20f * px; val rr = 7f * px; val x1 = size.width - i; val y1 = size.height - i
                fun curl(x: Float, y: Float, sx: Float, sy: Float) {
                    val p = Path(); p.moveTo(x, y + sy * len); p.lineTo(x, y + sy * rr); p.quadraticBezierTo(x, y, x + sx * rr, y); p.lineTo(x + sx * len, y)
                    drawPath(p, glow, style = Stroke(1.5f * px, cap = StrokeCap.Round))
                    drawCircle(glow, 1.7f * px, Offset(x + sx * len, y)); drawCircle(glow, 1.7f * px, Offset(x, y + sy * len))
                }
                curl(i, i, 1f, 1f); curl(x1, i, -1f, 1f); curl(i, y1, 1f, -1f); curl(x1, y1, -1f, -1f)
            }
            BorderKind.GLOW -> {
                drawOutline(outline, glow.copy(alpha = 0.05f), style = Stroke(w * 10f))
                drawOutline(outline, glow.copy(alpha = 0.10f), style = Stroke(w * 6f))
                drawOutline(outline, glow.copy(alpha = 0.80f), style = Stroke(w * 2f))
            }
            BorderKind.NONE -> Unit
        }
    }
}

// ------------------------------------------------------------------ components
/** A card / panel: shape, fill, texture, border and padding all come from the DNA. [raised] = a hero card (gradient on gradient-surface designs). */
@Composable fun DnaSurface(modifier: Modifier = Modifier, radius: Int = 18, raised: Boolean = false, padding: Dp = 16.dp, spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme
    val shape = dnaShape(radius)
    val fill: Brush = when {
        d.surface == SurfaceKind.GLASS -> Brush.verticalGradient(listOf(cs.surfaceContainerHigh.copy(alpha = 0.92f), cs.surfaceContainer.copy(alpha = 0.78f)))
        raised && d.surface == SurfaceKind.GRADIENT -> Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainer))
        else -> SolidColor(cs.surfaceContainerHigh)
    }
    Column(modifier.fillMaxWidth().clip(shape).background(fill).dnaTexture(d, cs.onSurface, cs.primary, card = true).dnaBorder(d, cs.outlineVariant, cs.primary, shape)
        .padding((padding.value * d.spacing).dp), verticalArrangement = Arrangement.spacedBy((spacing.value * d.spacing).dp), content = content)
}

/** A divider whose look is the DNA's: hairline, brush stroke, diamond ornament, glow or technical ticks. The caller gives the width (fillMaxWidth / weight). */
@Composable fun DnaRule(modifier: Modifier = Modifier, subtle: Boolean = false) {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme; val kind = if (subtle) RuleKind.HAIRLINE else d.rule
    val line = cs.outlineVariant; val acc = cs.primary
    val h = when (kind) { RuleKind.HAIRLINE -> 1.dp; RuleKind.BRUSH -> 8.dp; RuleKind.DIAMOND -> 10.dp; RuleKind.GLOW -> 9.dp; RuleKind.TECH -> 8.dp; RuleKind.STRIPES -> 10.dp; RuleKind.WAVE -> 10.dp; RuleKind.PIXEL -> 8.dp; RuleKind.PITCH -> 12.dp }
    Canvas(modifier.height(h)) {
        val w = size.width; val ph = size.height; val px = density; val cy = ph / 2f
        when (kind) {
            RuleKind.HAIRLINE -> drawRect(Brush.horizontalGradient(listOf(line, Color.Transparent), 0f, w), Offset(0f, 0f), Size(w, ph))
            RuleKind.BRUSH -> drawPath(brushPath(0f, w, cy, 6f * px, 0.8f * px, 0.6f), acc.copy(alpha = 0.6f))
            RuleKind.DIAMOND -> {
                val gap = 8f * px; val half = 4f * px; val cx = w / 2f
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, line), 0f, cx - gap), Offset(0f, cy - 0.5f * px), Size(cx - gap, px))
                drawRect(Brush.horizontalGradient(listOf(line, Color.Transparent), cx + gap, w), Offset(cx + gap, cy - 0.5f * px), Size(w - cx - gap, px))
                drawPath(Path().apply { moveTo(cx, cy - half); lineTo(cx + half, cy); lineTo(cx, cy + half); lineTo(cx - half, cy); close() }, acc)
            }
            RuleKind.GLOW -> {
                val b = Brush.horizontalGradient(listOf(Color.Transparent, acc.copy(alpha = 0.9f), Color.Transparent), 0f, w)
                drawRect(b, Offset(0f, cy - 2.5f * px), Size(w, 5f * px), alpha = 0.18f)
                drawRect(b, Offset(0f, cy - 0.5f * px), Size(w, px))
                drawCircle(acc.copy(alpha = 0.35f), 4f * px, Offset(w / 2f, cy)); drawCircle(acc, 1.8f * px, Offset(w / 2f, cy))
            }
            RuleKind.TECH -> {
                drawRect(line, Offset(0f, cy - 0.5f * px), Size(w, px))
                for (i in 0..5) drawRect(acc.copy(alpha = 0.9f), Offset(i * 7f * px, cy - 3f * px), Size(1.4f * px, 6f * px))
            }
            RuleKind.STRIPES -> {
                val bw = 5f * px; val sl = 6f * px; val t = cy - ph * 0.4f; val b = cy + ph * 0.4f
                for (i in 0..2) { val x = i * (bw + 4f * px); drawPath(Path().apply { moveTo(x + sl, t); lineTo(x + sl + bw, t); lineTo(x + bw, b); lineTo(x, b); close() }, acc.copy(alpha = 1f - 0.3f * i)) }
                drawRect(Brush.horizontalGradient(listOf(line, Color.Transparent), 40f * px, w), Offset(40f * px, cy - 0.5f * px), Size(w - 40f * px, px))
            }
            RuleKind.WAVE -> {
                val p = Path(); var x = 0f; p.moveTo(0f, cy)
                while (x < w) { x += 3f * px; p.lineTo(x, cy + 2.5f * px * sin(x / (14f * px) * 6.2832f)) }
                drawPath(p, Brush.horizontalGradient(listOf(acc.copy(alpha = 0.85f), Color.Transparent), 0f, w), style = Stroke(1.3f * px))
            }
            RuleKind.PIXEL -> {
                val n = (w / (9f * px)).toInt().coerceAtMost(60)
                for (i in 0 until n) drawRect((if (i < 4) acc else line).copy(alpha = 1f - i.toFloat() / n), Offset(i * 9f * px, cy - 2f * px), Size(4f * px, 4f * px))
            }
            RuleKind.PITCH -> {
                val r0 = 4.5f * px; val cx = 14f * px
                drawRect(Brush.horizontalGradient(listOf(line, Color.Transparent), cx + r0, w), Offset(cx + r0, cy - 0.5f * px), Size(w - cx - r0, px))
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, line), 0f, cx - r0), Offset(0f, cy - 0.5f * px), Size(cx - r0, px))
                drawCircle(acc, r0, Offset(cx, cy), style = Stroke(1.3f * px)); drawCircle(acc, 1.5f * px, Offset(cx, cy))
            }
        }
    }
}

/** Progress in [0,1]: round bar, tapered brush stroke, segmented ticks or glowing bar, according to the DNA. */
@Composable fun DnaProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, color: Color? = null) {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme
    val f = fraction.coerceIn(0f, 1f)
    val h = if (d.progress == ProgressKind.BRUSH) maxOf(height, 12.dp) else if (d.progress == ProgressKind.GLOW) maxOf(height, 10.dp) else height
    Canvas(modifier.fillMaxWidth().height(h)) {
        val w = size.width; val ph = size.height; val px = density; val bh = height.toPx()
        val track = color?.copy(alpha = 0.22f) ?: cs.outlineVariant; val a = color ?: cs.primary; val b = color ?: cs.secondary
        when (d.progress) {
            ProgressKind.BAR -> {
                val top = (ph - bh) / 2f; val r = androidx.compose.ui.geometry.CornerRadius(bh / 2f, bh / 2f)
                drawRoundRect(track, Offset(0f, top), Size(w, bh), r)
                if (f > 0f) drawRoundRect(Brush.horizontalGradient(listOf(a, b), 0f, w), Offset(0f, top), Size(maxOf(bh, w * f), bh), r)
            }
            ProgressKind.BRUSH -> {
                drawRect(track.copy(alpha = 0.8f), Offset(0f, ph / 2f - 0.5f * px), Size(w, px))
                if (f > 0f) {
                    val end = maxOf(w * f, 14f * px)
                    drawPath(brushPath(0f, end, ph / 2f, bh * 1.15f, 0.7f * px, 0.5f), a)
                    drawPath(brushPath(0f, end * 0.9f, ph / 2f + bh * 0.3f, bh * 0.25f, 0.5f * px, 0.9f), a.copy(alpha = 0.5f))
                }
            }
            ProgressKind.TICKS -> {
                val n = 24; val gap = 2f * px; val tw = (w - gap * (n - 1)) / n; val on = (f * n + 0.0001f).toInt()
                for (i in 0 until n) drawRect(if (i < on) a else track, Offset(i * (tw + gap), (ph - bh) / 2f), Size(tw, bh))
            }
            ProgressKind.STRIPED -> {
                val top = (ph - bh) / 2f; val r = androidx.compose.ui.geometry.CornerRadius(2f * px, 2f * px)
                drawRoundRect(track, Offset(0f, top), Size(w, bh), r)
                if (f > 0f) {
                    val fw = maxOf(bh, w * f)
                    drawRoundRect(Brush.horizontalGradient(listOf(a, b), 0f, w), Offset(0f, top), Size(fw, bh), r)
                    clipRect(0f, top, fw, top + bh) { var x = -bh; while (x < fw) { drawLine(Color.Black.copy(alpha = 0.22f), Offset(x, top + bh), Offset(x + bh, top), 3f * px); x += 7f * px } }
                }
            }
            ProgressKind.GLOW -> {
                val top = (ph - bh) / 2f; val r = androidx.compose.ui.geometry.CornerRadius(bh / 2f, bh / 2f)
                drawRoundRect(track, Offset(0f, top), Size(w, bh), r)
                if (f > 0f) {
                    val fw = maxOf(bh, w * f)
                    drawRoundRect(Brush.horizontalGradient(listOf(a, b), 0f, w), Offset(0f, top), Size(fw, bh), r)
                    drawCircle(a.copy(alpha = 0.28f), bh * 1.5f, Offset(fw - bh / 2f, ph / 2f)); drawCircle(a, bh * 0.45f, Offset(fw - bh / 2f, ph / 2f))
                }
            }
        }
    }
}

/** Tab selector (index in, index out): filled pills, brush / hairline underline, or cut-corner segments, according to the DNA. */
@Composable fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme; val d = LocalDna.current
    when (d.tabs) {
        TabKind.UNDERLINE -> Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                labels.forEachIndexed { i, t ->
                    val on = i == selected; val acc = cs.primary; val brush = d.rule == RuleKind.BRUSH
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clickable { onSelect(i) }.padding(horizontal = 6.dp).drawBehind {
                        if (on) {
                            val px = density
                            if (brush) drawPath(brushPath(size.width * 0.16f, size.width * 0.84f, size.height - 4f * px, 5f * px, 0.6f * px, 0.6f), acc)
                            else drawRect(acc, Offset(size.width * 0.16f, size.height - 3f * px), Size(size.width * 0.68f, 2f * px))
                        }
                    }, contentAlignment = Alignment.Center) {
                        Lbl(t, color = if (on) cs.primary else cs.onSurfaceVariant, weight = if (on) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant.copy(alpha = 0.7f)))
        }
        else -> {
            val outer = if (d.tabs == TabKind.CUT) CutCornerShape(14.dp) else dnaShape(16)
            val inner = when (d.tabs) { TabKind.CUT -> CutCornerShape(10.dp); TabKind.SLANT -> SlantTab; else -> dnaShape(12) }
            Row(Modifier.fillMaxWidth().clip(outer).background(cs.surfaceContainerHigh).dnaBorder(d, cs.outlineVariant, cs.primary, outer).padding(4.dp)) {
                labels.forEachIndexed { i, t ->
                    val on = i == selected
                    val bg by animateColorAsState(if (on) cs.primary.copy(alpha = 0.20f) else Color.Transparent, tween(180), label = "tab")
                    Box(Modifier.weight(1f).heightIn(min = 44.dp).clip(inner).background(bg).clickable { onSelect(i) }.padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Lbl(t, color = if (on) cs.primary else cs.onSurfaceVariant, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/** Section title with the DNA's divider after it. */
@Composable fun SectionHeader(t: String) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        DnaRule(Modifier.weight(1f))
    }
}

private fun styleLabel(n: String) = when (n) {
    "PAPER" -> tr("Ink & paper", "حبر وورق"); "LUMINOUS" -> tr("Luminous, painted", "مضيء ومرسوم"); "DRAMATIC" -> tr("Dark & dramatic", "داكن ودرامي")
    "NEON" -> tr("Neon", "نيون"); "MINIMAL" -> tr("Minimal", "بسيط"); "SOFT" -> tr("Soft & light", "ناعم وفاتح"); else -> n
}

private fun tagLabel(n: String) = when (n) {
    "SPORTS" -> tr("Sports", "رياضة"); "RACING" -> tr("Racing", "سباق"); "FIGHTING" -> tr("Fighting", "قتال"); "SHOOTER" -> tr("Shooter", "إطلاق نار")
    "HORROR" -> tr("Horror", "رعب"); "FANTASY" -> tr("Fantasy", "فانتازيا"); "DARK_FANTASY" -> tr("Dark fantasy", "فانتازيا مظلمة"); "SAMURAI" -> tr("Samurai / Japan", "ساموراي / اليابان")
    "SCIFI" -> tr("Sci-fi", "خيال علمي"); "CYBERPUNK" -> tr("Cyberpunk", "سايبربانك"); "SPACE" -> tr("Space", "فضاء"); "PIXEL" -> tr("Pixel / retro", "بكسل / كلاسيكي")
    "ANIME" -> tr("Anime", "أنمي"); "ADVENTURE" -> tr("Adventure", "مغامرة"); "STRATEGY" -> tr("Strategy", "استراتيجية"); "SURVIVAL" -> tr("Survival", "بقاء")
    "FAMILY" -> tr("Family / casual", "عائلي / خفيف"); "NAUTICAL" -> tr("Nautical", "بحري"); else -> n
}

/**
 * Lets the user choose the design of one game: automatic (follows the theme mode), the design generated from the game's own artwork, or any preset / profile.
 * Only visual: nothing about the game or the console changes. [gen] is the generated design (null while it is being computed).
 */
@Composable fun GameThemePicker(titleId: String, gameName: String, gen: AppTheme?) {
    ThemeEngine.assignedTheme(titleId)
    val cur = ThemeEngine.assignedId(titleId)
    val all = ThemeEngine.PRESETS + ThemeEngine.profiles
    var saved by remember(titleId) { mutableStateOf(false) }
    DnaSurface {
        SectionTitle(tr("Theme for this game", "ثيم هذه اللعبة"))
        Dim(tr("Only this game's pages use it. “Auto” follows the theme mode chosen in Settings.", "تستخدمه صفحات هذه اللعبة فقط. «تلقائي» يتبع نمط الثيم المختار في الإعدادات."))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(cur == null, { ThemeEngine.assign(titleId, null) }, label = { Lbl(tr("Auto", "تلقائي")) })
            FilterChip(cur == ThemeEngine.COVER, { ThemeEngine.assign(titleId, ThemeEngine.COVER) }, enabled = gen != null, label = { Lbl(tr("From this game's artwork", "من فن هذه اللعبة")) })
            all.forEach { t -> FilterChip(cur == t.id, { ThemeEngine.assign(titleId, t.id) }, label = { Lbl(t.name) }) }
        }
        val rep = if (gen != null) ThemeEngine.reportFor(titleId)?.toMap() else null
        if (gen != null && rep != null) {
            DnaRule(Modifier.fillMaxWidth())
            Text(tr("Design analysis (on this phone, no internet)", "تحليل التصميم (على الهاتف دون إنترنت)"), style = MaterialTheme.typography.labelLarge)
            val top = ArtStyle.values().map { it.name to (rep[it.name] ?: 0f) }.filter { it.second >= 0.35f }.sortedByDescending { it.second }
            Dim(if (top.isEmpty()) tr("No dominant style was found, so a neutral design was used.", "لم يُعثر على طابع غالب فاستُخدم تصميم محايد.")
                else tr("Style: ", "الطابع: ") + top.joinToString(" + ") { styleLabel(it.first) + " " + (it.second * 100).toInt() + "%" })
            fun pc(k: String) = ((rep[k] ?: 0f) * 100).toInt()
            Dim(tr("Light ${pc("light")}%  ·  dark ${pc("dark")}%  ·  colourful ${pc("colorful")}%  ·  empty space ${pc("flat")}%",
                "الفاتح ${pc("light")}٪  ·  الداكن ${pc("dark")}٪  ·  الملوّن ${pc("colorful")}٪  ·  الفراغ ${pc("flat")}٪"))
            val tg = rep.filterKeys { it.startsWith("tag:") }.entries.sortedByDescending { it.value }
            Dim(if (tg.isEmpty()) tr("Game type unknown, so the design follows the artwork only.", "نوع اللعبة غير معروف، فالتصميم يتبع الغلاف فقط.")
                else tr("Game type: ", "نوع اللعبة: ") + tg.joinToString(" + ") { tagLabel(it.key.removePrefix("tag:")) + " " + (it.value * 100).toInt() + "%" })
            Dim(tr("Motifs and fonts follow the game type. Recognising specific details of the artwork (a particular leaf, crest or logo font) needs the visual analysis.",
                "الزخارف والخطوط تتبع نوع اللعبة. أما التعرّف على تفاصيل بعينها في الغلاف (ورقة أو شعار أو خط الشعار) فيحتاج التحليل البصري."))
            OutlinedButton(onClick = { ThemeEngine.saveProfile(gen.copy(id = ThemeEngine.newId(), name = gameName.take(22) + " design", builtin = false, basePreset = null), false); saved = true }, enabled = !saved) {
                Lbl(if (saved) tr("Saved in Settings > Theme", "حُفظ في الإعدادات ← الثيم") else tr("Save this design as a profile", "احفظ هذا التصميم كملف"))
            }
        }
    }
}


// ------------------------------------------------------------------ shared page components (Home, Downloads, Files, Browser ...)
/** Card look without padding or a column: shape, fill, texture and border of the DNA. For rows and custom layouts; add your own padding after it. */
@Composable fun Modifier.dnaCard(radius: Int = 18, raised: Boolean = false, fill: Brush? = null, line: Color? = null): Modifier {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme; val shape = dnaShape(radius)
    val f: Brush = fill ?: when {
        d.surface == SurfaceKind.GLASS -> Brush.verticalGradient(listOf(cs.surfaceContainerHigh.copy(alpha = 0.92f), cs.surfaceContainer.copy(alpha = 0.78f)))
        raised && d.surface == SurfaceKind.GRADIENT -> Brush.linearGradient(listOf(cs.surfaceContainerHigh, cs.surfaceContainer))
        else -> SolidColor(cs.surfaceContainerHigh)
    }
    return this.clip(shape).background(f).dnaTexture(d, cs.onSurface, cs.primary, card = true).dnaBorder(d, line ?: cs.outlineVariant, cs.primary, shape)
}

/** The DNA's divider under a screen title or above the bottom bar. Draws nothing for the plain hairline design, so the default look is unchanged. */
@Composable fun DnaHeaderRule(modifier: Modifier = Modifier) { if (LocalDna.current.rule != RuleKind.HAIRLINE) DnaRule(modifier.fillMaxWidth()) }

/** Shortcut tile: icon chip + label + arrow. */
@Composable fun ActionTile(@androidx.annotation.DrawableRes icon: Int, label: String, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier.heightIn(min = 68.dp).dnaCard(18).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(38.dp).clip(dnaShape(12)).background(Brush.linearGradient(listOf(cs.primaryContainer, cs.surfaceContainerHighest))), contentAlignment = Alignment.Center) { Ico(icon, 22.dp, cs.primary) }
        Lbl(label, Modifier.weight(1f), MaterialTheme.typography.titleSmall, cs.onSurface, FontWeight.SemiBold)
    }
}

/** Connection state of the console: marker + text + colour, so it never depends on colour alone. The pill becomes a cut / slanted / square tag in angular designs. */
@Composable fun StatusPill(text: String, color: Color) {
    val d = LocalDna.current
    val round = d.shape.corner == Corner.ROUND
    val shape: Shape = if (round) CircleShape else dnaShape(10)
    val dot: Shape = if (round) CircleShape else RectangleShape
    Row(Modifier.clip(shape).background(color.copy(alpha = 0.16f)).dnaBorder(d, color.copy(alpha = 0.5f), color, shape).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(8.dp).clip(dot).background(color)); Lbl(text, color = color, style = MaterialTheme.typography.labelLarge, weight = FontWeight.SemiBold)
    }
}

/** Primary action: accent fill (gradient, or a solid colour in solid-surface designs). Same contract as a Button (enabled / onClick / content). */
@Composable fun PrimaryAction(enabled: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme; val d = LocalDna.current; val shape = dnaShape(16)
    val fill: Brush = if (d.surface == SurfaceKind.SOLID) SolidColor(cs.primary) else Brush.horizontalGradient(listOf(cs.primary, cs.secondary))
    CompositionLocalProvider(LocalContentColor provides cs.onPrimary) {
        Row(modifier.height(52.dp).clip(shape).background(fill).dnaBorder(d, Color.Transparent, cs.onPrimary.copy(alpha = 0.6f), shape).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    }
}
@Composable fun SecondaryAction(enabled: Boolean, accent: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme; val d = LocalDna.current; val shape = dnaShape(16); val c = if (accent) cs.secondary else cs.onSurface
    CompositionLocalProvider(LocalContentColor provides c) {
        Row(modifier.height(48.dp).clip(shape).dnaBorder(d, if (accent) cs.secondary.copy(alpha = 0.7f) else cs.outlineVariant, if (accent) cs.secondary else cs.primary, shape).alpha(if (enabled) 1f else 0.5f).clickable(enabled = enabled, onClick = onClick),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
    }
}
