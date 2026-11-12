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
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.R
import nl.kijk.tv.Step

/** Laadscherm bij het opstarten: de app verschijnt pas als alles klaar is. */
@Composable
fun SplashScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    var canSkip by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; delay(20_000); canSkip = true }
    Box(
        Modifier.fillMaxSize().background(Kijk.Background).focusRequester(focus)
            .onKeyEvent { e ->
                if (canSkip && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { vm.finishStartup(); true } else false
            }.focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedLogo(Modifier.width(300.dp))
            Spacer(Modifier.height(36.dp))
            vm.steps.forEach { s -> StepRow(s); Spacer(Modifier.height(14.dp)) }
            Spacer(Modifier.height(10.dp))
            Label(if (canSkip) "Druk op OK om alvast te beginnen" else " ", size = Kijk.Caption, color = Kijk.Secondary)
        }
    }
}

@Composable
private fun StepRow(s: Step) {
    val active = s.state == 1
    val labelColor = when (s.state) { 0 -> Kijk.Secondary; 3 -> Kijk.Secondary; else -> Color.White }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(when (s.state) { 2 -> "✓"; 4 -> "!"; 3 -> "–"; else -> "" }, Modifier.width(26.dp), size = Kijk.Headline, weight = Kijk.Bold,
                color = when (s.state) { 2 -> Color(0xFF30D158); 4 -> Kijk.Live; else -> Kijk.Secondary })
            Label(s.label, Modifier.weight(1f), size = Kijk.Headline, weight = if (active) Kijk.Bold else Kijk.Semibold, color = labelColor)
            val right = when {
                s.state == 3 -> "Niet nodig"
                active && s.total > 0 -> "${(s.done * 100 / s.total).coerceIn(0, 100)}%"
                active && s.done > 0 -> "${s.done / 1_048_576} MB"
                else -> ""
            }
            Label(right, size = Kijk.Body, color = Kijk.Secondary)
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.padding(start = 26.dp)) {
            when {
                s.state == 2 -> Bar(1f, Color(0xFF30D158).copy(alpha = 0.7f))
                s.state == 4 -> Bar(1f, Kijk.Live.copy(alpha = 0.7f))
                active && s.total > 0 -> Bar((s.done.toFloat() / s.total).coerceIn(0f, 1f), Kijk.Accent)
                active -> MovingBar()
                else -> Bar(0f, Kijk.Accent)
            }
        }
        val note = s.note
        if (note != null && s.state != 0) Label(note, Modifier.padding(start = 26.dp, top = 4.dp), size = Kijk.Caption, color = Kijk.Secondary, maxLines = 1)
    }
}

@Composable
private fun Bar(f: Float, c: Color) {
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x26FFFFFF))) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c))
    }
}

/** Bezig, maar zonder bekende voortgang: een blauw stuk dat heen en weer loopt. */
@Composable
private fun MovingBar() {
    val t = rememberInfiniteTransition(label = "bar")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "x")
    BoxWithConstraints(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x26FFFFFF))) {
        val w = maxWidth * 0.3f
        Box(Modifier.offset(x = (maxWidth - w) * x).width(w).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Kijk.Accent))
    }
}
