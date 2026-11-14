@file:OptIn(ExperimentalTvMaterial3Api::class)

package nl.kijk.tv.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.Screen
import nl.kijk.tv.data.Channel
import nl.kijk.tv.data.Movie
import nl.kijk.tv.data.Show
import nl.kijk.tv.data.Sky
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val NL = Locale("nl", "NL")

/**
 * Thuis: groet, tijd en datum, het weer (week), Verder kijken, Nu op je favorieten, Nieuw binnen
 * en onderaan een vaste nieuws-lichtkrant. Alleen het weericoon, de lichtkrant en de oogjes bewegen.
 */
@Composable
fun HomeTab(vm: AppViewModel, nav: Nav, hs: HomeState, onPlay: ((List<Channel>, Int) -> Unit)? = null) {
    LaunchedEffect(Unit) { while (true) { vm.refreshHome(); delay(5 * 60_000L) } }
    // één frameklok voor alles wat beweegt (alleen gelezen tijdens het tekenen)
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }

    val favs = remember(vm.channels, vm.favorites) { vm.channels.filter { it.key in vm.favorites && !vm.isAdult(it.group) && !vm.isAdult(it.name) } }   // nooit volwassen zenders op Thuis
    val newest = vm.newest   // één keer berekend en bewaard in het ViewModel

    // achtergrond: titel waar je op staat (na even wachten), anders de nieuwste
    var focusItem by remember { mutableStateOf<Any?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(focusItem, newest) {
        val it = focusItem ?: newest.firstOrNull() ?: run { hs.backdrop = null; return@LaunchedEffect }
        delay(600)
        hs.backdrop = when (it) { is Movie -> vm.movieInfo(it)?.backdrop ?: vm.knownPoster(it) ?: it.poster; is Show -> vm.knownPoster(it) ?: it.poster; else -> null }
    }

    fun openMovie(m: Movie, resume: Boolean) {
        vm.addRecentMovie(m)
        val pos = vm.store.progress(movieKey(m))?.takeIf { it.resumable }?.pos ?: 0L
        if (resume && pos > 0) nav.push(Screen.Vod(listOf(moviePlayable(m, null)), 0, pos, null)) else nav.push(Screen.MovieDetail(m))
    }
    fun openShow(s: Show) { vm.addRecentShow(s); nav.push(Screen.ShowDetail(s)) }

    Column(Modifier.fillMaxSize()) {
        // ---- groet + tijd links, weer rechts
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {   // hoogte past zich aan: niets valt eraf
            Column(Modifier.weight(1f)) {
                Label(greeting(vm.now), size = 30.sp, weight = Kijk.Bold, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Label(Format.time(vm.now), size = 30.sp, weight = androidx.compose.ui.text.font.FontWeight.Light, color = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Label(dateLabel(vm.now), Modifier.padding(bottom = 5.dp), size = Kijk.Body, color = Kijk.Secondary)
                }
            }
            WeatherBlock(vm, frame)
        }
        // ---- rijen
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (favs.isNotEmpty()) item {
                HomeRow("Nu op je favorieten") {
                    items(favs) { ch ->
                        FavChannelCard(vm, ch) {
                            val idx = vm.channels.indexOf(ch).coerceAtLeast(0)
                            if (onPlay != null) onPlay(vm.channels, idx) else nav.push(Screen.Live(vm.channels, idx))
                        }
                    }
                }
            }
            val f1 = vm.f1; val psv = vm.psvMatch; val table = vm.eredivisie
            if (f1 != null || psv != null || table != null) item {
                HomeRow("Sport") {
                    // OK: bezig → zender aan; nog niet begonnen → herinnering; niets gevonden → uitleg
                    fun act(tv: nl.kijk.tv.SportTv?, start: Long, live: Boolean, title: String) {
                        if (tv == null) { vm.notice = "Niet gevonden in de gids. Kies een vaste zender in Instellingen › Zenders en gids."; return }
                        if (live) {
                            val list = if (tv.channel in vm.channels) vm.channels else listOf(tv.channel)
                            val idx = list.indexOf(tv.channel).coerceAtLeast(0)
                            if (onPlay != null) onPlay(list, idx) else nav.push(Screen.Live(list, idx))
                        } else if (vm.now < start) vm.toggleSportReminder(tv, start, title)
                    }
                    if (f1 != null) item {
                        val next = f1.sessions.firstOrNull { it.start + 2 * 3600_000L > vm.now }
                        F1Card(vm, f1, vm.f1Tv) { if (next != null) act(vm.f1Tv, next.start, sportLive(vm.f1Tv, next.start, next.name, vm.now), "F1: ${next.name}") }
                    }
                    if (table != null) item { TableCard(table) }
                    if (psv != null) item { MatchCard(vm, psv, vm.psvTv) { act(vm.psvTv, psv.start, sportLive(vm.psvTv, psv.start, "Wedstrijd", vm.now), "${psv.home} – ${psv.away}") } }
                }
            }
            if (newest.isNotEmpty()) item {
                HomeRow("Nieuwste films en series") {
                    items(newest) { item ->
                        when (item) {
                            is Movie -> PosterCard(item.name, item.poster, null, Modifier.width(92.dp), fav = vm.isFav(item), onLongClick = { vm.toggleFav(item) },
                                onFocus = { focusItem = item }, fallback = { vm.posterFallback(item) }, known = vm.knownPoster(item)) { openMovie(item, resume = false) }
                            is Show -> PosterCard(item.name, item.poster, null, Modifier.width(92.dp), fav = vm.isFav(item), onLongClick = { vm.toggleFav(item) },
                                onFocus = { focusItem = item }, fallback = { vm.posterFallback(item) }, known = vm.knownPoster(item), badge = if (vm.isNew(item)) "Nieuw" else null) { openShow(item) }
                        }
                    }
                }
            }
            if (favs.isEmpty() && newest.isEmpty()) item {
                Label(if (vm.progress != null) "Even geduld, de lijsten worden geladen…" else "Kies Live, Gids, Films of Series in het menu.",
                    Modifier.padding(12.dp), color = Kijk.Secondary)
            }
        }
    }
}

