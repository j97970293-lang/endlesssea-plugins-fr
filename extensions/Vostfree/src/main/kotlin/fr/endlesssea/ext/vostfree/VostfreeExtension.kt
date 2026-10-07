package fr.endlesssea.ext.vostfree

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.urlEncode

/**
 * Vostfree (vostfree.ws) — portage **natif** Endless Sea.
 *
 * Site DataLife Engine : la fiche contient à la fois les épisodes et les
 * lecteurs. Les `<div id="content_player_N">` portent le jeton de chaque
 * lecteur, les `<div id="buttons_{ep}">` associent épisode → lecteurs.
 */
class VostfreeExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://vostfree.ws"
    override val providerName = "Vostfree"
    override val extensionId = "fr.endlesssea.ext.vostfree"
    override val versionCode = 15
    override val descriptionText = "Animes VF & VOSTFR et films (Sibnet, Uqload, VidMoly, Myvi…)."
    override val supportedTypes = setOf(MediaType.ANIME, MediaType.MOVIE)

    override val homeRows = listOf(
        HomeRow("vostfr", "Animes VOSTFR", "/animes-vostfr/"),
        HomeRow("vf", "Animes VF", "/animes-vf/"),
        HomeRow("films", "Films VF & VOSTFR", "/films-vf-vostfr/"),
    )

    private val baseHeaders = mapOf("Accept-Language" to "fr-FR,fr;q=0.9")

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val url = if (page <= 1) mainUrl + row.path else mainUrl + row.path.trimEnd('/') + "/page/$page/"
        val html = http.getOrNull(url, baseHeaders)?.text ?: return PagedResult(emptyList(), page, false)
        val items = parseCards(html)
        return PagedResult(items, page, items.size >= 12)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val html = http.getOrNull(
            "$mainUrl/index.php?do=search&subaction=search&story=${query.urlEncode()}",
            baseHeaders,
        )?.text ?: return emptyList()

        val results = Regex(
            """<span class="image"><img src="([^"]+)" alt="([^"]+)"[^>]*/></span>\s*<div class="info">\s*<div class="title"><a href="([^"]+)""""
        ).findAll(html).mapNotNull { m ->
            val url = Text.fixUrl(m.groupValues[3], mainUrl)
            if (!Regex("""/\d+-[a-z0-9-]+\.html$""").containsMatchIn(url)) return@mapNotNull null
            val title = m.groupValues[2].trim()
            if (title.isBlank()) null
            else item(title, url, MediaType.ANIME, Text.fixUrl(m.groupValues[1], mainUrl))
        }.toList()

        return results.ifEmpty { parseCards(html) }
    }

    private fun parseCards(html: String): List<SearchItem> {
        val out = LinkedHashMap<String, SearchItem>()
        Regex(
            """<a\s+href="((?:https?://[^"]+)?/\d+-[a-z0-9-]+\.html)"[^>]*title="([^"]+)"[^>]*>\s*<img[^>]+src="([^"]+)""""
        ).findAll(html).forEach { m ->
            val url = Text.fixUrl(m.groupValues[1], mainUrl)
            val title = m.groupValues[2].trim()
            if (title.isBlank()) return@forEach
            val slug = url.trimEnd('/').substringAfterLast('/')
            val isFilm = slug.contains("film") || slug.contains("movie") ||
                (!url.contains("anime") && title.contains("film", true))
            out.putIfAbsent(
                url,
                item(title, url, if (isFilm) MediaType.MOVIE else MediaType.ANIME, Text.fixUrl(m.groupValues[3], mainUrl)),
            )
        }
        return out.values.take(40)
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        val res = http.get(url, baseHeaders).requireOk()
        val doc = res.document
        val html = res.text

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: url.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst(".slide-poster img")?.attr("src")
        val plot = doc.selectFirst(".slide-desc")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Text.year(html)
        val lang = if (title.contains("VF", true) && !title.contains("VOSTFR", true)) AudioLang.VF else AudioLang.VOSTFR

        val blocks = episodeBlocks(html)
        if (blocks.isEmpty()) throw SourceException.ParseError("aucun lecteur sur la fiche")

        if (blocks.size == 1 && blocks.keys.first() <= 1) {
            return movieDetails(
                url = url, title = title, payload = "$url|${blocks.keys.first()}",
                poster = poster, synopsis = plot, year = year, type = MediaType.MOVIE,
                languagesFound = listOf(lang),
            )
        }

        val mediaId = Text.idOf("vost", url)
        val episodes = blocks.keys.sorted().map { n ->
            Episode("$mediaId:e$n", n.toFloat(), 1, "Épisode $n", poster, null, "$url|$n")
        }
        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
            type = MediaType.ANIME, year = year,
            episodeCount = episodes.size,
            seasons = listOf(Season(1, null, episodes)),
            languages = listOf(lang),
        )
    }

    /** {numéro d'épisode → [(url du lecteur, libellé)]}. */
    private fun episodeBlocks(html: String): Map<Int, List<Pair<String, String>>> {
        val contents = HashMap<String, String>()
        Regex("""<div id="content_player_(\d+)" class="player_box">([^<]*)</div>""")
            .findAll(html).forEach { contents[it.groupValues[1]] = it.groupValues[2].trim() }

        val out = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
        Regex(
            """<div id="buttons_(\d+)" class="button_box">(.*?)(?=<div id="buttons_\d+"|</div>\s*<b class)""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html).forEach { bm ->
            val ep = bm.groupValues[1].toIntOrNull() ?: return@forEach
            Regex("""<div id="player_(\d+)" class="new_player_([a-z0-9]+)">([^<]*)</div>""")
                .findAll(bm.groupValues[2]).forEach { pm ->
                    val content = contents[pm.groupValues[1]] ?: return@forEach
                    val type = pm.groupValues[2]
                    val label = pm.groupValues[3].trim().ifBlank { type.replaceFirstChar { it.uppercase() } }
                    val target = playerUrl(type, content) ?: return@forEach
                    out.getOrPut(ep) { ArrayList() }.add(target to label)
                }
        }
        return out
    }

    /** (type de lecteur, jeton) → URL d'embed exploitable. */
    private fun playerUrl(type: String, content: String): String? {
        if (content.startsWith("http")) return content
        if (content.isBlank()) return null
        return when (type) {
            "sibnet", "netu" -> "https://video.sibnet.ru/shell.php?videoid=$content"
            "uqload" -> "https://uqload.io/embed-$content.html"
            "mytv", "myvi" -> "https://www.myvi.top/embed/$content"
            "fembed" -> "https://www.fembed.com/v/$content"
            "cloudvideo" -> "https://cloudvideo.tv/embed-$content.html"
            "uptostream" -> "https://uptostream.com/iframe/$content"
            "vidmoly" -> "https://vidmoly.org/embed-$content.html"
            else -> null
        }
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val articleUrl = payload.substringBefore('|')
        val ep = payload.substringAfter('|', "1").toIntOrNull() ?: 1
        val html = http.get(articleUrl, baseHeaders).requireOk().text
        val players = episodeBlocks(html)[ep].orEmpty()
        if (players.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur pour cet épisode")
        return players.map { (u, label) ->
            ServerEntry(
                name = label,
                url = u,
                lang = Text.audioLang(label),
                referer = mainUrl,
                direct = Regex("""https?://\S+\.(?:m3u8|mp4|webm)(\?\S*)?$""").matches(u),
            )
        }
    }
}
