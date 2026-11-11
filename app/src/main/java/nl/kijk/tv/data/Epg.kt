package nl.kijk.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.TimeZone

private class EpgDb(ctx: Context) : SQLiteOpenHelper(ctx, "epg.db", null, 2) {
    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE prog (cid TEXT NOT NULL, start INTEGER NOT NULL, stop INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT)")
        db.execSQL("CREATE INDEX prog_cid ON prog (cid, start)")
        db.execSQL("CREATE INDEX prog_stop ON prog (stop)")
        db.execSQL("CREATE TABLE chan (cid TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE disp (cid TEXT NOT NULL, name TEXT NOT NULL)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        listOf("prog", "chan", "disp", "meta").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }
}

data class EpgStats(val programmes: Int, val epgChannels: Int)
data class EpgHit(val cid: String, val names: List<String>, val count: Int, val now: String?)
data class FoundProgramme(val cid: String, val p: Programme)

/**
 * Lokale XMLTV-gids. Zelfde werking als de webversie: streamend importeren, 8 dagen terug/vooruit bewaren,
 * koppelen op eigen keuze > tvg-id > naam met hetzelfde land, en dubbele programma's weglaten.
 */
class EpgRepository(ctx: Context) {
    private val db: SQLiteDatabase = EpgDb(ctx.applicationContext).writableDatabase

    fun updated(): Long = db.rawQuery("SELECT v FROM meta WHERE k='updated'", null).use { c -> if (c.moveToFirst()) c.getString(0).toLongOrNull() ?: 0 else 0 }

