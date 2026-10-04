package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.AudioLang

/**
 * Lecteurs publics indexés par identifiant TMDB (Videasy, Frembed, VidFast,
 * VidSrc, 2Embed…) et agrégateur « apiwiflix ».
 *
 * Plusieurs extensions FR s'en servent comme sources supplémentaires quand le
 * site d'origine n'expose qu'un ou deux lecteurs. Mutualisé ici pour éviter la
 * duplication qui existait dans les providers CloudStream.
 */
object TmdbEmbeds {

    fun publicEmbeds(tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val isTv = season != null && episode != null
        val list = if (isTv) listOf(
            "https://player.videasy.net/tv/$tmdb/$season/$episode?overlay=true&nextEpisode=true" to "Videasy",
            "https://frembed.skin/embed/serie/$tmdb?sa=$season&epi=$episode" to "Frembed",
            "https://peachify.top/embed/tv/$tmdb/$season/$episode?dub=French&sub=French" to "Peachify",
            "https://vidfast.pro/tv/$tmdb/$season/$episode?autoPlay=true&sub=fr" to "VidFast",
            "https://vidsrc.cc/v2/embed/tv/$tmdb/$season/$episode" to "VidSrc.cc",
            "https://www.vidsrc.wtf/api/2/tv/?id=$tmdb&s=$season&e=$episode" to "VidSrc.wtf",
            "https://www.2embed.cc/embedtv/$tmdb&s=$season&e=$episode" to "2Embed",
            "https://111movies.com/tv/$tmdb/$season/$episode" to "111Movies",
            "https://www.vidking.net/embed/tv/$tmdb/$season/$episode?autoPlay=true" to "VidKing",
            "https://vidnest.fun/tv/$tmdb/$season/$episode" to "VidNest",
        ) else listOf(
            "https://player.videasy.net/movie/$tmdb?overlay=true" to "Videasy",
            "https://frembed.skin/embed/movie/$tmdb" to "Frembed",
            "https://peachify.top/embed/movie/$tmdb?dub=French&sub=French" to "Peachify",
            "https://vidfast.pro/movie/$tmdb?autoPlay=true&sub=fr" to "VidFast",
            "https://vidsrc.cc/v2/embed/movie/$tmdb" to "VidSrc.cc",
            "https://www.vidsrc.wtf/api/3/movie/?id=$tmdb" to "VidSrc.wtf",
            "https://www.2embed.cc/embed/$tmdb" to "2Embed",
            "https://111movies.com/movie/$tmdb" to "111Movies",
            "https://www.vidking.net/embed/movie/$tmdb?autoPlay=true" to "VidKing",
            "https://vidnest.fun/movie/$tmdb" to "VidNest",
        )
        return list.map { (url, name) -> ServerEntry(name, url) }
    }

    /** Agrégateur apiwiflix : liens hébergeurs étiquetés VF / VOSTFR. */
    suspend fun wiflix(http: Http, tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/apiwiflix.php?id=$tmdb"
        }
        val html = http.getOrNull(url, mapOf("Accept" to "*/*"))?.text ?: return emptyList()
        val raw = Regex("""allSources\s*=\s*(\[.*?])\s*;""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        return Json.parseOrNull(raw)?.list.orEmpty().mapNotNull { s ->
            val u = s.str("url")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val lang = s.str("language")?.trim()?.uppercase()?.replace("FRENCH", "VF")
            val name = s.str("name") ?: "Lecteur"
            ServerEntry(
                name = name + (lang?.let { " · $it" } ?: ""),
                url = u,
                lang = Text.audioLang(lang ?: name),
            )
        }
    }

    /** Étiquette de langue par défaut quand la source ne précise rien. */
    fun guessLang(label: String): AudioLang = Text.audioLang(label)
}
