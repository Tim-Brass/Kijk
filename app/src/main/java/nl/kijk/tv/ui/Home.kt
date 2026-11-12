@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.GROUP_ALL
import nl.kijk.tv.GROUP_ARCH
import nl.kijk.tv.GROUP_FAV
import nl.kijk.tv.GROUP_RECENT
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel

private val TABS = listOf("Live", "Gids", "Films", "Series", "Zoeken", "Instellingen")

/** Gedeelde keuze van de zendergroep (Live en Gids gebruiken dezelfde). */
class HomeState(tab: Int) {
    var tab by mutableIntStateOf(tab)
    var group by mutableStateOf(GROUP_ALL)
    var jump by mutableStateOf<Channel?>(null)   // zendernummer ingetypt: gids springt hierheen
    var backdrop by mutableStateOf<String?>(null) // achtergrondfoto bij Films/Series
}

fun groupLabel(g: String) = when (g) {
    GROUP_ALL -> "Alle zenders"; GROUP_FAV -> "★ Favorieten"; GROUP_ARCH -> "↺ Terugkijken"; GROUP_RECENT -> "🕘 Laatst bekeken"; else -> g
}

@Composable
/** Het menu als laag over een zender: Terug sluit hem, een zender kiezen wisselt in de speler. */
class HomeOverlay(val channel: Channel, val onPlay: (List<Channel>, Int) -> Unit, val onClose: () -> Unit)

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav, overlay: HomeOverlay? = null) {
    val hs = remember { HomeState(if (overlay != null) 1 else vm.store.lastTab.coerceIn(0, TABS.lastIndex)) }
    val tabFocus = remember { List(TABS.size) { FocusRequester() } }
    var digits by remember { mutableStateOf("") }
    // over een zender: de gids pakt zelf de selectie (op de huidige zender)
    LaunchedEffect(Unit) { if (overlay == null) runCatching { tabFocus[hs.tab].requestFocus() } }
    fun pick(i: Int) { hs.tab = i; if (overlay == null) vm.store.lastTab = i }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(if (digits.length >= 4) 300 else 1500)
        val idx = vm.channels.indexOfFirst { it.number.toString() == digits }
        digits = ""
        if (idx < 0) return@LaunchedEffect
        if (hs.tab == 1) hs.jump = vm.channels[idx] else if (overlay != null) overlay.onPlay(vm.channels, idx) else nav.push(Screen.Live(vm.channels, idx))
    }
    // Terug: eerst naar de tabbalk, dan naar Live, dan vragen of je wilt afsluiten.
    val activity = LocalContext.current as? android.app.Activity
    var tabsFocused by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // na een herinnering-melding de selectie terugzetten
    var hadReminder by remember { mutableStateOf(false) }
    LaunchedEffect(vm.dueReminder) {
        if (vm.dueReminder != null) hadReminder = true
        else if (hadReminder) { hadReminder = false; delay(100); runCatching { tabFocus[hs.tab].requestFocus() } }
    }
    BackHandler {
        when {
            overlay != null -> overlay.onClose()
            !tabsFocused -> runCatching { tabFocus[hs.tab].requestFocus() }
            hs.tab != 0 -> { hs.tab = 0; vm.store.lastTab = 0; runCatching { tabFocus[0].requestFocus() } }
            else -> nav.confirm("Kijk afsluiten?", "Kijk blijft op de achtergrond klaarstaan, zodat hij de volgende keer meteen weer start.", yes = "Afsluiten") {
                activity?.moveTaskToBack(true)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(if (overlay != null) Color(0xD90B0B0F) else Kijk.Background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || hs.tab >= 4) return@onPreviewKeyEvent false  // in zoeken/instellingen zijn cijfers tekst
                val d = digitOf(e) ?: return@onPreviewKeyEvent false
                digits = (digits + d).take(5); true
            },
    ) {
        if (overlay == null && (hs.tab == 2 || hs.tab == 3)) VodBackdrop(hs.backdrop)
        Column(Modifier.fillMaxSize().padding(horizontal = Kijk.SafeH, vertical = Kijk.SafeV)) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.Center).onFocusChanged { tabsFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TABS.forEachIndexed { i, title ->
                        FocusCard(
                            onClick = { pick(i) }, onFocus = { pick(i) },
                            selected = hs.tab == i, modifier = Modifier.focusRequester(tabFocus[i]),
                            shape = RoundedCornerShape(50), focusedScale = 1.06f,
                        ) { Label(title, Modifier.padding(horizontal = 18.dp, vertical = 7.dp), size = Kijk.Headline, weight = Kijk.Semibold) }
                    }
                }
                Label(Format.time(vm.now), Modifier.align(Alignment.CenterEnd), size = Kijk.Headline, color = Kijk.Secondary)
                vm.update?.let { u ->
                    SmallPill("⬆  Nieuwe versie 0.1.${u.build}", Modifier.align(Alignment.CenterStart), selected = true) { vm.installUpdate(ctx) }
                }
            }
            Spacer(Modifier.height(8.dp))
            ProgressLine(vm.progress, vm.error ?: vm.status, vm.error != null)
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f)) {
                FadeIn(hs.tab) {
                    when (hs.tab) {
                        0 -> LiveTab(vm, nav, hs, overlay?.onPlay)
                        1 -> GuideGrid(vm, nav, hs, focusChannel = overlay?.channel, onPlay = overlay?.onPlay)
                        2 -> VodTab(vm, nav, series = false, hs = hs)
                        3 -> VodTab(vm, nav, series = true, hs = hs)
                        4 -> SearchTab(vm, nav)
                        else -> SettingsTab(vm, nav)
                    }
                }
            }
        }
        NumberOverlay(digits, vm, Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = Kijk.SafeH))
    }
}

