package nl.kijk.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos

/** Eén sessie van een raceweekend (tijd in ms, UTC). */
data class F1Session(val name: String, val start: Long)
data class F1Weekend(
    val name: String, val circuit: String, val place: String, val round: Int, val rounds: Int,
    val sessions: List<F1Session>,
    /** Vorm van het circuit, genormaliseerd naar 0..1 (x, y); leeg als onbekend. */
    val outline: List<Pair<Float, Float>>,
)

data class Match(
    val competition: String, val home: String, val away: String, val homeLogo: String?, val awayLogo: String?,
    val start: Long, val venue: String?, val psvHome: Boolean,
    /** "PSV" of "Oranje" (voor het zoeken in de gids) */
    val team: String = "PSV",
    val competitionLogo: String? = null,
    val slug: String = "",
)

/**
 * Sportgegevens voor Thuis:
 * - F1: Jolpica (openbare opvolger van Ergast, geen sleutel); circuitvormen uit bacinger/f1-circuits (MIT), één keer opgehaald en bewaard.
 * - PSV: ESPN (geen sleutel, niet-officieel; bij een fout blijft de kaart gewoon weg).
 */
class Sports(private val dir: File) {
    companion object {
        /** Officieel F1-logo zoals ESPN het op zijn F1-pagina's gebruikt (wordt geladen, niet nagetekend). */
        const val F1_LOGO = "https://a.espncdn.com/i/teamlogos/leagues/500/f1.png"
    }

    // ---------------------------------------------------------------- F1
    fun f1(now: Long = System.currentTimeMillis()): F1Weekend? {
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        for (y in listOf(year, year + 1)) {
            val races = runCatching {
                JSONObject(get("https://api.jolpi.ca/ergast/f1/$y.json") ?: return@runCatching null)
                    .getJSONObject("MRData").getJSONObject("RaceTable").getJSONArray("Races")
            }.getOrNull() ?: continue
            for (i in 0 until races.length()) {
                val r = races.getJSONObject(i)
                val raceStart = ts(r.optString("date"), r.optString("time")) ?: continue
                // weekend blijft staan tot 3 uur na de start van de race
                if (raceStart + 3 * 3600_000L < now) continue
                val sessions = ArrayList<F1Session>()
                listOf(
                    "FirstPractice" to "Vrije training 1", "SecondPractice" to "Vrije training 2", "ThirdPractice" to "Vrije training 3",
                    "SprintQualifying" to "Sprintkwalificatie", "SprintShootout" to "Sprintkwalificatie", "Sprint" to "Sprint",
                    "Qualifying" to "Kwalificatie",
                ).forEach { (k, label) ->
                    r.optJSONObject(k)?.let { o -> ts(o.optString("date"), o.optString("time"))?.let { sessions += F1Session(label, it) } }
                }
                sessions += F1Session("Race", raceStart)
                sessions.sortBy { it.start }
                // "Vrije training 1" heet gewoon "Vrije training" als er maar één is (sprintweekend)
                val fp = sessions.count { it.name.startsWith("Vrije training") }
                val list = if (fp == 1) sessions.map { if (it.name.startsWith("Vrije training")) it.copy(name = "Vrije training") else it } else sessions
                val c = r.getJSONObject("Circuit"); val loc = c.optJSONObject("Location")
                val lat = loc?.optString("lat")?.toDoubleOrNull(); val lon = loc?.optString("long")?.toDoubleOrNull()
                return F1Weekend(
                    name = r.optString("raceName"), circuit = c.optString("circuitName"),
                    place = listOfNotNull(loc?.optString("locality")?.ifEmpty { null }, loc?.optString("country")?.ifEmpty { null }).joinToString(", "),
                    round = r.optString("round").toIntOrNull() ?: 0, rounds = races.length(),
                    sessions = list, outline = if (lat != null && lon != null) outline(lat, lon) else emptyList(),
                )
            }
        }
        return null
    }

