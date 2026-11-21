@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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

/** Dezelfde echte laadstappen als in de bestaande Kijk-versie. */
private val DOING = listOf("Playlist laden", "Zenders laden", "Terugkijken controleren", "Gids laden", "Films en series laden")

/**
 * Premium zwart laadscherm: het bestaande woordmerk met de bewegende oogjes,
 * een ingetogen blauwe lichtstreep en een eerlijke laadstatus.
 *
 * Geen full-screen frame-loop, rondvliegende tiles of nieuwe netwerkverzoeken.
 * Alleen de kleine accentstreep pulseert; EyesLogo animeert zijn eigen Canvas.
 * De startupflow, voortgangsberekening en de 20s-OK-optie blijven ongewijzigd.
 */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        delay(20_000)
        canSkip = true
    }

    val steps = vm.steps
    val done = steps.all { it.state >= 2 }
    val failed = steps.firstOrNull { it.state == 4 }
    val cur = steps.indexOfFirst { it.state == 1 }.takeIf { it >= 0 }
        ?: steps.indexOfFirst { it.state == 0 }.takeIf { it >= 0 }
        ?: steps.lastIndex
    val progress = steps.sumOf { s ->
        when {
            s.state >= 2 -> 1.0
            s.state == 1 && s.total > 0 -> 0.15 + 0.8 * (s.done.toDouble() / s.total).coerceIn(0.0, 1.0)
            s.state == 1 -> 0.4
            else -> 0.0
        }
    }.toFloat() / steps.size.coerceAtLeast(1)
    val shown by animateFloatAsState(if (done) 1f else progress, tween(450), label = "loadingProgress")
    val transition = rememberInfiniteTransition(label = "loadingLight")
    val light by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(animation = tween(1900), repeatMode = RepeatMode.Reverse),
        label = "lightIntensity",
    )

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { event ->
                if (canSkip && event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    vm.finishStartup()
                    true
                } else false
            }.focusable(),
    ) {
        // Rustige merkpresentatie: oogjes bewegen/knipperen zoals voorheen.
        Column(
            Modifier.align(Alignment.Center).offset(y = (-30).dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EyesLogo(done = done, modifier = Modifier.height(126.dp))
            Spacer(Modifier.height(16.dp))
            // Slechts 240 x 10 dp wordt geanimeerd; geen full-screen 60fps rendering.
            Canvas(Modifier.width(240.dp).height(10.dp)) {
                val y = size.height * 0.5f
                val cx = size.width * 0.5f
                drawLine(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, Color(0xFF0A84FF).copy(alpha = light),
                            Color(0xFF40A0FF).copy(alpha = light), Color.Transparent)
                    ),
                    start = Offset(0f, y), end = Offset(size.width, y),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF0A84FF).copy(alpha = light * 0.25f), Color.Transparent),
                        center = Offset(cx, y), radius = size.width * 0.35f,
                    ),
                    radius = size.width * 0.35f, center = Offset(cx, y),
                )
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 92.dp).width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = when {
                failed != null -> "${failed.label}: ${failed.note ?: "niet gelukt"}"
                done -> "Klaar"
                else -> DOING.getOrElse(cur) { steps.getOrNull(cur)?.label ?: "" }
            }
            Label(title, size = 19.sp, weight = Kijk.Semibold,
                color = if (failed != null) Color(0xFFFF6961) else Color.White, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Box(Modifier.width(240.dp).height(3.dp)
                .background(Color(0xFF20242B), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(3.dp)
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF0A84FF), Color(0xFF40A0FF))), RoundedCornerShape(50)))
            }
            Spacer(Modifier.height(10.dp))
            val note = if (done || failed != null) " " else steps.getOrNull(cur)?.let { s ->
                s.note ?: if (s.state == 1 && s.total > 0)
                    "${(s.done * 100 / s.total).coerceIn(0, 100)}%" else null
            } ?: " "
            Label(note, size = Kijk.Caption, color = Kijk.Secondary, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Label(if (canSkip) "Druk op OK om alvast te beginnen" else " ",
                size = Kijk.Caption, color = Kijk.Secondary)
        }
    }
}
