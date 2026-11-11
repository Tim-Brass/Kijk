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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.BuildConfig
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.EpgHit
import nl.kijk.tv.data.Playable
import nl.kijk.tv.data.Store
import nl.kijk.tv.data.SubResult
import nl.kijk.tv.data.SubsQuery
import nl.kijk.tv.data.VodCategory
import nl.kijk.tv.phone.PhoneServer

private val PL_POLICIES = listOf("start" to "Bij elke start", "daily" to "1× per dag", "weekly" to "1× per week", "manual" to "Alleen handmatig")
private val EPG_POLICIES = listOf("start" to "Bij elke start", "twice" to "2× per dag", "daily" to "1× per dag", "manual" to "Alleen handmatig")
private val SUB_LANGS = listOf("nl" to "Nederlands", "en" to "Engels", "auto" to "Automatisch (nl, dan en)", "off" to "Uit")
private val SUB_SIZES = listOf("Klein", "Normaal", "Groot", "Extra groot")
private val SUB_BGS = listOf("Omlijnd", "Zwarte balk", "Half doorzichtig")
private val OFFSETS = listOf(0, 30, 60, 90, 120, -120, -90, -60, -30)

private fun <T> next(list: List<T>, cur: T) = list[(list.indexOf(cur) + 1).mod(list.size)]

