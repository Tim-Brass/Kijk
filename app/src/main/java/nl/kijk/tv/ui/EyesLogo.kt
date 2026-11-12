package nl.kijk.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import nl.kijk.tv.R

/**
 * Laadscherm-logo: de puntjes op de "ij" van kijk zijn twee oogjes die rondkijken en knipperen zolang er
 * geladen wordt. Als alles klaar is (`done`), worden ze weer de blauwe stip en de afspeelknop.
 * Alleen op het laadscherm; elders in de app geen animaties.
 */
@Composable
fun EyesLogo(done: Boolean, modifier: Modifier = Modifier, idle: Boolean = false, still: Boolean = false) {
    val look = remember { Animatable(0f) }    // -1 links … 1 rechts
    val blink = remember { Animatable(1f) }   // 1 open … 0 dicht
    val morph = remember { Animatable(0f) }   // 0 oogjes … 1 logo
    LaunchedEffect(done, idle, still) {
        if (still) { look.snapTo(0f); blink.snapTo(1f); morph.snapTo(0f); return@LaunchedEffect }
        if (idle) {   // menu: rustig, af en toe even kijken en knipperen
            morph.snapTo(0f)
            launch {
                while (isActive) {
                    delay(4000 + (0..4000).random().toLong())
                    look.animateTo(if ((0..1).random() == 0) -1f else 1f, tween(260, easing = FastOutSlowInEasing))
                    delay(900); look.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                }
            }
            while (isActive) {
                delay(2500 + (0..3000).random().toLong())
                repeat(if ((0..4).random() == 0) 2 else 1) { blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110)); delay(90) }
            }
            return@LaunchedEffect
        }
        if (done) { look.animateTo(0f, tween(150)); blink.snapTo(1f); morph.animateTo(1f, tween(450, easing = FastOutSlowInEasing)); return@LaunchedEffect }
        morph.snapTo(0f)
        launch {   // rondkijken
            val seq = listOf(0f, -1f, -1f, 1f, 1f, 0f)
            while (isActive) for (t in seq) { look.animateTo(t, tween(260, easing = FastOutSlowInEasing)); delay(380) }
        }
        while (isActive) {   // knipperen, af en toe twee keer
            delay(1600 + (0..1400).random().toLong())
            repeat(if ((0..3).random() == 0) 2 else 1) { blink.animateTo(0.06f, tween(70)); blink.animateTo(1f, tween(110)); delay(90) }
        }
    }
    Box(modifier.aspectRatio(16f / 9f)) {
        Image(painterResource(R.drawable.wordmark), contentDescription = "Kijk", modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val m = morph.value
            val r = size.height * LOGO_DOT_R
            val eyeR = r * 1.25f
            val cy = size.height * LOGO_DOT_Y
            val xs = listOf(size.width * LOGO_DOT_X, size.width * LOGO_PLAY_X)
            // oogjes (verdwijnen bij het overgaan naar het logo)
            if (m < 1f) for (cx in xs) {
                val h = eyeR * 2 * blink.value
                drawOval(Color.White.copy(alpha = 1f - m), topLeft = Offset(cx - eyeR, cy - h / 2), size = Size(eyeR * 2, h))
                if (blink.value > 0.3f) {
                    val pr = eyeR * 0.55f
                    val ph = pr * 2 * blink.value
                    drawOval(Color(0xFF0A84FF).copy(alpha = 1f - m), topLeft = Offset(cx + look.value * eyeR * 0.38f - pr, cy - ph / 2), size = Size(pr * 2, ph))
                }
            }
            // logo: blauwe stip op de i en afspeelknop op de j
            if (m > 0f) {
                drawCircle(Color(0xFF1E90E0).copy(alpha = m), radius = r, center = Offset(xs[0], cy))
                val t = r * 1.25f * (0.6f + 0.4f * m); val cx = xs[1]
                val p = Path().apply { moveTo(cx - t * 0.8f, cy - t); lineTo(cx - t * 0.8f, cy + t); lineTo(cx + t, cy); close() }
                drawPath(p, Brush.verticalGradient(listOf(Color(0xFF28A0DC), Color(0xFF3C46AA)), startY = cy - t, endY = cy + t), alpha = m)
            }
        }
    }
}

/** Klein logo voor in het menu: alleen het woordbeeld (ingesneden), oogjes knipperen af en toe. */
@Composable
fun MenuLogo(still: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 66.dp, height = 44.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        EyesLogo(done = false, idle = true, still = still, modifier = Modifier.requiredWidth(138.dp))
    }
}
