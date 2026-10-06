package fr.endlesssea.ext.unjour1film

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
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.Json
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.TmdbEmbeds
import java.util.Base64

/**
 * 1JOUR1FILM — portage **natif** Endless Sea.
 *
 * Site WordPress protégé par Cloudflare, dont tout le protocole passe par
 * `admin-ajax.php` :
 *
 *  - catalogue : `POST j1f_catalogue` (type, tri, page) → `{data:{pages, html}}`,
 *    avec repli sur l'API REST `/wp-json/wp/v2/{movies|tvshows}` ;
 *  - fiches : les variables utiles (`J1F_POST_ID`, `J1F_SEASON_ID`,
 *    `j1fEpsData`, `var tmdb`) sont dans des **scripts inline encodés en
 *    base64** (`data:text/javascript;base64,…`) ;
 *  - lecture : `j1f_get_nonce` puis `j1f_get_source` / `j1f_get_ep_source`
 *    (indices 0..5) → URL d'hébergeur, complétée par les lecteurs publics TMDB.
 *
 * Charges utiles : `j1fm:{postId}:{tmdb}` et `j1fe:{seasonId}:{epId}:{tmdb}:{s}:{e}`.
 */
class UnJour1FilmExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://1jour1film0926b.lol"
    override val providerName = "1Jour1Film"
    override val extensionId = "fr.endlesssea.ext.unjour1film"
    override val versionCode = 20
    override val descriptionText = "Films et séries VF en streaming."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override val homeRows = listOf(
        HomeRow("sorties", "Dernières sorties", "/dernieres-sorties/"),
        HomeRow("movies", "Films", "movies"),
        HomeRow("tvshows", "Séries", "tvshows"),
    )

    private fun ajaxHeaders(referer: String) = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Origin" to mainUrl,
        "Referer" to referer,
    )

    // -----------------------------------------------------------------------
    // Catalogue
    // -----------------------------------------------------------------------

    /** `POST j1f_catalogue` → (nombre de pages, html des cartes). */
    private suspend fun catalogue(type: String, page: Int, search: String): Pair<Int, String> {
        val res = http.getOrNull("$mainUrl/wp-admin/admin-ajax.php") // amorce les cookies
        val text = runCatching {
            http.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = ajaxHeaders("$mainUrl/catalogue-films/"),
                data = mapOf(
                    "action" to "j1f_catalogue", "type" to type,
                    "genre" to "", "annee" to "", "qualite" to "", "reseau" to "", "decennie" to "",
                    "tri" to "date", "search" to search, "page" to page.toString(),
                ),
            ).text
        }.getOrNull() ?: return 1 to ""
        if (res == null && text.isBlank()) return 1 to ""
        val root = Json.parseOrNull(text) ?: return 1 to ""
        if (root["success"].bool != true) return 1 to ""
        return (root["data"]["pages"].int ?: 1) to (root["data"].str("html") ?: "")
    }

    /** Repli REST : insensible aux changements du protocole admin-ajax (sans posters). */
    private suspend fun restCards(type: String, page: Int): Pair<List<SearchItem>, Boolean> {
        val root = http.getOrNull("$mainUrl/wp-json/wp/v2/$type?per_page=60&page=$page")?.asJsonOrNull()
            ?: return emptyList<SearchItem>() to false
        val out = root.list.mapNotNull { p ->
            val link = p.str("link")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val title = p["title"].str("rendered")?.let { Text.stripHtml(it) }?.takeIf { it.isNotBlank() }
                ?: link.trimEnd('/').substringAfterLast('/').replace('-', ' ')
            val year = Text.year(p.str("dtyear"))
            item(
                if (year != null) "$title ($year)" else title, link,
                if (type == "tvshows") MediaType.SERIES else MediaType.MOVIE, year = year,
            )
        }
        return out to (out.size >= 60)
    }

    /** Cartes `j1f-card` du HTML renvoyé par l'ajax. */
    private fun parseCards(html: String): List<SearchItem> = Regex(
        """<a href="((?:https?://[^"]*)?/(?:films|tvshows)/([a-z0-9-]+)/)"[^>]*class="j1f-card"[^>]*>(.*?)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(html).map { m ->
        val href = Text.fixUrl(m.groupValues[1], mainUrl)
        val seg = m.groupValues[3]
        val title = Regex("""<div class="card-title">([^<]+)</div>""").find(seg)?.groupValues?.get(1)?.trim()
            ?: Regex("""alt="([^"]*)"""").find(seg)?.groupValues?.get(1)?.trim()
            ?: m.groupValues[2].replace('-', ' ')
        item(
            title, href,
            if ("/tvshows/" in href) MediaType.SERIES else MediaType.MOVIE,
            Regex("""<img[^>]+(?:data-src|src)="(https?://[^"]+)"""").find(seg)?.groupValues?.get(1),
        )
    }.distinctBy { it.url }.toList()

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (row.key == "sorties") {
            if (page > 1) return PagedResult(emptyList(), page, false)
            val html = http.getOrNull(mainUrl + row.path)?.text
            val cards = html?.let { parseCards(it) }.orEmpty()
            if (cards.isNotEmpty()) return PagedResult(cards, page, false)
            val rest = restCards("movies", 1).first + restCards("tvshows", 1).first
            if (rest.isNotEmpty()) return PagedResult(rest, page, false)
            throw SourceException.VideoUnavailable(
                "site inaccessible — modifiez l'adresse dans les réglages de l'extension si le domaine a changé",
            )
        }
        val (pages, html) = catalogue(row.path, page, "")
        val items = parseCards(html)
        if (items.isEmpty()) {
            val (rest, hasNext) = restCards(row.path, page)
            if (rest.isNotEmpty()) return PagedResult(rest, page, hasNext)
            throw SourceException.VideoUnavailable("catalogue inaccessible")
        }
        return PagedResult(items, page, page < pages && items.size >= 20)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val out = ArrayList<SearchItem>()
        out += parseCards(runCatching { catalogue("movies", 1, query).second }.getOrDefault(""))
        out += parseCards(runCatching { catalogue("tvshows", 1, query).second }.getOrDefault(""))
        if (out.isEmpty()) {
            out += restCards("movies", 1).first.filter { it.title.contains(query, ignoreCase = true) }
        }
        return out.distinctBy { it.url }
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    /** Scripts inline embarqués en base64 (`data:text/javascript;base64,…`). */
    private fun decodeInlineScripts(html: String): List<String> =
        Regex("""base64,([A-Za-z0-9+/=]{50,})""").findAll(html).mapNotNull { m ->
            runCatching { String(Base64.getDecoder().decode(m.groupValues[1])) }.getOrNull()
        }.toList()

    override suspend fun details(url: String): MediaDetails {
        val html = http.get(url).verifyNotBlocked().requireOk().text
        val title = Regex("""<title>([^<]+)</title>""").find(html)?.groupValues?.get(1)
            ?.substringBefore('|')?.trim() ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""property="og:description" content="([^"]*)"""").find(html)?.groupValues?.get(1)?.trim()
        val year = Text.year(title)
        val scripts = decodeInlineScripts(html)

        if ("/tvshows/" in url) {
            val seasonLinks = Regex("""href="((?:https?://[^"]*)?/saisons/([a-z0-9-]+)/)"[^>]*class="season-card"""")
                .findAll(html).map { Text.fixUrl(it.groupValues[1], mainUrl) }.distinct().toList()
            if (seasonLinks.isEmpty()) throw SourceException.VideoUnavailable("aucune saison trouvée")

            val mediaId = Text.idOf("j1f", url)
            val seasons = ArrayList<Season>()
            seasonLinks.forEachIndexed { idx, seasonUrl ->
                val seasonHtml = http.getOrNull(seasonUrl)?.text ?: return@forEachIndexed
                val s = decodeInlineScripts(seasonHtml)
                val seasonId = s.firstNotNullOfOrNull {
                    Regex("""J1F_SEASON_ID\s*=\s*(\d+)""").find(it)?.groupValues?.get(1)
                } ?: return@forEachIndexed
                val seasonNum = Regex("""[Ss]aison\s*(\d+)""")
                    .find(Regex("""<title>([^<]+)</title>""").find(seasonHtml)?.groupValues?.get(1).orEmpty())
                    ?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                val tmdbId = s.firstNotNullOfOrNull {
                    Regex("""var\s+tmdb\s*=\s*(\d+)\s*;""").find(it)?.groupValues?.get(1)
                } ?: "0"
                val realSeason = s.firstNotNullOfOrNull {
                    Regex("""var\s+season\s*=\s*(\d+)\s*;""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                } ?: seasonNum
                val epsData = s.firstNotNullOfOrNull {
                    Regex("""j1fEpsData\s*=\s*(\[.*?]);""", RegexOption.DOT_MATCHES_ALL).find(it)
                        ?.groupValues?.get(1)?.let(Json::parseOrNull)
                } ?: return@forEachIndexed

                val eps = epsData.list.mapNotNull { ep ->
                    val epId = ep["id"].int ?: return@mapNotNull null
                    val num = ep.str("num")?.toIntOrNull() ?: return@mapNotNull null
                    if (epId <= 0) return@mapNotNull null
                    Episode(
                        id = "$mediaId:s${seasonNum}e$num", number = num.toFloat(), season = seasonNum,
                        title = ep.str("label"),
                        thumbnailUrl = ep.str("backdrop") ?: poster,
                        data = "j1fe:$seasonId:$epId:$tmdbId:$realSeason:$num",
                    )
                }
                if (eps.isNotEmpty()) seasons += Season(seasonNum, "Saison $seasonNum", eps)
            }
            if (seasons.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode disponible")
            return MediaDetails(
                id = mediaId, url = url, title = Text.decodeHtml(title),
                synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
                type = MediaType.SERIES, year = year,
                episodeCount = seasons.sumOf { it.episodes.size },
                seasons = seasons.sortedBy { it.number },
            )
        }

        val postId = scripts.firstNotNullOfOrNull {
            Regex("""J1F_POST_ID\s*=\s*(\d+)""").find(it)?.groupValues?.get(1)
        } ?: throw SourceException.VideoUnavailable("identifiant du film introuvable")
        val tmdbId = scripts.firstNotNullOfOrNull {
            Regex("""vp4-(\d+)-""").find(it)?.groupValues?.get(1)
        } ?: "0"
        return movieDetails(
            url = url, title = title, payload = "j1fm:$postId:$tmdbId",
            poster = poster, synopsis = plot, year = year,
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    private suspend fun nonce(): String? = runCatching {
        http.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = ajaxHeaders("$mainUrl/"),
            data = mapOf("action" to "j1f_get_nonce"),
        ).asJsonOrNull()?.get("data")?.str("nonce")
    }.getOrNull()

    private suspend fun fetchSource(params: Map<String, String>): String? = runCatching {
        val root = http.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            headers = ajaxHeaders("$mainUrl/"),
            data = params,
        ).asJsonOrNull() ?: return@runCatching null
        if (root["success"].bool != true) return@runCatching null
        root["data"].str("url")?.takeIf { it.startsWith("http") }
    }.getOrNull()

    override suspend fun servers(payload: String): List<ServerEntry> {
        val nonce = nonce() ?: return emptyList()
        val entries = ArrayList<ServerEntry>()
        var tmdb = "0"
        var season: Int? = null
        var episode: Int? = null

        when {
            payload.startsWith("j1fm:") -> {
                val parts = payload.removePrefix("j1fm:").split(":")
                tmdb = parts.getOrNull(1) ?: "0"
                for (idx in 0 until 6) {
                    val u = fetchSource(
                        mapOf("action" to "j1f_get_source", "nonce" to nonce, "post_id" to parts[0], "idx" to idx.toString()),
                    ) ?: continue
                    entries += ServerEntry(Text.host(u), u, referer = mainUrl)
                }
            }
            payload.startsWith("j1fe:") -> {
                val parts = payload.removePrefix("j1fe:").split(":")
                if (parts.size < 2) return emptyList()
                tmdb = parts.getOrNull(2) ?: "0"
                season = parts.getOrNull(3)?.toIntOrNull()
                episode = parts.getOrNull(4)?.toIntOrNull()
                for (idx in 0 until 4) {
                    val u = fetchSource(
                        mapOf(
                            "action" to "j1f_get_ep_source", "nonce" to nonce,
                            "season_id" to parts[0], "ep_id" to parts[1], "idx" to idx.toString(),
                        ),
                    ) ?: continue
                    entries += ServerEntry(Text.host(u), u, referer = mainUrl)
                }
            }
        }

        if (tmdb != "0" && tmdb.isNotBlank()) {
            entries += TmdbEmbeds.publicEmbeds(tmdb, season, episode)
        }
        return entries.distinctBy { it.url }
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val entries = servers(data.episode.data)
        if (entries.isEmpty()) throw SourceException.VideoUnavailable("aucune source renvoyée par le site")
        return resolveServers(entries, data.preferredServer)
    }
}
