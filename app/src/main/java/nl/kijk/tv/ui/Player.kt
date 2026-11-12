@file:OptIn(ExperimentalTvMaterial3Api::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package nl.kijk.tv.ui

import android.content.Context
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.Net
import nl.kijk.tv.data.SubStyle

// ================================================================ gedeeld door beide spelers

data class TrackOption(val group: Tracks.Group?, val index: Int, val label: String, val selected: Boolean)

val SUB_SIZE_NAMES = listOf("Klein", "Normaal", "Groot", "Extra groot")
val SUB_BG_NAMES = listOf("Omlijnd", "Zwarte balk", "Half doorzichtig")

fun buildPlayer(ctx: Context, softAudio: Boolean = true): ExoPlayer {
    val http = DefaultHttpDataSource.Factory().setUserAgent(Net.USER_AGENT).setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000).setReadTimeoutMs(30_000)
    // IPTV-streams beginnen vaak midden in een GOP.
    val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
        DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
    )
    // Met softAudio gaat Dolby/DTS via FFmpeg in de app naar gewoon (PCM-)geluid, in plaats van doorgeven aan tv/soundbar.
    val renderers = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        .setExtensionRendererMode(if (softAudio) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
    // DefaultDataSource: naast http ook lokale bestanden (de gedownloade OpenSubtitles-ondertitels)
    val data = androidx.media3.datasource.DefaultDataSource.Factory(ctx, http)
    return ExoPlayer.Builder(ctx, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(data, extractors)).build()
}

fun mediaItem(url: String, start: MediaItem.Builder.() -> Unit = {}): MediaItem = MediaItem.Builder().setUri(url).apply {
    if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    start()
}.build()

fun applyPreferences(player: ExoPlayer, s: SubStyle) {
    val b = player.trackSelectionParameters.buildUpon().setPreferredAudioLanguages("nl", "nld", "dut")
    when (s.language) {
        "off" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        "auto" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setSelectUndeterminedTextLanguage(true).setPreferredTextLanguages("nl", "nld", "dut", "en", "eng")
        "en" -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("en", "eng")
        else -> b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages("nl", "nld", "dut")
    }
    player.trackSelectionParameters = b.build()
}

fun styleSubtitles(view: PlayerView, s: SubStyle, aboveInfo: Boolean) {
    val sv: SubtitleView = view.subtitleView ?: return
    sv.setApplyEmbeddedStyles(false)
    sv.setApplyEmbeddedFontSizes(false)
    sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.scale)
    val bg = when (s.background) { 1 -> 0xE6000000.toInt(); 2 -> 0x8C000000.toInt(); else -> android.graphics.Color.TRANSPARENT }
    val edge = if (s.background == 0) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
    sv.setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, bg, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
    // Als de balk onderin zichtbaar is, schuiven de ondertitels erboven.
    sv.setBottomPaddingFraction(if (aboveInfo) 0.36f else s.bottomFraction)
}

fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> {
    val out = ArrayList<TrackOption>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            val lang = Format.language(f.language)
            val kind = when (f.sampleMimeType) {
                MimeTypes.APPLICATION_DVBSUBS -> "DVB"
                MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
                MimeTypes.AUDIO_AC3 -> "Dolby Digital"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital+"
                MimeTypes.AUDIO_AAC -> "AAC"
                else -> null
            }
            val ch = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) (if (f.channelCount >= 6) "5.1" else if (f.channelCount == 2) "stereo" else "${f.channelCount} kan.") else null
            val name = if (f.id?.startsWith("os:") == true) "OpenSubtitles · ${lang ?: ""}" else (f.label ?: lang ?: "Spoor ${out.size + 1}")
            out += TrackOption(g, i, listOfNotNull(name, kind, ch).joinToString(" · "), g.isTrackSelected(i))
        }
    }
    return out
}

fun selectTrack(player: ExoPlayer, type: Int, opt: TrackOption?) {
    val b = player.trackSelectionParameters.buildUpon()
    val g = opt?.group
    if (opt == null || g == null) b.setTrackTypeDisabled(type, true)
    else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, opt.index))
    player.trackSelectionParameters = b.build()
}

@Composable
fun VideoSurface(player: ExoPlayer, style: SubStyle, aboveInfo: Boolean) {
    AndroidView(
        factory = { c ->
            PlayerView(c).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false; isFocusable = false; descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK); setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                this.player = player
            }
        },
        update = { view -> styleSubtitles(view, style, aboveInfo) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
fun StatusBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xB3000000), RoundedCornerShape(Kijk.Corner)).padding(horizontal = 22.dp, vertical = 12.dp)) {
        Label(text, size = Kijk.Headline, color = Color.White, maxLines = 2)
    }
}

