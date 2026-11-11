@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.key
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.EpgRepository.Companion.DAY
import nl.kijk.tv.data.EpgRepository.Companion.HOUR
import nl.kijk.tv.data.EpgRepository.Companion.MINUTE
import nl.kijk.tv.data.Playable
import nl.kijk.tv.data.Programme

private const val WINDOW = 2 * HOUR
private const val STEP = 30 * MINUTE
private const val BUCKET = 6 * HOUR
private val CHAN_W = 210.dp
private val ROW_H = 58.dp

fun catchupPlayable(vm: AppViewModel, ch: Channel, p: Programme, offsetMs: Long = 0L) = Playable(
    key = "c:${ch.key}:${p.start}", title = p.title, subtitle = "${shortName(ch.name)} · ${Format.dayTime(p.start)}",
    url = vm.catchupUrl(ch, p.start + offsetMs, maxOf(p.stop, p.start + offsetMs + MINUTE)),
    catchupChannel = ch, catchupProgramme = p, offsetMs = offsetMs,
)

private fun halfHour(t: Long) = t - t % STEP

/**
 * Gids als raster, zoals bij Ziggo/KPN: zenders onder elkaar, programma's in de tijd naast elkaar.
 * Links/rechts aan de rand schuift het tijdvenster per half uur; terugkijken gaat via OK op een afgelopen programma.
 */