@Composable
private fun HomeRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        Label(title, Modifier.padding(start = 12.dp, bottom = 4.dp), size = Kijk.Body, weight = Kijk.Semibold, color = Color(0xFFD1D1D6))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), content = content)
    }
}

@Composable
private fun FavChannelCard(vm: AppViewModel, ch: Channel, onClick: () -> Unit) {
    val cur = vm.nowNext(ch).firstOrNull()?.takeIf { it.isLive(vm.now) }
    FocusCard(onClick = onClick, modifier = Modifier.width(230.dp).height(82.dp), focusedScale = 1.06f) { f ->
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(ch.logo, ch.name, Modifier.size(width = 56.dp, height = 36.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Label("${ch.number} · ${ch.name}", size = Kijk.Caption, weight = Kijk.Semibold, color = if (f) Color.Black else Color.White)
                Label(cur?.title ?: "Geen gidsinformatie", Modifier.padding(top = 2.dp), size = Kijk.Caption, color = secondary(f))
                if (cur != null) ProgressBar(cur.progress(vm.now), Modifier.fillMaxWidth().padding(top = 6.dp), focused = f, height = 3.dp)
            }
        }
    }
}

private fun greeting(now: Long): String = when (Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)) {
    in 6..11 -> "Goedemorgen"
    in 12..17 -> "Goedemiddag"
    in 18..23 -> "Goedenavond"
    else -> "Goedenacht"
}

private fun dateLabel(now: Long) = SimpleDateFormat("EEEE d MMMM", NL).format(now)

