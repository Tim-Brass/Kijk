package nl.kijk.tv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.kijk.tv.data.Catchup
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.EpgHit
import nl.kijk.tv.data.EpgRepository
import nl.kijk.tv.data.EpgRepository.Companion.DAY
import nl.kijk.tv.data.EpgRepository.Companion.HOUR
import nl.kijk.tv.data.EpgRepository.Companion.MINUTE
import nl.kijk.tv.data.M3u
import nl.kijk.tv.data.M3uVodSource
import nl.kijk.tv.data.Movie
import nl.kijk.tv.data.MovieInfo
import nl.kijk.tv.data.Net
import nl.kijk.tv.data.OpenSubtitles
import nl.kijk.tv.data.Playlist
import nl.kijk.tv.data.Programme
import nl.kijk.tv.data.Recent
import nl.kijk.tv.data.Show
import nl.kijk.tv.data.ShowDetails
import nl.kijk.tv.data.Store
import nl.kijk.tv.data.SubStyle
import nl.kijk.tv.data.VodCategory
import nl.kijk.tv.data.VodSource
import nl.kijk.tv.data.Xtream
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LoadProgress(val label: String, val done: Long = 0, val total: Long = 0)
data class SearchResult(
    val channels: List<Channel>,
    val programmes: List<Pair<Channel, Programme>>,
    val movies: List<Movie>,
    val shows: List<Show>,
)

const val GROUP_ALL = "\u0000alle"
const val GROUP_FAV = "\u0000fav"
const val GROUP_ARCH = "\u0000arch"
const val GROUP_RECENT = "\u0000recent"

