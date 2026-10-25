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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
}
@Composable fun dnaShape(r: Int): Shape { val d = LocalDna.current; return remember(d, r) { shapeOf(d, r) } }
fun dnaShapes(d: Dna) = Shapes(small = shapeOf(d, 10), medium = shapeOf(d, 14), large = shapeOf(d, 18), extraLarge = shapeOf(d, 24))

private val condensedFamily: FontFamily by lazy { FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL)) }
private fun family(k: FontKind): FontFamily = when (k) {
    FontKind.DEFAULT -> FontFamily.Default; FontKind.SANS -> FontFamily.SansSerif; FontKind.SERIF -> FontFamily.Serif; FontKind.CONDENSED -> condensedFamily
}
/** Title roles (display / headline / title) take the title face, weight and tracking; body and label roles take the body face. Arabic keeps its joined letters (no tracking). */
@Composable fun dnaTypography(d: Dna): Typography {
    val ar = Lang.isAr
    return remember(d, ar) {
        val t = d.type; val base = Typography()
        if (t.title == FontKind.DEFAULT && t.body == FontKind.DEFAULT && t.titleWeight == 0 && t.tracking == 0f) base
        else {
            val tf = family(t.title); val bf = family(t.body)
            fun ti(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = tf, fontWeight = if (t.titleWeight > 0) FontWeight(t.titleWeight) else s.fontWeight,
                letterSpacing = if (ar || t.tracking <= 0f) s.letterSpacing else t.tracking.em)
            fun bo(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = bf)
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
        onDrawBehind { for (f in draws) f(this) }
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
@Composable fun DnaRule(modifier: Modifier = Modifier) {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme
    val line = cs.outlineVariant; val acc = cs.primary
    val h = when (d.rule) { RuleKind.HAIRLINE -> 1.dp; RuleKind.BRUSH -> 8.dp; RuleKind.DIAMOND -> 10.dp; RuleKind.GLOW -> 9.dp; RuleKind.TECH -> 8.dp }
    Canvas(modifier.height(h)) {
        val w = size.width; val ph = size.height; val px = density; val cy = ph / 2f
        when (d.rule) {
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
        }
    }
}

/** Progress in [0,1]: round bar, tapered brush stroke, segmented ticks or glowing bar, according to the DNA. */
@Composable fun DnaProgress(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val d = LocalDna.current; val cs = MaterialTheme.colorScheme
    val f = fraction.coerceIn(0f, 1f)
    val h = if (d.progress == ProgressKind.BRUSH) maxOf(height, 12.dp) else if (d.progress == ProgressKind.GLOW) maxOf(height, 10.dp) else height
    Canvas(modifier.fillMaxWidth().height(h)) {
        val w = size.width; val ph = size.height; val px = density; val bh = height.toPx()
        val track = cs.outlineVariant; val a = cs.primary; val b = cs.secondary
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
            val inner = if (d.tabs == TabKind.CUT) CutCornerShape(10.dp) else dnaShape(12)
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

/** Lets the user assign any preset / profile to one game, or go back to automatic. Only visual: nothing about the game or the console changes. */
@Composable fun GameThemePicker(titleId: String) {
    val cur = ThemeEngine.assignedTheme(titleId)?.id
    val all = ThemeEngine.PRESETS + ThemeEngine.profiles
    DnaSurface {
        SectionTitle(tr("Theme for this game", "ثيم هذه اللعبة"))
        Dim(tr("Only this game's pages use it. “Auto” follows the theme mode chosen in Settings.", "تستخدمه صفحات هذه اللعبة فقط. «تلقائي» يتبع نمط الثيم المختار في الإعدادات."))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(cur == null, { ThemeEngine.assign(titleId, null) }, label = { Lbl(tr("Auto", "تلقائي")) })
            all.forEach { t -> FilterChip(cur == t.id, { ThemeEngine.assign(titleId, t.id) }, label = { Lbl(t.name) }) }
        }
    }
}
