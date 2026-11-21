package nl.kijk.tv.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
private val CupraC = Color(0xFFC8865A)
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(if (vm.hyCar != null) 14.dp else 18.dp)) {
            TempCard(vm, Modifier.weight(1f)) { panel = "temp" }
            LightCard(vm, Modifier.weight(1f)) { panel = "light" }
            AfvalCard(vm, Modifier.weight(1f)) { panel = "afval" }
            BellCard(vm, Modifier.weight(1f)) { if (vm.hyBell?.video != null) nav.push(nl.kijk.tv.Screen.Doorbell) else vm.hySnapshot() }
            if (vm.hyCar != null) CarCard(vm, Modifier.weight(1f)) { panel = "auto" }
        }
    }
    panel?.let { p -> HuisPanel(vm, p) { panel = null } }
}

@Composable
private fun HuisCard(modifier: Modifier, onClick: () -> Unit, backdrop: Int? = null, bias: Float = 1f, light: Boolean = false, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier.height(HuisH), shape = RoundedCornerShape(16.dp), focusedScale = 1.04f, darkFocus = true) { _ ->
        if (backdrop != null) CardBackdrop(backdrop, bias, light)
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
    HuisCard(modifier, onClick, backdrop = nl.kijk.tv.R.drawable.huis_temp) {
        CardHead("Temperatuur", TempC)
        Row(verticalAlignment = Alignment.Bottom) {
            Label(deg(vm.hyShown(t, "measure_temperature")), size = 30.sp, weight = Kijk.Bold, color = Color.White)
            if (t?.num("target_temperature") != null) Label("  → ${deg(vm.hyShown(t, "target_temperature"))}", Modifier.padding(bottom = 5.dp), size = 13.sp, color = GreyH)
        }
        listOfNotNull(vm.hyAirco1, vm.hyAirco2).forEach { a ->
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Label(a.name, Modifier.weight(1f), size = 12.sp, color = Color(0xFFD1D1D6))
                val on = a.bool("onoff") != false
                Label(if (on) degShort(a.num("target_temperature")) else "uit", size = 12.sp, weight = Kijk.Semibold, color = if (on) TempC else GreyH)
            }
        }
        Spacer(Modifier.weight(1f))
        CardFoot(if (t == null) "Geen thermostaat gevonden" else "Comfort ${deg(vm.hyShown(t, "target_temperature"))}")
    }
}

@Composable
private fun LightCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val rooms = vm.hyRooms
    val on = rooms.filter { it.on > 0 }
    HuisCard(modifier, onClick, backdrop = nl.kijk.tv.R.drawable.huis_licht) {
        CardHead("Verlichting", LightC)
        Row(verticalAlignment = Alignment.Bottom) {
            Label("${on.size}", size = 30.sp, weight = Kijk.Bold, color = Color.White)
            Label(if (on.size == 1) "  ruimte aan" else "  ruimtes aan", Modifier.padding(bottom = 5.dp), size = 13.sp, color = GreyH)
        }
        // de ruimtes die aan zijn, daarna een paar die uit zijn
        Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            (on + rooms.filter { it.on == 0 }).take(5).forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (r.on > 0) LightC else Color(0xFF3A3A40), RoundedCornerShape(50)))
                    Spacer(Modifier.width(6.dp))
                    Label(r.zone.name, size = 12.sp, color = if (r.on > 0) Color.White else GreyH)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        CardFoot(if (rooms.isEmpty()) "Geen lampen gevonden" else "${rooms.size} ruimtes")
    }
}

// ---------------------------------------------------------------- afval

data class AfvalItem(val type: String, val date: Long?, val text: String, val color: Color? = null)

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
    val fromApp = vm.hyAfvalSettings?.let { o -> afvalFromTrashchecker(o, now).ifEmpty { afvalFromSettings(o, now) } }.orEmpty()
    if (fromApp.isNotEmpty()) return fromApp
    return afvalFromDevice(vm.hyAfval, now)
}

private val WASTE = listOf("gft", "groen", "papier", "pmd", "plastic", "pbd", "rest", "grof", "textiel", "kca", "glas", "kerst", "snoei", "luier")