    /** @param progress (melding?, gedownloade bytes, totaal) */
    fun refresh(urls: List<String>, progress: (String?, Long, Long) -> Unit): Int {
        val now = System.currentTimeMillis()
        val keepFrom = now - 8 * DAY
        val keepUntil = now + 8 * DAY
        var count = 0
        db.beginTransaction()
        try {
            listOf("prog", "chan", "disp").forEach { db.delete(it, null, null) }
            val insProg = db.compileStatement("INSERT INTO prog (cid, start, stop, title, descr) VALUES (?, ?, ?, ?, ?)")
            val insChan = db.compileStatement("INSERT INTO chan (cid, name) VALUES (?, ?)")
            val insDisp = db.compileStatement("INSERT INTO disp (cid, name) VALUES (?, ?)")
            urls.forEachIndexed { i, url ->
                progress(if (urls.size > 1) "Gids ${i + 1} van ${urls.size} downloaden" else "Gids downloaden", 0, 0)
                Net.open(url) { done, total -> progress(null, done, total) }.use { input ->
                    val p = Xml.newPullParser()
                    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    p.setInput(input, null)
                    var chanId: String? = null
                    var pCid: String? = null
                    var pStart = 0L; var pStop = 0L
                    var title: String? = null; var desc: String? = null
                    var ev = p.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            when (p.name) {
                                "channel" -> {
                                    chanId = p.getAttributeValue(null, "id")
                                    chanId?.let { id -> insChan.bindString(1, id); insChan.bindString(2, normalize(id)); insChan.executeInsert() }
                                }
                                "display-name" -> chanId?.let { id ->
                                    val raw = p.nextText().trim()
                                    if (raw.isNotEmpty()) {
                                        insChan.bindString(1, id); insChan.bindString(2, normalize(raw)); insChan.executeInsert()
                                        insDisp.bindString(1, id); insDisp.bindString(2, raw); insDisp.executeInsert()
                                    }
                                }
                                "programme" -> {
                                    pCid = p.getAttributeValue(null, "channel")
                                    pStart = parseTime(p.getAttributeValue(null, "start")) ?: 0L
                                    pStop = parseTime(p.getAttributeValue(null, "stop")) ?: 0L
                                    title = null; desc = null
                                }
                                "title" -> if (pCid != null && title == null) title = p.nextText().trim()
                                "desc" -> if (pCid != null && desc == null) desc = p.nextText().trim()
                            }
                        } else if (ev == XmlPullParser.END_TAG) {
                            when (p.name) {
                                "channel" -> chanId = null
                                "programme" -> {
                                    val cid = pCid; val t = title
                                    if (pStop <= pStart) pStop = pStart + 30 * MINUTE
                                    if (cid != null && !t.isNullOrEmpty() && pStart > 0 && pStop > keepFrom && pStart < keepUntil) {
                                        insProg.bindString(1, cid); insProg.bindLong(2, pStart); insProg.bindLong(3, pStop); insProg.bindString(4, t)
                                        val d = desc; if (d.isNullOrBlank()) insProg.bindNull(5) else insProg.bindString(5, d)
                                        insProg.executeInsert()
                                        if (++count % 5000 == 0) progress("Gids verwerken… $count programma's", -1, -1)
                                    }
                                    pCid = null
                                }
                            }
                        }
                        ev = p.next()
                    }
                }
            }
            db.execSQL("INSERT OR REPLACE INTO meta (k, v) VALUES ('updated', ?)", arrayOf(now.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return count
    }

    fun stats(): EpgStats {
        val progs = db.rawQuery("SELECT COUNT(*) FROM prog", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        val chans = db.rawQuery("SELECT COUNT(DISTINCT cid) FROM prog", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        return EpgStats(progs, chans)
    }

    /** Koppelt zenders aan de gids. Volgorde: eigen koppeling > tvg-id > naam (met hetzelfde land). */
    fun match(channels: List<Channel>, links: Map<String, String>): Map<String, String> {
        val ids = HashSet<String>()
        val lower = HashMap<String, String>()
        db.rawQuery("SELECT DISTINCT cid FROM prog", null).use { c -> while (c.moveToNext()) { val id = c.getString(0); ids += id; lower[id.lowercase()] = id } }
        val byName = HashMap<String, MutableList<String>>()
        db.rawQuery("SELECT cid, name FROM chan", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (id in ids) byName.getOrPut(c.getString(1)) { ArrayList(2) }.let { if (id !in it) it += id }
            }
        }
        val dispCountry = HashMap<String, String>()
        db.rawQuery("SELECT cid, name FROM disp", null).use { c -> while (c.moveToNext()) countryOf(c.getString(1))?.let { dispCountry.putIfAbsent(c.getString(0), it) } }

        fun cidCountry(cid: String): String? =
            Regex("""\.([a-z]{2})$""").find(cid.lowercase())?.let { cc(it.groupValues[1]) } ?: dispCountry[cid] ?: countryOf(cid)

        fun byNameFor(name: String, want: String?): String? {
            val cands = byName[normalize(name)] ?: return null
            if (want != null) {
                cands.firstOrNull { cidCountry(it) == want }?.let { return it }
                return cands.firstOrNull { cidCountry(it) == null }  // liever geen gids dan die van een ander land
            }
            return cands.firstOrNull { cidCountry(it) == null } ?: cands.first()
        }

        val out = HashMap<String, String>()
        for (ch in channels) {
            val link = links[ch.name]
            if (link != null) {
                if (link.isEmpty()) continue  // bewust geen gids
                if (link in ids) { out[ch.key] = link; continue }
                // gekozen gidszender bestaat niet meer: automatisch koppelen
            }
            val want = countryOf(ch.name) ?: countryOf(ch.group)
            val t = ch.tvgId
            val cid = t?.let { if (it in ids) it else lower[it.lowercase()] }
                ?: t?.let { byNameFor(it, want) }
                ?: byNameFor(ch.name, want)
                ?: ch.tvgName?.let { byNameFor(it, want) }
            if (cid != null) out[ch.key] = cid
        }
        return out
    }

    fun nowNext(at: Long): Map<String, List<Programme>> {
        val raw = HashMap<String, MutableList<Programme>>()
        db.rawQuery(
            "SELECT cid, start, stop, title, descr FROM prog WHERE stop > ? AND start < ? ORDER BY cid, start",
            arrayOf((at - 3 * HOUR).toString(), (at + 6 * HOUR).toString()),
        ).use { c -> while (c.moveToNext()) raw.getOrPut(c.getString(0)) { ArrayList() } += Programme(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)) }
        return raw.mapValues { (_, l) -> dedupe(l).filter { it.stop > at }.take(2) }
    }

    fun programmes(cid: String, from: Long, to: Long): List<Programme> {
        val out = ArrayList<Programme>()
        db.rawQuery(
            "SELECT start, stop, title, descr FROM prog WHERE cid = ? AND stop > ? AND start < ? ORDER BY start",
            arrayOf(cid, (from - 3 * HOUR).toString(), to.toString()),
        ).use { c -> while (c.moveToNext()) out += Programme(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3)) }
        return dedupe(out)
    }

    fun earliest(cid: String): Long? =
        db.rawQuery("SELECT MIN(start) FROM prog WHERE cid = ?", arrayOf(cid)).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

    /** Zoeken op programmatitel (voor het zoekscherm). */
    fun find(q: String, from: Long, to: Long, limit: Int = 400): List<FoundProgramme> {
        val out = ArrayList<FoundProgramme>()
        val seen = HashSet<String>()
        db.rawQuery(
            "SELECT cid, start, stop, title, descr FROM prog WHERE title LIKE ? AND stop > ? AND start < ? ORDER BY start LIMIT ?",
            arrayOf("%$q%", from.toString(), to.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) + "|" + c.getLong(1) / (5 * MINUTE) + "|" + c.getString(3).lowercase()
                if (seen.add(k)) out += FoundProgramme(c.getString(0), Programme(c.getLong(1), c.getLong(2), c.getString(3), c.getString(4)))
            }
        }
        return out
    }

