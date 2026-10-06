package fr.endlesssea.ext.wavewatch

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
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
import fr.endlesssea.common.Json
import fr.endlesssea.common.JsonNode
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz

/**
 * WaveWatch (wavewatch.top) — portage **natif** Endless Sea.
 *
 *  - catalogue : l'API maison du site, qui proxifie TMDB
 *    (`/api/tmdb/trending/…`, `/api/tmdb/discover/…`, `/api/tmdb/search`) ;
 *  - chaînes TV en direct : `/api/tv-channels` (les chaînes « ADULTE » sont
 *    exclues, ce portage étant sans contenu NSFW) ;
 *  - lecture : `https://wwembed.{domaine}/api/v1/streaming/ww-{movie|tv}-…`
 *    (tableau JS `var _src = [...]`), complété par les agrégateurs du réseau
 *    (zeus SSE, wiflix, playerix, movix, mouve, moviesapi, vidsrc.buzz).
 *
 * Charges utiles : `live|{id}`, `movie|{tmdb}`, `tv|{tmdb}|{s}|{e}`.
 */
class WaveWatchExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://wavewatch.top"
    override val providerName = "WaveWatch"
    override val extensionId = "fr.endlesssea.ext.wavewatch"
    override val versionCode = 25
    override val descriptionText = "Films, séries, animes et chaînes TV en direct."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.ANIME, MediaType.OTHER)

    private val tmdbImage = "https://image.tmdb.org/t/p"
    private fun wwembedBase() = "https://wwembed." + Text.host(mainUrl)

    override val homeRows = listOf(
        HomeRow("tr-movies", "🔥 Films Tendance", "tmdb:/api/tmdb/trending/movies"),
        HomeRow("tr-tv", "📺 Séries Tendance", "tmdb:/api/tmdb/trending/tv"),
        HomeRow("tr-anime", "🌸 Animes Tendance", "tmdb:/api/tmdb/trending/anime"),
        HomeRow("upcoming", "📅 Prochaines Sorties", "tmdb:/api/tmdb/upcoming/movies"),
        HomeRow("action", "💥 Films Action", "paged:/api/tmdb/discover/movie?genre=28"),
        HomeRow("comedie", "😂 Films Comédie", "paged:/api/tmdb/discover/movie?genre=35"),
        HomeRow("sf", "🚀 Films Science-Fiction", "paged:/api/tmdb/discover/movie?genre=878"),
        HomeRow("horreur", "👻 Films Horreur", "paged:/api/tmdb/discover/movie?genre=27"),
        HomeRow("thriller", "🔪 Films Thriller", "paged:/api/tmdb/discover/movie?genre=53"),
        HomeRow("animation", "✨ Films Animation", "paged:/api/tmdb/discover/movie?genre=16"),
        HomeRow("tv-action", "🗡️ Séries Action & Aventure", "paged:/api/tmdb/discover/tv?genre=10759"),
        HomeRow("tv-drame", "🎭 Séries Drame", "paged:/api/tmdb/discover/tv?genre=18"),
        HomeRow("tv-anim", "🎴 Séries Animation", "paged:/api/tmdb/discover/tv?genre=16"),
        HomeRow("live", "📡 Chaînes TV en Direct", "live:"),
    )

    private fun card(n: JsonNode, isTv: Boolean, isAnime: Boolean): SearchItem? {
        val id = n["id"].int ?: return null
        val title = (n.str("title") ?: n.str("name")) ?: return null
        val type = when {
            isAnime -> MediaType.ANIME
            isTv -> MediaType.SERIES
            else -> MediaType.MOVIE
        }
        val url = if (isTv || isAnime) "$mainUrl/tv/$id" else "$mainUrl/movie/$id"
        return item(
            title, url, type,
            n.str("poster_path")?.let { "$tmdbImage/w500$it" },
            (n.str("release_date") ?: n.str("first_air_date"))?.take(4)?.toIntOrNull(),
        )
    }

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        // ---- Chaînes TV en direct ----
        if (row.path == "live:") {
            if (page > 1) return PagedResult(emptyList(), page, false)
            val channels = http.getOrNull("$mainUrl/api/tv-channels")?.asJsonOrNull()?.get("channels")?.list.orEmpty()
            val cards = channels.mapNotNull { ch ->
                if (ch["isActive"].bool == false) return@mapNotNull null
                if (ch.str("category")?.trim()?.uppercase() == "ADULTE") return@mapNotNull null
                if (ch.str("streamUrl")?.startsWith("http") != true) return@mapNotNull null
                val id = ch.str("id") ?: return@mapNotNull null
                item(ch.str("name") ?: "Chaîne", "$mainUrl/live/$id", MediaType.OTHER, ch.str("logoUrl"))
            }
            return PagedResult(cards, page, false)
        }

        val paged = row.path.startsWith("paged:")
        if (!paged && page > 1) return PagedResult(emptyList(), page, false)
        val path = row.path.removePrefix("tmdb:").removePrefix("paged:")
        val sep = if ("?" in path) "&" else "?"
        val root = http.getOrNull("$mainUrl$path${sep}page=$page")?.asJsonOrNull()
            ?: return PagedResult(emptyList(), page, false)
        val isTv = "/tv" in path
        val isAnime = "/anime" in path
        val items = root["results"].list.mapNotNull { card(it, isTv, isAnime) }.distinctBy { it.url }
        return PagedResult(items, page, paged && items.isNotEmpty())
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1 || query.trim().length < 2) return emptyList()
        val root = http.getOrNull("$mainUrl/api/tmdb/search", params = mapOf("q" to query.trim()))?.asJsonOrNull()
            ?: return emptyList()
        return root["results"].list.mapNotNull { card(it, it.str("media_type") == "tv", false) }
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        // ---- Chaîne en direct ----
        if ("/live/" in url) {
            val id = url.substringAfterLast('/')
            val ch = http.getOrNull("$mainUrl/api/tv-channels")?.asJsonOrNull()?.get("channels")?.list
                ?.firstOrNull { it.str("id") == id }
            return movieDetails(
                url = url, title = ch?.str("name") ?: "Chaîne TV", payload = "live|$id",
                poster = ch?.str("logoUrl"), synopsis = ch?.str("description"),
                type = MediaType.OTHER,
                genres = listOfNotNull(ch?.str("category")?.replaceFirstChar { it.uppercase() }),
            )
        }

        val tmdb = Regex("""/(movie|tv)/(\d+)""").find(url)?.groupValues?.get(2)
            ?: throw SourceException.VideoUnavailable("URL non reconnue")

        if ("/movie/" in url) {
            val d = http.get("$mainUrl/api/tmdb/movie/$tmdb").requireOk().asJson()
            return movieDetails(
                url = url, title = d.str("title") ?: "Film", payload = "movie|$tmdb",
                poster = d.str("poster_path")?.let { "$tmdbImage/w500$it" },
                banner = d.str("backdrop_path")?.let { "$tmdbImage/original$it" },
                synopsis = d.str("overview"),
                year = d.str("release_date")?.take(4)?.toIntOrNull(),
                genres = d["genres"].list.mapNotNull { it.str("name") },
                durationMin = d["runtime"].int,
            )
        }

        val d = http.get("$mainUrl/api/tmdb/tv/$tmdb").requireOk().asJson()
        val title = d.str("name") ?: "Série"
        val genres = d["genres"].list.mapNotNull { it.str("name") }
        val isAnime = genres.any { it.equals("Animation", true) }
        val mediaId = Text.idOf("wave", url)

        val seasons = d["seasons"].list
            .mapNotNull { it["season_number"].int?.takeIf { n -> n > 0 } }
            .sorted()
            .mapNotNull { n ->
                val sd = http.getOrNull("$mainUrl/api/tmdb/tv/$tmdb/season/$n")?.asJsonOrNull() ?: return@mapNotNull null
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
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = d.str("overview")?.let { Text.stripHtml(it) },
            posterUrl = d.str("poster_path")?.let { "$tmdbImage/w500$it" },
            bannerUrl = d.str("backdrop_path")?.let { "$tmdbImage/original$it" },
            type = if (isAnime) MediaType.ANIME else MediaType.SERIES,
            year = d.str("first_air_date")?.take(4)?.toIntOrNull(),
            genres = genres, episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    /** `var _src = [...]` de la page wwembed. */
    private fun parseSrcArray(html: String): List<JsonNode> =
        Regex("""var\s+_src\s*=\s*(\[.*?])\s*;""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.groupValues?.get(1)?.let { Json.parseOrNull(it) }?.list.orEmpty()

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        val kind = parts.firstOrNull()

        // ---- Chaîne TV en direct ----
        if (kind == "live") {
            val id = parts.getOrNull(1) ?: return emptyList()
            val stream = http.getOrNull("$mainUrl/api/tv-channels")?.asJsonOrNull()?.get("channels")?.list
                ?.firstOrNull { it.str("id") == id }?.str("streamUrl") ?: return emptyList()
            return listOf(ServerEntry("WaveWatch TV", stream, direct = true, referer = mainUrl))
        }

        val tmdb = parts.getOrNull(1) ?: return emptyList()
        val isTv = kind == "tv"
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()
        val out = LinkedHashMap<String, ServerEntry>()

        // 1) sources du site (wwembed) : la langue est portée par chaque lecteur
        val embedUrl = if (isTv && season != null && episode != null) {
            "${wwembedBase()}/api/v1/streaming/ww-tv-$tmdb-s$season-e$episode"
        } else {
            "${wwembedBase()}/api/v1/streaming/ww-movie-$tmdb"
        }
        http.getOrNull(embedUrl, referer = "$mainUrl/")?.text?.let { html ->
            parseSrcArray(html).forEach { s ->
                val u = s.str("url")?.takeIf { it.startsWith("http") } ?: return@forEach
                val name = s.str("name") ?: Text.host(u)
                val lang = s.str("lang")
                out.putIfAbsent(
                    u,
                    ServerEntry(
                        name + (lang?.let { " · ${it.uppercase()}" } ?: ""), u,
                        Text.audioLang(lang ?: name),
                        referer = "$mainUrl/",
                        direct = s.str("format")?.lowercase() in setOf("hls", "mp4", "dash") && s["iframe"].bool != true,
                    ),
                )
            }
        }

        // 2) agrégateurs du réseau WaveWatch
        val aggregated = listOf(
            runCatching { Aggregators.zeus(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { TmdbEmbeds.wiflix(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { Aggregators.playerix(http, tmdb, season, episode, m3u8Only = isTv) }.getOrDefault(emptyList()),
            runCatching { Aggregators.movix(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            runCatching { Aggregators.moviesApi(http, tmdb, season, episode) }.getOrDefault(emptyList()),
            // mouve : films uniquement (en TV les liens ne dépendent pas de l'épisode)
            if (isTv) emptyList() else runCatching { Aggregators.mouve(http, tmdb) }.getOrDefault(emptyList()),
            TmdbEmbeds.publicEmbeds(tmdb, season, episode),
        ).flatten()
        aggregated.forEach { out.putIfAbsent(it.url, it) }

        return out.values.toList()
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val payload = data.episode.data
        val entries = servers(payload)
        val out = LinkedHashMap<String, VideoLink>()
        if (entries.isNotEmpty()) {
            runCatching { resolveServers(entries, data.preferredServer) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
        }
        if (out.isEmpty() && !payload.startsWith("live|")) {
            val parts = payload.split("|")
            runCatching {
                VidSrcBuzz.links(http, parts[1], parts.getOrNull(2)?.toIntOrNull(), parts.getOrNull(3)?.toIntOrNull())
            }.getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
        }
        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun serveur exploitable")
        return out.values.sortedByDescending { it.quality.pixels }
    }
}
