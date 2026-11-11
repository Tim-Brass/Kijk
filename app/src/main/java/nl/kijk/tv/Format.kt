package nl.kijk.tv

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Format {
    private val nl = Locale("nl", "NL")
    private val hm = SimpleDateFormat("HH:mm", nl)
    private val day = SimpleDateFormat("EEEE d MMMM", nl)

    fun time(t: Long): String = hm.format(Date(t))
    fun range(a: Long, b: Long) = "${time(a)} – ${time(b)}"

    fun dayLabel(t: Long, now: Long = System.currentTimeMillis()): String {
        val diff = dayIndex(t) - dayIndex(now)
        return when (diff) {
            0 -> "Vandaag"
            1 -> "Morgen"
            -1 -> "Gisteren"
            else -> day.format(Date(t)).replaceFirstChar { it.uppercase() }
        }
    }

    fun dayTime(t: Long) = "${dayLabel(t).lowercase()} ${time(t)}"

    fun dayIndex(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
    }

    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun minutesLeft(stop: Long, now: Long): String {
        val m = ((stop - now) / 60_000L).coerceAtLeast(0)
        return if (m >= 60) "nog ${m / 60} u ${m % 60} min" else "nog $m min"
    }

    /** 1:02:03 of 4:05 */
    fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** "1 u 52 min" uit Xtream-duur ("01:52:10") of minuten. */
    fun runtime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(':').mapNotNull { it.trim().toIntOrNull() }
        val minutes = when {
            parts.size >= 2 -> parts[0] * 60 + parts[1]
            parts.size == 1 -> parts[0]
            else -> return raw
        }
        if (minutes <= 0) return null
        return if (minutes >= 60) "${minutes / 60} u ${minutes % 60} min" else "$minutes min"
    }

    fun language(code: String?): String? {
        if (code.isNullOrBlank() || code == "und") return null
        val name = Locale(code).getDisplayLanguage(nl)
        return if (name.isBlank() || name == code) code.uppercase() else name.replaceFirstChar { it.uppercase() }
    }
}
