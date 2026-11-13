package nl.kijk.tv.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Instellingen delen tussen apparaten in hetzelfde thuisnetwerk (bijv. Philips-tv en Xiaomi-box).
 *
 * Elke instelling wordt een los "item" met een tijdstempel; per item wint de laatste wijziging.
 * Sets (favorieten, verborgen groepen) en lijsten worden per element gedeeld, zodat wijzigingen op
 * twee apparaten tegelijk allebei blijven bestaan. Er is geen account of cloud: de apparaten praten
 * rechtstreeks met elkaar via de ingebouwde webserver, beveiligd met een gedeelde sleutel die bij het
 * koppelen (met de code op het scherm) wordt uitgewisseld.
 */
class Sync(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("kijk", Context.MODE_PRIVATE)
    private val meta = ctx.getSharedPreferences("kijk_sync", Context.MODE_PRIVATE)
    private val lock = Any()

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also { sp.edit().putString("deviceId", it).apply() }
    val deviceName: String = (try { Settings.Global.getString(ctx.contentResolver, "device_name") } catch (_: Exception) { null })
        ?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Kijk"

    var group: String
        get() = sp.getString("syncGroup", "") ?: ""
        private set(v) { sp.edit().putString("syncGroup", v).apply() }
    val paired get() = group.isNotEmpty()

    /** Bekende apparaten: id -> (naam, host:poort). */
    data class Peer(val id: String, val name: String, val address: String, val lastSync: Long = 0L)
    val peers = ConcurrentHashMap<String, Peer>()
    /** Gevonden in het netwerk (nog niet per se gekoppeld): servicenaam -> apparaat. */
    data class Found(val id: String, val name: String, val address: String)
    fun found(): List<Found> = discovered.values.groupBy { it.id.ifEmpty { it.address } }.map { it.value.first() }.sortedBy { it.name }
    val discovered = ConcurrentHashMap<String, Found>()

    init {
        try {
            val a = JSONArray(sp.getString("syncPeers", "[]"))
            for (i in 0 until a.length()) a.getJSONObject(i).let { peers[it.getString("id")] = Peer(it.getString("id"), it.getString("name"), it.getString("address"), it.optLong("last")) }
        } catch (_: Exception) { }
    }
    private fun savePeers() = sp.edit().putString("syncPeers", JSONArray(peers.values.map {
        JSONObject().put("id", it.id).put("name", it.name).put("address", it.address).put("last", it.lastSync)
    }).toString()).apply()
    private fun rememberPeer(id: String, name: String, address: String, synced: Boolean = false) {
        if (id.isEmpty() || id == deviceId) return
        val old = peers[id]
        peers[id] = Peer(id, name, address, if (synced) System.currentTimeMillis() else old?.lastSync ?: 0L)
        savePeers()
    }

    // ------------------------------------------------------------------ wat wordt gedeeld

    private val SCALARS = listOf("playlistUrl", "epgUrl", "refreshPlaylist", "refreshEpg", "epgOffset", "pinHash", "lockOn",
        "subLang", "subSize", "subBg", "subPos", "softAudio", "autoSubs", "osKey", "osUser", "osPass")
    private val SETS = listOf("favs", "hidden", "hiddenM", "hiddenS")
    private val MAPS = listOf("epgLinks", "lastEp")
    private val LISTS = listOf("recent", "vodFavs", "reminders")
    private val PREFIXES = listOf("p:", "sc:", "ss:", "seen:")

    private fun enc(v: Any?): String? = when (v) {
        is String -> "s:$v"; is Int -> "i:$v"; is Long -> "l:$v"; is Boolean -> "b:$v"; else -> null
    }
    private fun listId(k: String, o: JSONObject) = if (k == "reminders") o.optString("ch") + "@" + o.optLong("start") else o.optString("kind") + ":" + o.optString("id")

    /** Alle deelbare instellingen als platte lijst items. */
    private fun snapshot(): Map<String, String> {
        val all = sp.all
        val out = HashMap<String, String>()
        for (k in SCALARS) enc(all[k])?.let { out["S|$k"] = it }
        for (k in SETS) try { val a = JSONArray(all[k] as? String ?: "[]"); for (i in 0 until a.length()) out["E|$k|${a.getString(i)}"] = "1" } catch (_: Exception) { }
        for (k in MAPS) try { val o = JSONObject(all[k] as? String ?: "{}"); for (key in o.keys()) out["M|$k|$key"] = "s:" + o.getString(key) } catch (_: Exception) { }
        for (k in LISTS) try { val a = JSONArray(all[k] as? String ?: "[]"); for (i in 0 until a.length()) a.getJSONObject(i).let { out["L|$k|${listId(k, it)}"] = it.toString() } } catch (_: Exception) { }
        for ((k, v) in all) if (PREFIXES.any { k.startsWith(it) }) enc(v)?.let { out["P|$k"] = it }
        return out
    }

    /** Ontvangen items toepassen op de instellingen (in volgorde van tijd, zodat lijsten goed gesorteerd blijven). */
    private fun apply(items: List<Triple<String, String, Long>>) {
        val e = sp.edit()
        val sets = HashMap<String, MutableSet<String>>()
        val maps = HashMap<String, JSONObject>()
        val lists = HashMap<String, MutableList<JSONObject>>()
        fun put(k: String, v: String) {
            if (v.isEmpty()) { e.remove(k); return }
            val body = v.substring(2)
            when (v.take(2)) {
                "s:" -> e.putString(k, body); "i:" -> body.toIntOrNull()?.let { e.putInt(k, it) }
                "l:" -> body.toLongOrNull()?.let { e.putLong(k, it) }; "b:" -> e.putBoolean(k, body == "true")
            }
        }
        for ((key, v, _) in items.sortedBy { it.third }) {
            val parts = key.split('|', limit = 3)
            when (parts[0]) {
                "S", "P" -> put(parts[1], v)
                "E" -> {
                    val s = sets.getOrPut(parts[1]) { try { JSONArray(sp.getString(parts[1], "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } } catch (_: Exception) { mutableSetOf() } }
                    if (v.isEmpty()) s.remove(parts[2]) else s.add(parts[2])
                }
                "M" -> {
                    val m = maps.getOrPut(parts[1]) { try { JSONObject(sp.getString(parts[1], "{}") ?: "{}") } catch (_: Exception) { JSONObject() } }
                    if (v.isEmpty()) m.remove(parts[2]) else m.put(parts[2], v.removePrefix("s:"))
                }
                "L" -> {
                    val l = lists.getOrPut(parts[1]) { try { JSONArray(sp.getString(parts[1], "[]")).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() } } catch (_: Exception) { mutableListOf() } }
                    l.removeAll { listId(parts[1], it) == parts[2] }
                    if (v.isNotEmpty()) try { l.add(0, JSONObject(v)) } catch (_: Exception) { }
                }
            }
        }
        sets.forEach { (k, s) -> e.putString(k, JSONArray(s.toList()).toString()) }
        maps.forEach { (k, m) -> e.putString(k, m.toString()) }
        lists.forEach { (k, l) -> e.putString(k, JSONArray(l.take(if (k == "recent") 40 else 500)).toString()) }
        e.commit()
    }

    private data class M(val t: Long, val v: String)
    private fun metaAll(): Map<String, M> = meta.all.mapNotNull { (k, raw) ->
        if (k.startsWith("#")) return@mapNotNull null
        val s = raw as? String ?: return@mapNotNull null
        val i = s.indexOf('\u0001'); if (i < 0) return@mapNotNull null
        k to M(s.substring(0, i).toLongOrNull() ?: 0L, s.substring(i + 1))
    }.toMap()

    /** Lokale wijzigingen sinds de vorige keer een tijdstempel geven. Geeft true als er iets veranderd is. */
    fun scan(): Boolean = synchronized(lock) {
        val now = System.currentTimeMillis()
        val first = !meta.contains("#init")
        val snap = snapshot(); val m = metaAll()
        val e = meta.edit(); var changed = false
        for ((k, v) in snap) {
            val old = m[k]
            if (old == null) { e.putString(k, "${if (first) 1L else now}\u0001$v"); if (!first) changed = true }
            else if (old.v != v) { e.putString(k, "$now\u0001$v"); changed = true }
        }
        for ((k, old) in m) if (k !in snap && old.v.isNotEmpty()) { e.putString(k, "$now\u0001"); changed = true }
        if (first) e.putBoolean("#init", true)
        e.commit()
        changed
    }

    fun entries(): JSONObject = entriesJson()
    private fun entriesJson(): JSONObject = synchronized(lock) {
        val o = JSONObject()
        for ((k, m) in metaAll()) o.put(k, JSONArray().put(m.v).put(m.t))
        o
    }

    /** Items van een ander apparaat samenvoegen. Met force: alles overnemen (bij koppelen van een nieuw apparaat). */
    fun merge(entries: JSONObject, force: Boolean = false): Set<String> = synchronized(lock) {
        scan()
        val m = metaAll()
        val todo = ArrayList<Triple<String, String, Long>>()
        for (k in entries.keys()) {
            val a = entries.optJSONArray(k) ?: continue
            val v = a.optString(0); val t = a.optLong(1)
            val local = m[k]
            if (force || local == null || t > local.t) if (local?.v != v || force) todo += Triple(k, v, t)
        }
        if (todo.isEmpty()) return emptySet()
        apply(todo)
        val e = meta.edit()
        for ((k, v, t) in todo) e.putString(k, "$t\u0001$v")
        e.commit()
        todo.map { it.first.split('|')[1] }.toSet()
    }

    // ------------------------------------------------------------------ netwerk

    private fun post(address: String, path: String, body: JSONObject, timeout: Int = 4000): JSONObject {
        val c = URL("http://$address$path").openConnection() as HttpURLConnection
        c.connectTimeout = minOf(timeout, 2500); c.readTimeout = timeout
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: "{}"
        val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
        if (code >= 400) throw java.io.IOException(o.optString("error").ifEmpty { "HTTP $code" })
        return o
    }

    private fun hello() = JSONObject().put("device", deviceId).put("name", deviceName).put("group", group).put("port", nl.kijk.tv.phone.PhoneServer.port)
    /** Openbaar antwoord zonder sleutel: alleen wie we zijn. */
    fun helloResponse() = JSONObject().put("ok", true).put("device", deviceId).put("name", deviceName)

    /**
     * Koppelen. takeOver = true: dit apparaat neemt alle instellingen van het andere over.
     * takeOver = false: dit apparaat stuurt zijn instellingen naar het andere, dat ze overneemt.
     */
    fun pair(address: String, code: String, takeOver: Boolean = true): Pair<String, Set<String>> {
        val myGroup = group.ifEmpty { UUID.randomUUID().toString().replace("-", "") }
        val req = hello().put("group", myGroup).put("code", code)
        if (!takeOver) { scan(); req.put("push", entriesJson()) }
        val r = post(address, "/sync/pair", req, 15_000)
        group = r.optString("group").ifEmpty { myGroup }
        rememberPeer(r.optString("device"), r.optString("name"), address, synced = true)
        val changed = if (takeOver) merge(r.optJSONObject("entries") ?: JSONObject(), force = true) else emptySet()
        return r.optString("name") to changed
    }

    fun unpair() { group = ""; peers.clear(); savePeers() }

    /** Met alle gekoppelde apparaten uitwisselen. Geeft de gewijzigde onderdelen terug. */
    fun syncAll(): Set<String> {
        if (!paired) return emptySet()
        scan()
        val changed = HashSet<String>()
        for (p in addresses()) try {
            val r = post(p.second, "/sync/exchange", hello().put("entries", entriesJson()))
            if (r.optString("group") != group) continue
            rememberPeer(r.optString("device"), r.optString("name"), p.second, synced = true)
            changed += merge(r.optJSONObject("entries") ?: JSONObject())
        } catch (_: Exception) { }
        return changed
    }

    /** Adressen van gekoppelde apparaten: wat we onthouden hebben plus wat we nu in het netwerk zien. */
    private fun addresses(): List<Pair<String, String>> {
        val list = LinkedHashMap<String, String>()
        peers.values.forEach { list[it.address] = it.id }
        // een gekoppeld apparaat met een nieuw adres (alleen bekende apparaten: de sleutel gaat nooit naar onbekenden)
        discovered.values.forEach { f -> if (peers.containsKey(f.id)) list.putIfAbsent(f.address, f.id) }
        return list.map { it.value to it.key }
    }

    /** Afspelen claimen: andere apparaten stoppen. Geeft (sleutel, positie) terug van wat daar speelde. */
    fun claim(): List<Pair<String, Long>> {
        if (!paired) return emptyList()
        val out = ArrayList<Pair<String, Long>>()
        val threads = addresses().map { p ->
            Thread {
                try {
                    val r = post(p.second, "/sync/stop", hello(), 1500)
                    if (r.optBoolean("stopped")) synchronized(out) { out += r.optString("key") to r.optLong("pos") }
                } catch (_: Exception) { }
            }.apply { start() }
        }
        threads.forEach { it.join(2000) }
        return out
    }

    /**
     * Verzoeken van andere apparaten (via PhoneServer). onStop wordt op de hoofdthread uitgevoerd en
     * geeft terug wat er speelde (sleutel, positie) of null.
     */
    fun handle(path: String, o: JSONObject, from: String, port: Int, onChanged: (Set<String>) -> Unit, onStop: (String) -> Pair<String, Long>?): JSONObject {
        val them = o.optString("device"); val name = o.optString("name")
        val addr = "$from:${o.optInt("port", port)}"
        return when (path) {
            "/sync/pair" -> {
                if (o.optString("code") != nl.kijk.tv.phone.PhoneServer.code) return JSONObject().put("_status", 403).put("error", "Onjuiste code")
                // het andere apparaat stuurt zijn instellingen mee: dan nemen wíj alles over en gaan we in zijn groep
                val push = o.optJSONObject("push")
                if (push != null || group.isEmpty()) group = o.optString("group")
                rememberPeer(them, name, addr, synced = true)
                if (push != null) { val ch = merge(push, force = true); if (ch.isNotEmpty()) onChanged(ch + "playlistUrl") }
                scan()
                hello().put("ok", true).put("entries", entriesJson())
            }
            "/sync/exchange" -> {
                if (!paired || o.optString("group") != group) return JSONObject().put("_status", 403).put("error", "Niet gekoppeld")
                rememberPeer(them, name, addr, synced = true)
                val changed = merge(o.optJSONObject("entries") ?: JSONObject())
                if (changed.isNotEmpty()) onChanged(changed)
                hello().put("entries", entriesJson())
            }
            "/sync/stop" -> {
                if (!paired || o.optString("group") != group) return JSONObject().put("_status", 403).put("error", "Niet gekoppeld")
                val r = onStop(name.ifEmpty { "de andere tv" })
                hello().put("stopped", r != null).put("key", r?.first ?: "").put("pos", r?.second ?: 0L)
            }
            "/sync/hello" -> helloResponse()
            else -> JSONObject().put("_status", 404)
        }
    }

    // ------------------------------------------------------------------ zoeken in het eigen netwerk (reserve)

    /**
     * Niet elke router geeft de zoekberichten (mDNS) door. Daarom ook zelf het eigen netwerk afgaan:
     * alle adressen x.x.x.1–254 op poort 8080–8082 kort aanroepen. Duurt een paar seconden.
     */
    fun scanSubnet() {
        val me = nl.kijk.tv.phone.PhoneServer.ip() ?: return
        val prefix = me.substringBeforeLast('.')
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        for (i in 1..254) {
            val host = "$prefix.$i"
            if (host == me) continue
            pool.execute {
                for (port in 8080..8082) try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress(host, port), 350); sock.close()
                    val r = post("$host:$port", "/sync/hello", JSONObject(), 1500)
                    val id = r.optString("device")
                    if (id.isNotEmpty() && id != deviceId) {
                        discovered["scan:$host:$port"] = Found(id, r.optString("name").ifEmpty { "Kijk ($host)" }, "$host:$port")
                        peers[id]?.let { p -> if (p.address != "$host:$port") { peers[id] = p.copy(address = "$host:$port"); savePeers() } }
                    }
                    break
                } catch (_: Exception) { }
            }
        }
        pool.shutdown()
        pool.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------ vinden in het netwerk (mDNS)

    private var nsd: NsdManager? = null
    private var myService: String? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun startDiscovery(port: Int) {
        if (nsd != null || port == 0) return
        val m = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsd = m
        val info = NsdServiceInfo().apply { serviceName = "Kijk-" + deviceId.take(8); serviceType = TYPE; setPort(port) }
        try {
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { myService = i.serviceName }
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            })
            m.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.DiscoveryListener {
                override fun onServiceFound(s: NsdServiceInfo) {
                    if (s.serviceName == myService || s.serviceName == "Kijk-" + deviceId.take(8)) return
                    synchronized(resolveQueue) { resolveQueue.addLast(s) }; resolveNext()
                }
                override fun onServiceLost(s: NsdServiceInfo) { discovered.remove(s.serviceName) }
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) {}
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            })
        } catch (_: Exception) { }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val m = nsd ?: return
        val s = synchronized(resolveQueue) { if (resolving) return; resolveQueue.removeFirstOrNull()?.also { resolving = true } } ?: return
        try {
            m.resolveService(s, object : NsdManager.ResolveListener {
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val host = i.host?.hostAddress
                    if (host != null && !host.contains(':')) {
                        val addr = "$host:${i.port}"
                        // naam opvragen bij het apparaat zelf
                        Thread {
                            val r = try { post(addr, "/sync/hello", JSONObject(), 2000) } catch (_: Exception) { JSONObject() }
                            val id = r.optString("device")
                            if (id != deviceId) {
                                discovered[i.serviceName] = Found(id, r.optString("name").ifEmpty { "Kijk ($host)" }, addr)
                                peers[id]?.let { p -> if (p.address != addr) { peers[id] = p.copy(address = addr); savePeers() } }
                            }
                        }.start()
                    }
                    synchronized(resolveQueue) { resolving = false }; resolveNext()
                }
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { synchronized(resolveQueue) { resolving = false }; resolveNext() }
            })
        } catch (_: Exception) { synchronized(resolveQueue) { resolving = false } }
    }

    companion object { const val TYPE = "_kijk._tcp" }   // zonder punt aan het eind: anders mislukt aanmelden op sommige toestellen
}
