package nl.kijk.tv

/** Versienaam bij een buildnummer, identiek aan de Gradle- en GitHub-regel. */
object Versions {
    fun name(build: Int): String = if (build >= 193) "0.3.${build - 193}" else if (build >= 111) "0.2.${build - 111}" else "0.1.$build"
}
