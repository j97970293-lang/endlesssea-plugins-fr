package fr.endlesssea.ext.animesite

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
import fr.endlesssea.common.Json
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.urlEncode
import java.text.Normalizer

/**
 * AnimeSite (animesite.fr) — portage **natif** Endless Sea.
 *
 * Site Next.js :
 *  - listes : `GET /api/medias?name={trending|added-episode|top-rated}&page=N` ;
 *  - l'identifiant public est `tvdbId = id × 336`, le slug exact vient du sitemap ;
 *  - recherche : `GET /search/{q}` (payload RSC `self.__next_f.push`) ;
 *  - fiche : JSON-LD `TVSeries` + `containsSeason` dans le payload RSC ;
 *  - lecture : `POST /api/stream/token` → `/v/{token}` (page SibNet proxifiée)
 *    → `player.src([{src:'/v/{hash}/{id}.mp4'}])` sur video.sibnet.ru.
 */
class AnimeSiteExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://animesite.fr"
    override val providerName = "AnimeSite"
    override val extensionId = "fr.endlesssea.ext.animesite"
    override val versionCode = 15
    override val descriptionText = "Animes VF/VOSTFR, lecteurs SibNet en MP4 direct."
    override val supportedTypes = setOf(MediaType.ANIME, MediaType.MOVIE)

    override val homeRows = listOf(
        HomeRow("trending", "Tendances"),
        HomeRow("added-episode", "Nouveaux épisodes"),
        HomeRow("top-rated", "Les mieux notés"),
    )

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Referer" to referer,
        "Origin" to mainUrl,
    )

    // -----------------------------------------------------------------------
    // Accueil & recherche
    // -----------------------------------------------------------------------

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val res = http.getOrNull("$mainUrl/api/medias?name=${row.key}&page=$page", headers())
            ?: return PagedResult(emptyList(), page, false)
        val medias = res.asJsonOrNull()?.list.orEmpty()
        val items = medias.mapNotNull { toItem(it) }
        return PagedResult(items, page, medias.size >= 20)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url = "$mainUrl/search/" + q.urlEncode().replace("+", "%20")
        val html = http.getOrNull(url, headers("$mainUrl/search"))?.text ?: return emptyList()
        val flight = rscFlight(html)
        val block = Regex(""""medias":\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(flight)?.groupValues?.get(1) ?: return emptyList()
        return splitJsonObjects(block).mapNotNull { raw ->
            Json.parseOrNull(raw)?.let { toItem(it) }
        }
    }

    private suspend fun toItem(node: fr.endlesssea.common.JsonNode): SearchItem? {
        val id = node["id"].int ?: return null
        val title = node.str("title") ?: return null
        if (node["isForAdult"].bool == true) return null
        val tvdbId = id * 336
        val slug = slugFor(tvdbId, title)
        val isMovie = node.str("type") == "movie"
        return item(
            title = title,
            url = "$mainUrl/$slug",
            type = if (isMovie) MediaType.MOVIE else MediaType.ANIME,
            poster = node["image"].str("poster"),
            year = node.str("releasedAt")?.take(4)?.toIntOrNull(),
        )
    }

    /** Concatène et dés-échappe le payload RSC (`self.__next_f.push([1,"…"])`). */
    private fun rscFlight(html: String): String =
        Regex("""self\.__next_f\.push\(\[1,"((?:[^"\\]|\\.)+)"\]\)""")
            .findAll(html).joinToString("") { it.groupValues[1] }
            .replace("\\\"", "\"").replace("\\\"", "\"")
            .replace("\\\\n", "\n").replace("\\n", "\n")

    /** Découpe une suite d'objets JSON « {…},{…} » (accolades équilibrées). */
    private fun splitJsonObjects(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escape = false
        for (i in s.indices) {
            val c = s[i]
            if (escape) { escape = false; continue }
            when {
                inString && c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> { if (depth == 0) start = i; depth++ }
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) { out += s.substring(start, i + 1); start = -1 }
                }
            }
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Slugs (sitemap)
    // -----------------------------------------------------------------------

    @Volatile
    private var slugIndex: Map<Int, String>? = null

    private suspend fun slugFor(tvdbId: Int, title: String): String {
        slugIndex?.let { return it[tvdbId] ?: slugify(title) }
        val map = http.getOrNull("$mainUrl/sitemap.xml", headers())?.text?.let { xml ->
            Regex("""<loc>https?://[^<]+/(\d{5,9})-([a-z0-9-]+)</loc>""").findAll(xml)
                .associate { it.groupValues[1].toInt() to "${it.groupValues[1]}-${it.groupValues[2]}" }
        } ?: emptyMap()
        slugIndex = map
        return map[tvdbId] ?: slugify(title)
    }

    private fun slugify(title: String): String {
        val low = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).replace(Regex("[\\p{Mn}]+"), "")
        return Regex("[^a-z0-9\\s-]").replace(low, " ").trim()
            .replace(Regex("\\s+"), "-").replace(Regex("-+"), "-")
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        val idAndSlug = url.trimEnd('/').substringAfterLast('/')
        val html = http.get("$mainUrl/$idAndSlug", headers()).requireOk().text
        val flight = rscFlight(html)
        if (""""@type":"TVSeries"""" !in flight) throw SourceException.ParseError("fiche introuvable")

        val title = Regex(""""@type":"TVSeries","name":"([^"]{2,120})"""").find(flight)?.groupValues?.get(1)?.trim()
            ?: idAndSlug.substringAfter('-').replace('-', ' ')
        val poster = Regex(""""image":"(https?://[^"]+)"""").find(flight)?.groupValues?.get(1)
        val plot = Regex(""""description":"(.*?)","image"""", RegexOption.DOT_MATCHES_ALL)
            .find(flight)?.groupValues?.get(1)?.trim()
        val year = Regex(""""startDate":"(\d{4})"""").find(flight)?.groupValues?.get(1)?.toIntOrNull()
        val genres = Regex(""""genre":\[(.*?)]""", RegexOption.DOT_MATCHES_ALL).find(flight)?.groupValues?.get(1)
            ?.let { g -> Regex(""""([^"]{2,30})"""").findAll(g).map { it.groupValues[1] }.toList() }.orEmpty()

        val declared = Regex(
            """\{"@type":"TVSeason","seasonNumber":"(\d+)"(?:,"name":"[^"]*")?,"numberOfEpisodes":(\d+)"""
        ).findAll(flight).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

        val mediaId = Text.idOf("ansi", url)
        fun payload(s: Int, e: Int) = "$idAndSlug/$s/$e"

        if (declared.isEmpty() || (declared.size == 1 && declared[0].second == 1)) {
            val s = declared.firstOrNull()?.first ?: 1
            return movieDetails(
                url = url, title = title, payload = payload(s, 1), poster = poster,
                synopsis = plot, year = year, type = MediaType.MOVIE, genres = genres.take(8),
            )
        }

        val seasons = declared.map { (season, count) ->
            Season(
                season, "Saison $season",
                (1..count).map { ep ->
                    Episode("$mediaId:s${season}e$ep", ep.toFloat(), season, null, poster, null, payload(season, ep))
                },
            )
        }

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) },
            posterUrl = poster, type = MediaType.ANIME, year = year,
            genres = genres.take(8),
            episodeCount = seasons.sumOf { it.episodes.size },
            seasons = seasons,
            languages = listOf(AudioLang.VOSTFR, AudioLang.VF),
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val m = Regex("""([^/]+)/(\d+)/(\d+)""").find(payload)
            ?: throw SourceException.VideoUnavailable("charge utile invalide")
        val (idAndSlug, season, episode) = m.destructured
        val out = ArrayList<ServerEntry>()
        for (index in 0..5) {
            resolvePlayer(idAndSlug, season.toInt(), episode.toInt(), index)?.let { out += it }
        }
        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur disponible")
        return out.distinctBy { it.url }
    }

    /** `POST /api/stream/token` → `/v/{token}` (SibNet proxifié) → MP4 direct. */
    private suspend fun resolvePlayer(idAndSlug: String, season: Int, episode: Int, index: Int): ServerEntry? {
        val referer = "$mainUrl/play/$idAndSlug/$season/$episode"
        val body = """{"idAndSlugTitle":${Json.quote(idAndSlug)},"seasonNumber":$season,""" +
            """"episodeNumber":$episode,"playerIndex":$index}"""
        val tokenJson = runCatching {
            http.post("$mainUrl/api/stream/token", headers(referer), referer, json = body).text
        }.getOrElse { if (it is SourceException.CaptchaRequired) throw it else return null }
        if (""""status":"ok"""" !in tokenJson) return null

        val json = Json.parseOrNull(tokenJson) ?: return null
        val src = json.str("src") ?: return null
        when (json.str("kind") ?: "embed") {
            "external" -> return null
            "direct" -> if (src.startsWith("http")) {
                return ServerEntry("Lecteur ${index + 1}", src, AudioLang.OTHER, referer, direct = true)
            }
        }

        val vUrl = if (src.startsWith("http")) src else "$mainUrl$src"
        val page = http.getOrNull(vUrl, headers(referer))?.text ?: return null
        val playerSrc = Regex("""player\.src\(\[\{src:\s*"([^"]+)"""").find(page)?.groupValues?.get(1) ?: return null
        if (".mp4" !in playerSrc && ".m3u8" !in playerSrc) return null
        val videoUrl = when {
            playerSrc.startsWith("http") -> playerSrc
            playerSrc.startsWith("/v/") -> "https://video.sibnet.ru$playerSrc"
            else -> "$mainUrl$playerSrc"
        }
        val ogTitle = Regex("""property="og:title"\s+content="([^"]*)"""").find(page)?.groupValues?.get(1).orEmpty()
        val lang = Text.audioLang(ogTitle)
        val label = when (lang) {
            AudioLang.VOSTFR -> "SibNet · VOSTFR (${index + 1})"
            AudioLang.VF -> "SibNet · VF (${index + 1})"
            else -> "SibNet · Lecteur ${index + 1}"
        }
        return ServerEntry(label, videoUrl, lang, referer = "https://video.sibnet.ru/", direct = true)
    }
}
