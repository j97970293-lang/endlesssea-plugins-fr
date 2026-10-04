package dev.endlesssea.extensions.api

import dev.endlesssea.extensions.api.model.SubtitleTrack
import dev.endlesssea.extensions.api.model.VideoLink

/**
 * Resolver for one video host (embed/iframe page → direct variants).
 * Extensions register shared extractors via [EsExtension.extractors]; the loader tries
 * every extractor whose [hosts] match an unknown player URL (docs/en/05 §7).
 */
abstract class ExtractorApi {

    abstract val name: String

    /** Canonical host, e.g. "https://streamhost.example". */
    abstract val mainUrl: String

    /** Extra domains this extractor handles. */
    open val hosts: List<String> get() = listOf(mainUrl)

    abstract val requiresReferer: Boolean

    /**
     * Resolve [url] into playable [VideoLink]s (emitting via [callback]) and optional
     * subtitles ([subtitleCallback]). Wrap every failure into SourceException subtypes.
     */
    abstract suspend fun getUrl(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleTrack) -> Unit = {},
        callback: (VideoLink) -> Unit = {},
    ): List<VideoLink>
}
