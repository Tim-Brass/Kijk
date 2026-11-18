package nl.kijk.tv.data

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Hoe een zenderlogo getekend moet worden op het donkere vak:
 * 0 = zoals het is, 1 = wit maken (zwart/heel donker en eenkleurig), 2 = lichtgrijs plaatje erachter (heel donker en veelkleurig).
 * Eén keer gemeten per logo-adres en onthouden (ook na opnieuw opstarten).
 */
object LogoTone {
    private val tones = mutableStateMapOf<String, Int>()
    private var prefs: android.content.SharedPreferences? = null
    private val initMutex = Mutex()
    private var initialized = false

    /** SharedPreferences kan bij een eerste opening schijf-I/O doen: nooit op de UI-thread. */
    suspend fun init(ctx: Context) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return@withLock
            val (p, saved) = withContext(Dispatchers.IO) {
                val p = ctx.applicationContext.getSharedPreferences("kijk_logotone", 0)
                val saved = p.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
                p to saved
            }
            // metingen van vóór het laden ook bewaren
            val early = tones.filterKeys { it !in saved }
            tones.putAll(saved)
            if (early.isNotEmpty()) withContext(Dispatchers.IO) { p.edit().apply { early.forEach { (k, v) -> putInt(k, v) } }.apply() }
            prefs = p
            initialized = true
        }
    }

    // één wachtrij voor alle metingen, i.p.v. een losse taak per logo
    private val queue = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "kijk-logotone").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }.asCoroutineDispatcher())
    private val pending = java.util.Collections.synchronizedSet(HashSet<String>())
    fun measureLater(url: String, bmp: Bitmap) {
        if (known(url) || !pending.add(url)) return
        queue.launch { try { measure(url, bmp) } finally { pending.remove(url) } }
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
