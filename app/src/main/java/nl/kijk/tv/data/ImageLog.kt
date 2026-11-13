package nl.kijk.tv.data

/** Laatste mislukte afbeeldingen (te bekijken via http://<tv>:8080/log), om problemen per apparaat te zien. */
object ImageLog {
    private val items = ArrayDeque<String>()
    @Synchronized fun add(s: String) {
        items.addLast(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(System.currentTimeMillis()) + "  " + s.take(400))
        while (items.size > 40) items.removeFirst()
    }
    @Synchronized fun text(): String = if (items.isEmpty()) "Geen mislukte afbeeldingen." else items.joinToString("\n")
}
