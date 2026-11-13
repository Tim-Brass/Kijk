package nl.kijk.tv.data

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Instellingen delen via een privé GitHub-repository ("kijk-instellingen").
 * Inloggen gaat met de device flow van een eigen GitHub App: de tv toont een code,
 * die vul je op je telefoon in. Het token blijft alleen op dit apparaat (niet in de sync).
 */
class GitHubSync(ctx: Context, private val sync: Sync) {
    companion object {
        /** Client ID van de GitHub App "Kijk TV sync" (geen geheim). */
        const val CLIENT_ID = "Iv23litshMmvxlzFKDzn"
        const val REPO_NAME = "kijk-instellingen"
        const val FILE = "kijk-sync.json"
    }

    private val sp = ctx.getSharedPreferences("kijk_github", Context.MODE_PRIVATE)
    val token: String get() = sp.getString("token", "") ?: ""
    val login: String get() = sp.getString("login", "") ?: ""
    val connected get() = token.isNotEmpty() && login.isNotEmpty()
    var lastSync: Long
        get() = sp.getLong("last", 0L)
        private set(v) { sp.edit().putLong("last", v).apply() }

    fun signOut() { sp.edit().clear().apply() }

    /** TMDB-sleutel uit kijk-sleutels.json in de privé repo (alleen lokaal bewaard, niet in de sync). */
    val tmdbKey: String get() = sp.getString("tmdb", "") ?: ""

    private fun fetchKeys() {
        val (code, body) = api("GET", "https://api.github.com/repos/$login/$REPO_NAME/contents/kijk-sleutels.json", token, allow404 = true)
        if (code != 200) return
        val raw = String(Base64.decode(JSONObject(body).optString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        val o = JSONObject(raw)
        val k = listOf("tmdb", "tmdbKey", "tmdb_key", "tmdbToken").firstNotNullOfOrNull { o.optString(it).trim().takeIf { v -> v.isNotEmpty() } } ?: ""
        sp.edit().putString("tmdb", k).apply()
    }

    // ---------------------------------------------------------------- inloggen (device flow)
    data class DeviceCode(val deviceCode: String, val userCode: String, val uri: String, val interval: Int, val expiresAt: Long)

    fun startLogin(): DeviceCode {
        val o = form("https://github.com/login/device/code", "client_id" to CLIENT_ID)
        if (o.has("error")) throw IOException(o.optString("error_description", o.optString("error")))
        return DeviceCode(o.getString("device_code"), o.getString("user_code"), o.optString("verification_uri", "https://github.com/login/device"),
            o.optInt("interval", 5), System.currentTimeMillis() + o.optInt("expires_in", 900) * 1000L)
    }

    /** Eén keer navragen. null = nog wachten; anders klaar (true) of een fout (exception). Bij "slow_down" langer wachten. */
    fun poll(dc: DeviceCode): Boolean? {
        val o = form("https://github.com/login/oauth/access_token",
            "client_id" to CLIENT_ID, "device_code" to dc.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")
        val tok = o.optString("access_token")
        if (tok.isNotEmpty()) {
            val user = JSONObject(api("GET", "https://api.github.com/user", tok).second)
            sp.edit().putString("token", tok).putString("login", user.getString("login")).apply()
            return true
        }
        return when (o.optString("error")) {
            "authorization_pending", "slow_down" -> null
            "expired_token" -> throw IOException("De code is verlopen. Probeer het opnieuw.")
            "access_denied" -> throw IOException("Inloggen geweigerd.")
            else -> throw IOException(o.optString("error_description", o.optString("error", "Onbekende fout")))
        }
    }

    // ---------------------------------------------------------------- synchroniseren
    private val fileUrl get() = "https://api.github.com/repos/$login/$REPO_NAME/contents/$FILE"

    /** Ophalen, samenvoegen en zo nodig terugschrijven. Geeft de gewijzigde instellingen terug. */
    fun syncOnce(): Set<String> {
        if (!connected) return emptySet()
        runCatching { fetchKeys() }
        repeat(3) { attempt ->
            sync.scan()
            val (code, body) = api("GET", fileUrl, token, allow404 = true)
            var sha: String? = null
            var remote = JSONObject()
            if (code == 200) {
                val o = JSONObject(body)
                sha = o.optString("sha").ifEmpty { null }
                val raw = String(Base64.decode(o.optString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
                remote = try { JSONObject(raw).optJSONObject("entries") ?: JSONObject() } catch (_: Exception) { JSONObject() }
            }
            val changed = sync.merge(remote)
            val local = sync.entries()
            if (!needsPush(local, remote)) { lastSync = System.currentTimeMillis(); return changed }
            val doc = JSONObject().put("version", 1).put("updated", System.currentTimeMillis()).put("entries", local)
            val put = JSONObject()
                .put("message", "Kijk: instellingen bijgewerkt (${sync.deviceName})")
                .put("content", Base64.encodeToString(doc.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
            if (sha != null) put.put("sha", sha)
            val (pc, _) = api("PUT", fileUrl, token, put.toString(), allowConflict = true)
            if (pc in 200..299) { lastSync = System.currentTimeMillis(); return changed }
            // 409/422: iemand anders schreef tussendoor; opnieuw ophalen en samenvoegen
            if (attempt == 2) throw IOException("Opslaan mislukt (code $pc)")
        }
        return emptySet()
    }

    private fun needsPush(local: JSONObject, remote: JSONObject): Boolean {
        for (k in local.keys()) {
            val l = local.optJSONArray(k) ?: continue
            val r = remote.optJSONArray(k) ?: return true
            if (l.optLong(1) > r.optLong(1) || l.optString(0) != r.optString(0)) return true
        }
        return false
    }

    // ---------------------------------------------------------------- http
    private fun form(url: String, vararg params: Pair<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(params.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        return try { JSONObject(text) } catch (_: Exception) { throw IOException("GitHub antwoordde met code $code") }
    }

    private fun api(method: String, url: String, tok: String, body: String? = null, allow404: Boolean = false, allowConflict: Boolean = false): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("Authorization", "Bearer $tok")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "Kijk-TV")
        c.useCaches = false
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code in 200..299 || (allow404 && code == 404) || (allowConflict && (code == 409 || code == 422))) return code to text
        throw IOException(when (code) {
            401 -> "Niet meer ingelogd bij GitHub. Meld opnieuw aan."
            403 -> "Geen toegang. Is de app geïnstalleerd op '$REPO_NAME'?"
            404 -> "Repository '$REPO_NAME' niet gevonden."
            else -> "GitHub antwoordde met code $code"
        })
    }
}
