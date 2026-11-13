package nl.kijk.tv.data

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Alle aanroepen zijn blokkerend; roep ze aan vanaf Dispatchers.IO. [cat] = null betekent: alles. */
interface VodSource {
    fun movieCategories(): List<VodCategory>
    fun movies(cat: String?): List<Movie>
    fun movieInfo(movie: Movie): MovieInfo?
    fun showCategories(): List<VodCategory>
    fun shows(cat: String?): List<Show>
    fun showDetails(show: Show): ShowDetails
}

private fun JSONObject.str(name: String): String? = optString(name, "").takeIf { it.isNotBlank() && it != "null" }
private fun JSONObject.firstOf(vararg names: String): String? = names.firstNotNullOfOrNull { str(it) }

/** Xtream Codes-API: categorieën, posters, beschrijvingen, seizoenen, terugkijken. */
private val MOVIE_KEYS = setOf("stream_id", "container_extension", "name", "stream_icon", "category_id", "rating", "year", "added")
private val SHOW_KEYS = setOf("series_id", "name", "cover", "category_id", "plot", "rating", "releaseDate", "release_date", "last_modified")
private val LIVE_KEYS = setOf("stream_id", "tv_archive", "tv_archive_duration")

class Xtream(val host: String, val user: String, val pass: String) : VodSource {
    private fun enc(s: String) = Uri.encode(s)

    private fun api(action: String?, extra: String = ""): String {
        val a = if (action != null) "&action=$action" else ""
        return Net.text("$host/player_api.php?username=${enc(user)}&password=${enc(pass)}$a$extra")
    }

    /**
     * Grote lijsten (alle films, alle zenders) niet als één tekst inlezen: dat kan >100 MB zijn en de tv heeft
     * te weinig geheugen. In plaats daarvan object voor object lezen en alleen de velden bewaren die we nodig hebben.
     */
    private fun stream(action: String, extra: String, keys: Set<String>, each: (Map<String, String>) -> Unit) {
        val url = "$host/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action$extra"
        Net.open(url).use { input ->
            val r = android.util.JsonReader(java.io.InputStreamReader(java.io.BufferedInputStream(input, 65536), Charsets.UTF_8))
            r.isLenient = true
            if (r.peek() != android.util.JsonToken.BEGIN_ARRAY) return
            r.beginArray()
            val m = HashMap<String, String>()
            while (r.hasNext()) {
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                m.clear()
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (name !in keys) { r.skipValue(); continue }
                    when (r.peek()) {
                        android.util.JsonToken.STRING, android.util.JsonToken.NUMBER -> m[name] = r.nextString()
                        android.util.JsonToken.BOOLEAN -> m[name] = r.nextBoolean().toString()
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                each(m)
            }
        }
    }
    private fun Map<String, String>.s(k: String) = this[k]?.takeIf { it.isNotBlank() && it != "null" }

    private fun array(json: String): JSONArray = json.trim().let { if (it.startsWith("[")) JSONArray(it) else JSONArray() }
    private fun obj(json: String): JSONObject = json.trim().let { if (it.startsWith("{")) JSONObject(it) else JSONObject() }

    private fun categories(action: String): List<VodCategory> {
        val a = array(api(action))
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            VodCategory(o.str("category_id") ?: return@mapNotNull null, o.str("category_name") ?: "Overig")
        }
    }

    override fun movieCategories() = categories("get_vod_categories")
    override fun showCategories() = categories("get_series_categories")

    override fun movies(cat: String?): List<Movie> {
        val out = ArrayList<Movie>()
        stream("get_vod_streams", if (cat != null) "&category_id=${enc(cat)}" else "", MOVIE_KEYS) { o ->
            val id = o.s("stream_id") ?: return@stream
            val ext = o.s("container_extension") ?: "mp4"
            out += Movie(id, o.s("name") ?: "Film", o.s("stream_icon"), cat ?: o.s("category_id") ?: "",
                "$host/movie/${enc(user)}/${enc(pass)}/$id.$ext", o.s("rating")?.takeIf { it != "0" }, o.s("year"),
                o.s("added")?.toLongOrNull()?.let { it * 1000 })
        }
        return out
    }

