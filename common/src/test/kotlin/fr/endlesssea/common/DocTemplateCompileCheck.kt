package fr.endlesssea.common

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.VideoLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * PREUVE DE COMPILATION du gabarit de docs/ARCHITECTURE.md.
 *
 * Ce fichier n'est pas un test : il existe pour que le gabarit documenté soit
 * vérifié par le compilateur à chaque build. Si quelqu'un rend `load` ou
 * `loadLinks` non finale, ou renomme `details`/`defaultUrl`, ce fichier cesse
 * de compiler et la doc redevient fausse — donc visible.
 */
class MonSiteExtension(ctx: ExtensionContext) : EsProvider(ctx) {
    override val defaultUrl = "https://monsite.tld"
    override val providerName = "Mon Site"
    override val homeRows = listOf(HomeRow("films", "Films", "/films/"))

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> =
        PagedResult(emptyList(), page, false)

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> = emptyList()

    override suspend fun details(url: String): MediaDetails =
        MediaDetails(id = url, url = url, title = "Titre", type = MediaType.MOVIE)

    override suspend fun servers(payload: String): List<ServerEntry> = emptyList()
}

/** Variante « résolution personnalisée » du même document. */
class MonAutreSiteExtension(ctx: ExtensionContext) : EsProvider(ctx) {
    override val defaultUrl = "https://monsite.tld"
    override val providerName = "Mon Autre Site"

    override suspend fun details(url: String): MediaDetails =
        MediaDetails(id = url, url = url, title = "Titre", type = MediaType.MOVIE)

    override fun linkStream(data: LinkRequest): Flow<VideoLink> = flow {
        emitAll(
            firstNonEmpty(
                { resolveServersFlow(servers(data.episode.data), data.preferredServer) },
                { linksBlocking { secours(data) } },
            )
        )
    }

    private suspend fun secours(data: LinkRequest): List<VideoLink> = emptyList()
}
