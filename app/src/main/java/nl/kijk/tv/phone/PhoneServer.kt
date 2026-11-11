package nl.kijk.tv.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Kleine webserver in de app: met de telefoon (QR-code) of vanuit de webversie op de laptop
 * stuur je de playlist, gids-link, OpenSubtitles-gegevens en al je keuzes naar de tv.
 * Een code van 6 cijfers (op het tv-scherm) voorkomt dat anderen in je netwerk iets wijzigen.
 */
object PhoneServer {
    @Volatile private var server: ServerSocket? = null
    @Volatile var port = 0; private set
    /** Voor de controle op het tv-scherm: wanneer er voor het laatst iets binnenkwam, en van welk toestel. */
    @Volatile var lastHit: Pair<Long, String>? = null; private set
    @Volatile var startError: String? = null; private set

    /** Test op de tv zelf: reageert de server op zijn eigen netwerkadres? */
    fun selfTest(): String {
        val i = ip() ?: return "✕ De tv heeft geen netwerkadres gevonden"
        if (port == 0) return startError?.let { "✕ Server niet gestart: $it" } ?: "Server start…"
        return try {
            Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(i, port), 3000); sock.soTimeout = 3000
                sock.getOutputStream().write("GET /status HTTP/1.1\r\nHost: $i\r\nX-Kijk-Self: 1\r\n\r\n".toByteArray())
                val r = sock.getInputStream().bufferedReader().readLine() ?: ""
                if (r.contains("200")) "✓ Server werkt op $i:$port" else "✕ Server geeft vreemd antwoord: $r"
            }
        } catch (e: Exception) { "✕ Server reageert niet op $i:$port (${e.javaClass.simpleName})" }
    }
    /** Koppelcode; blijft bewaard, zodat een geopende pagina op je telefoon ook na een herstart blijft werken. */
    @Volatile var code: String = "------"; private set
    private val main = Handler(Looper.getMainLooper())
    /** Altijd de nieuwste ontvanger: na het opnieuw opbouwen van het scherm mag de oude niet meer gebruikt worden. */
    @Volatile private var listener: ((JSONObject) -> Unit)? = null

    fun ip(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (_: Exception) { null }

    /** Adres voor de QR-code, met de code erin. */
    fun url(): String? { val i = ip() ?: return null; if (port == 0) return null; return "http://$i:$port/?code=$code" }
    fun address(): String? { val i = ip() ?: return null; if (port == 0) return null; return "$i:$port" }

    @Volatile private var starting = false

    @Volatile private var filesDir: java.io.File? = null
    /** Verzoeken tussen Kijk-apparaten (instellingen delen); wordt ingesteld door de app. */
    @Volatile var syncHandler: ((String, JSONObject, String) -> JSONObject)? = null

    fun start(ctx: Context, onImport: (JSONObject) -> Unit) {
        filesDir = ctx.filesDir
        listener = onImport
        val sp = ctx.getSharedPreferences("kijk", Context.MODE_PRIVATE)
        code = sp.getString("phoneCode", null) ?: "%06d".format(Random.nextInt(0, 1_000_000)).also { sp.edit().putString("phoneCode", it).apply() }
        if (server != null || starting) return
        starting = true
        thread(name = "kijk-phone", isDaemon = true) {
            val s = (8080..8090).firstNotNullOfOrNull { p -> try { ServerSocket(p).apply { reuseAddress = true } } catch (_: Exception) { null } }
            starting = false
            if (s == null) { startError = "poort 8080–8090 bezet"; return@thread }
            server = s; port = s.localPort
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { try { handle(c) } catch (_: Exception) { } finally { try { c.close() } catch (_: Exception) { } } }
            }
        }
    }

    /** De server blijft draaien zolang de app leeft; alleen de ontvanger wordt losgekoppeld. */
    fun stop(onImport: (JSONObject) -> Unit) { if (listener === onImport) listener = null }

    private fun handle(c: Socket) {
        c.soTimeout = 15_000
        val inp = BufferedInputStream(c.getInputStream())
        val head = ByteArrayOutputStream()
        var last = 0
        while (true) {   // header lezen tot lege regel
            val b = inp.read(); if (b < 0) return
            head.write(b)
            last = (last shl 8 or b) and 0xFFFFFFFF.toInt()
            if (last == 0x0D0A0D0A) break
            if (head.size() > 16_384) return
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, target) = lines[0].split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len.coerceAtMost(2_000_000)).also { var off = 0; while (off < it.size) { val n = inp.read(it, off, it.size - off); if (n < 0) break; off += n } }
        val path = target.substringBefore('?')
        if (headers["x-kijk-self"] == null) lastHit = System.currentTimeMillis() to "${method} ${path} van ${c.inetAddress.hostAddress}"
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val out = c.getOutputStream()
        fun send(status: String, type: String, data: ByteArray) {
            out.write(("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${data.size}\r\nAccess-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Private-Network: true\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(data); out.flush()
        }
        fun json(status: String, o: JSONObject) = send(status, "application/json; charset=utf-8", o.toString().toByteArray())
        when {
            method == "OPTIONS" -> send("204 No Content", "text/plain", ByteArray(0))
            method == "GET" && path == "/" -> send("200 OK", "text/html; charset=utf-8", PAGE.replace("{{CODE}}", query["code"]?.filter { it.isDigit() } ?: "").toByteArray())
            method == "GET" && path == "/crash" -> send("200 OK", "text/plain; charset=utf-8",
                (filesDir?.let { java.io.File(it, "crash_last.txt") }?.takeIf { it.exists() }?.readText() ?: "Geen crash bewaard.").toByteArray())
            method == "GET" && path == "/status" -> json("200 OK", JSONObject().put("app", "Kijk").put("ok", true).put("open", listener != null))
            method == "POST" && path.startsWith("/sync/") -> {
                val o = try { JSONObject(String(body, Charsets.UTF_8).ifBlank { "{}" }) } catch (_: Exception) { JSONObject() }
                val h = syncHandler ?: return json("503 Service Unavailable", JSONObject().put("error", "Kijk staat niet open"))
                val r = try { h(path, o, c.inetAddress.hostAddress ?: "") } catch (e: Exception) { JSONObject().put("_status", 500).put("error", e.message ?: "fout") }
                when (r.optInt("_status", 200)) {
                    200 -> json("200 OK", r)
                    403 -> json("403 Forbidden", r)
                    404 -> json("404 Not Found", r)
                    else -> json("500 Internal Server Error", r)
                }
            }
            method == "POST" && path == "/import" -> {
                val o = try { JSONObject(String(body, Charsets.UTF_8)) } catch (_: Exception) { return json("400 Bad Request", JSONObject().put("error", "Ongeldige gegevens")) }
                if (o.optString("code") != code) return json("403 Forbidden", JSONObject().put("error", "Onjuiste code. Kijk op de tv: Instellingen › Instellen via telefoon."))
                o.remove("code")
                val l = listener ?: return json("503 Service Unavailable", JSONObject().put("error", "Kijk staat niet open op de tv. Open de app en probeer het opnieuw."))
                main.post { try { l(o) } catch (_: Exception) { } }
                json("200 OK", JSONObject().put("ok", true))
            }
            else -> send("404 Not Found", "text/plain", "Niet gevonden".toByteArray())
        }
    }

    private val PAGE = """<!doctype html><html lang="nl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kijk instellen</title><style>
body{font:16px -apple-system,system-ui,sans-serif;background:#0b0b0f;color:#fff;margin:0;padding:20px;max-width:560px;margin:auto}
h1{font-size:28px;margin:8px 0 4px}p{color:#98989f;margin:4px 0 16px}h2{font-size:13px;color:#98989f;text-transform:uppercase;margin:22px 0 6px}
label{display:block;font-size:13px;color:#98989f;margin:10px 0 4px}input,select{width:100%;box-sizing:border-box;padding:12px;border-radius:12px;border:0;background:#1c1c22;color:#fff;font-size:16px}
.seg{display:flex;gap:6px;background:#1c1c22;border-radius:12px;padding:4px}.seg button{flex:1;padding:9px;border:0;border-radius:9px;background:none;color:#fff;font-size:15px}
.seg button.on{background:#3a3a44}button.go{width:100%;margin:24px 0;padding:14px;border:0;border-radius:14px;background:#0a84ff;color:#fff;font-size:17px;font-weight:600}
#msg{text-align:center;min-height:22px}.hint{font-size:12px;color:#6e6e76;margin-top:4px}
</style></head><body>
<h1>Kijk instellen</h1><p>Alles wat je hier invult gaat rechtstreeks naar je tv. Lege velden blijven ongewijzigd.</p>
<h2>Playlist</h2><div class="seg"><button id="bm" class="on" onclick="mode('m')">M3U-link</button><button id="bx" onclick="mode('x')">Xtream-login</button></div>
<div id="m"><label>Playlist-link (M3U)</label><input id="url" inputmode="url" autocapitalize="off" placeholder="http://…/get.php?…"></div>
<div id="x" hidden><label>Server</label><input id="host" inputmode="url" autocapitalize="off" placeholder="http://voorbeeld.tv:8080">
<label>Gebruikersnaam</label><input id="user" autocapitalize="off"><label>Wachtwoord</label><input id="pass" type="password"></div>
<label>Gids-link (XMLTV, optioneel)</label><input id="epg" inputmode="url" autocapitalize="off" placeholder="Leeg = uit de playlist">
<label>Playlist vernieuwen</label><select id="rp"><option value="">Niet wijzigen</option><option value="start">Bij elke start</option><option value="daily">1× per dag</option><option value="weekly">1× per week</option><option value="manual">Alleen handmatig</option></select>
<label>Gids vernieuwen</label><select id="re"><option value="">Niet wijzigen</option><option value="start">Bij elke start</option><option value="twice">2× per dag</option><option value="daily">1× per dag</option><option value="manual">Alleen handmatig</option></select>
<h2>OpenSubtitles</h2><label>API-sleutel (Consumer)</label><input id="osk" autocapitalize="off" autocomplete="off">
<label>Gebruikersnaam</label><input id="osu" autocapitalize="off"><div class="hint">Je gebruikersnaam, niet je e-mailadres.</div><label>Wachtwoord</label><input id="osp" type="password">
<h2>Koppelcode</h2><label>Code op het tv-scherm</label><input id="code" inputmode="numeric" value="{{CODE}}">
<button class="go" onclick="send()">Naar tv sturen</button><div id="msg"></div><div id="st" class="hint" style="text-align:center"></div>
<script>
let md='m';function mode(v){md=v;m.hidden=v!='m';x.hidden=v!='x';bm.className=v=='m'?'on':'';bx.className=v=='x'?'on':''}
function $(i){return document.getElementById(i).value.trim()}
async function send(){const o={code:$('code')};
if(md=='m'&&$('url'))o.playlist_url=$('url');
if(md=='x'&&$('host')&&$('user')&&$('pass')){let h=$('host');if(!/^https?:/.test(h))h='http://'+h;h=h.replace(/\/+$/,'');o.playlist_url=h+'/get.php?username='+encodeURIComponent($('user'))+'&password='+encodeURIComponent($('pass'))+'&type=m3u_plus&output=ts'}
if($('epg'))o.epg_url=$('epg');if($('rp'))o.refresh_playlist=$('rp');if($('re'))o.refresh_epg=$('re');
if($('osk'))o.os_key=$('osk').replace(/\s/g,'');if($('osu'))o.os_user=$('osu');if(document.getElementById('osp').value)o.os_pass=document.getElementById('osp').value;
msg.textContent='Versturen…';const ac=new AbortController();const t=setTimeout(()=>ac.abort(),10000);
try{const r=await fetch('/import?t='+Date.now(),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(o),signal:ac.signal,cache:'no-store'});const j=await r.json();
msg.textContent=j.ok?'✓ Verstuurd. Bovenin de tv verschijnt een bevestiging.':'✕ '+(j.error||'Mislukt')}
catch(e){msg.textContent='✕ Geen antwoord van de tv. Staat Kijk open op de tv en zit je telefoon op hetzelfde wifi-netwerk (niet op 4G/5G of een gastnetwerk)?'}finally{clearTimeout(t)}}
async function check(){try{const ac=new AbortController();setTimeout(()=>ac.abort(),4000);const j=await (await fetch('/status?t='+Date.now(),{signal:ac.signal,cache:'no-store'})).json();
st.textContent=j.open?'● Verbonden met de tv':'● De tv is bereikbaar, maar Kijk staat niet open';st.style.color=j.open?'#30d158':'#ff9f0a'}catch(e){st.textContent='● Geen verbinding met de tv';st.style.color='#ff453a'}}
check();setInterval(check,5000);
</script></body></html>"""
}
