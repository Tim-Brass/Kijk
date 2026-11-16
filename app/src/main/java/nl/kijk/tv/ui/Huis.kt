package nl.kijk.tv.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import nl.kijk.tv.AppViewModel
import nl.kijk.tv.Format
import nl.kijk.tv.Nav
import nl.kijk.tv.data.HDevice
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val HuisH = 186.dp
private val TempC = Color(0xFFFF9F0A)
private val LightC = Color(0xFFFFC940)
private val AfvalC = Color(0xFF30D158)
private val BellC = Color(0xFF64D2FF)
private val GreyH = Color(0xFF8E8E93)
private val LineH = Color(0xFF2A2A31)
private val PanelBg = Color(0xFF16161C)

private fun deg(v: Double?) = v?.let { String.format(Locale("nl"), "%.1f°", it) } ?: "–"
private fun degShort(v: Double?) = v?.let { if (it % 1.0 == 0.0) "${it.toInt()}°" else String.format(Locale("nl"), "%.1f°", it) } ?: "–"

/** Rij "Huis" op Thuis: vier even brede kaarten, net als de sportrij. Alleen als er een Homey-sleutel is. */
@Composable
fun HuisRow(vm: AppViewModel, nav: Nav) {
    if (!vm.homeyReady) return
    // alleen verversen zolang Thuis in beeld is (hooguit eens per minuut)
    LaunchedEffect(Unit) { while (true) { vm.refreshHomey(); delay(60_000) } }
    var panel by remember { mutableStateOf<String?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Huis", Modifier.padding(start = 12.dp), size = Kijk.Body, weight = Kijk.Semibold, color = Color(0xFFD1D1D6))
            val err = vm.hyError
            if (err != null) Label("  ·  Homey niet bereikbaar", size = 12.sp, color = GreyH)
            else if (vm.hyDevices.isEmpty()) Label("  ·  Homey zoeken…", size = 12.sp, color = GreyH)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            TempCard(vm, Modifier.weight(1f)) { panel = "temp" }
            LightCard(vm, Modifier.weight(1f)) { panel = "light" }
            AfvalCard(vm, Modifier.weight(1f)) { panel = "afval" }
            BellCard(vm, Modifier.weight(1f)) { if (vm.hyBell?.video != null) nav.push(nl.kijk.tv.Screen.Doorbell) else vm.hySnapshot() }
        }
    }
    panel?.let { p -> HuisPanel(vm, p) { panel = null } }
}

@Composable
private fun HuisCard(modifier: Modifier, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.height(HuisH), shape = RoundedCornerShape(16.dp), focusedScale = 1.04f, darkFocus = true) { _ ->
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 9.dp)) { content() }
    }
}

@Composable
private fun CardHead(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Label(title, size = 14.sp, weight = Kijk.Semibold, color = color)
    }
}

@Composable
private fun CardFoot(text: String, color: Color = GreyH) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineH))
        Label(text, Modifier.padding(top = 6.dp), size = 12.sp, color = color, maxLines = 1)
    }
}

@Composable
private fun TempCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val t = vm.hyThermostat
    HuisCard(modifier, onClick) {
        CardHead("Temperatuur", TempC)
        Row(verticalAlignment = Alignment.Bottom) {
            Label(deg(t?.num("measure_temperature")), size = 30.sp, weight = Kijk.Bold, color = Color.White)
            if (t?.num("target_temperature") != null) Label("  → ${deg(t.num("target_temperature"))}", Modifier.padding(bottom = 5.dp), size = 13.sp, color = GreyH)
        }
        listOfNotNull(vm.hyAirco1, vm.hyAirco2).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Label(a.name, Modifier.weight(1f), size = 12.sp, color = Color(0xFFD1D1D6))
                val on = a.bool("onoff") != false
                Label(if (on) degShort(a.num("target_temperature")) else "uit", size = 12.sp, weight = Kijk.Semibold, color = if (on) TempC else GreyH)
            }
        }
        Spacer(Modifier.weight(1f))
        val c = vm.hyComfort
        CardFoot(if (c != null) "Comfort ${deg((c.value as? Number)?.toDouble())}" else if (t == null) "Geen thermostaat gevonden" else "OK om te bedienen")
    }
}