@Composable
private fun LiveTab(vm: AppViewModel, nav: Nav, hs: HomeState, onPlay: ((List<Channel>, Int) -> Unit)? = null) {
    if (vm.channels.isEmpty()) { EmptyState(if (vm.progress != null) "Zenders laden…" else "Nog geen zenders", vm.error ?: "Controleer je playlist bij Instellingen."); return }
    val special = listOfNotNull(GROUP_ALL, GROUP_FAV.takeIf { vm.favorites.isNotEmpty() }, GROUP_RECENT.takeIf { vm.recentChannels.isNotEmpty() },
        GROUP_ARCH.takeIf { vm.channels.any { it.archiveDays > 0 } })
    val all = special + vm.groups
    if (hs.group !in all) hs.group = GROUP_ALL
    val list = remember(vm.dataVersion, hs.group, vm.favorites, vm.recentChannels, vm.unlocked) { vm.list(hs.group) }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(230.dp), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(6.dp)) {
            items(all, key = { it }) { g ->
                val locked = vm.isLocked(g) && !vm.unlocked
                FocusCard(
                    onClick = { if (locked) nav.askPin(vm) { vm.unlock(); hs.group = g } else hs.group = g },
                    onFocus = { hs.group = g }, selected = hs.group == g, modifier = Modifier.fillMaxWidth(),
                ) { Label((if (locked) "🔒 " else "") + groupLabel(g), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), weight = Kijk.Medium) }
            }
        }
        Spacer(Modifier.width(20.dp))
        when {
            vm.isLocked(hs.group) && !vm.unlocked -> EmptyState("🔒 Vergrendeld", "Druk op OK op de groep om je pincode in te voeren.")
            list.isEmpty() -> EmptyState("Geen zenders", if (hs.group == GROUP_FAV) "Houd OK ingedrukt op een zender om hem bij je favorieten te zetten." else "")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp)) {
                itemsIndexed(list) { i, ch ->
                    ChannelRow(vm, ch, onClick = { if (onPlay != null) onPlay(list, i) else nav.push(Screen.Live(list, i)) }, onLongClick = { vm.toggleFavorite(ch) })
                }
            }
        }
    }
}

@Composable
fun ChannelRow(vm: AppViewModel, ch: Channel, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, compact: Boolean = false) {
    val nn = vm.nowNext(ch)
    val cur = nn.firstOrNull()?.takeIf { it.isLive(vm.now) }
    val next = if (cur != null) nn.getOrNull(1) else nn.firstOrNull()
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!compact) Label(ch.number.toString(), Modifier.width(44.dp), color = secondary(focused))
            ChannelLogo(ch.logo, ch.name)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(ch.name, Modifier.weight(1f, fill = false), size = Kijk.Headline, weight = Kijk.Semibold)
                    if (ch.key in vm.favorites) Label("  ★", color = if (focused) Color(0xFFB8860B) else Color(0xFFFFD60A))
                    if (ch.archiveDays > 0) Label("  ↺", color = Kijk.Accent, weight = Kijk.Bold)
                }
                if (cur != null) {
                    Label(cur.title, color = if (focused) Color.Black else Color.White)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressBar(cur.progress(vm.now), Modifier.width(120.dp), focused)
                        Spacer(Modifier.width(8.dp))
                        Label(Format.minutesLeft(cur.stop, vm.now), size = Kijk.Caption, color = secondary(focused))
                    }
                } else Label(if (vm.hasEpg(ch)) "Nu geen programma-informatie" else "Geen gidsinformatie", size = Kijk.Caption, color = secondary(focused))
            }
            if (!compact && next != null) {
                Spacer(Modifier.width(16.dp))
                Column(Modifier.width(210.dp)) {
                    Label("Straks · ${Format.time(next.start)}", size = Kijk.Caption, color = secondary(focused))
                    Label(next.title, color = secondary(focused))
                }
            }
        }
    }
}
