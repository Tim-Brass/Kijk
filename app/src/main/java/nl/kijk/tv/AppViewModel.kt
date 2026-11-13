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
import nl.kijk.tv.data.HomeInfo
import nl.kijk.tv.data.News
import nl.kijk.tv.data.Weather
import org.json.JSONObject
import nl.kijk.tv.phone.PhoneServer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LoadProgress(val label: String, val done: Long = 0, val total: Long = 0)

/** Stap op het laadscherm: 0 wacht, 1 bezig, 2 klaar, 3 niet nodig, 4 mislukt. */
data class Step(val label: String, val state: Int = 0, val done: Long = 0, val total: Long = 0, val note: String? = null)
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

    // ---- laadscherm bij het opstarten
    var startupDone by mutableStateOf(store.playlistUrl.isBlank()); private set
    var steps by mutableStateOf(listOf(Step("Playlist"), Step("Zenders"), Step("Terugkijken"), Step("Gids"), Step("Films en series"))); private set
    private fun step(i: Int, f: (Step) -> Step) { steps = steps.toMutableList().also { it[i] = f(it[i]) } }
    fun finishStartup() { startupDone = true }

    // ---------------------------------------------------------------- delen met andere apparaten
    val sync = nl.kijk.tv.data.Sync(app)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    var syncStatus by mutableStateOf<String?>(null); private set
    var peersVersion by mutableStateOf(0); private set
    /** Wat er nu speelt (sleutel + huidige positie), gezet door de spelers. */
    var playing: Pair<String, () -> Long>? = null
    /** Gezet als een ander apparaat het afspelen overneemt: de speler stopt dan. */
    var stoppedBy by mutableStateOf<String?>(null)

    private fun <T> onMain(block: () -> T): T? {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1); var out: T? = null
        mainHandler.post { try { out = block() } finally { latch.countDown() } }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return out
    }

    private fun startSync() {
        PhoneServer.syncHandler = { path, o, from ->
            sync.handle(path, o, from, PhoneServer.port,
                onChanged = { ch -> mainHandler.post { reloadFromStore(ch); if ("playlistUrl" in ch && configured) imports++; peersVersion++ } },
                onStop = { name -> onMain { stopForPeer(name) } })
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (PhoneServer.port == 0) delay(1000)
            // koppelen via wifi is vervangen door GitHub: alleen nog voor een bestaande koppeling
            if (!sync.paired) return@launch
            sync.startDiscovery(PhoneServer.port)
            while (sync.paired) {
                if (sync.paired) {
                    val ch = try { sync.syncAll() } catch (_: Exception) { emptySet() }
                    withContext(Dispatchers.Main) { if (ch.isNotEmpty()) reloadFromStore(ch); peersVersion++ }
                }
                delay(12_000)
            }
        }
    }

    // ---------------------------------------------------------------- delen via GitHub
    val github = nl.kijk.tv.data.GitHubSync(app, sync)
    var githubCode by mutableStateOf<nl.kijk.tv.data.GitHubSync.DeviceCode?>(null); private set
    var githubStatus by mutableStateOf<String?>(null); private set
    var githubVersion by mutableStateOf(0); private set
    private var githubLoginJob: Job? = null
    private val githubMutex = Mutex()

    fun startGitHubLogin() {
        githubLoginJob?.cancel()
        githubStatus = "Code ophalen…"; githubCode = null
        githubLoginJob = viewModelScope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { github.startLogin() }
                githubCode = dc; githubStatus = null
                var wait = dc.interval.coerceAtLeast(5) * 1000L
                while (System.currentTimeMillis() < dc.expiresAt) {
                    delay(wait)
                    val done = try { withContext(Dispatchers.IO) { github.poll(dc) } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: java.net.SocketTimeoutException) { null }
                    if (done == true) {
                        githubCode = null; githubStatus = "Ingelogd als ${github.login}"; githubVersion++
                        githubSyncNow(); return@launch
                    }
                }
                githubCode = null; githubStatus = "De code is verlopen. Probeer het opnieuw."
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { githubCode = null; githubStatus = "Inloggen mislukt: ${e.message ?: e.javaClass.simpleName}" }
        }
    }
    fun cancelGitHubLogin() { githubLoginJob?.cancel(); githubCode = null; githubStatus = null }
    fun githubSignOut() { github.signOut(); githubStatus = "Afgemeld"; githubVersion++ }

    fun githubSyncNow(quiet: Boolean = false) {
        if (!github.connected) return
        viewModelScope.launch {
            if (!githubMutex.tryLock()) return@launch
            try {
                if (!quiet) githubStatus = "Synchroniseren…"
                val ch = withContext(Dispatchers.IO) { github.syncOnce() }
                if (ch.isNotEmpty()) { reloadFromStore(ch); if ("playlistUrl" in ch && configured) imports++ }
                githubStatus = if (quiet) githubStatus?.takeIf { !it.startsWith("Fout") } else if (ch.isEmpty()) "Alles is gelijk" else "Bijgewerkt: ${ch.size} onderdelen"
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { githubStatus = "Fout: ${e.message ?: e.javaClass.simpleName}"
            } finally { githubMutex.unlock(); githubVersion++ }
        }
    }

    /** Voor het afsluiten: nog even wegschrijven (hooguit een paar seconden). */
    suspend fun githubFlush() {
        if (!github.connected) return
        kotlinx.coroutines.withTimeoutOrNull(6_000) { githubMutex.withLock { withContext(Dispatchers.IO) { runCatching { github.syncOnce() } } } }
    }

    private fun stopForPeer(name: String): Pair<String, Long>? {
        val p = playing ?: return null
        val r = p.first to (try { p.second() } catch (_: Exception) { 0L })
        playing = null
        stoppedBy = name
        return r
    }

    /** Vóór het afspelen: andere apparaten laten stoppen (je aanbieder staat één stream toe). */
    suspend fun claimPlayback(): List<Pair<String, Long>> =
        if (!sync.paired) emptyList() else withContext(Dispatchers.IO) { try { sync.claim() } catch (_: Exception) { emptyList() } }

    fun pairWith(address: String, code: String, takeOver: Boolean = true) {
        syncStatus = "Koppelen…"
        viewModelScope.launch {
            try {
                val (name, changed) = withContext(Dispatchers.IO) { sync.pair(address.trim().let { if (':' in it) it else "$it:8080" }, code.trim(), takeOver) }
                syncStatus = "Gekoppeld met $name"
                notice = if (takeOver) "✓ Gekoppeld met $name: instellingen overgenomen" else "✓ Gekoppeld met $name: instellingen daarheen gestuurd"
                reloadFromStore(changed + "playlistUrl")
                imports++
                peersVersion++
            } catch (e: Exception) {
                syncStatus = "Koppelen mislukt: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }
    fun unpair() { sync.unpair(); syncStatus = "Ontkoppeld"; peersVersion++ }
    fun syncNow() {
        syncStatus = "Synchroniseren…"
        viewModelScope.launch {
            val ch = withContext(Dispatchers.IO) { try { sync.syncAll() } catch (_: Exception) { emptySet() } }
            if (ch.isNotEmpty()) reloadFromStore(ch)
            syncStatus = if (ch.isEmpty()) "Alles is gelijk" else "Bijgewerkt: ${ch.size} onderdelen"
            peersVersion++
        }
    }

    /** Na ontvangen instellingen: schermstatus opnieuw uit de opslag lezen. */
    private fun reloadFromStore(changed: Set<String>) {
        favorites = store.favorites; hidden = store.hiddenGroups
        hiddenMovieCats = store.hiddenMovieCats; hiddenShowCats = store.hiddenShowCats
        subStyle = store.subStyle; epgOffsetMinutes = store.epgOffsetMinutes; lockOn = store.lockOn
        vodFavs = store.vodFavs; reminders = store.reminders
        if (("playlistUrl" in changed || "epgUrl" in changed) && store.playlistUrl.isNotBlank() && (allChannels.isEmpty() || "playlistUrl" in changed && store.playlistUrl != loadedUrl))
            saveSource(store.playlistUrl, store.epgUrl)
        else if ("epgLinks" in changed) viewModelScope.launch { epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }; refreshNowNext(); dataVersion++ }
        applyHidden()
    }
    private var loadedUrl = store.playlistUrl
    private val offset get() = epgOffsetMinutes * MINUTE


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
                            step(0) { it.copy(state = 1, note = "Downloaden") }
                            progress = LoadProgress("Playlist downloaden")
                            val tmp = File(cacheFile.path + ".tmp")
                            Net.open(store.playlistUrl) { d, t -> progress = LoadProgress("Playlist downloaden", d, t); step(0) { it.copy(state = 1, done = d, total = t) } }.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                            tmp.renameTo(cacheFile); downloaded = true
                            store.playlistUpdated = System.currentTimeMillis()
                            step(0) { it.copy(state = 2, note = "Vernieuwd") }
                        } else { progress = LoadProgress("Opgeslagen playlist openen"); step(0) { it.copy(state = 2, note = "Opgeslagen versie van ${Format.dayTime(store.playlistUpdated)}") } }
                        progress = LoadProgress("Zenders verwerken")
                        step(1) { it.copy(state = 1) }
                        cacheFile.useLines { M3u.parse(it, xt != null) }
                    } catch (e: Exception) {
                        error = "Playlist laden mislukt: ${e.message ?: e.javaClass.simpleName}"
                        if (cacheFile.exists()) { error += " (opgeslagen versie gebruikt)"; cacheFile.useLines { M3u.parse(it, xt != null) } } else null
                    }
                }
                if (pl == null) { progress = null; step(0) { it.copy(state = 4, note = error) }; startupDone = true; return@withLock }
                step(1) { it.copy(state = 2, note = "${pl.channels.size} zenders") }
                // terugkijken (Xtream): alleen opnieuw vragen als de playlist vers is
                var chans = pl.channels
                if (xt != null) {
                    val (arch, off) = withContext(Dispatchers.IO) {
                        val cached = try { JSONObject(store.archiveJson) } catch (_: Exception) { JSONObject() }
                        if (!downloaded && cached.has("archive")) {
                            step(2) { it.copy(state = 2, note = "Opgeslagen") }
                            val a = cached.getJSONObject("archive"); a.keys().asSequence().associateWith { a.getInt(it) } to cached.optLong("offset")
                        } else try {
                            progress = LoadProgress("Terugkijk-informatie ophalen")
                            step(2) { it.copy(state = 1) }
                            val r = xt.archiveInfo()
                            step(2) { it.copy(state = 2, note = "${r.first.size} zenders met terugkijken") }
                            store.archiveJson = JSONObject().put("archive", JSONObject(r.first as Map<*, *>)).put("offset", r.second).toString()
                            r
                        } catch (_: Exception) { step(2) { it.copy(state = 4, note = "Niet gelukt") }; emptyMap<String, Int>() to 0L }
                    }
                    serverOffset = off
                    chans = chans.map { c -> c.streamId?.let { sid -> arch[sid] }?.let { c.copy(archiveDays = it) } ?: c.copy(archiveDays = if (c.catchup != null) c.archiveDays else 0) }
                }
                if (xt == null) step(2) { it.copy(state = if (pl.channels.any { c -> c.catchup != null }) 2 else 3) }
                playlistEpgUrl = pl.epgUrl
                // Zenders meteen tonen; de gids wordt daarna gekoppeld.
                allChannels = chans
                allGroups = chans.map { it.group }.distinct()
                val newVod: VodSource? = xt ?: pl.vod.takeIf { it.isNotEmpty() }?.let { M3uVodSource(it) }
                if (newVod?.javaClass != vod?.javaClass || downloaded) { indexJob?.cancel(); vodIndexReady = false }
                vod = newVod; vodCache.clear(); archCache.clear()
                applyHidden()
                progress = LoadProgress("Zenders aan de gids koppelen")
                epgMatch = try { withContext(Dispatchers.Default) { epg.match(chans, store.epgLinks) } } catch (e: Exception) { status = "Gids koppelen mislukt: ${e.message}"; emptyMap() }
                dataVersion++
                progress = null
                refreshNowNext()
                ensureVodIndex(force = downloaded && indexFile.exists() && System.currentTimeMillis() - indexFile.lastModified() > DAY)
                if (forceEpg || epgDue(startup)) refreshEpgLocked()
                else step(3) { it.copy(state = 2, note = epgStatsText()) }
                // laadscherm: ook wachten op de zoeklijst van films en series
                if (!startupDone) {
                    if (vod == null) step(4) { it.copy(state = 3) }
                    else {
                        step(4) { it.copy(state = 1) }
                        indexJob?.join()
                        step(4) { it.copy(state = if (vodIndexReady) 2 else 4, note = if (vodIndexReady) "${movieIndex.size} films · ${showIndex.size} series" else vodIndexStatus) }
                    }
                    delay(900)   // even tijd voor het logo om "klaar" te laten zien
                    startupDone = true
                }
            }
        }
    }

    fun refreshEpg() { viewModelScope.launch { loadLock.withLock { refreshEpgLocked() } } }

    private suspend fun refreshEpgLocked() {
        val urls = (store.epgUrl.ifBlank { playlistEpgUrl ?: xtream?.xmltvUrl() ?: "" }).split(',').map { it.trim() }.filter { it.startsWith("http") }
        if (urls.isEmpty()) { status = "Geen gids-link. Vul er een in bij Instellingen › Playlist."; step(3) { it.copy(state = 3, note = "Geen gids-link") }; return }
        epgBusy = true
        step(3) { it.copy(state = 1) }
        try {
            withContext(Dispatchers.IO) {
                var label = "Gids downloaden"
                epg.refresh(urls) { msg, d, t ->
                    if (msg != null) label = msg.trimEnd('…')
                    progress = if (d >= 0) LoadProgress(label, d, t) else LoadProgress(label, progress?.done ?: 0, progress?.total ?: 0)
                    step(3) { it.copy(state = 1, note = label, done = if (d >= 0) d else it.done, total = if (d >= 0) t else it.total) }
                }
            }
            status = null
        } catch (e: Exception) {
            status = "Gids bijwerken mislukt: ${e.message ?: e.javaClass.simpleName}"
            step(3) { it.copy(state = 4, note = status) }
        } finally { epgBusy = false; progress = null }
        epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }
        refreshNowNext()
        dataVersion++
        if (steps[3].state == 1) step(3) { it.copy(state = 2, note = epgStatsText()) }
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

    var vodFavs by mutableStateOf(store.vodFavs); private set
    fun isFav(m: Movie) = vodFavs.any { it.kind == "movie" && it.id == m.id }
    fun isFav(s: Show) = vodFavs.any { it.kind == "show" && it.id == s.id }
    fun toggleFav(m: Movie) = toggleVod(Recent("movie", m.id, m.name, m.poster, m.categoryId, m.url))
    fun toggleFav(s: Show) { toggleVod(Recent("show", s.id, s.name, s.poster, s.categoryId, null)); if (isFav(s)) markSeen(s) }

    private fun showUpdated(s: Show): Long? = s.updated ?: showIndex.firstOrNull { it.id == s.id }?.updated
    /** Favoriete serie met een nieuwe aflevering sinds je hem voor het laatst opende. */
    fun isNew(s: Show): Boolean {
        if (!isFav(s)) return false
        val u = showUpdated(s) ?: return false
        val seen = store.seenShow(s.id)
        return seen in 1 until u
    }
    fun markSeen(s: Show) { showUpdated(s)?.let { store.setSeenShow(s.id, it) } }
    private fun baselineFavShows() {
        vodFavs.filter { it.kind == "show" && store.seenShow(it.id) == 0L }.forEach { r -> showIndex.firstOrNull { it.id == r.id }?.updated?.let { store.setSeenShow(r.id, it) } }
    }

    // ---- herinneringen
    var reminders by mutableStateOf(store.reminders); private set
    var dueReminder by mutableStateOf<nl.kijk.tv.data.Reminder?>(null); private set
    fun hasReminder(ch: Channel, p: Programme) = reminders.any { it.channelKey == ch.key && it.start == p.start }
    fun toggleReminder(ch: Channel, p: Programme) {
        reminders = if (hasReminder(ch, p)) reminders.filter { !(it.channelKey == ch.key && it.start == p.start) }
        else reminders + nl.kijk.tv.data.Reminder(ch.key, ch.name, p.start, p.stop, p.title)
        store.reminders = reminders
        notice = if (hasReminder(ch, p)) "🔔 Herinnering gezet: ${p.title} om ${Format.time(p.start)}" else "Herinnering verwijderd"
    }
    fun dismissReminder() { dueReminder = null }
    private fun checkReminders() {
        val t = System.currentTimeMillis()
        val due = reminders.firstOrNull { it.start - 30_000 <= t && t < it.stop }
        val keep = reminders.filter { it.stop > t && it != due }
        if (due != null && dueReminder == null) dueReminder = due
        if (keep.size != reminders.size) { reminders = keep; store.reminders = keep }
    }

    // ---- bijwerken vanuit de app (GitHub-releases)
    data class UpdateInfo(val build: Int, val url: String)
    var update by mutableStateOf<UpdateInfo?>(null); private set
    var updateStatus by mutableStateOf<String?>(null); private set
    private var updating = false
    fun checkUpdate(manual: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (manual) updateStatus = "Controleren…"
            try {
                val j = JSONObject(Net.text("https://api.github.com/repos/Tim-Brass/Kijk/releases/latest"))
                val n = j.optString("tag_name").removePrefix("build-").toIntOrNull() ?: 0
                val assets = j.optJSONArray("assets")
                val url = (0 until (assets?.length() ?: 0)).map { assets!!.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url")
                if (n > BuildConfig.VERSION_CODE && url != null) { update = UpdateInfo(n, url); updateStatus = "Versie 0.1.$n staat klaar · OK om bij te werken" }
                else if (manual) updateStatus = "Je hebt de nieuwste versie (0.1.${BuildConfig.VERSION_CODE})"
            } catch (e: Exception) { if (manual) updateStatus = "Controleren mislukt: ${e.message ?: e.javaClass.simpleName}" }
        }
    }
    fun installUpdate(ctx: android.content.Context) {
        val u = update ?: return checkUpdate(manual = true)
        if (updating) return
        val pm = ctx.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            updateStatus = "Sta eerst toe dat Kijk apps mag installeren, kies daarna opnieuw Bijwerken"
            notice = updateStatus
            try {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { notice = "Zet bij de tv-instellingen 'Onbekende apps installeren' aan voor Kijk" }
            return
        }
        updating = true
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
                    val out = File(dir, "Kijk.apk")
                    Net.open(u.url) { d, t -> updateStatus = if (t > 0) "Downloaden ${d * 100 / t}%" else "Downloaden…"; notice = updateStatus }
                        .use { input -> out.outputStream().use { input.copyTo(it) } }
                    out
                }
                updateStatus = "Installeren…"; notice = null
                val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                updateStatus = "Bijwerken mislukt: ${e.message ?: e.javaClass.simpleName}"; notice = "✕ $updateStatus"
            } finally { updating = false }
        }
    }
    private fun toggleVod(r: Recent) {
        val has = vodFavs.any { it.kind == r.kind && it.id == r.id }
        vodFavs = if (has) vodFavs.filter { !(it.kind == r.kind && it.id == r.id) } else listOf(r) + vodFavs
        store.vodFavs = vodFavs
    }
    fun favMovies(): List<Movie> = vodFavs.filter { it.kind == "movie" }.map { Movie(it.id, it.name, it.poster, it.categoryId, it.url ?: "", null, null) }
    fun favShows(): List<Show> = vodFavs.filter { it.kind == "show" }.map { r -> Show(r.id, r.name, r.poster, r.categoryId, null, null, null, showIndex.firstOrNull { it.id == r.id }?.updated) }

    fun toggleFavorite(ch: Channel) {
        favorites = if (ch.key in favorites) favorites - ch.key else favorites + ch.key
        store.favorites = favorites
    }

    fun watched(ch: Channel) {
        store.lastChannel = ch.key; store.addRecentChannel(ch.key); recentChannels = store.recentChannels
    }

    fun updateHiddenGroups(v: Set<String>) { hidden = v; store.hiddenGroups = v; applyHidden() }
    fun updateHiddenMovieCats(v: Set<String>) { hiddenMovieCats = v; store.hiddenMovieCats = v }
    fun updateHiddenShowCats(v: Set<String>) { hiddenShowCats = v; store.hiddenShowCats = v }

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
        return SearchResult(chans, progs, emptyList(), emptyList())
    }

    // ---- zoekindex voor films en series: één keer per dag ophalen en op de tv bewaren, zodat zoeken direct gaat
    var vodIndexReady by mutableStateOf(false); private set
    var vodIndexStatus by mutableStateOf<String?>(null); private set
    private var movieIndex: List<Movie> = emptyList()
    private var showIndex: List<Show> = emptyList()
    private var indexJob: kotlinx.coroutines.Job? = null
    private val indexFile get() = File(getApplication<Application>().filesDir, "vod_index.tsv")

    fun ensureVodIndex(force: Boolean = false) {
        val src = vod ?: return
        if (indexJob?.isActive == true || (vodIndexReady && !force)) return
        indexJob = viewModelScope.launch(Dispatchers.IO) {
            val f = indexFile
            val tag = "v3:" + store.playlistUrl.hashCode()
            try {
                val fresh = !force && f.exists() && System.currentTimeMillis() - f.lastModified() < DAY
                if (fresh && readIndex(f, tag)) { vodIndexReady = true; baselineFavShows(); return@launch }
                vodIndexStatus = "Films ophalen voor zoeken…"
                step(4) { it.copy(note = "Films ophalen") }
                val m = src.movies(null)
                vodIndexStatus = "Series ophalen voor zoeken…"
                step(4) { it.copy(note = "Series ophalen") }
                val sh = src.shows(null)
                movieIndex = m; showIndex = sh.map { it.copy(plot = null) }
                vodIndexReady = true; vodIndexStatus = null
                baselineFavShows()
                writeIndex(f, tag)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Throwable) {
                if (f.exists() && readIndex(f, tag)) vodIndexReady = true   // oude index is beter dan niets
                vodIndexStatus = if (vodIndexReady) null else "Films en series zoeken lukt nu niet (${e.javaClass.simpleName})"
            }
        }
    }

    private fun clean(t: String?) = (t ?: "").replace('\t', ' ').replace('\n', ' ')
    private fun writeIndex(f: File, tag: String) {
        val tmp = File(f.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("#$tag\n")
            movieIndex.forEach { w.write("m\t${clean(it.id)}\t${clean(it.name)}\t${clean(it.poster)}\t${clean(it.categoryId)}\t${clean(it.url)}\t${clean(it.year)}\t${it.added ?: ""}\n") }
            showIndex.forEach { w.write("s\t${clean(it.id)}\t${clean(it.name)}\t${clean(it.poster)}\t${clean(it.categoryId)}\t\t${clean(it.year)}\t${it.updated ?: ""}\n") }
        }
        tmp.renameTo(f)
    }
    private fun readIndex(f: File, tag: String): Boolean = try {
        val m = ArrayList<Movie>(); val sh = ArrayList<Show>()
        var ok = false
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) { ok = line.drop(1) == tag; if (!ok) break; continue }
                val c = line.split('\t')
                if (c.size < 7) continue
                val poster = c[3].ifEmpty { null }; val year = c[6].ifEmpty { null }
                if (c[0] == "m") m += Movie(c[1], c[2], poster, c[4], c[5], null, year, c.getOrNull(7)?.toLongOrNull())
                else sh += Show(c[1], c[2], poster, c[4], null, null, year, c.getOrNull(7)?.toLongOrNull())
            }
        }
        if (ok) { movieIndex = m; showIndex = sh }
        ok
    } catch (_: Exception) { false }

    /** "Nieuw binnen" op Thuis: laatst toegevoegde films en series (zonder verborgen/vergrendelde). */
    suspend fun newestVod(limit: Int = 18): List<Any> {
        if (!vodIndexReady) return emptyList()
        val mCats = (try { movieCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        val sCats = (try { showCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        return withContext(Dispatchers.Default) {
            val ms = movieIndex.filter { it.categoryId !in hiddenMovieCats && (unlocked || (!isLocked(mCats[it.categoryId]) && !isLocked(it.name))) }
            val ss = showIndex.filter { it.categoryId !in hiddenShowCats && (unlocked || (!isLocked(sCats[it.categoryId]) && !isLocked(it.name))) }
            // zonder datum (M3U): de laatste regels van de lijst zijn meestal het nieuwst
            val mTop: List<Pair<Long, Any>> = if (ms.any { it.added != null }) ms.sortedByDescending { it.added ?: 0 }.take(limit).map { (it.added ?: 0L) to it }
                else ms.takeLast(limit).reversed().mapIndexed { i, m -> (Long.MAX_VALUE - i * 2) to m }
            val sTop: List<Pair<Long, Any>> = if (ss.any { it.updated != null }) ss.sortedByDescending { it.updated ?: 0 }.take(limit).map { (it.updated ?: 0L) to it }
                else ss.takeLast(limit).reversed().mapIndexed { i, x -> (Long.MAX_VALUE - i * 2 - 1) to x }
            (mTop + sTop).sortedByDescending { it.first }.take(limit).map { it.second }
        }
    }

    // ---------------------------------------------------------------- Thuis: weer en nieuws
    var weather by mutableStateOf<Weather?>(null); private set
    var news by mutableStateOf<News?>(null); private set
    private var homeJob: Job? = null
    /** Weer en nieuws ophalen; hooguit eens per half uur. */
    fun refreshHome() {
        if (homeJob?.isActive == true) return
        val w = weather; val n = news
        val stale = 30 * MINUTE
        val t = System.currentTimeMillis()
        if (w != null && n != null && t - w.fetched < stale && t - n.fetched < stale) return
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            if (w == null || t - w.fetched >= stale) runCatching { weather = HomeInfo.weather() }
            if (n == null || t - n.fetched >= stale) runCatching { news = HomeInfo.news() }
        }
    }

    /** Films en series zoeken in de index; null = index nog niet klaar. */
    suspend fun searchVod(q0: String): Pair<List<Movie>, List<Show>>? {
        if (!vodIndexReady) { ensureVodIndex(); return null }
        val q = q0.trim().lowercase()
        if (q.length < 2) return emptyList<Movie>() to emptyList()
        fun rank(n: String) = n.lowercase().let { if (it.startsWith(q)) 0 else if (" $q" in it) 1 else 2 }
        val mCats = (try { movieCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        val sCats = (try { showCategories() } catch (_: Exception) { emptyList() }).associate { it.id to it.name }
        return withContext(Dispatchers.Default) {
            val movies = movieIndex.filter { q in it.name.lowercase() && it.categoryId !in hiddenMovieCats && (unlocked || (!isLocked(mCats[it.categoryId]) && !isLocked(it.name))) }
                .sortedBy { rank(it.name) }.take(60)
            val shows = showIndex.filter { q in it.name.lowercase() && it.categoryId !in hiddenShowCats && (unlocked || (!isLocked(sCats[it.categoryId]) && !isLocked(it.name))) }
                .sortedBy { rank(it.name) }.take(60)
            movies to shows
        }
    }

    // ---------------------------------------------------------------- bron en import
    fun saveSource(playlistUrl: String, epgUrl: String) {
        loadedUrl = playlistUrl
        store.playlistUrl = playlistUrl; store.epgUrl = epgUrl; store.playlistUpdated = 0L; store.archiveJson = "{}"
        allChannels = emptyList(); channels = emptyList(); cacheFile.delete()
        load(forcePlaylist = true, forceEpg = true)
    }

    /** Instellingen van de telefoon of de webversie overnemen. */
    /** Melding bovenin beeld (bijv. "instellingen ontvangen"); verdwijnt vanzelf. */
    var notice by mutableStateOf<String?>(null)
    /** Telt elke ontvangen set instellingen, zodat het scherm kan reageren. */
    var imports by mutableStateOf(0); private set

    fun importSettings(json: JSONObject) {
        val oldUrl = store.playlistUrl
        try { store.import(json) } catch (e: Exception) { notice = "✕ Instellingen ontvangen, maar niet te lezen: ${e.message}"; return }
        val what = listOfNotNull(
            "playlist".takeIf { json.has("playlist_url") }, "gids-link".takeIf { json.has("epg_url") && json.optString("epg_url").isNotBlank() },
            "favorieten".takeIf { json.has("favs") }, "OpenSubtitles".takeIf { json.has("os_key") || json.has("os_user") },
        )
        notice = "✓ Instellingen ontvangen" + if (what.isNotEmpty()) ": " + what.joinToString(", ") else ""
        imports++
        favorites = store.favorites; hidden = store.hiddenGroups; hiddenMovieCats = store.hiddenMovieCats; hiddenShowCats = store.hiddenShowCats
        subStyle = store.subStyle; epgOffsetMinutes = store.epgOffsetMinutes; lockOn = store.lockOn; unlocked = false
        if (store.playlistUrl.isNotBlank() && (store.playlistUrl != oldUrl || allChannels.isEmpty())) saveSource(store.playlistUrl, store.epgUrl)
        else if (allChannels.isNotEmpty()) { applyHidden(); viewModelScope.launch { epgMatch = withContext(Dispatchers.IO) { epg.match(allChannels, store.epgLinks) }; dataVersion++ } }
    }

    fun clearHistory(kind: String) { store.clearHistory(kind); recentChannels = store.recentChannels; dataVersion++ }

    companion object {
        val ADULT = Regex("""(adult|xxx|18\s*\+|\+\s*18|porn|erotic|erotiek|volwassen)""", RegexOption.IGNORE_CASE)
    }

    // Pas hier, ná alle velden: Kotlin initialiseert in volgorde, en de lussen hieronder gebruiken velden die
    // verderop in de klasse staan (anders crasht de app bij het opstarten).
    init {
        viewModelScope.launch { while (true) { now = System.currentTimeMillis(); checkReminders(); delay(15_000) } }
        viewModelScope.launch { while (true) { delay(60_000); refreshNowNext() } }
        viewModelScope.launch {
            while (true) {
                delay(30 * 60_000L)
                if (!configured) continue
                if (playlistDue(false)) load() else if (epgDue(false)) refreshEpg()
            }
        }
        if (configured) load(startup = true)
        startSync()
    }
}
