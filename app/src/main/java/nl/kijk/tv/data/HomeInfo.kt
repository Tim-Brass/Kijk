package nl.kijk.tv.data

import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import android.util.Xml

/** Soort weer, voor het (geanimeerde) icoon. */
enum class Sky { Clear, Partly, Cloudy, Fog, Rain, Snow, Storm }

data class WeatherDay(val date: String, val sky: Sky, val max: Int, val min: Int)
data class Weather(val temp: Int, val sky: Sky, val text: String, val isDay: Boolean, val days: List<WeatherDay>, val fetched: Long)
data class News(val headlines: List<String>, val fetched: Long, val logo: String? = null)

/** Weer (Open-Meteo, gratis en zonder sleutel) en nieuwskoppen (NOS) voor de Thuis-pagina. */
object HomeInfo {
    // Nuenen
    private const val LAT = 51.47
    private const val LON = 5.55

    fun weather(): Weather {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
            "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min" +
            "&timezone=Europe%2FAmsterdam&forecast_days=7"
        val root = JSONObject(Net.text(url))
        val cur = root.getJSONObject("current")
        val code = cur.getInt("weather_code")
        val d = root.getJSONObject("daily")
        val dates = d.getJSONArray("time"); val codes = d.getJSONArray("weather_code")
        val maxs = d.getJSONArray("temperature_2m_max"); val mins = d.getJSONArray("temperature_2m_min")
        val days = (0 until dates.length()).map { i ->
            WeatherDay(dates.getString(i), sky(codes.optInt(i)), Math.round(maxs.optDouble(i)).toInt(), Math.round(mins.optDouble(i)).toInt())
        }
        return Weather(Math.round(cur.getDouble("temperature_2m")).toInt(), sky(code), text(code), cur.optInt("is_day", 1) == 1, days, System.currentTimeMillis())
    }

    fun news(): News {
        val titles = ArrayList<String>()
        var logo: String? = null
        Net.open("https://feeds.nos.nl/nosnieuwsalgemeen").use { input ->
            val p = Xml.newPullParser()
            p.setInput(input, null)
            var inItem = false; var inImage = false
            while (p.next() != XmlPullParser.END_DOCUMENT && titles.size < 12) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    when {
                        p.name == "item" -> inItem = true
                        // het logo van de nieuwsbron zelf (kanaalafbeelding in de feed)
                        !inItem && p.name == "image" -> inImage = true
                        inImage && p.name == "url" -> logo = p.nextText().trim().ifEmpty { null }
                        inItem && p.name == "title" -> p.nextText().trim().takeIf { it.isNotEmpty() }?.let { titles += it }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG) {
                    if (p.name == "item") inItem = false
                    if (p.name == "image") inImage = false
                }
            }
        }
        return News(titles, System.currentTimeMillis(), logo)
    }

    // WMO-weercodes
    private fun sky(c: Int) = when (c) {
        0 -> Sky.Clear
        1, 2 -> Sky.Partly
        3 -> Sky.Cloudy
        45, 48 -> Sky.Fog
        in 51..67, in 80..82 -> Sky.Rain
        in 71..77, 85, 86 -> Sky.Snow
        in 95..99 -> Sky.Storm
        else -> Sky.Cloudy
    }

    private fun text(c: Int) = when (c) {
        0 -> "Helder"
        1 -> "Overwegend helder"
        2 -> "Half bewolkt"
        3 -> "Bewolkt"
        45, 48 -> "Mist"
        51, 53, 55 -> "Motregen"
        56, 57 -> "IJzel"
        61 -> "Lichte regen"
        63 -> "Regen"
        65 -> "Zware regen"
        66, 67 -> "IJzel"
        71, 73, 75, 77 -> "Sneeuw"
        80, 81 -> "Buien"
        82 -> "Zware buien"
        85, 86 -> "Sneeuwbuien"
        95 -> "Onweer"
        96, 99 -> "Onweer met hagel"
        else -> "Bewolkt"
    }
}
