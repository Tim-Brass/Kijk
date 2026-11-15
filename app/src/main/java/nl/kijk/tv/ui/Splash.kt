@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val OrbitDim = Color(0xFF1F3A8A)
private val OrbitBright = Color(0xFF2F6BFF)
private val TileBg = Color(0xFF0F1626)
private val TileLine = Color(0xFF1D2A44)
private val IconBlue = Color(0xFF5B8CFF)

/** Wat Kijk aan het doen is, per stap (zelfde volgorde als vm.steps). */
private val DOING = listOf("Playlist laden", "Zenders laden", "Terugkijken controleren", "Gids laden", "Films en series laden")

/**
 * Laadscherm: zwart, het kijk-logo met oogjes in het midden, vier iconen die langzaam rond het logo
 * vliegen over een blauwe baan, en onderaan gewoon wat Kijk doet met één dun voortgangsbalkje.
 * Eén frameklok; de iconen en de baan worden in twee Canvassen getekend (achter en voor het logo).
 */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; delay(20_000); canSkip = true }
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
    val start = remember { System.nanoTime() }

    val steps = vm.steps
    val done = steps.all { it.state >= 2 }
    val failed = steps.firstOrNull { it.state == 4 }
    val cur = steps.indexOfFirst { it.state == 1 }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.state == 0 }.takeIf { it >= 0 } ?: steps.lastIndex
    val prog = steps.sumOf { s ->
        when {
            s.state >= 2 -> 1.0
            s.state == 1 && s.total > 0 -> 0.15 + 0.8 * (s.done.toDouble() / s.total).coerceIn(0.0, 1.0)
            s.state == 1 -> 0.4
            else -> 0.0
        }
    }.toFloat() / steps.size.coerceAtLeast(1)
    val shown by animateFloatAsState(if (done) 1f else prog, tween(450), label = "prog")

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (canSkip && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { vm.finishStartup(); true } else false
            }.focusable(),
    ) {
        // achterste helft van de baan en de iconen die achter het logo langs gaan
        Canvas(Modifier.fillMaxSize()) { drawOrbit(frame, start, front = false) }
        // logo
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EyesLogo(done = done, modifier = Modifier.offset(y = (-54).dp).height(118.dp))   // midden van de baan (40% hoogte)
        }
        // voorste helft: iconen die vóór het logo langs gaan
        Canvas(Modifier.fillMaxSize()) { drawOrbit(frame, start, front = true) }
        // status
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp).width(520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val title = when {
                failed != null -> "${failed.label}: ${failed.note ?: "niet gelukt"}"
                done -> "Klaar"
                else -> DOING.getOrElse(cur) { steps.getOrNull(cur)?.label ?: "" }
            }
            Label(title, size = 20.sp, weight = Kijk.Semibold, color = if (failed != null) Color(0xFFFF6961) else Color.White, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.width(180.dp).height(4.dp).background(Color(0xFF1C2335), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(4.dp).background(Kijk.Accent, RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(8.dp))
            val note = if (done || failed != null) " " else steps.getOrNull(cur)?.let { s ->
                s.note ?: if (s.state == 1 && s.total > 0) "${(s.done * 100 / s.total).coerceIn(0, 100)}%" else null
            } ?: " "
            Label(note, size = Kijk.Caption, color = Kijk.Secondary, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Label(if (canSkip) "Druk op OK om alvast te beginnen" else " ", size = Kijk.Caption, color = Kijk.Secondary)
        }
    }
}

/** De baan (schuine ellips) met vier iconen-tegels. front = alleen wat vóór het logo ligt. */
private fun DrawScope.drawOrbit(frame: MutableLongState, start: Long, front: Boolean) {
    val t = ((frame.longValue - start).coerceAtLeast(0L)) / 1e9f
    val cx = size.width / 2f; val cy = size.height * 0.40f
    val rx = size.width * 0.30f; val ry = size.height * 0.16f
    val tilt = -8f
    val oval = Rect(Offset(cx - rx, cy - ry), Size(rx * 2, ry * 2))
    rotate(tilt, Offset(cx, cy)) {
        // één baan, overal even dik
        if (!front) drawOval(OrbitBright.copy(alpha = 0.75f), oval.topLeft, oval.size, style = Stroke(2.dp.toPx()))
    }
    val rad = tilt * PI.toFloat() / 180f
    val tile = 46.dp.toPx()
    val n = 7
    for (i in 0 until n) {
        val a = t * 0.35f + i * 2f * PI.toFloat() / n
        val x = cos(a) * rx; val y = sin(a) * ry
        val px = cx + x * cos(rad) - y * sin(rad); val py = cy + x * sin(rad) + y * cos(rad)
        val inFront = sin(a) > 0f
        if (inFront != front) continue
        val depth = (sin(a) + 1f) / 2f              // 0 achter … 1 voor
        val s = tile * (0.78f + 0.22f * depth)
        val alpha = 0.45f + 0.55f * depth
        translate(px - s / 2, py - s / 2) {
            drawRoundRect(TileBg, Offset.Zero, Size(s, s), CornerRadius(s * 0.24f), alpha = alpha)
            drawRoundRect(TileLine, Offset.Zero, Size(s, s), CornerRadius(s * 0.24f), style = Stroke(1.dp.toPx()), alpha = alpha)
            drawTileIcon(i, s, alpha)
        }
    }
}

/** Eenvoudige platte iconen: 0 filmklapper, 1 voetbal, 2 krant, 3 afspelen, 4 terugkijken, 5 raceauto, 6 weer. */
private fun DrawScope.drawTileIcon(kind: Int, s: Float, alpha: Float) {
    val st = Stroke(s * 0.055f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    val c = IconBlue
    when (kind) {
        0 -> {   // filmklapper
            drawRoundRect(c, Offset(s * 0.24f, s * 0.42f), Size(s * 0.52f, s * 0.32f), CornerRadius(s * 0.04f), style = st, alpha = alpha)
            val top = Path().apply { moveTo(s * 0.24f, s * 0.40f); lineTo(s * 0.72f, s * 0.27f); lineTo(s * 0.75f, s * 0.36f); lineTo(s * 0.27f, s * 0.49f); close() }
            drawPath(top, c, style = st, alpha = alpha)
            for (k in 0..2) { val x = s * (0.36f + 0.12f * k); drawLine(c, Offset(x, s * 0.43f - k * s * 0.03f), Offset(x + s * 0.05f, s * 0.33f - k * s * 0.03f), st.width * 0.8f, StrokeCap.Round, alpha = alpha) }
        }
        1 -> {   // voetbal
            val ctr = Offset(s * 0.5f, s * 0.5f); val r = s * 0.25f
            drawCircle(c, r, ctr, style = st, alpha = alpha)
            val pent = Path(); val pr = r * 0.38f
            for (k in 0 until 5) {
                val a = -PI.toFloat() / 2 + k * 2 * PI.toFloat() / 5
                val p = Offset(ctr.x + cos(a) * pr, ctr.y + sin(a) * pr)
                if (k == 0) pent.moveTo(p.x, p.y) else pent.lineTo(p.x, p.y)
                drawLine(c, p, Offset(ctr.x + cos(a) * r, ctr.y + sin(a) * r), st.width * 0.8f, StrokeCap.Round, alpha = alpha)
            }
            pent.close(); drawPath(pent, c, alpha = alpha)
        }
        2 -> {   // krant
            drawRoundRect(c, Offset(s * 0.24f, s * 0.26f), Size(s * 0.52f, s * 0.48f), CornerRadius(s * 0.05f), style = st, alpha = alpha)
            drawLine(c, Offset(s * 0.32f, s * 0.36f), Offset(s * 0.68f, s * 0.36f), st.width * 1.3f, StrokeCap.Round, alpha = alpha)
            drawRect(c, Offset(s * 0.32f, s * 0.44f), Size(s * 0.14f, s * 0.12f), alpha = alpha)
            drawLine(c, Offset(s * 0.52f, s * 0.46f), Offset(s * 0.68f, s * 0.46f), st.width * 0.8f, StrokeCap.Round, alpha = alpha)
            drawLine(c, Offset(s * 0.52f, s * 0.54f), Offset(s * 0.68f, s * 0.54f), st.width * 0.8f, StrokeCap.Round, alpha = alpha)
            drawLine(c, Offset(s * 0.32f, s * 0.64f), Offset(s * 0.68f, s * 0.64f), st.width * 0.8f, StrokeCap.Round, alpha = alpha)
        }
        3 -> {   // afspelen
            val p = Path().apply { moveTo(s * 0.38f, s * 0.28f); lineTo(s * 0.38f, s * 0.72f); lineTo(s * 0.74f, s * 0.5f); close() }
            drawPath(p, c, alpha = alpha)
        }
        5 -> {   // raceauto (zijaanzicht, formule-wagen)
            val body = Path().apply {
                moveTo(s * 0.16f, s * 0.58f); lineTo(s * 0.30f, s * 0.52f); lineTo(s * 0.44f, s * 0.52f); lineTo(s * 0.50f, s * 0.44f)
                lineTo(s * 0.60f, s * 0.44f); lineTo(s * 0.64f, s * 0.52f); lineTo(s * 0.80f, s * 0.54f); lineTo(s * 0.84f, s * 0.60f)
                lineTo(s * 0.16f, s * 0.62f); close()
            }
            drawPath(body, c, alpha = alpha)
            drawLine(c, Offset(s * 0.78f, s * 0.40f), Offset(s * 0.86f, s * 0.40f), st.width * 1.2f, StrokeCap.Round, alpha = alpha)   // achtervleugel
            drawLine(c, Offset(s * 0.82f, s * 0.40f), Offset(s * 0.80f, s * 0.54f), st.width * 0.8f, StrokeCap.Round, alpha = alpha)
            drawCircle(c, s * 0.075f, Offset(s * 0.30f, s * 0.64f), alpha = alpha)
            drawCircle(c, s * 0.085f, Offset(s * 0.72f, s * 0.63f), alpha = alpha)
            drawCircle(TileBg, s * 0.03f, Offset(s * 0.30f, s * 0.64f), alpha = alpha)
            drawCircle(TileBg, s * 0.035f, Offset(s * 0.72f, s * 0.63f), alpha = alpha)
        }
        6 -> {   // weer: zonnetje half achter een wolk
            val sc = Offset(s * 0.42f, s * 0.40f); val sr = s * 0.11f
            drawCircle(c, sr, sc, style = st, alpha = alpha)
            for (k in 0 until 6) {
                val a = (-200f + k * 40f) * PI.toFloat() / 180f
                drawLine(c, Offset(sc.x + cos(a) * sr * 1.5f, sc.y + sin(a) * sr * 1.5f), Offset(sc.x + cos(a) * sr * 2.1f, sc.y + sin(a) * sr * 2.1f), st.width * 0.9f, StrokeCap.Round, alpha = alpha)
            }
            // wolk (gevuld met tegelkleur, zodat hij voor de zon ligt)
            // één omtrek: de drie vormen samengevoegd (anders zie je de binnenlijnen)
            val a1 = Path().apply { addOval(Rect(Offset(s * 0.36f, s * 0.46f), Size(s * 0.20f, s * 0.20f))) }
            val a2 = Path().apply { addOval(Rect(Offset(s * 0.48f, s * 0.40f), Size(s * 0.24f, s * 0.24f))) }
            val a3 = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset(s * 0.30f, s * 0.54f), Size(s * 0.48f, s * 0.16f)), CornerRadius(s * 0.08f))) }
            val a12 = Path().apply { op(a1, a2, androidx.compose.ui.graphics.PathOperation.Union) }
            val cloud = Path().apply { op(a12, a3, androidx.compose.ui.graphics.PathOperation.Union) }
            drawPath(cloud, TileBg, alpha = alpha)
            drawPath(cloud, c, style = st, alpha = alpha)
        }
        else -> {   // terugkijken: pijl tegen de klok in rond een klokje
            val r = s * 0.24f; val ctr = Offset(s * 0.5f, s * 0.52f)
            drawArc(c, -60f, -290f, false, Offset(ctr.x - r, ctr.y - r), Size(r * 2, r * 2), style = st, alpha = alpha)
            val a = Math.toRadians(-60.0).toFloat()
            val tip = Offset(ctr.x + cos(a) * r, ctr.y + sin(a) * r)
            val head = Path().apply { moveTo(tip.x - s * 0.02f, tip.y - s * 0.11f); lineTo(tip.x + s * 0.03f, tip.y + s * 0.01f); lineTo(tip.x - s * 0.10f, tip.y + s * 0.02f); close() }
            drawPath(head, c, alpha = alpha)
            drawLine(c, ctr, Offset(ctr.x, ctr.y - r * 0.55f), st.width, StrokeCap.Round, alpha = alpha)
            drawLine(c, ctr, Offset(ctr.x + r * 0.4f, ctr.y + r * 0.2f), st.width, StrokeCap.Round, alpha = alpha)
        }
    }
}
