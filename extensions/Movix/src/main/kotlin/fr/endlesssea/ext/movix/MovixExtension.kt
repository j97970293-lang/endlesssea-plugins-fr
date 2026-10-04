package fr.endlesssea.ext.movix

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.Aggregators
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Tmdb
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz

/**
 * Movix (movix.men) — portage **natif** Endless Sea.
 *
 * Movix n'a pas de catalogue HTML exploitable : le site lui-même se sert de
 * TMDB pour l'affichage et de `api.movix.men` pour les lecteurs. Le portage
 * reprend ce schéma :
 *
 *  - catalogue + fiches + épisodes : TMDB en français ([Tmdb]) ;
 *  - lecteurs : `api.movix.men/api/tmdb/…`, puis les sources du réseau
 *    (Purstream, Wiflix, FrenchStream, CpasMal, liens directs), le réseau
 *    Frembed, et enfin vidsrc.buzz (TMDB puis id IMDb en secours).
 *
 * Les charges utiles d'épisode sont des marqueurs `movix:{movie|tv}:{tmdb}[:{s}:{e}]`.
 */
class MovixExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://movix.men"
    private val api get() = "https://api." + mainUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
    override val providerName = "Movix"
    override val extensionId = "fr.endlesssea.ext.movix"
    override val versionCode = 12
    override val descriptionText = "Catalogue TMDB en français, lecteurs du réseau Movix et agrégateurs FR."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override val homeRows = listOf(
        HomeRow("trending-movie", "🔥 Films tendance", "trending/movie/week"),
        HomeRow("movie-popular", "🎬 Films populaires", "movie/popular"),
        HomeRow("movie-top", "⭐ Films les mieux notés", "movie/top_rated"),
        HomeRow("movie-upcoming", "🗓️ Prochainement", "movie/upcoming"),
        HomeRow("movie-now", "🍿 Au cinéma", "movie/now_playing"),
        HomeRow("trending-tv", "📺 Séries tendance", "trending/tv/week"),
        HomeRow("tv-popular", "📼 Séries populaires", "tv/popular"),
        HomeRow("tv-top", "🏆 Séries les mieux notées", "tv/top_rated"),
        HomeRow("netflix-movie", "🎬 Netflix · Films", "discover/movie?with_watch_providers=8&watch_region=FR"),
        HomeRow("netflix-tv", "📺 Netflix · Séries", "discover/tv?with_watch_providers=8&watch_region=FR"),
        HomeRow("prime-movie", "🎥 Prime Video · Films", "discover/movie?with_watch_providers=119&watch_region=FR"),
        HomeRow("prime-tv", "📺 Prime Video · Séries", "discover/tv?with_watch_providers=119&watch_region=FR"),
        HomeRow("disney-movie", "🏰 Disney+ · Films", "discover/movie?with_watch_providers=337&watch_region=FR"),
        HomeRow("disney-tv", "🏰 Disney+ · Séries", "discover/tv?with_watch_providers=337&watch_region=FR"),
    )

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        // movie/* et tv/* ne renvoient pas « media_type » : sans type de secours,
        // toutes les cartes seraient filtrées.
        val fallback = when {
            "movie" in row.path -> "movie"
            "tv" in row.path -> "tv"
            else -> null
        }
        val (items, hasNext) = Tmdb.page(http, row.path, page, "movix", fallback)
        return PagedResult(items, page, hasNext)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> =
        Tmdb.searchMulti(http, query, "movix", page)

    override suspend fun details(url: String): MediaDetails {
        val parts = url.substringAfter("movix:", "").split(":").filter { it.isNotBlank() }
        val isTv = parts.getOrNull(0) == "tv"
        val tmdb = parts.getOrNull(1) ?: throw SourceException.VideoUnavailable("fiche invalide")
        return Tmdb.details(http, tmdb, isTv, "movix")
            ?: throw SourceException.VideoUnavailable("fiche TMDB introuvable")
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val payload = data.episode.data
        val parts = payload.substringAfter("movix:", payload).split(":").filter { it.isNotBlank() }
        val isTv = parts.getOrNull(0) == "tv"
        val tmdb = parts.getOrNull(1) ?: throw SourceException.VideoUnavailable("identifiant manquant")
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val entries = ArrayList<ServerEntry>()

        // 1) lecteurs officiels de movix.men
        entries += runCatching { Aggregators.movix(http, tmdb, season ?: if (isTv) 1 else null, episode ?: if (isTv) 1 else null) }
            .getOrDefault(emptyList())

        // 2) sources du réseau (Purstream HLS direct, Wiflix, FrenchStream, CpasMal…)
        entries += runCatching { Aggregators.movixNetwork(http, api, tmdb, isTv, season, episode) }
            .getOrDefault(emptyList())

        // 3) réseau Frembed
        entries += runCatching { Aggregators.frembedNetwork(http, tmdb, isTv, season, episode) }
            .getOrDefault(emptyList())

        // 4) lecteurs publics indexés TMDB
        entries += TmdbEmbeds.publicEmbeds(tmdb, season, episode)

        val out = LinkedHashMap<String, VideoLink>()
        if (entries.isNotEmpty()) {
            runCatching { resolveServers(entries, data.preferredServer) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it) }
        }

        // 5) vidsrc.buzz : d'abord par TMDB, puis par id IMDb (certains contenus
        //    n'y sont indexés que sous leur identifiant IMDb)
        if (out.isEmpty()) {
            runCatching { VidSrcBuzz.links(http, tmdb, season, episode) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
            if (out.isEmpty()) {
                Tmdb.imdbId(http, tmdb, isTv)?.let { imdb ->
                    runCatching { VidSrcBuzz.links(http, imdb, season, episode) }
                        .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
                }
            }
        }

        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun serveur exploitable")
        return out.values.sortedByDescending { it.quality.pixels }
    }

    override suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
