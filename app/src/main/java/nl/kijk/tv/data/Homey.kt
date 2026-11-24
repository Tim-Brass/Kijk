package nl.kijk.tv.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Eén waarde van een Homey-apparaat (bv. measure_temperature). */
data class HCap(val id: String, val title: String, val value: Any?, val units: String, val updated: Long)

data class HDevice(
    val id: String, val name: String, val zone: String, val cls: String,
    val caps: Map<String, HCap>, val image: String?, val imageAt: Long, val video: String? = null,
) {
    fun num(c: String): Double? = (caps[c]?.value as? Number)?.toDouble()
    fun bool(c: String): Boolean? = caps[c]?.value as? Boolean
    val isLight get() = "onoff" in caps && (cls == "light" || "light_hue" in caps || "light_temperature" in caps)
}

data class HZone(val id: String, val name: String, val parent: String?)
data class HVar(val id: String, val name: String, val type: String, val value: Any?)
data class HFlow(val id: String, val name: String, val advanced: Boolean)

/**
 * Homey Pro via de lokale Web API (alleen in je eigen netwerk, geen cloud).
 * IP en API-sleutel komen uit kijk-sleutels.json in de privé repo en blijven alleen op de tv.
 */
class Homey(
    private val ipOf: () -> String, private val keyOf: () -> String,
    private val lastOf: () -> String, private val saveLast: (String) -> Unit,
) {
    /** Alleen de sleutel is nodig: het adres zoekt Kijk zo nodig zelf in het netwerk. */
    val configured get() = keyOf().isNotBlank()
    val key: String get() = keyOf().trim()
    private val host: String get() = lastOf().ifBlank { ipOf() }.trim().trimEnd('/').removePrefix("http://")
    val base: String get() = "http://$host"

    private class Unreachable(e: Exception) : IOException(e.message, e)

    private fun call(method: String, path: String, body: String? = null): String = try {
        if (host.isBlank()) throw Unreachable(IOException("adres onbekend"))
        callAt(host, method, path, body)
    } catch (e: Unreachable) {
        // niet bereikbaar op het bekende adres: in het netwerk zoeken en nog één keer proberen
        val found = discover() ?: throw IOException("Homey niet gevonden in het netwerk")
        callAt(found, method, path, body)
    }

    /** Zoekt de Homey: eerst het opgegeven IP, dan het eigen subnet (alleen een antwoord op de juiste sleutel telt). */
    @Volatile private var failedAt = 0L
    /** Handmatig opnieuw zoeken (Instellingen): de wachttijd na een mislukte zoektocht vervalt. */
    fun forgetFailure() { failedAt = 0L }

    @Synchronized fun discover(): String? {
        if (System.currentTimeMillis() - failedAt < 10 * 60_000L) return null   // niet elke minuut het hele netwerk af
        fun ok(h: String) = runCatching { callAt(h, "GET", "/api/manager/zones/zone/", null, quick = true).trim().startsWith("{") }.getOrDefault(false)
        ipOf().trim().removePrefix("http://").trimEnd('/').takeIf { it.isNotBlank() && it != lastOf() && ok(it) }?.let { saveLast(it); return it }
        val me = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is java.net.Inet4Address && it.isSiteLocalAddress }?.hostAddress ?: run { failedAt = System.currentTimeMillis(); return null }
        val prefix = me.substringBeforeLast('.')
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        try {
            val cs = java.util.concurrent.ExecutorCompletionService<String?>(pool)
            val hosts = (1..254).map { "$prefix.$it" }.filter { it != me && it != lastOf() }
            hosts.forEach { h -> cs.submit { if (ok(h)) h else null } }
            repeat(hosts.size) {
                val r = cs.poll(8, java.util.concurrent.TimeUnit.SECONDS)?.get() ?: return@repeat
                saveLast(r); return r
            }
        } finally { pool.shutdownNow() }
        failedAt = System.currentTimeMillis()
        return null
    }

    private fun callAt(h: String, method: String, path: String, body: String?, quick: Boolean = false): String {
        val c = URL("http://$h$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = if (quick) 700 else 3_000; c.readTimeout = if (quick) 2_000 else 6_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                try { c.outputStream.use { it.write(body.toByteArray()) } }
                catch (e: java.net.ConnectException) { throw Unreachable(e) } catch (e: java.net.SocketTimeoutException) { throw Unreachable(e) }
            }
            val code = try { c.responseCode } catch (e: java.net.SocketTimeoutException) { throw Unreachable(e) }
                catch (e: java.net.ConnectException) { throw Unreachable(e) } catch (e: java.net.NoRouteToHostException) { throw Unreachable(e) }
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("Homey antwoordt $code ${txt.take(100)}")
            return txt
        } finally { c.disconnect() }
    }

    fun devices(): List<HDevice> {
        val o = JSONObject(call("GET", "/api/manager/devices/device/"))
        return o.keys().asSequence().mapNotNull { id ->
            val d = o.optJSONObject(id) ?: return@mapNotNull null
            val capsObj = d.optJSONObject("capabilitiesObj")
            val caps = mutableMapOf<String, HCap>()
            capsObj?.keys()?.forEach { k ->
                val c = capsObj.optJSONObject(k) ?: return@forEach
                val v = c.opt("value").takeIf { it != JSONObject.NULL }
                caps[k] = HCap(k, c.optString("title", k), v, c.optString("units", "").takeIf { it != "null" } ?: "", ts(c.opt("lastUpdated")))
            }
            var img: String? = null; var imgAt = 0L
            d.optJSONArray("images")?.let { a ->
                for (i in 0 until a.length()) {
                    val im = a.optJSONObject(i) ?: continue
                    val io = im.optJSONObject("imageObj")
                    val url = io?.optString("url")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: io?.optString("id")?.takeIf { it.isNotBlank() }?.let { "/api/image/$it" }
                        ?: im.optString("imageId").takeIf { it.isNotBlank() }?.let { "/api/image/$it" }
                    if (url != null) {
                        // meerdere beelden: het nieuwste nemen
                        val at = listOf(io?.opt("lastUpdated"), im.opt("lastUpdated"), io?.opt("updatedAt"), im.opt("updatedAt")).maxOf { ts(it) }
                        if (img == null || at > imgAt) { img = url; imgAt = at }
                    }
                }
            }
            // live beeld (Homey "videos", WebRTC)
            var vid: String? = null
            d.optJSONArray("videos")?.let { a ->
                for (i in 0 until a.length()) {
                    val v = a.optJSONObject(i) ?: continue
                    vid = v.optJSONObject("videoObj")?.optString("id")?.takeIf { it.isNotBlank() && it != "null" }
                        ?: v.optString("videoId").takeIf { it.isNotBlank() && it != "null" } ?: v.optString("id").takeIf { it.isNotBlank() && it != "null" }
                    if (vid != null) break
                }
            }
            if (vid == null) d.optJSONObject("video")?.optString("id")?.takeIf { it.isNotBlank() }?.let { vid = it }
            HDevice(id, d.optString("name"), d.optString("zone"), d.optString("virtualClass").takeIf { it.isNotBlank() && it != "null" } ?: d.optString("class"),
                caps, img, imgAt, vid)
        }.toList()
    }

    fun zones(): Map<String, HZone> {
        val o = JSONObject(call("GET", "/api/manager/zones/zone/"))
        return o.keys().asSequence().mapNotNull { id ->
            val z = o.optJSONObject(id) ?: return@mapNotNull null
            id to HZone(id, z.optString("name"), z.optString("parent").takeIf { it.isNotBlank() && it != "null" })
        }.toMap()
    }

    fun variables(): List<HVar> {
        val o = JSONObject(call("GET", "/api/manager/logic/variable/"))
        return o.keys().asSequence().mapNotNull { id ->
            val v = o.optJSONObject(id) ?: return@mapNotNull null
            HVar(id, v.optString("name"), v.optString("type"), v.opt("value"))
        }.toList()
    }

    fun flows(): List<HFlow> {
        fun read(path: String, adv: Boolean) = runCatching {
            val o = JSONObject(call("GET", path))
            o.keys().asSequence().mapNotNull { id -> o.optJSONObject(id)?.let { HFlow(id, it.optString("name"), adv) } }.toList()
        }.getOrDefault(emptyList())
        return (read("/api/manager/flow/flow/", false) + read("/api/manager/flow/advancedflow/", true)).sortedBy { it.name.lowercase() }
    }

    /**
     * Welke lampen een schakelaar aanstuurt, afgeleid uit je (geavanceerde) flows:
     * "<schakelaar> aangezet → (wacht/ALL) → <lamp> zet aan / dim naar …".
     */
    fun switchTargets(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        val o = runCatching { JSONObject(call("GET", "/api/manager/flow/advancedflow/")) }.getOrNull() ?: return out
        fun dev(uri: String) = uri.removePrefix("homey:device:").takeIf { uri.startsWith("homey:device:") }
        for (fid in o.keys()) {
            val cards = o.optJSONObject(fid)?.optJSONObject("cards") ?: continue
            for (cid in cards.keys()) {
                val c = cards.optJSONObject(cid) ?: continue
                if (c.optString("type") != "trigger") continue
                val sw = dev(c.optString("ownerUri")) ?: continue
                val what = c.optString("id").lowercase()
                if (!what.contains("turned_on") && !what.endsWith(":on") && !what.contains("onoff_true")) continue
                // volgen tot aan apparaat-acties (via wachten/ALL/ANY), hooguit een paar stappen diep
                val seen = mutableSetOf<String>()
                fun walk(id: String, depth: Int) {
                    if (depth > 4 || !seen.add(id)) return
                    val n = cards.optJSONObject(id) ?: return
                    val d = if (n.optString("type") == "action") dev(n.optString("ownerUri")) else null
                    if (d != null) { if (d != sw && !n.optString("id").lowercase().contains("off")) out.getOrPut(sw) { mutableSetOf() }.add(d); return }
                    n.optJSONArray("outputSuccess")?.let { a -> for (i in 0 until a.length()) walk(a.optString(i), depth + 1) }
                }
                c.optJSONArray("outputSuccess")?.let { a -> for (i in 0 until a.length()) walk(a.optString(i), 1) }
            }
        }
        return out
    }

    /** Instellingen van een app (bv. de ophaaldagen van "Afval Herinnering 2.0"). */
    fun appSettings(appId: String): JSONObject? = runCatching { JSONObject(call("GET", "/api/manager/apps/app/$appId/setting/")) }
        .onFailure { ImageLog.add("Homey app-instellingen $appId: ${it.message}") }.getOrNull()

    /** Ophaaldagen van Afval Herinnering 2.0: eerst de eigen app-API, dan de instelling "collectingDays". */
    fun trashDays(): JSONObject? {
        for (path in listOf("/api/app/com.trashchecker/trashcollectiondays?recalculate=false",
            "/api/manager/apps/app/com.trashchecker/setting/collectingDays", "/api/manager/apps/app/com.trashchecker/setting/")) {
            try {
                val t = call("GET", path).trim()
                ImageLog.add("Afval: $path → ${t.take(120)}")
                val v = org.json.JSONTokener(t).nextValue()
                return when (v) { is JSONObject -> v; is JSONArray -> JSONObject().put("data", v); is String -> JSONObject().put("data", v); else -> null } ?: continue
            } catch (e: Exception) { ImageLog.add("Afval: $path mislukt: ${e.message}") }
        }
        return null
    }

    fun setCapability(device: String, cap: String, value: Any) {
        call("PUT", "/api/manager/devices/device/$device/capability/$cap", JSONObject().put("value", value).toString())
    }

    fun setVariable(id: String, value: Any) {
        try { call("PUT", "/api/manager/logic/variable/$id", JSONObject().put("variable", JSONObject().put("value", value)).toString()) }
        catch (e: IOException) { call("PUT", "/api/manager/logic/variable/$id", JSONObject().put("value", value).toString()) }
    }

    /** Werkt voor flows die beginnen met "Deze flow is gestart". */
    fun trigger(f: HFlow) {
        call("POST", if (f.advanced) "/api/manager/flow/advancedflow/${f.id}/trigger" else "/api/manager/flow/flow/${f.id}/trigger", "{}")
    }

    /**
     * WebRTC: stuur ons aanbod (SDP) naar Homey en krijg het antwoord terug. Homey's precieze vorm is niet
     * gedocumenteerd, dus een paar varianten proberen. Geeft (answerSdp, streamId?) terug.
     */
    fun videoOffer(videoId: String, offerSdp: String): Pair<String, String?> {
        val path = "/api/manager/videos/video/$videoId/offer"
        var last: Exception? = null
        for (key in listOf("offerSdp", "sdp", "offer")) {
            try {
                val txt = call("POST", path, JSONObject().put(key, offerSdp).toString())
                ImageLog.add("Homey video offer ($key) → ${txt.take(160)}")
                val t = txt.trim()
                if (t.startsWith("v=")) return t to null
                val o = runCatching { JSONObject(t) }.getOrNull() ?: runCatching { JSONObject().put("answerSdp", org.json.JSONTokener(t).nextValue() as String) }.getOrNull()
                    ?: throw IOException("onbekend antwoord")
                val ans = o.optString("answerSdp").ifBlank { o.optString("sdp") }.ifBlank { o.optJSONObject("answer")?.optString("sdp") ?: "" }
                    .ifBlank { o.optString("answer") }
                if (ans.isNotBlank()) return ans to (o.optString("streamId").ifBlank { o.optString("id") }.takeIf { it.isNotBlank() })
                throw IOException("geen antwoord-SDP: ${t.take(80)}")
            } catch (e: Exception) { last = e; ImageLog.add("Homey video offer ($key) mislukt: ${e.message}") }
        }
        throw last ?: IOException("live beeld lukt niet")
    }

    fun videoKeepAlive(videoId: String, streamId: String) {
        runCatching { call("POST", "/api/manager/videos/video/$videoId/keepalive", JSONObject().put("streamId", streamId).toString()) }
    }

    fun imageUrl(path: String) = if (path.startsWith("http")) path else base + path

    /** Het beeld zelf ophalen (Ring maakt het soms pas op verzoek: ruime wachttijd). */
    fun imageBytes(path: String): ByteArray {
        val u = imageUrl(path, System.currentTimeMillis())
        val c = URL(u).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 3_000; c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $key")
            c.useCaches = false
            if (c.responseCode !in 200..299) throw IOException("beeld ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    /** Beeld met een versie erachter (zoals de Homey-app doet), zodat er nooit een oude kopie terugkomt. */
    fun imageUrl(path: String, version: Long) = imageUrl(path).let { u -> u + (if ('?' in u) "&" else "?") + version }

    companion object {
        private val iso = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX")
        fun ts(v: Any?): Long = when (v) {
            is Number -> v.toLong()
            is String -> iso.firstNotNullOfOrNull { p ->
                runCatching { SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)?.time }.getOrNull()
            } ?: 0L
            else -> 0L
        }
        @Suppress("unused") private fun JSONArray.objs() = (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