private fun dayShort(date: String): String = try {
    SimpleDateFormat("EEE", NL).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!).trimEnd('.')
} catch (_: Exception) { "" }

// ---------------------------------------------------------------- weer

@Composable
private fun WeatherBlock(vm: AppViewModel, frame: MutableLongState) {
    val w = vm.weather ?: return
    // compact: even hoog als de groet; icoon + temperatuur, daarnaast de week in smalle kolommetjes
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 58.dp, height = 50.dp)) { drawSky(w.sky, !w.isDay, frame.longValue / 1e9f, animate = true) }
        Spacer(Modifier.width(6.dp))
        Column {
            Label("${w.temp}°", size = 30.sp, weight = androidx.compose.ui.text.font.FontWeight.Light, color = Color.White, maxLines = 1)
            Label(w.text, size = Kijk.Caption, weight = Kijk.Semibold, color = Color.White, maxLines = 1)
        }
        Spacer(Modifier.width(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            w.days.forEachIndexed { i, d ->
                Column(
                    Modifier.width(40.dp).background(if (i == 0) Kijk.Accent.copy(alpha = 0.35f) else Color(0x99141419), RoundedCornerShape(7.dp)).padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Label(dayShort(d.date), size = 12.sp, weight = Kijk.Semibold, color = Color.White, maxLines = 1)
                    Canvas(Modifier.padding(vertical = 1.dp).size(width = 22.dp, height = 18.dp)) { drawSky(d.sky, false, 0f, animate = false) }
                    Label("${d.max}°", size = 13.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
                    Label("${d.min}°", size = 12.sp, weight = Kijk.Semibold, color = Color(0xFFC7C7CC), maxLines = 1)
                }
            }
        }
    }
}

private val SunYellow = Color(0xFFFFD60A)
private val CloudLight = Color(0xFFE5E5EA)
private val CloudGrey = Color(0xFF8E8E93)
private val DropBlue = Color(0xFF0A84FF)

private fun DrawScope.cloud(cx: Float, cy: Float, w: Float, c: Color) {
    val h = w * 0.34f
    drawRoundRect(c, Offset(cx - w / 2, cy - h / 2), Size(w, h), CornerRadius(h / 2))
    drawCircle(c, w * 0.22f, Offset(cx - w * 0.16f, cy - h * 0.45f))
    drawCircle(c, w * 0.28f, Offset(cx + w * 0.1f, cy - h * 0.6f))
}

private fun DrawScope.sun(cx: Float, cy: Float, r: Float, t: Float, animate: Boolean) {
    rotate(if (animate) t * 15f else 0f, Offset(cx, cy)) {
        for (i in 0 until 8) {
            val a = i * PI.toFloat() / 4
            drawLine(SunYellow, Offset(cx + cos(a) * r * 1.35f, cy + sin(a) * r * 1.35f), Offset(cx + cos(a) * r * 1.85f, cy + sin(a) * r * 1.85f), r * 0.28f, StrokeCap.Round)
        }
    }
    drawCircle(SunYellow, r, Offset(cx, cy))
}

private fun DrawScope.moon(cx: Float, cy: Float, r: Float) {
    val p = Path().apply {
        op(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), r)) },
            Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx + r * 0.55f, cy - r * 0.35f), r * 0.85f)) }, PathOperation.Difference)
    }
    drawPath(p, Color(0xFFF2F2F7))
}

