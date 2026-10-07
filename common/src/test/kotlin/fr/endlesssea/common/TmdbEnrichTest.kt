package fr.endlesssea.common

import dev.endlesssea.extensions.api.EsRequest
import dev.endlesssea.extensions.api.EsResponse
import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.ExtensionHttpClient
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contrat de [Tmdb.enrich] sur le champ `studios` (app 0.7.0+, affiché en
 * pastille par `DetailsScreen`), et surtout sur l'**ordre des affectations**.
 *
 * Le piège : `MediaDetails.studios` est un `val` de constructeur (donc
 * `.copy()`), alors que `rating`, `ratingCount`, `characters` et `trailerUrl`
 * sont des `var` **hors constructeur** que `copy()` ne recopie pas. Copier
 * après avoir rempli ces champs les ferait disparaître silencieusement — la
 * fiche perdrait sa note, sa distribution et sa bande-annonce. Ces tests
 * existent pour que cette régression ne puisse pas passer inaperçue.
 *
 * Aucun réseau : la réponse TMDB est servie par une fausse passerelle HTTP.
 */
class TmdbEnrichTest {

    /** Sert un corps JSON fixe, quel que soit l'URL demandée. */
    private class CannedHttp(private val body: String) : ExtensionHttpClient {
        override val userAgent: String = "EndlessSea-Test/1.0"
        var calls = 0
        override suspend fun execute(request: EsRequest): EsResponse {
            calls++
            return EsResponse(200, body, mapOf("content-type" to "application/json"), request.url)
        }
        override fun dumpCookies(host: String): Map<String, String> = emptyMap()
    }

    /** `cacheDir` null → `Cache` inopérant, la requête passe à chaque fois. */
    private fun http(body: String) = Http(
        ExtensionContext(CannedHttp(body), File("build/tmp/es-tmdb"), "fr").apply { cacheDir = null }
    )

    private fun details() = MediaDetails(
        id = "x:movie:1", url = "x:movie:1", title = "Titre", type = MediaType.MOVIE,
    )

    private fun tmdbJson(networks: String, companies: String) = """
        {
          "vote_average": 7.5,
          "vote_count": 1234,
          "networks": [$networks],
          "production_companies": [$companies],
          "videos": { "results": [
            { "site": "YouTube", "type": "Trailer", "iso_639_1": "fr", "key": "CLE123" }
          ] },
          "credits": { "cast": [
            { "name": "Untel", "character": "Héros", "profile_path": "/p.jpg" }
          ] }
        }
    """.trimIndent()

    // -----------------------------------------------------------------------

    @Test
    fun seriesUseNetworksAndKeepEveryOtherEnrichedField() = runBlocking {
        val body = tmdbJson(
            networks = """{ "name": "Fuji TV" }, { "name": "Tokyo MX" }""",
            companies = """{ "name": "Toei Animation" }""",
        )
        val out = Tmdb.enrich(http(body), details(), "1", isTv = true)

        // Le diffuseur prime sur la société de production pour une série.
        assertEquals(listOf("Fuji TV", "Tokyo MX"), out.studios)

        // Et rien de ce qui est hors constructeur n'a été perdu par le copy().
        assertEquals(7.5, out.rating!!, 0.0001)
        assertEquals(1234, out.ratingCount)
        assertEquals("https://www.youtube.com/watch?v=CLE123", out.trailerUrl)
        assertEquals(1, out.characters.size)
        assertEquals("Héros", out.characters.first().name)
        assertEquals("Untel", out.characters.first().role)
    }

    @Test
    fun moviesFallBackToProductionCompanies() = runBlocking {
        val body = tmdbJson(networks = "", companies = """{ "name": "Warner Bros." }""")
        val out = Tmdb.enrich(http(body), details(), "1", isTv = false)

        assertEquals(listOf("Warner Bros."), out.studios)
        assertEquals(7.5, out.rating!!, 0.0001)
    }

    @Test
    fun blankNamesAreDropped() = runBlocking {
        val body = tmdbJson(
            networks = """{ "name": "   " }, { "name": "Canal+" }""",
            companies = "",
        )
        val out = Tmdb.enrich(http(body), details(), "1", isTv = true)
        assertEquals(listOf("Canal+"), out.studios)
    }

    @Test
    fun missingStudiosLeaveTheDetailsUntouched() = runBlocking {
        val body = tmdbJson(networks = "", companies = "")
        val out = Tmdb.enrich(http(body), details(), "1", isTv = false)

        assertTrue(out.studios.isEmpty())
        // La fiche reste enrichie sur tout le reste.
        assertEquals(7.5, out.rating!!, 0.0001)
        assertEquals(1, out.characters.size)
    }

    @Test
    fun studiosAreCappedAtFive() = runBlocking {
        val companies = (1..9).joinToString(", ") { """{ "name": "Studio $it" }""" }
        val out = Tmdb.enrich(http(tmdbJson("", companies)), details(), "1", isTv = false)
        assertEquals(5, out.studios.size)
        assertEquals("Studio 1", out.studios.first())
    }

    @Test
    fun unreachableTmdbKeepsTheOriginalDetails() = runBlocking {
        // Corps non JSON : `Json.parseOrNull` renvoie null, `enrich` rend la fiche telle quelle.
        val out = Tmdb.enrich(http("<html>indisponible</html>"), details(), "1", isTv = false)
        assertTrue(out.studios.isEmpty())
        assertNull(out.rating)
        assertTrue(out.characters.isEmpty())
    }
}
