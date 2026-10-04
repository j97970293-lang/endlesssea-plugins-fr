package fr.endlesssea.ext.cinestream

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.urlEncode

/**
 * CineStream (cinestream.info) — portage **natif** Endless Sea.
 *
 *  - listes paginées : `/film-en-streaming/{n}`, `/films-ajoutes-recemment/{n}`,
 *    `/films-populaires/{n}`, `/films/{Genre}/{n}` ;
 *  - recherche SSR : `/search?q=…` ;
 *  - fiche : `/film/{slug}` → boutons lecteurs + `tmdbid` dans le payload RSC ;
 *  - lecteurs : `/player/{tmdbid}/{index}` → `<iframe src="URL hébergeur">`,
 *    résolus par les extracteurs natifs (Vidara, Voe, Uqload, VidMoly…) ;
 *  - filet de secours : agrégateur vidsrc.buzz par identifiant TMDB.
 */
class CineStreamExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://cinestream.info"
    override val providerName = "CineStream"
    override val extensionId = "fr.endlesssea.ext.cinestream"
    override val versionCode = 6
    override val descriptionText = "Films VF/VOSTFR, une quinzaine de lecteurs par titre."
    override val supportedTypes = setOf(MediaType.MOVIE)

    override val homeRows = listOf(
        HomeRow("films", "Films (derniers)", "/film-en-streaming"),
        HomeRow("recents", "Ajoutés récemment", "/films-ajoutes-recemment"),
        HomeRow("populaires", "Films populaires", "/films-populaires"),
        HomeRow("action", "Action", "/films/Action"),
        HomeRow("animation", "Animation", "/films/Animation"),
        HomeRow("aventure", "Aventure", "/films/Aventure"),
        HomeRow("comedie", "Comédie", "/films/Com%C3%A9die"),
        HomeRow("sf", "Science-Fiction", "/films/Science-Fiction"),
        HomeRow("horreur", "Horreur", "/films/Horreur"),
        HomeRow("thriller", "Thriller", "/films/Thriller"),
    )

    private val baseHeaders = mapOf("Accept-Language" to "fr-FR,fr;q=0.9")

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val html = http.getOrNull("$mainUrl${row.path}/$page", baseHeaders)?.text
            ?: return PagedResult(emptyList(), page, false)
        val items = parseCards(html)
        return PagedResult(items, page, items.size >= 20)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val html = http.getOrNull("$mainUrl/search?q=${query.urlEncode()}", baseHeaders)?.text
            ?: return emptyList()
        return parseCards(html)
    }

    /** `<a href="/film/{slug}"><img alt="Affiche du film {titre} en streaming…" src="https://image.tmdb.org/…">`. */
    private fun parseCards(html: String): List<SearchItem> {
        val out = LinkedHashMap<String, SearchItem>()
        Regex(
            """<a href="(/film/([a-z0-9-]+))">\s*<div[^>]*>\s*<img[^>]+alt="([^"]*)"[^>]*src="(https://image\.tmdb\.org/t/p/[^"]+)"""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html).forEach { m ->
            val url = mainUrl + m.groupValues[1]
            val alt = m.groupValues[3]
            val title = alt.substringAfter("Affiche du film ", alt).substringBefore(" en streaming").trim()
                .ifBlank { m.groupValues[2].replace('-', ' ') }
            if (title.isNotBlank()) out.putIfAbsent(url, item(title, url, MediaType.MOVIE, m.groupValues[4]))
        }
        return out.values.toList()
    }

    override suspend fun load(url: String): MediaDetails {
        val html = http.get(url, baseHeaders).requireOk().text
        val ogTitle = Regex("""property="og:title" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: throw SourceException.ParseError("fiche illisible")
        var title = ogTitle.removePrefix("Film ")
            .replace(Regex("""\s+en\s+Streaming\s*$""", RegexOption.IGNORE_CASE), "").trim()
        val year = Regex("""\b((?:19|20)\d{2})\s*$""").find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (year != null) title = title.removeSuffix(year.toString()).trim().trimEnd('(', ')').trim()

        val poster = Regex("""property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""Synopsis du film</h3>\s*<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.let { Text.stripHtml(it) }
        val tmdbId = Regex("""tmdbid[\\"]*:+(\d+)""").find(html)?.groupValues?.get(1)

        val players = playerLabels(html)
        return movieDetails(
            url = url,
            title = title,
            payload = "$url|${tmdbId.orEmpty()}",
            poster = poster,
            synopsis = plot,
            year = year,
            type = MediaType.MOVIE,
            servers = players.mapIndexed { i, l -> dev.endlesssea.extensions.api.model.ServerRef("p$i", l) },
        ).copy(externalIds = tmdbId?.let { mapOf("tmdb" to it) } ?: emptyMap())
    }

    private fun playerLabels(html: String): List<String> =
        Regex("""<button id="([^"]+)" aria-label="Lecteur""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

    override suspend fun servers(payload: String): List<ServerEntry> {
        val ficheUrl = payload.substringBefore('|')
        val html = http.get(ficheUrl, baseHeaders).requireOk().text
        val tmdbId = payload.substringAfter('|', "").ifBlank {
            Regex("""tmdbid[\\"]*:+(\d+)""").find(html)?.groupValues?.get(1).orEmpty()
        }
        val labels = playerLabels(html)
        if (labels.isEmpty() || tmdbId.isBlank()) return emptyList()

        return labels.mapIndexedNotNull { index, label ->
            val page = http.getOrNull("$mainUrl/player/$tmdbId/$index", baseHeaders, referer = ficheUrl)?.text
                ?: return@mapIndexedNotNull null
            val embed = Regex("""<iframe[^>]*src="([^"]+)"""").find(page)?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            if (!embed.startsWith("http")) return@mapIndexedNotNull null
            ServerEntry(
                name = label,
                url = embed,
                lang = if (label.contains("vostfr", true)) AudioLang.VOSTFR else AudioLang.VF,
                referer = ficheUrl,
            )
        }
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val payload = data.episode.data
        val entries = runCatching { servers(payload) }.getOrDefault(emptyList())
        val links = if (entries.isEmpty()) emptyList()
        else runCatching { resolveServers(entries, data.preferredServer) }.getOrDefault(emptyList())

        // Secours : agrégateur TMDB
        val tmdbId = payload.substringAfter('|', "")
        val fallback = if (links.isEmpty() && tmdbId.isNotBlank()) {
            runCatching { VidSrcBuzz.links(http, tmdbId) }.getOrDefault(emptyList())
        } else emptyList()

        val all = links + fallback
        if (all.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur exploitable")
        return all
    }
}