/** Weericoon; met `animate` bewegen zon, wolken, regen, sneeuw, bliksem, mist en sterren. */
private fun DrawScope.drawSky(sky: Sky, night: Boolean, t: Float, animate: Boolean) {
    val w = size.width; val h = size.height; val m = minOf(w, h)
    val drift = if (animate) sin(t * 0.9f) * w * 0.03f else 0f
    fun precip(snow: Boolean) {
        val n = 5
        for (i in 0 until n) {
            val x0 = w * (0.3f + 0.1f * i)
            val ph = if (animate) ((t / (if (snow) 2.6f else 1.1f) + i * 0.23f) % 1f) else 0.4f
            val y = h * 0.62f + ph * h * 0.32f
            val a = if (animate) sin(ph * PI.toFloat()) else 1f
            if (snow) drawCircle(Color.White, m * 0.035f, Offset(x0 + sin(ph * 6f) * m * 0.03f, y), alpha = a)
            else drawLine(DropBlue, Offset(x0, y), Offset(x0 - m * 0.03f, y + m * 0.09f), m * 0.04f, StrokeCap.Round, alpha = a)
        }
    }
    when (sky) {
        Sky.Clear -> if (night) {
            moon(w * 0.5f, h * 0.48f, m * 0.3f)
            listOf(0.15f to 0.2f, 0.85f to 0.25f, 0.2f to 0.75f, 0.8f to 0.8f).forEachIndexed { i, (x, y) ->
                val a = if (animate) 0.25f + 0.75f * (0.5f + 0.5f * sin(t * 2.4f + i * 1.7f)) else 0.8f
                drawCircle(Color.White, m * 0.025f, Offset(w * x, h * y), alpha = a)
            }
        } else sun(w * 0.5f, h * 0.5f, m * 0.22f, t, animate)
        Sky.Partly -> {
            if (night) moon(w * 0.38f, h * 0.36f, m * 0.2f) else sun(w * 0.38f, h * 0.36f, m * 0.15f, t, animate)
            cloud(w * 0.56f + drift, h * 0.62f, w * 0.6f, CloudLight)
        }
        Sky.Cloudy -> {
            cloud(w * 0.42f - drift, h * 0.42f, w * 0.5f, CloudGrey)
            cloud(w * 0.56f + drift, h * 0.6f, w * 0.62f, CloudLight)
        }
        Sky.Fog -> {
            cloud(w * 0.5f + drift, h * 0.38f, w * 0.6f, CloudGrey)
            for (i in 0 until 3) {
                val dx = if (animate) sin(t * 0.8f + i) * w * 0.05f else 0f
                drawLine(CloudLight, Offset(w * 0.18f + dx, h * (0.66f + 0.12f * i)), Offset(w * 0.82f + dx, h * (0.66f + 0.12f * i)), m * 0.05f, StrokeCap.Round, alpha = 0.8f)
            }
        }
        Sky.Rain -> { cloud(w * 0.5f + drift, h * 0.4f, w * 0.7f, Color(0xFFD1D1D6)); precip(false) }
        Sky.Snow -> { cloud(w * 0.5f + drift, h * 0.4f, w * 0.7f, Color(0xFFF2F2F7)); precip(true) }
        Sky.Storm -> {
            cloud(w * 0.5f + drift, h * 0.4f, w * 0.7f, CloudGrey); precip(false)
            val flash = if (animate) { val p = t % 4.5f; if (p in 3.9f..4.15f) 1f else 0f } else 1f
            if (flash > 0f) {
                val b = Path().apply {
                    moveTo(w * 0.52f, h * 0.5f); lineTo(w * 0.42f, h * 0.72f); lineTo(w * 0.5f, h * 0.72f)
                    lineTo(w * 0.45f, h * 0.95f); lineTo(w * 0.62f, h * 0.66f); lineTo(w * 0.53f, h * 0.66f); lineTo(w * 0.58f, h * 0.5f); close()
                }
                drawPath(b, SunYellow, alpha = flash)
            }
        }
    }
}

// ---------------------------------------------------------------- lichtkrant

