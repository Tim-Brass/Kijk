@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.phone.PhoneServer
import java.net.URLEncoder

fun qrBitmap(text: String, size: Int = 512): ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}

@Composable
fun QrCode(text: String, modifier: Modifier = Modifier.size(220.dp)) {
    val bmp = remember(text) { qrBitmap(text) }
    Image(bmp, contentDescription = text, modifier = modifier.clip(RoundedCornerShape(12.dp)), filterQuality = FilterQuality.None)
}

/** Instellen: links een QR-code voor de telefoon (makkelijk voor lange links en sleutels), rechts invullen met de afstandsbediening. */
@Composable
fun SetupScreen(vm: AppViewModel, onPair: () -> Unit = {}, onGitHub: () -> Unit = {}, onDone: () -> Unit) {
    var xtream by rememberSaveable { mutableStateOf(false) }
    var m3u by rememberSaveable { mutableStateOf(vm.store.playlistUrl) }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var epg by rememberSaveable { mutableStateOf(vm.store.epgUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf(PhoneServer.url()) }
    var test by remember { mutableStateOf("Controleren…") }
    var hit by remember { mutableStateOf(PhoneServer.lastHit) }
    LaunchedEffect(Unit) {   // netwerk kan later pas klaar zijn; elke 3 s opnieuw kijken
        while (true) {
            url = PhoneServer.url(); hit = PhoneServer.lastHit
            test = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PhoneServer.selfTest() }
            delay(3000)
        }
    }

    Row(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 20.dp, vertical = Kijk.SafeV), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(330.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label("Instellen met je telefoon", size = Kijk.Title, weight = Kijk.Bold)
            Spacer(Modifier.height(6.dp))
            Label("Scan de code en vul alles in op je telefoon: playlist, gids en OpenSubtitles.", size = Kijk.Body, color = Kijk.Secondary, maxLines = 3)
            Spacer(Modifier.height(16.dp))
            val u = url
            if (u != null) {
                QrCode(u)
                Spacer(Modifier.height(10.dp))
                Label(PhoneServer.address() ?: "", size = Kijk.Headline, weight = Kijk.Semibold)
                Label("Code: ${PhoneServer.code}", size = Kijk.Title, weight = Kijk.Bold, color = Kijk.Accent)
                Spacer(Modifier.height(6.dp))
                Label("Vanuit de webversie op je laptop: Instellingen › Naar tv sturen, met dit adres en deze code.", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 3)
                Spacer(Modifier.height(10.dp))
                Label(test, size = Kijk.Caption, color = if (test.startsWith("✓")) androidx.compose.ui.graphics.Color(0xFF30D158) else Kijk.Live, maxLines = 2)
                Label(hit?.let { (t, w) -> "Laatst ontvangen ${Format.time(t)}: $w" } ?: "Nog niets ontvangen van een telefoon of laptop", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2)
            } else Label("Geen netwerkverbinding gevonden.", color = Kijk.Live)
        }
        Spacer(Modifier.width(40.dp))
        Box(Modifier.width(1.dp).fillMaxHeight(0.7f).background(Kijk.Separator))
        Spacer(Modifier.width(40.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Label(if (vm.configured) "Playlist wijzigen" else "Welkom bij Kijk", size = Kijk.LargeTitle, weight = Kijk.Bold)
            Label("Of vul het hier in met de afstandsbediening.", size = Kijk.Body, color = Kijk.Secondary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill("M3U-link", selected = !xtream) { xtream = false }
                SmallPill("Xtream-login", selected = xtream) { xtream = true }
            }
            Spacer(Modifier.height(12.dp))
            if (xtream) {
                Field("Server", server, { server = it }, "http://voorbeeld.tv:8080", KeyboardType.Uri)
                Field("Gebruikersnaam", user, { user = it })
                Field("Wachtwoord", pass, { pass = it }, keyboard = KeyboardType.Password, secret = true)
            } else Field("Playlist-link (M3U)", m3u, { m3u = it }, "http://…/playlist.m3u", KeyboardType.Uri)
            Field("Gids-link (XMLTV, optioneel)", epg, { epg = it }, if (xtream) "Leeg = gids van je Xtream-server" else "Leeg = gids-link uit de playlist", KeyboardType.Uri, last = true)
            error?.let { Label(it, Modifier.padding(top = 4.dp), size = Kijk.Body, color = Kijk.Live, maxLines = 2) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Opslaan en laden") save@{
                    val playlist: String
                    if (xtream) {
                        if (server.isBlank() || user.isBlank() || pass.isBlank()) { error = "Vul server, gebruikersnaam en wachtwoord in."; return@save }
                        val host = server.trim().let { if (it.startsWith("http")) it else "http://$it" }.trimEnd('/')
                        playlist = "$host/get.php?username=${URLEncoder.encode(user.trim(), "UTF-8")}&password=${URLEncoder.encode(pass.trim(), "UTF-8")}&type=m3u_plus&output=ts"
                    } else {
                        playlist = m3u.trim()
                        if (!playlist.startsWith("http")) { error = "Een playlist-link begint met http:// of https://"; return@save }
                    }
                    vm.saveSource(playlist, epg.trim()); onDone()
                }
                if (vm.configured) PillButton("Annuleren") { onDone() }
                PillButton("Overnemen via GitHub") { onGitHub() }
            }
        }
    }
}

