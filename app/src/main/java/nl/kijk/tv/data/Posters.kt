package nl.kijk.tv.data

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Ontbrekende posters van buiten halen:
 * - series: TVmaze (gratis, geen sleutel)
 * - films (en series als TVmaze niets vindt): TMDB, alleen als er een sleutel is (uit kijk-sleutels.json in de privé repo)
 * Resultaten (ook "niet gevonden") worden bewaard, zodat elke titel maar één keer wordt opgezocht.
 */
class Posters(dir: File, private val tmdbKey: () -> String) {
    private val file = File(dir, "posters.tsv")
    private val cache = ConcurrentHashMap<String, String>()
    private val sem = kotlinx.coroutines.sync.Semaphore(3)

    init {
        runCatching { if (file.exists()) file.forEachLine { l -> val i = l.indexOf('\t'); if (i > 0) cache[l.substring(0, i)] = l.substring(i + 1) } }
    }

    fun cached(key: String): String? = cache[key]?.ifEmpty { null }
    fun known(key: String) = cache.containsKey(key)

    suspend fun movie(id: String, name: String, year: String?, tmdbId: String?): String? = lookup("m:$id", needsKey = true) {
        val key = tmdbKey()
        if (key.isBlank()) return@lookup null
        val path = if (!tmdbId.isNullOrBlank()) tmdb(key, "movie/$tmdbId")?.optString("poster_path") else null
        (path?.takeIf { it.isNotBlank() && it != "null" } ?: tmdbSearch(key, "movie", name, year))?.let { "https://image.tmdb.org/t/p/w342$it" }
    }

    suspend fun show(id: String, name: String, year: String?): String? = lookup("s:$id", needsKey = false) {
        val (q, y) = clean(name, year)
        val tv = runCatching {
            val o = JSONObject(get("https://api.tvmaze.com/singlesearch/shows?q=" + enc(q)) ?: return@runCatching null)
            if (y != null && o.optString("premiered").take(4).let { it.isNotEmpty() && it != y }) null
            else o.optJSONObject("image")?.let { it.optString("original").ifEmpty { it.optString("medium") } }?.ifEmpty { null }
        }.getOrNull()
        tv ?: tmdbKey().takeIf { it.isNotBlank() }?.let { k -> tmdbSearch(k, "tv", name, year)?.let { "https://image.tmdb.org/t/p/w342$it" } }
    }

    private suspend fun lookup(key: String, needsKey: Boolean, find: suspend () -> String?): String? {
        cache[key]?.let { return it.ifEmpty { null } }
        if (needsKey && tmdbKey().isBlank()) return null   // zonder sleutel niet als "niet gevonden" onthouden
        sem.acquire()
        try {
            cache[key]?.let { return it.ifEmpty { null } }
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { find() }.getOrNull() }
            cache[key] = r ?: ""
            runCatching { synchronized(file) { file.appendText("$key\t${r ?: ""}\n") } }
            return r
        } finally { sem.release() }
    }

    private fun tmdbSearch(key: String, kind: String, name: String, year: String?): String? {
        val (q, y) = clean(name, year)
        val yp = if (y != null) (if (kind == "movie") "&year=$y" else "&first_air_date_year=$y") else ""
        val o = tmdb(key, "search/$kind", "query=" + enc(q) + yp) ?: return null
        val res = o.optJSONArray("results") ?: return null
        for (i in 0 until minOf(res.length(), 5)) {
            val p = res.optJSONObject(i)?.optString("poster_path")
            if (!p.isNullOrBlank() && p != "null") return p
        }
        return null
    }

    private fun tmdb(key: String, path: String, query: String = ""): JSONObject? {
        val bearer = key.startsWith("eyJ")   // Read Access Token; anders de korte API Key
        val q = listOf(query, "language=nl-NL", if (!bearer) "api_key=" + enc(key) else "").filter { it.isNotEmpty() }.joinToString("&")
        val text = get("https://api.themoviedb.org/3/$path?$q", if (bearer) key else null) ?: return null
        return runCatching { JSONObject(text) }.getOrNull()
    }

    private fun get(url: String, bearer: String? = null): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Kijk-TV")
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val PREFIX = Regex("""^\s*(\|[^|]{1,6}\||\[[^\]]{1,12}\]|[A-Z0-9]{2,4}\s*[-:|]\s+)""")
        private val YEAR = Regex("""\((\d{4})\)""")
        private val TAIL = Regex("""\s*(\(\d{4}\)|\[[^\]]*\]|\b(4K|UHD|FHD|HD|SD|MULTI|NL|SUB)\b).*$""", RegexOption.IGNORE_CASE)

        /** Naam opschonen voor zoeken: landcodes/labels ervoor en jaar/kwaliteit erachter weg. */
        fun clean(name: String, year: String?): Pair<String, String?> {
            var n = name
            repeat(2) { n = n.replace(PREFIX, "") }
            val y = year?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: YEAR.findAll(name).lastOrNull()?.groupValues?.get(1)
            n = n.replace(TAIL, "").trim().ifEmpty { name }
            return n to y
        }
    }
}
