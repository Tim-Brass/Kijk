package nl.kijk.tv.data

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SubResult(val fileId: Long, val release: String, val lang: String?, val downloads: Int, val hearingImpaired: Boolean, val ai: Boolean, val cached: Boolean)

/** Ondertitels zoeken en downloaden via opensubtitles.com (API-sleutel + account van de gebruiker). */
class OpenSubtitles(private val store: Store, private val dir: File) {
    private val api = "https://api.opensubtitles.com/api/v1"
    private var base = api
    private var token: String? = null
    private var tokenTime = 0L
    var remaining: Int? = null; private set

    init { dir.mkdirs() }

    val configured get() = store.osKey.isNotBlank()

    private fun req(method: String, path: String, body: JSONObject? = null, auth: Boolean = false, useBase: String = base): JSONObject {
        if (store.osKey.isBlank()) throw IOException("Geen OpenSubtitles API-sleutel ingesteld")
        val c = URL(useBase + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Api-Key", store.osKey)
        c.setRequestProperty("User-Agent", "Kijk v0.1.0")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/json")
        if (auth) c.setRequestProperty("Authorization", "Bearer " + login())
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        if (code !in 200..299) {
            if (code == 401) token = null
            val msg = try { JSONObject(text).optString("message") } catch (_: Exception) {
                if (text.contains("<html", true)) "het antwoord is een webpagina (blokkade door een netwerkfilter?)" else ""
            }
            throw IOException("OpenSubtitles gaf fout $code${if (msg.isNotBlank()) ": $msg" else ""}").also { (it as? OsError)?.code }
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private class OsError(msg: String, val code: Int) : IOException(msg)

    private fun login(): String {
        token?.let { if (System.currentTimeMillis() - tokenTime < 20 * 3600_000L) return it }
        if (store.osUser.isBlank() || store.osPass.isBlank()) throw IOException("Vul je OpenSubtitles-gebruikersnaam en wachtwoord in")
        val r = req("POST", "/login", JSONObject().put("username", store.osUser).put("password", store.osPass), useBase = api)
        token = r.optString("token").ifEmpty { null }; tokenTime = System.currentTimeMillis()
        r.optString("base_url").takeIf { it.isNotBlank() }?.let { b ->
            val h = if (b.startsWith("http")) b else "https://$b"
            base = h.trimEnd('/') + if (h.trimEnd('/').endsWith("/api/v1")) "" else "/api/v1"
        }
        r.optJSONObject("user")?.optInt("allowed_downloads")?.let { remaining = it }
        return token ?: throw IOException("Inloggen bij OpenSubtitles mislukt")
    }

    /** Stap 1: API-sleutel, stap 2: inloggen. Geeft een leesbare uitkomst terug. */
    fun test(): String {
        token = null; base = api
        try { req("GET", "/infos/formats") } catch (e: IOException) { return "✕ Stap 1 (API-sleutel) mislukt: ${e.message}" }
        if (store.osUser.isBlank() || store.osPass.isBlank()) return "✓ API-sleutel werkt. Vul ook gebruikersnaam en wachtwoord in voor meer downloads per dag."
        try { login() } catch (e: IOException) { return "✕ API-sleutel werkt, maar inloggen mislukt: ${e.message}. Gebruik je gebruikersnaam (niet je e-mailadres)." }
        return try {
            val d = req("GET", "/infos/user", auth = true).optJSONObject("data")
            remaining = d?.optInt("remaining_downloads") ?: remaining
            "✓ Verbonden${d?.optString("level")?.let { " ($it)" } ?: ""} · nog ${remaining ?: "?"} van ${d?.optInt("allowed_downloads") ?: "?"} downloads vandaag"
        } catch (_: IOException) { "✓ Verbonden" }
    }

    fun search(q: SubsQuery, lang: String): List<SubResult> {
        val p = sortedMapOf<String, String>("languages" to lang)
        q.tmdb?.let { p["tmdb_id"] = it }
        q.imdb?.let { p["imdb_id"] = it.removePrefix("tt") }
        q.parentTmdb?.let { p["parent_tmdb_id"] = it }
        q.season?.let { p["season_number"] = it.toString() }
        q.episode?.let { p["episode_number"] = it.toString() }
        q.year?.let { p["year"] = it }
        if (q.tmdb == null && q.imdb == null && q.parentTmdb == null) q.query?.let { p["query"] = it }
        val qs = p.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val data = req("GET", "/subtitles?$qs").optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            val f = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            val id = f.optLong("file_id")
            SubResult(id, a.optString("release").ifBlank { f.optString("file_name") }, a.optString("language"), a.optInt("download_count"),
                a.optBoolean("hearing_impaired"), a.optBoolean("ai_translated") || a.optBoolean("machine_translated"), File(dir, "$id.vtt").exists())
        }.sortedWith(compareBy({ !it.cached }, { it.ai }, { -it.downloads }))
    }

    /** WebVTT-bestand op de tv; wordt maar één keer gedownload. */
    fun get(fileId: Long): File {
        val f = File(dir, "$fileId.vtt")
        if (f.exists()) return f
        val body = JSONObject().put("file_id", fileId).put("sub_format", "webvtt")
        val r = try { req("POST", "/download", body, auth = store.osUser.isNotBlank() && store.osPass.isNotBlank()) }
        catch (e: IOException) { token = null; req("POST", "/download", body, useBase = api) }
        if (r.has("remaining")) remaining = r.optInt("remaining")
        val link = r.optString("link").ifEmpty { throw IOException(r.optString("message").ifEmpty { "Geen downloadlink ontvangen" }) }
        val raw = Net.open(link).use { it.readBytes() }
        var text = try { String(raw, Charsets.UTF_8).also { if ('�' in it) throw IllegalStateException() } } catch (_: Exception) { String(raw, charset("windows-1252")) }
        text = text.trimStart('﻿')
        if (!text.startsWith("WEBVTT")) text = "WEBVTT\n\n" + text.replace("\r\n", "\n").replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")
        f.writeText(text)
        return f
    }

    /** Verschoven kopie (ondertitels eerder/later): tijden in het VTT-bestand aanpassen. */
    fun shifted(src: File, seconds: Double): File {
        if (seconds == 0.0) return src
        val out = File(dir, src.nameWithoutExtension + "_" + (seconds * 10).toInt() + ".vtt")
        val re = Regex("""(\d{1,2}:)?(\d{2}):(\d{2})\.(\d{3})""")
        val shiftedText = src.readText().lines().joinToString("\n") { line ->
            if ("-->" !in line) line else re.replace(line) { m ->
                val h = m.groupValues[1].trimEnd(':').toLongOrNull() ?: 0
                val ms = ((h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()) * 1000 + m.groupValues[4].toLong() + (seconds * 1000).toLong()).coerceAtLeast(0)
                "%02d:%02d:%02d.%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
            }
        }
        out.writeText(shiftedText)
        return out
    }
}
