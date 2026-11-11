package nl.kijk.tv.data

data class Channel(
    /** Vaste sleutel: "s" + zendernummer bij Xtream, anders "z" + hash van het adres (gelijk aan de webversie). */
    val key: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val group: String,
    val tvgId: String?,
    val tvgName: String?,
    val url: String,
    val streamId: String? = null,
    val archiveDays: Int = 0,
    val catchup: String? = null,
    val catchupSource: String? = null,
)

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String?,
) {
    fun isLive(now: Long) = now in start until stop
    fun progress(now: Long): Float =
        ((now - start).toFloat() / (stop - start).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

/** Film- of serie-regel uit een M3U-playlist. */
data class VodEntry(val name: String, val logo: String?, val group: String, val url: String, val isSeries: Boolean)

data class Playlist(val channels: List<Channel>, val epgUrl: String?, val vod: List<VodEntry> = emptyList())

data class VodCategory(val id: String, val name: String)

data class Movie(
    val id: String,
    val name: String,
    val poster: String?,
    val categoryId: String,
    val url: String,
    val rating: String?,
    val year: String?,
)

data class MovieInfo(
    val plot: String?, val genre: String?, val duration: String?, val cast: String?,
    val director: String?, val backdrop: String?, val year: String?, val tmdb: String?, val imdb: String?,
)

data class Show(
    val id: String, val name: String, val poster: String?, val categoryId: String,
    val plot: String?, val rating: String?, val year: String?,
)

data class Episode(
    val key: String, val season: Int, val number: Int, val title: String, val url: String,
    val plot: String?, val duration: String?, val still: String?,
)

data class ShowDetails(val plot: String?, val backdrop: String?, val episodes: List<Episode>, val tmdb: String?)

/** Zoekgegevens voor OpenSubtitles. */
data class SubsQuery(
    val tmdb: String? = null, val imdb: String? = null, val parentTmdb: String? = null,
    val query: String? = null, val year: String? = null, val season: Int? = null, val episode: Int? = null,
)

/** Iets dat de on-demand-speler afspeelt: film, aflevering of terugkijk-programma. */
data class Playable(
    val key: String,
    val title: String,
    val subtitle: String?,
    val url: String,
    val subs: SubsQuery? = null,
    /** Terugkijken: zender + programma; de url begint bij [offsetMs] na de start. */
    val catchupChannel: Channel? = null,
    val catchupProgramme: Programme? = null,
    val offsetMs: Long = 0,
) {
    val isCatchup get() = catchupChannel != null
}

data class SubStyle(
    /** "nl", "en", "auto" of "off" */
    val language: String = "nl",
    /** 0 = klein, 1 = normaal, 2 = groot, 3 = extra groot */
    val size: Int = 1,
    /** 0 = omlijnd, 1 = zwarte balk, 2 = half doorzichtig */
    val background: Int = 0,
    /** 0 = laag, 1 = hoger */
    val position: Int = 0,
) {
    val scale: Float get() = when (size) { 0 -> 0.8f; 2 -> 1.25f; 3 -> 1.5f; else -> 1f }
    val bottomFraction: Float get() = if (position == 1) 0.16f else 0.06f
}

/** Opgeslagen kijkvoortgang. */
data class Progress(val pos: Long, val dur: Long) {
    val fraction get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val resumable get() = pos > 30_000 && dur > 0 && pos < dur * 0.95
    val finished get() = dur > 0 && pos >= dur * 0.95
}
