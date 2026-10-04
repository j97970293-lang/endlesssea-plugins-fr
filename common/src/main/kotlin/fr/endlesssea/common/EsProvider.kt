package fr.endlesssea.common

import dev.endlesssea.extensions.api.EsExtension
import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.ExtractorApi
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.API_VERSION
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.ExtensionInfo
import dev.endlesssea.extensions.api.model.FilterSet
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MainPageRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.ServerRef
import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.SubtitleTrack
import dev.endlesssea.extensions.api.model.VideoLink
import dev.endlesssea.extensions.api.permission.ExtensionPermission

/** Une rangée de la page d'accueil (équivalent natif de `mainPageOf`). */
data class HomeRow(val key: String, val title: String, val path: String = "")

/**
 * Un lecteur annoncé par la fiche, résolu à la demande dans `loadLinks`
 * (remplace la paire `ExtractorLink` + `loadExtractor` de CloudStream).
 */
data class ServerEntry(
    val name: String,
    val url: String,
    val lang: AudioLang = AudioLang.OTHER,
    val referer: String? = null,
    /** `true` si [url] est déjà un flux jouable (pas un embed à résoudre). */
    val direct: Boolean = false,
)

/**
 * Socle commun des extensions natives Endless Sea de ce dépôt.
 *
 * Il fournit : l'accès HTTP, les extracteurs natifs, les fabriques de DTO et la
 * résolution des lecteurs. Chaque provider n'implémente plus que la logique
 * propre au site (URLs, sélecteurs, pagination).
 */
abstract class EsProvider(protected val ctx: ExtensionContext) : EsExtension {

    protected val http: Http by lazy { Http(ctx) }

    abstract val mainUrl: String
    abstract val providerName: String

    /** Rangées proposées sur l'accueil ; la première sert de valeur par défaut. */
    open val homeRows: List<HomeRow> = emptyList()

