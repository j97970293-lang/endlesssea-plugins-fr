package fr.endlesssea.common

import dev.endlesssea.extensions.api.EsExtension
import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.ExtractorApi
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.API_VERSION
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.ExtensionInfo
import dev.endlesssea.extensions.api.model.ExtensionSetting
import dev.endlesssea.extensions.api.model.FilterSet
import dev.endlesssea.extensions.api.model.HomeCategory
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

    /**
     * Domaine « usine » de la source, utilisé tant que l'utilisateur n'a pas
     * saisi d'adresse personnalisée dans les réglages de l'extension.
     */
    abstract val defaultUrl: String

    /**
     * Adresse réellement utilisée : le réglage `site_url` (Endless Sea 0.4.0+)
     * s'il est valide, sinon [defaultUrl]. Les providers à résolution
     * automatique de domaine peuvent surcharger en gardant [userUrl] prioritaire.
     */
    open val mainUrl: String get() = userUrl ?: defaultUrl

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

    /**
     * Icône affichée par l'accueil, la recherche et l'écran Extensions
     * (Endless Sea 0.9.0 — `ExtensionInfo.iconUrl`). Par défaut le favicon du
     * domaine réellement utilisé : elle suit donc automatiquement les
     * migrations de domaine (réglage `site_url` ou résolution dynamique).
     * PNG/JPEG/WebP/GIF uniquement : le chargeur de l'app refuse les SVG.
     */
    open val iconUrl: String?
        get() {
            val url = runCatching { mainUrl }.getOrNull()?.takeIf { it.isNotBlank() } ?: defaultUrl
            val host = Text.host(url).removePrefix("www.").takeIf { it.isNotBlank() } ?: return null
            return "https://www.google.com/s2/favicons?domain=$host&sz=128"
        }

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
            iconUrl = iconUrl,
        )
    }

    override fun extractors(): List<ExtractorApi> = Extractors.all(http)

    // -----------------------------------------------------------------------
    // Réglages utilisateur (Endless Sea 0.4.0 : injectés dans ctx.settings)
    // -----------------------------------------------------------------------

    /** Valeur brute d'un réglage déclaré par [settings]. */
    protected fun setting(key: String, fallback: String = ""): String =
        ctx.settings[key]?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    /** Réglage booléen (`true`/`1`/`oui`/`on`). */
    protected fun settingFlag(key: String, fallback: Boolean = false): Boolean =
        when (setting(key).lowercase()) {
            "true", "1", "oui", "yes", "on" -> true
            "false", "0", "non", "no", "off" -> false
            else -> fallback
        }

    /** Clé du réglage d'adresse ; `null` désactive l'entrée « domaine ». */
    protected open val siteUrlKey: String? = "site_url"
    protected open val siteUrlTitle: String get() = "Adresse de $providerName"
    protected open val siteUrlSummary: String? get() =
        "Le site change régulièrement de domaine. Laissez vide pour utiliser " +
            "l'adresse par défaut ($defaultUrl)."

    /** Adresse saisie par l'utilisateur, normalisée, ou `null` si absente/invalide. */
    protected val userUrl: String?
        get() {
            val key = siteUrlKey ?: return null
            return normalizeSiteUrl(ctx.settings[key])
        }

    /** Réglages propres à la source, ajoutés après l'entrée « domaine ». */
    protected open val extraSettings: List<ExtensionSetting> get() = emptyList()

    /**
     * Réglage « langue préférée » : l'app n'offre aucun choix VF/VOSTFR global,
     * et le type LIST de l'API n'accepte pas de liste d'options — on utilise
     * donc un champ texte libre (`vf`, `vostfr`, `vo`, `multi`, vide = auto).
     */
    protected open val offersLangSetting: Boolean get() = true

    private fun langSetting() = ExtensionSetting(
        key = "pref_lang",
        title = "Langue préférée",
        summary = "Les lecteurs de cette langue passent en premier. " +
            "« auto » suit le réglage global du lecteur.",
        type = ExtensionSetting.Type.LIST,
        defaultValue = "auto",
    ).also { it.options = listOf("auto", "vf", "vostfr", "vo", "multi") }

    /**
     * Langue demandée : réglage de la source, sinon préférence globale de l'app
     * (`app.pref_lang`, 0.7.0+), sinon `null` = ordre d'origine.
     */
    protected fun preferredLang(): AudioLang? {
        val raw = setting("pref_lang").lowercase().takeIf { it.isNotBlank() && it != "auto" }
            ?: ctx.settings["app.pref_lang"]?.trim()?.lowercase().orEmpty()
        return parseLang(raw)
    }

    private fun parseLang(value: String): AudioLang? = when (value) {
        "vf", "french", "francais", "français" -> AudioLang.VF
        "vostfr", "vost", "sub" -> AudioLang.VOSTFR
        "vo", "original" -> AudioLang.VO
        "multi" -> AudioLang.MULTI
        else -> null
    }

    override suspend fun settings(): List<ExtensionSetting> {
        val extras = extraSettings + (if (offersLangSetting) listOf(langSetting()) else emptyList())
        val key = siteUrlKey ?: return extras
        return listOf(
            ExtensionSetting(
                key = key,
                title = siteUrlTitle,
                summary = siteUrlSummary,
                type = ExtensionSetting.Type.TEXT,
                defaultValue = defaultUrl,
            )
        ) + extras
    }

    /** `exemple.com`, ` https://exemple.com/ ` → `https://exemple.com`. */
    protected fun normalizeSiteUrl(raw: String?): String? {
        val v = raw?.trim()?.trimEnd('/').orEmpty()
        if (v.isEmpty()) return null
        val withScheme = if (v.startsWith("http://") || v.startsWith("https://")) v else "https://$v"
        val host = Text.host(withScheme)
        if (!host.contains('.') || host.contains(' ')) return null
        return withScheme
    }


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
        rating: Double? = null,
        audioLangs: List<AudioLang> = emptyList(),
        genres: List<String> = emptyList(),
    ) = SearchItem(
        id = Text.idOf(providerName.lowercase().take(4), url),
        title = Text.decodeHtml(title).trim(),
        altTitles = altTitles,
        url = Text.fixUrl(url, mainUrl),
        posterUrl = poster?.let { Text.fixUrlNull(it, mainUrl) },
        type = type,
        year = year,
    ).also { s ->
        // Champs additifs de l'app 0.7.0 : badges ⭐ et VF/VOSTFR sur la vignette.
        rating?.takeIf { it > 0.0 }?.let { s.rating = it }
        s.genres = genres
        s.audioLangs = audioLangs.ifEmpty { langsFromLabel(title) }
    }

    /** VF / VOSTFR devinés depuis le titre de la vignette (« … VF », « … VOSTFR »). */
    private fun langsFromLabel(label: String): List<AudioLang> {
        val l = label.lowercase()
        val out = ArrayList<AudioLang>(2)
        if (Regex("""\bvostfr?\b|\bsous[- ]titr""").containsMatchIn(l)) out += AudioLang.VOSTFR
        if (Regex("""\bv(?:f|ff|fq|fi)\b|\btruefrench\b|\bfrench\b""").containsMatchIn(l)) out += AudioLang.VF
        if (Regex("""\bmulti\b""").containsMatchIn(l)) out += AudioLang.MULTI
        return out
    }

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
        // Priorité : lecteur choisi par l'utilisateur > langue préférée > ordre source.
        val pref = preferredLang()
        val ordered = entries.sortedWith(
            compareByDescending<ServerEntry> { e ->
                preferred != null && (e.name.equals(preferred.name, true) || e.url == preferred.id)
            }.thenByDescending { e ->
                when {
                    pref == null -> 0
                    e.lang == pref -> 2
                    e.lang == AudioLang.MULTI -> 1
                    else -> 0
                }
            }
        )
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

    /**
     * Rangées proposées à l'app (0.7.0+) : chaque [HomeRow] devient une
     * catégorie navigable (Accueil, Explorer, « Tout voir »). La première
     * tient lieu de « main ».
     */
    override suspend fun categories(): List<HomeCategory> =
        homeRows.map { HomeCategory(it.key, it.title) }

    override suspend fun getMainPage(request: MainPageRequest): PagedResult<SearchItem> {
        val row = homeRows.firstOrNull { it.key == request.category } ?: homeRows.firstOrNull()
        ?: return PagedResult(emptyList(), request.page, false)
        return home(row, request.page)
    }

    /** À implémenter si [homeRows] n'est pas vide. */
    protected open suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> =
        PagedResult(emptyList(), page, false)

    override suspend fun search(query: String, page: Int, filters: FilterSet): PagedResult<SearchItem> {
        // --- Parcours par catégorie via la recherche ---------------------------
        // L'app ne demande que la rangée « main » (MainPageRequest) et envoie
        // toujours un FilterSet vide : les autres rangées (genres, tendances…)
        // seraient invisibles. On les rend donc accessibles en tapant
        // « genre:action », « #action » ou « :action » dans la recherche —
        // et on honore filters.genres si l'app venait à le remplir.
        val wanted = filters.genres.firstOrNull()?.trim()
            ?: Regex("""^\s*(?:genre\s*:|cat\s*:|[#:])\s*(.+)$""", RegexOption.IGNORE_CASE)
                .find(query)?.groupValues?.get(1)?.trim()
        if (!wanted.isNullOrBlank()) {
            val row = findRow(wanted)
            if (row != null) {
                val res = home(row, page)
                if (res.items.isEmpty() && page == 1) throw SourceException.NoResults
                return res
            }
            if (page == 1 && homeRows.isNotEmpty()) {
                throw SourceException.NoResults
            }
        }

        val items = searchQuery(query, page)
        if (items.isEmpty() && page == 1) throw SourceException.NoResults
        return PagedResult(items, page, items.size >= 10)
    }

    /** Rangée dont la clé ou le titre correspond, accents et emojis ignorés. */
    protected fun findRow(label: String): HomeRow? {
        fun norm(v: String): String {
            val stripped = java.text.Normalizer.normalize(v, java.text.Normalizer.Form.NFD)
                .replace(Regex("""\p{M}+"""), "")
            return stripped.lowercase().filter { it.isLetterOrDigit() }
        }
        val n = norm(label)
        if (n.isEmpty()) return null
        return homeRows.firstOrNull { norm(it.key) == n || norm(it.title) == n }
            ?: homeRows.firstOrNull { norm(it.title).contains(n) || norm(it.key).contains(n) }
    }

    /** Libellés des rangées, pour les messages d'aide. */
    fun categoryTitles(): List<String> = homeRows.map { it.title }

    protected open suspend fun searchQuery(query: String, page: Int): List<SearchItem> = emptyList()

    protected fun paged(items: List<SearchItem>, page: Int, pageSize: Int = 20) =
        PagedResult(items, page, items.size >= pageSize)

    // -----------------------------------------------------------------------
    // Fiche : point d'entrée unique + enrichissement TMDB (app 0.5.0)
    // -----------------------------------------------------------------------

    /**
     * Construit la fiche propre à la source. Les providers implémentent ceci
     * plutôt que [load] : le socle se charge ensuite d'ajouter la
     * bande-annonce et la distribution quand un identifiant TMDB est connu.
     */
    protected abstract suspend fun details(url: String): MediaDetails

    final override suspend fun load(url: String): MediaDetails {
        val base = details(url)
        val tmdb = tmdbIdOf(base) ?: return base
        val isTv = base.type == MediaType.SERIES || base.type == MediaType.ANIME

        // 1) vignettes d'épisodes manquantes (copy() : seasons est un val, donc
        //    à faire AVANT de poser les champs hors constructeur).
        val withStills =
            if (isTv) runCatching { Tmdb.withStills(http, base, tmdb) }.getOrDefault(base) else base

        // 2) bande-annonce + distribution (propriétés var, posées en dernier).
        if (withStills.characters.isNotEmpty() && withStills.trailerUrl != null) return withStills
        return runCatching { Tmdb.enrichOrSame(http, withStills, tmdb, isTv) }.getOrDefault(withStills)
    }

    /**
     * Identifiant TMDB déduit de la fiche : `externalIds`, puis conventions de
     * charge utile du dépôt (`movie|123`, `x:movie:123`, `tv|123|1|1`,
     * `/movie/123`…). Surchargeable quand la source le stocke ailleurs.
     */
    protected open fun tmdbIdOf(d: MediaDetails): String? {
        d.externalIds["tmdb"]?.takeIf { it.isNotBlank() }?.let { return it }
        val haystacks = listOfNotNull(
            d.url,
            d.seasons.firstOrNull()?.episodes?.firstOrNull()?.data,
        )
        for (h in haystacks) {
            Regex("""(?:^|[|:/])(?:movie|tv|serie)[|:/](\d{2,8})(?:[|:/]|$)""")
                .find(h)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> =
        resolveServers(servers(data.episode.data), data.preferredServer)

    /** Lecteurs disponibles pour la charge utile d'un épisode. */
    protected open suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}