@Composable
fun SettingsTab(vm: AppViewModel, nav: Nav) {
    val s = vm.store
    var tick by remember { mutableStateOf(0) }   // forceert opnieuw tekenen na een wijziging in Store
    fun changed() { tick++ }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(horizontal = 60.dp, vertical = 6.dp)) {
        item { Section("BRON") }
        item { tick.hashCode(); SettingRow("Instellen via telefoon of laptop", PhoneServer.address()?.let { "$it · code ${PhoneServer.code}" } ?: "Niet beschikbaar (geen netwerk)") { nav.push(Screen.Setup) } }
        item { tick.hashCode(); SettingRow("Playlist", s.playlistUrl.let { u -> nl.kijk.tv.data.Xtream.fromPlaylistUrl(u)?.let { "Xtream · ${it.host}" } ?: u.take(60) }) { nav.push(Screen.Setup) } }
        item { tick.hashCode(); SettingRow("Gids-link", s.epgUrl.ifBlank { "Uit de playlist" }.take(80)) { nav.push(Screen.Setup) } }
        item { tick.hashCode(); SettingRow("Playlist vernieuwen", PL_POLICIES.first { it.first == s.refreshPlaylist }.second) { s.refreshPlaylist = next(PL_POLICIES.map { it.first }, s.refreshPlaylist); changed() } }
        item { tick.hashCode(); SettingRow("Gids vernieuwen", EPG_POLICIES.firstOrNull { it.first == s.refreshEpg }?.second ?: "") { s.refreshEpg = next(EPG_POLICIES.map { it.first }, s.refreshEpg); changed() } }
        item { tick.hashCode(); SettingRow("Playlist nu vernieuwen", if (s.playlistUpdated > 0) "Bijgewerkt ${Format.dayTime(s.playlistUpdated)}" else "") { vm.load(forcePlaylist = true) } }
        item { tick.hashCode(); SettingRow("Gids nu bijwerken", if (vm.epgBusy) "Bezig…" else vm.epgStatsText()) { vm.refreshEpg() } }

        item { Section("ZENDERS EN GIDS") }
        item { tick.hashCode(); SettingRow("Zendergroepen", "${vm.groups.size} van ${vm.allGroups.size} zichtbaar") { nav.push(Screen.Groups("live")) } }
        item { tick.hashCode(); SettingRow("Filmcategorieën", if (vm.hiddenMovieCats.isEmpty()) "Alles zichtbaar" else "${vm.hiddenMovieCats.size} verborgen") { nav.push(Screen.Groups("movie")) } }
        item { tick.hashCode(); SettingRow("Seriecategorieën", if (vm.hiddenShowCats.isEmpty()) "Alles zichtbaar" else "${vm.hiddenShowCats.size} verborgen") { nav.push(Screen.Groups("show")) } }
        item { tick.hashCode(); SettingRow("Gids koppelen per zender", "${s.epgLinks.size} handmatig gekoppeld") { nav.push(Screen.EpgChannels) } }
        item { tick.hashCode(); SettingRow("Tijdverschuiving gids", if (vm.epgOffsetMinutes == 0) "Geen" else "%+d min".format(vm.epgOffsetMinutes)) { vm.setEpgOffset(next(OFFSETS, vm.epgOffsetMinutes)) } }

        item { Section("ANDERE TV") }
        item {
            vm.peersVersion.hashCode()
            val peers = vm.sync.peers.values.toList()
            SettingRow(if (vm.sync.paired) "Gekoppelde apparaten" else "Koppelen met een andere tv",
                if (vm.sync.paired && peers.isNotEmpty()) peers.joinToString(", ") { p -> p.name + (if (p.lastSync > 0) " (${Format.time(p.lastSync)})" else "") }
                else "Instellingen, favorieten en kijkvoortgang delen; kies dit op het nieuwe apparaat") { nav.push(Screen.SyncPair) }
        }
        if (vm.sync.paired) {
            item { SettingRow("Nu synchroniseren", vm.syncStatus ?: "Gaat ook vanzelf, elke paar seconden") { vm.syncNow() } }
            item { SettingRow("Ontkoppelen", "Dit apparaat deelt dan niets meer") { nav.confirm("Ontkoppelen?", "Instellingen blijven op dit apparaat staan, maar worden niet meer gedeeld.", yes = "Ontkoppelen") { vm.unpair() } } }
        }
        item { Section("KINDERSLOT") }
        item { tick.hashCode(); SettingRow("Kinderslot op volwassen groepen", if (vm.lockOn) "Aan" else "Uit") {
            if (vm.lockOn) nav.askPin(vm, sub = "Voer je pincode in om het kinderslot uit te zetten.") { vm.setLock(false) } else vm.setLock(true)
        } }
        item { tick.hashCode(); SettingRow("Pincode wijzigen", if (s.pinHash.isEmpty()) "Standaard (0000)" else "Eigen code") {
            nav.askPin(vm, "Huidige pincode", "Voer je huidige pincode in.") {
                nav.push(Screen.Pin("Nieuwe pincode", "Kies 4 cijfers.", { true }) { code ->
                    nav.pop()
                    if (code != null) nav.push(Screen.Pin("Herhaal pincode", "Voer de nieuwe code nog een keer in.", { it == code }) { c2 ->
                        nav.pop(); if (c2 != null) { s.pinHash = Store.hashPin(c2); changed() }
                    })
                })
            }
        } }
        if (vm.unlocked) item { tick.hashCode(); SettingRow("Opnieuw vergrendelen", "Nu ontgrendeld") { vm.relock() } }

        item { Section("ONDERTITELS") }
        val st = vm.subStyle
        item { tick.hashCode(); SettingRow("Taal", SUB_LANGS.first { it.first == st.language }.second) { vm.updateSubStyle(st.copy(language = next(SUB_LANGS.map { it.first }, st.language))) } }
        item { tick.hashCode(); SettingRow("Grootte", SUB_SIZES[st.size]) { vm.updateSubStyle(st.copy(size = (st.size + 1) % 4)) } }
        item { tick.hashCode(); SettingRow("Achtergrond", SUB_BGS[st.background]) { vm.updateSubStyle(st.copy(background = (st.background + 1) % 3)) } }
        item { tick.hashCode(); SettingRow("Positie", if (st.position == 1) "Hoger" else "Laag") { vm.updateSubStyle(st.copy(position = 1 - st.position)) } }
        item { tick.hashCode(); SettingRow("Automatisch zoeken bij OpenSubtitles", if (s.autoSubs) "Aan, als de film geen eigen ondertitels heeft" else "Uit") { s.autoSubs = !s.autoSubs; changed() } }
        item { tick.hashCode(); SettingRow("Dolby- en DTS-geluid omzetten", if (s.softAudio) "Aan: de app zet het om, werkt altijd" else "Uit: doorgeven aan tv of soundbar") { s.softAudio = !s.softAudio; changed() } }
        item { tick.hashCode(); SettingRow("OpenSubtitles-account", if (vm.subs.configured) "Ingesteld" + (if (s.osUser.isNotBlank()) " · ${s.osUser}" else "") else "Niet ingesteld") { nav.push(Screen.OsConfig) } }

        item { Section("KIJKGESCHIEDENIS") }
        listOf("live" to "Laatst bekeken zenders wissen", "movie" to "Films: voortgang wissen", "show" to "Series: voortgang wissen", "all" to "Alles wissen").forEach { (k, t) ->
            item { tick.hashCode(); SettingRow(t, "") { nav.confirm(t, "Dit kan niet ongedaan worden gemaakt.") { vm.clearHistory(k); changed() } } }
        }

        item { Section("OVER") }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            SettingRow("Kijk bijwerken", vm.updateStatus ?: "Versie ${BuildConfig.VERSION_NAME} · OK om te controleren op een nieuwe versie") {
                if (vm.update != null) vm.installUpdate(ctx) else vm.checkUpdate(manual = true)
            }
        }
        item { tick.hashCode(); SettingRow("Kijk", "Versie ${BuildConfig.VERSION_NAME} · ${vm.allChannels.size} zenders") { } }
    }
}

