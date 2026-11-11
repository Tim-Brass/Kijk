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
