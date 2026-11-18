package nl.kijk.tv

/** Versienaam bij een buildnummer (zelfde regel als in build.gradle.kts): vanaf build 111 is het 0.2.x. */
object Versions {
    fun name(build: Int): String = if (build >= 111) "0.2.${build - 111}" else "0.1.$build"
}
