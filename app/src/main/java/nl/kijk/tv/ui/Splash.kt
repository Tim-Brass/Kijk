@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.R
import nl.kijk.tv.Step

/** Grappige omschrijving per laadstap (zelfde volgorde als vm.steps). */
private val FUN_LABELS = listOf(
    "Playlist uitgerold als een rode loper",
    "Zenders tellen (en nog een keer, voor de zekerheid)",
    "Terugspoelknop aan het oliën",
    "Gids lezen, ook de kleine lettertjes",
    "Popcorn poffen voor films en series",
)

private val JOKES = listOf(
    "Afstandsbediening zoeken onder de bank…",
    "Afstandsbediening onder de bank gevonden.",
    "Even de antenne bijstellen…",
    "Chips in een bakje doen…",
    "Het lampje dimmen voor de sfeer…",
    "Plekje op de bank warm houden…",
    "De kat van de bank af praten…",
    "Bijna klaar. Echt.",
)

private val SplashBlue = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0A84FF), Color(0xFF1E48C3)))
private val SoftWhite = Color(0xFFD6E6FF)

/** Laadscherm bij het opstarten: de app verschijnt pas als alles klaar is. */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var canSkip by remember { mutableStateOf(false) }
    var joke by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; delay(20_000); canSkip = true }
    LaunchedEffect(Unit) { while (true) { delay(3200); joke = (joke + 1) % JOKES.size } }
    val done = vm.steps.all { it.state >= 2 }
    val failed = vm.steps.any { it.state == 4 }
    // voortgang 0..1 over alle stappen (een stap die bezig is telt half, of naar rato)
    val prog = vm.steps.sumOf { st ->
        when {
            st.state >= 2 -> 1.0
            st.state == 1 && st.total > 0 -> 0.2 + 0.7 * (st.done.toDouble() / st.total).coerceIn(0.0, 1.0)
            st.state == 1 -> 0.4
            else -> 0.0
        }
    }.toFloat() / vm.steps.size.coerceAtLeast(1)
    // "wakker worden": oogleden gaan open en het beeld wordt scherper naarmate er meer geladen is
    val open = remember { androidx.compose.animation.core.Animatable(0f) }
    val sharp = remember { androidx.compose.animation.core.Animatable(0f) }
    val target = if (done || failed) 1f else 0.18f + 0.62f * prog
    LaunchedEffect(target) {
        launch { sharp.animateTo(if (done || failed) 1f else prog, androidx.compose.animation.core.tween(600)) }
        open.animateTo(target, androidx.compose.animation.core.tween(if (done) 350 else 700))
    }
    LaunchedEffect(done, failed) {   // af en toe slaperig knipperen
        if (done || failed) return@LaunchedEffect
        while (true) {
            delay(2200 + (0..2200).random().toLong())
            val keep = open.value
            open.animateTo(0f, androidx.compose.animation.core.tween(90)); open.animateTo(keep, androidx.compose.animation.core.tween(160))
        }
    }
    Box(
        Modifier.fillMaxSize().background(SplashBlue).focusRequester(focus)
            .onKeyEvent { e ->
                if (canSkip && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { vm.finishStartup(); true } else false
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(600.dp).graphicsLayer {
                val b = (1f - sharp.value).coerceIn(0f, 1f)
                // echte vervaging vanaf Android 12; daaronder alleen het "scherpstellen" (zoom + doorzichtigheid)
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && b > 0.02f) androidx.compose.ui.graphics.BlurEffect(b * 22f, b * 22f, androidx.compose.ui.graphics.TileMode.Decal) else null
                val sc = 1f + 0.05f * b; scaleX = sc; scaleY = sc
                alpha = 1f - 0.45f * b
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EyesLogo(done = done, modifier = Modifier.height(96.dp))
            Spacer(Modifier.height(24.dp))
            vm.steps.forEachIndexed { i, s -> StepRow(s, FUN_LABELS.getOrElse(i) { s.label }); Spacer(Modifier.height(12.dp)) }
            Spacer(Modifier.height(8.dp))
            val line = when {
                failed -> vm.steps.firstOrNull { it.state == 4 }?.let { "${it.label}: ${it.note ?: "niet gelukt"}" } ?: " "
                done -> "Daar gaan we!"
                else -> JOKES[joke]
            }
            Label(line, size = Kijk.Body, color = if (failed) Color.White else SoftWhite)
            Spacer(Modifier.height(6.dp))
            Label(if (canSkip) "Druk op OK om alvast te beginnen" else " ", size = Kijk.Caption, color = SoftWhite)
        }
        // oogleden (zwart, licht gebogen rand)
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val o = open.value.coerceIn(0f, 1f)
            if (o >= 0.999f) return@Canvas
            val half = size.height / 2f
            val mid = half * (1f - o)                          // rand van het ooglid in het midden
            val side = mid + size.height * 0.22f * (1f - o)    // aan de zijkanten hangt het ooglid lager: oogvorm
            val c = 2f * mid - side                            // controlepunt zodat de curve in het midden door `mid` gaat
            val top = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, side)
                quadraticTo(size.width / 2f, c, 0f, side); close()
            }
            val bottom = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, size.height); lineTo(size.width, size.height); lineTo(size.width, size.height - side)
                quadraticTo(size.width / 2f, size.height - c, 0f, size.height - side); close()
            }
            drawPath(top, Color.Black); drawPath(bottom, Color.Black)
        }
    }
}

@Composable
private fun StepRow(s: Step, label: String) {
    val active = s.state == 1
    val labelColor = when (s.state) { 0, 3 -> SoftWhite.copy(alpha = 0.6f); else -> Color.White }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(when (s.state) { 2 -> "✓"; 4 -> "!"; 3 -> "–"; else -> "" }, Modifier.width(26.dp), size = Kijk.Headline, weight = Kijk.Bold,
                color = when (s.state) { 2 -> Color.White; 4 -> Color(0xFFFFD60A); else -> SoftWhite })
            Label(label + if (active) "…" else "", Modifier.weight(1f), size = Kijk.Headline, weight = if (active) Kijk.Bold else Kijk.Semibold, color = labelColor)
            val right = when {
                s.state == 3 -> "Niet nodig"
                active && s.total > 0 -> "${(s.done * 100 / s.total).coerceIn(0, 100)}%"
                active && s.done > 0 -> "${s.done / 1_048_576} MB"
                else -> ""
            }
            Label(right, size = Kijk.Body, color = SoftWhite)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.padding(start = 26.dp)) {
            when {
                s.state == 2 -> Bar(1f, Color.White.copy(alpha = 0.85f))
                s.state == 4 -> Bar(1f, Color(0xFFFFD60A).copy(alpha = 0.8f))
                active && s.total > 0 -> Bar((s.done.toFloat() / s.total).coerceIn(0f, 1f), Color.White)
                active -> MovingBar()
                else -> Bar(0f, Color.White)
            }
        }
        val note = s.note
        // regel altijd innemen (ook leeg), zodat het logo en de rest niet verspringen als een stap klaar is
        Label(if (note != null && s.state != 0) note else " ", Modifier.padding(start = 26.dp, top = 4.dp), size = Kijk.Caption, color = SoftWhite, maxLines = 1)
    }
}

@Composable
private fun Bar(f: Float, c: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
    }
}

/** Bezig, maar zonder bekende voortgang: een blauw stuk dat heen en weer loopt. */
@Composable
private fun MovingBar() {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x33FFFFFF))) {
        val w = maxWidth * 0.3f
        Box(Modifier.offset(x = (maxWidth - w) * x).width(w).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
    }
}
