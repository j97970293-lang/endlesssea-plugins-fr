package dev.endlesssea.extensions.api

import dev.endlesssea.extensions.api.model.ExtensionInfo
import dev.endlesssea.extensions.api.model.ExtensionSetting
import dev.endlesssea.extensions.api.model.FilterSet
import dev.endlesssea.extensions.api.model.HomeCategory
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MainPageRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.VideoLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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

    /**
     * Catalog rows this extension offers ("genres", "trending", "planning"…).
     * The first entry acts as "main". Default: empty = single "main" row as before.
     * (Binary-compatible: implementations stay optional.)
     */
    suspend fun categories(): List<HomeCategory> = emptyList()

    /** Full-text + filtered search (spec §12). */
    suspend fun search(query: String, page: Int, filters: FilterSet): PagedResult<SearchItem>

    /** Details sheet (spec §13): synopsis, seasons, episodes, servers… */
    suspend fun load(url: String): MediaDetails

    /** All playable variants for one episode/movie, grouped by server (spec §14). */
    suspend fun loadLinks(data: LinkRequest): List<VideoLink>

    /**
     * Même chose que [loadLinks], mais **au fil de l'eau** : chaque lecteur
     * résolu est émis dès qu'il est prêt, sans attendre les plus lents.
     *
     * Pourquoi : une fiche agrège souvent 10 à 20 lecteurs ; il suffit d'un
     * hôte injoignable pour que le timeout de 20 s retienne toute la liste,
     * alors que la moitié des liens sont déjà jouables.
     *
     * Additif et **compatible binairement** : l'implémentation par défaut
     * rejoue simplement [loadLinks], donc les extensions existantes gardent
     * exactement leur comportement actuel.
     */
    fun loadLinksFlow(data: LinkRequest): Flow<VideoLink> = flow {
        loadLinks(data).forEach { emit(it) }
    }

    /** Shared host resolvers, tried on embed/iframe URLs before failing. */
    fun extractors(): List<ExtractorApi> = emptyList()

    /** Optional settings rendered in the app. */
    suspend fun settings(): List<ExtensionSetting> = emptyList()
}