/** Vaste, Nederlandse namen per afvalstroom (ook als de app Engelse teksten geeft). */
private fun wasteName(k: String): String {
    val l = k.lowercase().trim()
    return when {
        "gft" in l || "groen" in l || l == "green" -> "GFT"
        "rest" in l || "grey" in l || "gray" in l || "grijs" in l -> "Restafval"
        "pmd" in l || "plastic" in l || "pbd" in l || "packages" in l || "verpakking" in l -> "PMD"
        "papier" in l || "paper" in l -> "Oud papier"
        "textiel" in l || "textile" in l -> "Textiel"
        "glas" in l || "glass" in l -> "Glas"
        "grof" in l || "bulky" in l -> "Grofvuil"
        "snoei" in l || "pruning" in l -> "Snoeiafval"
        "kerst" in l || "christmas" in l -> "Kerstbomen"
        "kca" in l || "chemisch" in l || "chemical" in l -> "KCA"
        else -> k.trim().replaceFirstChar { it.uppercase() }
    }
}

/**
 * Afval Herinnering 2.0 (com.trashchecker): een lijst van { type: "GFT", localText?, color?, dates: [ISO, …] }.
 * Datums zijn tijdstippen in UTC (middernacht hier = 23:00 de dag ervoor), dus eerst naar lokale tijd.
 */