    override fun movieInfo(movie: Movie): MovieInfo? {
        val root = obj(api("get_vod_info", "&vod_id=${movie.id}"))
        val info = root.optJSONObject("info") ?: return null
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() }
        return MovieInfo(
            plot = info.firstOf("plot", "description"), genre = info.str("genre"), duration = info.str("duration"),
            cast = info.firstOf("cast", "actors"), director = info.str("director"),
            backdrop = backdrop ?: info.str("movie_image"), year = info.str("releasedate")?.take(4),
            tmdb = info.firstOf("tmdb_id", "tmdb"), imdb = info.str("imdb_id"),
            poster = info.firstOf("cover_big", "movie_image"),
        )
    }

    override fun shows(cat: String?): List<Show> {
        val out = ArrayList<Show>()
        // bij "alles" (zoeken) de beschrijving overslaan: scheelt veel geheugen
        val keys = if (cat == null) SHOW_KEYS - "plot" else SHOW_KEYS
        stream("get_series", if (cat != null) "&category_id=${enc(cat)}" else "", keys) { o ->
            out += Show(o.s("series_id") ?: return@stream, o.s("name") ?: "Serie", o.s("cover"), cat ?: o.s("category_id") ?: "",
                o.s("plot"), o.s("rating")?.takeIf { it != "0" }, (o.s("releaseDate") ?: o.s("release_date"))?.take(4),
                o.s("last_modified")?.toLongOrNull()?.let { it * 1000 })
        }
        return out
    }

    override fun showDetails(show: Show): ShowDetails {
        val root = obj(api("get_series_info", "&series_id=${show.id}"))
        val info = root.optJSONObject("info")
        val episodes = ArrayList<Episode>()
        root.optJSONObject("episodes")?.let { bySeason ->
            val keys = bySeason.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = bySeason.optJSONArray(k) ?: continue
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val id = e.str("id") ?: continue
                    val ext = e.str("container_extension") ?: "mp4"
                    val ei = e.optJSONObject("info")
                    val num = e.str("episode_num")?.toIntOrNull() ?: (i + 1)
                    episodes += Episode(
                        key = "x$id", season = e.str("season")?.toIntOrNull() ?: k.toIntOrNull() ?: 1, number = num,
                        title = e.str("title") ?: "Aflevering $num", url = "$host/series/${enc(user)}/${enc(pass)}/$id.$ext",
                        plot = ei?.str("plot"), duration = ei?.str("duration"), still = ei?.str("movie_image"),
                    )
                }
            }
        }
        val backdrop = info?.optJSONArray("backdrop_path")?.optString(0)?.takeIf { it.isNotBlank() }
        return ShowDetails(info?.str("plot") ?: show.plot, backdrop, episodes.sortedWith(compareBy({ it.season }, { it.number })), info?.firstOf("tmdb", "tmdb_id"))
    }

    /** {zendernummer: dagen terugkijken} en het tijdsverschil van de server (ms t.o.v. UTC). */
    fun archiveInfo(): Pair<Map<String, Int>, Long> {
        val days = HashMap<String, Int>()
        stream("get_live_streams", "", LIVE_KEYS) { o ->
            if (o.s("tv_archive") == "1" || o.s("tv_archive") == "true") days[o.s("stream_id") ?: return@stream] = o.s("tv_archive_duration")?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        }
        var offset = 0L
        try {
            val si = obj(api(null)).optJSONObject("server_info")
            val ts = si?.str("timestamp_now")?.toLongOrNull()
            val local = si?.str("time_now")
            if (ts != null && local != null) {
                val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val asUtc = f.parse(local)?.time
                if (asUtc != null) offset = Math.round((asUtc - ts * 1000) / 900_000.0) * 900_000L
            }
        } catch (_: Exception) { }
        return days to offset
    }

    /** Volledige terugkijkgids van één zender (vaak 7 dagen, ook als de XMLTV-gids korter teruggaat). */
    fun archiveEpg(streamId: String): List<Programme> {
        val root = obj(api("get_simple_data_table", "&stream_id=$streamId"))
        val list = root.optJSONArray("epg_listings") ?: return emptyList()
        fun dec(v: String?): String = try { String(Base64.decode(v ?: "", Base64.DEFAULT), Charsets.UTF_8).trim() } catch (_: Exception) { (v ?: "").trim() }
        return (0 until list.length()).mapNotNull { i ->
            val e = list.optJSONObject(i) ?: return@mapNotNull null
            val st = (e.str("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null) * 1000
            val sp = (e.str("stop_timestamp")?.toLongOrNull() ?: return@mapNotNull null) * 1000
            val title = dec(e.str("title")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (sp <= st) null else Programme(st, sp, title, dec(e.str("description")).takeIf { it.isNotEmpty() })
        }
    }

    fun timeshiftUrl(streamId: String, startMs: Long, minutes: Int, offset: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return "$host/timeshift/${enc(user)}/${enc(pass)}/$minutes/${f.format(startMs + offset)}/$streamId.ts"
    }

    fun xmltvUrl() = "$host/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

    companion object {
        /** Herkent een Xtream get.php-link en haalt server, gebruiker en wachtwoord eruit. */
        fun fromPlaylistUrl(url: String): Xtream? {
            if (!url.contains("get.php")) return null
            val uri = Uri.parse(url)
            val user = uri.getQueryParameter("username") ?: return null
            val pass = uri.getQueryParameter("password") ?: return null
            return Xtream("${uri.scheme}://${uri.encodedAuthority}", user, pass)
        }
    }
}

/** Films en series uit een gewone M3U-playlist (zonder extra informatie). */
class M3uVodSource(entries: List<VodEntry>) : VodSource {
    private val episodeRegex = Regex("""^(.*?)[\s._-]*[Ss](\d{1,2})[\s._-]*[Ee](\d{1,3})(.*)$""")
    private val movieEntries = entries.filter { !it.isSeries }

    private class Ep(val show: String, val group: String, val season: Int, val number: Int, val entry: VodEntry, val rest: String)

    private val eps: List<Ep> = entries.filter { it.isSeries }.map { e ->
        val m = episodeRegex.find(e.name)
        if (m != null) Ep(m.groupValues[1].trim().ifEmpty { e.name }, e.group, m.groupValues[2].toInt(), m.groupValues[3].toInt(), e, m.groupValues[4].trim(' ', '-', '.', ':'))
        else Ep(e.group, e.group, 1, 0, e, e.name)
    }

    override fun movieCategories() = movieEntries.map { it.group }.distinct().map { VodCategory(it, it) }
    override fun showCategories() = eps.map { it.group }.distinct().map { VodCategory(it, it) }

    override fun movies(cat: String?) = movieEntries.filter { cat == null || it.group == cat }.map {
        Movie(it.url, it.name, it.logo, it.group, it.url, null, Regex("""\((\d{4})\)""").find(it.name)?.groupValues?.get(1))
    }

    override fun movieInfo(movie: Movie): MovieInfo? = null

    override fun shows(cat: String?) = eps.filter { cat == null || it.group == cat }
        .groupBy { it.group to it.show }
        .map { (k, list) -> Show("${k.first}|${k.second}", k.second, list.firstNotNullOfOrNull { it.entry.logo }, k.first, null, null, null) }

    override fun showDetails(show: Show): ShowDetails {
        var counter = 0
        return ShowDetails(null, null, eps.filter { "${it.group}|${it.show}" == show.id }.map { e ->
            counter++
            val n = if (e.number > 0) e.number else counter
            Episode(e.entry.url, e.season, n, e.rest.ifBlank { "Aflevering $n" }, e.entry.url, null, null, e.entry.logo)
        }.sortedWith(compareBy({ it.season }, { it.number })), null)
    }
}

/** Terugkijk-adres voor M3U-zenders met catchup-gegevens (zelfde regels als de webversie). */
object Catchup {
    fun m3uUrl(ch: Channel, start: Long, stop: Long): String {
        val minutes = ((stop - start) / 60_000 + 1).toInt().coerceAtLeast(1)
        val mode = (ch.catchup ?: "default").lowercase()
        val s = start / 1000; val e = stop / 1000; val n = System.currentTimeMillis() / 1000
        val url = ch.url
        if (mode == "shift" || (mode in setOf("default", "append") && ch.catchupSource == null))
            return url + (if ('?' in url) "&" else "?") + "utc=$s&lutc=$n"
        if (mode in setOf("flussonic", "flussonic-hls", "flussonic-ts", "fs")) {
            val base = url.substringBeforeLast('/')
            return if (".m3u8" in url) "$base/archive-$s-${e - s}.m3u8" else "$base/timeshift_abs-$s.ts"
        }
        val g = SimpleDateFormat("yyyy|MM|dd|HH|mm|ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(start).split('|')
        var src = ch.catchupSource ?: ""
        mapOf(
            "{utc}" to s, "\${start}" to s, "{start}" to s, "{utcend}" to e, "\${end}" to e, "{end}" to e,
            "{lutc}" to n, "\${now}" to n, "\${timestamp}" to n, "{timestamp}" to n, "{duration}" to e - s,
            "\${duration}" to e - s, "{duration:60}" to minutes, "{offset}" to n - s, "\${offset}" to n - s,
            "{Y}" to g[0], "{m}" to g[1], "{d}" to g[2], "{H}" to g[3], "{M}" to g[4], "{S}" to g[5],
        ).forEach { (k, v) -> src = src.replace(k, v.toString()) }
        return if (mode == "append" || !src.startsWith("http")) url + src else src
    }
}
