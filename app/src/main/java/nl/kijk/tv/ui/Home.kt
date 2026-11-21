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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
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
import kotlinx.coroutines.launch
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.GROUP_ALL
import nl.kijk.tv.GROUP_ARCH
import nl.kijk.tv.GROUP_FAV
import nl.kijk.tv.GROUP_RECENT
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel

private val TABS = listOf("Thuis", "Live", "Gids", "Films", "Series", "Zoeken", "Instellingen")
const val TAB_HOME = 0
const val TAB_LIVE = 1
const val TAB_GUIDE = 2
const val TAB_FILMS = 3
const val TAB_SERIES = 4
const val TAB_SEARCH = 5
const val TAB_SETTINGS = 6

/** Gedeelde keuze van de zendergroep (Live en Gids gebruiken dezelfde). */
class HomeState(tab: Int) {
    var tab by mutableIntStateOf(tab)
    var group by mutableStateOf(GROUP_ALL)
    var jump by mutableStateOf<Channel?>(null)   // zendernummer ingetypt: gids springt hierheen
    var guideTop by mutableStateOf(0)            // terug naar het menu: gids naar boven
    var backdrop by mutableStateOf<String?>(null) // achtergrondfoto bij Films/Series
}

fun groupLabel(g: String) = when (g) {
    GROUP_ALL -> "Alle zenders"; GROUP_FAV -> "★ Favorieten"; GROUP_ARCH -> "↺ Terugkijken"; GROUP_RECENT -> "🕘 Laatst bekeken"; else -> g
}

/** Het menu als laag over een zender: Terug sluit hem, een zender kiezen wisselt in de speler. */
class HomeOverlay(val channel: Channel, val onPlay: (List<Channel>, Int) -> Unit, val onClose: () -> Unit, val onBeforeCatchup: (() -> Unit)? = null)

