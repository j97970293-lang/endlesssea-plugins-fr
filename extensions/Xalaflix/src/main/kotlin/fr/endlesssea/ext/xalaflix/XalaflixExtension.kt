package fr.endlesssea.ext.xalaflix

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
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.Tmdb
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz

/**
 * Xalaflix — portage **natif** Endless Sea.
 *
 *  - domaine : résolu dynamiquement via la page d'annonce `xalaflix.online`
 *    (liens `xalaflix.*`), validé sur la présence de cartes `/movie|tv-show/` ;
 *  - listes : `/trending`, `/movies?page=N`, `/tv-shows?page=N`, `/top-imdb` ;
 *  - recherche : `/search/{q}` (⚠ encodage de **chemin** : les `+` d'un
 *    encodage de formulaire donnent zéro résultat) ;
 *  - séries : épisodes de la saison rendue + les autres saisons récupérées par
 *    **Livewire** (`POST /livewire/update`, méthode `updateSeason`) ;
 *  - lecture : tableau JS `videos = [{server_name,label,version,link}]`,
 *    replis iframes, puis agrégateurs indexés TMDB (réseau Movix, Frembed,
 *    lecteurs publics, vidsrc.buzz). L'id TMDB vient des liens du site ou,
 *    à défaut, d'une recherche TMDB sur le titre.
 *
 * Charge utile : `{url de la page}|{movie|tv}|{titre}`.
 */
class XalaflixExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://xalaflix.tax"
    private val registryUrl = "https://xalaflix.online/"

    override val mainUrl get() = userUrl ?: resolved ?: defaultUrl
    override val providerName = "Xalaflix"
    override val extensionId = "fr.endlesssea.ext.xalaflix"
    override val versionCode = 16
    override val descriptionText = "Films et séries VF/VOSTFR, serveurs du site et agrégateurs FR."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    @Volatile private var resolved: String? = null

    /** ⚙ adresse courante : annonce officielle → défaut, validée sur l'accueil. */
    private suspend fun ensureDomain(): String {
        userUrl?.let { return it }          // adresse imposée par l'utilisateur
        resolved?.let { return it }
        val candidates = ArrayList<String>()
        http.getOrNull(registryUrl)?.text?.let { page ->
            Regex("""https?://xalaflix\.[a-z]{2,6}/?""").findAll(page)
                .map { it.value.trimEnd('/') }.toCollection(candidates)
        }
        candidates += defaultUrl
        for (c in candidates.distinct()) {
            val home = http.getOrNull(c)?.text ?: continue
            if (Regex("""/(?:movie|tv-show)/[a-z0-9-]+""").containsMatchIn(home)) {
                resolved = c
                return c
            }
        }
        resolved = defaultUrl
        return defaultUrl
    }

    override val homeRows = listOf(
        HomeRow("trending", "🔥 Tendances", "trending"),
        HomeRow("movies", "🎬 Films", "movies"),
        HomeRow("tv-shows", "📺 Séries", "tv-shows"),
        HomeRow("top-imdb", "⭐ Top IMDb", "top-imdb"),
    )

    /** Cartes communes aux listes et à la recherche. */
    private fun parseCards(html: String, base: String): List<SearchItem> =
        Regex("""<a href="(?:https?://[^"]*?)/(movie|tv-show)/([a-z0-9-]+)"[\s\S]{0,400}?<img[^>]+data-src="([^"]+)"[^>]*alt="([^"]*)"""")
            .findAll(html).mapNotNull { m ->
                val (kind, slug, img, title) = m.destructured
                val t = Text.decodeHtml(title).trim().ifBlank { slug.replace('-', ' ') }
                item(
                    t, "$base/$kind/$slug",
                    if (kind == "tv-show") MediaType.SERIES else MediaType.MOVIE,
                    img, Text.year(t),
                )
            }.distinctBy { it.url }.toList()

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val base = ensureDomain()
        val url = when (row.path) {
            "trending", "top-imdb" -> "$base/${row.path}"
            else -> "$base/${row.path}?page=$page"
        }
        val html = http.getOrNull(url)?.text ?: return PagedResult(emptyList(), page, false)
        val items = parseCards(html, base)
        val hasNext = row.path !in setOf("trending", "top-imdb") && items.size >= 20
        return PagedResult(items, page, hasNext)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val base = ensureDomain()
        // encodage de chemin : « %20 » et non « + »
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        if (q.isEmpty()) return emptyList()
        val html = http.getOrNull("$base/search/$q")?.text ?: return emptyList()
        return parseCards(html, base)
    }

    /** Snapshot JSON (déséchappé) d'un composant Livewire. */
    private fun extractSnapshot(html: String, componentName: String): String? {
        Regex("""wire:snapshot="([^"]*)"""").findAll(html).forEach { m ->
            val snap = m.groupValues[1]
                .replace("&quot;", "\"").replace("&amp;", "&").replace("&#039;", "'")
            if (""""name":"$componentName"""" in snap) return snap
        }
        return null
    }

    override suspend fun load(url: String): MediaDetails {
        val base = ensureDomain()
        val html = http.get(url).verifyNotBlocked().requireOk().text
        val slug = url.trimEnd('/').substringAfterLast('/')
        val isSeries = "/tv-show/" in url

        val title = Regex("""<title>([^<]*)</title>""").find(html)?.groupValues?.get(1)
            ?.replace(Regex("""\s+(?:Streaming|streaming|Stream|stream).*$"""), "")
            ?.replace(Regex("""\s+(?:Complet|complet|VF/VOSTFR|VF|VOSTFR).*$"""), "")
            ?.trim()?.takeIf { it.isNotBlank() } ?: slug.replace('-', ' ')
        val poster = Regex("""<meta property="og:image" content="([^"]+)"""").find(html)?.groupValues?.get(1)
        val plot = Regex("""<meta name="description" content="([^"]*)"""").find(html)?.groupValues?.get(1)
        val year = Text.year(title)

        if (!isSeries) {
            return movieDetails(
                url = url, title = title, payload = "$url|movie|$title",
                poster = poster, synopsis = plot, year = year,
            )
        }

        val mediaId = Text.idOf("xala", url)
        val found = LinkedHashMap<String, Episode>()
        fun parseEpisodeCards(block: String) {
            Regex("""<a href="(?:https?://[^"]*?)/episode/([a-z0-9-]+)/(\d+)-(\d+)"""")
                .findAll(block).forEach { m ->
                    val s = m.groupValues[2].toInt()
                    val e = m.groupValues[3].toInt()
                    val epUrl = "$base/episode/${m.groupValues[1]}/$s-$e"
                    found.putIfAbsent(
                        epUrl,
                        Episode(
                            id = "$mediaId:s${s}e$e", number = e.toFloat(), season = s,
                            title = "Épisode $e", thumbnailUrl = poster,
                            data = "$epUrl|tv|$title",
                        ),
                    )
                }
        }
        parseEpisodeCards(html) // saison rendue par défaut

        // Autres saisons : wire:click="updateSeason('id')"
        val seasonIds = Regex("""updateSeason\('(\d+)'\)""").findAll(html)
            .map { it.groupValues[1] }.distinct().toList()
        if (seasonIds.size > 1 || (seasonIds.isNotEmpty() && found.isEmpty())) {
            extractSnapshot(html, "season-component")?.let { snapshot ->
                for (sid in seasonIds) {
                    val body = """{"components":[{"snapshot":${Json.quote(snapshot)},"updates":{},""" +
                        """"calls":[{"method":"updateSeason","params":["$sid"]}]}]}"""
                    val resp = runCatching {
                            http.post(
                                "$base/livewire/update",
                                headers = mapOf("X-Livewire" to "true"),
                                referer = url,
                                json = body,
                            ).text
                        }.getOrNull() ?: continue
                    val block = Json.parseOrNull(resp)?.get("components")?.list
                        ?.firstOrNull()?.get("effects")?.str("html") ?: continue
                    parseEpisodeCards(block)
                }
            }
        }
        if (found.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode trouvé")

        val seasons = found.values.groupBy { it.season ?: 1 }.toSortedMap().map { (s, eps) ->
            Season(s, "Saison $s", eps.sortedBy { it.number })
        }
        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
            type = MediaType.SERIES, year = year,
            episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        ensureDomain()
        val pageUrl = payload.substringBefore('|')
        val fragType = payload.split('|').getOrNull(1)
        val fragTitle = payload.split('|').getOrNull(2)
        val html = http.getOrNull(pageUrl)?.text

        val parts = pageUrl.substringAfter("://", "").substringAfter('/', "").trim('/').split("/")
        val isTvEpisode = parts.firstOrNull() == "episode"
        val isTv = fragType == "tv" || parts.firstOrNull() == "tv-show" || isTvEpisode
        val se = parts.getOrNull(2)?.split("-")
        val season = se?.getOrNull(0)?.toIntOrNull()
        val episode = se?.getOrNull(1)?.toIntOrNull()

        val out = LinkedHashMap<String, ServerEntry>()

        // 1) lecteurs du site : const videos = [{server_name,label,version,link}]
        if (html != null) {
            val videosJson = Regex("""(?:const|var|let)\s+videos\s*=\s*(\[[\s\S]*?]);""")
                .find(html)?.groupValues?.get(1)
            Json.parseOrNull(videosJson)?.list.orEmpty().forEach { v ->
                val link = v.str("link")?.takeIf { it.startsWith("http") } ?: return@forEach
                val label = listOfNotNull(v.str("server_name"), v.str("label"), v.str("version"))
                    .distinct().joinToString(" · ").ifBlank { Text.host(link) }
                out.putIfAbsent(link, ServerEntry(label, link, Text.audioLang(label), referer = pageUrl))
            }
            if (out.isEmpty()) {
                Regex("""<iframe[^>]*\ssrc="(https?://[^"]+)"""").findAll(html).forEach { m ->
                    val u = m.groupValues[1]
                    if ("xalaflix." in u || "google" in u || "facebook" in u) return@forEach
                    out.putIfAbsent(u, ServerEntry(Text.host(u), u, referer = pageUrl))
                }
            }
        }

        // 2) agrégateurs TMDB (fonctionnent même si la page est injoignable)
        val title = fragTitle?.takeIf { it.isNotBlank() }
            ?: Regex("""<title>([^<]*)</title>""").find(html.orEmpty())?.groupValues?.get(1)?.trim()
        val tmdb = Regex("""[?&]tmdb=(\d+)""").find(html.orEmpty())?.groupValues?.get(1)
            ?: title?.let { Tmdb.idFromTitle(http, it, isTv) }

        if (tmdb != null) {
            runCatching { Aggregators.movixNetwork(http, "https://api.movix.men", tmdb, isTv, season, episode) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
            runCatching { Aggregators.frembedNetwork(http, tmdb, isTv, season, episode) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
            TmdbEmbeds.publicEmbeds(tmdb, season, episode).forEach { out.putIfAbsent(it.url, it) }
        }

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
        if (out.isEmpty()) {
            // dernier recours : vidsrc.buzz par TMDB, puis par IMDb
            val parts = payload.substringBefore('|').substringAfter("://", "")
                .substringAfter('/', "").trim('/').split("/")
            val isTv = payload.split('|').getOrNull(1) == "tv" || parts.firstOrNull() == "episode"
            val se = parts.getOrNull(2)?.split("-")
            val title = payload.split('|').getOrNull(2)
            val tmdb = title?.let { Tmdb.idFromTitle(http, it, isTv) }
            if (tmdb != null) {
                runCatching { VidSrcBuzz.links(http, tmdb, se?.getOrNull(0)?.toIntOrNull(), se?.getOrNull(1)?.toIntOrNull()) }
                    .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
            }
        }
        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun serveur exploitable")
        return out.values.sortedByDescending { it.quality.pixels }
    }
}