private fun afvalFromTrashchecker(o: org.json.JSONObject, now: Long): List<AfvalItem> {
    val arr = o.optJSONArray("data") ?: return emptyList()
    val today = dayStart(now)
    val out = mutableListOf<AfvalItem>()
    for (i in 0 until arr.length()) {
        val e = arr.optJSONObject(i) ?: continue
        val type = e.optString("type").takeIf { it.isNotBlank() } ?: continue
        val name = wasteName(type).takeIf { it != type.trim().replaceFirstChar { c -> c.uppercase() } }
            ?: e.optString("localText").takeIf { it.isNotBlank() && it != "null" }?.let { wasteName(it) } ?: wasteName(type)
        val color: Color? = null   // vaste Kijk-kleuren per stroom (zie afvalColor)
        val ds = e.optJSONArray("dates") ?: continue
        for (j in 0 until ds.length()) {
            val raw = ds.opt(j)
            val t = when (raw) { is Number -> raw.toLong(); is String -> nl.kijk.tv.data.Homey.ts(raw).takeIf { it > 0 } ?: parseAfvalDate(raw, now); else -> null } ?: continue
            val d = dayStart(t)
            if (d >= today && d < today + 180L * 86_400_000L) out += AfvalItem(name, d, "", color)
        }
    }
    return out.distinctBy { it.type + it.date }.sortedBy { it.date }
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

fun afvalColor(type: String): Color = when (wasteName(type)) {
    "GFT" -> Color(0xFF30D158)
    "Restafval" -> Color(0xFF8E8E93)
    "PMD" -> Color(0xFFFFD60A)
    "Oud papier" -> Color(0xFF0A84FF)
    "Textiel" -> Color(0xFFBF5AF2)
    "Glas" -> Color(0xFF64D2FF)
    "Grofvuil" -> Color(0xFFAC8E68)
    "Snoeiafval" -> Color(0xFF248A3D)
    "Kerstbomen" -> Color(0xFF1E7B34)
    "KCA" -> Color(0xFFFF453A)
    else -> Color(0xFF8E8E93)
}

/** Icoon per afvalstroom in zijn eigen kleur: kliko, zak (PMD), papier, shirt (textiel), fles (glas). */
@Composable
fun WasteIcon(type: String, size: androidx.compose.ui.unit.Dp) {
    val c = afvalColor(type)
    val kind = wasteName(type)
    val res = when (kind) {
        "GFT", "Snoeiafval" -> nl.kijk.tv.R.drawable.ic_waste_gft
        "PMD" -> nl.kijk.tv.R.drawable.ic_waste_pmd
        "Oud papier" -> nl.kijk.tv.R.drawable.ic_waste_papier
        "Textiel" -> nl.kijk.tv.R.drawable.ic_waste_textiel
        "Glas" -> null
        else -> nl.kijk.tv.R.drawable.ic_waste_rest
    }
    if (res != null) {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(res), null, Modifier.size(size),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(c))
        return
    }
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        when (kind) {
            "PMD" -> {
                val bag = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.32f, w * 0.24f); quadraticTo(w * 0.5f, w * 0.12f, w * 0.68f, w * 0.24f)
                    lineTo(w * 0.80f, w * 0.84f); quadraticTo(w * 0.5f, w * 0.96f, w * 0.20f, w * 0.84f); close()
                }
                drawPath(bag, c)
                drawLine(Color(0xFF16161C), androidx.compose.ui.geometry.Offset(w * 0.40f, w * 0.24f), androidx.compose.ui.geometry.Offset(w * 0.60f, w * 0.24f), w * 0.05f)
            }
            "Oud papier" -> {
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.16f, w * 0.20f), androidx.compose.ui.geometry.Size(w * 0.68f, w * 0.62f), androidx.compose.ui.geometry.CornerRadius(w * 0.06f))
                for (k in 0..2) drawRect(Color(0xFF16161C), androidx.compose.ui.geometry.Offset(w * 0.28f, w * (0.33f + 0.14f * k)), androidx.compose.ui.geometry.Size(if (k == 2) w * 0.26f else w * 0.44f, w * 0.06f))
            }
            "Textiel" -> {
                val shirt = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.34f, w * 0.18f); lineTo(w * 0.16f, w * 0.30f); lineTo(w * 0.24f, w * 0.46f); lineTo(w * 0.32f, w * 0.42f)
                    lineTo(w * 0.32f, w * 0.84f); lineTo(w * 0.68f, w * 0.84f); lineTo(w * 0.68f, w * 0.42f); lineTo(w * 0.76f, w * 0.46f)
                    lineTo(w * 0.84f, w * 0.30f); lineTo(w * 0.66f, w * 0.18f); quadraticTo(w * 0.5f, w * 0.30f, w * 0.34f, w * 0.18f); close()
                }
                drawPath(shirt, c)
            }
            "Glas" -> {
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.32f, w * 0.40f), androidx.compose.ui.geometry.Size(w * 0.36f, w * 0.46f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f))
                drawRect(c, androidx.compose.ui.geometry.Offset(w * 0.43f, w * 0.14f), androidx.compose.ui.geometry.Size(w * 0.14f, w * 0.30f))
            }
            else -> {   // kliko
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.22f, w * 0.30f), androidx.compose.ui.geometry.Size(w * 0.56f, w * 0.54f), androidx.compose.ui.geometry.CornerRadius(w * 0.07f))
                drawRoundRect(c, androidx.compose.ui.geometry.Offset(w * 0.16f, w * 0.18f), androidx.compose.ui.geometry.Size(w * 0.68f, w * 0.10f), androidx.compose.ui.geometry.CornerRadius(w * 0.04f))
                drawCircle(Color(0xFF16161C), w * 0.05f, androidx.compose.ui.geometry.Offset(w * 0.32f, w * 0.86f))
                drawCircle(Color(0xFF16161C), w * 0.05f, androidx.compose.ui.geometry.Offset(w * 0.68f, w * 0.86f))
            }
        }
    }
}

private val dayFmt get() = SimpleDateFormat("EEE d MMM", Locale("nl"))
private val shortDay get() = SimpleDateFormat("EEE d", Locale("nl"))

