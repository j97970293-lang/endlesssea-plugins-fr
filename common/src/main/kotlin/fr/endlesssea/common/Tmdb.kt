package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season

/**
 * Catalogue TMDB en français : utilisé par les providers dont le site n'expose
 * pas de catalogue propre (Movix, Frembed, WaveWatch…) et qui se contentent de
 * fournir des lecteurs indexés par id TMDB.
 */
object Tmdb {

    const val KEY = "f3d757824f08ea2cff45eb8f47ca3a1e"
    const val BASE = "https://api.themoviedb.org/3"
    const val IMG = "https://image.tmdb.org/t/p/w500"
    const val STILL = "https://image.tmdb.org/t/p/w300"

    private val headers = mapOf("Accept" to "application/json")

    suspend fun json(http: Http, path: String, query: String = ""): JsonNode? {
        val sep = if ("?" in path) "&" else "?"
        val url = "$BASE$path$sep" + "api_key=$KEY&language=fr-FR" + (if (query.isBlank()) "" else "&$query")
        return http.getOrNull(url, headers)?.asJsonOrNull()
    }

    /** Une page de résultats TMDB → [SearchItem]. `idPrefix` préfixe l'URL interne. */
    suspend fun page(
        http: Http,
        path: String,
        page: Int,
        idPrefix: String,
        fallbackType: String? = null,
    ): Pair<List<SearchItem>, Boolean> {
        val root = json(http, "/$path", "page=$page") ?: return emptyList<SearchItem>() to false
        val items = root["results"].list.mapNotNull { card(it, idPrefix, fallbackType) }
        val totalPages = (root["total_pages"].int ?: 1).coerceAtMost(500)
        return items to (page < totalPages)
    }

    suspend fun searchMulti(http: Http, query: String, idPrefix: String, page: Int = 1): List<SearchItem> {
        val root = json(http, "/search/multi", "query=${query.trim().urlEncode()}&include_adult=false&page=$page")
            ?: return emptyList()
        return root["results"].list.mapNotNull { card(it, idPrefix) }
    }

    fun card(r: JsonNode, idPrefix: String, fallbackType: String? = null): SearchItem? {
        val id = r["id"].int ?: return null
        val type = (r.str("media_type") ?: fallbackType)?.takeIf { it == "movie" || it == "tv" } ?: return null
        val title = (r.str("title") ?: r.str("name")) ?: return null
        val date = r.str("release_date") ?: r.str("first_air_date")
        return SearchItem(
            id = "$idPrefix:$type:$id",
            title = Text.decodeHtml(title),
            url = "$idPrefix:$type:$id",
            posterUrl = r.str("poster_path")?.let { IMG + it },
            year = date?.take(4)?.toIntOrNull(),
            type = if (type == "tv") MediaType.SERIES else MediaType.MOVIE,
        )
    }

    /** Fiche complète (film ou série, saisons/épisodes inclus) pour un id TMDB. */
    suspend fun details(http: Http, tmdb: String, isTv: Boolean, idPrefix: String): MediaDetails? {
        val d = json(http, "/${if (isTv) "tv" else "movie"}/$tmdb") ?: return null
        val title = (d.str("title") ?: d.str("name") ?: "Fiche $tmdb").trim()
        val poster = d.str("poster_path")?.let { IMG + it }
        val backdrop = d.str("backdrop_path")?.let { IMG + it }
        val plot = d.str("overview")
        val year = (d.str("release_date") ?: d.str("first_air_date"))?.take(4)?.toIntOrNull()
        val genres = d["genres"].list.mapNotNull { it.str("name") }
        val url = "$idPrefix:${if (isTv) "tv" else "movie"}:$tmdb"

        if (!isTv) {
            return MediaDetails(
                id = url, url = url, title = title, synopsis = plot, posterUrl = poster,
                bannerUrl = backdrop, type = MediaType.MOVIE, year = year, genres = genres,
                durationMin = d["runtime"].int?.takeIf { it > 0 },
                episodeCount = 1,
                seasons = listOf(
                    Season(1, "Film", listOf(Episode(id = url, number = 1f, season = 1, title = title, data = url))),
                ),
            )
        }

        val seasonNumbers = d["seasons"].list.mapNotNull { it["season_number"].int?.takeIf { n -> n >= 1 } }.sorted()
        val seasons = seasonNumbers.mapNotNull { sn ->
            val sd = json(http, "/tv/$tmdb/season/$sn") ?: return@mapNotNull null
            val eps = sd["episodes"].list.mapNotNull { e ->
                val n = e["episode_number"].int ?: return@mapNotNull null
                Episode(
                    id = "$idPrefix:tv:$tmdb:$sn:$n", number = n.toFloat(), season = sn,
                    title = e.str("name") ?: "Épisode $n",
                    thumbnailUrl = e.str("still_path")?.let { STILL + it },
                    data = "$idPrefix:tv:$tmdb:$sn:$n",
                )
            }
            if (eps.isEmpty()) null else Season(sn, "Saison $sn", eps)
        }
        if (seasons.isEmpty()) return null

        return MediaDetails(
            id = url, url = url, title = title, synopsis = plot, posterUrl = poster,
            bannerUrl = backdrop, type = MediaType.SERIES, year = year, genres = genres,
            episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
        )
    }

    /**
     * Id TMDB déduit d'un titre : utile pour les sites qui n'exposent pas
     * l'identifiant (Xalaflix, WaveWatch…) mais dont les agrégateurs en ont besoin.
     * L'année entre parenthèses du titre, si présente, sert de filtre.
     */
    suspend fun idFromTitle(http: Http, title: String, isTv: Boolean): String? {
        val clean = title.replace(Regex("""\(?(19|20)\d{2}\)?"""), "").trim().ifBlank { return null }
        val year = Text.year(title)
        val path = if (isTv) "/search/tv" else "/search/movie"
        val extra = if (year != null) (if (isTv) "&first_air_date_year=$year" else "&year=$year") else ""
        val root = json(http, path, "query=${clean.urlEncode()}$extra") ?: return null
        return root["results"].list.firstOrNull()?.get("id")?.int?.toString()
    }

    /** Id IMDb d'une fiche TMDB (certains agrégateurs n'indexent que l'IMDb). */
    suspend fun imdbId(http: Http, tmdb: String, isTv: Boolean): String? =
        json(http, "/${if (isTv) "tv" else "movie"}/$tmdb/external_ids")
            ?.str("imdb_id")?.takeIf { it.startsWith("tt") }
}