/** Zijpaneel rechts (opties, ondertitels, audio). */
@Composable
fun SidePanel(refocus: Any? = null, content: LazyListScope.(first: FocusRequester) -> Unit) {
    val first = remember { FocusRequester() }
    // ook opnieuw als de lijst verandert (bijv. sporen geladen): anders raakt de selectie kwijt en reageert niets meer
    LaunchedEffect(refocus) { delay(60); runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxHeight().width(420.dp).background(Color(0xF21C1C22))) {
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 24.dp, vertical = Kijk.SafeV + 8.dp)) {
            content(first)
        }
    }
}

@Composable
fun PanelHeader(title: String) {
    Label(title, Modifier.padding(top = 12.dp, bottom = 4.dp, start = 4.dp), size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
}

@Composable
fun OptionRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.fillMaxWidth(), focusedScale = 1.02f) { focused ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Label(if (checked) "✓" else "", Modifier.width(26.dp), size = Kijk.Headline, weight = Kijk.Bold, color = if (focused) Color.Black else Kijk.Accent)
            Label(label, size = Kijk.Headline, weight = if (checked) Kijk.Semibold else Kijk.Medium, maxLines = 2)
        }
    }
}

fun LazyListScope.subStyleRows(vm: AppViewModel) {
    item(key = "st-size") { val s = vm.subStyle; OptionRow("Tekstgrootte: ${SUB_SIZE_NAMES[s.size]}", false) { vm.updateSubStyle(s.copy(size = (s.size + 1) % 4)) } }
    item(key = "st-bg") { val s = vm.subStyle; OptionRow("Stijl: ${SUB_BG_NAMES[s.background]}", false) { vm.updateSubStyle(s.copy(background = (s.background + 1) % 3)) } }
    item(key = "st-pos") { val s = vm.subStyle; OptionRow("Positie: ${if (s.position == 1) "Hoger" else "Laag"}", false) { vm.updateSubStyle(s.copy(position = 1 - s.position)) } }
}

// ================================================================ live

/**
 * Live kijken. ▲▼ zappen, OK infobalk (daarin ▲▼ door de gids van andere zenders bladeren, OK = overschakelen),
 * ◀ vorige zender, ▶ opties (ondertitels, audio, opnieuw beginnen, favoriet), cijfers = zendernummer.
 */
