package dev.endlesssea.extensions.api.model

import dev.endlesssea.extensions.api.permission.ExtensionPermission

/** API major version the app implements. Extensions declare the one they target. */
const val API_VERSION: Int = 1

enum class MediaType { ANIME, MOVIE, SERIES, OVA, ONA, SPECIAL, OTHER }

enum class MediaStatus { ONGOING, COMPLETED, UPCOMING, HIATUS, CANCELLED, UNKNOWN }

/** Normalized video qualities (selection matrix, spec §5). */
enum class Quality(val label: String, val pixels: Int) {
    Q360("360p", 360), Q480("480p", 480), Q720("720p", 720),
    Q1080("1080p", 1080), Q1440("1440p", 1440), Q4K("2160p", 2160), UNKNOWN("?", 0);

    companion object {
        fun fromLabel(raw: String?): Quality {
            val p = raw?.filter { it.isDigit() }?.toIntOrNull() ?: return UNKNOWN
            return entries.minByOrNull { kotlin.math.abs(it.pixels - p) } ?: UNKNOWN
        }
    }
}

enum class AudioLang(val iso: String) { VOSTFR("vostfr"), VF("vf"), VO("vo"), MULTI("multi"), OTHER("?") }

enum class StreamType { DIRECT_FILE, HLS, DASH, TORRENT, EMBED }

enum class SubtitleFormat { SRT, ASS, SSA, VTT, UNKNOWN }

/** A catalog row item (home rows + search results). */
data class SearchItem(
    val id: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val url: String,                 // opaque key handed back to EsExtension.load()
    val posterUrl: String? = null,
    val type: MediaType,
    val year: Int? = null,
)

/** Page container: `page` starts at 1 (spec §2 pagination). */
data class PagedResult<T>(val items: List<T>, val page: Int, val hasNextPage: Boolean)

data class Season(val number: Int, val name: String? = null, val episodes: List<Episode> = emptyList())

data class Episode(
    val id: String,
    val number: Float,
    val season: Int? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val durationMs: Long? = null,
    val data: String,                // opaque payload handed back to loadLinks()
)

data class ServerRef(val id: String, val name: String)

data class MediaDetails(
    val id: String,
    val url: String,
    val title: String,
    val altTitles: List<String> = emptyList(),
    val synopsis: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val type: MediaType,
    val year: Int? = null,
    val status: MediaStatus = MediaStatus.UNKNOWN,
    val genres: List<String> = emptyList(),
    val studios: List<String> = emptyList(),
    val episodeCount: Int? = null,
    val durationMin: Int? = null,
    val seasons: List<Season> = emptyList(),
    val servers: List<ServerRef> = emptyList(),
    val languages: List<AudioLang> = emptyList(),
    val externalIds: Map<String, String> = emptyMap(),
) {
    /**
     * Champs enrichis ajoutés hors constructeur (compatibilité binaire apiVersion 1) :
     * les extensions existantes continuent de fonctionner sans recompilation ;
     * les nouvelles peuvent peupler personnages/doubleurs/bande-annonce.
     */
    var characters: List<CharacterCredit> = emptyList()
    var trailerUrl: String? = null
}

/** Personnage + voix du doublage (ex. AniList/fragment anime). */
data class CharacterCredit(
    val name: String,
    val role: String = "",            // « Principal », « Secondaire »…
    val voiceActor: String? = null,
    val voiceActorLang: String? = null, // « JA », « FR »…
    val imageUrl: String? = null,
)

data class SubtitleTrack(
    val url: String,
    val lang: String,                // BCP-47
    val label: String = lang,
    val format: SubtitleFormat,
)

data class VideoLink(
    val url: String,
    val streamType: StreamType,
    val quality: Quality = Quality.UNKNOWN,
    val server: String,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<SubtitleTrack> = emptyList(),
    val audioLang: AudioLang = AudioLang.OTHER,
)

data class FilterSet(
    val genres: List<String> = emptyList(),
    val years: IntRange? = null,
    val types: Set<MediaType> = emptySet(),
    val languages: Set<String> = emptySet(),
    val status: MediaStatus? = null,
    val qualities: Set<Quality> = emptySet(),
    val extra: Map<String, String> = emptyMap(),
)

/** A home row request: the app asks for a named row ("recently_added", "trending", …). */
data class MainPageRequest(val category: String, val page: Int)

/** Carries everything the app knows back to the extension when resolving links. */
data class LinkRequest(
    val episode: Episode,
    val mediaId: String,
    val preferredServer: ServerRef? = null,   // user's pick or auto-rule result (spec §14)
)

/** Declarative per-extension settings (rendered in the app). */
data class ExtensionSetting(
    val key: String,
    val title: String,
    val summary: String? = null,
    val type: Type,
    val defaultValue: String = "",
) { enum class Type { SWITCH, TEXT, PASSWORD, LIST } }

/** Identity/capability block, mirrors assets/extension.json (see docs/en/04). */
data class ExtensionInfo(
    val id: String,
    val name: String,
    val version: Int,
    val apiVersion: Int,
    val languages: List<String>,
    val types: Set<MediaType>,
    val permissions: Set<ExtensionPermission>,
    val author: String = "",
    val description: String = "",
    val nsfw: Boolean = false,
)
