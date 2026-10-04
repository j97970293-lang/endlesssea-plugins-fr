package dev.endlesssea.extensions.api

import dev.endlesssea.extensions.api.model.ExtensionInfo
import dev.endlesssea.extensions.api.model.ExtensionSetting
import dev.endlesssea.extensions.api.model.FilterSet
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MainPageRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.VideoLink

/**
 * The single contract every Endless Sea extension implements (docs/en/05-extension-api.md).
 *
 * Implementation notes for authors:
 *  - All functions are `suspend`; never block the calling thread.
 *  - Failures must be mapped to [dev.endlesssea.extensions.api.error.SourceException]
 *    subtypes — never leak raw stack traces to the UI.
 *  - Only implement flows for sources you are allowed to use (ToS / licenses).
 */
interface EsExtension {

    val info: ExtensionInfo

    /** Home rows ("recently_added", "trending", …). Default: empty. */
    suspend fun getMainPage(request: MainPageRequest): PagedResult<SearchItem> =
        PagedResult(emptyList(), request.page, hasNextPage = false)

    /** Full-text + filtered search (spec §12). */
    suspend fun search(query: String, page: Int, filters: FilterSet): PagedResult<SearchItem>

    /** Details sheet (spec §13): synopsis, seasons, episodes, servers… */
    suspend fun load(url: String): MediaDetails

    /** All playable variants for one episode/movie, grouped by server (spec §14). */
    suspend fun loadLinks(data: LinkRequest): List<VideoLink>

    /** Shared host resolvers, tried on embed/iframe URLs before failing. */
    fun extractors(): List<ExtractorApi> = emptyList()

    /** Optional settings rendered in the app. */
    suspend fun settings(): List<ExtensionSetting> = emptyList()
}
