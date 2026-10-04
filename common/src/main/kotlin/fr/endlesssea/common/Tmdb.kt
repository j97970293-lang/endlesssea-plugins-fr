package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.CharacterCredit
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
        // Les métadonnées TMDB bougent peu : 6 h de cache disque évitent de
        // refrapper l'API à chaque ouverture d'écran (app 0.7.0+).
        val raw = http.cache.getOrPut("tmdb:$path?$query") { http.getOrNull(url, headers)?.text }
            ?: return null
        return Json.parseOrNull(raw)
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


    /** Genres TMDB (films + séries) en français, pour `SearchItem.genres`. */
    val GENRES: Map<Int, String> = mapOf(
        28 to "Action", 12 to "Aventure", 16 to "Animation", 35 to "Comédie",
        80 to "Crime", 99 to "Documentaire", 18 to "Drame", 10751 to "Famille",
        14 to "Fantastique", 36 to "Histoire", 27 to "Horreur", 10402 to "Musique",
        9648 to "Mystère", 10749 to "Romance", 878 to "Science-Fiction",
        10770 to "Téléfilm", 53 to "Thriller", 10752 to "Guerre", 37 to "Western",
        10759 to "Action & Aventure", 10762 to "Jeunesse", 10763 to "Information",
        10764 to "Téléréalité", 10765 to "Science-Fiction & Fantastique",
        10766 to "Feuilleton", 10767 to "Débat", 10768 to "Guerre & Politique",
    )

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
        ).also { item ->
            // Champs additifs de l'app 0.7.0 : badge ⭐ et genres sur la vignette.
            r["vote_average"].double?.takeIf { v -> v > 0.0 }?.let { item.rating = it }
            item.genres = r["genre_ids"].list.mapNotNull { g -> g.int?.let(GENRES::get) }
        }
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
            return enrichOrSame(http, MediaDetails(
                id = url, url = url, title = title, synopsis = plot, posterUrl = poster,
                bannerUrl = backdrop, type = MediaType.MOVIE, year = year, genres = genres,
                durationMin = d["runtime"].int?.takeIf { it > 0 },
                episodeCount = 1,
                seasons = listOf(
                    Season(1, "Film", listOf(Episode(id = url, number = 1f, season = 1, title = title, data = url))),
                ),
            ), tmdb, false)
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

        return enrichOrSame(
            http,
            MediaDetails(
                id = url, url = url, title = title, synopsis = plot, posterUrl = poster,
                bannerUrl = backdrop, type = MediaType.SERIES, year = year, genres = genres,
                episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
            ),
            tmdb, true,
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

    // -----------------------------------------------------------------------
    // Enrichissement (Endless Sea 0.5.0 : MediaDetails.trailerUrl / .characters)
    // -----------------------------------------------------------------------

    /**
     * Ajoute la bande-annonce et la distribution à une fiche déjà construite.
     *
     * Les deux champs sont hors constructeur côté API : les remplir est sans
     * risque pour une app plus ancienne, qui les ignorera simplement.
     * Priorité à la bande-annonce française, repli sur la version originale.
     */
    suspend fun enrich(http: Http, details: MediaDetails, tmdb: String, isTv: Boolean): MediaDetails {
        val kind = if (isTv) "tv" else "movie"
        val d = json(http, "/$kind/$tmdb", "append_to_response=videos,credits")
            ?: return details

        // --- Note : champs dédiés depuis l'app 0.7.0 (plus de bricolage dans le synopsis).
        val withNote = details
        d["vote_average"].double?.takeIf { it > 0.0 }?.let { withNote.rating = it }
        d["vote_count"].int?.takeIf { it > 0 }?.let { withNote.ratingCount = it }

        val videos = d["videos"]["results"].list
        fun pick(lang: String?, type: String) = videos.firstOrNull {
            it.str("site").equals("YouTube", true) &&
                it.str("type").equals(type, true) &&
                (lang == null || it.str("iso_639_1").equals(lang, true))
        }?.str("key")
        val key = pick("fr", "Trailer") ?: pick("fr", "Teaser")
            ?: pick(null, "Trailer") ?: pick(null, "Teaser")
        if (key != null) withNote.trailerUrl = "https://www.youtube.com/watch?v=$key"

        withNote.characters = d["credits"]["cast"].list.take(25).mapNotNull { c ->
            val actor = c.str("name")?.trim().orEmpty()
            val role = c.str("character")?.trim().orEmpty()
            if (actor.isEmpty() && role.isEmpty()) return@mapNotNull null
            CharacterCredit(
                name = role.ifEmpty { actor },
                role = if (role.isEmpty()) "" else actor,
                voiceActor = actor.takeIf { it.isNotEmpty() && role.isNotEmpty() },
                voiceActorLang = null,
                imageUrl = c.str("profile_path")?.let { IMG + it },
            )
        }
        return withNote
    }

    /** Variante tolérante : ne casse jamais la fiche si TMDB est injoignable. */
    suspend fun enrichOrSame(http: Http, details: MediaDetails, tmdb: String?, isTv: Boolean): MediaDetails {
        val id = tmdb?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: return details
        return runCatching { enrich(http, details, id, isTv) }.getOrDefault(details)
    }


    /**
     * Complète les vignettes d'épisodes manquantes avec les « stills » TMDB.
     *
     * Beaucoup de sources FR ne publient aucune image par épisode ; l'app en
     * affiche une quand `Episode.thumbnailUrl` est renseigné. On ne touche
     * qu'aux épisodes vides, et uniquement si la saison existe côté TMDB.
     */
    suspend fun withStills(http: Http, details: MediaDetails, tmdb: String): MediaDetails {
        val seasons = details.seasons
        if (seasons.isEmpty()) return details
        if (seasons.all { s -> s.episodes.all { it.thumbnailUrl != null } }) return details

        val filled = seasons.map { season ->
            if (season.episodes.all { it.thumbnailUrl != null }) return@map season
            val sd = json(http, "/tv/$tmdb/season/${season.number}") ?: return@map season
            val stills = sd["episodes"].list.mapNotNull { e ->
                val n = e["episode_number"].int ?: return@mapNotNull null
                val path = e.str("still_path") ?: return@mapNotNull null
                n to (STILL + path)
            }.toMap()
            if (stills.isEmpty()) return@map season
            season.copy(
                episodes = season.episodes.map { ep ->
                    if (ep.thumbnailUrl != null) ep
                    else ep.copy(thumbnailUrl = stills[ep.number.toInt()])
                }
            )
        }
        return details.copy(seasons = filled)
    }

}
