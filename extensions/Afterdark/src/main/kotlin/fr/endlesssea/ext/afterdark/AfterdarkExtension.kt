package fr.endlesssea.ext.afterdark

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.Aggregators
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.JsonNode
import fr.endlesssea.common.OneEmbed
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.allExtractorsWithAggregators
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Afterdark (afd926.mom) — portage **natif** Endless Sea.
 *
 * Le site est un « front » sans catalogue propre : il consomme le proxy TMDB
 * de WaveWatch pour l'affichage et des agrégateurs FR pour la lecture.
 *
 *  - catalogue + recherche : `https://wavewatch.top/api/tmdb/…` ;
 *  - lecture : wiflix, playerix, zeus (SSE), movix, FrenchStream, moviesapi,
 *    mouve (films), 1Embed, lecteurs publics TMDB, puis vidsrc.buzz.
 *
 * Le site étant orienté VOSTFR, les liens VOSTFR sont présentés en premier.
 *
 * Charges utiles : `movie|{tmdb}` et `tv|{tmdb}|{s}|{e}`.
 */
class AfterdarkExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://afd926.mom"
    override val providerName = "Afterdark"
    override val extensionId = "fr.endlesssea.ext.afterdark"
    override val versionCode = 28
    override val descriptionText = "Films et séries en VOSTFR, multi-serveurs."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override fun extractors() = allExtractorsWithAggregators(http)

    private val tmdbProxy = "https://wavewatch.top/api/tmdb"
    private val tmdbImage = "https://image.tmdb.org/t/p"

    override val homeRows = listOf(
        HomeRow("tr-movies", "🔥 Films Tendance", "tmdb:/trending/movies"),
        HomeRow("tr-tv", "📺 Séries Tendance", "tmdb:/trending/tv"),
        HomeRow("upcoming", "📅 Prochaines Sorties", "tmdb:/upcoming/movies"),
        HomeRow("action", "💥 Action", "paged:/discover/movie?genre=28"),
        HomeRow("sf", "🚀 Science-Fiction", "paged:/discover/movie?genre=878"),
        HomeRow("thriller", "🔪 Thriller", "paged:/discover/movie?genre=53"),
        HomeRow("animation", "✨ Animation", "paged:/discover/movie?genre=16"),
        HomeRow("horreur", "👻 Horreur", "paged:/discover/movie?genre=27"),
        HomeRow("comedie", "😂 Comédie", "paged:/discover/movie?genre=35"),
        HomeRow("tv-action", "🗡️ Séries Action & Aventure", "paged:/discover/tv?genre=10759"),
        HomeRow("tv-drame", "🎭 Séries Drame", "paged:/discover/tv?genre=18"),
    )

    private fun card(n: JsonNode, isTv: Boolean): SearchItem? {
        val id = n["id"].int ?: return null
        val title = (n.str("title") ?: n.str("name")) ?: return null
        val tv = isTv || n.str("media_type") == "tv"
        return item(
            title, if (tv) "$mainUrl/tv/$id" else "$mainUrl/movie/$id",
            if (tv) MediaType.SERIES else MediaType.MOVIE,
            n.str("poster_path")?.let { "$tmdbImage/w500$it" },
            (n.str("release_date") ?: n.str("first_air_date"))?.take(4)?.toIntOrNull(),
        )
    }

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val paged = row.path.startsWith("paged:")
        if (!paged && page > 1) return PagedResult(emptyList(), page, false)
        val path = row.path.removePrefix("tmdb:").removePrefix("paged:")
        val sep = if ("?" in path) "&" else "?"
        val root = http.getOrNull("$tmdbProxy$path${sep}page=$page")?.asJsonOrNull()
            ?: return PagedResult(emptyList(), page, false)
        val items = root["results"].list.mapNotNull { card(it, "/tv" in path) }.distinctBy { it.url }
        return PagedResult(items, page, paged && items.isNotEmpty())
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1 || query.trim().length < 2) return emptyList()
        val root = http.getOrNull("$tmdbProxy/search", params = mapOf("q" to query.trim()))?.asJsonOrNull()
            ?: return emptyList()
        return root["results"].list.mapNotNull { card(it, it.str("media_type") == "tv") }
    }

    override suspend fun details(url: String): MediaDetails {
        val tmdb = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)
            ?: throw SourceException.VideoUnavailable("URL non reconnue")

        if ("/movie/" in url) {
            val d = http.get("$tmdbProxy/movie/$tmdb").requireOk().asJson()
            return movieDetails(
                url = url, title = d.str("title") ?: "Film", payload = "movie|$tmdb",
                poster = d.str("poster_path")?.let { "$tmdbImage/w500$it" },
                banner = d.str("backdrop_path")?.let { "$tmdbImage/original$it" },
                synopsis = d.str("overview"),
                year = d.str("release_date")?.take(4)?.toIntOrNull(),
                genres = d["genres"].list.mapNotNull { it.str("name") },
                durationMin = d["runtime"].int,
                languagesFound = listOf(AudioLang.VOSTFR),
            )
        }

        val d = http.get("$tmdbProxy/tv/$tmdb").requireOk().asJson()
        val mediaId = Text.idOf("afd", url)
        val seasons = d["seasons"].list
            .mapNotNull { it["season_number"].int?.takeIf { n -> n > 0 } }.sorted()
            .mapNotNull { n ->
                val sd = http.getOrNull("$tmdbProxy/tv/$tmdb/season/$n")?.asJsonOrNull() ?: return@mapNotNull null
                val eps = sd["episodes"].list.mapNotNull { ep ->
                    val num = ep["episode_number"].int?.takeIf { it > 0 } ?: return@mapNotNull null
                    Episode(
                        id = "$mediaId:s${n}e$num", number = num.toFloat(), season = n,
                        title = ep.str("name") ?: "Épisode $num",
                        thumbnailUrl = ep.str("still_path")?.let { "$tmdbImage/w500$it" },
                        data = "tv|$tmdb|$n|$num",
                    )
                }
                if (eps.isEmpty()) null else Season(n, "Saison $n", eps)
            }
        if (seasons.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode disponible")

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(d.str("name") ?: "Série"),
            synopsis = d.str("overview")?.let { Text.stripHtml(it) },
            posterUrl = d.str("poster_path")?.let { "$tmdbImage/w500$it" },
            bannerUrl = d.str("backdrop_path")?.let { "$tmdbImage/original$it" },
            type = MediaType.SERIES,
            year = d.str("first_air_date")?.take(4)?.toIntOrNull(),
            genres = d["genres"].list.mapNotNull { it.str("name") },
            episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
            languages = listOf(AudioLang.VOSTFR),
        )
    }

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        val isTv = parts.firstOrNull() == "tv"
        val tmdb = parts.getOrNull(1) ?: return emptyList()
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val entries = listOf(
            runCatching { TmdbEmbeds.wiflix(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { Aggregators.playerix(http, tmdb, season, episode, m3u8Only = isTv) }.getOrDefault(emptyList()),
            runCatching { Aggregators.zeus(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { Aggregators.movix(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { Aggregators.moviesApi(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            if (isTv) emptyList() else runCatching { Aggregators.mouve(http, tmdb) }.getOrDefault(emptyList()),
            TmdbEmbeds.publicEmbeds(tmdb, season, episode),
        ).flatten().distinctBy { it.url }

        // Site orienté VOSTFR : on remonte le VOSTFR, puis le reste.
        return entries.sortedBy {
            when (it.lang) {
                AudioLang.VOSTFR -> 0
                AudioLang.VO -> 1
                AudioLang.VF -> 2
                else -> 3
            }
        }
    }

    override fun linkStream(data: LinkRequest): Flow<VideoLink> = flow {
        val payload = data.episode.data
        val parts = payload.split("|")
        val isTv = parts.firstOrNull() == "tv"
        val tmdb = parts.getOrNull(1) ?: throw SourceException.VideoUnavailable("identifiant manquant")
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val seen = HashSet<String>()
        var any = false
        suspend fun push(links: List<VideoLink>) {
            links.forEach { if (seen.add(it.url)) { any = true; emit(it) } }
        }

        // 1Embed d'abord : playlists HLS directes, les plus fiables
        val oneEmbedUrl = if (isTv) "https://1embed.cc/embed/tv/$tmdb/${season ?: 1}/${episode ?: 1}"
        else "https://1embed.cc/embed/movie/$tmdb"
        push(runCatching { OneEmbed(http).getUrl(oneEmbedUrl, mainUrl) }.getOrDefault(emptyList()))

        // Lecteurs de la fiche, émis au fil de l'eau (app 0.25.0)
        resolveServersFlow(servers(payload), data.preferredServer).collect {
            if (seen.add(it.url)) { any = true; emit(it) }
        }

        if (!any) push(runCatching { VidSrcBuzz.links(http, tmdb, season, episode) }.getOrDefault(emptyList()))
        if (!any) throw SourceException.VideoUnavailable("aucun serveur exploitable")
    }
}