@Composable
fun LivePlayer(vm: AppViewModel, nav: Nav, startList: List<Channel>, startIndex: Int) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(startList.ifEmpty { vm.channels }) }
    if (list.isEmpty()) { EmptyState("Geen zenders"); BackHandler { nav.pop() }; return }
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, list.lastIndex)) }
    var prevKey by remember { mutableStateOf<String?>(null) }
    val channel = list[index.coerceIn(0, list.lastIndex)]
    val player = remember { buildPlayer(ctx, vm.store.softAudio) }
    var showInfo by remember { mutableStateOf(true) }
    var infoTick by remember { mutableIntStateOf(0) }
    var browse by remember { mutableStateOf<Int?>(null) }     // index in de lijst waar je in de infobalk naar kijkt
    var showOptions by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }   // gids als laag over de zender
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var retries by remember { mutableIntStateOf(0) }
    var digits by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) { error = null; retries = 0 }
            }
            override fun onPlayerError(e: PlaybackException) { error = "Deze zender speelt niet af (${e.errorCodeName})." }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release(); if (vm.playing?.first?.startsWith("live:") == true) vm.playing = null }
    }
    LaunchedEffect(vm.subStyle) { applyPreferences(player, vm.subStyle) }
    var claimed by remember { mutableStateOf(false) }
    // Een ander apparaat neemt het over: stoppen (je aanbieder staat maar één stream toe)
    LaunchedEffect(vm.stoppedBy) {
        val n = vm.stoppedBy ?: return@LaunchedEffect
        vm.stoppedBy = null; player.stop(); vm.notice = "Gestopt: je kijkt nu op $n"; nav.pop()
    }
    LaunchedEffect(channel.key) {
        if (!claimed) { claimed = true; vm.claimPlayback() }   // eerst de andere tv laten stoppen
        vm.playing = "live:${channel.key}" to { 0L }
        vm.watched(channel)
        error = null; retries = 0; tracks = Tracks.EMPTY
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverrides().build()
        applyPreferences(player, vm.subStyle)
        player.stop()   // oude stream eerst echt sluiten: je aanbieder staat maar één verbinding toe
        player.setMediaItem(mediaItem(channel.url)); player.prepare(); player.playWhenReady = true
        showInfo = true; browse = null; infoTick++
    }
    // Veel IPTV-streams haperen even: een paar keer opnieuw proberen.
    LaunchedEffect(error) {
        if (error != null && retries < 5) { delay(3_000); retries++; player.prepare(); player.playWhenReady = true }
    }
    LaunchedEffect(showInfo, infoTick) { if (showInfo) { delay(if (browse != null) 10_000 else 6_000); showInfo = false; browse = null } }
    LaunchedEffect(showOptions, showGuide, vm.dueReminder) { if (!showOptions && !showGuide && vm.dueReminder == null) runCatching { focus.requestFocus() } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(if (digits.length >= 4) 300 else 1500)
        val d = digits; digits = ""
        val i = list.indexOfFirst { it.number.toString() == d }
        if (i >= 0) { prevKey = channel.key; index = i }
        else vm.channels.indexOfFirst { it.number.toString() == d }.takeIf { it >= 0 }?.let { prevKey = channel.key; list = vm.channels; index = it }
    }

    fun switchTo(i: Int) { if (i != index) { prevKey = channel.key; index = i.mod(list.size) } }
    fun current() = vm.nowNext(channel).firstOrNull()?.takeIf { it.isLive(vm.now) }
    fun restart() {
        val p = current() ?: return
        if (vm.canRestart(channel, p)) nav.push(Screen.Vod(listOf(catchupPlayable(vm, channel, p)), 0, 0L, null))
    }

    BackHandler {
        when {
            showGuide -> showGuide = false
            showOptions -> showOptions = false
            browse != null -> { browse = null; infoTick++ }
            // Terug tijdens het kijken: de gids over de zender (naar het menu via de knop in de gids)
            else -> { showInfo = false; showGuide = true }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || showOptions || showGuide) return@onKeyEvent false
                digitOf(e)?.let { digits = (digits + it).take(5); return@onKeyEvent true }
                val b = browse
                when (e.key) {
                    Key.DirectionUp, Key.DirectionDown -> {
                        val d = if (e.key == Key.DirectionUp) -1 else 1
                        if (showInfo) { browse = ((b ?: index) + d).mod(list.size); infoTick++ } else switchTo(index + d)
                        true
                    }
                    Key.ChannelUp, Key.PageUp -> { switchTo(index + 1); true }
                    Key.ChannelDown, Key.PageDown -> { switchTo(index - 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (b != null) { switchTo(b); browse = null } else { showInfo = !showInfo }
                        infoTick++; true
                    }
                    Key.DirectionRight, Key.Menu, Key.Captions -> { showOptions = true; showInfo = false; browse = null; true }
                    Key.DirectionLeft -> {
                        val p = prevKey?.let { k -> list.indexOfFirst { it.key == k } } ?: -1
                        if (p >= 0) switchTo(p); true
                    }
                    Key.Guide, Key.G -> { showGuide = true; showInfo = false; browse = null; true }
                    Key.MediaRewind, Key.MediaPrevious -> { restart(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        VideoSurface(player, vm.subStyle, showInfo)
        // groot logo bij het wisselen van zender, zakt daarna weg naar de infobalk
        if (!showGuide) ZapBadge(channel, Modifier.align(Alignment.Center).glow(rememberDominantColor(channel.logo, true), 0.7f, 0.25f))
        if (error != null || buffering) StatusBubble(error?.let { if (retries < 5) "$it Opnieuw proberen…" else it } ?: "Laden…", Modifier.align(Alignment.TopCenter).padding(top = Kijk.SafeV))
        NumberOverlay(digits, vm, Modifier.align(Alignment.TopEnd).padding(top = Kijk.SafeV, end = Kijk.SafeH))

        AnimatedVisibility(showInfo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            val shown = browse?.let { list.getOrNull(it) } ?: channel
            InfoOverlay(vm, shown, browsing = browse != null, canRestart = current()?.let { vm.canRestart(channel, it) } == true && browse == null)
        }
        if (showGuide) {
            // halfdoorzichtige gids over de zender; het beeld loopt door
            // het hele menu (met de gids open) halfdoorzichtig over de zender
            HomeScreen(vm, nav, HomeOverlay(channel, onPlay = { l, i ->
                showGuide = false
                if (l[i].key != channel.key) { prevKey = channel.key; list = l; index = i }
            }, onClose = { showGuide = false }))
        }
        AnimatedVisibility(showOptions, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            val subs = trackOptions(tracks, C.TRACK_TYPE_TEXT)
            val audio = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
            val subsOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || subs.none { it.selected }
            var refresh by remember { mutableIntStateOf(0) }
            SidePanel(refocus = tracks) { first ->
                item(key = "h") { PanelHeader(shortName(channel.name)) }
                item(key = "guide") { OptionRow("📋  Gids en menu", false, Modifier.focusRequester(first)) { showOptions = false; showGuide = true } }
                val cur = current()
                if (cur != null && vm.canRestart(channel, cur)) item(key = "restart") {
                    OptionRow("↺ Begin opnieuw: ${cur.title}", false) { showOptions = false; restart() }
                }
                item(key = "fav") {
                    OptionRow(if (channel.key in vm.favorites) "★ Uit favorieten halen" else "☆ Aan favorieten toevoegen", false) { vm.toggleFavorite(channel) }
                }
                item(key = "h-subs") { PanelHeader("Ondertitels") }
                item(key = "sub-off$refresh") { OptionRow("Uit", subsOff) { selectTrack(player, C.TRACK_TYPE_TEXT, null); refresh++ } }
                if (subs.isEmpty()) item(key = "sub-none") { Label("Deze zender zendt geen ondertitels mee.", Modifier.padding(6.dp), size = Kijk.Body, color = Kijk.Secondary, maxLines = 2) }
                items(subs) { o -> OptionRow(o.label, o.selected && !subsOff) { selectTrack(player, C.TRACK_TYPE_TEXT, o); refresh++ } }
                subStyleRows(vm)
                if (audio.size > 1) {
                    item(key = "h-audio") { PanelHeader("Audio") }
                    items(audio) { o -> OptionRow(o.label, o.selected) { selectTrack(player, C.TRACK_TYPE_AUDIO, o); refresh++ } }
                }
            }
        }
    }
}

@Composable
private fun InfoOverlay(vm: AppViewModel, ch: Channel, browsing: Boolean, canRestart: Boolean) {
    val nn = vm.nowNext(ch)
    val current = nn.firstOrNull()?.takeIf { it.isLive(vm.now) }
    val next = if (current != null) nn.getOrNull(1) else nn.firstOrNull()
    Box(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = Kijk.SafeH, end = Kijk.SafeH, bottom = Kijk.SafeV, top = 60.dp),
    ) {
        Column {
            if (browsing) Label("▲▼ andere zenders · OK = overschakelen · Terug = annuleren", Modifier.padding(bottom = 6.dp), size = Kijk.Caption, color = Kijk.Accent)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(ch.logo, ch.name, Modifier.size(width = 96.dp, height = 60.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label("${ch.number}", size = Kijk.Title, color = Kijk.Secondary)
                        Spacer(Modifier.width(10.dp))
                        Label(ch.name, size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                        if (ch.key in vm.favorites) Label("  ★", size = Kijk.Title, color = Color(0xFFFFD60A))
                        if (ch.archiveDays > 0) Label("  ↺", size = Kijk.Title, color = Kijk.Accent)
                    }
                    if (current != null) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        LiveBadge(); Spacer(Modifier.width(8.dp))
                        Label(current.title, size = Kijk.Headline, weight = Kijk.Semibold, color = Color.White)
                    }
                }
                Label(Format.time(vm.now), size = Kijk.Title, color = Color.White)
            }
            if (current != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(Format.time(current.start), size = Kijk.Caption, color = Kijk.Secondary)
                    Spacer(Modifier.width(10.dp))
                    ProgressBar(current.progress(vm.now), Modifier.weight(1f), height = 4.dp)
                    Spacer(Modifier.width(10.dp))
                    Label(Format.time(current.stop), size = Kijk.Caption, color = Kijk.Secondary)
                }
                current.desc?.let { Spacer(Modifier.height(8.dp)); Label(it, size = Kijk.Body, color = Color(0xFFD1D1D6), maxLines = 2) }
            } else Label(if (vm.hasEpg(ch)) "Nu geen programma-informatie" else "Geen gidsinformatie voor deze zender", Modifier.padding(top = 8.dp), color = Kijk.Secondary)
            Spacer(Modifier.height(10.dp))
            Row {
                Label(next?.let { "Straks ${Format.time(it.start)} · ${it.title}" } ?: "", Modifier.weight(1f), size = Kijk.Body, color = Kijk.Secondary)
                if (!browsing) Label("▲▼ bladeren   ◀ vorige   ▶ opties   Terug = gids en menu" + (if (canRestart) "   ⏪ opnieuw beginnen" else ""), size = Kijk.Caption, color = Kijk.Secondary)
            }
        }
    }
}
