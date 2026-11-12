package nl.kijk.tv.ui

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.kijk.tv.R
import nl.kijk.tv.data.Channel
import java.util.concurrent.ConcurrentHashMap

// ======================================================================== kleur uit een afbeelding

private val colorCache = ConcurrentHashMap<String, Int>()

/**
 * Hoofdkleur van een poster of logo, voor de gloed achter het geselecteerde item.
 * Alleen berekend als `enabled` (dus voor wat geselecteerd is), met een piepklein plaatje, en daarna onthouden.
 */
@Composable
fun rememberDominantColor(url: String?, enabled: Boolean): Color? {
    val ctx = LocalContext.current
    var c by remember(url) { mutableStateOf(url?.let { colorCache[it] }?.let { Color(it) }) }
    LaunchedEffect(url, enabled) {
        if (url == null || !enabled || c != null) return@LaunchedEffect
        val argb = withContext(Dispatchers.IO) {
            try {
                val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(48).allowHardware(false).build())
                val bmp = ((r as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                val p = Palette.from(bmp).maximumColorCount(12).generate()
                (p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch)?.rgb
            } catch (_: Exception) { null }
        } ?: return@LaunchedEffect
        colorCache[url] = argb
        c = Color(argb)
    }
    return c
}

/** Zachte gloed achter een element (buiten de randen), in de gegeven kleur. */
fun Modifier.glow(color: Color?, alpha: Float, spread: Float = 0.55f): Modifier = if (color == null || alpha <= 0f) this else drawBehind {
    val r = maxOf(size.width, size.height) * (0.5f + spread)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f * alpha), color.copy(alpha = 0.18f * alpha), Color.Transparent), center = center, radius = r), radius = r, center = center)
}

// ======================================================================== glans over een poster

/** Eén lichtglans die over het element glijdt zodra het geselecteerd wordt. */
fun Modifier.sheen(active: Boolean): Modifier = composed {
    val p = remember { Animatable(-1f) }
    LaunchedEffect(active) { if (active) { p.snapTo(-0.6f); p.animateTo(1.6f, tween(900, easing = FastOutSlowInEasing)) } else p.snapTo(-1f) }
    drawWithContent {
        drawContent()
        val v = p.value
        if (v > -0.6f && v < 1.6f) {
            val x = size.width * v
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent),
                start = Offset(x - size.width * 0.35f, 0f), end = Offset(x + size.width * 0.35f, size.height)))
        }
    }
}

/** Kort "optillen": bij selecteren kantelt het element even naar je toe en veert terug. */
fun Modifier.lift(active: Boolean): Modifier = composed {
    val t = remember { Animatable(0f) }
    LaunchedEffect(active) { if (active) { t.snapTo(1f); t.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) } }
    graphicsLayer { rotationX = 6f * t.value; cameraDistance = 12f * density }
}

// ======================================================================== pulserend bolletje

@Composable
fun PulseDot(color: Color = Color.White, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(modifier.size(7.dp).graphicsLayer { alpha = a }.background(color, CircleShape))
}

// ======================================================================== zap: groot logo bij wisselen

/** Bij het wisselen van zender: logo en nummer groot in beeld, daarna zakken ze weg naar de infobalk. */
@Composable
fun ZapBadge(channel: Channel, modifier: Modifier = Modifier) {
    val a = remember(channel.key) { Animatable(0f) }      // zichtbaar
    val y = remember(channel.key) { Animatable(0f) }      // omlaag
    val s = remember(channel.key) { Animatable(1.25f) }   // schaal
    LaunchedEffect(channel.key) {
        launch { a.animateTo(1f, tween(180)) }
        s.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
        delay(650)
        launch { y.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
        a.animateTo(0f, tween(420))
    }
    Column(
        modifier.graphicsLayer {
            alpha = a.value; scaleX = s.value * (1f - 0.35f * y.value); scaleY = scaleX
            translationY = y.value * size.height * 2.2f
        }.background(Color(0xCC0B0B0F), RoundedCornerShape(22.dp)).padding(horizontal = 34.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChannelLogo(channel.logo, channel.name, Modifier.size(width = 160.dp, height = 96.dp))
        Spacer(Modifier.height(10.dp))
        Label("${channel.number}  ${shortName(channel.name)}", size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
    }
}

// ======================================================================== geanimeerd logo (laadscherm)

/**
 * Woordmerk "kijk" zonder puntjes als afbeelding; de blauwe stip (i) en afspeelknop (j) vallen er met een
 * veertje in. Posities zijn verhoudingen binnen de afbeelding (zie scripts/icons2.py).
 */
@Composable
fun AnimatedLogo(modifier: Modifier = Modifier) {
    val word = remember { Animatable(0f) }
    val dot = remember { Animatable(-1f) }
    val play = remember { Animatable(-1f) }
    LaunchedEffect(Unit) {
        launch { word.animateTo(1f, tween(450)) }
        delay(250)
        launch { dot.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
        delay(180)
        play.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
    }
    Box(modifier.aspectRatio(16f / 9f)) {
        Image(painterResource(R.drawable.wordmark), contentDescription = "Kijk",
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = word.value; scaleX = 0.92f + 0.08f * word.value; scaleY = scaleX })
        Canvas(Modifier.fillMaxSize()) {
            val r = size.height * LOGO_DOT_R
            // stip op de i: valt van boven
            drawCircle(Color(0xFF1E90E0), radius = r, center = Offset(size.width * LOGO_DOT_X, size.height * LOGO_DOT_Y + dot.value * size.height * 0.5f),
                alpha = (1f + dot.value).coerceIn(0f, 1f))
            // afspeelknop op de j: schuift van links in
            val cx = size.width * LOGO_PLAY_X + play.value * size.width * 0.25f
            val cy = size.height * LOGO_DOT_Y
            val t = r * 1.25f
            val path = Path().apply { moveTo(cx - t * 0.8f, cy - t); lineTo(cx - t * 0.8f, cy + t); lineTo(cx + t, cy); close() }
            drawPath(path, Brush.verticalGradient(listOf(Color(0xFF28A0DC), Color(0xFF3C46AA)), startY = cy - t, endY = cy + t), alpha = (1f + play.value).coerceIn(0f, 1f))
        }
    }
}