@Composable
fun GuideGrid(vm: AppViewModel, nav: Nav, hs: HomeState) {
    var favOnly by remember { mutableStateOf(vm.store.guideFav) }
    var archOnly by remember { mutableStateOf(vm.store.guideArch) }
    var winStart by remember { mutableLongStateOf(halfHour(System.currentTimeMillis())) }
    var sel by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val base = remember(vm.dataVersion, hs.group, vm.favorites, vm.unlocked) { vm.list(hs.group) }
    val list = remember(base, favOnly, archOnly, vm.favorites) {
        base.filter { (!favOnly || it.key in vm.favorites) && (!archOnly || it.archiveDays > 0) }
    }
    val state = rememberLazyListState()
    val maxBack = remember(list) { (list.maxOfOrNull { it.archiveDays } ?: 0).coerceAtLeast(1) * DAY }
    /** Tijdvenster zo zetten dat tijdstip t goed in beeld is. */
    fun show(t: Long): Boolean {
        if (t >= winStart && t < winStart + WINDOW - 15 * MINUTE) return true
        val n = if (t < winStart) halfHour(t) else halfHour(t) - STEP
        val now = System.currentTimeMillis()
        if (n < halfHour(now - maxBack) || n > now + 7 * DAY) return false
        winStart = n; return true
    }
    fun shift(by: Long): Boolean {
        val now = System.currentTimeMillis()
        val n = winStart + by
        if (n < halfHour(now - maxBack) || n > now + 7 * DAY) return false
        winStart = n; return true
    }
    LaunchedEffect(hs.jump) {
        val j = hs.jump ?: return@LaunchedEffect
        val i = list.indexOfFirst { it.key == j.key }
        if (i >= 0) { winStart = halfHour(System.currentTimeMillis()); state.scrollToItem(i) }
        hs.jump = null
    }

    Column(Modifier.fillMaxSize()) {
        // ---- details van het gefocuste programma
        Box(Modifier.fillMaxWidth().height(74.dp)) {
            val s = sel
            if (s != null) Column {
                val p = s.second
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p != null && p.isLive(vm.now)) { LiveBadge(); Spacer(Modifier.width(8.dp)) }
                    Label(p?.title ?: shortName(s.first.name), size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                }
                val info = if (p == null) "${s.first.number} · ${s.first.name}" else listOfNotNull(
                    "${Format.dayLabel(p.start, vm.now)} ${Format.range(p.start, p.stop)}", shortName(s.first.name),
                    when { p.isLive(vm.now) -> Format.minutesLeft(p.stop, vm.now); vm.canCatchup(s.first, p) -> "↺ OK om terug te kijken"; else -> null },
                ).joinToString("  ·  ")
                Label(info, size = Kijk.Caption, color = Kijk.Secondary)
                p?.desc?.let { Label(it, size = Kijk.Caption, color = Color(0xFFD1D1D6), maxLines = 2) }
            } else Label("Kies een programma met de pijltjestoetsen", Modifier.align(Alignment.CenterStart), color = Kijk.Secondary)
        }
        // ---- werkbalk
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SmallPill("Nu") { winStart = halfHour(System.currentTimeMillis()) }
            SmallPill("◀ Eerder") { shift(-WINDOW) }
            SmallPill("Later ▶") { shift(WINDOW) }
            SmallPill("★ Alleen favorieten", selected = favOnly) { favOnly = !favOnly; vm.store.guideFav = favOnly }
            SmallPill("↺ Alleen terugkijken", selected = archOnly) { archOnly = !archOnly; vm.store.guideArch = archOnly }
            Spacer(Modifier.width(8.dp))
            Label("${groupLabel(hs.group)} · ${Format.dayLabel(winStart, vm.now)}", size = Kijk.Caption, color = Kijk.Secondary)
        }
        if (list.isEmpty()) {
            EmptyState("Geen zenders", if (favOnly) "Je hebt (in deze groep) nog geen favorieten." else if (archOnly) "Geen zenders met terugkijken in deze groep." else "Kies een andere groep onder Live.")
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timeW = maxWidth - CHAN_W - 8.dp
            val perMin = timeW / (WINDOW / MINUTE).toFloat()
            Column {
                // ---- tijdlijn
                Box(Modifier.fillMaxWidth().height(22.dp)) {
                    for (i in 0 until (WINDOW / STEP).toInt()) {
                        Label(Format.time(winStart + i * STEP), Modifier.offset(x = CHAN_W + 8.dp + perMin * (i * STEP / MINUTE).toFloat()), size = Kijk.Caption, color = Kijk.Secondary)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        itemsIndexed(list) { i, ch ->
                            GuideRow(vm, ch, winStart, perMin,
                                onFocus = { sel = ch to it },
                                onShift = { dir -> shift(dir * STEP) }, onShow = { t -> show(t) },
                                onPlayLive = { nav.push(Screen.Live(list, i)) },
                                onCatchup = { p -> nav.push(Screen.Vod(listOf(catchupPlayable(vm, ch, p)), 0, 0L, null)) },
                            )
                        }
                    }
                    // nu-lijn
                    val now = vm.now
                    if (now in winStart until winStart + WINDOW) {
                        Box(Modifier.offset(x = CHAN_W + 8.dp + perMin * ((now - winStart) / MINUTE).toFloat()).width(2.dp).fillMaxHeight().background(Kijk.Live.copy(alpha = 0.8f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel, ch: Channel, winStart: Long, perMin: Dp,
    onFocus: (Programme?) -> Unit, onShift: (Int) -> Boolean, onShow: (Long) -> Boolean, onPlayLive: () -> Unit, onCatchup: (Programme) -> Unit,
) {
    // Links/rechts regelen we zelf: naar het vorige/volgende programma, en zo nodig schuift de gids mee.
    // (Automatisch zoeken van Android sprong soms uit de gids naar de tabbalk.)
    val reqs = remember { HashMap<Long, FocusRequester>() }
    var want by remember { mutableStateOf<Long?>(null) }
    val bucket = winStart - Math.floorMod(winStart, BUCKET)
    val progs by produceState<List<Programme>?>(null, ch.key, bucket, vm.dataVersion, vm.epgOffsetMinutes) {
        value = try { vm.programmes(ch, bucket - HOUR, bucket + BUCKET + WINDOW + HOUR) } catch (_: Exception) { emptyList() }
    }
    val winEnd = winStart + WINDOW
    Row(Modifier.fillMaxWidth().height(ROW_H)) {
        FocusCard(onClick = onPlayLive, onLongClick = { vm.toggleFavorite(ch) }, onFocus = { onFocus(null) }, modifier = Modifier.width(CHAN_W).fillMaxHeight(), focusedScale = 1.02f) { f ->
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(ch.number.toString(), Modifier.width(36.dp), size = Kijk.Caption, color = secondary(f))
                ChannelLogo(ch.logo, ch.name, Modifier.size(width = 52.dp, height = 34.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Label(shortName(ch.name), size = Kijk.Caption, weight = Kijk.Semibold)
                    Row {
                        if (ch.key in vm.favorites) Label("★ ", size = Kijk.Caption, color = if (f) Color(0xFFB8860B) else Color(0xFFFFD60A))
                        if (ch.archiveDays > 0) Label("↺ ${ch.archiveDays} d", size = Kijk.Caption, color = Kijk.Accent)
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val all = progs ?: emptyList()
            val inWin = all.filter { it.stop > winStart && it.start < winEnd }
            LaunchedEffect(want, inWin) {
                val target = want ?: return@LaunchedEffect
                repeat(20) {
                    val rq = reqs[target]
                    if (rq != null && runCatching { rq.requestFocus() }.isSuccess) { want = null; return@LaunchedEffect }
                    delay(25)
                }
                want = null
            }
            fun move(p: Programme, e: androidx.compose.ui.input.key.KeyEvent): Boolean {
                if (e.type != KeyEventType.KeyDown) return false
                return when (e.key) {
                    Key.DirectionRight -> {
                        val next = all.firstOrNull { it.start > p.start && it.start >= p.stop - MINUTE }
                        if (next != null && onShow(next.start)) want = next.start else onShift(1)
                        true
                    }
                    Key.DirectionLeft -> {
                        val prev = all.lastOrNull { it.start < p.start && it.stop <= p.start + MINUTE }
                        when {
                            prev != null && onShow(maxOf(prev.start, prev.stop - STEP)) -> { want = prev.start; true }
                            p.start <= winStart -> { onShift(-1); true }
                            else -> false   // naar de zender links
                        }
                    }
                    else -> false
                }
            }
            if (progs != null && inWin.isEmpty()) {
                FocusCard(onClick = onPlayLive, onFocus = { onFocus(null) }, modifier = Modifier.fillMaxSize()
                    .onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && onShift(1).let { true } }, focusedScale = 1f) { f ->
                    Label(if (vm.hasEpg(ch)) "Geen programma-informatie" else "Geen gidsinformatie", Modifier.align(Alignment.CenterStart).padding(horizontal = 12.dp), size = Kijk.Caption, color = secondary(f))
                }
            }
            inWin.forEach { p -> key(p.start) {
                val a = maxOf(p.start, winStart); val b = minOf(p.stop, winEnd)
                val x = perMin * ((a - winStart) / MINUTE).toFloat()
                val w = (perMin * ((b - a) / MINUTE).toFloat() - 4.dp).coerceAtLeast(6.dp)
                val live = p.isLive(vm.now)
                val catchup = vm.canCatchup(ch, p)
                val past = p.stop <= vm.now
                // Smalle vakjes worden breder zolang ze geselecteerd zijn, zodat de titel leesbaar is.
                var hasFocus by remember { mutableStateOf(false) }
                val shown = if (hasFocus && w < 260.dp) 260.dp else w
                FocusCard(
                    onClick = { when { live -> onPlayLive(); catchup -> onCatchup(p) } },
                    onFocus = { onFocus(p) },
                    modifier = Modifier.offset(x = x).zIndex(if (hasFocus) 1f else 0f).width(shown).fillMaxHeight()
                        .onFocusChanged { hasFocus = it.hasFocus }
                        .focusRequester(reqs.getOrPut(p.start) { FocusRequester() })
                        .onPreviewKeyEvent { e -> move(p, e) },
                    selected = live, shape = RoundedCornerShape(8.dp), focusedScale = 1f,
                ) { f ->
                    // al uitgezonden deel van een lopend programma kleurt mee
                    if (live && !f) Box(Modifier.fillMaxHeight().fillMaxWidth(p.progress(vm.now).coerceIn(0f, 1f)).background(Kijk.AccentSoft))
                    Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (catchup) Label("↺ ", size = Kijk.Caption, color = if (f) Color(0xFF0050A0) else Kijk.Accent, weight = Kijk.Bold)
                            Label(p.title, size = Kijk.Caption, weight = Kijk.Semibold, color = if (past && !catchup && !f) Kijk.Secondary else Color.Unspecified)
                        }
                        if (shown >= 70.dp) Label(Format.range(p.start, p.stop), size = 11.sp(), color = secondary(f))
                        if (live) ProgressBar(p.progress(vm.now), Modifier.fillMaxWidth().padding(top = 3.dp), f, 2.dp)
                    }
                }
            } }
        }
    }
}

private fun Int.sp() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
