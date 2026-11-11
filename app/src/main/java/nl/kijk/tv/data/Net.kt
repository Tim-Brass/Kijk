package nl.kijk.tv.data

import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Net {
    /** Veel IPTV-servers weigeren onbekende spelers; VLC wordt vrijwel overal geaccepteerd. */
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** Opent een URL, volgt ook http↔https-doorverwijzingen en pakt gzip automatisch uit. */
    fun open(url: String, progress: ((Long, Long) -> Unit)? = null): InputStream {
        var current = URL(url)
        var redirects = 0
        while (true) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("Doorverwijzing zonder adres")
                c.disconnect()
                current = URL(current, location)
                if (++redirects > 6) throw IOException("Te veel doorverwijzingen")
                continue
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("Server antwoordde met code $code") }
            val total = c.contentLengthLong
            val counted: InputStream = if (progress == null) c.inputStream else object : FilterInputStream(c.inputStream) {
                var done = 0L
                override fun read(): Int { val r = super.read(); if (r >= 0) { done++; if (done % 65536 == 0L) progress(done, total) }; return r }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n > 0) { done += n; progress(done, total) }; return n }
            }
            val stream = BufferedInputStream(counted, 64 * 1024)
            stream.mark(4)
            val b1 = stream.read(); val b2 = stream.read()
            stream.reset()
            return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(stream, 64 * 1024), 64 * 1024) else stream
        }
    }

    fun text(url: String, progress: ((Long, Long) -> Unit)? = null): String = open(url, progress).use { it.readBytes().toString(Charsets.UTF_8) }
}