/** NOS-lichtkrant over de volle schermbreedte (onderaan Thuis, buiten de marges). */
@Composable
fun NewsTicker(vm: AppViewModel) {
    val news = vm.news ?: return
    if (news.headlines.isEmpty()) return
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame.longValue = it } }
    val tm = rememberTextMeasurer()
    val style = TextStyle(color = Color(0xFFE5E5EA), fontSize = 14.sp)
    val layouts = remember(news) { news.headlines.map { tm.measure(it, style) } }
    val start = remember(news) { frame.longValue }
    Row(
        Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF141419)).drawTopLine(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // logo van de NOS uit de nieuwsfeed zelf; lukt dat niet, dan de tekst
        Box(Modifier.padding(start = Kijk.SafeH)) {
            LogoOr(news.logo, Modifier.height(20.dp).width(44.dp)) { Label("NOS", size = Kijk.Caption, weight = Kijk.Bold, color = Color.White) }
        }
        Label(Format.time(news.fetched), Modifier.padding(start = 6.dp, end = 10.dp), size = Kijk.Caption, color = Kijk.Secondary)
        Canvas(Modifier.weight(1f).fillMaxHeight().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val gap = 18.dp.toPx(); val eyeW = 22.dp.toPx()
            val item = layouts.map { it.size.width + gap * 2 + eyeW }
            val total = item.sum()
            if (total <= 0f) return@Canvas
            val t = ((frame.longValue - start).coerceAtLeast(0L)) / 1e9f
            val off = (t * 45.dp.toPx()) % total
            var x = -off
            var i = 0
            while (x < size.width) {
                val k = i % layouts.size
                val tl = layouts[k]
                if (x + item[k] > 0f) {
                    drawText(tl, topLeft = Offset(x + gap, (size.height - tl.size.height) / 2))
                    miniEyes(x + gap * 2 + tl.size.width, size.height / 2, t, k)
                }
                x += item[k]; i++
            }
            // zachte randen
            val fade = 28.dp.toPx()
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width), topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
        }
    }
}

/** Twee mini-oogjes tussen de koppen: knipperen af en toe en kijken heen en weer. */
private fun DrawScope.miniEyes(x: Float, cy: Float, t: Float, seed: Int) {
    val rx = 4.5.dp.toPx(); val ry = 6.dp.toPx()
    val cycle = 3.4f + (seed % 3) * 0.4f
    val p = (t + seed * 0.7f) % cycle
    val open = if (p > cycle - 0.18f) 0.1f else 1f
    val look = sin(t * 1.3f + seed) * rx * 0.35f
    for (j in 0..1) {
        val cx = x + rx + j * (rx * 2 + 2.dp.toPx())
        val hh = ry * 2 * open
        drawOval(Color.White, Offset(cx - rx, cy - hh / 2), Size(rx * 2, hh))
        if (open > 0.5f) drawCircle(Color(0xFF0B0B0F), rx * 0.5f, Offset(cx + look, cy + ry * 0.12f))
    }
}

private fun Modifier.drawTopLine(): Modifier = this.drawBehind {
    drawRect(Color(0xFF2A2A31), size = Size(size.width, 1.dp.toPx()))
}


// ---------------------------------------------------------------- sport


private fun dayTime(t: Long) = SimpleDateFormat("EEE HH:mm", NL).format(t).replace(".", "")
private fun dayDateTime(t: Long) = SimpleDateFormat("EEE d MMM · HH:mm", NL).format(t).replace(".", "")

/** "over 1 dag 4 uur", "over 25 min", "nu bezig". */
private fun countdown(start: Long, now: Long, liveFor: Long): String {
    val d = start - now
    if (d <= 0) return if (now < start + liveFor) "Nu bezig" else "Afgelopen"
    val m = d / 60_000; val h = m / 60; val days = h / 24
    return when {
        days >= 1 -> "over $days ${if (days == 1L) "dag" else "dagen"}" + if (h % 24 > 0) " ${h % 24} uur" else ""
        h >= 1 -> "over $h uur" + if (m % 60 > 0) " ${m % 60} min" else ""
        else -> "over ${m.coerceAtLeast(1)} min"
    }
}

private val Grey = Color(0xFF8E8E93)
private val DividerC = Color(0xFF2A2A31)

