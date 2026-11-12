@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.LoadProgress

@Composable
fun EmptyState(title: String, subtitle: String = "") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(title, size = Kijk.Title, weight = Kijk.Semibold)
            if (subtitle.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Label(subtitle, size = Kijk.Body, color = Kijk.Secondary, maxLines = 3) }
        }
    }
}

@Composable
fun PillButton(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, selected = selected, shape = RoundedCornerShape(12.dp), focusedScale = 1.06f) {
        Label(text, Modifier.padding(horizontal = 22.dp, vertical = 10.dp), size = Kijk.Headline, weight = Kijk.Semibold)
    }
}

@Composable
fun SmallPill(text: String, modifier: Modifier = Modifier, selected: Boolean = false, highlight: Color? = null, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    FocusCard(onClick = onClick, onFocus = onFocus, modifier = modifier, selected = selected, shape = RoundedCornerShape(50), focusedScale = 1.06f) { f ->
        Box(if (highlight != null && !f) Modifier.background(highlight) else Modifier) {
            Label(text, Modifier.padding(horizontal = 14.dp, vertical = 5.dp), size = Kijk.Caption, weight = Kijk.Semibold)
        }
    }
}

/** Zachte fade-in (0,15 s) bij een nieuw scherm of tab; goedkoop via graphicsLayer. */
@Composable
fun FadeIn(key: Any?, enabled: Boolean = true, content: @Composable () -> Unit) {
    val a = androidx.compose.runtime.remember(key) { androidx.compose.animation.core.Animatable(if (enabled) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(key) { if (enabled) a.animateTo(1f, androidx.compose.animation.core.tween(150)) }
    androidx.compose.foundation.layout.Box(Modifier.graphicsLayer { alpha = a.value }) { content() }
}

@Composable
fun Section(title: String) {
    Label(title, Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp), size = Kijk.Caption, weight = Kijk.Semibold, color = Kijk.Secondary)
}

@Composable
fun SettingRow(title: String, value: String, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f) { focused ->
        Row(Modifier.padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(title, Modifier.weight(1f), size = Kijk.Headline, weight = Kijk.Medium)
            Spacer(Modifier.width(16.dp))
            Label(value, Modifier.weight(1.3f), size = Kijk.Body, color = secondary(focused), maxLines = 2)
        }
    }
}

/** Tekstveld voor de afstandsbediening: OK/Volgende gaat naar het volgende veld. */
@Composable
fun Field(
    label: String, value: String, onChange: (String) -> Unit, placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text, secret: Boolean = false, last: Boolean = false, modifier: Modifier = Modifier,
    onDone: (() -> Unit)? = null,
) {
    val fm = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier.padding(bottom = 10.dp)) {
        if (label.isNotEmpty()) Label(label, Modifier.padding(start = 4.dp, bottom = 4.dp), size = Kijk.Caption, weight = Kijk.Semibold, color = Kijk.Secondary)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = if (focused) Color.Black else Color.White, fontSize = Kijk.Headline),
            cursorBrush = SolidColor(Kijk.Accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (last) ImeAction.Done else ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onAny = { onDone?.invoke() ?: fm.moveFocus(FocusDirection.Down) }),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) Kijk.Focused else Kijk.Fill, RoundedCornerShape(Kijk.Corner))
                .border(2.dp, if (focused) Kijk.Accent else Color.Transparent, RoundedCornerShape(Kijk.Corner))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Label(placeholder, size = Kijk.Headline, color = if (focused) Kijk.SecondaryOnFocus else Kijk.Secondary); inner() }
            },
        )
    }
}

@Composable
fun ProgressLine(p: LoadProgress?, message: String?, error: Boolean) {
    Box(Modifier.fillMaxWidth().height(20.dp), contentAlignment = Alignment.CenterStart) {
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(p.label + "…", size = Kijk.Caption, color = Kijk.Secondary)
                Spacer(Modifier.width(12.dp))
                val frac = if (p.total > 0) (p.done.toFloat() / p.total).coerceIn(0f, 1f) else null
                Box(Modifier.width(360.dp).height(5.dp).background(Color(0x24FFFFFF), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth(frac ?: 0.3f).height(5.dp).background(Kijk.Accent, RoundedCornerShape(50)))
                }
                Spacer(Modifier.width(12.dp))
                val mb = if (p.done > 0) "%.1f MB".format(p.done / 1048576f) else ""
                Label(listOfNotNull(frac?.let { "${(it * 100).toInt()} %" }, mb.ifEmpty { null }).joinToString(" · "), size = Kijk.Caption, color = Kijk.Secondary)
            }
        } else if (message != null) {
            Label(message, size = Kijk.Caption, color = if (error) Kijk.Live else Kijk.Secondary)
        }
    }
}

