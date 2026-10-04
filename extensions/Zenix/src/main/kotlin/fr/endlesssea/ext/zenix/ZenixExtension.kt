package fr.endlesssea.ext.zenix

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
import fr.endlesssea.common.OneEmbed
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.allExtractorsWithAggregators

/**
 * Zenix (zenix.best) — portage **natif** Endless Sea.
 *
 *  - accueil : `/trending`, `/top-imdb`, `/movies`, `/tv-shows`, `/genre/…` ;
 *  - recherche : API `/ajax/search/suggest?q=…` (la page HTML ne filtre pas) ;
 *  - fiche : `/movie/{slug}` ou `/tv-show/{slug}` (les pages `/episode/` remontent
 *    à leur série) ;
 *  - lecture : boutons `selectStream(...)` de la page + agrégateurs TMDB
 *    (1Embed, Playerix, Wiflix, Movix, MoviesApi, VidSrc.buzz).
 */
class ZenixExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://zenix.best"
    override val providerName = "Zenix"
    override val extensionId = "fr.endlesssea.ext.zenix"
    override val versionCode = 12
    override val descriptionText = "Films et séries VF/VOSTFR, serveurs du site + agrégateurs TMDB."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override fun extractors() = allExtractorsWithAggregators(http)

    override val homeRows = listOf(
        HomeRow("trending", "🔥 Tendances", "/trending"),
        HomeRow("top-imdb", "⭐ Top IMDb", "/top-imdb"),
        HomeRow("movies", "🎬 Films", "/movies"),
        HomeRow("tv-shows", "📺 Séries", "/tv-shows"),
        HomeRow("action", "💥 Action", "/genre/action"),
        HomeRow("adventure", "🧭 Aventure", "/genre/adventure"),
        HomeRow("animation", "✨ Animation", "/genre/animation"),
        HomeRow("comedy", "😂 Comédie", "/genre/comedy"),
        HomeRow("sf", "🚀 Science-Fiction", "/genre/science-fiction"),
        HomeRow("horror", "👻 Horreur", "/genre/horror"),
        HomeRow("thriller", "🔪 Thriller", "/genre/thriller"),
        HomeRow("romance", "❤️ Romance", "/genre/romance"),
        HomeRow("crime", "🕵️ Policier", "/genre/crime"),
        HomeRow("drama", "🎭 Drame", "/genre/drama"),
    )

    private val episodeRegex = Regex("""/episode/[^/]+/(\d+)-(\d+)""")
    private val selectStreamRegex = Regex("""selectStream\(\d+,\s*'(.*?)',\s*'(.*?)',\s*'([a-zA-Z_]+)'\)""")

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (page > 1) return PagedResult(emptyList(), page, false)
        val doc = http.get(mainUrl + row.path).requireOk().document
        val items = doc.select("a.zenix-card__inner").mapNotNull { el ->
            val href = el.attr("href").trim()
            if (href.isBlank() || href.startsWith("javascript")) return@mapNotNull null
            val url = Text.fixUrl(href, mainUrl)
            val title = el.attr("aria-label").trim()
                .ifBlank { el.selectFirst(".zenix-card__title-wrapper img")?.attr("alt")?.trim().orEmpty() }
            if (title.isBlank()) return@mapNotNull null
            val poster = el.selectFirst(".zenix-card__img")?.let { img ->
                listOf("data-src", "data-original", "src").firstNotNullOfOrNull { a ->
                    img.attr(a).takeIf { it.startsWith("http") }
                }
            }
            item(
                title, url,
                if ("/tv-show/" in url) MediaType.SERIES else MediaType.MOVIE,
                poster,
                el.selectFirst(".zenix-card__meta > span")?.text()?.trim()?.toIntOrNull(),
            )
        }.distinctBy { it.url }
        return PagedResult(items, page, false)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1 || query.trim().length < 2) return emptyList()
        val json = http.getOrNull("$mainUrl/ajax/search/suggest", params = mapOf("q" to query.trim()))
            ?.asJsonOrNull() ?: return emptyList()
        return json["posts"].list.mapNotNull { p ->
            val url = p.str("url")?.let { Text.fixUrl(it, mainUrl) } ?: return@mapNotNull null
            val title = p.str("title") ?: return@mapNotNull null
            item(
                title, url,
                if (p.str("type") == "tv") MediaType.SERIES else MediaType.MOVIE,
                p.str("image")?.takeIf { it.startsWith("http") },
                p.str("year")?.trim()?.toIntOrNull(),
            )
        }
    }

    override suspend fun details(url: String): MediaDetails {
        var fetchUrl = url
        var doc = http.get(fetchUrl).requireOk().document
        if ("/episode/" in fetchUrl) {
            doc.selectFirst("a[href*='/tv-show/']")?.attr("href")?.let {
                fetchUrl = Text.fixUrl(it, mainUrl)
                doc = http.get(fetchUrl).requireOk().document
            }
        }
        val isTv = "/tv-show/" in fetchUrl

        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("–")?.trim()
            ?: "Inconnu"
        val poster = doc.selectFirst(".zx-poster-wrap img")?.attr("src")?.takeIf { it.startsWith("http") }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }
        val plot = doc.selectFirst("[class*=overview]")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        var year: Int? = null
        val genres = ArrayList<String>()
        doc.select(".zx-meta .zx-badge").forEach { badge ->
            val text = badge.text().replace('\u00A0', ' ').trim()
            if (text.isBlank()) return@forEach
            val cls = badge.className()
            when {
                "zx-badge-gold" in cls || "zx-badge-views" in cls || "zx-badge-imdb" in cls -> Unit
                text.endsWith("min") -> Unit
                text.toIntOrNull() != null -> if (year == null) year = text.toInt()
                else -> genres.add(text)
            }
        }

        if (!isTv) {
            return movieDetails(
                url = fetchUrl, title = title, payload = fetchUrl, poster = poster,
                synopsis = plot, year = year, genres = genres.distinct(),
            )
        }

        val mediaId = Text.idOf("zeni", fetchUrl)
        val raw = doc.select("a[href*='/episode/']").mapNotNull { a ->
            val href = a.attr("href")
            val m = episodeRegex.find(href) ?: return@mapNotNull null
            val season = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val number = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val name = a.selectFirst("h3")?.text()?.trim()?.takeIf {
                it.isNotBlank() && !Regex("""\.(mp4|mkv|avi)$""", RegexOption.IGNORE_CASE).containsMatchIn(it)
            } ?: "Épisode $number"
            Triple(Text.fixUrl(href, mainUrl), season to number, name to a.selectFirst("img")?.attr("src"))
        }.distinctBy { it.first }.sortedWith(compareBy({ it.second.first }, { it.second.second }))

        if (raw.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode listé")

        val seasons = raw.groupBy { it.second.first }.toSortedMap().map { (s, list) ->
            Season(
                s, "Saison $s",
                list.map { (href, se, meta) ->
                    Episode(
                        id = "$mediaId:s${s}e${se.second}", number = se.second.toFloat(), season = s,
                        title = meta.first, thumbnailUrl = meta.second?.takeIf { it.startsWith("http") },
                        data = href,
                    )
                },
            )
        }

        return MediaDetails(
            id = mediaId, url = fetchUrl, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
            type = MediaType.SERIES, year = year, genres = genres.distinct(),
            episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val pageUrl = data.episode.data
        val raw = http.get(pageUrl).requireOk().text
        val epMatch = episodeRegex.find(pageUrl)
        val season = epMatch?.groupValues?.get(1)?.toIntOrNull()
        val episode = epMatch?.groupValues?.get(2)?.toIntOrNull()
        val isTv = "/tv-show/" in pageUrl || "/episode/" in pageUrl
        val tmdb = Regex("""[?&]tmdb=(\d+)""").find(raw)?.groupValues?.get(1)

        val out = LinkedHashMap<String, VideoLink>()

        // 1) 1Embed (HLS directs, les plus fiables)
        if (tmdb != null) {
            val oneEmbedUrl = when {
                epMatch != null -> "https://1embed.cc/embed/tv/$tmdb/$season/$episode"
                isTv -> "https://1embed.cc/embed/tv/$tmdb/1/1"
                else -> "https://1embed.cc/embed/movie/$tmdb"
            }
            runCatching { OneEmbed(http).getUrl(oneEmbedUrl, mainUrl) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it) }
        }

        // 2) boutons « Serveurs de lecture » de la page
        val entries = ArrayList<ServerEntry>()
        selectStreamRegex.findAll(raw).forEach { m ->
            val u = m.groupValues[1].replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")
            val label = m.groupValues[2]
            if (u.startsWith("http")) entries += ServerEntry(label, u, Text.audioLang(label), referer = mainUrl)
        }

        // 3) agrégateurs TMDB
        if (tmdb != null) {
            entries += runCatching { Aggregators.playerix(http, tmdb, season, episode, m3u8Only = isTv) }.getOrDefault(emptyList())
            entries += runCatching { TmdbEmbeds.wiflix(http, tmdb, season, episode) }.getOrDefault(emptyList())
            entries += runCatching { Aggregators.movix(http, tmdb, season, episode) }.getOrDefault(emptyList())
            entries += runCatching { Aggregators.moviesApi(http, tmdb, season, episode) }.getOrDefault(emptyList())
            entries += TmdbEmbeds.publicEmbeds(tmdb, season, episode)
        }

        if (entries.isNotEmpty()) {
            runCatching { resolveServers(entries, data.preferredServer) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it) }
        }

        if (tmdb != null && out.isEmpty()) {
            runCatching { VidSrcBuzz.links(http, tmdb, season, episode) }
                .getOrDefault(emptyList())
                .forEach { out.putIfAbsent(it.url, it) }
        }

        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun serveur exploitable")
        return out.values.sortedByDescending { it.quality.pixels }
    }

    override suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
