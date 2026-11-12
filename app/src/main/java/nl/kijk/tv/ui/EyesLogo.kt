package nl.kijk.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.kijk.tv.R

/**
 * Het "kijk"-logo met twee oogjes als puntjes op de ij (zelfde stijl als het app-icoon).
 * - laadscherm: oogjes kijken rond en knipperen; bij `done` een knipoog
 * - menu (`idle`): af en toe even opzij kijken en knipperen
 * - `still`: geen animatie (bv. als het menu over een lopende zender ligt)
 * Alleen de Canvas tekent opnieuw; geen hercompositie van de rest.
 */
@Composable
fun EyesLogo(done: Boolean, modifier: Modifier = Modifier, idle: Boolean = false, still: Boolean = false) {
    val look = remember { Animatable(0.35f) }  // -1 links … 1 rechts
    val blinkL = remember { Animatable(1f) }   // 1 open … 0 dicht
    val blinkR = remember { Animatable(1f) }
    suspend fun blink() { launchBoth({ blinkL.animateTo(0.06f, tween(70)) }, { blinkR.animateTo(0.06f, tween(70)) }); launchBoth({ blinkL.animateTo(1f, tween(110)) }, { blinkR.animateTo(1f, tween(110)) }) }
    LaunchedEffect(done, idle, still) {
        blinkL.snapTo(1f); blinkR.snapTo(1f)
        when {
            still -> look.snapTo(0.35f)
            done -> {   // knipoog
                look.animateTo(0.2f, tween(150))
                blinkR.animateTo(0.06f, tween(110)); delay(260); blinkR.animateTo(1f, tween(160))
            }
            idle -> {
                launch {
                    while (isActive) {
                        delay(4000 + (0..4000).random().toLong())
                        look.animateTo(if ((0..1).random() == 0) -1f else 1f, tween(260, easing = FastOutSlowInEasing))
                        delay(900); look.animateTo(0.35f, tween(260, easing = FastOutSlowInEasing))
                    }
                }
                while (isActive) { delay(2500 + (0..3000).random().toLong()); repeat(if ((0..4).random() == 0) 2 else 1) { blink(); delay(90) } }
            }
            else -> {
                launch {   // rondkijken
                    val seq = listOf(0.35f, -1f, -1f, 1f, 1f, 0f)
                    while (isActive) for (t in seq) { look.animateTo(t, tween(260, easing = FastOutSlowInEasing)); delay(380) }
                }
                while (isActive) { delay(1600 + (0..1400).random().toLong()); repeat(if ((0..3).random() == 0) 2 else 1) { blink(); delay(90) } }
            }
        }
    }
    Box(modifier.aspectRatio(WM_ASPECT)) {
        Image(painterResource(R.drawable.wordmark_kijk), contentDescription = "Kijk", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val rx = size.width * WM_EYE_RX; val ry = size.height * WM_EYE_RY; val cy = size.height * WM_EYE_Y
            listOf(size.width * WM_EYE1_X to blinkL.value, size.width * WM_EYE2_X to blinkR.value).forEach { (cx, b) ->
                val h = ry * 2 * b
                drawOval(Color.White, topLeft = Offset(cx - rx, cy - h / 2), size = Size(rx * 2, h))
                if (b > 0.35f) {
                    val pr = rx * 0.5f; val ph = pr * 2 * b
                    val px = cx + look.value * rx * 0.42f; val py = cy + ry * 0.1f * b
                    drawOval(Color(0xFF0B0B0F), topLeft = Offset(px - pr, py - ph / 2), size = Size(pr * 2, ph))
                    val hr = pr * 0.32f
                    drawCircle(Color.White.copy(alpha = 0.9f), hr, Offset(px + pr * 0.15f, py - pr * 0.45f * b))
                }
            }
        }
    }
}

private suspend fun launchBoth(a: suspend () -> Unit, b: suspend () -> Unit) = kotlinx.coroutines.coroutineScope { launch { a() }; launch { b() } }

/** Klein logo voor in het menu. */
@Composable
fun MenuLogo(still: Boolean, modifier: Modifier = Modifier, height: Dp = 40.dp) {
    EyesLogo(done = false, idle = true, still = still, modifier = modifier.height(height))
}