/** Is het nu bezig? Volgens de gids als die het programma heeft, anders volgens de verwachte duur. */
private fun sportLive(tv: nl.kijk.tv.SportTv?, start: Long, name: String, now: Long): Boolean {
    tv?.programme?.let { return it.isLive(now) }
    val dur = when { name == "Race" || name == "Wedstrijd" -> 2 * 3600_000L; name == "Sprint" -> 45 * 60_000L; else -> 3600_000L }
    return now in start until start + dur
}

/** Onderregel van een sporttegel: dunne lijn en dan óf LIVE + zender, óf klokje + aftelling (+ 🔔 als er een herinnering staat). */
@Composable
private fun SportFooter(live: Boolean, countdown: String, channel: String?, accent: Color, reminder: Boolean) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerC))
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                Box(Modifier.background(Color(0xFFFF3B30), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                    Label("LIVE", size = 11.sp, weight = Kijk.Bold, color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Label(if (channel != null) "Nu op $channel" else "Nu bezig", size = 13.sp, weight = Kijk.Semibold, color = Color.White, maxLines = 1)
                if (channel != null) Label(" · OK om te kijken", size = 13.sp, color = Color(0xFFC7C7CC), maxLines = 1)
            } else {
                Canvas(Modifier.size(16.dp)) {
                    val st = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx(), cap = StrokeCap.Round)
                    drawCircle(accent, size.minDimension / 2 - st.width / 2, style = st)
                    val c = Offset(size.width / 2, size.height / 2)
                    drawLine(accent, c, Offset(c.x, size.height * 0.24f), st.width, StrokeCap.Round)
                    drawLine(accent, c, Offset(size.width * 0.72f, c.y), st.width, StrokeCap.Round)
                }
                Spacer(Modifier.width(8.dp))
                Label(countdown, size = 13.sp, weight = Kijk.Semibold, color = accent, maxLines = 1)
                if (channel != null) Label(" · op $channel", Modifier.weight(1f, fill = false), size = 13.sp, color = Color(0xFFC7C7CC), maxLines = 1)
                if (reminder) Label("  🔔", size = 13.sp)
            }
        }
    }
}

/** Logo laden (officieel plaatje van de bron); lukt dat niet, dan de vervanger tonen. */
@Composable
private fun LogoOr(url: String?, modifier: Modifier, fallback: @Composable () -> Unit) {
    var failed by remember(url) { mutableStateOf(url == null) }
    if (failed) fallback()
    else coil.compose.AsyncImage(url, null, modifier = modifier, contentScale = androidx.compose.ui.layout.ContentScale.Fit, onError = { failed = true })
}

private val CardW = 268.dp
private val CardH = 204.dp
private val F1Red = Color(0xFFE10600)
private val PsvRed = Color(0xFFED1C24)
private val KnvbOrange = Color(0xFFFF7A00)