/** Het hart van de app: playlist, gids, terugkijken, films/series, keuzes. Zelfde werking als de webversie. */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    private val epg = EpgRepository(app)
    private val cacheFile = File(app.filesDir, "playlist.m3u")
    val subs = OpenSubtitles(store, File(app.filesDir, "subs"))

    // ---- toestand voor de schermen
    var allChannels by mutableStateOf<List<Channel>>(emptyList()); private set
    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var allGroups by mutableStateOf<List<String>>(emptyList()); private set
    var groups by mutableStateOf<List<String>>(emptyList()); private set
    var progress by mutableStateOf<LoadProgress?>(null); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var epgBusy by mutableStateOf(false); private set
    var now by mutableLongStateOf(System.currentTimeMillis()); private set
    var favorites by mutableStateOf(store.favorites); private set
    var hidden by mutableStateOf(store.hiddenGroups); private set
    var hiddenMovieCats by mutableStateOf(store.hiddenMovieCats); private set
    var hiddenShowCats by mutableStateOf(store.hiddenShowCats); private set
    var recentChannels by mutableStateOf(store.recentChannels); private set
    var subStyle by mutableStateOf(store.subStyle); private set
    var epgOffsetMinutes by mutableStateOf(store.epgOffsetMinutes); private set
    var unlocked by mutableStateOf(false); private set
    var lockOn by mutableStateOf(store.lockOn); private set
    var vod by mutableStateOf<VodSource?>(null); private set
    var epgMatch by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var dataVersion by mutableStateOf(0); private set   // verhoogd als zenders/gids veranderen
    private var nowNextById by mutableStateOf<Map<String, List<Programme>>>(emptyMap())
    private var playlistEpgUrl: String? = null
    private var serverOffset = 0L
    private val vodCache = HashMap<String, Any?>()
    private val archCache = HashMap<String, Pair<Long, List<Programme>>>()
    private val loadLock = Mutex()
    private var loadJob: Job? = null

    val xtream: Xtream? get() = Xtream.fromPlaylistUrl(store.playlistUrl)
    val configured get() = store.playlistUrl.isNotBlank()
    private val offset get() = epgOffsetMinutes * MINUTE

    init {
        viewModelScope.launch { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
        viewModelScope.launch { while (true) { delay(60_000); refreshNowNext() } }
        viewModelScope.launch {
            while (true) {
                delay(30 * 60_000L)
                if (!configured) continue
                if (playlistDue(false)) load() else if (epgDue(false)) refreshEpg()
            }
        }
        if (configured) load(startup = true)
    }

    // ---------------------------------------------------------------- vernieuwen
    private fun day(t: Long) = if (t == 0L) "" else SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    fun playlistDue(startup: Boolean): Boolean {
        val last = store.playlistUpdated
        if (!cacheFile.exists() || last == 0L) return true
        return when (store.refreshPlaylist) {
            "start" -> startup
            "daily" -> day(last) != day(System.currentTimeMillis())
            "weekly" -> System.currentTimeMillis() - last > 7 * DAY
            else -> false
        }
    }

    fun epgDue(startup: Boolean): Boolean {
        val last = epg.updated()
        if (last == 0L) return true
        return when (store.refreshEpg) {
            "start" -> startup
            "twice" -> System.currentTimeMillis() - last > 12 * HOUR
            "daily" -> day(last) != day(System.currentTimeMillis())
            else -> false
        }
    }

    fun load(forcePlaylist: Boolean = false, forceEpg: Boolean = false, startup: Boolean = false) {
        loadJob = viewModelScope.launch {
            loadLock.withLock {
                error = null
                val xt = xtream
                val download = forcePlaylist || playlistDue(startup)
                var downloaded = false
                val pl: Playlist? = withContext(Dispatchers.IO) {
                    try {
                        if (download) {
                            progress = LoadProgress("Playlist downloaden")
                            val tmp = File(cacheFile.path + ".tmp")
                            Net.open(store.playlistUrl) { d, t -> progress = LoadProgress("Playlist downloaden", d, t) }.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            tmp.renameTo(cacheFile); downloaded = true
                            store.playlistUpdated = System.currentTimeMillis()
                        } else progress = LoadProgress("Opgeslagen playlist openen")
                        progress = LoadProgress("Zenders verwerken")
                        cacheFile.useLines { M3u.parse(it, xt != null) }
                    } catch (e: Exception) {
                        error = "Playlist laden mislukt: ${e.message ?: e.javaClass.simpleName}"
                        if (cacheFile.exists()) { error += " (opgeslagen versie gebruikt)"; cacheFile.useLines { M3u.parse(it, xt != null) } } else null
                    }
                }
                if (pl == null) { progress = null; return@withLock }
                // terugkijken (Xtream): alleen opnieuw vragen als de playlist vers is
                var chans = pl.channels
                if (xt != null) {
                    val (arch, off) = withContext(Dispatchers.IO) {
                        val cached = try { JSONObject(store.archiveJson) } catch (_: Exception) { JSONObject() }
                        if (!downloaded && cached.has("archive")) {
                            val a = cached.getJSONObject("archive"); a.keys().asSequence().associateWith { a.getInt(it) } to cached.optLong("offset")
                        } else try {
                            progress = LoadProgress("Terugkijk-informatie ophalen")
                            val r = xt.archiveInfo()
                            store.archiveJson = JSONObject().put("archive", JSONObject(r.first as Map<*, *>)).put("offset", r.second).toString()
                            r
                        } catch (_: Exception) { emptyMap<String, Int>() to 0L }
                    }
                    serverOffset = off
                    chans = chans.map { c -> c.streamId?.let { sid -> arch[sid] }?.let { c.copy(archiveDays = it) } ?: c.copy(archiveDays = if (c.catchup != null) c.archiveDays else 0) }
                }
                playlistEpgUrl = pl.epgUrl
                progress = LoadProgress("Zenders aan de gids koppelen")
                val m = withContext(Dispatchers.IO) { epg.match(chans, store.epgLinks) }
                allChannels = chans
                allGroups = chans.map { it.group }.distinct()
                epgMatch = m
                val newVod: VodSource? = xt ?: pl.vod.takeIf { it.isNotEmpty() }?.let { M3uVodSource(it) }
                vod = newVod; vodCache.clear(); archCache.clear()
                applyHidden()
                progress = null
                refreshNowNext()
                if (forceEpg || epgDue(startup)) refreshEpgLocked()
            }
        }
    }

    fun refreshEpg() { viewModelScope.launch { loadLock.withLock { refreshEpgLocked() } } }

    private suspend fun refreshEpgLocked() {
        val urls = (store.epgUrl.ifBlank { playlistEpgUrl ?: xtream?.xmltvUrl() ?: "" }).split(',').map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) { status = "Geen gids-link. Vul er een in bij Instellingen › Playlist."; return }
        epgBusy = true
        try {
            withContext(Dispatchers.IO) {
                var label = "Gids downloaden"
                epg.refresh(urls) { msg, d, t ->
                    if (msg != null) label = msg.trimEnd('…')
                    progress = if (d >= 0) LoadProgress(label, d, t) else LoadProgress(label, progress?.done ?: 0, progress?.total ?: 0)
                }
            }
            status = null
        } catch (e: Exception) {
            status = "Gids bijwerken mislukt: ${e.message ?: e.javaClass.simpleName}"
        } finally { epgBusy = false; progress = null }
        epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }
        refreshNowNext()
        dataVersion++
    }

    private suspend fun refreshNowNext() {
        val at = System.currentTimeMillis() - offset
        nowNextById = withContext(Dispatchers.IO) { epg.nowNext(at) }
    }

    // ---------------------------------------------------------------- zenders en groepen
    fun isLocked(name: String?) = lockOn && name != null && ADULT.containsMatchIn(name)

    private fun applyHidden() {
        channels = allChannels.filter { it.group !in hidden && (unlocked || !isLocked(it.group)) }
        groups = allGroups.filter { it !in hidden }
        dataVersion++
    }

    fun list(group: String): List<Channel> = when (group) {
        GROUP_ALL -> channels
        GROUP_FAV -> channels.filter { it.key in favorites }
        GROUP_ARCH -> channels.filter { it.archiveDays > 0 }
        GROUP_RECENT -> recentChannels.mapNotNull { k -> channels.firstOrNull { it.key == k } }
        else -> if (isLocked(group) && !unlocked) emptyList() else channels.filter { it.group == group }
    }

    fun hasEpg(ch: Channel) = ch.key in epgMatch || (ch.archiveDays > 0 && ch.streamId != null && xtream != null)

    fun nowNext(ch: Channel): List<Programme> {
        val l = epgMatch[ch.key]?.let { nowNextById[it] } ?: return emptyList()
        return l.map { it.copy(start = it.start + offset, stop = it.stop + offset) }.filter { it.stop > now }
    }

    /** Gids uit XMLTV, aangevuld met de terugkijkgids van de server voor oudere programma's. */
    suspend fun programmes(ch: Channel, from: Long, to: Long): List<Programme> = withContext(Dispatchers.IO) {
        val f = from - offset; val t = to - offset
        val cid = epgMatch[ch.key]
        var lst = if (cid != null) epg.programmes(cid, f, t) else emptyList()
        val xt = xtream
        if (xt != null && ch.archiveDays > 0 && ch.streamId != null && f < System.currentTimeMillis()) {
            val earliest = lst.minOfOrNull { it.start } ?: cid?.let { epg.earliest(it) }
            if (earliest == null || f < earliest) {
                val older = archiveEpg(xt, ch.streamId).filter { it.stop > f && it.start < t && (earliest == null || it.stop <= earliest + 5 * MINUTE) }
                lst = EpgRepository.dedupe(older + lst)
            }
        }
        lst.map { it.copy(start = it.start + offset, stop = it.stop + offset) }
    }

    private fun archiveEpg(xt: Xtream, sid: String): List<Programme> {
        archCache[sid]?.let { if (System.currentTimeMillis() - it.first < 30 * MINUTE) return it.second }
        val l = try { xt.archiveEpg(sid) } catch (_: Exception) { emptyList() }
        archCache[sid] = System.currentTimeMillis() to l
        return l
    }

    fun canCatchup(ch: Channel, p: Programme, at: Long = now) = p.stop <= at && ch.archiveDays > 0 && p.start > at - ch.archiveDays * DAY
    fun canRestart(ch: Channel, p: Programme, at: Long = now) = ch.archiveDays > 0 && p.start <= at && p.start > at - ch.archiveDays * DAY

    fun catchupUrl(ch: Channel, start: Long, stop: Long): String {
        val s = start - offset; val e = stop - offset
        val xt = xtream
        return if (xt != null && ch.streamId != null && ch.catchupSource == null)
            xt.timeshiftUrl(ch.streamId, s, ((e - s) / MINUTE + 1).toInt().coerceAtLeast(1), serverOffset)
        else Catchup.m3uUrl(ch, s, e)
    }

    fun toggleFavorite(ch: Channel) {
        favorites = if (ch.key in favorites) favorites - ch.key else favorites + ch.key
        store.favorites = favorites
    }

    fun watched(ch: Channel) {
        store.lastChannel = ch.key; store.addRecentChannel(ch.key); recentChannels = store.recentChannels
    }

    fun setHiddenGroups(v: Set<String>) { hidden = v; store.hiddenGroups = v; applyHidden() }
    fun setHiddenMovieCats(v: Set<String>) { hiddenMovieCats = v; store.hiddenMovieCats = v }
    fun setHiddenShowCats(v: Set<String>) { hiddenShowCats = v; store.hiddenShowCats = v }

    fun unlock() { unlocked = true; applyHidden() }
    fun relock() { unlocked = false; applyHidden() }
    fun setLock(on: Boolean) { lockOn = on; store.lockOn = on; unlocked = false; applyHidden() }

    fun setEpgOffset(m: Int) { epgOffsetMinutes = m; store.epgOffsetMinutes = m; dataVersion++; viewModelScope.launch { refreshNowNext() } }
    fun updateSubStyle(s: SubStyle) { subStyle = s; store.subStyle = s }

    suspend fun epgSearch(q: String): List<EpgHit> = withContext(Dispatchers.IO) { epg.search(q) }
    fun linkEpg(name: String, cid: String?) {
        store.epgLinks = if (cid == null) store.epgLinks - name else store.epgLinks + (name to cid)
        viewModelScope.launch { epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }; refreshNowNext(); dataVersion++ }
    }
    fun epgStatsText(): String {
        val s = epg.stats()
        if (s.programmes == 0) return "Nog geen gidsgegevens"
        val upd = epg.updated().takeIf { it > 0 }?.let { " · bijgewerkt ${Format.dayTime(it)}" } ?: ""
        return "${s.programmes} programma's · ${channels.count { it.key in epgMatch }} van ${channels.size} zenders gekoppeld$upd"
    }

    // ---------------------------------------------------------------- films en series
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> cached(key: String, load: (VodSource) -> T): T? {
        val src = vod ?: return null
        if (vodCache.containsKey(key)) return vodCache[key] as T
        return withContext(Dispatchers.IO) { load(src) }.also { vodCache[key] = it }
    }
    suspend fun movieCategories(): List<VodCategory> = cached("mc") { it.movieCategories() } ?: emptyList()
    suspend fun movies(cat: String): List<Movie> = cached("m:$cat") { it.movies(cat) } ?: emptyList()
    suspend fun movieInfo(m: Movie): MovieInfo? = try { cached("mi:${m.id}") { it.movieInfo(m) } } catch (_: Exception) { null }
    suspend fun showCategories(): List<VodCategory> = cached("sc") { it.showCategories() } ?: emptyList()
    suspend fun shows(cat: String): List<Show> = cached("s:$cat") { it.shows(cat) } ?: emptyList()
    suspend fun showDetails(s: Show): ShowDetails? = cached("sd:${s.id}") { it.showDetails(s) }

    fun continueMovies(): List<Movie> = store.recent.filter { it.kind == "movie" && store.progress("m:" + it.id)?.resumable == true }
        .map { Movie(it.id, it.name, it.poster, it.categoryId, it.url ?: "", null, null) }
    fun continueShows(): List<Show> = store.recent.filter { it.kind == "show" && store.lastEpisodes.containsKey(it.id) }
        .map { Show(it.id, it.name, it.poster, it.categoryId, null, null, null) }
    fun addRecentMovie(m: Movie) = store.addRecent(Recent("movie", m.id, m.name, m.poster, m.categoryId, m.url))
    fun addRecentShow(s: Show) = store.addRecent(Recent("show", s.id, s.name, s.poster, s.categoryId, null))

    // ---------------------------------------------------------------- zoeken
    suspend fun search(q0: String): SearchResult {
        val q = q0.trim().lowercase()
        if (q.length < 2) return SearchResult(emptyList(), emptyList(), emptyList(), emptyList())
        fun rank(n: String) = n.lowercase().let { if (it.startsWith(q)) 0 else if (" $q" in it) 1 else 2 }
        val chans = channels.filter { q in it.name.lowercase() }.sortedBy { rank(it.name) }.take(40)
        val at = System.currentTimeMillis()
        val byCid = HashMap<String, MutableList<Channel>>()
        val visible = channels.associateBy { it.key }
        epgMatch.forEach { (k, cid) -> visible[k]?.let { byCid.getOrPut(cid) { ArrayList() } += it } }
        val progs = withContext(Dispatchers.IO) { epg.find(q0.trim(), at - 7 * DAY - offset, at + 2 * DAY - offset) }
            .flatMap { f -> (byCid[f.cid] ?: emptyList()).map { it to f.p.copy(start = f.p.start + offset, stop = f.p.stop + offset) } }
            .filter { (c, p) -> p.stop > at || canCatchup(c, p, at) }
            .sortedWith(compareBy({ (_, p) -> if (p.isLive(at)) 0 else if (p.start > at) 1 else 2 }, { (_, p) -> kotlin.math.abs(p.start - at) }))
            .take(60)
        val mCats = (try { movieCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        val sCats = (try { showCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        val movies = (try { cached("m:*") { it.movies(null) } } catch (_: Exception) { null } ?: emptyList())
            .filter { q in it.name.lowercase() && it.categoryId !in hiddenMovieCats && (unlocked || (!isLocked(mCats[it.categoryId]) && !isLocked(it.name))) }
            .sortedBy { rank(it.name) }.take(60)
        val shows = (try { cached("s:*") { it.shows(null) } } catch (_: Exception) { null } ?: emptyList())
            .filter { q in it.name.lowercase() && it.categoryId !in hiddenShowCats && (unlocked || (!isLocked(sCats[it.categoryId]) && !isLocked(it.name))) }
            .sortedBy { rank(it.name) }.take(60)
        return SearchResult(chans, progs, movies, shows)
    }

    // ---------------------------------------------------------------- bron en import
    fun saveSource(playlistUrl: String, epgUrl: String) {
        store.playlistUrl = playlistUrl; store.epgUrl = epgUrl; store.playlistUpdated = 0L; store.archiveJson = "{}"
        allChannels = emptyList(); channels = emptyList(); cacheFile.delete()
        load(forcePlaylist = true, forceEpg = true)
    }

    /** Instellingen van de telefoon of de webversie overnemen. */
    fun importSettings(json: JSONObject) {
        val oldUrl = store.playlistUrl
        store.import(json)
        favorites = store.favorites; hidden = store.hiddenGroups; hiddenMovieCats = store.hiddenMovieCats; hiddenShowCats = store.hiddenShowCats
        subStyle = store.subStyle; epgOffsetMinutes = store.epgOffsetMinutes; lockOn = store.lockOn; unlocked = false
        if (store.playlistUrl != oldUrl || allChannels.isEmpty()) saveSource(store.playlistUrl, store.epgUrl)
        else { applyHidden(); viewModelScope.launch { epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }; dataVersion++ } }
    }

    fun clearHistory(kind: String) { store.clearHistory(kind); recentChannels = store.recentChannels; dataVersion++ }

    companion object {
        val ADULT = Regex("""(adult|xxx|18\s*\+|\+\s*18|porn|erotic|erotiek|volwassen)""", RegexOption.IGNORE_CASE)
    }
}
