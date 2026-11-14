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
            val f1 = vm.f1; val psv = vm.psvMatch
            if (f1 != null || psv != null) item {
                HomeRow("Sport") {
                    fun play(tv: Pair<Channel, nl.kijk.tv.data.Programme>?) {
                        val ch = tv?.first ?: return
                        if (!tv.second.isLive(vm.now)) return   // nog niet begonnen: niets doen
                        val idx = vm.channels.indexOf(ch).coerceAtLeast(0)
                        if (onPlay != null) onPlay(vm.channels, idx) else nav.push(Screen.Live(vm.channels, idx))
                    }
                    if (f1 != null) item { F1Card(vm, f1, vm.f1Tv) { play(vm.f1Tv) } }
                    if (psv != null) item { MatchCard(vm, psv, vm.psvTv) { play(vm.psvTv) } }
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
        Label("NOS", Modifier.padding(start = Kijk.SafeH), size = Kijk.Caption, weight = Kijk.Bold, color = Color.White)
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

private val F1Red = Color(0xFFE10600)
private val OutlineBlue = Color(0xFF5B8CFF)

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

@Composable
private fun F1Card(vm: AppViewModel, w: nl.kijk.tv.data.F1Weekend, tv: Pair<Channel, nl.kijk.tv.data.Programme>?, onClick: () -> Unit) {
    val now = vm.now
    val next = w.sessions.firstOrNull { it.start + 2 * 3600_000L > now }
    FocusCard(onClick = onClick, modifier = Modifier.width(400.dp).height(206.dp), focusedScale = 1.04f, focusRing = true) { f ->
        // vorm van het circuit op de achtergrond
        if (w.outline.size > 2) Canvas(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).size(170.dp)) {
            val p = Path().apply {
                w.outline.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * size.width, y * size.height) else lineTo(x * size.width, y * size.height) }
            }
            drawPath(p, OutlineBlue.copy(alpha = if (f) 0.5f else 0.32f), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = StrokeCap.Round))
            val (sx, sy) = w.outline.first()
            drawCircle(OutlineBlue.copy(alpha = 0.7f), 4.dp.toPx(), Offset(sx * size.width, sy * size.height))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.background(F1Red, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp)) { Label("F1", size = 12.sp, weight = Kijk.Bold, color = Color.White) }
                Spacer(Modifier.width(8.dp))
                Label(w.name, size = Kijk.Body, weight = Kijk.Semibold, color = if (f) Color.Black else Color.White, maxLines = 1)
            }
            Label("${w.circuit} · ronde ${w.round} van ${w.rounds}", Modifier.padding(top = 2.dp, bottom = 6.dp), size = 12.sp, color = secondary(f), maxLines = 1)
            w.sessions.forEach { s ->
                val past = s.start + 2 * 3600_000L < now
                val isNext = s == next
                Row(Modifier.width(250.dp).padding(vertical = 1.dp)) {
                    Label(s.name, Modifier.weight(1f), size = 13.sp, weight = if (isNext) Kijk.Semibold else null,
                        color = when { isNext -> if (f) Color(0xFF0050A0) else Kijk.Accent; past -> secondary(f).copy(alpha = 0.6f); f -> Color.Black; else -> Color(0xFFD1D1D6) })
                    Label(dayTime(s.start), size = 13.sp, weight = if (isNext) Kijk.Semibold else null,
                        color = when { isNext -> if (f) Color(0xFF0050A0) else Kijk.Accent; past -> secondary(f).copy(alpha = 0.6f); f -> Color.Black; else -> Color.White })
                }
            }
            Spacer(Modifier.weight(1f))
            if (next != null) Label("${next.name} ${countdown(next.start, now, 2 * 3600_000L).replaceFirstChar { it.lowercase() }}" + (tv?.let { " · op ${it.first.name}" } ?: ""),
                size = 12.sp, weight = Kijk.Semibold, color = if (f) Color(0xFF0050A0) else Kijk.Accent, maxLines = 1)
        }
    }
}

@Composable
private fun MatchCard(vm: AppViewModel, m: nl.kijk.tv.data.Match, tv: Pair<Channel, nl.kijk.tv.data.Programme>?, onClick: () -> Unit) {
    val now = vm.now
    FocusCard(onClick = onClick, modifier = Modifier.width(330.dp).height(206.dp), focusedScale = 1.04f, focusRing = true) { f ->
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Label(m.competition, size = 12.sp, color = secondary(f), maxLines = 1)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TeamBadge(m.home, m.homeLogo, f, Modifier.weight(1f))
                Label("–", size = 18.sp, color = secondary(f))
                TeamBadge(m.away, m.awayLogo, f, Modifier.weight(1f))
            }
            Label(dayDateTime(m.start), size = Kijk.Body, weight = Kijk.Semibold, color = if (f) Color.Black else Color.White)
            Label(listOfNotNull(m.venue, if (m.psvHome) "thuis" else "uit").joinToString(" · "), size = 12.sp, color = secondary(f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Label(countdown(m.start, now, 2 * 3600_000L) + (tv?.let { " · op ${it.first.name}" } ?: ""), size = 12.sp, weight = Kijk.Semibold,
                color = if (f) Color(0xFF0050A0) else Kijk.Accent, maxLines = 1)
        }
    }
}

@Composable
private fun TeamBadge(name: String, logo: String?, focused: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (logo != null) coil.compose.AsyncImage(logo, name, modifier = Modifier.fillMaxSize())
            else Box(Modifier.size(44.dp).background(Color(0x22FFFFFF), RoundedCornerShape(50)))
        }
        Label(name, Modifier.padding(top = 4.dp), size = 13.sp, weight = Kijk.Semibold, color = if (focused) Color.Black else Color.White, maxLines = 1)
    }
}