@Composable
fun HomeScreen(vm: AppViewModel, nav: Nav, overlay: HomeOverlay? = null) {
    // Kijk opent altijd op Thuis; het menu over een zender opent op het laatst gekozen tabblad (Thuis → Live)
    // onthouden (ook na een film of detailpagina): bij Terug kom je op hetzelfde tabblad en dezelfde groep terug
    val hs = androidx.compose.runtime.saveable.rememberSaveable(saver = androidx.compose.runtime.saveable.Saver<HomeState, List<Any>>(
        save = { listOf(it.tab, it.group) }, restore = { HomeState(it[0] as Int).apply { group = it[1] as String } },
    )) { HomeState(if (overlay == null) TAB_HOME else vm.store.lastTab.coerceIn(0, TABS.lastIndex).takeIf { it != TAB_HOME } ?: TAB_LIVE) }
    val tabFocus = remember { List(TABS.size) { FocusRequester() } }
    // Bewaar de LazyColumn/LazyRow scrollpositie per tab, ook wanneer het scherm uit compositie gaat.
    val tabStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    var digits by remember { mutableStateOf("") }
    // over een zender: de gids pakt zelf de selectie (op de huidige zender)
    LaunchedEffect(Unit) { runCatching { tabFocus[hs.tab].requestFocus() } }
    // menu over een zender: alleen bij het openen pakt de gids de selectie; daarna blijft die op de tabbalk
    var guideGrabsFocus by remember { mutableStateOf(false) }
    fun pick(i: Int) { hs.tab = i; guideGrabsFocus = false; vm.store.lastTab = i }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(if (digits.length >= 4) 300 else 1500)
        val idx = vm.channels.indexOfFirst { it.number.toString() == digits }
        digits = ""
        if (idx < 0) return@LaunchedEffect
        if (hs.tab == TAB_GUIDE) hs.jump = vm.channels[idx] else if (overlay != null) overlay.onPlay(vm.channels, idx) else nav.push(Screen.Live(vm.channels, idx))
    }
    // Terug: eerst naar de tabbalk, dan naar Live, dan vragen of je wilt afsluiten.
    val activity = LocalContext.current as? android.app.Activity
    var tabsFocused by remember { mutableStateOf(false) }
    var menuBarPx by remember { mutableStateOf(0) }   // hoogte van de menubalk, voor de achtergrond
    val ctx = LocalContext.current
    // na een herinnering-melding de selectie terugzetten
    var hadReminder by remember { mutableStateOf(false) }
    LaunchedEffect(vm.dueReminder) {
        if (vm.dueReminder != null) hadReminder = true
        else if (hadReminder) { hadReminder = false; delay(100); runCatching { tabFocus[hs.tab].requestFocus() } }
    }
    BackHandler {
        when {
            // eerst altijd terug naar de menubalk, ook als het menu over een zender ligt
            !tabsFocused -> { if (hs.tab == TAB_GUIDE && overlay == null) hs.guideTop++; runCatching { tabFocus[hs.tab].requestFocus() } }
            overlay != null -> overlay.onClose()
            hs.tab != TAB_HOME -> { hs.tab = TAB_HOME; runCatching { tabFocus[TAB_HOME].requestFocus() } }
            else -> nav.confirm("Kijk afsluiten?", "Kijk wordt helemaal gesloten. De volgende keer start hij opnieuw op.", yes = "Afsluiten") {
                quitApp(activity, vm)
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(if (overlay != null) Color(0xD90B0B0F) else Kijk.Background)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || hs.tab >= TAB_SEARCH) return@onPreviewKeyEvent false  // in zoeken/instellingen zijn cijfers tekst
                val d = digitOf(e) ?: return@onPreviewKeyEvent false
                digits = (digits + d).take(5); true
            },
    ) {
        // Thuis zonder lopende zender: vaste achtergrond (32 bits, anders streperig verloop)
        if (overlay == null && hs.tab == TAB_HOME) androidx.compose.foundation.Image(backdropBitmap(nl.kijk.tv.R.drawable.thuis_achtergrond, alpha = true), null,
            Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (overlay == null && (hs.tab == TAB_HOME || hs.tab == TAB_FILMS || hs.tab == TAB_SERIES)) VodBackdrop(hs.backdrop)
        // menubalk-achtergrond: blauwe golf achter de tabs, loopt via zwart zacht over in de rest van het scherm (verloop zit in het plaatje)
        // even hoog als de menubalk + een kort verloop (zoals voorheen)
        val barDp = with(androidx.compose.ui.platform.LocalDensity.current) { menuBarPx.toDp() }
        if (menuBarPx > 0) androidx.compose.foundation.Image(backdropBitmap(nl.kijk.tv.R.drawable.menu_balk, alpha = true), null,
            Modifier.fillMaxWidth().height(barDp + 22.dp), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
        Column(Modifier.fillMaxSize()) {
            // menubalk: effen zwart tot aan de grijze lijn (ook boven een lopende zender)
            Box(Modifier.fillMaxWidth().onSizeChanged { menuBarPx = it.height }) {
              Box(Modifier.fillMaxWidth().padding(start = Kijk.SafeH, end = Kijk.SafeH, top = Kijk.SafeV)) {
                Row(Modifier.align(Alignment.BottomCenter).onFocusChanged { tabsFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
                    TABS.forEachIndexed { i, title ->
                        val m = Modifier.focusRequester(tabFocus[i]).tabShade()
                        // wisselen pas bij OK, niet bij alleen erlangs bewegen
                        when (i) {
                            TAB_SEARCH -> LineItem(m, active = hs.tab == i, onClick = { pick(i) }) { c -> LineIcon(LineIconKind.Search, c) }
                            TAB_SETTINGS -> LineItem(m, active = hs.tab == i, onClick = { pick(i) }) { c -> LineIcon(LineIconKind.Settings, c) }
                            else -> LineText(title, m, active = hs.tab == i) { pick(i) }
                        }
                    }
                }
                MenuLogo(still = false, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp), height = 36.dp)
                Row(Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.CenterVertically) {
                    vm.update?.let { u ->
                        SmallPill("⬆  Nieuwe versie ${nl.kijk.tv.Versions.name(u.build)}", Modifier.padding(bottom = 6.dp), selected = true) { vm.installUpdate(ctx) }
                        Spacer(Modifier.width(14.dp))
                    }
                    if (hs.tab != TAB_HOME) {   // op Thuis staat de tijd al groot
                        Label(Format.time(vm.now), Modifier.padding(bottom = 9.dp), size = Kijk.Headline, color = Kijk.Secondary)
                        Spacer(Modifier.width(10.dp))
                    }
                    // afsluiten
                    LineItem(modifier = Modifier.tabShade(), onClick = {
                        nav.confirm("Kijk afsluiten?", "Kijk wordt helemaal gesloten. De volgende keer start hij opnieuw op.", yes = "Afsluiten") {
                            quitApp(activity, vm)
                        }
                    }) { c -> LineIcon(LineIconKind.Exit, c) }
                }
              }
            }
            // Thuis: iets smallere zijmarges, zodat er meer op past
            val side = 28.dp   // op alle tabbladen dezelfde, iets smallere zijmarges als op Thuis
            Column(Modifier.weight(1f).padding(start = side, end = side, bottom = if (hs.tab == TAB_HOME) 6.dp else Kijk.SafeV)) {
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f)) {
                tabStateHolder.SaveableStateProvider(hs.tab) {
                    FadeIn(hs.tab) {
                    when (hs.tab) {
                        TAB_HOME -> HomeTab(vm, nav, hs, overlay?.onPlay)
                        TAB_LIVE -> androidx.compose.runtime.CompositionLocalProvider(LocalDarkFocus provides true) { LiveTab(vm, nav, hs, overlay?.onPlay) }
                        TAB_GUIDE -> androidx.compose.runtime.CompositionLocalProvider(LocalDarkFocus provides true) { GuideGrid(vm, nav, hs, focusChannel = overlay?.channel, autoFocus = guideGrabsFocus, onPlay = overlay?.onPlay, onBeforeCatchup = overlay?.onBeforeCatchup) }
                        TAB_FILMS -> VodTab(vm, nav, series = false, hs = hs)
                        TAB_SERIES -> VodTab(vm, nav, series = true, hs = hs)
                        TAB_SEARCH -> SearchTab(vm, nav)
                        else -> androidx.compose.runtime.CompositionLocalProvider(LocalDarkFocus provides true) { SettingsTab(vm, nav) }
                    }
                    }
                }
            }
            }
            // Thuis: lichtkrant over de volle breedte, altijd in beeld
            if (hs.tab == TAB_HOME) NewsTicker(vm)
        }
        NumberOverlay(digits, vm, Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = Kijk.SafeH))
        // voortgang en meldingen: klein zwevend labeltje linksboven, over alles heen (neemt geen ruimte in)
        MenuStatus(vm.progress, vm.error ?: vm.status, vm.error != null,
            Modifier.align(Alignment.TopStart).padding(top = 6.dp, start = 12.dp)
                .background(Color(0xE6202026), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
    }
}