    /** Gidszenders zoeken voor handmatig koppelen. */
    fun search(q: String, limit: Int = 60): List<EpgHit> {
        val like = "%" + q.trim() + "%"
        val cids = LinkedHashSet<String>()
        db.rawQuery("SELECT DISTINCT cid FROM disp WHERE name LIKE ? LIMIT ?", arrayOf(like, limit.toString())).use { c -> while (c.moveToNext()) cids += c.getString(0) }
        db.rawQuery("SELECT DISTINCT cid FROM prog WHERE cid LIKE ? LIMIT ?", arrayOf(like, limit.toString())).use { c -> while (c.moveToNext()) cids += c.getString(0) }
        val now = System.currentTimeMillis().toString()
        return cids.take(limit).map { cid ->
            val names = ArrayList<String>()
            db.rawQuery("SELECT DISTINCT name FROM disp WHERE cid = ? LIMIT 3", arrayOf(cid)).use { c -> while (c.moveToNext()) names += c.getString(0) }
            val n = db.rawQuery("SELECT COUNT(*) FROM prog WHERE cid = ?", arrayOf(cid)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val nowT = db.rawQuery("SELECT title FROM prog WHERE cid = ? AND start <= ? AND stop > ? LIMIT 1", arrayOf(cid, now, now)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            EpgHit(cid, names, n, nowT)
        }.sortedByDescending { it.count }
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
        const val DAY = 24 * HOUR

        private val COUNTRIES = setOf("nl", "be", "uk", "gb", "de", "fr", "us", "es", "it", "pt", "tr", "pl", "se", "no", "dk", "fi", "ch", "at",
            "ca", "au", "ie", "gr", "ro", "ru", "al", "ar", "in", "pk", "br", "mx", "hu", "cz", "sk", "hr", "rs", "bg", "ex")
        private val NOISE = setOf("hd", "fhd", "uhd", "sd", "4k", "8k", "hevc", "h264", "h265", "raw", "backup", "nl", "be", "vl", "tv",
            "uk", "gb", "de", "fr", "es", "pt", "tr", "pl", "se", "dk", "fi", "ch", "ca", "au", "ie", "gr", "ro", "ru")

        private fun cc(c: String): String? { val l = c.lowercase(); return if (l == "gb") "uk" else if (l in COUNTRIES) l else null }

        /** Land uit "NL: NPO 1", "NL | RTL 4", "NL 4K-Discovery", "|NL| …" of een id als "Discovery.nl". */
        fun countryOf(name: String?): String? {
            if (name.isNullOrBlank()) return null
            val s = name.trim()
            Regex("""^\W*([A-Za-z]{2})(?:\W|$)""").find(s)?.let { m -> cc(m.groupValues[1])?.let { return it } }
            return Regex("""\.([A-Za-z]{2})$""").find(s)?.let { cc(it.groupValues[1]) }
        }

        fun normalize(s: String): String {
            val noBrackets = s.lowercase().replace(Regex("""[(\[{].*?[)\]}]"""), " ")
            val noPrefix = noBrackets.replace(Regex("""^\s*[a-z]{2,3}\s*[:|]\s*"""), " ")
            val tokens = noPrefix.split(Regex("""[^\p{L}\p{N}+]+""")).filter { it.isNotEmpty() && it !in NOISE }
            return tokens.joinToString("").ifEmpty { noPrefix.filter { it.isLetterOrDigit() } }
        }

        /** Dubbele/overlappende programma's uit de gids van de aanbieder weglaten. */
        fun dedupe(progs: List<Programme>): List<Programme> {
            val out = ArrayList<Programme>()
            for (p0 in progs.sortedWith(compareBy<Programme>({ it.start }, { -(it.desc?.length ?: 0) }))) {
                var p = p0
                val last = out.lastOrNull()
                if (last != null && p.start < last.stop - 2 * MINUTE) {
                    val same = last.title.trim().equals(p.title.trim(), ignoreCase = true)
                    if (same || p.stop <= last.stop + 2 * MINUTE) {
                        if (last.desc.isNullOrBlank() && !p.desc.isNullOrBlank()) out[out.lastIndex] = last.copy(desc = p.desc)
                        continue
                    }
                    p = p.copy(start = last.stop)
                }
                out += p
            }
            return out
        }

        /** XMLTV-tijd: "20261110200000 +0100" */
        fun parseTime(s: String?): Long? {
            if (s == null || s.length < 12) return null
            return try {
                val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")); cal.clear()
                cal.set(s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                    s.substring(8, 10).toInt(), s.substring(10, 12).toInt(),
                    if (s.length >= 14 && s[12].isDigit()) s.substring(12, 14).toInt() else 0)
                var t = cal.timeInMillis
                val tz = s.substring(minOf(14, s.length)).trim()
                if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-')) {
                    val off = (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * MINUTE
                    t = if (tz[0] == '+') t - off else t + off
                }
                t
            } catch (_: Exception) { null }
        }
    }
}