/** Cijfertoets van de afstandsbediening of het toetsenbord, of null. */
fun digitOf(e: KeyEvent): Char? = when (e.key) {
    Key.Zero, Key.NumPad0 -> '0'; Key.One, Key.NumPad1 -> '1'; Key.Two, Key.NumPad2 -> '2'; Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'; Key.Five, Key.NumPad5 -> '5'; Key.Six, Key.NumPad6 -> '6'; Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'; Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

/** Zendernaam zonder landvoorvoegsel ("NL: NPO 1" → "NPO 1"). */
fun shortName(n: String) = n.replace(Regex("""^\s*[A-Z]{2,3}\s*[:|]\s*"""), "")

@Composable
fun NumberOverlay(digits: String, vm: AppViewModel, modifier: Modifier = Modifier) {
    if (digits.isEmpty()) return
    val ch = vm.channels.firstOrNull { it.number.toString() == digits }
    Column(modifier.background(Color(0xCC000000), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 10.dp), horizontalAlignment = Alignment.End) {
        Label(digits, size = Kijk.LargeTitle, weight = Kijk.Bold, color = Color.White)
        Label(ch?.name ?: "…", size = Kijk.Caption, color = Color(0xFFD1D1D6))
    }
}

/**
 * Menu-item in lijnstijl: grijze tekst; actief = wit met blauwe lijn; focus = wit, iets groter, witte lijn.
 * Geen achtergrondvlak, dus net zo licht als de oude pillen.
 */
@Composable
fun LineItem(
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable (color: Color) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val line = when { active -> Kijk.Accent; focused -> Color.White; else -> Color.Transparent }
    val lineH = if (active) 3.dp else 2.dp
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus?.invoke() }
        },
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.Transparent, focusedContentColor = Color.White,
            pressedContainerColor = Color.Transparent, pressedContentColor = Color.White,
        ),
    ) {
        Box(
            Modifier
                .drawBehind { if (line != Color.Transparent) { val h = lineH.toPx(); drawRect(line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h)) } }
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 9.dp),
        ) { content(if (focused || active) Color.White else Kijk.Secondary) }
    }
}

@Composable
fun LineText(text: String, modifier: Modifier = Modifier, active: Boolean = false, size: androidx.compose.ui.unit.TextUnit = Kijk.Headline, onFocus: (() -> Unit)? = null, onClick: () -> Unit) {
    LineItem(modifier, active, onFocus, onClick) { c -> Label(text, size = size, weight = Kijk.Semibold, color = c) }
}

/** Aan/uit-knop in lijnstijl: vinkje-rondje voor de tekst. */
@Composable
fun LineToggle(text: String, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    LineItem(modifier, false, null, onClick) { c ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
                val r = size.minDimension / 2
                if (checked) {
                    drawCircle(Kijk.Accent, r)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.68f)
                        lineTo(size.width * 0.73f, size.height * 0.36f)
                    }
                    drawPath(p, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.8.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                } else {
                    val w = 1.5.dp.toPx()
                    drawCircle(c, r - w / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
                }
            }
            Spacer(Modifier.width(7.dp))
            Label(text, size = Kijk.Caption, weight = Kijk.Semibold, color = if (checked) Color.White else c)
        }
    }
}

enum class LineIconKind { Search, Settings, Exit }

/** Eenvoudige lijn-iconen (zoals Tabler), getekend op een Canvas: geen extra bibliotheek nodig. */
@Composable
fun LineIcon(kind: LineIconKind, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    androidx.compose.foundation.Canvas(modifier) {
        val s = size.minDimension / 24f       // tekenen op een 24×24 raster
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * s, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x * s, y * s)
        fun path(block: androidx.compose.ui.graphics.Path.() -> Unit) = drawPath(androidx.compose.ui.graphics.Path().apply(block), color, style = st)
        when (kind) {
            LineIconKind.Search -> {
                drawCircle(color, 7f * s, o(10f, 10f), style = st)
                drawLine(color, o(15.2f, 15.2f), o(21f, 21f), 2f * s, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            LineIconKind.Settings -> {
                // tandwiel: 8 tanden rond een ring
                val c = o(12f, 12f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val cx = kotlin.math.cos(a).toFloat(); val sy = kotlin.math.sin(a).toFloat()
                    drawLine(color, androidx.compose.ui.geometry.Offset(c.x + cx * 7f * s, c.y + sy * 7f * s), androidx.compose.ui.geometry.Offset(c.x + cx * 9.6f * s, c.y + sy * 9.6f * s), 2.6f * s, androidx.compose.ui.graphics.StrokeCap.Round)
                }
                drawCircle(color, 7f * s, c, style = st)
                drawCircle(color, 2.6f * s, c, style = st)
            }
            LineIconKind.Exit -> {
                // deur met pijl naar buiten (ti-door-exit)
                path { moveTo(13f * s, 12f * s); lineTo(13f * s, 12.01f * s) }
                path { moveTo(3f * s, 21f * s); lineTo(21f * s, 21f * s) }
                path { moveTo(5f * s, 21f * s); lineTo(5f * s, 5f * s); quadraticTo(5f * s, 3f * s, 7f * s, 3f * s); lineTo(14f * s, 3f * s) }
                path { moveTo(19f * s, 13f * s); lineTo(19f * s, 21f * s) }
                path { moveTo(14f * s, 7f * s); lineTo(21f * s, 7f * s) }
                path { moveTo(18f * s, 4f * s); lineTo(21f * s, 7f * s); lineTo(18f * s, 10f * s) }
            }
        }
    }
}

/** Dunne grijze lijn onder een balk. */
fun Modifier.baseline(): Modifier = drawBehind {
    val h = 1.dp.toPx()
    drawRect(Color(0xFF2A2A31), topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
}