@Composable
private fun AfvalCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val items = afvalItems(vm)
    val today = dayStart(vm.now)
    val next = items.firstOrNull { (it.date ?: Long.MAX_VALUE) >= today } ?: items.firstOrNull()
    HuisCard(modifier, onClick, backdrop = nl.kijk.tv.R.drawable.huis_afval) {
        CardHead("Afval", AfvalC)
        if (next == null) {
            Label(if (vm.hyAfval == null && vm.hyAfvalSettings == null) "Geen afval-app gevonden" else "Geen ophaaldagen", Modifier.padding(top = 8.dp), size = 13.sp, color = GreyH, maxLines = 2)
        } else {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                WasteIcon(next.type, 34.dp)
                Spacer(Modifier.width(7.dp))
                Column {
                    Label(next.date?.let { dayFmt.format(it) } ?: next.text, size = 17.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
                    Label(next.type, size = 13.sp, color = Color(0xFFD1D1D6), maxLines = 1)
                }
            }
            items.filter { it.type != next.type }.distinctBy { it.type }.take(4).forEach {
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    WasteIcon(it.type, 15.dp)
                    Spacer(Modifier.width(5.dp))
                    Label("${it.date?.let { d -> shortDay.format(d) } ?: it.text} · ${it.type}", size = 12.sp, color = GreyH, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val days = next?.date?.let { ((it - today) / 86_400_000L).toInt() }
        val sameDay = items.filter { it.date == next?.date }.map { it.type }.distinct().joinToString(" en ")
        when (days) {
            1 -> CardFoot("Vanavond buiten zetten: $sameDay", TempC)
            0 -> CardFoot("Vandaag: $sameDay", TempC)
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
    // beeld uit het live kijken alleen als het nieuwer is dan dat van Homey, en hooguit een kwartier
    val live = vm.liveFrame?.takeIf { it.second > imgAt && vm.now - it.second < 15 * 60_000L }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF22222A)), contentAlignment = Alignment.Center) {
        val snap = vm.hySnap?.takeIf { d?.id == vm.hyBell?.id }
        if (live != null && (snap == null || live.second > snap.second)) androidx.compose.foundation.Image(remember(live) { live.first.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (snap != null) androidx.compose.foundation.Image(remember(snap) { snap.first.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (path == null) Label("Geen beeld", size = 12.sp, color = GreyH)
        else {
            val url = vm.homey.imageUrl(path, if (imgAt > 0) imgAt else vm.hyAt)
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
    HuisCard(modifier, onClick, backdrop = nl.kijk.tv.R.drawable.huis_bel) {
        CardHead("Deurbel", BellC)
        HomeyImage(vm, b, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp, bottom = 5.dp))
        val ring = vm.hyRings.firstOrNull()
        Label(when {
            ring != null -> "Aangebeld ${ringTime(ring, vm.now)}"
            (vm.liveFrame?.second ?: 0L) > maxOf(b?.imageAt ?: 0L, vm.hySnap?.second ?: 0L) && vm.now - vm.liveFrame!!.second < 15 * 60_000L -> "Beeld ${ringTime(vm.liveFrame!!.second, vm.now)}"
            vm.hySnap != null -> "Beeld ${ringTime(vm.hySnap!!.second, vm.now)}"
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
            // achtergrond van het venster: hetzelfde beeld als de kaart, gedimd zodat de regels leesbaar blijven
            val (bg, bias) = when (kind) { "temp" -> nl.kijk.tv.R.drawable.huis_temp to 1f; "light" -> nl.kijk.tv.R.drawable.huis_licht to 1f
                "auto" -> nl.kijk.tv.R.drawable.huis_cupra to 0.88f; else -> nl.kijk.tv.R.drawable.huis_afval to 0.95f }
            Box(Modifier.align(Alignment.CenterEnd).width(480.dp).fillMaxHeight().background(PanelBg)) {
                androidx.compose.foundation.Image(backdropBitmap(bg), null, Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop, alignment = androidx.compose.ui.BiasAlignment(bias, 0f))
                Box(Modifier.matchParentSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color(0xE60B0B0F), Color(0xB30B0B0F), Color(0xD90B0B0F)))))
            }
            Column(Modifier.align(Alignment.CenterEnd).width(480.dp).fillMaxHeight()
                .padding(start = 26.dp, end = Kijk.SafeH, top = Kijk.SafeV + 6.dp, bottom = Kijk.SafeV)) {
                val (title, color) = when (kind) { "temp" -> "Temperatuur" to TempC; "light" -> "Verlichting" to LightC; "auto" -> "Cupra Leon" to CupraC; else -> "Afval" to AfvalC }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
                    Spacer(Modifier.width(10.dp))
                    Label(title, size = Kijk.Title, weight = Kijk.Bold, color = Color.White)
                }
                Label(when (kind) { "auto" -> "Terug = sluiten"; "temp" -> "‹ › = comfort-temperatuur · Terug = sluiten"; "light" -> "OK = aan/uit · ‹ › = dimmen · Terug = sluiten"; else -> "Terug = sluiten" },
                    Modifier.padding(top = 2.dp, bottom = 10.dp), size = 12.sp, color = GreyH)
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    when (kind) {
                        "temp" -> tempPanel(vm, first)
                        "light" -> lightPanel(vm, first)
                        "auto" -> carPanel(vm, first)
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
    var f by remember { mutableStateOf(false) }
    FocusCard(onClick = onClick, focusedScale = 1.02f, modifier = modifier.fillMaxWidth().onFocusChanged { f = it.isFocused }.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onLeft?.invoke(); onLeft != null }
            Key.DirectionRight -> { onRight?.invoke(); onRight != null }
            else -> false
        }
    }) { _ ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(title, size = Kijk.Body, weight = Kijk.Medium, color = Color.White)
                if (sub != null) Label(sub, size = 12.sp, color = Kijk.Secondary)
            }
            if (onLeft != null && f) Label("‹  ", size = Kijk.Body, color = Kijk.Secondary)
            Label(value, size = Kijk.Body, weight = Kijk.Semibold, color = valueColor)
            if (onRight != null && f) Label("  ›", size = Kijk.Body, color = Kijk.Secondary)
        }
    }
}

@Composable
private fun HuisHeader(text: String) {
    Label(text, Modifier.padding(start = 4.dp, top = 10.dp, bottom = 2.dp), size = 12.sp, weight = Kijk.Semibold, color = GreyH)
}

private fun androidx.compose.foundation.lazy.LazyListScope.tempPanel(vm: AppViewModel, first: FocusRequester) {
    // Alleen de comfort-temperatuur (= setpoint van de thermostaat) en terug naar programma.
    // De rest regelt het verwarmingsscript in Homey zelf; daar wordt via de tv niet aan gezeten.
    val t = vm.hyThermostat
    if (t == null) { item { Label("Geen thermostaat gevonden in Homey.", size = Kijk.Body, color = GreyH) }; return }
    item(key = "comfort") {
        PanelRow("Comfort-temperatuur", deg(vm.hyShown(t, "target_temperature")), Modifier.focusRequester(first), TempC,
            sub = "nu ${deg(vm.hyShown(t, "measure_temperature"))} · geldt tot het volgende programmamoment",
            onLeft = { vm.hyTarget(t, -0.5) }, onRight = { vm.hyTarget(t, 0.5) })
    }
    if (vm.hyResume != null) item(key = "resume") {
        Box(Modifier.padding(top = 10.dp)) { PanelRow("Terug naar programma", "", sub = "de thermostaat weer op het schema") { vm.hyResumeSchedule() } }
    }
    val aircos = listOfNotNull(vm.hyAirco1, vm.hyAirco2)
    if (aircos.isNotEmpty()) {
        item(key = "h1") { HuisHeader("Airco's (regelt Homey zelf)") }
        aircos.forEach { a ->
            item(key = "a" + a.id) {
                val on = a.bool("onoff") != false
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Label(a.name, Modifier.weight(1f), size = Kijk.Body, color = Color(0xFFD1D1D6))
                    Label(if (on) deg(a.num("target_temperature")) else "uit", size = Kijk.Body, color = if (on) TempC else GreyH)
                }
            }
        }
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
                    sub = null,
                    onLeft = if (r.dimmable) ({ vm.hyDimRoom(r, -0.1) }) else null, onRight = if (r.dimmable) ({ vm.hyDimRoom(r, 0.1) }) else null) { vm.hyToggleRoom(r) }
            }
        }
    }
    item(key = "alloff") { Box(Modifier.padding(top = 10.dp)) { PanelRow("Alles uit", "", sub = "alle lampen hierboven") { vm.hyAllOff() } } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.afvalPanel(vm: AppViewModel, first: FocusRequester) {
    val items = afvalItems(vm).groupBy { it.type }.values.flatMap { it.take(2) }.sortedBy { it.date }
    if (items.isEmpty()) { item { Label("Geen ophaaldagen gevonden. Kies de afval-app in Instellingen › Huis.", size = Kijk.Body, color = GreyH, maxLines = 3) }; return }
    items.forEachIndexed { i, it ->
        item(key = "af$i") {
            // rijen kunnen focus krijgen (alleen om te scrollen); Terug sluit
            var f by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                .onFocusChanged { st -> f = st.isFocused }.focusable()
                .background(if (f) Color(0xFF1B1B23) else Color.Transparent, RoundedCornerShape(10.dp))
                .border(2.dp, if (f) Kijk.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                WasteIcon(it.type, 26.dp)
                Spacer(Modifier.width(10.dp))
                Label(it.type, Modifier.weight(1f), size = Kijk.Body, color = Color.White)
                Label(it.date?.let { d -> dayFmt.format(d) } ?: it.text, size = Kijk.Body, weight = Kijk.Semibold, color = Color(0xFFD1D1D6))
            }
        }
    }
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
        "resume" -> "Flow: terug naar programma" to vm.hyFlows.map { Triple(it.id, it.name, if (it.advanced) "geavanceerd" else "") }
        "snap" -> "Flow: nieuwe snapshot" to vm.hyFlows.map { Triple(it.id, it.name, if (it.advanced) "geavanceerd" else "") }
        else -> "Ruimtes verbergen" to zones.values.sortedBy { it.name.lowercase() }.map { Triple(it.id, it.name, "") }
    }
}