// ---------------------------------------------------------------- zoeken

@Composable
fun SearchTab(vm: AppViewModel, nav: Nav) {
    var q by rememberSaveable { mutableStateOf("") }
    // wat zoek je: 0 films, 1 series, 2 zenders, 3 in de gids (onthouden)
    var kind by remember { mutableStateOf(vm.store.searchKind.coerceIn(0, 3)) }
    val vodKind = kind <= 1
    var res by remember { mutableStateOf<nl.kijk.tv.SearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var vodRes by remember { mutableStateOf<Pair<List<nl.kijk.tv.data.Movie>, List<nl.kijk.tv.data.Show>>?>(null) }
    LaunchedEffect(Unit) { vm.ensureVodIndex() }
    LaunchedEffect(q, vm.dataVersion, vodKind) {
        if (vodKind || q.trim().length < 2) { res = null; busy = false; return@LaunchedEffect }
        busy = true
        delay(350)
        res = try { vm.search(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        busy = false
    }
    LaunchedEffect(q, vm.vodIndexReady, vodKind) {
        vodRes = null
        if (!vodKind || q.trim().length < 2) return@LaunchedEffect
        delay(350)
        vodRes = try { vm.searchVod(q) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    }
    var dots by remember { mutableStateOf(0) }
    val waitingVod = vodKind && vm.vod != null && !vm.vodIndexReady
    LaunchedEffect(busy, waitingVod) { while (busy || waitingVod) { delay(400); dots = (dots + 1) % 4 } }
    val kinds = listOf("Films", "Series", "Zenders", "In de gids")
    val placeholder = when (kind) { 0 -> "Zoek een film…"; 1 -> "Zoek een serie…"; 2 -> "Zoek een zender…"; else -> "Zoek een programma in de gids…" }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field("", q, { q = it }, placeholder = placeholder, modifier = Modifier.width(560.dp), last = true)
            Spacer(Modifier.width(16.dp))
            val st = when {
                busy -> "Zoeken" + ".".repeat(dots)
                q.trim().length >= 2 && waitingVod -> (vm.vodIndexStatus ?: "Films en series voorbereiden").trimEnd('…') + ".".repeat(dots)
                else -> ""
            }
            Label(st, size = Kijk.Body, color = Kijk.Accent)
        }
        // keuze in lijnstijl
        Row(Modifier.fillMaxWidth().padding(top = 6.dp).baseline(), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.Bottom) {
            kinds.forEachIndexed { i, t -> LineText(t, active = kind == i, size = Kijk.Body) { kind = i; vm.store.searchKind = i } }
        }
        Spacer(Modifier.height(8.dp))
        val enough = q.trim().length >= 2
        when {
            !enough -> EmptyState(kinds[kind], when (kind) {
                0, 1 -> "Typ minstens twee letters van de titel."
                2 -> "Typ minstens twee letters van de zendernaam."
                else -> "Typ minstens twee letters. Programma's worden gezocht van een week terug tot twee dagen vooruit."
            })
            vodKind -> {
                val list: List<Any>? = vodRes?.let { if (kind == 0) it.first else it.second }
                when {
                    list == null -> EmptyState(if (waitingVod) "Even geduld…" else "Zoeken…", "Bij de eerste keer zoeken haalt Kijk de lijst met films en series op; dat kan even duren.")
                    list.isEmpty() -> EmptyState("Geen ${kinds[kind].lowercase()} gevonden voor \"${q.trim()}\"",
                        "De zoeklijst bevat ${vm.movieIndex.size} films en ${vm.showIndex.size} series.")
                    else -> Column {
                        Label("${list.size}${if (list.size >= 60) "+" else ""} ${if (kind == 0) (if (list.size == 1) "film" else "films") else (if (list.size == 1) "serie" else "series")} gevonden", Modifier.padding(start = 8.dp), size = Kijk.Caption, color = Kijk.Secondary)
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(118.dp), modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp),
                        ) {
                            items(list.size) { i ->
                                when (val it = list[i]) {
                                    is nl.kijk.tv.data.Movie -> PosterCard(it.name, it.poster, null, fav = vm.isFav(it), onLongClick = { vm.toggleFav(it) }, fallback = { vm.posterFallback(it) }, known = vm.knownPoster(it)) { vm.addRecentMovie(it); nav.push(Screen.MovieDetail(it)) }
                                    is nl.kijk.tv.data.Show -> PosterCard(it.name, it.poster, null, fav = vm.isFav(it), onLongClick = { vm.toggleFav(it) }, fallback = { vm.posterFallback(it) }, known = vm.knownPoster(it)) { vm.addRecentShow(it); nav.push(Screen.ShowDetail(it)) }
                                }
                            }
                        }
                    }
                }
            }
            else -> {
                val r = res
                when {
                    r == null -> EmptyState(if (busy) "Zoeken…" else "")
                    kind == 2 && r.channels.isEmpty() -> EmptyState("Geen zenders gevonden voor \"${q.trim()}\"")
                    kind == 3 && r.programmes.isEmpty() -> EmptyState("Niets in de gids gevonden voor \"${q.trim()}\"")
                    else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                        if (kind == 2) items(r.channels) { ch ->
                            ChannelRow(vm, ch, onClick = { nav.push(Screen.Live(r.channels, r.channels.indexOf(ch))) }, onLongClick = { vm.toggleFavorite(ch) })
                        }
                        if (kind == 3) items(r.programmes) { (ch, p) ->
                            val live = p.isLive(vm.now); val back = vm.canCatchup(ch, p)
                            FocusCard(onClick = {
                                when {
                                    live -> nav.push(Screen.Live(vm.channels, vm.channels.indexOf(ch).coerceAtLeast(0)))
                                    back -> nav.push(Screen.Vod(listOf(catchupPlayable(vm, ch, p)), 0, 0L, null))
                                }
                            }, modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f) { f ->
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ChannelLogo(ch.logo, ch.name, Modifier.size(52.dp, 32.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (live) { LiveBadge(); Spacer(Modifier.width(6.dp)) }
                                            if (back) Label("↺ ", color = if (f) Color(0xFF0050A0) else Kijk.Accent, weight = Kijk.Bold)
                                            Label(p.title, size = Kijk.Headline, weight = Kijk.Semibold)
                                        }
                                        Label("${shortName(ch.name)} · ${Format.dayLabel(p.start, vm.now)} ${Format.range(p.start, p.stop)}", size = Kijk.Caption, color = secondary(f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
