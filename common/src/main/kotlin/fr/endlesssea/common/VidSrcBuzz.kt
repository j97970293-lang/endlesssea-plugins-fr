package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.VideoLink
import java.net.URLEncoder

/**
 * vidsrc.buzz — agrégateur TMDB multi-serveurs (films, séries, animes).
 *
 * Chaîne d'appels : `/embed/{movie|tv}/{tmdb}` → `var Q = {…}` (jeton)
 * → `/pl/api.php?a=sources` (liste de serveurs) → `/pl/api.php?a=play`
 * → `{url:"/_stream?id=…"}` (HLS proxifié).
 *
 * Sert de filet de secours commun à plusieurs extensions (CineStream, Movix…).
 */
object VidSrcBuzz {

    private const val BASE = "https://vidsrc.buzz"

    suspend fun links(
        http: Http,
        tmdbId: String,
        season: Int? = null,
        episode: Int? = null,
        maxServers: Int = 4,
    ): List<VideoLink> {
        val kind = if (season != null) "tv" else "movie"
        val embedUrl = buildString {
            append("$BASE/embed/$kind/$tmdbId")
            if (season != null) append("/$season")
            if (episode != null) append("/$episode")
        }
        val html = http.getOrNull(embedUrl)?.text ?: return emptyList()
        val qRaw = Regex("""var Q = (\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return emptyList()
        val q = Json.parseOrNull(qRaw) ?: return emptyList()
        val id = q.str("id") ?: return emptyList()
        val token = q.str("t") ?: return emptyList()
        val type = q.str("type") ?: kind
        val s = q["s"].int ?: season ?: 0
        val e = q["e"].int ?: episode ?: 0

        val qs = "type=$type&id=${id.enc()}&s=$s&e=$e&t=${token.enc()}"
        val headers = mapOf("Referer" to embedUrl, "Accept" to "application/json")
        val sources = http.getOrNull("$BASE/pl/api.php?a=sources&$qs", headers)?.asJsonOrNull()
            ?: return emptyList()

        val out = ArrayList<VideoLink>()
        sources["servers"].list.take(maxServers).forEach { sv ->
            val ref = sv.str("ref") ?: return@forEach
            val name = (sv.str("name") ?: "Serveur").replace(Regex("""^Server\s+"""), "").trim()
            val play = http.getOrNull("$BASE/pl/api.php?a=play&ref=${ref.enc()}&t=${token.enc()}", headers)?.text
                ?: return@forEach
            val u = Regex(""""url"\s*:\s*"([^"]+)"""").find(play)?.groupValues?.get(1)
                ?.replace("\\/", "/") ?: return@forEach
            val streamUrl = when {
                u.startsWith("/") -> BASE + u
                u.startsWith("http") -> u
                else -> return@forEach
            }
            out += VideoLink(
                url = streamUrl,
                streamType = Text.streamType(streamUrl),
                quality = Text.quality(streamUrl),
                server = "VidSrc · $name",
                headers = mapOf("Referer" to "$BASE/", "User-Agent" to http.userAgent),
            )
        }
        return out
    }

    private fun String.enc() = URLEncoder.encode(this, "UTF-8")
}
