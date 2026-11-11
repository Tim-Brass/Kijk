package nl.kijk.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Een eerder bekeken film of serie, voor "Verder kijken". */
data class Recent(val kind: String, val id: String, val name: String, val poster: String?, val categoryId: String, val url: String?)

/**
 * Alle instellingen en kijkgegevens, bewaard op de tv. Sleutels en pincode zijn gelijk aan de
 * webversie, zodat instellingen van de laptop naar de tv overgezet kunnen worden.
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("kijk", Context.MODE_PRIVATE)

    private fun str(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()
    private fun putInt(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun putLong(k: String, v: Long) = sp.edit().putLong(k, v).apply()
    private fun putBool(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun set(k: String): Set<String> = try { JSONArray(str(k, "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } } catch (_: Exception) { emptySet() }
    private fun putSet(k: String, v: Collection<String>) = put(k, JSONArray(v.toList()).toString())
    private fun map(k: String): Map<String, String> = try { JSONObject(str(k, "{}")).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } } catch (_: Exception) { emptyMap() }
    private fun putMap(k: String, v: Map<String, String>) = put(k, JSONObject(v).toString())

    // ---- bron
    var playlistUrl: String get() = str("playlistUrl"); set(v) = put("playlistUrl", v)
    var epgUrl: String get() = str("epgUrl"); set(v) = put("epgUrl", v)
    var refreshPlaylist: String get() = str("refreshPlaylist", "daily"); set(v) = put("refreshPlaylist", v)
    var refreshEpg: String get() = str("refreshEpg", "twice"); set(v) = put("refreshEpg", v)
    var playlistUpdated: Long get() = sp.getLong("playlistUpdated", 0); set(v) = putLong("playlistUpdated", v)
    var epgOffsetMinutes: Int get() = sp.getInt("epgOffset", 0); set(v) = putInt("epgOffset", v)
    var archiveJson: String get() = str("archive", "{}"); set(v) = put("archive", v)

    // ---- keuzes
    var favorites: Set<String> get() = set("favs"); set(v) = putSet("favs", v)
    var hiddenGroups: Set<String> get() = set("hidden"); set(v) = putSet("hidden", v)
    var hiddenMovieCats: Set<String> get() = set("hiddenM"); set(v) = putSet("hiddenM", v)
    var hiddenShowCats: Set<String> get() = set("hiddenS"); set(v) = putSet("hiddenS", v)
    var epgLinks: Map<String, String> get() = map("epgLinks"); set(v) = putMap("epgLinks", v)
    var guideArch: Boolean get() = sp.getBoolean("guideArch", false); set(v) = putBool("guideArch", v)
    var guideFav: Boolean get() = sp.getBoolean("guideFav", false); set(v) = putBool("guideFav", v)
    var lastTab: Int get() = sp.getInt("tab", 0); set(v) = putInt("tab", v)

    // ---- kinderslot
    var pinHash: String get() = str("pinHash"); set(v) = put("pinHash", v)
    var lockOn: Boolean get() = sp.getBoolean("lockOn", true); set(v) = putBool("lockOn", v)
    fun pinOk(code: String) = if (pinHash.isEmpty()) code == "0000" else hashPin(code) == pinHash

    // ---- ondertitels
    var subStyle: SubStyle
        get() = SubStyle(str("subLang", "nl"), sp.getInt("subSize", 1), sp.getInt("subBg", 0), sp.getInt("subPos", 0))
        set(v) = sp.edit().putString("subLang", v.language).putInt("subSize", v.size).putInt("subBg", v.background).putInt("subPos", v.position).apply()
    /** Dolby/DTS-geluid in de app zelf omzetten (anders soms geen geluid via tv of soundbar). */
    var softAudio: Boolean get() = sp.getBoolean("softAudio", true); set(v) = putBool("softAudio", v)
    var autoSubs: Boolean get() = sp.getBoolean("autoSubs", true); set(v) = putBool("autoSubs", v)
    var osKey: String get() = str("osKey"); set(v) = put("osKey", v)
    var osUser: String get() = str("osUser"); set(v) = put("osUser", v)
    var osPass: String get() = str("osPass"); set(v) = put("osPass", v)

    // ---- kijkgeschiedenis
    fun progress(key: String): Progress? = str("p:$key").takeIf { it.isNotEmpty() }?.split('|')?.let { Progress(it[0].toLong(), it.getOrNull(1)?.toLongOrNull() ?: 0) }
    fun saveProgress(key: String, pos: Long, dur: Long) = put("p:$key", "$pos|$dur")
    fun progressKeys(): List<String> = sp.all.keys.filter { it.startsWith("p:") }.map { it.removePrefix("p:") }
    var lastEpisodes: Map<String, String> get() = map("lastEp"); set(v) = putMap("lastEp", v)
    fun setLastEpisode(showId: String, epKey: String) { lastEpisodes = lastEpisodes + (showId to epKey) }
    var recentChannels: List<String> get() = try { JSONArray(str("recentCh", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (_: Exception) { emptyList() }
        set(v) = put("recentCh", JSONArray(v).toString())
    fun addRecentChannel(key: String) { recentChannels = (listOf(key) + recentChannels.filter { it != key }).take(20) }

    var recent: List<Recent>
        get() = try {
            JSONArray(str("recent", "[]")).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Recent(it.getString("kind"), it.getString("id"), it.optString("name"), it.optString("poster").ifEmpty { null },
                        it.optString("cat"), it.optString("url").ifEmpty { null })
                }
            }
        } catch (_: Exception) { emptyList() }
        set(v) = put("recent", JSONArray(v.map {
            JSONObject().put("kind", it.kind).put("id", it.id).put("name", it.name).put("poster", it.poster ?: "").put("cat", it.categoryId).put("url", it.url ?: "")
        }).toString())
    fun addRecent(r: Recent) { recent = (listOf(r) + recent.filter { !(it.kind == r.kind && it.id == r.id) }).take(40) }

    /** Gekozen OpenSubtitles-ondertitel per film/aflevering: bestand + verschuiving in seconden. */
    fun subChoice(key: String): Pair<Long, Double>? = str("sc:$key").takeIf { it.isNotEmpty() }?.split('|')?.let { it[0].toLong() to (it.getOrNull(1)?.toDoubleOrNull() ?: 0.0) }
    fun setSubChoice(key: String, fileId: Long, offset: Double) = put("sc:$key", "$fileId|$offset")

    fun clearHistory(kind: String) {
        val e = sp.edit()
        val keys = sp.all.keys
        if (kind == "movie" || kind == "all") {
            keys.filter { it.startsWith("p:m:") }.forEach { e.remove(it) }
            e.apply(); recent = recent.filter { it.kind != "movie" }
        }
        if (kind == "show" || kind == "all") {
            val e2 = sp.edit()
            sp.all.keys.filter { it.startsWith("p:") && !it.startsWith("p:m:") }.forEach { e2.remove(it) }
            e2.remove("lastEp").apply(); recent = recent.filter { it.kind != "show" }
        }
        if (kind == "live" || kind == "all") sp.edit().remove("recentCh").remove("lastChannel").apply()
        if (kind == "all") { val e3 = sp.edit(); sp.all.keys.filter { it.startsWith("sc:") }.forEach { e3.remove(it) }; e3.remove("recent").apply() }
    }

    var lastChannel: String get() = str("lastChannel"); set(v) = put("lastChannel", v)

    /**
     * Instellingen overnemen van de webversie of van de telefoon (zelfde namen als de webversie).
     * Alleen velden die aanwezig zijn worden overgenomen.
     */
    fun import(o: JSONObject) {
        fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        o.optString("playlist_url").takeIf { it.isNotBlank() }?.let { playlistUrl = it.trim() }
        if (o.has("epg_url")) epgUrl = o.optString("epg_url").trim()
        arr("favs")?.let { favorites = it }
        arr("hidden")?.let { hiddenGroups = it }
        arr("hiddenM")?.let { hiddenMovieCats = it }
        arr("hiddenS")?.let { hiddenShowCats = it }
        o.optJSONObject("epg_links")?.let { l -> epgLinks = l.keys().asSequence().associateWith { l.getString(it) } }
        o.optString("pinHash").takeIf { it.isNotBlank() }?.let { pinHash = it }
        if (o.has("lockOn")) lockOn = o.optBoolean("lockOn", true)
        o.optJSONObject("sub")?.let { s ->
            val langs = listOf("nl", "en", "auto", "off")
            subStyle = SubStyle(langs.getOrElse(s.optInt("lang", 0)) { "nl" }, s.optInt("size", 1), s.optInt("bg", 0), s.optInt("pos", 0))
        }
        if (o.has("offset")) epgOffsetMinutes = o.optInt("offset", 0)
        o.optString("os_key").takeIf { it.isNotBlank() }?.let { osKey = it.trim() }
        o.optString("os_user").takeIf { it.isNotBlank() }?.let { osUser = it.trim() }
        o.optString("os_pass").takeIf { it.isNotBlank() }?.let { osPass = it }
        if (o.has("auto_subs")) autoSubs = o.optBoolean("auto_subs", true)
        o.optString("refresh_playlist").takeIf { it.isNotBlank() }?.let { refreshPlaylist = it }
        o.optString("refresh_epg").takeIf { it.isNotBlank() }?.let { refreshEpg = it }
    }

    companion object {
        /** Zelfde als de webversie: SHA-256 van "kijk:" + code. */
        fun hashPin(code: String): String =
            MessageDigest.getInstance("SHA-256").digest("kijk:$code".toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
