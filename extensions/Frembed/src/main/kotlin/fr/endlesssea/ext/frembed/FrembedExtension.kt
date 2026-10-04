package fr.endlesssea.ext.frembed

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
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
import fr.endlesssea.common.Tmdb
import fr.endlesssea.common.TmdbEmbeds
import fr.endlesssea.common.VidSrcBuzz

/**
 * Frembed (frembed.surf / frembed.skin) — portage **natif** Endless Sea.
 *
 *  - accueil : pages `/movies` et `/tv-show` du site (cartes
 *    `/{route}/{slug}/{tmdbId}` + `<img alt>`), pagination `?page=N` ;
 *  - recherche : le site n'en a pas → TMDB multi, puis **sonde** de
 *    disponibilité sur l'API Frembed pour ne proposer que du lisible ;
 *  - fiche : TMDB en français ;
 *  - lecture : `/api/films|series` → `links[].url` relatifs `/api/stream?…`
 *    résolus par redirection, puis vidsrc.buzz (TMDB puis IMDb) en secours.
 */
class FrembedExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    private val fallbackDomains = listOf("https://frembed.surf", "https://frembed.skin")

    override val defaultUrl = "https://frembed.surf"
    override val providerName = "Frembed"
    override val extensionId = "fr.endlesssea.ext.frembed"
    override val versionCode = 13
    override val descriptionText = "Réseau de lecteurs FR indexé par TMDB (Voe, Dood, Uqload…)."
    override val supportedTypes = setOf(MediaType.MOVIE, MediaType.SERIES)

    @Volatile
    private var resolvedDomain: String? = null

    /** Premier domaine du réseau qui répond — les miroirs tournent souvent. */
    private suspend fun origin(): String {
        userUrl?.let { return it }          // adresse imposée par l'utilisateur
        resolvedDomain?.let { return it }
        for (candidate in fallbackDomains) {
            val ok = http.getOrNull("$candidate/", referer = "$candidate/")?.isSuccessful == true
            if (ok) {
                resolvedDomain = candidate
                return candidate
            }
        }
        return fallbackDomains.first()
    }

    override val homeRows = listOf(
        HomeRow("movies", "🎬 Derniers films", "movies"),
        HomeRow("tv-show", "📺 Dernières séries", "tv-show"),
    )

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val origin = origin()
        val url = "$origin/${row.path}" + if (page > 1) "?page=$page" else ""
        val html = http.getOrNull(url, referer = "$origin/")?.text
            ?: return PagedResult(emptyList(), page, false)

        // Les attributs de <img> sont en ordre alphabétique (React) : alt avant
        // src → on capture tout le tag puis on lit chaque attribut.
        val items = LinkedHashMap<String, SearchItem>()
        Regex(
            """href="(?:https?://[^"]*?)?/(${row.path})/([a-z0-9-]+)/(\d+)""" + """["'][\s\S]{0,900}?<img([^>]+)>""",
        ).findAll(html).forEach { m ->
            val (_, slug, tmdb, imgTag) = m.destructured
            val id = tmdb.toIntOrNull() ?: return@forEach
            val title = Regex("""alt="([^"]*)"""").find(imgTag)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: slug.replace('-', ' ').replaceFirstChar { it.uppercase() }
            val poster = Regex("""src="([^"]+)"""").find(imgTag)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }
            val isTv = row.path == "tv-show"
            val data = "frembed:${if (isTv) "tv" else "movie"}:$id"
            items.putIfAbsent(
                data,
                SearchItem(
                    id = data, title = title, url = data,
                    posterUrl = poster?.let { if (it.startsWith("http")) it else origin + it },
                    type = if (isTv) MediaType.SERIES else MediaType.MOVIE,
                ),
            )
        }

        val totalPages = Regex("""page\s+\d+\s*/\s*(\d+)""")
            .find(Regex("""<[^>]+>""").replace(html, " "))?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return PagedResult(items.values.toList(), page, page < totalPages && items.isNotEmpty())
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val origin = origin()
        // On ne propose que ce que le réseau sait réellement lire : chaque
        // résultat TMDB est sondé sur l'API Frembed avant d'être affiché.
        return Tmdb.searchMulti(http, query, "frembed").take(10).filter { item ->
            val parts = item.url.split(":")
            val isTv = parts.getOrNull(1) == "tv"
            val id = parts.getOrNull(2) ?: return@filter false
            val probe = if (isTv) "$origin/api/series?id=$id&sa=1&epi=1&idType=tmdb"
            else "$origin/api/films?id=$id&idType=tmdb"
            val body = http.getOrNull(probe, mapOf("Accept" to "application/json"), referer = "$origin/")?.text
            body != null && Regex(""""url"\s*:""").containsMatchIn(body)
        }
    }

    override suspend fun details(url: String): MediaDetails {
        val parts = url.substringAfter("frembed:", "").split(":").filter { it.isNotBlank() }
        val isTv = parts.getOrNull(0) == "tv"
        val tmdb = parts.getOrNull(1) ?: throw SourceException.VideoUnavailable("fiche invalide")
        return Tmdb.details(http, tmdb, isTv, "frembed")
            ?: throw SourceException.VideoUnavailable("fiche TMDB introuvable")
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val payload = data.episode.data
        val parts = payload.substringAfter("frembed:", payload).split(":").filter { it.isNotBlank() }
        val isTv = parts.getOrNull(0) == "tv"
        val tmdb = parts.getOrNull(1) ?: throw SourceException.VideoUnavailable("identifiant manquant")
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()

        val entries = ArrayList<ServerEntry>()
        entries += runCatching { Aggregators.frembedNetwork(http, tmdb, isTv, season, episode, origin()) }
            .getOrDefault(emptyList())
        entries += TmdbEmbeds.publicEmbeds(tmdb, season, episode)

        val out = LinkedHashMap<String, VideoLink>()
        runCatching { resolveServers(entries, data.preferredServer) }
            .getOrDefault(emptyList())
            .forEach { out.putIfAbsent(it.url, it) }

        if (out.isEmpty()) {
            runCatching { VidSrcBuzz.links(http, tmdb, season, episode) }
                .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
            if (out.isEmpty()) {
                Tmdb.imdbId(http, tmdb, isTv)?.let { imdb ->
                    runCatching { VidSrcBuzz.links(http, imdb, season, episode) }
                        .getOrDefault(emptyList()).forEach { out.putIfAbsent(it.url, it) }
                }
            }
        }

        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun serveur exploitable")
        return out.values.sortedByDescending { it.quality.pixels }
    }

    override suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
