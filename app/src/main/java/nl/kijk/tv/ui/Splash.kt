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
 * Geen rondvliegende tiles of netwerkverzoeken: alleen de horizonboog ademt en krijgt een glijdende glans;
 * EyesLogo animeert zijn eigen Canvas.
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
    // rustig ademen van de gloed
    val light by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(3200, easing = androidx.compose.animation.core.FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "lightIntensity",
    )
    // een zachte glans die langzaam over de boog glijdt (met pauze aan het eind)
    val sheen by transition.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(animation = tween(5200, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        label = "sheen",
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
        // Blauwe horizonboog onder het logo: brede zachte gloed, heldere kern, glans die eroverheen glijdt
        Canvas(Modifier.align(Alignment.Center).offset(y = 58.dp).fillMaxWidth(0.84f).height(150.dp)) {
            val w = size.width; val h = size.height
            val top = h * 0.22f
            val arc = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, h * 0.78f); quadraticBezierTo(w / 2, top - h * 0.2f, w, h * 0.78f)
            }
            val blue = Color(0xFF0A84FF); val cyan = Color(0xFF64D2FF)
            // licht onder de horizon (atmosfeer)
            drawOval(Brush.radialGradient(listOf(blue.copy(alpha = 0.22f * light), Color.Transparent),
                center = Offset(w / 2, top + h * 0.05f), radius = w * 0.42f),
                topLeft = Offset(w * 0.08f, top - h * 0.1f), size = androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.9f))
            fun edgeFade(c: Color, a: Float) = Brush.horizontalGradient(0f to Color.Transparent, 0.18f to c.copy(alpha = a), 0.5f to c.copy(alpha = a),
                0.82f to c.copy(alpha = a), 1f to Color.Transparent)
            drawPath(arc, edgeFade(blue, 0.10f * light), style = androidx.compose.ui.graphics.drawscope.Stroke(26.dp.toPx(), cap = StrokeCap.Round))
            drawPath(arc, edgeFade(blue, 0.28f * light), style = androidx.compose.ui.graphics.drawscope.Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(arc, edgeFade(cyan, 0.95f * light), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            // glans: smal wit lichtpunt dat over de kern glijdt
            if (sheen in 0.12f..0.88f) {
                val d = 0.07f
                drawPath(arc, Brush.horizontalGradient(
                    0f to Color.Transparent, (sheen - d).coerceAtLeast(0.001f) to Color.Transparent, sheen to Color.White.copy(alpha = 0.9f),
                    (sheen + d).coerceAtMost(0.999f) to Color.Transparent, 1f to Color.Transparent),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
                drawPath(arc, Brush.horizontalGradient(
                    0f to Color.Transparent, (sheen - d * 2).coerceAtLeast(0.001f) to Color.Transparent, sheen to cyan.copy(alpha = 0.35f),
                    (sheen + d * 2).coerceAtMost(0.999f) to Color.Transparent, 1f to Color.Transparent),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(14.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        // het Kijk-logo met de bewegende oogjes, net boven de boog
        EyesLogo(done = done, modifier = Modifier.align(Alignment.Center).offset(y = (-40).dp).height(140.dp))

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp).width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val title = when {
                failed != null -> "${failed.label}: ${failed.note ?: "niet gelukt"}"
                done -> "Klaar"
                else -> DOING.getOrElse(cur) { steps.getOrNull(cur)?.label ?: "" }
            }
            Label(if (failed == null && !done) "$title…" else title, size = 22.sp, weight = Kijk.Medium,
                color = if (failed != null) Color(0xFFFF6961) else Color(0xFFE5E5EA), maxLines = 2)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.width(360.dp).height(7.dp)
                .background(Color(0xFF3A3A42), RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).height(7.dp)
                    .background(Brush.horizontalGradient(listOf(
                        Color(0xFF0A84FF), Color(0xFF64D2FF))), RoundedCornerShape(50)))
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
