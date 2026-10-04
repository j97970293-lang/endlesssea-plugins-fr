package fr.endlesssea.ext.purstream

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaStatus
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.JsonNode
import fr.endlesssea.common.M3u8
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.urlEncode

/**
 * Purstream (purstream.ad) — portage **natif** Endless Sea.
 *
 * Tout passe par l'API JSON publique du site (`https://api.purstream.ad/api/v1/`) :
 * carrousels d'accueil, catalogue paginé, recherche, fiche, saisons, et
 * `stream/{id}` qui renvoie des playlists HLS directes.
 * Les lecteurs TMDB publics et l'agrégateur apiwiflix complètent la liste.
 */
class PurstreamExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://purstream.tech"   // purstream.ad redirige désormais vers le wiki

    /** Annuaire officiel : expose le domaine courant en JSON. */
    private val registryUrl = "https://purstream.wiki/api/status"

    @Volatile private var resolved: String? = null

    /**
     * Adresse courante : réglage utilisateur → annuaire `purstream.wiki`
     * → domaine par défaut. Le site tourne sur un domaine qui change souvent,
     * mais l'annuaire, lui, est stable.
     */
    override val mainUrl: String get() = userUrl ?: resolved ?: defaultUrl

    private suspend fun ensureDomain(): String {
        userUrl?.let { return it }
        resolved?.let { return it }
        val domain = http.getOrNull(registryUrl, mapOf("Accept" to "application/json"))
            ?.asJsonOrNull()?.str("domain")?.trim()?.trimEnd('/')
        val candidate = normalizeSiteUrl(domain)
        resolved = candidate ?: defaultUrl
        return resolved!!
    }

    /** L'API vit sur le sous-domaine `api.` du domaine courant. */
    private suspend fun apiUrl(): String = "https://api." + Text.host(ensureDomain()) + "/api/v1/"

    override val providerName = "Purstream"
    override val extensionId = "fr.endlesssea.ext.purstream"
    override val versionCode = 9
    override val descriptionText = "Films et séries VF/VOSTFR en HLS direct (API officielle du site)."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override val homeRows = listOf(
        HomeRow("recent", "Derniers ajouts"),
        HomeRow("catalog", "Catalogue"),
        HomeRow("carousel:Action", "Action"),
        HomeRow("carousel:Aventure", "Aventure"),
        HomeRow("carousel:Animation", "Animation"),
        HomeRow("carousel:Animé", "Animés"),
        HomeRow("carousel:HUMOUR", "Comédie"),
        HomeRow("carousel:DRAME", "Drame"),
        HomeRow("carousel:horreur", "Horreur"),
    )

    private val apiHeaders
        get() = mapOf("Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")

    private suspend fun api(path: String): JsonNode? =
        http.getOrNull(apiUrl() + path, apiHeaders)?.asJsonOrNull()

    // -----------------------------------------------------------------------
    // Accueil
    // -----------------------------------------------------------------------

    @Volatile
    private var carousels: Pair<Long, List<Pair<String, List<JsonNode>>>>? = null

    private suspend fun fetchCarousels(): List<Pair<String, List<JsonNode>>> {
        carousels?.let { (ts, v) -> if (System.currentTimeMillis() - ts < 120_000L) return v }
        val root = api("carousels/home") ?: return emptyList()
        val out = root["data"]["items"].list.map { c ->
            (c.str("title") ?: "") to c["movies"]["items"].list
        }.filter { it.first.isNotBlank() && it.second.isNotEmpty() }
        carousels = System.currentTimeMillis() to out
        return out
    }

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> = when {
        row.key == "recent" -> {
            if (page > 1) PagedResult(emptyList(), page, false)
            else PagedResult(
                api("last-released-movies/48")?.get("data")?.get("items")?.list.orEmpty().mapNotNull { toItem(it) },
                page, false,
            )
        }
        row.key == "catalog" -> {
            val node = api("catalog/movies?page=$page")?.get("data")?.get("items")
            val items = node?.get("data")?.list.orEmpty().mapNotNull { toItem(it) }
            val hasNext = (node?.get("current_page")?.int ?: 0) < (node?.get("last_page")?.int ?: 1)
            PagedResult(items, page, hasNext)
        }
        else -> {
            if (page > 1) PagedResult(emptyList(), page, false) else {
                val wanted = row.key.removePrefix("carousel:")
                val items = fetchCarousels().firstOrNull { it.first.equals(wanted, true) }
                    ?.second.orEmpty().mapNotNull { toItem(it) }
                PagedResult(items, page, false)
            }
        }
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val root = api("search-bar/search/${query.urlEncode()}") ?: return emptyList()
        return root["data"]["items"]["movies"]["items"].list.mapNotNull { toItem(it) }
    }

    private fun toItem(node: JsonNode): SearchItem? {
        val id = node["id"].int ?: return null
        if (id <= 0) return null
        val title = node.str("title") ?: return null
        val poster = node.str("large_poster_path") ?: node["posters"].str("large")
        val isTv = node.str("type") == "tv"
        val url = if (isTv) "$mainUrl/serie/$id" else "$mainUrl/movie/$id"
        return item(title, url, if (isTv) MediaType.SERIES else MediaType.MOVIE, poster)
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        val id = url.trimEnd('/').substringAfterLast('/').toIntOrNull()
            ?: throw SourceException.ParseError("identifiant absent de l'URL")
        val isTv = url.contains("/serie/")

        val sheet = api("media/$id/sheet") ?: throw SourceException.SourceUnavailable(null)
        val m = sheet["data"]["items"]
        val title = m.str("title") ?: "Purstream $id"
        val poster = m["posters"].str("large") ?: m["posters"].str("small")
        val plot = m.str("overview")
        val year = m.str("releaseDate")?.take(4)?.toIntOrNull()
        val genres = m["categories"].list.mapNotNull { it.str("name") }.take(8)
        val tmdb = m["tmdbId"].int?.takeIf { it > 0 }

        if (!isTv) {
            return movieDetails(
                url = url, title = title,
                payload = "movie|$id|${tmdb ?: ""}",
                poster = poster, synopsis = plot, year = year, genres = genres,
            )
        }

        data class EpMeta(val name: String?, val overview: String?, val poster: String?)
        val metas = HashMap<Pair<Int, Int>, EpMeta>()
        api("media/$id/seasons")?.get("data")?.get("items")?.list?.forEach { season ->
            val sDef = season["season"].int ?: 1
            season["episodes"].list.forEach { ep ->
                val n = ep["episode"].int ?: 0
                val s = ep["season"].int ?: sDef
                if (n > 0 && s > 0) metas[s to n] = EpMeta(ep.str("name"), ep.str("overview"), ep.str("poster"))
            }
        }

        val keys = sortedSetOf<Pair<Int, Int>>(compareBy({ it.first }, { it.second }))
        val re = Regex("""/S(\d+)/E(\d+)/""")
        m["urls"].list.forEach { u ->
            val mm = re.find(u.str("url").orEmpty()) ?: return@forEach
            val s = mm.groupValues[1].toIntOrNull() ?: return@forEach
            val e = mm.groupValues[2].toIntOrNull() ?: return@forEach
            if (s > 0 && e > 0) keys.add(s to e)
        }
        keys.addAll(metas.keys)
        if (keys.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode disponible")

        val mediaId = Text.idOf("purs", url)
        val seasons = keys.groupBy { it.first }.toSortedMap().map { (s, pairs) ->
            Season(
                s, "Saison $s",
                pairs.sortedBy { it.second }.map { (_, e) ->
                    val meta = metas[s to e]
                    Episode(
                        id = "$mediaId:s${s}e$e", number = e.toFloat(), season = s,
                        title = meta?.name, thumbnailUrl = meta?.poster ?: poster,
                        data = "tv|$id|${tmdb ?: ""}|$s|$e",
                    )
                },
            )
        }

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
            type = MediaType.SERIES, year = year, status = MediaStatus.ONGOING,
            genres = genres, episodeCount = seasons.sumOf { it.episodes.size },
            seasons = seasons,
            externalIds = tmdb?.let { mapOf("tmdb" to it.toString()) } ?: emptyMap(),
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val parts = data.episode.data.split("|")
        val kind = parts.getOrNull(0) ?: "movie"
        val id = parts.getOrNull(1).orEmpty()
        val tmdb = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        val season = parts.getOrNull(3)?.toIntOrNull()
        val episode = parts.getOrNull(4)?.toIntOrNull()

        val out = LinkedHashMap<String, VideoLink>()

        // 1) sources officielles du site (HLS direct)
        val endpoint = if (kind == "tv" && season != null && episode != null) {
            "stream/$id/episode?season=$season&episode=$episode"
        } else {
            "stream/$id"
        }
        api(endpoint)?.get("data")?.get("items")?.get("sources")?.list?.forEach { src ->
            val u = src.str("stream_url") ?: return@forEach
            if (!u.startsWith("http")) return@forEach
            val label = src.str("source_name") ?: "Source"
            M3u8.variants(http, u, "Purstream · $label", referer = "$mainUrl/")
                .ifEmpty { listOf(VideoLink(u, StreamType.HLS, server = "Purstream · $label")) }
                .forEach { out.putIfAbsent(it.url, it) }
        }

        // 2) lecteurs publics TMDB + agrégateur wiflix + vidsrc.buzz
        if (tmdb != null) {
            val entries = TmdbEmbeds.publicEmbeds(tmdb, season, episode) +
                runCatching { TmdbEmbeds.wiflix(http, tmdb, season, episode) }.getOrDefault(emptyList())
            runCatching { resolveServers(entries, data.preferredServer) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it.copy(server = "Purstream+ · ${it.server}")) }
            runCatching { VidSrcBuzz.links(http, tmdb, season, episode) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it) }
        }

        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucune source disponible")
        return out.values.sortedByDescending { it.quality.pixels }
    }

    override suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
