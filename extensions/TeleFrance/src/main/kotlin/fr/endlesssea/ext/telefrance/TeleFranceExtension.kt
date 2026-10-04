package fr.endlesssea.ext.telefrance

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.ExtensionSetting
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.M3u8
import fr.endlesssea.common.ServerEntry

/**
 * Télé FR Direct — portage **natif** Endless Sea.
 *
 * Sources 100 % publiques :
 *  - playlists M3U d'iptv-org (France + langue française) ;
 *  - chaînes malgaches diffusées en direct sur YouTube : le manifeste HLS
 *    (`hlsManifestUrl`) est lu directement sur la page `watch`, sans extracteur
 *    propriétaire (l'extracteur YouTube de CloudStream n'existe pas côté Endless Sea).
 */
class TeleFranceExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://iptv-org.github.io"
    override val providerName = "Télé FR Direct"
    override val extensionId = "fr.endlesssea.ext.telefrance"
    override val versionCode = 7
    override val descriptionText =
        "Chaînes françaises, francophones et malgaches en direct (playlists IPTV publiques)."
    override val supportedTypes = setOf(MediaType.OTHER)

    private val defaultPlaylist = "https://iptv-org.github.io/iptv/countries/fr.m3u"
    private val francoPlaylist = "https://iptv-org.github.io/iptv/languages/fra.m3u"

    /** Pas de « domaine » ici : la source est une playlist publique. */
    override val siteUrlKey: String? = null

    override val extraSettings = listOf(
        ExtensionSetting(
            key = "playlist_url",
            title = "Liste de chaînes (M3U)",
            summary = "Par défaut : chaînes françaises d'iptv-org. Exemple Madagascar : " +
                "https://iptv-org.github.io/iptv/countries/mg.m3u",
            type = ExtensionSetting.Type.TEXT,
            defaultValue = defaultPlaylist,
        ),
        ExtensionSetting(
            key = "franco_playlist",
            title = "Liste francophonie (M3U)",
            summary = "Deuxième playlist fusionnée avec la première.",
            type = ExtensionSetting.Type.TEXT,
            defaultValue = francoPlaylist,
        ),
    )

    /** Playlists effectives : réglage utilisateur si renseigné, sinon défaut. */
    private val playlistUrl: String get() = setting("playlist_url", defaultPlaylist)
    private val francoUrl: String get() = setting("franco_playlist", francoPlaylist)

    override val homeRows = listOf(
        HomeRow("madagascar", "🇲🇬 Madagascar"),
        HomeRow("franco", "🌍 Francophonie"),
        HomeRow("general", "📺 Généralistes"),
        HomeRow("info", "📰 Info & Actualité"),
        HomeRow("cinema", "🎬 Cinéma"),
        HomeRow("series", "🎞️ Séries"),
        HomeRow("divertissement", "🎭 Divertissement"),
        HomeRow("animation", "✨ Animation"),
        HomeRow("enfants", "🧒 Enfants"),
        HomeRow("sport", "⚽ Sport"),
        HomeRow("documentaires", "🌍 Documentaires"),
        HomeRow("musique", "🎵 Musique"),
        HomeRow("divers", "📡 Divers"),
    )

    // -----------------------------------------------------------------------
    // Playlists M3U
    // -----------------------------------------------------------------------

    private data class Channel(
        val rawName: String,
        val name: String,
        val logo: String?,
        val groups: List<String>,
        val url: String,
        val forcedSection: String? = null,
    ) {
        val key: String get() = Integer.toHexString((name + url).hashCode())
    }

    @Volatile
    private var cache: Pair<Long, List<Channel>>? = null

    private suspend fun channels(): List<Channel> {
        cache?.let { (ts, list) ->
            if (list.isNotEmpty() && System.currentTimeMillis() - ts < 600_000L) return list
        }
        val text = http.getOrNull(playlistUrl)?.text
            ?: throw SourceException.SourceUnavailable(null)
        val out = parsePlaylist(text).toMutableList()
        http.getOrNull(francoUrl)?.text?.let { fra ->
            val known = out.map { it.name.lowercase() }.toSet()
            out += parsePlaylist(fra, forcedSection = "franco", exclude = known)
        }
        if (out.isEmpty()) throw SourceException.NoResults
        cache = System.currentTimeMillis() to out
        return out
    }

    private fun parsePlaylist(
        text: String,
        forcedSection: String? = null,
        exclude: Set<String> = emptySet(),
    ): List<Channel> {
        val out = ArrayList<Channel>()
        var pending: Triple<String, String?, List<String>>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    val rawName = line.substringAfterLast(',').trim()
                    val logo = Regex("""tvg-logo="([^"]*)"""").find(line)?.groupValues?.get(1)
                        ?.takeIf { it.startsWith("http") }
                    val groups = Regex("""group-title="([^"]*)"""").find(line)?.groupValues?.get(1)
                        ?.split(';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
                    pending = Triple(rawName, logo, groups)
                }
                line.startsWith("http") && pending != null -> {
                    val (rawName, logo, groups) = pending!!
                    pending = null
                    if ("[Geo-blocked]" in rawName || "[Not 24/7]" in rawName) return@forEach
                    if ("Chrome/" in rawName || "Mozilla" in rawName || "like Gecko" in rawName) return@forEach
                    val clean = rawName
                        .replace(Regex("""\s*\[\d+x\d+\]"""), "")
                        .replace(Regex("""\s*\((?:\d{3,4}p|SD|HD|FHD|UHD)\)"""), "")
                        .trim()
                    if (clean.isBlank() || clean.lowercase() in exclude) return@forEach
                    out += Channel(rawName, clean, logo, groups, line, forcedSection)
                }
            }
        }
        return out
    }

    private val groupKeys = linkedMapOf(
        "General" to "general", "News" to "info", "Movies" to "cinema",
        "Series" to "series", "Entertainment" to "divertissement", "Animation" to "animation",
        "Kids" to "enfants", "Sports" to "sport", "Documentary" to "documentaires",
        "Music" to "musique", "Travel" to "voyages", "Business" to "info",
        "Culture" to "documentaires", "Family" to "enfants", "Comedy" to "divertissement",
        "Outdoor" to "documentaires", "Religious" to "divers", "Auto" to "divertissement",
        "Legislative" to "info", "Weather" to "info", "Science" to "documentaires",
        "Education" to "documentaires", "Shop" to "divers", "Classic" to "cinema",
        "Lifestyle" to "divertissement", "Food" to "divertissement",
    )

    private fun sectionFor(ch: Channel): String =
        ch.forcedSection ?: ch.groups.firstNotNullOfOrNull { groupKeys[it.trim()] } ?: "divers"

    // -----------------------------------------------------------------------
    // Chaînes malgaches (directs YouTube)
    // -----------------------------------------------------------------------

    private data class MgChannel(val name: String, val channelId: String, val logo: String, val description: String)

    private val mgChannels = listOf(
        MgChannel(
            "TVM · Télévision Malagasy", "UCY4OPgqs3SGZ9m-lUaPHHfA",
            "https://yt3.googleusercontent.com/pT7rhz9YS6y0nprCLf52KtkPzerA3OcW-3_0Tr05iyeIp5BU_2ZCGs7OOuEu3hY0FA2zHy9Ojw=s900-c-k-c0x00ffffff-no-rj",
            "Chaîne nationale malgache — journaux (Vaovao) et magazines en direct",
        ),
        MgChannel(
            "RealTV Madagasikara", "UC05gqN4aAxZBJBrjPvc340Q",
            "https://yt3.googleusercontent.com/0TiqrzY8aHd4uoPdNxiWsbmWf0TQ6T8QbF6F9AYzuz2rdVr2SEahAyhDvZqV51zdeZQMUHY-oRc=s900-c-k-c0x00ffffff-no-rj",
            "Info et débats de Madagascar en direct",
        ),
        MgChannel(
            "RTA Madagascar", "UCgTfo5nsaRZ24eQ_KrMPdkA",
            "https://yt3.googleusercontent.com/kn3S0tpBCYjbhn6QWOXzUZBTGJpwHnKJ2xqf1m_wVKYa7_eY3ulr4FEPZ6k8lAsj2G6gr8pZVA=s900-c-k-c0x00ffffff-no-rj",
            "Radio Télévision Analamanga (Antananarivo) — directs et émissions",
        ),
        MgChannel(
            "Viva TV Madagasikara", "UCxjA5_vnrzATgAuy5yv749A",
            "https://yt3.googleusercontent.com/fxVdbYnkGCr6bFEHsfGr6GfXmdZPUjHhuYYWImybkw1kVwx1Hvm5aP2ONmhKU9cgVp5fg3lVfZ4=s900-c-k-c0x00ffffff-no-rj",
            "JT malgasy et français, magazines — Antananarivo",
        ),
        MgChannel(
            "KOLO TV", "UCNJOl4QkinZUXCaJRoJFGAw",
            "https://yt3.googleusercontent.com/ytc/AIdro_mDIkOUdDEY6LmeLvlkrr0JG6wqsqaAMiUPpi-vNYE=s900-c-k-c0x00ffffff-no-rj",
            "Musique, divertissement et culture malgache",
        ),
        MgChannel(
            "TV Plus Madagascar", "UCEGz7uOqsNyHZZTlPtzMBmw",
            "https://yt3.googleusercontent.com/09GTmzlhEU4PcQm9AETYTV5RT14q3O57Vj-dh5DRjpg5Ql_ej5raexORUd3-1L9Uz1FkUFxT8T0=s900-c-k-c0x00ffffff-no-rj",
            "Généraliste malgache — info et divertissement",
        ),
    )

    /** Page `/live` d'une chaîne YouTube → identifiant de la vidéo en cours. */
    private suspend fun ytVideoId(channelId: String): String? =
        http.getOrNull("https://www.youtube.com/channel/$channelId/live")?.text
            ?.let { Regex(""""videoId":"([\w-]{11})"""").find(it)?.groupValues?.get(1) }

    /** Manifeste HLS officiel d'un direct YouTube (`hlsManifestUrl` de la page watch). */
    private suspend fun ytHls(videoId: String): Pair<String, String?>? {
        val html = http.getOrNull("https://www.youtube.com/watch?v=$videoId")?.text ?: return null
        val hls = Regex(""""hlsManifestUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.replace("\\u0026", "&")?.replace("\\/", "/") ?: return null
        val title = Regex("""<title>([^<]{1,160})</title>""").find(html)?.groupValues?.get(1)
            ?.removeSuffix(" - YouTube")?.trim()
        return hls to title
    }

    // -----------------------------------------------------------------------
    // Accueil / recherche
    // -----------------------------------------------------------------------

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        if (page > 1) return PagedResult(emptyList(), page, false)
        val items = if (row.key == "madagascar") {
            mgChannels.map { item(it.name, "$mainUrl/ytmg/${it.channelId}", MediaType.OTHER, it.logo) }
        } else {
            channels().filter { sectionFor(it) == row.key }
                .map { item(it.name, "$mainUrl/channel/${it.key}", MediaType.OTHER, it.logo) }
        }
        return PagedResult(items, page, false)
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val mg = mgChannels
            .filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) || "madagascar" in q }
            .map { item(it.name, "$mainUrl/ytmg/${it.channelId}", MediaType.OTHER, it.logo) }
        val fr = channels().filter { it.name.lowercase().contains(q) }
            .map { item(it.name, "$mainUrl/channel/${it.key}", MediaType.OTHER, it.logo) }
        return mg + fr
    }

    // -----------------------------------------------------------------------
    // Fiche = chaîne, un « épisode » unique = le direct
    // -----------------------------------------------------------------------

    override suspend fun details(url: String): MediaDetails {
        if ("/ytmg/" in url) {
            val id = url.substringAfterLast('/')
            val ch = mgChannels.firstOrNull { it.channelId == id }
                ?: throw SourceException.ParseError("chaîne inconnue")
            val videoId = ytVideoId(id)
            val live = videoId?.let { ytHls(it) }
            return liveDetails(
                url = url,
                title = ch.name,
                poster = ch.logo,
                synopsis = live?.second?.let { "📺 En cours : $it" } ?: ch.description,
                payload = "yt:$id",
            )
        }
        val key = url.substringAfterLast('/')
        val ch = channels().firstOrNull { it.key == key }
            ?: throw SourceException.ParseError("chaîne absente de la playlist")
        return liveDetails(
            url = url,
            title = ch.name,
            poster = ch.logo,
            synopsis = "Chaîne en direct — ${ch.rawName}" + (ch.groups.firstOrNull()?.let { " · $it" } ?: ""),
            payload = "m3u:${ch.url}",
        )
    }

    private fun liveDetails(url: String, title: String, poster: String?, synopsis: String, payload: String): MediaDetails {
        val id = "tele:${url.substringAfterLast('/')}"
        return MediaDetails(
            id = id,
            url = url,
            title = title,
            synopsis = synopsis,
            posterUrl = poster,
            type = MediaType.OTHER,
            episodeCount = 1,
            seasons = listOf(
                Season(1, "Direct", listOf(Episode("$id:live", 1f, 1, "Direct", poster, null, payload)))
            ),
        )
    }

    override suspend fun loadLinks(data: dev.endlesssea.extensions.api.model.LinkRequest): List<VideoLink> {
        val payload = data.episode.data
        return when {
            payload.startsWith("yt:") -> {
                val channelId = payload.removePrefix("yt:")
                val videoId = ytVideoId(channelId)
                    ?: throw SourceException.VideoUnavailable("aucun direct en cours sur cette chaîne")
                val (hls, _) = ytHls(videoId)
                    ?: throw SourceException.VideoUnavailable("la vidéo en cours n'est pas un direct HLS")
                M3u8.variants(http, hls, "YouTube Live", referer = "https://www.youtube.com/")
            }
            payload.startsWith("m3u:") -> {
                val stream = payload.removePrefix("m3u:")
                M3u8.variants(http, stream, "Direct").ifEmpty {
                    listOf(VideoLink(stream, StreamType.HLS, server = "Direct"))
                }
            }
            else -> resolveServers(listOf(ServerEntry("Direct", payload, direct = true)))
        }
    }
}
