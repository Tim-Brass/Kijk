package nl.kijk.tv.data

import java.security.MessageDigest

object M3u {
    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")
    private val vodExtensions = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v")
    private val streamIdRegex = Regex("""/(\d+)(?:\.\w+)?$""")

    private fun attrs(line: String): Map<String, String> =
        attrRegex.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** @param xtream bij Xtream zijn films/series via de API op te halen; dan worden ze hier overgeslagen. */
    fun parse(lines: Sequence<String>, xtream: Boolean): Playlist {
        val channels = ArrayList<Channel>()
        val seen = HashSet<String>()
        val vod = ArrayList<VodEntry>()
        var epgUrl: String? = null
        var info: String? = null
        var extGroup: String? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> {
                    val a = attrs(line)
                    epgUrl = (a["url-tvg"] ?: a["x-tvg-url"] ?: a["tvg-url"])?.takeIf { it.isNotBlank() }
                }
                line.startsWith("#EXTINF") -> info = line
                line.startsWith("#EXTGRP:") -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val inf = info; info = null
                    val group0 = extGroup; extGroup = null
                    val kind = vodKind(url)
                    if (kind != null && xtream) continue
                    val a = if (inf != null) attrs(inf) else emptyMap()
                    val afterAttrs = inf?.let { s -> val q = s.lastIndexOf('"'); if (q >= 0) s.substring(q + 1) else s }
                    val name = afterAttrs?.substringAfter(',', "")?.trim()?.takeIf { it.isNotEmpty() }
                        ?: a["tvg-name"]?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/')
                    val logo = a["tvg-logo"]?.takeIf { it.isNotBlank() }
                    val group = a["group-title"]?.takeIf { it.isNotBlank() } ?: group0 ?: "Overig"
                    if (kind != null) { vod += VodEntry(name, logo, group, url, kind == "series"); continue }
                    if (!seen.add(url)) continue
                    val sid = streamIdRegex.find(url.substringBefore('?'))?.groupValues?.get(1)
                    val key = if (xtream && sid != null) "s$sid" else "z" + sha1(url).take(12)
                    val catchup = a["catchup"] ?: a["catchup-type"] ?: (if (a["catchup-source"] != null) "default" else null)
                    val catchupDays = (a["catchup-days"] ?: a["tvg-rec"] ?: a["timeshift"])?.toIntOrNull() ?: 0
                    channels += Channel(
                        key = key,
                        number = a["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                        name = name, logo = logo, group = group,
                        tvgId = a["tvg-id"]?.takeIf { it.isNotBlank() },
                        tvgName = a["tvg-name"]?.takeIf { it.isNotBlank() },
                        url = url, streamId = sid,
                        archiveDays = if (catchup != null || catchupDays > 0) (catchupDays.takeIf { it > 0 } ?: 7) else 0,
                        catchup = catchup, catchupSource = a["catchup-source"]?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        return Playlist(channels, epgUrl, vod)
    }

    private fun vodKind(url: String): String? {
        val u = url.lowercase().substringBefore('?')
        return when {
            "/series/" in u -> "series"
            "/movie/" in u -> "movie"
            vodExtensions.any { u.endsWith(it) } -> "movie"
            else -> null
        }
    }
}
