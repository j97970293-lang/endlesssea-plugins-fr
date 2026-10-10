package fr.endlesssea.ext.frenchstream

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
import fr.endlesssea.common.Json
import fr.endlesssea.common.JsonNode
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.urlEncode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * French Stream (fs01.lol, ex fs27.lol) — portage **natif** Endless Sea.
 *
 * DataLife Engine :
 *  - listes `/films/`, `/series/` (+ `/page/N/`), recherche en POST sur `/index.php` ;
 *  - fiche `/index.php?newsid=X` ;
 *  - film : `/engine/ajax/film_api.php?id=X` → `{players:{nom:{vostfr,vff,vfq,default}}}` ;
 *  - série : `/static/series/{id}.js` → `{vf:{"1":{lecteur:url}}, vostfr:{…}, vo:{…}}` ;
 *  - hébergeurs : fsvid.lol et vidzy.cc (videojs XOR, géré par le renifleur natif),
 *    Vidara, Uqload, Dood, Voe, Netu.
 */
class FrenchStreamExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://fs01.lol"
    override val providerName = "French Stream"
    override val extensionId = "fr.endlesssea.ext.frenchstream"
    override val versionCode = 18
    override val descriptionText = "Films & séries VF/VOSTFR, multi-lecteurs."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    override val homeRows = listOf(
        HomeRow("films", "Films (derniers ajouts)", "/films/"),
        HomeRow("films-action", "Films · Action", "/films/actions/"),
        HomeRow("films-comedie", "Films · Comédie", "/films/comedies/"),
        HomeRow("films-animation", "Films · Animation", "/films/animations/"),
        HomeRow("films-horreur", "Films · Horreur", "/films/epouvante-horreurs/"),
        HomeRow("films-sf", "Films · Science-Fiction", "/films/science-fictions/"),
        HomeRow("films-thriller", "Films · Thriller", "/films/thrillers/"),
        HomeRow("series", "Séries (derniers ajouts)", "/series/"),
        HomeRow("animes", "Animes (séries d'animation)", "/animation-serie-//"),
        HomeRow("k-drama", "K-Dramas", "/k-drama-//"),
        HomeRow("netflix", "Séries Netflix", "/s-tv/netflix-series-/"),
    )

    private val baseHeaders = mapOf("Accept-Language" to "fr-FR,fr;q=0.9")

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val url = if (page <= 1) mainUrl + row.path else mainUrl + row.path.trimEnd('/') + "/page/$page/"
        val html = http.getOrNull(url, baseHeaders)?.text ?: return PagedResult(emptyList(), page, false)
        val items = parseCards(html)
        return PagedResult(items, page, items.size >= 15)
    }

    /** La recherche DLE de ce site n'est fiable qu'en POST (le GET renvoie le catalogue). */
    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val html = runCatching {
            http.post(
                "$mainUrl/index.php",
                headers = baseHeaders,
                referer = "$mainUrl/",
                data = mapOf(
                    "do" to "search", "subaction" to "search", "story" to query,
                    "search_start" to "0", "full_search" to "0", "result_from" to "1",
                ),
            ).text
        }.getOrElse { if (it is SourceException.CaptchaRequired) throw it else return emptyList() }
        return parseCards(html)
    }

    private fun parseCards(html: String): List<SearchItem> {
        val out = LinkedHashMap<String, SearchItem>()
        Regex("""<a\s+class="short-poster[^"]*"\s+href="([^"]+newsid=(\d+))"[^>]*alt="([^"]*)"""")
            .findAll(html).forEach { m ->
                val id = m.groupValues[2]
                val title = m.groupValues[3].trim()
                if (title.isBlank()) return@forEach
                val after = html.substring(m.range.last, minOf(html.length, m.range.last + 700))
                val poster = Regex("""<img[^>]+src="([^"]+)"""").find(after)?.groupValues?.get(1)
                val url = "$mainUrl/index.php?newsid=$id"
                val isSeries = m.groupValues[1].contains("/series") || title.contains("Saison", true)
                out.putIfAbsent(url, item(title, url, if (isSeries) MediaType.SERIES else MediaType.MOVIE, poster))
            }
        return out.values.toList()
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        val newsId = Regex("""newsid=(\d+)""").find(url)?.groupValues?.get(1)
            ?: throw SourceException.ParseError("URL French Stream invalide")
        val res = http.get("$mainUrl/index.php?newsid=$newsId", baseHeaders).requireOk()
        val doc = res.document
        val html = res.text

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.replace(Regex("\\s*en streaming complet.*$", RegexOption.IGNORE_CASE), "")
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Fiche $newsId"
        val poster = Regex("""data-affiche="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val backdrop = Regex("""data-affiche2="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Text.year(doc.selectFirst(".facts")?.text() ?: html.take(40_000))

        val isSeries = html.contains("id=\"serie-data\"") || html.contains("id=\"serie-config\"") ||
            title.contains("saison", true)

        if (!isSeries) {
            if (filmPlayers(newsId).isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur pour ce film")
            return movieDetails(
                url = url, title = title, payload = "film|$newsId", poster = poster,
                banner = backdrop, synopsis = plot, year = year,
            )
        }

        val seriesJson = seriesData(newsId) ?: throw SourceException.ParseError("données de série illisibles")
        val mediaId = Text.idOf("fstr", url)
        val episodes = ArrayList<Episode>()
        val langs = LinkedHashSet<AudioLang>()
        listOf("vostfr" to AudioLang.VOSTFR, "vf" to AudioLang.VF, "vo" to AudioLang.VO).forEach { (key, lang) ->
            val node = seriesJson[key]
            node.keys.sortedBy { it.toIntOrNull() ?: 0 }.forEach { epKey ->
                val n = epKey.toIntOrNull() ?: return@forEach
                if (node[epKey].keys.isEmpty()) return@forEach
                langs += lang
                episodes += Episode(
                    id = "$mediaId:$key:e$n",
                    number = n.toFloat(),
                    season = 1,
                    title = "Épisode $n (${key.uppercase()})",
                    thumbnailUrl = poster,
                    data = "serie|$newsId|$key|$n",
                )
            }
        }
        if (episodes.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode disponible")

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster, bannerUrl = backdrop,
            type = MediaType.SERIES, year = year,
            episodeCount = episodes.size,
            seasons = listOf(Season(1, null, episodes.sortedBy { it.number })),
            languages = langs.toList(),
        )
    }

    /** `/engine/ajax/film_api.php?id=X` → lecteurs par langue. */
    private suspend fun filmPlayers(newsId: String): List<ServerEntry> {
        val json = http.getOrNull(
            "$mainUrl/engine/ajax/film_api.php?id=$newsId",
            baseHeaders + mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = "$mainUrl/index.php?newsid=$newsId",
        )?.text ?: return emptyList()
        val root = Json.parseOrNull(json) ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        val players = root["players"]
        players.keys.forEach { player ->
            val langs = players[player]
            langs.keys.forEach { lang ->
                val u = langs[lang].string ?: return@forEach
                if (!u.startsWith("http")) return@forEach
                val label = when (lang) {
                    "vostfr" -> "VOSTFR"
                    "vff", "vf" -> "VF"
                    "vfq" -> "VFQ"
                    "vo" -> "VO"
                    "default" -> null
                    else -> lang.uppercase().take(8)
                }
                out += ServerEntry(
                    name = playerLabel(player, u) + (label?.let { " · $it" } ?: ""),
                    url = u,
                    lang = Text.audioLang(label ?: ""),
                    referer = mainUrl,
                )
            }
        }
        return out.distinctBy { it.url }
    }

    private suspend fun seriesData(newsId: String): JsonNode? =
        http.getOrNull("$mainUrl/static/series/$newsId.js", baseHeaders)?.asJsonOrNull()

    private fun playerLabel(player: String, url: String): String {
        val p = player.lowercase()
        return when {
            p == "premium" -> "FS Premium"
            p == "filmoon" || "vidara" in url -> "FileMoon"
            p.isNotBlank() -> player.replaceFirstChar { it.uppercase() }
            else -> Text.host(url).removePrefix("www.")
        }
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        return when (parts.getOrNull(0)) {
            "film" -> filmPlayers(parts[1])
            "serie" -> {
                val root = seriesData(parts[1]) ?: return emptyList()
                val langKey = parts.getOrNull(2) ?: "vostfr"
                val ep = parts.getOrNull(3) ?: "1"
                val node = root[langKey][ep]
                node.keys.mapNotNull { player ->
                    val u = node[player].string ?: return@mapNotNull null
                    if (!u.startsWith("http")) return@mapNotNull null
                    ServerEntry(
                        name = "${playerLabel(player, u)} · ${langKey.uppercase()}",
                        url = u,
                        lang = Text.audioLang(langKey),
                        referer = mainUrl,
                    )
                }
            }
            else -> emptyList()
        }
    }

    override fun linkStream(data: LinkRequest): Flow<VideoLink> = flow {
        val payload = data.episode.data
        emitAll(
            firstNonEmpty(
                { resolveServersFlow(servers(payload), data.preferredServer) },
                { linksBlocking { movieFallback(payload) } },
            )
        )
    }

    /** Secours films : titre → identifiant IMDb → agrégateur vidsrc.buzz. */
    private suspend fun movieFallback(payload: String): List<VideoLink> {
        if (!payload.startsWith("film|")) return emptyList()
        val newsId = payload.substringAfter('|')
        val html = http.getOrNull("$mainUrl/index.php?newsid=$newsId", baseHeaders)?.text
        val title = html?.let {
            Regex("""<meta property="og:title" content="([^"]*)"""").find(it)?.groupValues?.get(1)?.trim()
                ?: Regex("""<h1[^>]*>([^<]+)""").find(it)?.groupValues?.get(1)?.trim()
        }
        val imdb = title?.let { imdbIdFor(it) } ?: return emptyList()
        return runCatching { VidSrcBuzz.links(http, imdb) }.getOrDefault(emptyList())
    }

    /** Titre → identifiant IMDb via l'API de suggestion publique. */
    private suspend fun imdbIdFor(title: String): String? {
        val plain = title.replace(Regex("""[^\p{L}\p{N}\s]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (plain.isEmpty()) return null
        val first = plain.first().lowercaseChar()
        if (!first.isLetterOrDigit()) return null
        val url = "https://v2.sg.media-imdb.com/suggestion/$first/${plain.urlEncode().replace("+", "%20")}.json"
        val body = http.getOrNull(url, mapOf("Accept" to "application/json"))?.text ?: return null
        return Json.parseOrNull(body)?.get("d")?.list?.firstNotNullOfOrNull { entry ->
            entry.str("id")?.takeIf { it.startsWith("tt") }
        }
    }
}