// ---------------------------------------------------------------- auto (Cupra, uit Homey-variabelen)

private fun batteryColor(p: Int, charging: Boolean) = when {
    charging || p >= 50 -> Color(0xFF30D158)
    p >= 20 -> Color(0xFFFF9F0A)
    else -> Color(0xFFFF453A)
}

/** Batterij-icoon; tijdens het laden vult hij steeds rustig tot de doel-lading en pulseert het bliksemschichtje. */
@Composable
fun CarBattery(percent: Int, target: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val p = percent.coerceIn(0, 100) / 100f
    val goal = (target.coerceIn(percent, 100)) / 100f
    val anim = if (charging) androidx.compose.animation.core.rememberInfiniteTransition(label = "laden") else null
    val fill = anim?.animateFloat(p, goal.coerceAtLeast(p + 0.01f), androidx.compose.animation.core.infiniteRepeatable<Float>(
        androidx.compose.animation.core.tween<Float>(2400, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        androidx.compose.animation.core.RepeatMode.Restart), label = "vul")
    val bolt = anim?.animateFloat(0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable<Float>(
        androidx.compose.animation.core.tween<Float>(600), androidx.compose.animation.core.RepeatMode.Reverse), label = "bliksem")
    val col = batteryColor(percent, charging)
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val nub = w * 0.07f; val body = w - nub
        val sw = h * 0.08f
        drawRoundRect(Color(0xFFD1D1D6), androidx.compose.ui.geometry.Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(body - sw, h - sw),
            androidx.compose.ui.geometry.CornerRadius(h * 0.18f), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawRoundRect(Color(0xFFD1D1D6), androidx.compose.ui.geometry.Offset(body, h * 0.32f), androidx.compose.ui.geometry.Size(nub, h * 0.36f), androidx.compose.ui.geometry.CornerRadius(nub / 2))
        val pad = sw * 1.9f
        val f = (fill?.value ?: p).coerceIn(0f, 1f)
        drawRoundRect(col, androidx.compose.ui.geometry.Offset(pad, pad), androidx.compose.ui.geometry.Size((body - 2 * pad) * f, h - 2 * pad), androidx.compose.ui.geometry.CornerRadius(h * 0.08f))
        if (charging) {
            val cx = body / 2; val cy = h / 2; val s = h * 0.36f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx + s * 0.15f, cy - s); lineTo(cx - s * 0.45f, cy + s * 0.12f); lineTo(cx - s * 0.02f, cy + s * 0.12f)
                lineTo(cx - s * 0.15f, cy + s); lineTo(cx + s * 0.45f, cy - s * 0.12f); lineTo(cx + s * 0.02f, cy - s * 0.12f); close()
            }
            drawPath(path, Color.White, alpha = bolt?.value ?: 1f)
        }
    }
}

