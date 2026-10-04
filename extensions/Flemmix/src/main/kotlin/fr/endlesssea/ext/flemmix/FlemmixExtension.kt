package fr.endlesssea.ext.flemmix

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
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text

/**
 * Flemmix (flemmix.cloud) — portage **natif** Endless Sea.
 *
 * Site DLE : les lecteurs sont déclarés par des appels `loadVideo('url')`.
 *
 *  - accueil : carrousels de `/` + listings `.mov` des catégories ;
 *  - recherche : POST DLE `/index.php?do=search`. ⚠ le site pose un
 *    « bot shield » : un script inline écrit le cookie `h_check=25`
 *    (`"h_check=" + (10+15)`) et le POST le vérifie — sans lui la réponse est
 *    « Bot shield active. » ;
 *  - séries : blocs `<div class="ep{N}vs">` (VOSTFR) / `ep{N}vf` (VF), le bloc
 *    `ep00` (saison complète) est ignoré ;
 *  - charge utile : `film|{url}` ou `ep|{url}|{n}`.
 */
class FlemmixExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://flemmix.eu"   // flemmix.cloud n'est plus qu'une page-relais (certificat de test)
    override val providerName = "Flemmix"
    override val extensionId = "fr.endlesssea.ext.flemmix"
    override val versionCode = 14
    override val descriptionText = "Films et séries VF/VOSTFR en streaming."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.ANIME)

    override val homeRows = listOf(
        HomeRow("accueil", "Derniers ajouts", "/"),
        HomeRow("films", "Films", "/film-en-streaming/"),
        HomeRow("films-action", "Films · Action", "/film-en-streaming/action/"),
        HomeRow("films-comedie", "Films · Comédie", "/film-en-streaming/comedie/"),
        HomeRow("films-animation", "Films · Animation", "/film-en-streaming/animation/"),
        HomeRow("films-thriller", "Films · Thriller", "/film-en-streaming/thriller/"),
        HomeRow("films-sf", "Films · Science-Fiction", "/film-en-streaming/science-fiction/"),
        HomeRow("films-horreur", "Films · Horreur", "/film-en-streaming/horreur/"),
        HomeRow("films-anciens", "Films anciens", "/film-ancien/"),
        HomeRow("series", "Séries", "/serie-en-streaming/"),
        HomeRow("vf", "Séries VF", "/vf/"),
        HomeRow("saisons", "Saisons complètes", "/saison-complete/"),
    )

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (row.key == "accueil" && page > 1) return PagedResult(emptyList(), page, false)
        val url = if (page <= 1) mainUrl + row.path else mainUrl + row.path.trimEnd('/') + "/page/$page/"
        val html = http.getOrNull(url)?.text ?: return PagedResult(emptyList(), page, false)
        // L'accueil n'a que des carrousels ; les catégories ont le listing « mov ».
        val items = if (row.key == "accueil") parseCarouselCards(html) else parseMovCards(html)
        return PagedResult(items, page, items.size >= 15)
    }

    private fun typeOf(url: String, title: String): MediaType = when {
        "/serie-en-streaming/" in url || "/saison-complete/" in url ||
            ("/vf/" in url && title.contains("saison", true)) -> MediaType.SERIES
        else -> MediaType.MOVIE
    }

    /** Listing catégorie : `<div class="mov-i…"><img …><a class="mov-t" href=…>`. */
    private fun parseMovCards(html: String): List<SearchItem> = Regex(
        """<div class="mov-i[^"]*">\s*<img[^>]+src="([^"]+)"[^>]*alt="([^"]*)"[^>]*/>.*?<a class="mov-t[^"]*" href="([^"]+)">([^<]*)</a>""",
        RegexOption.DOT_MATCHES_ALL,
    ).findAll(html).mapNotNull { m ->
        val title = Text.decodeHtml(m.groupValues[4]).trim().ifBlank { return@mapNotNull null }
        val url = Text.fixUrl(m.groupValues[3], mainUrl)
        item(title, url, typeOf(url, title), Text.fixUrl(m.groupValues[1], mainUrl))
    }.distinctBy { it.url }.toList()

    /** Carrousels de l'accueil : `<a href=…><img …><span class="title1">`. */
    private fun parseCarouselCards(html: String): List<SearchItem> = Regex(
        """<a\s+href="((?:https?://[^"]+)?/((?:film|serie)-en-streaming/\d+-[^"]+\.html))"[^>]*>\s*""" +
            """<img[^>]+src="([^"]+)"[^>]*alt="([^"]*)"[^>]*/>\s*<span class="title1">([^<]+)</span>""",
    ).findAll(html).mapNotNull { m ->
        val title = Text.decodeHtml(m.groupValues[5]).trim().ifBlank { return@mapNotNull null }
        val url = Text.fixUrl(m.groupValues[1], mainUrl)
        item(title, url, typeOf(url, title), Text.fixUrl(m.groupValues[3], mainUrl))
    }.distinctBy { it.url }.toList()

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val html = http.post(
            "$mainUrl/index.php?do=search",
            headers = mapOf("Origin" to mainUrl),
            referer = "$mainUrl/",
            cookies = mapOf("h_check" to "25"), // bot shield DLE
            data = mapOf(
                "do" to "search", "subaction" to "search", "story" to query,
                "search_start" to "0", "full_search" to "0", "result_from" to "1",
            ),
        ).text
        if (html.length < 500) return emptyList() // « Bot shield active. »

        // La page contient un bloc de recommandations masqué (#no-results-rec)
        // AVANT les vrais résultats : on ne parse que ce qui suit.
        val zone = html.substringAfter("no-results-rec", html).let { rest ->
            if (rest === html) html else rest.substringAfter("</div>", rest)
        }
        return Regex(
            """<div class="mov clearfix">\s*<div class="mov-i[^"]*">\s*<img[^>]+src="([^"]+)"""" +
                """.*?<a class="mov-t nowrap" href="(https?://[^"]+/\d+-[^"]+\.html)"[^>]*>([^<]+)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(zone).mapNotNull { m ->
            val title = Text.decodeHtml(m.groupValues[3]).trim().ifBlank { return@mapNotNull null }
            val url = m.groupValues[2]
            val isSeries = listOf("/serie-en-streaming/", "/saison-complete/", "/vf/", "/vostfr/").any { it in url }
            item(title, url, if (isSeries) MediaType.SERIES else MediaType.MOVIE, Text.fixUrl(m.groupValues[1], mainUrl))
        }.distinctBy { it.url }.toList()
    }

    override suspend fun details(url: String): MediaDetails {
        val html = http.get(url).verifyNotBlocked().requireOk().text

        val doc = org.jsoup.Jsoup.parse(html, url)
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: Regex("""<title>([^<]+?)\s*(?:&raquo;|»|\|)""").find(html)?.groupValues?.get(1)?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').substringAfter('-').replace('-', ' ')
        // Poster réel : <img id="posterimg" src="/checkimg.php?urli=…"> (og:image absent)
        val poster = (doc.selectFirst("img#posterimg")?.attr("src")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?.let { Text.fixUrlNull(it, mainUrl) }
        val plot = Regex("""Synopsis:</div>\s*<div class="mov-desc">\s*(?:<span[^>]*>)?([^<]+)""")
            .find(html)?.groupValues?.get(1)?.trim()
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() && !it.contains(".jpg") }
        val year = Regex("""Date de sortie:</div>\s*<div class="mov-desc">[^<]*?(\d{4})""")
            .find(html)?.groupValues?.get(1)?.toIntOrNull()
            ?: Text.year(title) ?: Text.year(html)

        val isSeries = "/serie-en-streaming/" in url || "/saison-complete/" in url
        if (isSeries) {
            // ⚠ le bloc « ep00 » est la saison complète, pas l'épisode 1.
            val epsVs = Regex("""<div class="ep(\d+)vs""").findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.distinct().sorted().toList()
            val epsVf = Regex("""<div class="ep(\d+)vf""").findAll(html)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.distinct().sorted().toList()
            if (epsVs.isNotEmpty() || epsVf.isNotEmpty()) {
                val mediaId = Text.idOf("flem", url)
                val seasons = ArrayList<Season>()
                if (epsVf.isNotEmpty()) {
                    seasons += Season(
                        1, "VF",
                        epsVf.map {
                            Episode("$mediaId:vf$it", it.toFloat(), 1, "Épisode $it", poster, data = "ep|$url|$it|vf")
                        },
                    )
                }
                if (epsVs.isNotEmpty()) {
                    seasons += Season(
                        if (epsVf.isNotEmpty()) 2 else 1, "VOSTFR",
                        epsVs.map {
                            Episode("$mediaId:vs$it", it.toFloat(), if (epsVf.isNotEmpty()) 2 else 1, "Épisode $it", poster, data = "ep|$url|$it|vs")
                        },
                    )
                }
                return MediaDetails(
                    id = mediaId, url = url, title = Text.decodeHtml(title),
                    synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
                    type = MediaType.SERIES, year = year,
                    episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
                    languages = buildList {
                        if (epsVf.isNotEmpty()) add(AudioLang.VF)
                        if (epsVs.isNotEmpty()) add(AudioLang.VOSTFR)
                    },
                )
            }
            // pas d'épisodes → film malgré l'URL
        }

        if (parseLoadVideo(html).isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur sur la fiche")
        return movieDetails(
            url = url, title = title, payload = "film|$url", poster = poster,
            synopsis = plot, year = year,
        )
    }

    /** Tous les couples (url, étiquette) déclarés par `loadVideo(...)`. */
    private fun parseLoadVideo(html: String): List<Pair<String, String>> =
        Regex("""loadVideo\('([^']+)'(?:,\s*this)?\)[^>]*>\s*<span[^>]*>([^<]*)</span>""")
            .findAll(html).mapNotNull { m ->
                val u = m.groupValues[1]
                if (!u.startsWith("http")) return@mapNotNull null
                u to m.groupValues[2].trim().ifBlank { Text.host(u) }
            }.distinctBy { it.first }.toList()

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        val articleUrl = parts.getOrNull(1) ?: return emptyList()
        val html = http.getOrNull(articleUrl)?.text ?: return emptyList()

        return if (parts.firstOrNull() == "ep") {
            val n = parts.getOrNull(2)?.toIntOrNull() ?: return emptyList()
            val suffix = parts.getOrNull(3) ?: "vs"
            val block = Regex("""<div class="ep$n$suffix"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1).orEmpty()
            val lang = if (suffix == "vf") AudioLang.VF else AudioLang.VOSTFR
            parseLoadVideo(block).map { (u, _) ->
                ServerEntry("${Text.host(u)} · ${if (suffix == "vf") "VF" else "VOSTFR"}", u, lang, referer = mainUrl)
            }
        } else {
            parseLoadVideo(html).map { (u, label) ->
                ServerEntry(label, u, Text.audioLang(label), referer = mainUrl)
            }
        }
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val entries = servers(data.episode.data)
        if (entries.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur pour cet épisode")
        return resolveServers(entries, data.preferredServer)
    }
}