@Composable
private fun F1Card(vm: AppViewModel, w: nl.kijk.tv.data.F1Weekend, tv: nl.kijk.tv.SportTv?, onClick: () -> Unit) {
    val now = vm.now
    val next = w.sessions.firstOrNull { it.start + 2 * 3600_000L > now }
    FocusCard(onClick = onClick, modifier = Modifier.width(CardW).height(CardH), shape = RoundedCornerShape(16.dp), focusedScale = 1.04f, darkFocus = true) { _ ->
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoOr(nl.kijk.tv.data.Sports.F1_LOGO, Modifier.height(26.dp).width(52.dp)) {
                    Box(Modifier.background(F1Red, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp)) { Label("F1", size = 15.sp, weight = Kijk.Bold, color = Color.White) }
                }
                Spacer(Modifier.width(8.dp))
                Label(w.name.removeSuffix(" Grand Prix").let { if (it != w.name) "GP $it" else it }, size = 16.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
            }
            Label("${w.circuit.removeSuffix(" Circuit").removeSuffix(" International")} · ronde ${w.round}/${w.rounds}", Modifier.padding(top = 1.dp, bottom = 4.dp), size = 12.sp, color = Grey, maxLines = 1)
            // sessies links, circuit rechts: allebei boven de lijn
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f)) {
                    w.sessions.forEach { s ->
                        val past = s.start + 2 * 3600_000L < now
                        val isNext = s == next
                        Row(
                            Modifier.fillMaxWidth().then(if (isNext) Modifier.background(F1Red, RoundedCornerShape(50)) else Modifier)
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                        ) {
                            val c = when { isNext -> Color.White; past -> Grey.copy(alpha = 0.55f); else -> Color(0xFFC7C7CC) }
                            Label(s.name.replace("Vrije training", "VT").replace("Sprintkwalificatie", "Sprint-kwal."), Modifier.weight(1f), size = 12.sp, weight = if (isNext) Kijk.Bold else null, color = c, maxLines = 1)
                            Label(dayTime(s.start), size = 12.sp, weight = if (isNext) Kijk.Bold else null, color = c)
                        }
                    }
                }
                if (w.outline.size > 2) Canvas(Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp).size(66.dp).align(Alignment.CenterVertically)) {
                    val p = Path().apply { w.outline.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) } }
                    drawPath(p, F1Red, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = StrokeCap.Round))
                    val (sx, sy) = w.outline.first()
                    val cs = 2.5.dp.toPx(); val ox = sx * size.width - cs * 1.5f; val oy = sy * size.height - cs * 1.5f
                    for (r in 0..2) for (c in 0..2) drawRect(if ((r + c) % 2 == 0) Color.White else Color.Black, Offset(ox + c * cs, oy + r * cs), Size(cs, cs))
                }
            }
            if (next != null) SportFooter(sportLive(tv, next.start, next.name, now), "${next.name.replace("Vrije training", "VT")} ${countdown(next.start, now, 2 * 3600_000L).replaceFirstChar { it.lowercase() }}",
                tv?.channel?.name, F1Red, vm.hasSportReminder(tv, next.start))
        }
    }
}

