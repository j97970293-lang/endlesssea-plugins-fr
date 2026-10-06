package fr.endlesssea.ext.animesama

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaStatus
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.SubtitleTrack
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsExtractor
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.Extractors
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.Http
import fr.endlesssea.common.M3u8
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.urlEncode
import java.net.URLDecoder

/**
 * Anime-Sama (anime-sama.to) — portage **natif** Endless Sea du provider CloudStream.
 *
 * Fonctionnement du site (inchangé par rapport à l'original) :
 *  - catalogue : `/catalogue/` (page unique), recherche instantanée :
 *    `POST /template-php/defaut/fetch.php` {query} ;
 *  - fiche : les saisons sont déclarées en JS par `panneauAnime("Saison 1", "saison1/vostfr")` ;
 *  - page saison : `/catalogue/{slug}/{saison}/{lang}/` charge `episodes.js`
 *    qui définit `var eps1 = [...]`, `var eps2 = [...]` (un tableau par miroir) ;
 *  - lecteurs : ansembed.net (JWPlayer), Sibnet, VidMoly, SendVid, Uqload…
 *
 * Différences d'implémentation (API Endless Sea) :
 *  - plus de `CloudflareKiller` : un défi anti-bot lève `SourceException.CaptchaRequired`,
 *    l'app ouvre sa WebView de vérification puis rejoue l'appel ;
 *  - plus de `loadExtractor` : les miroirs passent par les extracteurs natifs `:common` ;
 *  - la piste VF n'est plus une « DubStatus » mais des saisons distinctes + `AudioLang.VF`
 *    porté par chaque lien.
 */
class AnimeSamaExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://anime-sama.to"
    override val providerName = "Anime-Sama"
    override val extensionId = "fr.endlesssea.ext.animesama"
    override val versionCode = 13
    override val descriptionText =
        "Animes VF & VOSTFR : catalogue complet, saisons, films et OAV, miroirs multiples."
    override val supportedTypes = setOf(MediaType.ANIME, MediaType.MOVIE, MediaType.OVA)

    override val homeRows = listOf(
        HomeRow("nouveautes", "Nouveautés"),
        HomeRow("vostfr", "Derniers épisodes VOSTFR"),
        HomeRow("vf", "Derniers épisodes VF"),
        HomeRow("planning", "Animes du planning"),
        HomeRow("catalogue", "Catalogue complet"),
    )

    override fun extractors() = Extractors.all(http) + AnsEmbed(http)

    override val siteUrlTitle = "Adresse d'Anime-Sama"
    override val siteUrlSummary =
        "Le site change régulièrement de domaine (.to, .fr, .com…). Laissez vide pour le défaut."

    private val baseHeaders = mapOf("Accept-Language" to "fr-FR,fr;q=0.9")

    // -----------------------------------------------------------------------
    // Accueil
    // -----------------------------------------------------------------------

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (page > 1) return PagedResult(emptyList(), page, false)
        val items = when (row.key) {
            "catalogue" -> parseCatalogue(http.get("$mainUrl/catalogue/", baseHeaders).requireOk().text)
            "planning" -> recentCards(http.get("$mainUrl/planning/", baseHeaders).requireOk().text)
                .distinctBy { it.slug }
                .map { item(it.title, "$mainUrl/catalogue/${it.slug}/", MediaType.ANIME, it.poster) }
            "nouveautes" -> carousel(http.get("$mainUrl/", baseHeaders).requireOk().text)
            else -> {
                val want = if (row.key == "vf") "vf" else "vostfr"
                recentCards(http.get("$mainUrl/", baseHeaders).requireOk().text)
                    .filter { it.lang == want }
                    .distinctBy { it.slug }
                    .map { item(it.title, "$mainUrl/catalogue/${it.slug}/", MediaType.ANIME, it.poster) }
            }
        }
        return PagedResult(items, page, false)
    }

    private data class RecentCard(val slug: String, val lang: String, val title: String, val poster: String?)

    private fun recentCards(html: String): List<RecentCard> =
        Regex(
            """<a href="(?:https?://[^"]*)?/catalogue/([a-z0-9.-]+)/([a-z0-9]+)/([a-z]+)/"[^>]*>\s*<div class="card-image-container">\s*<img[^>]+class="card-image"[^>]+src="([^"]+)"[^>]*alt="([^"]*)""""
        ).findAll(html).map { m ->
            val (slug, _, lang, poster, alt) = m.destructured
            RecentCard(slug, lang, Text.decodeHtml(alt).trim().ifBlank { slug.replace('-', ' ') }, poster)
        }.toList()

    private fun carousel(html: String): List<SearchItem> {
        val out = LinkedHashMap<String, SearchItem>()
        Regex(
            """<div class="ak-slide[^"]*"[^>]*>(.*?)</div>\s*</div>\s*</div>\s*</div>""",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(html).forEach { m ->
            val seg = m.groupValues[1]
            val title = Regex("""<h2 class="ak-slide-title">([^<]+)</h2>""").find(seg)?.groupValues?.get(1)?.trim()
                ?: return@forEach
            val poster = Regex("""<div class="ak-slide-bg"><img[^>]+src="([^"]+)"""").find(seg)?.groupValues?.get(1)
            val slug = Regex("""href="(?:https?://[^"]*)?/catalogue/([a-z0-9.-]+)/[^>]*>""")
                .find(seg)?.groupValues?.get(1) ?: return@forEach
            val url = "$mainUrl/catalogue/$slug/"
            out.putIfAbsent(url, item(title, url, MediaType.ANIME, poster))
        }
        return out.values.toList()
    }

    private fun parseCatalogue(html: String): List<SearchItem> {
        val out = LinkedHashMap<String, SearchItem>()
        Regex(
            """<a\s+href="((?:https?://[^"]+)?/catalogue/([a-z0-9.-]+))/?"[^>]*>\s*<div[^>]*>\s*<img[^>]+src="([^"]+)"[^>]+alt="([^"]*)""""
        ).findAll(html).forEach { m ->
            val href = m.groupValues[1]
            val url = if (href.startsWith("http")) href else mainUrl + href
            val title = Text.decodeHtml(m.groupValues[4]).trim().ifBlank { m.groupValues[2].replace('-', ' ') }
            out.putIfAbsent(url, item(title, url, MediaType.ANIME, m.groupValues[3]))
        }
        return out.values.toList()
    }

    // -----------------------------------------------------------------------
    // Recherche
    // -----------------------------------------------------------------------

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, SearchItem>()
        // 1) recherche instantanée du site
        http.runCatchingPost(
            "$mainUrl/template-php/defaut/fetch.php",
            headers = baseHeaders + mapOf("X-Requested-With" to "XMLHttpRequest", "Origin" to mainUrl),
            referer = "$mainUrl/",
            data = mapOf("query" to q),
        )?.let { body ->
            Regex(
                """<a\s+href="([^"]+/catalogue/([a-z0-9.-]+)/?)"[^>]*class="asn-search-result"[^>]*>.*?<img[^>]+src="([^"]+)".*?<h3[^>]*>([^<]+)</h3>""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(body).forEach { m ->
                val title = Text.decodeHtml(m.groupValues[4]).trim()
                if (title.isNotBlank()) {
                    out.putIfAbsent(m.groupValues[1], item(title, m.groupValues[1], MediaType.ANIME, m.groupValues[3]))
                }
            }
        }
        // 2) repli : recherche serveur du catalogue
        if (out.isEmpty()) {
            http.getOrNull("$mainUrl/catalogue/?search=${q.urlEncode()}", baseHeaders)?.let {
                parseCatalogue(it.text).forEach { i -> out.putIfAbsent(i.url, i) }
            }
        }
        return out.values.toList()
    }

    private suspend fun Http.runCatchingPost(
        url: String,
        headers: Map<String, String>,
        referer: String,
        data: Map<String, String>,
    ): String? = runCatching { post(url, headers, referer, data = data).text }
        .getOrElse { if (it is SourceException.CaptchaRequired) throw it else null }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    private data class Pane(val name: String, val paths: List<String>, val natural: Int?, val kind: Int)

    override suspend fun details(url: String): MediaDetails {
        val slug = url.trimEnd('/').substringAfterLast('/')
        val res = http.get("$mainUrl/catalogue/$slug/", baseHeaders).requireOk()
        val doc = res.document

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: slug.replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val genres = doc.select("a[href*=/catalogue/?genre]").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        val entries = Regex("""panneauAnime\("([^"]+)",\s*"([^"]+)"\)""")
            .findAll(res.text)
            .map { it.groupValues[1] to it.groupValues[2] }
            .filter { it.first != "nom" && it.second != "url" }
            .toList()
        if (entries.isEmpty()) throw SourceException.ParseError("aucune saison sur la fiche")

        val panes = entries.groupBy({ it.first }, { it.second })
            .map { (name, paths) ->
                val p = paths.distinct()
                val isKai = p.any { it.startsWith("kai") } || Regex("""\bkai\b""", RegexOption.IGNORE_CASE).containsMatchIn(name)
                val isHs = p.any { Regex("""saison\d+hs""").containsMatchIn(it) }
                val isFilm = name.contains("film", true) || p.any { it.startsWith("film") }
                val isOav = name.contains("oav", true) || p.any { it.startsWith("oav") }
                val natural = Regex("""(?:saison|season|saga|kai)\s*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: p.mapNotNull { x -> Regex("""(?:saison|kai)(\d+)""").find(x)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull()
                Pane(name, p, natural, when { isKai -> 4; isHs -> 3; isOav -> 2; isFilm -> 1; else -> 0 })
            }
            .sortedWith(compareBy({ it.kind }, { it.natural ?: 9999 }, { it.name }))

        // Numérotation de saison unique et lisible (saisons naturelles d'abord,
        // puis films / OAV / hors-séries / Kai à la suite).
        val used = HashSet<Int>()
        fun free(base: Int): Int {
            var n = base
            while (!used.add(n)) n++
            return n
        }
        var nextSpecial = (panes.filter { it.kind == 0 && it.natural != null }.maxOfOrNull { it.natural!! } ?: 0) + 1
        val numbered = panes.map { p ->
            val n = if (p.kind == 0 && p.natural != null) free(p.natural) else free(nextSpecial).also { nextSpecial = it + 1 }
            n to p
        }.sortedBy { it.first }

        val mediaId = Text.idOf("anis", url)
        val seasons = ArrayList<Season>()
        val langs = LinkedHashSet<AudioLang>()

        for ((number, pane) in numbered) {
            for (path in pane.paths) {
                val isVf = path.trimEnd('/').endsWith("/vf")
                langs += if (isVf) AudioLang.VF else AudioLang.VOSTFR
                val count = countEpisodes("$mainUrl/catalogue/$slug/$path/") ?: continue
                if (count <= 0) continue
                val suffix = if (isVf) "VF" else "VOSTFR"
                val eps = (1..count).map { ep ->
                    Episode(
                        id = "$mediaId:s${number}$suffix:e$ep",
                        number = ep.toFloat(),
                        season = number,
                        title = "${pane.name} · Épisode $ep ($suffix)",
                        thumbnailUrl = poster,
                        data = payload(slug, path, ep),
                    )
                }
                seasons += Season(number, "${pane.name} · $suffix", eps)
            }
        }
        if (seasons.isEmpty()) throw SourceException.ParseError("aucun épisode listé")

        val isMovie = numbered.size == 1 && numbered[0].second.kind == 1

        return MediaDetails(
            id = mediaId,
            url = url,
            title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) },
            posterUrl = poster,
            type = if (isMovie) MediaType.MOVIE else MediaType.ANIME,
            year = Text.year(plot),
            status = MediaStatus.ONGOING,
            genres = genres,
            episodeCount = seasons.sumOf { it.episodes.size },
            seasons = seasons,
            languages = langs.toList(),
        )
    }

    private fun payload(slug: String, path: String, ep: Int) = "${slug}|${path}|$ep"

    /** Nombre d'épisodes d'une page saison (`episodes.js`), avec quelques tentatives. */
    private suspend fun countEpisodes(seasonUrl: String): Int? {
        repeat(3) {
            val html = http.getOrNull(seasonUrl, baseHeaders)?.text
            if (html != null) {
                val arrays = epsArrays(seasonUrl, html)
                if (arrays.isNotEmpty()) return arrays.maxOf { it.value.size }
                if (!Regex("""src=['"][^'"]*episodes\.js""").containsMatchIn(html)) return 0
            }
        }
        return null
    }

    /** `episodes.js` d'une page saison → { eps1: [urls], eps2: [urls]… }. */
    private suspend fun epsArrays(seasonUrl: String, html: String): Map<String, List<String>> {
        val src = Regex("""src=['"]([^'"]*episodes\.js[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            ?: return emptyMap()
        val jsUrl = when {
            src.startsWith("http") -> src
            src.startsWith("/") -> mainUrl + src
            else -> seasonUrl.trimEnd('/') + "/" + src
        }
        val js = http.getOrNull(jsUrl, baseHeaders, referer = seasonUrl)?.text ?: return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        Regex("""var\s+(eps\d+)\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).findAll(js).forEach { m ->
            val urls = Regex("""['"]([^'"]+)['"]""").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            if (urls.isNotEmpty()) out[m.groupValues[1]] = urls
        }
        return out
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        if (parts.size < 3) return emptyList()
        val (slug, path) = parts
        val ep = parts[2].toIntOrNull() ?: 1
        val lang = if (path.trimEnd('/').endsWith("/vf")) AudioLang.VF else AudioLang.VOSTFR

        val seasonUrl = "$mainUrl/catalogue/$slug/${path.trim('/')}/"
        val html = http.get(seasonUrl, baseHeaders).requireOk().text
        val arrays = epsArrays(seasonUrl, html)
        if (arrays.isEmpty()) throw SourceException.VideoUnavailable("episodes.js introuvable")

        return arrays.entries
            .sortedBy { it.key.removePrefix("eps").toIntOrNull() ?: 0 }
            .mapNotNull { (_, urls) -> urls.getOrNull(ep - 1) }
            .filter { it.startsWith("http") }
            .distinct()
            .map { ServerEntry(label(it), it, lang, referer = mainUrl) }
    }

    private fun label(url: String): String {
        val u = url.lowercase()
        return when {
            "ansembed" in u -> "AnsEmbed"
            "sibnet" in u -> "Sibnet"
            "vidmoly" in u -> "VidMoly"
            "sendvid" in u -> "SendVid"
            "uqload" in u -> "Uqload"
            "filemoon" in u -> "FileMoon"
            "dood" in u -> "Dood"
            "streamtape" in u -> "Streamtape"
            "voe" in u -> "Voe"
            else -> Text.host(url).removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    /** Lecteur de secours : certains miroirs sont déjà des flux directs. */
    override suspend fun loadLinks(data: dev.endlesssea.extensions.api.model.LinkRequest): List<VideoLink> {
        val entries = servers(data.episode.data).map {
            if (Regex("""https?://\S+\.(?:m3u8|mp4|webm)(\?\S*)?$""").matches(it.url)) it.copy(direct = true) else it
        }
        return resolveServers(entries, data.preferredServer)
    }
}

/** ansembed.net — clone VidMoly (JWPlayer) : `sources: [{ file: '…/master.m3u8' }]`. */
class AnsEmbed(http: Http) : EsExtractor(http) {
    override val name = "AnsEmbed"
    override val mainUrl = "https://ansembed.net"
    override val hosts = listOf("ansembed.net", "ansembed.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(
            url,
            headers = mapOf("Sec-Fetch-Dest" to "iframe"),
            referer = referer ?: "https://anime-sama.to/",
        )?.text ?: return emptyList()
        val file = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: return genericSniff(html, url)
        return M3u8.variants(http, file, name, referer = url).onEach(callback)
    }
}

/** Décodage des paramètres d'une ancienne charge utile `?slug=…&p=…&n=…`. */
internal fun legacyPayload(data: String): String {
    val q = data.substringAfter("?", "")
    if (q.isBlank()) return data
    val map = Regex("""(?:^|&)([a-z]+)=([^&]*)""").findAll(q).associate {
        it.groupValues[1] to runCatching { URLDecoder.decode(it.groupValues[2], "UTF-8") }.getOrDefault(it.groupValues[2])
    }
    val slug = map["slug"] ?: return data
    return "$slug|${map["p"].orEmpty()}|${map["n"] ?: "1"}"
}