@Composable
private fun LightCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val rooms = vm.hyRooms
    val on = rooms.filter { it.on > 0 }
    HuisCard(modifier, onClick) {
        CardHead("Verlichting", LightC)
        Row(verticalAlignment = Alignment.Bottom) {
            Label("${on.size}", size = 30.sp, weight = Kijk.Bold, color = Color.White)
            Label(if (on.size == 1) "  ruimte aan" else "  ruimtes aan", Modifier.padding(bottom = 5.dp), size = 13.sp, color = GreyH)
        }
        // de ruimtes die aan zijn, daarna een paar die uit zijn
        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            (on + rooms.filter { it.on == 0 }).take(3).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (r.on > 0) LightC else Color(0xFF3A3A40), RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Label(r.zone.name, size = 12.sp, color = if (r.on > 0) Color.White else GreyH)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CardFoot(if (rooms.isEmpty()) "Geen lampen gevonden" else "${rooms.size} ruimtes · OK om te bedienen")
    }
}

// ---------------------------------------------------------------- afval

data class AfvalItem(val type: String, val date: Long?, val text: String)

private val MONTHS = listOf("jan", "feb", "m", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec")

private fun dayStart(t: Long) = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

/** Leest een datum uit wat de afval-app laat zien ("2026-11-17", "17-11-2026", "di 17 nov", "morgen"). */
private fun parseAfvalDate(s: String, now: Long): Long? {
    val low = s.lowercase()
    val today = dayStart(now)
    if ("vandaag" in low) return today
    if ("overmorgen" in low) return today + 2 * 86_400_000L
    if ("morgen" in low) return today + 86_400_000L
    fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
    Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(low)?.let { val (y, m, d) = it.destructured; return cal(y.toInt(), m.toInt(), d.toInt()) }
    Regex("""(\d{1,2})[-/.](\d{1,2})[-/.](\d{4})""").find(low)?.let { val (d, m, y) = it.destructured; return cal(y.toInt(), m.toInt(), d.toInt()) }
    Regex("""(\d{1,2})\s+(jan|feb|maa|mrt|apr|mei|jun|jul|aug|sep|okt|nov|dec)""").find(low)?.let {
        val d = it.groupValues[1].toInt(); val mon = it.groupValues[2]
        val m = if (mon == "maa" || mon == "mrt") 3 else MONTHS.indexOf(mon) + 1
        val y = Calendar.getInstance().get(Calendar.YEAR)
        val t = cal(y, m, d)
        return if (t < today - 7 * 86_400_000L) cal(y + 1, m, d) else t
    }
    return null
}

/** Ophaaldagen: uit de afval-app (instellingen) of anders uit een afval-apparaat. Komende eerst. */
fun afvalItems(vm: AppViewModel): List<AfvalItem> {
    val now = vm.now
    val fromApp = vm.hyAfvalSettings?.let { afvalFromSettings(it, now) }.orEmpty()
    if (fromApp.isNotEmpty()) return fromApp
    return afvalFromDevice(vm.hyAfval, now)
}

private val WASTE = listOf("gft", "groen", "papier", "pmd", "plastic", "pbd", "rest", "grof", "textiel", "kca", "glas", "kerst", "snoei", "luier")

private fun wasteName(k: String): String {
    val l = k.lowercase()
    return when {
        "gft" in l || "groen" in l -> "GFT"
        "papier" in l -> "Papier"
        "pmd" in l || "plastic" in l || "pbd" in l -> "PMD"
        "rest" in l -> "Restafval"
        "grof" in l -> "Grofvuil"
        "textiel" in l -> "Textiel"
        "kerst" in l -> "Kerstbomen"
        "snoei" in l -> "Snoeiafval"
        else -> k.replaceFirstChar { it.uppercase() }
    }
}

/** Zoekt in de app-instellingen naar datums en de soort waar ze bij horen (sleutel of "SOORT;datum;datum"). */
fun afvalFromSettings(o: org.json.JSONObject, now: Long): List<AfvalItem> {
    val found = mutableListOf<Pair<String, Long>>()
    val dateRe = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})|(\d{1,2})-(\d{1,2})-(\d{4})""")
    fun cal(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m - 1, d) }.timeInMillis
    fun walk(v: Any?, key: String) {
        when (v) {
            is org.json.JSONObject -> v.keys().forEach { k ->
                // sleutels als "dates" of een datum zelf: soort van de ouder houden
                val useKey = if (WASTE.any { it in k.lowercase() } || key.isEmpty()) k else key
                walk(v.opt(k), useKey)
            }
            is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), key)
            is String -> {
                if (v.trim().startsWith("{") || v.trim().startsWith("[")) { runCatching { walk(org.json.JSONTokener(v).nextValue(), key) }; return }
                v.split('\n').forEach { line ->
                    val type = line.substringBefore(';', "").takeIf { ';' in line && it.isNotBlank() && it.length < 20 } ?: key
                    dateRe.findAll(line).forEach { m ->
                        val g = m.groupValues
                        val t = if (g[1].isNotEmpty()) cal(g[1].toInt(), g[2].toInt(), g[3].toInt()) else cal(g[6].toInt(), g[5].toInt(), g[4].toInt())
                        found += type to t
                    }
                }
            }
        }
    }
    walk(o, "")
    val today = dayStart(now)
    return found.filter { (k, t) -> t >= today && t < today + 120L * 86_400_000L && WASTE.any { it in k.lowercase() } }
        .map { (k, t) -> AfvalItem(wasteName(k), t, "") }
        .distinctBy { it.type + it.date }
        .sortedBy { it.date }
}

fun afvalFromDevice(d: HDevice?, now: Long): List<AfvalItem> {
    d ?: return emptyList()
    val skip = listOf("measure_battery", "alarm_battery", "onoff", "button", "alarm_generic")
    return d.caps.values.filter { c -> skip.none { c.id.startsWith(it) } && c.value != null && c.value !is Boolean }
        .map { c ->
            val text = when (val v = c.value) { is Number -> v.toString(); else -> v.toString() }
            AfvalItem(c.title.ifBlank { c.id }, parseAfvalDate(text, now), text)
        }
        .sortedWith(compareBy({ it.date == null }, { it.date ?: Long.MAX_VALUE }))
}

fun afvalColor(type: String): Color {
    val t = type.lowercase()
    return when {
        "gft" in t || "groen" in t -> AfvalC
        "papier" in t -> Color(0xFF0A84FF)
        "pmd" in t || "plastic" in t || "pbd" in t -> Color(0xFFFF9F0A)
        else -> Color(0xFF8E8E93)
    }
}

private val dayFmt get() = SimpleDateFormat("EEE d MMM", Locale("nl"))

@Composable
private fun AfvalCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val items = afvalItems(vm)
    val today = dayStart(vm.now)
    val next = items.firstOrNull { (it.date ?: Long.MAX_VALUE) >= today } ?: items.firstOrNull()
    HuisCard(modifier, onClick) {
        CardHead("Afval", AfvalC)
        if (next == null) {
            Label(if (vm.hyAfval == null && vm.hyAfvalSettings == null) "Geen afval-app gevonden" else "Geen ophaaldagen", Modifier.padding(top = 8.dp), size = 13.sp, color = GreyH, maxLines = 2)
        } else {
            Label(next.date?.let { dayFmt.format(it) } ?: next.text, Modifier.padding(top = 4.dp), size = 22.sp, weight = Kijk.Bold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Box(Modifier.size(9.dp).background(afvalColor(next.type), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(6.dp))
                Label(next.type, size = 13.sp, color = Color(0xFFD1D1D6))
            }
            items.filter { it !== next && it.type != next.type }.take(1).forEach {
                Label("${it.date?.let { d -> dayFmt.format(d) } ?: it.text} · ${it.type}", Modifier.padding(top = 4.dp), size = 12.sp, color = GreyH)
            }
        }
        Spacer(Modifier.weight(1f))
        val days = next?.date?.let { ((it - today) / 86_400_000L).toInt() }
        when (days) {
            1 -> CardFoot("Vanavond buiten zetten", TempC)
            0 -> CardFoot("Vandaag", TempC)
            null -> CardFoot("OK voor meer")
            else -> CardFoot("Over $days dagen")
        }
    }
}

// ---------------------------------------------------------------- deurbel

@Composable
fun HomeyImage(vm: AppViewModel, d: HDevice?, modifier: Modifier) {
    val ctx = LocalContext.current
    val path = d?.image
    val imgAt = d?.imageAt ?: 0L
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF22222A)), contentAlignment = Alignment.Center) {
        if (path == null) Label("Geen beeld", size = 12.sp, color = GreyH)
        else {
            val url = vm.homey.imageUrl(path)
            // Homey geeft steeds hetzelfde adres voor het beeld: niet op schijf bewaren en bij elke verversing opnieuw ophalen
            val stamp = "$url@$imgAt@${vm.hyAt}"
            var shown by remember { mutableStateOf<String?>(null) }
            val req = remember(stamp) {
                coil.request.ImageRequest.Builder(ctx).data(url).setHeader("Authorization", "Bearer ${vm.homey.key}")
                    .memoryCacheKey(stamp).placeholderMemoryCacheKey(shown)
                    .diskCachePolicy(coil.request.CachePolicy.DISABLED).networkCachePolicy(coil.request.CachePolicy.ENABLED)
                    .size(480, 270).listener(onSuccess = { _, _ -> shown = stamp }).build()
            }
            AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun BellCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val b = vm.hyBell
    HuisCard(modifier, onClick) {
        CardHead("Deurbel", BellC)
        HomeyImage(vm, b, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp, bottom = 5.dp))
        val ring = vm.hyRings.firstOrNull()
        Label(when {
            ring != null -> "Aangebeld ${ringTime(ring, vm.now)}"
            (b?.imageAt ?: 0L) > 0 -> "Beeld ${ringTime(b!!.imageAt, vm.now)}"
            b == null -> "Geen deurbel gevonden"
            else -> "Nog niet aangebeld"
        }, size = 12.sp, color = Color(0xFFD1D1D6))
        CardFoot(when { b?.video != null -> "OK · live bekijken"; vm.hySnapBusy -> "Vernieuwen…"; else -> "OK · beeld vernieuwen" }, if (vm.hySnapBusy) BellC else GreyH)
    }
}

private fun ringTime(t: Long, now: Long): String = when {
    dayStart(t) == dayStart(now) -> Format.time(t)
    dayStart(t) == dayStart(now) - 86_400_000L -> "gisteren ${Format.time(t)}"
    else -> SimpleDateFormat("d MMM HH:mm", Locale("nl")).format(t)
}

/** Melding over alles heen als er wordt aangebeld (via de Homey-flow). */
@Composable
fun BellAlert(vm: AppViewModel, modifier: Modifier) {
    val t = vm.bellAlert ?: return
    LaunchedEffect(t) { delay(15_000); if (vm.bellAlert == t) vm.bellAlert = null }
    Column(modifier.width(360.dp).background(Color(0xF21C1C22), RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(BellC, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp))
            Label("Er wordt aangebeld", Modifier.weight(1f), size = Kijk.Headline, weight = Kijk.Semibold, color = Color.White)
            Label(Format.time(t), size = Kijk.Caption, color = GreyH)
        }
        if (vm.hyBell?.video != null) Label("OK = live bekijken", Modifier.padding(top = 4.dp), size = 12.sp, color = GreyH)
        HomeyImage(vm, vm.hyBell, Modifier.padding(top = 8.dp).fillMaxWidth().height(190.dp))
    }
}

// ---------------------------------------------------------------- bedienpaneel (schuift van rechts over Thuis)

@Composable
private fun HuisPanel(vm: AppViewModel, kind: String, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize()) {
            val first = remember { FocusRequester() }
            LaunchedEffect(Unit) { delay(80); runCatching { first.requestFocus() } }
            Column(Modifier.align(Alignment.CenterEnd).width(480.dp).fillMaxHeight().background(PanelBg)
                .padding(start = 26.dp, end = Kijk.SafeH, top = Kijk.SafeV + 6.dp, bottom = Kijk.SafeV)) {
                val (title, color) = when (kind) { "temp" -> "Temperatuur" to TempC; "light" -> "Verlichting" to LightC; else -> "Afval" to AfvalC }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Label(title, size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                }
                Label(when (kind) { "temp", "light" -> "OK = aan/uit · ‹ › = aanpassen · Terug = sluiten"; else -> "Terug = sluiten" },
                    Modifier.padding(top = 2.dp, bottom = 10.dp), size = 12.sp, color = GreyH)
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    when (kind) {
                        "temp" -> tempPanel(vm, first)
                        "light" -> lightPanel(vm, first)
                        else -> afvalPanel(vm, first)
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRow(
    title: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.White, sub: String? = null,
    onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null, onClick: () -> Unit = {},
) {
    FocusCard(onClick = onClick, focusedScale = 1.02f, modifier = modifier.fillMaxWidth().onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft?.invoke(); onLeft != null }
            Key.DirectionRight -> { onRight?.invoke(); onRight != null }
            else -> false
        }
    }) { f ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, size = Kijk.Body, weight = Kijk.Medium, color = if (f) Color.Black else Color.White)
                if (sub != null) Label(sub, size = 12.sp, color = secondary(f))
            }
            if (onLeft != null && f) Label("‹  ", size = Kijk.Body, color = secondary(f))
            Label(value, size = Kijk.Body, weight = Kijk.Semibold, color = if (f) Color.Black else valueColor)
            if (onRight != null && f) Label("  ›", size = Kijk.Body, color = secondary(f))
        }
    }
}

@Composable
private fun HuisHeader(text: String) {
    Label(text, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), size = 12.sp, weight = Kijk.Semibold, color = GreyH)
}

private fun androidx.compose.foundation.lazy.LazyListScope.tempPanel(vm: AppViewModel, first: FocusRequester) {
    var firstUsed = false
    fun fm(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
    val c = vm.hyComfort
    if (c != null) {
        val m = fm()
        item(key = "comfort") { PanelRow("Comfort-temperatuur", deg((c.value as? Number)?.toDouble()), m, TempC, sub = "geldt voor je flows",
            onLeft = { vm.hyComfortBy(-0.5) }, onRight = { vm.hyComfortBy(0.5) }) }
    }
    item(key = "h1") { HuisHeader("Apparaten") }
    vm.hyThermostat?.let { t ->
        val m = fm()
        item(key = "t") { PanelRow(t.name, deg(t.num("target_temperature")), m, TempC, sub = "nu ${deg(t.num("measure_temperature"))}",
            onLeft = { vm.hyTarget(t, -0.5) }, onRight = { vm.hyTarget(t, 0.5) }) }
    }
    listOfNotNull(vm.hyAirco1, vm.hyAirco2).forEach { a ->
        val m = fm()
        val on = a.bool("onoff") != false
        val mode = when (a.caps["thermostat_mode"]?.value as? String) { "heat" -> "verwarmen"; "cool" -> "koelen"; "auto" -> "auto"; "off" -> "uit"; null -> null; else -> a.caps["thermostat_mode"]?.value.toString() }
        item(key = "a" + a.id) {
            PanelRow(a.name, if (on) deg(a.num("target_temperature")) else "uit", m, if (on) TempC else GreyH,
                sub = listOfNotNull(a.num("measure_temperature")?.let { "nu ${deg(it)}" }, mode?.takeIf { on }).joinToString(" · ").ifEmpty { null },
                onLeft = { vm.hyTarget(a, -0.5) }, onRight = { vm.hyTarget(a, 0.5) }) { vm.hyToggle(a) }
        }
        if (mode != null && on) item(key = "m" + a.id) { PanelRow("Modus ${a.name}", mode, valueColor = Color(0xFFD1D1D6)) { vm.hyNextMode(a) } }
    }
    if (vm.hyResume != null) {
        val m = fm()
        item(key = "resume") { Box(Modifier.padding(top = 10.dp)) { PanelRow("Hervat schema", "", m, sub = "handmatige aanpassingen terugdraaien") { vm.hyResumeSchedule() } } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.lightPanel(vm: AppViewModel, first: FocusRequester) {
    val rooms = vm.hyRooms
    if (rooms.isEmpty()) { item { Label("Geen lampen gevonden in Homey.", size = Kijk.Body, color = GreyH) }; return }
    var firstUsed = false
    rooms.groupBy { it.floor }.forEach { (floor, rs) ->
        item(key = "f$floor") { HuisHeader(floor) }
        rs.forEach { r ->
            val m = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            item(key = r.zone.id) {
                val pct = r.dim?.let { " · ${(it * 100).toInt()}%" } ?: ""
                PanelRow(r.zone.name, if (r.on > 0) "aan$pct" else "uit", m, if (r.on > 0) LightC else GreyH,
                    sub = if (r.switches.isEmpty() && r.total > 1) "${r.on} van ${r.total} lampen aan" else null,
                    onLeft = if (r.dimmable) ({ vm.hyDimRoom(r, -0.1) }) else null, onRight = if (r.dimmable) ({ vm.hyDimRoom(r, 0.1) }) else null) { vm.hyToggleRoom(r) }
            }
        }
    }
    item(key = "alloff") { Box(Modifier.padding(top = 10.dp)) { PanelRow("Alles uit", "", sub = "alle lampen hierboven") { vm.hyAllOff() } } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.afvalPanel(vm: AppViewModel, first: FocusRequester) {
    val items = afvalItems(vm).take(10)
    if (items.isEmpty()) { item { Label("Geen ophaaldagen gevonden. Kies de afval-app in Instellingen › Huis.", size = Kijk.Body, color = GreyH, maxLines = 3) }; return }
    items.forEachIndexed { i, it ->
        item(key = "af$i") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(11.dp).background(afvalColor(it.type), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(10.dp))
                Label(it.type, Modifier.weight(1f), size = Kijk.Body, color = Color.White)
                Label(it.date?.let { d -> dayFmt.format(d) } ?: it.text, size = Kijk.Body, weight = Kijk.Semibold, color = Color(0xFFD1D1D6))
            }
        }
    }
    // het paneel moet iets hebben dat focus kan krijgen, anders werkt Terug niet altijd
    item(key = "close") { Box(Modifier.padding(top = 10.dp)) { PanelRow("Sluiten", "", Modifier.focusRequester(first)) { } } }
}

// ---------------------------------------------------------------- instellingen: Homey-keuzes

/** Kiezen welk apparaat/welke variabele/flow op een plek komt (leeg = automatisch). */
@Composable
fun HomeyPickScreen(vm: AppViewModel, nav: Nav, slot: String) {
    LaunchedEffect(Unit) { vm.refreshHomey(meta = true) }
    val (title, options) = homeyOptions(vm, slot)
    val current = vm.hyChoice(slot)
    Column(Modifier.fillMaxSize().background(Kijk.Background).padding(horizontal = Kijk.SafeH + 40.dp, vertical = Kijk.SafeV)) {
        Label(title, size = Kijk.LargeTitle, weight = Kijk.Bold)
        Label(if (slot == "hide") "OK zet een ruimte aan of uit. Verborgen ruimtes staan niet op de verlichtingskaart."
            else "Automatisch kiest Kijk zelf op naam. Terug = klaar.", size = Kijk.Caption, color = Kijk.Secondary, maxLines = 2)
        Spacer(Modifier.height(10.dp))
        val first = remember { FocusRequester() }
        LaunchedEffect(options.isNotEmpty()) { delay(80); runCatching { first.requestFocus() } }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            if (slot != "hide") item {
                FocusCard(onClick = { vm.hySetChoice(slot, ""); nav.pop() }, modifier = Modifier.fillMaxWidth().focusRequester(first), selected = current.isEmpty(), focusedScale = 1.01f) { _ ->
                    Label("Automatisch", Modifier.padding(horizontal = 14.dp, vertical = 12.dp), weight = Kijk.Medium)
                }
            }
            if (options.isEmpty()) item { Label(if (vm.hyError != null) "Homey niet bereikbaar: ${vm.hyError}" else "Laden…", Modifier.padding(8.dp), color = Kijk.Secondary, maxLines = 3) }
            options.forEachIndexed { i, (id, name, sub) ->
                item(key = id) {
                    val sel = if (slot == "hide") name.lowercase() in vm.hyHidden else id == current
                    FocusCard(onClick = { if (slot == "hide") vm.hyToggleHidden(name) else { vm.hySetChoice(slot, id); nav.pop() } },
                        modifier = Modifier.fillMaxWidth().then(if (slot == "hide" && i == 0) Modifier.focusRequester(first) else Modifier), selected = sel, focusedScale = 1.01f) { f ->
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(name, Modifier.weight(1f), weight = Kijk.Medium)
                            if (sub.isNotEmpty()) Label(sub, size = Kijk.Caption, color = secondary(f))
                            if (slot == "hide") Label(if (sel) "  verborgen" else "  zichtbaar", size = Kijk.Caption, color = if (sel) Kijk.Live else secondary(f))
                        }
                    }
                }
            }
        }
    }
}

/** (id, naam, extra) per plek. */
fun homeyOptions(vm: AppViewModel, slot: String): Pair<String, List<Triple<String, String, String>>> {
    val zones = vm.hyZones
    fun dev(l: List<HDevice>) = l.sortedBy { it.name.lowercase() }.map { Triple(it.id, it.name, zones[it.zone]?.name ?: "") }
    return when (slot) {
        "thermo" -> "Thermostaat" to dev(vm.hyDevices.filter { "target_temperature" in it.caps })
        "airco1" -> "Airco 1" to dev(vm.hyDevices.filter { "target_temperature" in it.caps })
        "airco2" -> "Airco 2" to dev(vm.hyDevices.filter { "target_temperature" in it.caps })
        "afval" -> "Afval-app" to dev(vm.hyDevices)
        "bell" -> "Deurbel" to dev(vm.hyDevices.filter { it.image != null })
        "comfort" -> "Comfort-temperatuur (variabele)" to vm.hyVars.filter { it.type == "number" }.sortedBy { it.name.lowercase() }.map { Triple(it.id, it.name, it.value?.toString() ?: "") }
        "resume" -> "Flow: hervat schema" to vm.hyFlows.map { Triple(it.id, it.name, if (it.advanced) "geavanceerd" else "") }
        "snap" -> "Flow: nieuwe snapshot" to vm.hyFlows.map { Triple(it.id, it.name, if (it.advanced) "geavanceerd" else "") }
        else -> "Ruimtes verbergen" to zones.values.sortedBy { it.name.lowercase() }.map { Triple(it.id, it.name, "") }
    }
}