private val BatCyan = Color(0xFF22D8F5)

/** Statuslabel in Cupra-stijl: klein pilletje met gekleurde rand. */
@Composable
private fun CarChip(text: String, color: Color, bg: Color) {
    // donkere ondergrond zodat het label los komt van de achtergrondfoto
    Box(Modifier.background(Color(0xD90B0B0F), RoundedCornerShape(50)).background(bg, RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50))
        .padding(horizontal = 9.dp, vertical = 3.dp)) {
        Label(text, size = 12.sp, weight = Kijk.Semibold, color = color, maxLines = 1)
    }
}

/** Batterij in de stijl van de aangeleverde afbeelding: metalen behuizing (plaatje) met 5 vakjes die we zelf tekenen.
 *  Laden: lichtgolf door de gevulde vakjes, het vakje dat nu laadt pulseert, bliksem gloeit. Niet laden: stilstaand (geen animatie). */
@Composable
fun CupraBattery(percent: Int, charging: Boolean, modifier: Modifier = Modifier) {
    val p = percent.coerceIn(0, 100)
    val low = p < 20
    val top = if (low) Color(0xFFFF9F45) else Color(0xFF5CF2FF)
    val bottom = if (low) Color(0xFFE5402F) else Color(0xFF0098C8)
    val anim = if (charging) androidx.compose.animation.core.rememberInfiniteTransition(label = "laden") else null
    val wave = anim?.animateFloat(-0.3f, 1.3f, androidx.compose.animation.core.infiniteRepeatable<Float>(
        androidx.compose.animation.core.tween<Float>(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "golf")
    val pulse = anim?.animateFloat(0.45f, 1f, androidx.compose.animation.core.infiniteRepeatable<Float>(
        androidx.compose.animation.core.tween<Float>(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "puls")
    Box(modifier) {
        androidx.compose.foundation.Image(backdropBitmap(nl.kijk.tv.R.drawable.cupra_batterij, alpha = true), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val W = size.width; val H = size.height
            // binnenkant van de behuizing (verhoudingen uit de afbeelding)
            val x0 = W * 0.072f; val x1 = W * 0.847f; val y0 = H * 0.155f; val y1 = H * 0.843f
            val gap = (x1 - x0) * 0.022f
            val segW = (x1 - x0 - 4 * gap) / 5
            val r = androidx.compose.ui.geometry.CornerRadius(H * 0.06f)
            val brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(top, bottom), y0, y1)
            val charge = ((p - 1) / 20).coerceIn(0, 4)   // vakje dat nu laadt
            for (i in 0 until 5) {
                val xa = x0 + i * (segW + gap)
                val fill = ((p - i * 20) / 20f).coerceIn(0f, 1f)
                // leeg vakje: donker glas met een zachte rand, zoals het 5e vakje op de afbeelding
                drawRoundRect(bottom.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(xa, y0), androidx.compose.ui.geometry.Size(segW, y1 - y0), r)
                if (fill <= 0f) continue
                val a = if (charging && i == charge) (pulse?.value ?: 1f) else 1f
                drawRoundRect(brush, androidx.compose.ui.geometry.Offset(xa, y0), androidx.compose.ui.geometry.Size(segW * fill, y1 - y0), r, alpha = a)
                // glans bovenin
                drawRoundRect(Color.White.copy(alpha = 0.18f * a), androidx.compose.ui.geometry.Offset(xa + segW * 0.08f, y0 + (y1 - y0) * 0.06f),
                    androidx.compose.ui.geometry.Size(segW * fill * 0.84f, (y1 - y0) * 0.16f), r)
            }
            if (charging) {
                // lichtgolf over het gevulde deel
                val w = wave?.value ?: 0f
                val filledEnd = x0 + (x1 - x0) * (p / 100f)
                val cx = x0 + (x1 - x0) * w
                if (cx < filledEnd + segW) drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), cx - segW, cx + segW),
                    androidx.compose.ui.geometry.Offset(x0, y0), androidx.compose.ui.geometry.Size(filledEnd - x0, y1 - y0))
                // bliksem in het midden
                val bx = (x0 + x1) / 2; val by = (y0 + y1) / 2; val s = (y1 - y0) * 0.42f
                val bolt = androidx.compose.ui.graphics.Path().apply {
                    moveTo(bx + s * 0.22f, by - s); lineTo(bx - s * 0.42f, by + s * 0.14f); lineTo(bx - s * 0.02f, by + s * 0.14f)
                    lineTo(bx - s * 0.22f, by + s); lineTo(bx + s * 0.42f, by - s * 0.14f); lineTo(bx + s * 0.02f, by - s * 0.14f); close()
                }
                drawPath(bolt, top, alpha = 0.35f * (pulse?.value ?: 1f), style = androidx.compose.ui.graphics.drawscope.Stroke(s * 0.35f))
                drawPath(bolt, Color.White, alpha = 0.7f + 0.3f * (pulse?.value ?: 1f))
            }
        }
    }
}

