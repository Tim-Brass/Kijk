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
import kotlinx.coroutines.launch
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
/** Schermen in de "donker + blauw randje"-stijl (Live, Gids, Instellingen) zetten dit aan voor al hun tegels. */
val LocalDarkFocus = androidx.compose.runtime.staticCompositionLocalOf { true }   // overal de donkere stijl

/** Kleuren voor het rustrandje van kaarten. */
object Outline {
    val Steel = Color(0xFF9FB8DA)   // koel staal/blauw: standaard
    val Gold = Color(0xFFD9B26A)    // goud: uitgelichte kaart (volgende wedstrijd)
}

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
    outline: Color? = null,       // dun glazen randje in rust (licht aan de bovenkant), bv. Outline.Steel of Outline.Gold
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val darkFocus = darkFocus || LocalDarkFocus.current
    Surface(
        // sporttegels en posters: hetzelfde dunne blauwe randje met een beetje ruimte eromheen
        border = if (focusRing || darkFocus) ClickableSurfaceDefaults.border(
            // rust: dun lichtrandje, bovenaan het helderst (als glas dat licht vangt)
            border = outline?.let { o -> androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp,
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(o.copy(alpha = 0.75f), o.copy(alpha = 0.22f), o.copy(alpha = 0.40f)))), inset = 0.dp, shape = shape) }
                ?: androidx.tv.material3.Border.None,
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
            containerColor = if (darkFocus) (if (selected) Color(0xFF2A2A33) else Color(0xFF16161C)) else if (selected) Kijk.FillSelected else Kijk.Fill,
            contentColor = Color.White,
            focusedContainerColor = if (darkFocus) (if (selected) Color(0xFF2E2E38) else Color(0xFF1B1B23)) else Kijk.Focused,
            focusedContentColor = if (darkFocus) Color.White else Color.Black,
            // indrukken: heel even een fractie lichter, nooit wit (zoals bij de posters)
            pressedContainerColor = if (darkFocus) Color(0xFF2C2C36) else Color(0xFFD1D1D6),
            pressedContentColor = if (darkFocus) Color.White else Color.Black,
        ),
    ) {
        // donkere stijl: de inhoud blijft er bij focus hetzelfde uitzien (wit op donker), alleen het randje verandert
        content(focused && !darkFocus)
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
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier.size(width = 64.dp, height = 40.dp), plate: Boolean = true) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (plate) Color(0x14FFFFFF) else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        // eerst het verzorgde logo; lukt dat niet, dan dat van de aanbieder; anders de naam
        val nice = androidx.compose.runtime.remember(name, nl.kijk.tv.data.TvLogos.enabled) { nl.kijk.tv.data.TvLogos.url(name) }
        var step by androidx.compose.runtime.remember(name, url, nice) { androidx.compose.runtime.mutableIntStateOf(if (nice != null) 0 else 1) }
        val model = when (step) { 0 -> nice; 1 -> url; else -> null }
        if (model != null) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(Unit) { nl.kijk.tv.data.LogoTone.init(ctx) }
            val tone = nl.kijk.tv.data.LogoTone.tone(model)
            val req = androidx.compose.runtime.remember(ctx, model) {
                // Decodeer logo's maximaal op de kleine TV-weergavegrootte, zonder grote bronbitmaps.
                // Software-bitmap blijft nodig voor de eenmalige tintanalyse.
                coil.request.ImageRequest.Builder(ctx).data(model).size(256, 160)
                    .allowHardware(nl.kijk.tv.data.LogoTone.known(model)).crossfade(false).build()   // na de meting: via de grafische chip
            }
            Box(
                Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp)
                    .then(if (tone == 2) Modifier.background(Color(0xFFE5E5EA), RoundedCornerShape(6.dp)) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                AsyncImage(
                    model = req,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (tone == 1) androidx.compose.ui.graphics.ColorFilter.tint(Color.White) else null,
                    onError = { step++ },
                    onSuccess = { st ->
                        if (!nl.kijk.tv.data.LogoTone.known(model)) {
                            val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                            if (bmp != null && bmp.config != android.graphics.Bitmap.Config.HARDWARE)
                                nl.kijk.tv.data.LogoTone.measureLater(model, bmp)
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(horizontal = if (tone == 2) 5.dp else 2.dp, vertical = if (tone == 2) 3.dp else 2.dp),
                )
            }
        } else {
            Label(shortName(name).take(3).uppercase(), size = Kijk.Caption, weight = Kijk.Bold, color = Kijk.Secondary)
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
