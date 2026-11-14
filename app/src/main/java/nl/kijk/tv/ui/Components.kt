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
        border = if (darkFocus) ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White), shape = shape),
        ) else if (focusRing) ClickableSurfaceDefaults.border(
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
) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = size,
        fontWeight = weight,
        color = color,
        maxLines = maxLines,
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
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(4.dp),
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
