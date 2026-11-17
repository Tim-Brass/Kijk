package nl.kijk.tv.data

/** Nette zendernamen voor weergave: "NL: NPO 1 FHD" → "NPO 1". De echte naam blijft intern
 *  (gidskoppeling, zoeken, logo's). Per naam één keer berekend en onthouden. */
object ChannelNames {
    @Volatile var enabled = true
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val qCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val prefix = Regex("""^\s*(?:[\[|(]\s*[A-Za-z]{2,3}\s*[\]|)]|[A-Za-z]{2,3}\s*[:|]|(?:NL|BE|VL|UK|DE)\s+-)\s*""")
    private val brackets = Regex("""\s*[\[(][^\])]*[\])]""")
    private val noise = Regex("""(?i)\b(uhd|fhd|hd|sd|hq|4k|8k|hevc|h\.?26[45]|50 ?fps|60 ?fps|raw|backup|back-up|vip|multi|nl|be)\b""")
    private val qual = listOf("4K" to Regex("""(?i)\b(4k|uhd)\b"""), "FHD" to Regex("""(?i)\b(fhd|1080p?)\b"""),
        "HD" to Regex("""(?i)\bhd\b"""), "SD" to Regex("""(?i)\bsd\b"""), "Backup" to Regex("""(?i)\bback-?up\b"""))
    // namen die meestal anders geschreven worden
    private val fixes = mapOf("NPO1" to "NPO 1", "NPO2" to "NPO 2", "NPO3" to "NPO 3", "RTL4" to "RTL 4", "RTL5" to "RTL 5",
        "RTL7" to "RTL 7", "RTL8" to "RTL 8", "RTLZ" to "RTL Z", "NET5" to "Net5", "VERONICA" to "Veronica", "SBS 6" to "SBS6", "SBS 9" to "SBS9")

    fun clean(name: String): String = if (!enabled) name.replace(Regex("""^\s*[A-Z]{2,3}\s*[:|]\s*"""), "") else cache.getOrPut(name) { compute(name) }

    /** Kwaliteitslabel uit de originele naam ("FHD", "HD", ...) of "" */
    fun quality(name: String): String = qCache.getOrPut(name) { qual.firstOrNull { it.second.containsMatchIn(name) }?.first ?: "" }

    private fun compute(raw: String): String {
        var s = raw.replace(prefix, "")
        s = s.replace(brackets, " ").replace(noise, " ")
        s = s.replace(Regex("""[|•·_]+"""), " ").replace(Regex("""\s*[-:]\s*$"""), "").replace(Regex("""^\s*[-:]\s*"""), "")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.isEmpty()) return raw.trim()
        fixes[s.uppercase()]?.let { return it }
        // schreeuwende HOOFDLETTERS netjes maken, korte afkortingen (NPO, RTL, SBS, ESPN) laten staan
        if (s.length > 5 && s == s.uppercase() && s.any { it.isLetter() })
            s = s.split(' ').joinToString(" ") { w -> if (w.length <= 4 || w.any { it.isDigit() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
        return s
    }
}
