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
import fr.endlesssea.common.Aggregators
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import fr.endlesssea.common.Tmdb
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz
import fr.endlesssea.common.urlEncode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * CineStream (cinestream.info) — portage **natif** Endless Sea.
 *
 * Chemin nominal (site en ligne) :
 *  - listes paginées : `/film-en-streaming/{n}`, `/films-ajoutes-recemment/{n}`,
 *    `/films-populaires/{n}`, `/films/{Genre}/{n}` ;
 *  - recherche SSR : `/search?q=…` ;
 *  - fiche : `/film/{slug}` → boutons lecteurs + `tmdbid` dans le payload RSC ;
 *  - lecteurs : `/player/{tmdbid}/{index}` → `<iframe src="URL hébergeur">`,
 *    résolus par les extracteurs natifs (Vidara, Voe, Uqload, VidMoly…) ;
 *  - filet de secours : agrégateur vidsrc.buzz par identifiant TMDB.
 *
 * Mode dégradé (site injoignable — `cinestream.info` répond « 404 page not
 * found » depuis le 7 octobre 2026, sans nouvelle adresse publiée) :
 *  - une sonde à courte mémoire ([siteAvailable]) détecte la panne ;
 *  - accueil, recherche et fiches basculent sur le catalogue TMDB en français
 *    ([Tmdb]) ; les cartes de repli portent l'URL interne `cine:movie:{tmdb}` ;
 *  - les lecteurs de ces cartes sont servis par le réseau public
 *    `api.movix.men` (qui indexe aussi la source « cinestream »), les lecteurs
 *    TMDB publics, puis vidsrc.buzz ;
 *  - dès que le site revient — ou qu'une nouvelle adresse est saisie dans le
 *    réglage `site_url` — le chemin nominal reprend automatiquement.
 */
class CineStreamExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://cinestream.info"
    override val providerName = "CineStream"
    override val extensionId = "fr.endlesssea.ext.cinestream"
    override val versionCode = 17
    override val descriptionText =
        "Films VF/VOSTFR, une quinzaine de lecteurs par titre (repli TMDB si le site est injoignable)."
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

    // -----------------------------------------------------------------------
    // Sonde de disponibilité du site (mode dégradé)
    // -----------------------------------------------------------------------

    /** Résultat de sonde, invalidé après 10 min ou si l'adresse utilisée change. */
    private data class Probe(val url: String, val at: Long, val ok: Boolean)

    @Volatile private var probe: Probe? = null

    /**
     * `true` si le site répond avec un catalogue exploitable. Un défi anti-bot
     * (Cloudflare…) n'est **pas** une panne : il est propagé tel quel pour que
     * l'app ouvre sa WebView de vérification puis rejoue l'appel.
     */
    private suspend fun siteAvailable(): Boolean {
        probe?.let { p ->
            if (p.url == mainUrl && System.currentTimeMillis() - p.at < 600_000L) return p.ok
        }
        val res = try {
            http.getOrNull("$mainUrl/film-en-streaming/1", baseHeaders)
        } catch (e: SourceException.CaptchaRequired) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val ok = res != null && res.isSuccessful && parseCards(res.text).isNotEmpty()
        probe = Probe(mainUrl, System.currentTimeMillis(), ok)
        return ok
    }

    // -----------------------------------------------------------------------
    // Accueil
    // -----------------------------------------------------------------------

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (siteAvailable()) {
            val html = http.getOrNull("$mainUrl${row.path}/$page", baseHeaders)?.text
            if (html != null) {
                val items = parseCards(html)
                if (items.isNotEmpty()) return PagedResult(items, page, items.size >= 20)
            }
        }
        // Mode dégradé : rangée TMDB équivalente (films).
        val (items, hasNext) = Tmdb.page(http, fallbackPath(row), page, "cine", "movie")
        return PagedResult(items, page, hasNext)
    }

    /** Chemin TMDB en français équivalent à une rangée du site. */
    private fun fallbackPath(row: HomeRow): String {
        val genre = when (row.key) {
            "action" -> 28
            "animation" -> 16
            "aventure" -> 12
            "comedie" -> 35
            "sf" -> 878
            "horreur" -> 27
            "thriller" -> 53
            else -> null
        }
        return when {
            genre != null -> "discover/movie?genre=$genre"
            row.key == "recents" -> "movie/now_playing"
            row.key == "populaires" -> "movie/popular"
            else -> "trending/movie/week"
        }
    }

    // -----------------------------------------------------------------------
    // Recherche
    // -----------------------------------------------------------------------

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (siteAvailable() && page == 1) {
            val html = http.getOrNull("$mainUrl/search?q=${query.urlEncode()}", baseHeaders)?.text
            if (html != null) {
                val items = parseCards(html)
                if (items.isNotEmpty()) return items
            }
        }
        // Mode dégradé : recherche multi TMDB, films uniquement (la source ne
        // référence que des films). Les cartes restent lisibles en mode dégradé.
        return Tmdb.searchMulti(http, query, "cine", page).filter { it.type == MediaType.MOVIE }
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

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        // Carte de repli TMDB : « cine:movie:{tmdb} ».
        if (url.startsWith("cine:")) {
            val tmdb = url.substringAfterLast(':')
            return Tmdb.details(http, tmdb, false, "cine")
                ?: throw SourceException.VideoUnavailable("fiche TMDB introuvable")
        }

        // Chemin nominal : fiche du site.
        val site = runCatching { siteDetails(url) }
        site.getOrNull()?.let { return it }

        // Site injoignable : dernier recours, retrouver la fiche TMDB depuis le
        // slug (« /film/fight-club-1999 » → « fight club ») pour que les anciens
        // favoris restent lisibles ; la lecture suivra le mode dégradé.
        val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        val guess = slug.replace(Regex("""[-_]+"""), " ")
            .replace(Regex("""\s*\(?((?:19|20)\d{2})\)?$"""), "")
            .trim()
        if (guess.isNotBlank()) {
            val tmdb = runCatching { Tmdb.idFromTitle(http, guess, false) }.getOrNull()
            if (tmdb != null) {
                Tmdb.details(http, tmdb, false, "cine")?.let { return it }
            }
        }
        throw site.exceptionOrNull() ?: SourceException.SourceUnavailable(null)
    }

    /** Fiche du site (chemin nominal). */
    private suspend fun siteDetails(url: String): MediaDetails {
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
        val tmdbId = Regex("""tmdbid[\"]*:+(\d+)""").find(html)?.groupValues?.get(1)

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
        Regex("""<button id="([^"]+)" aria-label="Lecteur"""").findAll(html)
            .map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    override suspend fun servers(payload: String): List<ServerEntry> {
        val ficheUrl = payload.substringBefore('|')
        val html = http.get(ficheUrl, baseHeaders).requireOk().text
        val tmdbId = payload.substringAfter('|', "").ifBlank {
            Regex("""tmdbid[\"]*:+(\d+)""").find(html)?.groupValues?.get(1).orEmpty()
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

    /** Lecteurs du réseau public pour une carte de repli TMDB (films). */
    private suspend fun tmdbFallbackServers(tmdb: String): List<ServerEntry> = listOf(
        // Réseau api.movix.men : purstream HLS direct, wiflix (source « cinestream »),
        // frenchstream, cpasmal, liens directs.
        runCatching { Aggregators.movixNetwork(http, "https://api.movix.men/api", tmdb, isTv = false) }
            .getOrDefault(emptyList()),
        runCatching { Aggregators.movix(http, tmdb) }.getOrDefault(emptyList()),
        TmdbEmbeds.publicEmbeds(tmdb),
    ).flatten().distinctBy { it.url }

    override fun linkStream(data: LinkRequest): Flow<VideoLink> = flow {
        val payload = data.episode.data

        // Cartes de repli TMDB (« cine:movie:{tmdb} ») : réseau public, lecteurs
        // TMDB publics, puis vidsrc.buzz.
        if (payload.startsWith("cine:")) {
            val tmdb = payload.substringAfterLast(':')
            emitAll(
                firstNonEmpty(
                    {
                        resolveServersFlow(
                            runCatching { tmdbFallbackServers(tmdb) }.getOrDefault(emptyList()),
                            data.preferredServer,
                        )
                    },
                    { linksBlocking { VidSrcBuzz.links(http, tmdb) } },
                )
            )
            return@flow
        }

        // Chemin nominal : lecteurs de la fiche, puis agrégateur TMDB en secours.
        emitAll(
            firstNonEmpty(
                { resolveServersFlow(runCatching { servers(payload) }.getOrDefault(emptyList()), data.preferredServer) },
                {
                    // Secours : agrégateur TMDB
                    val tmdbId = payload.substringAfter('|', "")
                    if (tmdbId.isBlank()) throw SourceException.VideoUnavailable("pas d'identifiant TMDB")
                    linksBlocking { VidSrcBuzz.links(http, tmdbId) }
                },
            )
        )
    }
}