@Composable
private fun CarCard(vm: AppViewModel, modifier: Modifier, onClick: () -> Unit) {
    val car = vm.hyCar ?: return
    val bat = car.battery ?: 0
    HuisCard(modifier, onClick, backdrop = nl.kijk.tv.R.drawable.huis_cupra, bias = 0.85f, light = true) {
        // koperen Cupra-logo met woordmerk (aangeleverd), in plaats van tekst + los logo
        androidx.compose.foundation.Image(backdropBitmap(nl.kijk.tv.R.drawable.cupra_woordmerk, alpha = true), "Cupra",
            Modifier.height(28.dp).offset(x = (-4).dp), contentScale = ContentScale.FillHeight, alignment = Alignment.CenterStart)   // incl. ingebakken slagschaduw
        Spacer(Modifier.weight(1f))   // de auto staat nu in de achtergrond
        // batterij, percentage en bereik op één regel
        Row(verticalAlignment = Alignment.CenterVertically) {
            CupraBattery(bat, car.charging, Modifier.size(width = 54.dp, height = 20.dp))
            Spacer(Modifier.width(7.dp))
            Label("$bat", size = 19.sp, weight = Kijk.Bold, color = Color.White, maxLines = 1)
            Label("%", Modifier.padding(start = 1.dp), size = 11.sp, weight = Kijk.Semibold, color = CupraC)
            car.range?.let { Label("  $it km", size = 12.sp, weight = Kijk.Medium, color = Color(0xFFD1D1D6), maxLines = 1) }
        }
        Spacer(Modifier.height(7.dp))
        val tgt = car.target?.takeIf { it in 1..99 }?.let { " · tot $it%" } ?: ""
        when {
            car.charging -> CarChip("⚡ Laadt$tgt", BatCyan, Color(0x2600C8EB))
            car.plugged -> CarChip("Aan de lader", CupraC, Color(0x26C8865A))
            else -> CarChip("Niet aan de lader", GreyH, Color(0x1AFFFFFF))
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.carPanel(vm: AppViewModel, first: FocusRequester) {
    val car = vm.hyCar ?: return
    val rows = listOfNotNull(
        "Accu" to "${car.battery ?: "–"}%",
        car.range?.let { "Bereik elektrisch" to "$it km" },
        car.target?.let { "Doel-lading" to "$it%" },
        "Laden" to if (car.charging) "laadt" else "laadt niet",
        "Stekker" to if (car.plugged) "erin" else "eruit",
        car.updated?.let { "Bijgewerkt" to it },
    )
    item(key = "foto") {
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(nl.kijk.tv.R.drawable.car_cupra), null,
            Modifier.fillMaxWidth().height(150.dp), contentScale = ContentScale.Fit)
    }
    rows.forEachIndexed { i, (k, v) ->
        item(key = "c$i") {
            var f by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                .onFocusChanged { st -> f = st.isFocused }.focusable()
                .background(if (f) Color(0xFF1B1B23) else Color.Transparent, RoundedCornerShape(10.dp))
                .border(2.dp, if (f) Kijk.Accent else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(k, Modifier.weight(1f), size = Kijk.Body, color = Color.White)
                Label(v, size = Kijk.Body, weight = Kijk.Semibold, color = Color(0xFFD1D1D6))
            }
        }
    }
}
