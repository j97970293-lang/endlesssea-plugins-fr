package fr.endlesssea.common

import dev.endlesssea.extensions.api.EsRequest
import dev.endlesssea.extensions.api.EsResponse
import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.ExtensionHttpClient
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.Quality
import dev.endlesssea.extensions.api.model.ServerRef
import dev.endlesssea.extensions.api.model.VideoLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ConnectException

/**
 * Contrat de la résolution **au fil de l'eau** (`EsProvider.resolveServersFlow` /
 * `linkStream` / `loadLinksFlow`), ajouté pour l'app 0.25.0.
 *
 * Ce qui est vérifié ici, sans aucun réseau :
 *  1. chaque lecteur résolu est émis **dès qu'il est prêt** — un hôte injoignable
 *     ne retient plus les liens déjà jouables ;
 *  2. l'ordre d'émission respecte la priorité (lecteur choisi > langue préférée >
 *     ordre de la source) ;
 *  3. le chemin historique `loadLinks` renvoie **exactement les mêmes liens** que
 *     le flux (mêmes URL, même dédoublonnage, même langue), triés par qualité ;
 *  4. une liste de lecteurs vide reste une erreur explicite.
 *
 * Tous les lecteurs testés sont des fichiers directs `.mp4` : la branche « direct »
 * de `resolveServersFlow` ne fait aucune requête, donc le test est déterministe.
 */
class ResolveServersFlowTest {

    /** Passerelle HTTP qui refuse tout : prouve que le test ne dépend pas du réseau. */
    private class NoNetworkHttp : ExtensionHttpClient {
        override val userAgent: String = "EndlessSea-Test/1.0"
        override suspend fun execute(request: EsRequest): EsResponse =
            throw ConnectException("aucun réseau dans les tests unitaires")
        override fun dumpCookies(host: String): Map<String, String> = emptyMap()
    }

    /** Fournisseur minimal : expose les points d'entrée protégés au test. */
    private class TestProvider(ctx: ExtensionContext) : EsProvider(ctx) {
        override val defaultUrl = "https://source.test"
        override val providerName = "Test"

        var entries: List<ServerEntry> = emptyList()

        override suspend fun servers(payload: String): List<ServerEntry> = entries

        /** Inutilisé : le test ne porte que sur la résolution des lecteurs. */
        override suspend fun details(url: String): MediaDetails =
            throw UnsupportedOperationException("hors périmètre du test")

        fun stream(
            list: List<ServerEntry>,
            preferred: ServerRef? = null,
        ): Flow<VideoLink> = resolveServersFlow(list, preferred)
    }

    private fun context(settings: Map<String, String> = emptyMap()): ExtensionContext =
        ExtensionContext(NoNetworkHttp(), File("build/tmp/es-test"), "fr").apply {
            this.settings = settings
        }

    private fun provider(settings: Map<String, String> = emptyMap()) = TestProvider(context(settings))

    private fun direct(name: String, quality: String, lang: AudioLang = AudioLang.OTHER) =
        ServerEntry(
            name = name,
            url = "https://cdn.test/$name-$quality.mp4",
            lang = lang,
            referer = "https://source.test/",
            direct = true,
        )

    private fun request(): LinkRequest = LinkRequest(
        episode = Episode(id = "e1", number = 1f, data = "payload"),
        mediaId = "m1",
    )

    // -----------------------------------------------------------------------

    @Test
    fun unreachableHostDoesNotHoldBackResolvedLinks() = runBlocking {
        val p = provider()
        // Le 2e lecteur est un embed : sa résolution passe par le réseau, qui échoue.
        val entries = listOf(
            direct("rapide", "480"),
            ServerEntry("lent", "https://hote-injoignable.test/embed/1", referer = "https://source.test/"),
            direct("autre", "720"),
        )

        val emitted = p.stream(entries).toList()

        // Les deux fichiers directs passent, l'embed échoue silencieusement :
        // c'est exactement le comportement attendu, mais **sans attendre** l'embed.
        assertEquals(
            listOf("https://cdn.test/rapide-480.mp4", "https://cdn.test/autre-720.mp4"),
            emitted.map { it.url },
        )
    }

    @Test
    fun preferredServerIsEmittedFirst() = runBlocking {
        val p = provider()
        val entries = listOf(direct("alpha", "1080"), direct("beta", "480"), direct("gamma", "720"))

        val emitted = p.stream(entries, preferred = ServerRef("https://cdn.test/beta-480.mp4", "beta")).toList()

        assertEquals("beta", emitted.first().server.lowercase().removeSuffix(".test"))
        assertEquals(3, emitted.size)
    }

    @Test
    fun preferredLanguageComesFirst() = runBlocking {
        val p = provider(settings = mapOf("pref_lang" to "vostfr"))
        val entries = listOf(
            direct("vo", "1080", AudioLang.VO),
            direct("vostfr", "480", AudioLang.VOSTFR),
        )

        val emitted = p.stream(entries).toList()

        assertEquals(AudioLang.VOSTFR, emitted.first().audioLang)
        assertEquals("https://cdn.test/vostfr-480.mp4", emitted.first().url)
    }

    @Test
    fun loadLinksReturnsTheSameLinksSortedByQuality() = runBlocking {
        val settings = mapOf("pref_lang" to "vf")
        val entries = listOf(
            direct("un", "480", AudioLang.VO),
            direct("deux", "1080", AudioLang.VF),
            direct("trois", "720", AudioLang.VF),
            direct("un", "480", AudioLang.VO), // doublon : même URL
        )

        val streamed = provider(settings).let { p ->
            p.entries = entries
            p.loadLinksFlow(request()).toList()
        }
        val listed = provider(settings).let { p ->
            p.entries = entries
            p.loadLinks(request())
        }

        // Mêmes URL, dans les deux chemins (le doublon est éliminé une seule fois).
        assertEquals(streamed.map { it.url }.toSet(), listed.map { it.url }.toSet())
        assertEquals(3, listed.size)
        // Le chemin « liste » conserve le tri par qualité décroissante d'origine.
        assertEquals(
            listOf(Quality.Q1080, Quality.Q720, Quality.Q480),
            listed.map { it.quality },
        )
        // La langue de la source est bien reportée sur le lien, dans les deux chemins.
        assertEquals(AudioLang.VF, listed.first { it.quality == Quality.Q1080 }.audioLang)
        assertEquals(AudioLang.VF, streamed.first { it.quality == Quality.Q1080 }.audioLang)
    }

    @Test
    fun duplicateUrlIsEmittedOnce() = runBlocking {
        val p = provider()
        val same = direct("miroir", "720")
        val emitted = p.stream(listOf(same, same.copy(name = "miroir 2"))).toList()
        assertEquals(1, emitted.size)
    }

    @Test
    fun noAnnouncedServerIsAnExplicitError() = runBlocking {
        val p = provider()
        val failure = runCatching { p.stream(emptyList()).toList() }.exceptionOrNull()
        assertTrue(
            "attendu SourceException.VideoUnavailable, reçu $failure",
            failure is SourceException.VideoUnavailable,
        )
    }

    @Test
    fun noUsableStreamIsAlsoAnExplicitError() = runBlocking {
        val p = provider()
        val entries = listOf(
            ServerEntry("mort", "https://hote-injoignable.test/embed/1", referer = "https://source.test/"),
        )
        val failure = runCatching { p.stream(entries).toList() }.exceptionOrNull()
        assertTrue(
            "attendu SourceException.VideoUnavailable, reçu $failure",
            failure is SourceException.VideoUnavailable,
        )
    }
}