    open val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.SERIES)
    open val languages: List<String> = listOf("fr")
    open val nsfw: Boolean = false
    open val extensionId: String get() = "fr.endlesssea.ext." + providerName.lowercase().filter { it.isLetterOrDigit() }
    open val versionCode: Int = 1
    open val author: String = "j97970293-lang"
    open val descriptionText: String = providerName

    override val info: ExtensionInfo by lazy {
        ExtensionInfo(
            id = extensionId,
            name = providerName,
            version = versionCode,
            apiVersion = API_VERSION,
            languages = languages,
            types = supportedTypes,
            permissions = setOf(
                ExtensionPermission.INTERNET,
                ExtensionPermission.DOWNLOAD,
                ExtensionPermission.WEBVIEW,
            ),
            author = author,
            description = descriptionText,
            nsfw = nsfw,
        )
    }

    override fun extractors(): List<ExtractorApi> = Extractors.all(http)

    // -----------------------------------------------------------------------
    // Fabriques de DTO
    // -----------------------------------------------------------------------

    protected fun item(
        title: String,
        url: String,
        type: MediaType = supportedTypes.firstOrNull() ?: MediaType.OTHER,
        poster: String? = null,
        year: Int? = null,
        altTitles: List<String> = emptyList(),
    ) = SearchItem(
        id = Text.idOf(providerName.lowercase().take(4), url),
        title = Text.decodeHtml(title).trim(),
        altTitles = altTitles,
        url = Text.fixUrl(url, mainUrl),
        posterUrl = poster?.let { Text.fixUrlNull(it, mainUrl) },
        type = type,
        year = year,
    )

    /** Fiche « film » : une saison, un épisode, la charge utile pointant les lecteurs. */
    protected fun movieDetails(
        url: String,
        title: String,
        payload: String = url,
        poster: String? = null,
        synopsis: String? = null,
        year: Int? = null,
        type: MediaType = MediaType.MOVIE,
        genres: List<String> = emptyList(),
        durationMin: Int? = null,
        servers: List<ServerRef> = emptyList(),
        languagesFound: List<AudioLang> = emptyList(),
        banner: String? = null,
    ): MediaDetails {
        val id = Text.idOf(providerName.lowercase().take(4), url)
        return MediaDetails(
            id = id,
            url = Text.fixUrl(url, mainUrl),
            title = Text.decodeHtml(title).trim(),
            synopsis = synopsis?.let { Text.stripHtml(it) }?.takeIf { it.isNotBlank() },
            posterUrl = poster?.let { Text.fixUrlNull(it, mainUrl) },
            bannerUrl = banner?.let { Text.fixUrlNull(it, mainUrl) },
            type = type,
            year = year,
            genres = genres,
            durationMin = durationMin,
            episodeCount = 1,
            seasons = listOf(
                Season(
                    1, null,
                    listOf(Episode(id = "$id:1", number = 1f, season = 1, title = Text.decodeHtml(title).trim(), data = payload))
                )
            ),
            servers = servers,
            languages = languagesFound,
        )
    }

    protected fun episode(
        number: Float,
        season: Int,
        payload: String,
        title: String? = null,
        thumbnail: String? = null,
        mediaId: String = "",
    ) = Episode(
        id = "$mediaId:s${season}e${number.toInt()}",
        number = number,
        season = season,
        title = title?.let { Text.decodeHtml(it).trim() },
        thumbnailUrl = thumbnail?.let { Text.fixUrlNull(it, mainUrl) },
        data = payload,
    )

    // -----------------------------------------------------------------------
    // Résolution des lecteurs
    // -----------------------------------------------------------------------

    /**
     * Transforme les lecteurs annoncés en liens jouables : les flux directs sont
     * conservés tels quels, les embeds passent par les extracteurs natifs.
     * Le lecteur choisi par l'utilisateur ([LinkRequest.preferredServer]) est traité
     * en premier et remonté en tête de liste.
     */
    protected suspend fun resolveServers(
        entries: List<ServerEntry>,
        preferred: ServerRef? = null,
        subtitleCallback: (SubtitleTrack) -> Unit = {},
    ): List<VideoLink> {
        if (entries.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur annoncé")
        val ordered = entries.sortedByDescending { e ->
            preferred != null && (e.name.equals(preferred.name, true) || e.url == preferred.id)
        }
        val out = LinkedHashMap<String, VideoLink>()
        val subs = ArrayList<SubtitleTrack>()

        for (entry in ordered) {
            val links = if (entry.direct) {
                val type = Text.streamType(entry.url)
                if (type == StreamType.HLS) {
                    M3u8.variants(http, entry.url, entry.name, entry.referer ?: mainUrl)
                } else {
                    listOf(
                        VideoLink(
                            url = entry.url,
                            streamType = type,
                            quality = Text.quality(entry.url),
                            server = entry.name,
                            headers = buildMap {
                                put("User-Agent", http.userAgent)
                                put("Referer", entry.referer ?: mainUrl)
                            },
                        )
                    )
                }
            } else {
                runCatching {
                    Extractors.resolve(http, entry.url, entry.referer ?: mainUrl, entry.name) { subs.add(it) }
                }.getOrDefault(emptyList())
            }
            links.forEach { l ->
                val withLang = if (entry.lang != AudioLang.OTHER) l.copy(audioLang = entry.lang) else l
                out.putIfAbsent(withLang.url, withLang)
            }
        }
        subs.forEach(subtitleCallback)
        if (out.isEmpty()) throw SourceException.VideoUnavailable("aucun flux exploitable")
        return out.values.sortedWith(
            compareByDescending<VideoLink> { it.quality.pixels }.thenBy { it.server }
        ).map { if (subs.isEmpty()) it else it.copy(subtitles = it.subtitles + subs) }
    }

    protected fun serverRefs(entries: List<ServerEntry>): List<ServerRef> =
        entries.mapIndexed { i, e -> ServerRef(e.url.ifBlank { "srv$i" }, e.name) }

    // -----------------------------------------------------------------------
    // Valeurs par défaut
    // -----------------------------------------------------------------------

    override suspend fun getMainPage(request: MainPageRequest): PagedResult<SearchItem> {
        val row = homeRows.firstOrNull { it.key == request.category } ?: homeRows.firstOrNull()
        ?: return PagedResult(emptyList(), request.page, false)
        return home(row, request.page)
    }

    /** À implémenter si [homeRows] n'est pas vide. */
    protected open suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> =
        PagedResult(emptyList(), page, false)

    override suspend fun search(query: String, page: Int, filters: FilterSet): PagedResult<SearchItem> {
        val items = searchQuery(query, page)
        if (items.isEmpty() && page == 1) throw SourceException.NoResults
        return PagedResult(items, page, items.size >= 10)
    }

    protected open suspend fun searchQuery(query: String, page: Int): List<SearchItem> = emptyList()

    protected fun paged(items: List<SearchItem>, page: Int, pageSize: Int = 20) =
        PagedResult(items, page, items.size >= pageSize)

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> =
        resolveServers(servers(data.episode.data), data.preferredServer)

    /** Lecteurs disponibles pour la charge utile d'un épisode. */
    protected open suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