@Composable
private fun LiveTab(vm: AppViewModel, nav: Nav, hs: HomeState, onPlay: ((List<Channel>, Int) -> Unit)? = null) {
    if (vm.channels.isEmpty()) { EmptyState(if (vm.progress != null) "Zenders laden…" else "Nog geen zenders", vm.error ?: "Controleer je playlist bij Instellingen."); return }
    val special = listOfNotNull(GROUP_ALL, GROUP_FAV.takeIf { vm.favorites.isNotEmpty() }, GROUP_RECENT.takeIf { vm.recentChannels.isNotEmpty() },
        GROUP_ARCH.takeIf { vm.channels.any { it.archiveDays > 0 } })
    val all = special + vm.groups
    if (hs.group !in all) hs.group = GROUP_ALL
    val list = remember(vm.dataVersion, hs.group, vm.favorites, vm.recentChannels, vm.unlocked, vm.epgMatch) {
        vm.liveList(hs.group)
    }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.width(230.dp).fillMaxHeight().dividerRight(), verticalArrangement = Arrangement.spacedBy(2.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(all, key = { it }) { g ->
                val locked = vm.isLocked(g) && !vm.unlocked
                SideItem((if (locked) "🔒 " else "") + groupLabel(g), selected = hs.group == g, onFocus = { hs.group = g }) {
                    if (locked) nav.askPin(vm) { vm.unlock(); hs.group = g } else hs.group = g
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        when {
            vm.isLocked(hs.group) && !vm.unlocked -> EmptyState("🔒 Vergrendeld", "Druk op OK op de groep om je pincode in te voeren.")
            list.isEmpty() -> EmptyState("Geen zenders", if (hs.group == GROUP_FAV) "Houd OK ingedrukt op een zender om hem bij je favorieten te zetten." else "")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp, horizontal = 10.dp)) {
                itemsIndexed(list, key = { _, ch -> ch.key }) { i, ch ->
                    val play: (List<Channel>) -> Unit = { l -> if (onPlay != null) onPlay(l, i) else nav.push(Screen.Live(l, i)) }
                    ChannelRow(vm, ch, onClick = { play(list) }, onLongClick = { vm.toggleFavorite(ch) })
                }
            }
        }
    }
}