// ---------------------------------------------------------------- groepen en categorieën

@Composable
fun GroupsScreen(vm: AppViewModel, nav: Nav, kind: String) {
    BackHandler { nav.pop() }
    var cats by remember { mutableStateOf<List<VodCategory>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(kind) {
        cats = when (kind) {
            "live" -> vm.allGroups.map { VodCategory(it, it) }
            "movie" -> try { vm.movieCategories() } catch (_: Exception) { emptyList() }
            else -> try { vm.showCategories() } catch (_: Exception) { emptyList() }
        }
    }
    val hidden = when (kind) { "live" -> vm.hidden; "movie" -> vm.hiddenMovieCats; else -> vm.hiddenShowCats }
    fun set(v: Set<String>) = when (kind) { "live" -> vm.updateHiddenGroups(v); "movie" -> vm.updateHiddenMovieCats(v); else -> vm.updateHiddenShowCats(v) }
    val title = when (kind) { "live" -> "Zendergroepen"; "movie" -> "Filmcategorieën"; else -> "Seriecategorieën" }
    val all = cats ?: emptyList()
    val shown = all.filter { q.isBlank() || q.trim().lowercase() in it.name.lowercase() }

    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 40.dp, vertical = Kijk.SafeV)) {
        Label(title, size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label("${all.count { it.id !in hidden }} van ${all.size} zichtbaar. Verborgen groepen verdwijnen uit Live, de gids en het zoeken.", size = Kijk.Caption, color = Kijk.Secondary)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("", q, { q = it }, placeholder = "Zoeken…", modifier = Modifier.width(360.dp), last = true)
            PillButton("Alles aan") { set(hidden - shown.map { it.id }.toSet()) }
            PillButton("Alles uit") { set(hidden + shown.map { it.id }) }
        }
        if (cats == null) { EmptyState("Laden…"); return@Column }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(shown) { c ->
                val on = c.id !in hidden
                FocusCard(onClick = { set(if (on) hidden + c.id else hidden - c.id) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { f ->
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label(if (on) "☑" else "☐", Modifier.width(32.dp), size = Kijk.Headline, color = if (on) (if (f) Color(0xFF0050A0) else Kijk.Accent) else secondary(f))
                        Label((if (vm.isLocked(c.name)) "🔒 " else "") + c.name, Modifier.weight(1f), weight = Kijk.Medium)
                        if (kind == "live") Label("${vm.allChannels.count { it.group == c.id }} zenders", size = Kijk.Caption, color = secondary(f))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- gids koppelen

@Composable
fun EpgChannelsScreen(vm: AppViewModel, nav: Nav) {
    var q by remember { mutableStateOf("") }
    var onlyMissing by remember { mutableStateOf(false) }
    val links = vm.store.epgLinks
    val shown = remember(q, onlyMissing, vm.dataVersion, vm.epgMatch) {
        vm.channels.filter { (q.isBlank() || q.trim().lowercase() in it.name.lowercase()) && (!onlyMissing || it.key !in vm.epgMatch) }.take(400)
    }
    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 40.dp, vertical = Kijk.SafeV)) {
        Label("Gids koppelen", size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label("Kies een zender om zelf de juiste gids te kiezen. Handmatige koppelingen blijven bewaard als je de playlist vernieuwt.", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("", q, { q = it }, placeholder = "Zender zoeken…", modifier = Modifier.width(360.dp), last = true)
            PillButton("Alleen zonder gids", selected = onlyMissing) { onlyMissing = !onlyMissing }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            items(shown) { ch ->
                FocusCard(onClick = { nav.push(Screen.EpgLink(ch)) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { f ->
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(ch.logo, ch.name, Modifier.size(52.dp, 32.dp))
                        Spacer(Modifier.width(12.dp))
                        Label(ch.name, Modifier.weight(1f), weight = Kijk.Medium)
                        val cid = vm.epgMatch[ch.key]
                        Label(when { links.containsKey(ch.name) -> "✎ $cid"; cid != null -> "✓ $cid"; else -> "Geen gids" }, Modifier.width(300.dp), size = Kijk.Caption,
                            color = if (cid == null) Kijk.Live else secondary(f))
                    }
                }
            }
        }
    }
}

@Composable
fun EpgLinkScreen(vm: AppViewModel, nav: Nav, channel: Channel) {
    var q by remember { mutableStateOf(shortName(channel.name).replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC)\b""", RegexOption.IGNORE_CASE), "").trim()) }
    var hits by remember { mutableStateOf<List<EpgHit>?>(null) }
    LaunchedEffect(q) { delay(350); hits = if (q.trim().length < 2) emptyList() else vm.epgSearch(q) }
    val linked = vm.store.epgLinks[channel.name]
    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 40.dp, vertical = Kijk.SafeV)) {
        Label("Gids voor ${channel.name}", size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label("Nu gekoppeld: ${vm.epgMatch[channel.key] ?: "niets"}${if (linked != null) " (handmatig)" else " (automatisch)"}", size = Kijk.Caption, color = Kijk.Secondary)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("", q, { q = it }, placeholder = "Gidsnaam zoeken…", modifier = Modifier.width(360.dp), last = true)
            if (linked != null) PillButton("Weer automatisch") { vm.linkEpg(channel.name, null); nav.pop() }
        }
        val h = hits
        when {
            h == null -> EmptyState("Zoeken…")
            h.isEmpty() -> EmptyState("Niets gevonden", "Probeer een kortere naam, bijvoorbeeld zonder 'HD' of landcode.")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(h) { hit ->
                    FocusCard(onClick = { vm.linkEpg(channel.name, hit.cid); nav.pop() }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { f ->
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            Row {
                                Label(hit.names.firstOrNull() ?: hit.cid, Modifier.weight(1f), size = Kijk.Headline, weight = Kijk.Semibold)
                                Label("${hit.count} programma's", size = Kijk.Caption, color = secondary(f))
                            }
                            Label("${hit.cid}${if (hit.names.size > 1) " · " + hit.names.drop(1).joinToString(", ") else ""}", size = Kijk.Caption, color = secondary(f))
                            hit.now?.let { Label("Nu: $it", size = Kijk.Caption, color = secondary(f)) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- OpenSubtitles

@Composable
fun OsConfigScreen(vm: AppViewModel, nav: Nav) {
    val s = vm.store
    var key by remember { mutableStateOf(s.osKey) }
    var user by remember { mutableStateOf(s.osUser) }
    var pass by remember { mutableStateOf(s.osPass) }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save() { s.osKey = key.trim().replace(Regex("""\s"""), ""); s.osUser = user.trim(); s.osPass = pass }
    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 120.dp, vertical = Kijk.SafeV + 10.dp)) {
        Label("OpenSubtitles", size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label("Lange sleutels typ je makkelijker via je telefoon: Instellingen › Instellen via telefoon (${PhoneServer.address() ?: "geen netwerk"}).", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2)
        Spacer(Modifier.height(14.dp))
        Field("API-sleutel (Consumer)", key, { key = it }, placeholder = "Van opensubtitles.com › API consumers")
        Field("Gebruikersnaam", user, { user = it }, placeholder = "Geen e-mailadres")
        Field("Wachtwoord", pass, { pass = it }, secret = true, last = true, keyboard = KeyboardType.Password)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PillButton("Opslaan en testen") {
                save(); result = "Testen…"
                scope.launch { result = withContext(Dispatchers.IO) { vm.subs.test() } }
            }
            PillButton("Klaar") { save(); nav.pop() }
        }
        result?.let { Label(it, Modifier.padding(top = 14.dp), size = Kijk.Body, color = if (it.startsWith("✕")) Kijk.Live else Color.White, maxLines = 3) }
    }
}

@Composable
fun SubSearchScreen(vm: AppViewModel, item: Playable, onClose: () -> Unit, onPick: (Long) -> Unit) {
    val base = vm.subStyle.language.takeIf { it == "nl" || it == "en" } ?: "nl"
    var lang by remember { mutableStateOf(base) }
    var q by remember { mutableStateOf(item.subs?.query ?: item.title) }
    var results by remember { mutableStateOf<List<SubResult>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lang, q) {
        delay(300); results = null; error = null
        val query = (item.subs ?: SubsQuery()).let { if (q != (it.query ?: item.title)) SubsQuery(query = q, season = it.season, episode = it.episode) else it.copy(query = it.query ?: item.title) }
        results = try { withContext(Dispatchers.IO) { vm.subs.search(query, lang).filter { it.fileId > 0 }.distinctBy { it.fileId } } } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName; emptyList() }
    }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 40.dp, vertical = Kijk.SafeV)) {
        Label("Ondertitels zoeken", size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label(listOfNotNull(item.title, item.subtitle).joinToString(" · ") + (vm.subs.remaining?.let { " · nog $it downloads vandaag" } ?: ""), size = Kijk.Caption, color = Kijk.Secondary)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("", q, { q = it }, modifier = Modifier.width(380.dp), last = true)
            listOf("nl" to "Nederlands", "en" to "Engels").forEach { (c, n) -> SmallPill(n, selected = lang == c) { lang = c } }
        }
        val r = results
        when {
            !vm.subs.configured -> EmptyState("OpenSubtitles is niet ingesteld", "Vul je API-sleutel in bij Instellingen › OpenSubtitles-account.")
            r == null -> EmptyState("Zoeken…")
            r.isEmpty() -> EmptyState(if (error != null) "Zoeken mislukt" else "Geen ondertitels gevonden", error ?: "Probeer een andere taal of pas de zoekterm aan.")
            else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(r, key = { "f" + it.fileId }) { s ->
                    FocusCard(onClick = { onClose(); onPick(s.fileId) }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { f ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(s.release, Modifier.weight(1f), weight = Kijk.Medium, maxLines = 2)
                            Label(listOfNotNull(if (s.cached) "✓ gedownload" else null, if (s.hearingImpaired) "SDH" else null, if (s.ai) "AI-vertaald" else null, "${s.downloads}×").joinToString(" · "),
                                size = Kijk.Caption, color = secondary(f))
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- pincode en bevestigen

@Composable
fun PinScreen(screen: Screen.Pin) {
    var code by remember(screen) { mutableStateOf("") }
    var wrong by remember(screen) { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(screen) { delay(60); runCatching { first.requestFocus() } }
    fun press(d: Char) {
        if (code.length >= 4) return
        code += d; wrong = false
        if (code.length == 4) { if (screen.check(code)) screen.onResult(code) else { wrong = true; code = "" } }
    }
    Box(Modifier.fillMaxSize().background(Kijk.Background).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val d = digitOf(e) ?: return@onPreviewKeyEvent false
        press(d); true
    }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(screen.title, size = Kijk.LargeTitle, weight = Kijk.Bold)
            Label(if (wrong) "Onjuiste code, probeer het opnieuw." else screen.sub, size = Kijk.Body, color = if (wrong) Kijk.Live else Kijk.Secondary)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i -> Box(Modifier.size(18.dp).background(if (i < code.length) Color.White else Color(0x33FFFFFF), RoundedCornerShape(50))) }
            }
            Spacer(Modifier.height(22.dp))
            listOf("123", "456", "789", "⌫0").forEachIndexed { r, row ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (r == 3 && row.length == 2) Spacer(Modifier.width(0.dp))
                    row.forEachIndexed { i, ch ->
                        FocusCard(onClick = { if (ch == '⌫') code = code.dropLast(1) else press(ch) },
                            modifier = Modifier.size(76.dp, 56.dp).then(if (r == 0 && i == 0) Modifier.focusRequester(first) else Modifier),
                            focusedScale = 1.08f) { Label(ch.toString(), Modifier.align(Alignment.Center), size = Kijk.Title, weight = Kijk.Semibold) }
                    }
                }
            }
            Label("Je kunt ook de cijfertoetsen van de afstandsbediening gebruiken.", size = Kijk.Caption, color = Kijk.Secondary)
        }
    }
}

@Composable
fun ConfirmScreen(screen: Screen.Confirm) {
    val no = remember { FocusRequester() }
    LaunchedEffect(screen) { delay(60); runCatching { no.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Kijk.Background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(screen.title, size = Kijk.LargeTitle, weight = Kijk.Bold)
            Spacer(Modifier.height(8.dp))
            Label(screen.text, size = Kijk.Body, color = Kijk.Secondary, maxLines = 3)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PillButton("Annuleren", Modifier.focusRequester(no)) { screen.onResult(false) }
                PillButton(screen.yes) { screen.onResult(true) }
            }
        }
    }
}

// ---------------------------------------------------------------- koppelen met een andere tv

@Composable
fun SyncPairScreen(vm: AppViewModel, nav: Nav) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // gevonden apparaten verversen
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val found = remember(tick) { vm.sync.discovered.values.sortedBy { it.name } }
    Row(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 20.dp, vertical = Kijk.SafeV)) {
        Column(Modifier.weight(1f)) {
            Label("Koppelen met een andere tv", size = Kijk.LargeTitle, weight = Kijk.Bold)
            Label("Doe dit op het nieuwe apparaat. Het neemt dan alle instellingen, favorieten en kijkvoortgang over van de andere tv. Daarna blijven ze vanzelf gelijk.",
                size = Kijk.Body, color = Kijk.Secondary, maxLines = 3)
            Spacer(Modifier.height(14.dp))
            Section("GEVONDEN IN JE NETWERK")
            if (found.isEmpty()) Label("Zoeken… Staat Kijk open op de andere tv? Je kunt ook hiernaast het adres invullen.", Modifier.padding(6.dp), size = Kijk.Body, color = Kijk.Secondary, maxLines = 2)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                items(found) { f ->
                    FocusCard(onClick = { address = f.address }, selected = address == f.address, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { foc ->
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label("📺  " + f.name, Modifier.weight(1f), size = Kijk.Headline, weight = Kijk.Semibold)
                            Label(f.address + if (vm.sync.peers.containsKey(f.id)) " · gekoppeld" else "", size = Kijk.Caption, color = secondary(foc))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(40.dp))
        Column(Modifier.width(380.dp)) {
            Spacer(Modifier.height(60.dp))
            Field("Adres van de andere tv", address, { address = it }, "192.168.1.239:8080", KeyboardType.Uri)
            Field("Code op de andere tv", code, { code = it.filter { c -> c.isDigit() }.take(6) }, "Instellingen › Instellen via telefoon", KeyboardType.Number, last = true)
            Spacer(Modifier.height(8.dp))
            PillButton("Koppelen") { if (address.isNotBlank() && code.length == 6) vm.pairWith(address, code) }
            Spacer(Modifier.height(10.dp))
            Label(vm.syncStatus ?: "De code staat op de andere tv bij Instellingen › Instellen via telefoon of laptop.", size = Kijk.Body,
                color = if (vm.syncStatus?.startsWith("Koppelen mislukt") == true) Kijk.Live else Kijk.Secondary, maxLines = 3)
            Spacer(Modifier.height(18.dp))
            Label("Deze tv: ${vm.sync.deviceName} · ${PhoneServer.address() ?: "geen netwerk"} · code ${PhoneServer.code}", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2)
        }
    }
}
