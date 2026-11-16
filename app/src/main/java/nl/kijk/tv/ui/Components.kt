@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.text.font.FontWeight

/**
 * Basis-bouwsteen: een element dat bij focus licht wordt en iets "optilt",
 * zoals cellen op tvOS. Zo is altijd in één oogopslag te zien waar de focus staat.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onFocus: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(Kijk.Corner),
    focusedScale: Float = 1.03f,
    focusRing: Boolean = false,   // dun blauw randje om het geselecteerde item (posters)
    darkFocus: Boolean = false,   // tegel blijft donker bij focus, met een wit randje (sporttegels)
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        // sporttegels en posters: hetzelfde dunne blauwe randje met een beetje ruimte eromheen
        border = if (focusRing || darkFocus) ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Kijk.Accent), inset = 3.dp, shape = shape),
        ) else ClickableSurfaceDefaults.border(),
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused != focused) {
                focused = it.isFocused
                if (it.isFocused) onFocus?.invoke()
            }
        },
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (darkFocus) Color(0xFF16161C) else if (selected) Kijk.FillSelected else Kijk.Fill,
            contentColor = Color.White,
            focusedContainerColor = if (darkFocus) Color(0xFF1B1B23) else Kijk.Focused,
            focusedContentColor = if (darkFocus) Color.White else Color.Black,
            pressedContainerColor = Color(0xFFD1D1D6),
            pressedContentColor = Color.Black,
        ),
    ) {
        content(focused)
    }
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = Kijk.Body,
    weight: FontWeight? = null,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    align: androidx.compose.ui.text.style.TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = size,
        fontWeight = weight,
        color = color,
        maxLines = maxLines,
        textAlign = align,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun secondary(focused: Boolean) = if (focused) Kijk.SecondaryOnFocus else Kijk.Secondary

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, focused: Boolean = false, height: Dp = 3.dp) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (focused) Color(0x26000000) else Color(0x33FFFFFF)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(Kijk.Accent),
        )
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier.size(width = 64.dp, height = 40.dp)) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x14FFFFFF)),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // vaste, kleine decodeermaat; lege randen eraf; altijd in verhouding binnen het vak
            val req = androidx.compose.runtime.remember(url) {
                coil.request.ImageRequest.Builder(ctx).data(url).size(256, 160)
                    .scale(coil.size.Scale.FIT).precision(coil.size.Precision.INEXACT)
                    .transformations(TrimTransparent).crossfade(false).build()
            }
            AsyncImage(
                model = req,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().padding(4.dp),
            )
        } else {
            Label(name.take(3).uppercase(), size = Kijk.Caption, weight = Kijk.Bold, color = Kijk.Secondary)
        }
    }
}

@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .background(Kijk.Live)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Label("NU", size = Kijk.Caption, weight = Kijk.Bold, color = Color.White)
    }
}


/**
 * Knipt de lege (doorzichtige of effen) rand rond een zenderlogo weg, zodat alle logo's hun vak even goed vullen.
 * Draait één keer per logo bij het laden (op de achtergrond); het resultaat staat daarna in het geheugen.
 */
object TrimTransparent : coil.transform.Transformation {
    override val cacheKey = "trim-v1"
    override suspend fun transform(input: android.graphics.Bitmap, size: coil.size.Size): android.graphics.Bitmap {
        val w = input.width; val h = input.height
        if (w < 4 || h < 4) return input
        val px = IntArray(w * h)
        input.getPixels(px, 0, w, 0, 0, w, h)
        val corner = px[0]
        val opaque = (corner ushr 24) > 200
        fun empty(c: Int): Boolean {
            val a = c ushr 24
            if (a < 24) return true
            if (!opaque) return false
            // effen achtergrond (zelfde kleur als de hoek)
            val dr = ((c shr 16) and 255) - ((corner shr 16) and 255)
            val dg = ((c shr 8) and 255) - ((corner shr 8) and 255)
            val db = (c and 255) - (corner and 255)
            return dr * dr + dg * dg + db * db < 300
        }
        var top = 0; var bottom = h - 1; var left = 0; var right = w - 1
        fun rowEmpty(y: Int): Boolean { val o = y * w; for (x in 0 until w) if (!empty(px[o + x])) return false; return true }
        fun colEmpty(x: Int, y0: Int, y1: Int): Boolean { for (y in y0..y1) if (!empty(px[y * w + x])) return false; return true }
        while (top < bottom && rowEmpty(top)) top++
        while (bottom > top && rowEmpty(bottom)) bottom--
        while (left < right && colEmpty(left, top, bottom)) left++
        while (right > left && colEmpty(right, top, bottom)) right--
        val cw = right - left + 1; val ch = bottom - top + 1
        // niets (of bijna niets) weg te knippen, of alles leeg: origineel houden
        if (cw < 4 || ch < 4 || (cw >= w - 2 && ch >= h - 2)) return input
        // een effen logo met witte achtergrond niet tot op de letters bijsnijden: een klein randje laten
        val pad = if (opaque) (minOf(cw, ch) * 0.06f).toInt() else 0
        val x0 = (left - pad).coerceAtLeast(0); val y0 = (top - pad).coerceAtLeast(0)
        val x1 = (right + pad).coerceAtMost(w - 1); val y1 = (bottom + pad).coerceAtMost(h - 1)
        return android.graphics.Bitmap.createBitmap(input, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }
}