    /** Circuitvorm bij de coördinaten van het circuit (dichtstbijzijnde baan binnen ~10 km). */
    private fun outline(lat: Double, lon: Double): List<Pair<Float, Float>> {
        val f = File(dir, "f1-circuits.geojson")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 30L * 24 * 3600_000) {
            get("https://raw.githubusercontent.com/bacinger/f1-circuits/master/f1-circuits.geojson")?.let { f.writeText(it) }
        }
        if (!f.exists()) return emptyList()
        val feats = runCatching { JSONObject(f.readText()).getJSONArray("features") }.getOrNull() ?: return emptyList()
        var best: JSONArray? = null; var bestD = Double.MAX_VALUE
        for (i in 0 until feats.length()) {
            val b = feats.getJSONObject(i).optJSONArray("bbox") ?: continue
            val cx = (b.getDouble(0) + b.getDouble(2)) / 2; val cy = (b.getDouble(1) + b.getDouble(3)) / 2
            val d = (cx - lon) * (cx - lon) + (cy - lat) * (cy - lat)
            if (d < bestD) { bestD = d; best = feats.getJSONObject(i).getJSONObject("geometry").optJSONArray("coordinates") }
        }
        val coords = best ?: return emptyList()
        if (bestD > 0.01) return emptyList()   // geen baan in de buurt gevonden
        val k = cos(Math.toRadians(lat))
        val pts = (0 until coords.length()).map { val p = coords.getJSONArray(it); (p.getDouble(0) * k) to -p.getDouble(1) }
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }; val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        val span = maxOf(maxX - minX, maxY - minY).takeIf { it > 0 } ?: return emptyList()
        val step = maxOf(1, pts.size / 300)
        return pts.filterIndexed { i, _ -> i % step == 0 }.map { (((it.first - minX) / span).toFloat()) to (((it.second - minY) / span).toFloat()) }
    }

    // ---------------------------------------------------------------- PSV
    /** Eerstvolgende wedstrijd van PSV of het Nederlands elftal (mannen), welke het eerst is. */
    fun nextMatch(now: Long = System.currentTimeMillis()): Match? {
        val psv = team("148", "PSV", now, listOf("ned.1" to "Eredivisie", "uefa.champions" to "Champions League", "uefa.europa" to "Europa League",
            "uefa.europa.conf" to "Conference League", "ned.cup" to "KNVB Beker"))
        val oranje = team("449", "Oranje", now, listOf("uefa.nations" to "Nations League", "fifa.worldq.uefa" to "WK-kwalificatie",
            "fifa.world" to "WK", "uefa.euroq" to "EK-kwalificatie", "uefa.euro" to "EK", "fifa.friendly" to "Oefenwedstrijd"))
        val m = listOfNotNull(psv, oranje).minByOrNull { it.start } ?: return null
        return m.copy(competitionLogo = leagueLogo(m.slug))
    }

    /** Logo van de competitie (uit dezelfde bron), of null. */
    private fun leagueLogo(slug: String): String? = runCatching {
        val o = JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/scoreboard") ?: return@runCatching null)
        val l = o.optJSONArray("leagues")?.optJSONObject(0) ?: return@runCatching null
        val logos = l.optJSONArray("logos") ?: return@runCatching null
        // liefst het logo voor een donkere achtergrond
        (0 until logos.length()).map { logos.getJSONObject(it) }
            .sortedByDescending { lg -> lg.optJSONArray("rel")?.let { r -> (0 until r.length()).any { r.optString(it) == "dark" } } == true }
            .firstOrNull()?.optString("href")?.ifEmpty { null }
    }.getOrNull()

    private fun team(id: String, team: String, now: Long, leagues: List<Pair<String, String>>): Match? {
        var best: Match? = null
        for ((slug, label) in leagues) {
            val events = runCatching {
                JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/soccer/$slug/teams/$id/schedule?fixture=true") ?: return@runCatching null)
                    .optJSONArray("events")
            }.getOrNull() ?: continue
            for (i in 0 until events.length()) {
                val e = events.getJSONObject(i)
                val start = iso(e.optString("date")) ?: continue
                if (start + 2 * 3600_000L < now) continue           // tot 2 uur na de aftrap blijft hij staan
                if (best != null && start >= best.start) continue
                val comp = e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val teams = comp.optJSONArray("competitors") ?: continue
                var home: JSONObject? = null; var away: JSONObject? = null
                for (t in 0 until teams.length()) { val c = teams.getJSONObject(t); if (c.optString("homeAway") == "home") home = c else away = c }
                if (home == null || away == null) continue
                fun name(c: JSONObject) = c.optJSONObject("team")?.let { it.optString("shortDisplayName").ifEmpty { it.optString("displayName") } } ?: "?"
                fun logo(c: JSONObject) = c.optJSONObject("team")?.let { t -> t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifEmpty { null } ?: t.optString("logo").ifEmpty { null } }
                val ourHome = home.optJSONObject("team")?.optString("id") == id
                best = Match(label, name(home), name(away), logo(home), logo(away), start,
                    comp.optJSONObject("venue")?.optString("fullName")?.ifEmpty { null }, ourHome, team, slug = slug)
            }
        }
        return best
    }

    // ---------------------------------------------------------------- hulpjes
    private fun ts(date: String, time: String): Long? {
        if (date.length < 10) return null
        val t = time.ifEmpty { "12:00:00Z" }.removeSuffix("Z").let { if (it.length == 5) "$it:00" else it.take(8) }
        return runCatching { utc("yyyy-MM-dd HH:mm:ss").parse("$date $t")!!.time }.getOrNull()
    }
    private fun iso(s: String): Long? = runCatching {
        val x = s.removeSuffix("Z")
        utc(if (x.length > 16) "yyyy-MM-dd'T'HH:mm:ss" else "yyyy-MM-dd'T'HH:mm").parse(x.take(19))!!.time
    }.getOrNull()
    private fun utc(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Kijk-TV/1.0 (Android TV)")
        c.setRequestProperty("Accept", "application/json")
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null } finally { c.disconnect() }
    }
}