@Composable
fun ChannelRow(vm: AppViewModel, ch: Channel, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, compact: Boolean = false) {
    // Live toont weer één zenderkaart; kwaliteitskeuze blijft beschikbaar in het spelermenu (OK → OK).
    ChannelRowCard(vm, ch, onClick, onLongClick, compact, Modifier.fillMaxWidth(), showQuality = true)
}

@Composable
private fun ChannelRowCard(vm: AppViewModel, ch: Channel, onClick: () -> Unit, onLongClick: (() -> Unit)?, compact: Boolean, modifier: Modifier, showQuality: Boolean) {
    val nn = vm.nowNext(ch)
    val cur = nn.firstOrNull()?.takeIf { it.isLive(vm.now) }
    val next = if (cur != null) nn.getOrNull(1) else nn.firstOrNull()
    FocusCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier, darkFocus = true) { _ ->
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!compact) Label(ch.number.toString(), Modifier.width(40.dp), color = Kijk.Secondary)
                // groot logo zonder grijs vak (zoals in de gids)
                ChannelLogo(ch.logo, ch.name, Modifier.size(width = 104.dp, height = 46.dp), plate = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(shortName(ch.name), Modifier.weight(1f, fill = false), size = Kijk.Headline, weight = Kijk.Semibold)
                        if (showQuality) vm.variantLabel(ch).takeIf { it.isNotEmpty() }?.let { Label("  · $it", size = Kijk.Caption, color = Kijk.Secondary) }
                        if (ch.archiveDays > 0) Label("  ↺", color = Kijk.Accent, weight = Kijk.Bold)
                    }
                    if (cur != null) {
                        Label(cur.title, color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // voortgang van wat nu speelt: rood, zoals in de gids
                            Box(Modifier.width(120.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(cur.progress(vm.now).coerceIn(0f, 1f)).background(Kijk.Live))
                            }
                            Spacer(Modifier.width(8.dp))
                            Label(Format.minutesLeft(cur.stop, vm.now), size = Kijk.Caption, color = Kijk.Secondary)
                        }
                    } else Label(if (vm.hasEpg(ch)) "Nu geen programma-informatie" else "Geen gidsinformatie", size = Kijk.Caption, color = Kijk.Secondary)
                }
                if (!compact && next != null) {
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.width(210.dp)) {
                        Label("Straks · ${Format.time(next.start)}", size = Kijk.Caption, color = Kijk.Secondary)
                        Label(next.title, color = Kijk.Secondary)
                    }
                }
            }
            // favoriet: sterretje rechtsboven in de hoek
            if (ch.key in vm.favorites) Label("★", Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 8.dp), size = 14.sp, color = Color(0xFFFFD60A))
        }
    }
}

/** Kijk echt afsluiten: scherm dicht, uit de recente apps, en het proces stoppen (geen stream of server meer op de achtergrond). */
fun quitApp(activity: android.app.Activity?, vm: nl.kijk.tv.AppViewModel) {
    activity ?: return
    if (vm.quitting) return
    vm.quitting = true   // eerst alles uit beeld (en uit de luidspreker): alleen nog "Tot Kijk!"
    kotlinx.coroutines.MainScope().launch {
        val t0 = System.currentTimeMillis()
        // ondertussen nog wegschrijven naar GitHub, maar nooit lang
        kotlinx.coroutines.withTimeoutOrNull(2_500) { vm.githubFlush() }
        delay((1_300 - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
        activity.finishAndRemoveTask()
        @Suppress("DEPRECATION") activity.overridePendingTransition(0, 0)   // zonder animatie weg
        delay(300)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** Afscheid bij het afsluiten. */
@Composable
fun GoodbyeScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            EyesLogo(done = true, modifier = Modifier.height(96.dp))
            Spacer(Modifier.height(22.dp))
            Label("Tot Kijk!", size = Kijk.LargeTitle, color = Color.White)
        }
    }
}

/** Zachte donkere schaduw achter een tab of icoon, zodat het loskomt van de menubalk-achtergrond. Alleen tekenen, geen extra laag. */
private fun Modifier.tabShade(): Modifier = this.drawBehind {
    // elliptische zachte vlek: cirkel met verloop, verticaal platgedrukt
    val rx = size.width / 2 + size.width * 0.35f + 10.dp.toPx(); val ry = size.height / 2 + size.height * 0.45f
    scale(1f, ry / rx, pivot = center) {
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.3f), Color.Transparent),
            center = center, radius = rx), radius = rx, center = center)
    }
}