/** Stand van de Eredivisie: top 5 (PSV erbij als die lager staat); elke rij is een balkje naar het aantal punten. */
@Composable
private fun TableCard(t: nl.kijk.tv.data.Table) {
    val max = t.rows.maxOfOrNull { it.points }?.coerceAtLeast(1) ?: 1
    val psv = t.rows.firstOrNull { it.isPsv }
    val shown = if (psv == null || psv.rank <= 5) t.rows.take(5) else t.rows.take(4) + psv
    val round = t.rows.maxOfOrNull { it.played } ?: 0
    FocusCard(onClick = {}, modifier = Modifier.width(CardW).height(CardH), shape = RoundedCornerShape(16.dp), focusedScale = 1.04f, darkFocus = true) { _ ->
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoOr(t.logo, Modifier.size(26.dp)) { }
                if (t.logo != null) Spacer(Modifier.width(8.dp))
                Label(t.competition, Modifier.weight(1f), size = 16.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
                if (round > 0) Label("ronde $round", size = 12.sp, color = Grey)
            }
            Row(Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 1.dp, start = 8.dp, end = 8.dp)) {
                Label("#", Modifier.width(20.dp), size = 11.sp, color = Grey); Spacer(Modifier.width(24.dp))
                Label("Club", Modifier.weight(1f), size = 11.sp, color = Grey)
                Label("W", Modifier.width(24.dp), size = 11.sp, color = Grey, align = androidx.compose.ui.text.style.TextAlign.End)
                Label("Ptn", Modifier.width(30.dp), size = 11.sp, color = Grey, align = androidx.compose.ui.text.style.TextAlign.End)
            }
            shown.forEach { r ->
                Box(Modifier.fillMaxWidth().padding(bottom = 1.dp).height(19.dp)) {
                    // balkje achter de rij
                    Box(Modifier.fillMaxWidth(r.points.toFloat() / max).fillMaxHeight()
                        .background(if (r.isPsv) PsvRed.copy(alpha = 0.32f) else Color(0x12FFFFFF), RoundedCornerShape(6.dp)))
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("${r.rank}", Modifier.width(20.dp), size = 12.sp, weight = if (r.isPsv) Kijk.Bold else null, color = if (r.isPsv) Color.White else Grey)
                        Box(Modifier.size(16.dp)) { LogoOr(r.logo, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().background(Color(0x22FFFFFF), RoundedCornerShape(50))) } }
                        Spacer(Modifier.width(6.dp))
                        Label(r.team, Modifier.weight(1f), size = 12.sp, weight = if (r.isPsv) Kijk.Bold else null, color = Color.White, maxLines = 1)
                        Label("${r.played}", Modifier.width(24.dp), size = 12.sp, color = Color(0xFFC7C7CC), align = androidx.compose.ui.text.style.TextAlign.End)
                        Label("${r.points}", Modifier.width(30.dp), size = 12.sp, weight = Kijk.Bold, color = Color.White, align = androidx.compose.ui.text.style.TextAlign.End)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (psv != null) {
                val other = if (psv.rank == 1) t.rows.getOrNull(1) else t.rows.firstOrNull()
                if (other != null) {
                    val d = psv.points - other.points
                    Column {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(DividerC))
                        Row(Modifier.padding(top = 8.dp)) {
                            Label(when { d > 0 -> "PSV $d ${if (d == 1) "punt" else "punten"} voor"; d < 0 -> "PSV ${-d} ${if (d == -1) "punt" else "punten"} achter"; else -> "PSV gelijk" },
                                size = 13.sp, weight = Kijk.Semibold, color = PsvRed, maxLines = 1)
                            Label(" op ${other.team}", size = 13.sp, color = Color(0xFFC7C7CC), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchCard(vm: AppViewModel, m: nl.kijk.tv.data.Match, tv: nl.kijk.tv.SportTv?, onClick: () -> Unit) {
    val now = vm.now
    val accent = if (m.team == "PSV") PsvRed else KnvbOrange
    FocusCard(onClick = onClick, modifier = Modifier.width(CardW).height(CardH), shape = RoundedCornerShape(16.dp), focusedScale = 1.04f, darkFocus = true) { _ ->
        Box(Modifier.fillMaxWidth().height(4.dp).background(accent))   // gekleurde streep bovenaan
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoOr(m.competitionLogo, Modifier.size(26.dp)) { }
                if (m.competitionLogo != null) Spacer(Modifier.width(8.dp))
                Label(m.competition, size = 16.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamBadge(m.home, m.homeLogo, Modifier.weight(1f))
                Label("–", size = 20.sp, weight = Kijk.Bold, color = Color(0xFFC7C7CC))
                TeamBadge(m.away, m.awayLogo, Modifier.weight(1f))
            }
            Label(dayDateTime(m.start), Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp), size = 16.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
            Label(listOfNotNull(m.venue, if (m.psvHome) "Thuis" else "Uit").joinToString(" · "), Modifier.align(Alignment.CenterHorizontally), size = 12.sp, color = Grey, maxLines = 1)
            Spacer(Modifier.weight(1f))
            SportFooter(sportLive(tv, m.start, "Wedstrijd", now), countdown(m.start, now, 2 * 3600_000L), tv?.channel?.name, accent, vm.hasSportReminder(tv, m.start))
        }
    }
}

@Composable
private fun TeamBadge(name: String, logo: String?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            LogoOr(logo, Modifier.fillMaxSize()) { Box(Modifier.size(36.dp).background(Color(0x22FFFFFF), RoundedCornerShape(50))) }
        }
        Label(name, Modifier.padding(top = 3.dp), size = 13.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
    }
}
