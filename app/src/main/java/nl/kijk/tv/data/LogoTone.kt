package nl.kijk.tv.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf

/**
 * Hoe een zenderlogo getekend moet worden op het donkere vak:
 * 0 = zoals het is, 1 = wit maken (zwart/heel donker en eenkleurig), 2 = lichtgrijs plaatje erachter (heel donker en veelkleurig).
 * Eén keer gemeten per logo-adres en onthouden (ook na opnieuw opstarten).
 */
object LogoTone {
    private val tones = mutableStateMapOf<String, Int>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("kijk_logotone", 0)
        prefs = p
        p.all.forEach { (k, v) -> (v as? Int)?.let { tones[k] = it } }
    }

    fun tone(url: String): Int = tones[url] ?: 0
    fun known(url: String) = url in tones

    /** Meet op een klein plaatje (paar honderd pixels); draait op de achtergrond. */
    fun measure(url: String, src: Bitmap) {
        if (known(url)) return
        val small = try { Bitmap.createScaledBitmap(src, 48, (48f * src.height / src.width.coerceAtLeast(1)).toInt().coerceIn(8, 64), true) } catch (_: Exception) { return }
        val w = small.width; val h = small.height
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        var n = 0; var lum = 0.0; var sat = 0.0
        for (c in px) {
            if ((c ushr 24) < 60) continue
            val r = ((c shr 16) and 255) / 255.0; val g = ((c shr 8) and 255) / 255.0; val b = (c and 255) / 255.0
            lum += 0.2126 * r + 0.7152 * g + 0.0722 * b
            sat += maxOf(r, g, b) - minOf(r, g, b)
            n++
        }
        if (small !== src) small.recycle()
        val t = if (n < 15) 0 else {
            val l = lum / n; val s = sat / n
            when {
                l >= 0.15 -> 0          // goed genoeg zichtbaar (ook diep rood/paars zoals TLC of EO)
                s < 0.22 -> 1           // zwart of donkergrijs: wit maken
                else -> 2               // donker en gekleurd: plaatje erachter
            }
        }
        tones[url] = t
        prefs?.edit()?.putInt(url, t)?.apply()
    }
}
