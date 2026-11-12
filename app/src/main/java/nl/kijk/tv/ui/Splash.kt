@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Grappige omschrijving per laadstap (zelfde volgorde als vm.steps). */
private val FUN_LABELS = listOf(
    "Playlist uitgerold als een rode loper",
    "Zenders tellen (en nog een keer, voor de zekerheid)",
    "Terugspoelknop aan het oliën",
    "Gids lezen, ook de kleine lettertjes…",
    "Popcorn poffen voor films en series",
)
private const val DONE_TEXT = "De kat van de bank af gepraat. Daar gaan we!"

private val Carpet = listOf(Color(0xFFC9184A), Color(0xFFFF4D6D))
private val Gold = Color(0xFFD4A017)
private val Popcorn = Color(0xFFFFF3C4)

/**
 * Laadscherm (concept C): zwart, logo met oogjes, één centrale balk die per stap een ander effect heeft.
 * Alles wordt in één Canvas getekend met één frameklok: geen hercompositie per frame.
 */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; delay(20_000); canSkip = true }

    val steps = vm.steps
    val done = steps.all { it.state >= 2 }
    val failedStep = steps.firstOrNull { it.state == 4 }
    val cur = if (done) steps.size else steps.indexOfFirst { it.state == 1 }.takeIf { it >= 0 } ?: steps.indexOfFirst { it.state == 0 }.coerceAtLeast(0)

    // frameklok (alleen gelezen tijdens het tekenen)
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
    // begin van de huidige stap, voor het "kruipen" als de echte voortgang onbekend is
    val stepStart = remember(cur) { System.nanoTime() }

    val stepFrac: () -> Float = {
        val s = steps.getOrNull(cur)
        when {
            s == null -> 1f
            s.state >= 2 -> 1f
            s.state == 1 && s.total > 0 -> (s.done.toFloat() / s.total).coerceIn(0f, 1f)
            else -> { val t = (System.nanoTime() - stepStart) / 1e9f; 0.85f * (1f - exp(-t / 5f)) }   // onbekend: rustig oplopen
        }
    }
    val doneBefore = steps.count { it.state >= 2 }.coerceAtMost(cur)
    val shownTarget = if (done) 1f else doneBefore.toFloat() / steps.size
    val base by animateFloatAsState(shownTarget, tween(400), label = "base")

    val tm = rememberTextMeasurer()
    val chipStyle = TextStyle(color = Color(0xFFD1D1D6), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    val pctStyle = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    val chips = remember { (1..24).map { tm.measure(it.toString(), chipStyle) } }
    val pctCache = remember { HashMap<Int, TextLayoutResult>() }

    Box(
        Modifier.fillMaxSize().background(Kijk.Background).focusRequester(focus)
            .onKeyEvent { e ->
                if (canSkip && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { vm.finishStartup(); true } else false
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(620.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EyesLogo(done = done, marks = true, modifier = Modifier.height(100.dp))
            Spacer(Modifier.height(28.dp))

            // ---- de dynamische balk (met ruimte erboven voor zenders, percentage en popcorn)
            Canvas(Modifier.width(470.dp).height(52.dp)) {
                val now = frame.longValue
                val t = (now - stepStart) / 1e9f
                val barH = 8.dp.toPx(); val y = size.height - barH; val r = CornerRadius(barH / 2)
                val w = size.width
                val n = steps.size.toFloat()
                val segStart = base * w
                val end = if (done) w else (base + stepFrac() / n) * w
                drawRoundRect(Color(0x1FFFFFFF), Offset(0f, y), Size(w, barH), r)
                if (end > 0f) drawRoundRect(Kijk.Accent, Offset(0f, y), Size(end, barH), r)
                if (done || failedStep != null) return@Canvas
                when (cur) {
                    0 -> {   // rode loper rolt uit
                        val cw = (end - segStart).coerceAtLeast(0f)
                        if (cw > 0f) drawRoundRect(Brush.horizontalGradient(Carpet, startX = segStart, endX = end), Offset(segStart, y), Size(cw, barH), r)
                        drawCircle(Carpet[1], barH * 0.95f, Offset(end, y + barH / 2))
                        drawCircle(Carpet[0], barH * 0.95f, Offset(end, y + barH / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                    }
                    1 -> {   // zenders flitsen voorbij
                        val cwPx = 34.dp.toPx(); val chH = 22.dp.toPx(); val gap = 10.dp.toPx(); val step = cwPx + gap
                        val total = step * chips.size
                        val shift = (t * 150.dp.toPx()) % total
                        val fade = 50.dp.toPx()
                        chips.forEachIndexed { i, tl ->
                            var x = i * step - shift; if (x < -cwPx) x += total
                            if (x > w) return@forEachIndexed
                            val a = minOf(1f, (x + cwPx) / fade, (w - x) / fade).coerceIn(0f, 1f)
                            drawRoundRect(Color(0x24FFFFFF), Offset(x, 0f), Size(cwPx, chH), CornerRadius(5.dp.toPx()), alpha = a)
                            drawText(tl, topLeft = Offset(x + (cwPx - tl.size.width) / 2, (chH - tl.size.height) / 2), alpha = a)
                        }
                    }
                    2 -> {   // vloeibare goud/blauwe glans
                        val period = 120.dp.toPx(); val off = (t * 90.dp.toPx()) % period
                        drawRoundRect(
                            Brush.linearGradient(listOf(Kijk.Accent, Gold, Kijk.Accent), start = Offset(-off, 0f), end = Offset(period - off, 0f), tileMode = TileMode.Repeated),
                            Offset(0f, y), Size(end, barH), r,
                        )
                    }
                    3 -> {   // percentage loopt mee
                        val s = steps.getOrNull(3)
                        if (s != null && s.total > 0) {
                            val pc = (s.done * 100 / s.total).toInt().coerceIn(0, 100)
                            val tl = pctCache.getOrPut(pc) { tm.measure("$pc%", pctStyle) }
                            val x = (end - tl.size.width / 2f).coerceIn(0f, w - tl.size.width)
                            drawText(tl, topLeft = Offset(x, y - tl.size.height - 6.dp.toPx()))
                        }
                    }
                    4 -> {   // popcorn popt op uit de balk
                        val span = (end - segStart).coerceAtLeast(12.dp.toPx())
                        for (k in 0 until 7) {
                            val period = 0.9f; val ph = t / period + k / 7f
                            val cycle = ph.toInt(); val a = ph - cycle
                            val rnd = ((cycle * 7 + k) * 2654435761L).toInt().ushr(8) / 16777216f
                            val rnd2 = ((cycle * 13 + k * 3) * 40503L).toInt().ushr(8) % 1000 / 1000f
                            val x = segStart + rnd * span + (rnd2 - 0.5f) * 24.dp.toPx() * a
                            val yy = y + barH / 2 - 30.dp.toPx() * sin(a * PI.toFloat())
                            val rr = 3.5.dp.toPx() * (0.6f + 0.6f * sin(a * PI.toFloat()))
                            drawCircle(Popcorn, rr, Offset(x, yy), alpha = 1f - a * 0.6f)
                        }
                    }
                }
            }

            Spacer(Modifier.height(22.dp))
            val label = when {
                failedStep != null -> "${failedStep.label}: ${failedStep.note ?: "niet gelukt"}"
                done -> DONE_TEXT
                else -> FUN_LABELS.getOrElse(cur) { steps.getOrNull(cur)?.label ?: "" }
            }
            Label(label, size = 20.sp, weight = Kijk.Semibold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            val note = steps.getOrNull(cur)?.note?.takeIf { !done && failedStep == null } ?: " "
            Label(note, size = Kijk.Caption, color = Kijk.Secondary)
            Spacer(Modifier.height(18.dp))
            // bolletjes: waar zijn we?
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                steps.indices.forEach { i ->
                    val isCur = i == cur && !done
                    Box(
                        Modifier.width(if (isCur) 18.dp else 7.dp).height(7.dp)
                            .background(
                                when { isCur -> Kijk.Accent; i < cur || done -> Color.White; else -> Color(0x33FFFFFF) },
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Label(if (canSkip) "Druk op OK om alvast te beginnen" else " ", size = Kijk.Caption, color = Kijk.Secondary)
        }
    }
}
