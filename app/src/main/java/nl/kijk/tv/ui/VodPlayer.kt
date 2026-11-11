@file:OptIn(ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.kijk.tv.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Playable
import nl.kijk.tv.data.SubsQuery
import java.io.File

/** Een ondertitel van OpenSubtitles die bij deze film/aflevering is geladen. */
private data class ExtSub(val fileId: Long, val file: File, val offset: Double, val lang: String)

private val BUTTONS = listOf("play", "subs", "audio", "next")

/**
 * Speler voor films, afleveringen en terugkijken. Tijdlijn: ◀ ▶ spoelen (stapjes worden groter als je blijft drukken),
 * OK of even wachten = daarheen springen. ▼ naar de knoppen: afspelen/pauze, 💬 ondertitels, audio, volgende.
 */
@Composable
fun VodPlayer(vm: AppViewModel, nav: Nav, items: List<Playable>, startIndex: Int, startPosition: Long, showId: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember { buildPlayer(ctx) }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, items.lastIndex)) }
    val item = items[index]
    val currentItem by rememberUpdatedState(item)
    val catchup = item.isCatchup
    val prog = item.catchupProgramme
    var baseOffset by remember { mutableLongStateOf(item.offsetMs) }   // terugkijken: waar in het programma de stream begint
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var controls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var row by remember { mutableIntStateOf(0) }         // 0 = tijdlijn, 1 = knoppen
    var button by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<String?>(null) }   // "subs" of "audio"
    var scrub by remember { mutableStateOf<Long?>(null) }
    var scrubTick by remember { mutableIntStateOf(0) }
    var scrubRepeat by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableStateOf<Int?>(null) }
    var ext by remember { mutableStateOf<ExtSub?>(null) }
    var subMsg by remember { mutableStateOf<String?>(null) }
    var autoTried by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val hasNext = index < items.lastIndex && !catchup
    val progLen = prog?.let { it.stop - it.start } ?: 0L

    fun absPos() = if (catchup) baseOffset + player.currentPosition else player.currentPosition
    fun total() = if (catchup) progLen else player.duration.coerceAtLeast(0L)
    fun save() {
        if (catchup) return
        val d = player.duration
        if (d > 0) vm.store.saveProgress(currentItem.key, player.currentPosition.coerceAtMost(d), d)
    }
    fun poke() { controls = true; tick++ }

    /** Media (opnieuw) laden, met de gekozen OpenSubtitles-ondertitel erbij. */
    fun load(startMs: Long) {
        val cur = currentItem
        val cp = cur.catchupProgramme
        val url = if (cur.isCatchup && cp != null) vm.catchupUrl(cur.catchupChannel!!, cp.start + startMs, cp.stop) else cur.url
        val e = ext
        val mi = mediaItem(url) {
            if (e != null) setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(e.file)).setMimeType(MimeTypes.TEXT_VTT).setLanguage(e.lang)
                    .setId("os:${e.fileId}").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build(),
            ))
        }
        error = null; ended = false
        if (cur.isCatchup) { baseOffset = startMs; player.setMediaItem(mi) } else player.setMediaItem(mi, startMs)
        player.prepare(); player.playWhenReady = true
    }

    fun seekTo(ms: Long) {
        val t = total()
        val target = ms.coerceAtLeast(0L).let { if (t > 0) it.coerceAtMost(t - 2000) else it }
        if (catchup) load(target) else player.seekTo(target)
        position = target; poke()
    }

    /** OpenSubtitles-ondertitel laden (met verschuiving in seconden) en de film op dezelfde plek verder laten spelen. */
    fun useExt(fileId: Long, offset: Double, lang: String) {
        val key = currentItem.key
        subMsg = "Ondertitel laden…"
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { vm.subs.get(fileId).let { if (offset != 0.0) vm.subs.shifted(it, offset) else it } }
                val pos = absPos()
                ext = ExtSub(fileId, f, offset, lang)
                vm.store.setSubChoice(key, fileId, offset)
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages(lang).build()
                load(pos)
                subMsg = if (offset != 0.0) "Ondertitels %+.1f s".format(offset) else null
            } catch (e: Exception) { subMsg = "Ondertitel laden mislukt: ${e.message ?: e.javaClass.simpleName}" }
        }
    }

    fun go(newIndex: Int) {
        if (newIndex !in items.indices || newIndex == index) return
        save(); countdown = null; ext = null; autoTried = false; index = newIndex
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) error = null
            }
            override fun onPlayerError(e: PlaybackException) { error = "Afspelen mislukt (${e.errorCodeName})" }
        }
        player.addListener(l)
        onDispose { save(); player.removeListener(l); player.release() }
    }
    LaunchedEffect(vm.subStyle) { if (ext == null) applyPreferences(player, vm.subStyle) }

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(index) {
        val start = if (first) startPosition else vm.store.progress(item.key)?.takeIf { it.resumable }?.pos ?: 0L
        first = false
        showId?.let { vm.store.setLastEpisode(it, item.key) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        applyPreferences(player, vm.subStyle)
        load(if (catchup) item.offsetMs + start else start)
        poke()
    }

    // Automatisch ondertitels: eerder gekozen bestand, of zoeken als de film geen eigen ondertitels in je taal heeft.
    LaunchedEffect(tracks, index) {
        if (autoTried || ext != null || tracks.groups.isEmpty() || item.isCatchup) return@LaunchedEffect
        autoTried = true
        val lang = vm.subStyle.language
        if (lang == "off" || !vm.subs.configured) return@LaunchedEffect
        vm.store.subChoice(item.key)?.let { (id, off) -> useExt(id, off, if (lang == "en") "en" else "nl"); return@LaunchedEffect }
        if (!vm.store.autoSubs) return@LaunchedEffect
        val want = if (lang == "en") listOf("en", "eng") else listOf("nl", "nld", "dut")
        val own = tracks.groups.any { g -> g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { g.getTrackFormat(it).language?.lowercase() in want } }
        if (own) return@LaunchedEffect
        val langs = when (lang) { "en" -> listOf("en"); "auto" -> listOf("nl", "en"); else -> listOf("nl") }
        for (l in langs) {
            val r = try { withContext(Dispatchers.IO) { vm.subs.search(item.subs ?: SubsQuery(query = item.title), l) } } catch (_: Exception) { emptyList() }
            r.firstOrNull()?.let { useExt(it.fileId, 0.0, l); subMsg = "Ondertitels van OpenSubtitles (${if (l == "nl") "Nederlands" else "Engels"})"; return@LaunchedEffect }
        }
    }
    LaunchedEffect(subMsg) { if (subMsg != null && !subMsg!!.endsWith("…")) { delay(4000); subMsg = null } }

    // Positie bijhouden en elke 10 seconden opslaan.
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            position = absPos(); duration = total()
            if (++n % 20 == 0 && playing) save()
            delay(500)
        }
    }
    // Einde: markeer als bekeken en ga na 8 seconden door naar de volgende aflevering.
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        val d = player.duration
        if (d > 0 && !catchup) vm.store.saveProgress(currentItem.key, d, d)
        if (!hasNext) { nav.pop(); return@LaunchedEffect }
        for (s in 8 downTo 1) { countdown = s; delay(1000) }
        countdown = null; go(index + 1)
    }
    LaunchedEffect(controls, tick, playing, scrub) { if (controls && playing && scrub == null) { delay(5000); controls = false; row = 0 } }
    LaunchedEffect(scrub, scrubTick) { val s = scrub ?: return@LaunchedEffect; delay(1400); seekTo(s); scrub = null; scrubRepeat = 0 }
    LaunchedEffect(panel) { if (panel == null) runCatching { focus.requestFocus() } }

    fun scrubBy(dir: Int, repeat: Int) {
        val step = when { repeat > 24 -> 300_000L; repeat > 12 -> 60_000L; repeat > 4 -> 30_000L; else -> 10_000L }
        val t = total()
        scrub = ((scrub ?: absPos()) + dir * step).coerceAtLeast(0L).let { if (t > 0) it.coerceAtMost(t - 2000) else it }
        scrubTick++; poke()
    }
    fun press(b: String) {
        when (b) {
            "play" -> if (player.isPlaying) player.pause() else player.play()
            "subs" -> { panel = "subs"; controls = false }
            "audio" -> { panel = "audio"; controls = false }
            "next" -> go(index + 1)
        }
        poke()
    }
    val buttons = BUTTONS.filter { it != "next" || hasNext }

    BackHandler {
        when {
            panel != null -> panel = null
            scrub != null -> { scrub = null; scrubRepeat = 0 }
            countdown != null -> { countdown = null; nav.pop() }
            controls && playing -> { controls = false; row = 0 }
            else -> nav.pop()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || panel != null) return@onKeyEvent false
                val repeat = e.nativeKeyEvent.repeatCount
                when (e.key) {
                    Key.MediaPlayPause -> { press("play"); true }
                    Key.MediaPlay -> { player.play(); poke(); true }
                    Key.MediaPause -> { player.pause(); poke(); true }
                    Key.MediaRewind -> { scrubBy(-1, 13); true }
                    Key.MediaFastForward -> { scrubBy(1, 13); true }
                    Key.MediaNext -> { go(index + 1); true }
                    Key.MediaPrevious -> { go(index - 1); true }
                    Key.Captions -> { panel = "subs"; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            countdown != null -> go(index + 1)
                            scrub != null -> { seekTo(scrub!!); scrub = null; scrubRepeat = 0 }
                            !controls -> poke()
                            row == 1 -> press(buttons[button.coerceIn(0, buttons.lastIndex)])
                            else -> press("play")
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val d = if (e.key == Key.DirectionLeft) -1 else 1
                        if (controls && row == 1) { button = (button + d).coerceIn(0, buttons.lastIndex); poke() }
                        else { scrubRepeat = if (repeat > 0) scrubRepeat + 1 else scrubRepeat + (if (scrub != null) 1 else 0); scrubBy(d, scrubRepeat) }
                        true
                    }
                    Key.DirectionDown -> { if (controls && scrub == null) row = 1; poke(); true }
                    Key.DirectionUp -> { if (controls && row == 1) row = 0 else if (!controls) poke() else { panel = "subs"; controls = false }; poke(); true }
                    Key.Menu -> { panel = "subs"; controls = false; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(player, vm.subStyle, controls)

        if ((buffering && !playing) || error != null) StatusBubble(error ?: "Laden…", Modifier.align(Alignment.Center))
        else if (!playing && countdown == null && !ended && scrub == null) {
            Box(Modifier.align(Alignment.Center).size(96.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
                Label("❚❚", size = 34.sp, weight = Kijk.Bold, color = Color.White)
            }
        }
        subMsg?.let { StatusBubble(it, Modifier.align(Alignment.TopCenter).padding(top = Kijk.SafeV)) }

        countdown?.let { s ->
            Box(Modifier.align(Alignment.BottomEnd).padding(end = Kijk.SafeH, bottom = Kijk.SafeV + 40.dp)
                .background(Kijk.Focused, RoundedCornerShape(Kijk.Corner)).padding(horizontal = 22.dp, vertical = 14.dp)) {
                Column {
                    Label("Volgende aflevering over $s s", size = Kijk.Headline, weight = Kijk.Semibold, color = Color.Black)
                    items.getOrNull(index + 1)?.subtitle?.let { Label(it, size = Kijk.Body, color = Kijk.SecondaryOnFocus) }
                    Label("OK = nu afspelen · Terug = stoppen", size = Kijk.Caption, color = Kijk.SecondaryOnFocus)
                }
            }
        }

        AnimatedVisibility((controls || scrub != null) && countdown == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                .padding(start = Kijk.SafeH, end = Kijk.SafeH, bottom = Kijk.SafeV, top = 60.dp)) {
                Column {
                    Label(item.title, size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                    item.subtitle?.let { Label(it, size = Kijk.Body, color = Color(0xFFD1D1D6)) }
                    Spacer(Modifier.height(14.dp))
                    Timeline(position, duration, scrub, row == 0)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        val left = scrub ?: position
                        Label(if (catchup && prog != null) Format.time(prog.start + left) else Format.clock(left), size = Kijk.Caption, color = if (scrub != null) Color.White else Kijk.Secondary)
                        Spacer(Modifier.weight(1f))
                        Label(if (row == 0) "◀ ▶ spoelen (vasthouden = sneller) · OK afspelen/pauze · ▼ knoppen" else "◀ ▶ kiezen · OK · ▲ tijdlijn", size = Kijk.Caption, color = Kijk.Secondary)
                        Spacer(Modifier.weight(1f))
                        Label(when {
                            duration <= 0 -> ""
                            catchup && prog != null -> Format.time(prog.stop)
                            else -> "-" + Format.clock(duration - (scrub ?: position))
                        }, size = Kijk.Caption, color = Kijk.Secondary)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        buttons.forEachIndexed { i, b ->
                            val sel = row == 1 && i == button
                            val label = when (b) {
                                "play" -> if (playing) "❚❚  Pauze" else "▶  Afspelen"
                                "subs" -> "💬  Ondertitels" + (if (ext != null) " · OS" else "")
                                "audio" -> "🔊  Audio"
                                else -> "⏭  Volgende"
                            }
                            Box(Modifier.background(if (sel) Kijk.Focused else Color(0x33FFFFFF), RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 9.dp)) {
                                Label(label, size = Kijk.Body, weight = Kijk.Semibold, color = if (sel) Color.Black else Color.White)
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(panel != null, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            var refresh by remember { mutableIntStateOf(0) }
            val subs = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            val audio = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
            val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || subs.none { it.selected }
            if (panel == "audio") SidePanel { first ->
                item(key = "h") { PanelHeader("Audio") }
                if (audio.isEmpty()) item(key = "none") { Label("Geen audiosporen gevonden.", Modifier.padding(6.dp), color = Kijk.Secondary) }
                items(audio, key = { "a" + it.label + it.index + refresh }) { o ->
                    OptionRow(o.label, o.selected, if (o == audio.first()) Modifier.focusRequester(first) else Modifier) { selectTrack(player, C.TRACK_TYPE_AUDIO, o); refresh++ }
                }
            } else SidePanel { first ->
                item(key = "h") { PanelHeader("Ondertitels") }
                item(key = "off$refresh") { OptionRow("Uit", subsOff, Modifier.focusRequester(first)) { selectTrack(player, C.TRACK_TYPE_TEXT, null); refresh++ } }
                items(subs, key = { "s" + it.label + it.index + refresh }) { o -> OptionRow(o.label, o.selected && !subsOff) { selectTrack(player, C.TRACK_TYPE_TEXT, o); refresh++ } }
                if (!catchup) item(key = "os") {
                    OptionRow("🔎  Zoeken bij OpenSubtitles…", false) {
                        panel = null
                        nav.push(Screen.SubSearch(item) { fileId -> useExt(fileId, 0.0, if (vm.subStyle.language == "en") "en" else "nl") })
                    }
                }
                val e = ext
                if (e != null) {
                    item(key = "h-sync") { PanelHeader("Synchroniseren") }
                    item(key = "sync-info") { Label("Loopt de tekst voor of achter? Verschuif hem in stapjes van een halve seconde. Nu: %+.1f s".format(e.offset), Modifier.padding(6.dp), size = Kijk.Caption, color = Kijk.Secondary, maxLines = 3) }
                    item(key = "sync-") { OptionRow("◀  Eerder (−0,5 s)", false) { useExt(e.fileId, e.offset - 0.5, e.lang) } }
                    item(key = "sync+") { OptionRow("▶  Later (+0,5 s)", false) { useExt(e.fileId, e.offset + 0.5, e.lang) } }
                    if (e.offset != 0.0) item(key = "sync0") { OptionRow("Terug naar 0", false) { useExt(e.fileId, 0.0, e.lang) } }
                }
                item(key = "h-style") { PanelHeader("Weergave") }
                subStyleRows(vm)
            }
        }
    }
}

@Composable
private fun Timeline(pos: Long, dur: Long, scrub: Long?, focused: Boolean) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.CenterStart) {
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        ProgressBar(frac, Modifier.fillMaxWidth(), height = if (focused) 7.dp else 5.dp)
        if (scrub != null && dur > 0) {
            val s = (scrub.toFloat() / dur).coerceIn(0f, 1f)
            Box(Modifier.offset(x = maxWidth * s - 8.dp).size(16.dp).background(Color.White, CircleShape).border(2.dp, Kijk.Accent, CircleShape))
        }
    }
}
