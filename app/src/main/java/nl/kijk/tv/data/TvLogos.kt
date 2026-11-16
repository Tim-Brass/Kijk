package nl.kijk.tv.data

/**
 * Verzorgde zenderlogo's uit de openbare verzameling tv-logo/tv-logos (GitHub), Nederlandse zenders.
 * De logo's zijn handelsmerken van hun eigenaars; Kijk haalt ze bij gebruik op van de bron en bewaart ze in de cache.
 * Geen match of uitgeschakeld: het logo van de aanbieder blijft.
 */
object TvLogos {
    @Volatile var enabled = true
    private const val BASE = "https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/netherlands/"

    private val FILES = listOf(
        "100p-nl-tv-nl",
        "192-tv-nl",
        "24-kitchen-nl",
        "3voor12-nl",
        "animal-planet-nl",
        "at5-nl",
        "baby-tv-nl",
        "bbc-entertainment-nl",
        "bbc-nl-nl",
        "boomerang-nl",
        "bvn-nl",
        "cartoon-network-nl",
        "cbs-reality-nl",
        "comedy-central-extra-hz-nl",
        "comedy-central-extra-nl",
        "comedy-central-nl",
        "crime-and-investigation-nl",
        "curiosity-channel-nl",
        "dance-television-nl",
        "discovery-channel-nl",
        "discovery-science-nl",
        "disney-channel-nl",
        "disney-jr-nl",
        "dreamworks-channel-hz-nl",
        "dreamworks-channel-nl",
        "dusk-nl",
        "e-entertainment-nl",
        "espn-2-nl",
        "espn-3-nl",
        "espn-4-nl",
        "espn-5-nl",
        "espn-6-nl",
        "espn-nl",
        "espn-ultra-hd-nl",
        "eurosport-1-hd-nl",
        "eurosport-1-nl",
        "eurosport-2-hd-nl",
        "eurosport-2-nl",
        "evangelische-omroep-nl",
        "evil-angel-nl",
        "extreme-sports-channel-nl",
        "family7-nl",
        "fashion-tv-nl",
        "film1-action-hz-nl",
        "film1-action-nl",
        "film1-drama-hz-nl",
        "film1-drama-nl",
        "film1-family-hz-nl",
        "film1-family-nl",
        "film1-premiere-hz-nl",
        "film1-premiere-nl",
        "fox-nl",
        "history-channel-hd-nl",
        "history-channel-nl",
        "horse-and-country-nl",
        "id-investigation-discovery-nl",
        "l1-tv-nl",
        "love-nature-nl",
        "meiden-van-holland-hard-nl",
        "mezzo-live-nl",
        "mezzo-nl",
        "mtv-90s-nl",
        "mtv-hits-nl",
        "mtv-live-hd-nl",
        "mtv-music24-nl",
        "mtv-nl",
        "my-zen-tv-nl",
        "national-geographic-nl",
        "national-geographic-wild-nl",
        "net5-nl",
        "nh-nieuws-nl",
        "nh-nl",
        "nick-jr-nl",
        "nick-music-nl",
        "nick-toons-nl",
        "nickelodeon-nl",
        "njam-nl",
        "npo-nieuws-nl",
        "npo-politiek-en-nieuws-nl",
        "npo-politiek-nl",
        "npo-sport-nl",
        "npo-themakanalen-nl",
        "npo-zapp-nl",
        "npo-zappelin-extra-nl",
        "npo-zappelin-nl",
        "npo1-extra-nl",
        "npo1-nl",
        "npo2-extra-nl",
        "npo2-nl",
        "npo3-nl",
        "omroep-brabant-nl",
        "omroep-flevoland-nl",
        "omroep-gelderland-nl",
        "omroep-west-nl",
        "omroep-zeeland-nl",
        "omrop-fryslan-nl",
        "ons-nl",
        "out-tv-nl",
        "paramount-network-hz-nl",
        "paramount-network-nl",
        "passie-xxx-nl",
        "pebble-tv-nl",
        "penthouse-gold-nl",
        "rtl-crime-nl",
        "rtl-lounge-nl",
        "rtl-telekids-nl",
        "rtl4-nl",
        "rtl5-nl",
        "rtl7-nl",
        "rtl8-nl",
        "rtlz-nl",
        "rtv-drenthe-nl",
        "rtv-noord-nl",
        "rtv-oost-nl",
        "rtv-rijnmond-nl",
        "rtv-utrecht-nl",
        "rtv7-nl",
        "sbs6-nl",
        "sbs9-nl",
        "secret-circle-nl",
        "shorts-tv-nl",
        "slam-tv-nl",
        "spike-nl",
        "star-channel-nl",
        "stingray-classica-nl",
        "stingray-djazz-nl",
        "stingray-lite-tv-nl",
        "tlc-nl",
        "tommy-tv-nl",
        "tv-538-nl",
        "tv-oranje-nl",
        "tv-rain-nl",
        "utsav-gold-nl",
        "utsav-plus-nl",
        "veronica-disney-xd-nl",
        "veronica-nl",
        "viaplay-hz-nl",
        "viaplay-nl",
        "viaplay-tv-hz-nl",
        "viaplay-tv-nl",
        "viaplay-xtra-hz-nl",
        "viaplay-xtra-nl",
        "videoland-nl",
        "x-mo-nl",
        "xite-nl",
        "ziggo-sport-2-nl",
        "ziggo-sport-3-nl",
        "ziggo-sport-4-nl",
        "ziggo-sport-5-nl",
        "ziggo-sport-6-nl",
        "ziggo-sport-extra-nl",
        "ziggo-sport-extra1-nl",
        "ziggo-sport-extra2-nl",
        "ziggo-sport-nl",
        "ziggo-tv-nl"
    )

    /** sleutel (zonder -nl, zonder leestekens) → bestand; de liggende ("hz") variant heeft voorrang */
    private val index: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        FILES.sortedBy { if (it.endsWith("-hz-nl")) 0 else 1 }.forEach { f ->
            val k = key(f.removeSuffix("-nl").removeSuffix("-hz"))
            if (k !in m) m[k] = f
        }
        m
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9+]"), "")

    private val noise = Regex(
        "(?i)^(\\s*(nl|be|vl|ned|nederland|dutch)\\s*[:|\\-\\]]\\s*|\\[(nl|be)]\\s*)|" +
            "\\b(uhd|fhd|hd|sd|hq|4k|8k|hevc|h265|h\\.265|50fps|60fps|raw|backup|vip|multi|nl)\\b|\\(.*?\\)|\\[.*?]"
    )
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Adres van het verzorgde logo voor deze zendernaam, of null. */
    fun url(channelName: String): String? {
        if (!enabled) return null
        cache[channelName]?.let { return it.ifEmpty { null } }
        val clean = key(noise.replace(channelName, " "))
        val f = sequenceOf(clean, clean.removeSuffix("1"), clean + "channel", clean.removeSuffix("tv"), clean + "tv",
            clean.removeSuffix("hd"), clean.replace("nederland", ""))
            .filter { it.length >= 2 }.firstNotNullOfOrNull { index[it] }
        val u = f?.let { BASE + it + ".png" }
        cache[channelName] = u ?: ""
        return u
    }
}
