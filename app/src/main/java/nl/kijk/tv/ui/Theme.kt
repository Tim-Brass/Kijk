@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Kleuren en maten volgens de tvOS Human Interface Guidelines:
 * donkere weergave, systeemblauw als accent, grijze secundaire tekst,
 * en een veilige marge van ±5 % rondom (overscan).
 */
object Kijk {
    val Background = Color(0xFF0B0B0F)
    val Elevated = Color(0xFF1C1C22)
    val Fill = Color(0x1AFFFFFF)           // ongefocust element
    val FillSelected = Color(0x33FFFFFF)   // geselecteerd, niet gefocust
    val Focused = Color(0xFFF2F2F7)        // gefocust: licht "opgetild" element
    val Accent = Color(0xFF0A84FF)         // systemBlue (donker)
    val Live = Color(0xFFFF453A)           // systemRed (donker)
    val Secondary = Color(0xFF98989F)      // secondaryLabel
    val SecondaryOnFocus = Color(0xFF55555C)
    val Separator = Color(0x26FFFFFF)

    val SafeH = 48.dp
    val SafeV = 27.dp
    val Corner = 14.dp

    val LargeTitle = 30.sp
    val Title = 22.sp
    val Headline = 17.sp
    val Body = 15.sp
    val Caption = 13.sp

    val Bold = FontWeight.Bold
    val Semibold = FontWeight.SemiBold
    val Medium = FontWeight.Medium
}

@Composable
fun KijkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Kijk.Accent,
            background = Kijk.Background,
            surface = Kijk.Background,
            onSurface = Color.White,
            onBackground = Color.White,
        ),
        content = content,
    )
}
