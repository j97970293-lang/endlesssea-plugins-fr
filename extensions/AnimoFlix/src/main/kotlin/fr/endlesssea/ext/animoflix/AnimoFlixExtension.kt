package fr.endlesssea.ext.animoflix

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaStatus
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * AnimoFlix (animoflix.to) — portage **natif** Endless Sea.
 *
 *  - accueil : sections « derniers épisodes VOSTFR / VF », « derniers ajouts »
 *    de la page d'accueil, et `/catalogue/?page=N` ;
 *  - recherche : `/catalogue/?search=…&ajax=1` → `{cards:"<html>"}`, repli sur
 *    `/search-autocomplete.php?q=…` (qui matche aussi les titres alternatifs) ;
 *  - fiche : pages de saison `/anime/{slug}/{saison}/{vf|vostfr}/episode-{n}`,
 *    les saisons spéciales (film, kai, heroines…) deviennent la saison 0 ;
 *  - lecture : `<select id="epLecteurSelect">` rendu côté serveur, avec replis
 *    iframe / `og:video` / JSON-LD `embedUrl`.
 */
class AnimoFlixExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://animoflix.to"
    override val providerName = "AnimoFlix"
    override val extensionId = "fr.endlesssea.ext.animoflix"
    override val versionCode = 18
    override val descriptionText = "Animes VF et VOSTFR en streaming."
    override val supportedTypes = setOf(MediaType.ANIME, MediaType.MOVIE)

    override val homeRows = listOf(
        HomeRow("vostfr", "Derniers épisodes VOSTFR", "vostfr"),
        HomeRow("vf", "Derniers épisodes VF", "vf"),
        HomeRow("ajouts", "Derniers ajouts", "ajouts"),
        HomeRow("catalogue", "Catalogue", "catalogue"),
    )

    /** `/anime/{slug}/[saison/]{vf|vostfr}/episode-{n}` */
    private val episodePathRegex = Regex(
        """^/anime/([a-z0-9-]+)(?:/([a-z0-9-]+))?/(vf|vostfr)/episode-(\d+)$""",
        RegexOption.IGNORE_CASE,
    )

    // -----------------------------------------------------------------------
    // Aides URL / cartes
    // -----------------------------------------------------------------------

    private fun pathOf(href: String): String =
        href.substringBefore("#").substringBefore("?").removePrefix(mainUrl).trimEnd('/')

    private fun slugOf(href: String): String? {
        val path = pathOf(href)
        if (!path.startsWith("/anime/")) return null
        val slug = path.removePrefix("/anime/").substringBefore('/').lowercase()
        // « /anime/vf/… » est un lien de navigation, pas une fiche
        if (slug.isEmpty() || slug == "vf" || slug == "vostfr") return null
        if (!slug.matches(Regex("[a-z0-9-]+"))) return null
        return slug
    }

    private fun titleOf(a: Element): String? {
        val title = a.selectFirst("h1, h2, h3, h4")?.text()?.trim()
            ?: a.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim().takeIf { it.isNotBlank() && !it.startsWith("Voir") }
            ?: a.ownText().trim()
        return title.takeIf { it.isNotBlank() && it.length <= 120 }
    }

    /** Affiche de la carte, avec repli sur la jaquette `/covers/{slug}.webp`. */
    private fun posterOf(a: Element, slug: String?): String? {
        val img = a.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }
        return when {
            !img.isNullOrBlank() && !img.contains("default") -> Text.fixUrlNull(img, mainUrl)
            slug != null -> "$mainUrl/covers/$slug.webp"
            else -> null
        }
    }

    private fun toCard(a: Element): SearchItem? {
        val href = a.attr("href")
        val slug = slugOf(href) ?: return null
        val title = titleOf(a) ?: return null
        return item(title, "$mainUrl/anime/$slug/", MediaType.ANIME, posterOf(a, slug))
    }

    /** Section `<div class="section">` dont l'en-tête contient [needle]. */
    private fun Document.findSection(needle: String): Element? =
        select("div.section").firstOrNull {
            (it.selectFirst("header h1, header h2, h1, h2")?.text() ?: "").uppercase().contains(needle)
        }

    // -----------------------------------------------------------------------
    // Accueil
    // -----------------------------------------------------------------------

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        when (row.path) {
            "catalogue" -> {
                val doc = http.get("$mainUrl/catalogue/", params = mapOf("page" to page.toString()))
                    .verifyNotBlocked().requireOk().document
                val items = doc.select("a.cat-card-link").mapNotNull { toCard(it) }.distinctBy { it.url }
                return PagedResult(items, page, doc.selectFirst("a[href*=page=${page + 1}]") != null)
            }
            "vostfr", "vf" -> {
                if (page > 1) return PagedResult(emptyList(), page, false)
                val doc = http.get("$mainUrl/").verifyNotBlocked().requireOk().document
                val needle = if (row.path == "vf") "PISODES VF" else "PISODES VOSTFR"
                val items = (doc.findSection(needle) ?: doc).select("a[href*=episode-]")
                    .filter { pathOf(it.attr("href")).contains("/${row.path}/") }
                    .mapNotNull { toCard(it) }.distinctBy { it.url }
                return PagedResult(items, page, false)
            }
            else -> {
                if (page > 1) return PagedResult(emptyList(), page, false)
                val doc = http.get("$mainUrl/").verifyNotBlocked().requireOk().document
                val root = doc.findSection("AJOUTS") ?: doc
                val items = root.select("a[href]").mapNotNull { toCard(it) }.distinctBy { it.url }
                return PagedResult(items, page, false)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Recherche
    // -----------------------------------------------------------------------

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        // 1) recherche serveur du catalogue (renvoie du HTML dans du JSON)
        val cards = http.getOrNull("$mainUrl/catalogue/", params = mapOf("search" to query, "ajax" to "1"))
            ?.asJsonOrNull()?.str("cards")
        if (!cards.isNullOrBlank()) {
            val results = Jsoup.parseBodyFragment(cards, mainUrl)
                .select("a.cat-card-link").mapNotNull { toCard(it) }.distinctBy { it.url }
            if (results.isNotEmpty()) return results
        }
        // 2) autocomplétion (matche aussi les titres alternatifs)
        return http.getOrNull("$mainUrl/search-autocomplete.php", params = mapOf("q" to query))
            ?.asJsonOrNull()?.list.orEmpty().mapNotNull { s ->
                val title = s.str("title") ?: return@mapNotNull null
                val slug = s.str("slug") ?: return@mapNotNull null
                item(title, "$mainUrl/anime/$slug/", MediaType.ANIME, s.str("cover")?.let { "$mainUrl/covers/$it" })
            }
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        val doc = http.get(url).verifyNotBlocked().requireOk().document
        val slug = slugOf(url) ?: url.trimEnd('/').substringAfterLast('/')

        val title = doc.selectFirst("h1.hero-title, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: slug.replace('-', ' ')
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { Text.fixUrlNull(it, mainUrl) } ?: "$mainUrl/covers/$slug.webp"
        val altTitle = doc.selectFirst(".hero-title-alt")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst(".hero-synopsis .synopsis-inner")?.text()?.trim()
            ?: doc.selectFirst(".hero-synopsis")?.text()?.trim()
        val genres = doc.select(".hero-genres .genre-pill").map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 25 }.distinct()

        val label = doc.selectFirst(".hero-label")?.text()?.trim().orEmpty()
        val type = if (label.contains("Film", true)) MediaType.MOVIE else MediaType.ANIME

        val infoText = (doc.selectFirst(".hero")?.text().orEmpty()) + " " +
            doc.select(".hero-badges, .stat-row").joinToString(" ") { it.text() }
        val status = when {
            infoText.contains("en cours", true) -> MediaStatus.ONGOING
            infoText.contains("terminé", true) || infoText.contains("termine", true) -> MediaStatus.COMPLETED
            else -> MediaStatus.UNKNOWN
        }
        val year = Text.year(infoText)

        // Pages de saison (ou la fiche elle-même si le site n'en a pas)
        val seasonLinks = doc.select("a.season-card").map { it.attr("href") }.ifEmpty {
            doc.select("a[href]").map { it.attr("href") }.filter { href ->
                val path = pathOf(href)
                val rest = path.removePrefix("/anime/$slug/")
                path.startsWith("/anime/$slug/") && rest.isNotBlank() && !rest.contains('/')
            }
        }.map { Text.fixUrl(it, mainUrl) }
            .filter { pathOf(it).removePrefix("/anime/$slug/") != "scan" }
            .distinct()

        val raw = ArrayList<Triple<Int, String, Episode>>() // (saison, langue, épisode)
        val mediaId = Text.idOf("anfl", url)
        for (seasonUrl in seasonLinks.ifEmpty { listOf(url) }) {
            val sDoc = http.getOrNull(seasonUrl)?.document ?: continue
            val segment = pathOf(seasonUrl).removePrefix("/anime/$slug/").substringBefore('/')
            val seasonNum = Regex("""(?:saison|season)[-_](\d+)""", RegexOption.IGNORE_CASE)
                .find(segment)?.groupValues?.get(1)?.toIntOrNull() ?: 0 // spéciaux : film, kai…
            sDoc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                val m = episodePathRegex.matchEntire(pathOf(href)) ?: return@forEach
                if (m.groupValues[1] != slug) return@forEach
                val lang = m.groupValues[3].lowercase()
                val number = m.groupValues[4].toIntOrNull() ?: return@forEach
                val name = a.attr("title").trim()
                    .replace(Regex("""\s*V(OST)?FR\s*$""", RegexOption.IGNORE_CASE), "").trim()
                    .ifBlank { "Épisode $number" }
                raw += Triple(
                    seasonNum, lang,
                    Episode(
                        id = "$mediaId:$lang:s${seasonNum}e$number", number = number.toFloat(),
                        season = seasonNum, title = name, data = Text.fixUrl(href, mainUrl),
                    ),
                )
            }
        }
        if (raw.isEmpty()) throw SourceException.VideoUnavailable("aucun épisode listé")

        // Une saison par couple (saison du site, langue) : VF et VOSTFR restent séparés.
        val seasons = raw.distinctBy { it.third.data }
            .groupBy { it.first to it.second }
            .toSortedMap(compareBy({ it.first }, { it.second }))
            .entries.mapIndexed { idx, (key, list) ->
                val (sNum, lang) = key
                val label2 = (if (sNum == 0) "Spéciaux" else "Saison $sNum") +
                    " · " + if (lang == "vf") "VF" else "VOSTFR"
                Season(
                    idx + 1, label2,
                    list.map { it.third.copy(season = idx + 1) }.sortedBy { it.number },
                )
            }

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            altTitles = listOfNotNull(altTitle),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster,
            type = type, year = year, status = status, genres = genres,
            episodeCount = seasons.sumOf { it.episodes.size }, seasons = seasons,
            languages = buildList {
                if (raw.any { it.second == "vf" }) add(AudioLang.VF)
                if (raw.any { it.second == "vostfr" }) add(AudioLang.VOSTFR)
            },
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val doc = http.getOrNull(payload)?.document ?: return emptyList()
        val html = doc.html()
        val lang = if ("/vf/" in payload) AudioLang.VF else AudioLang.VOSTFR
        val players = LinkedHashMap<String, ServerEntry>()

        // Le <select> est rendu côté serveur avec tous les lecteurs ;
        // data-restricted="true" = lecteur à passer en dernier.
        doc.select("#epLecteurSelect option[value]").forEach { option ->
            val value = option.attr("value").trim()
            if (!value.startsWith("http")) return@forEach
            val label = option.text().trim().ifBlank { Text.host(value) }
            players.putIfAbsent(value, ServerEntry(label, value, lang, referer = mainUrl))
        }

        if (players.isEmpty()) {
            doc.select("iframe[src]").forEach { f ->
                val src = f.attr("src").trim()
                if (src.startsWith("http") && Text.host(src) != Text.host(mainUrl)) {
                    players.putIfAbsent(src, ServerEntry(Text.host(src), src, lang, referer = mainUrl))
                }
            }
            doc.selectFirst("meta[property=og:video]")?.attr("content")?.trim()?.takeIf { it.startsWith("http") }
                ?.let { players.putIfAbsent(it, ServerEntry(Text.host(it), it, lang, referer = mainUrl)) }
            Regex(""""(?:embedUrl|contentUrl)"\s*:\s*"(https?://[^"]+)"""").findAll(html).forEach {
                val u = it.groupValues[1]
                players.putIfAbsent(u, ServerEntry(Text.host(u), u, lang, referer = mainUrl))
            }
        }

        // Flux directs présents dans la page de l'épisode
        Regex("""https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*""").findAll(html).map { it.value }.distinct()
            .forEach { players.putIfAbsent(it, ServerEntry("Direct", it, lang, referer = mainUrl, direct = true)) }

        return players.values.toList()
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val entries = servers(data.episode.data)
        if (entries.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur sur la page de l'épisode")
        return resolveServers(entries, data.preferredServer)
    }
}
