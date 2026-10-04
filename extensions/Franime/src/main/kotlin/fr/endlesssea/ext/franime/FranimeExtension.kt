package fr.endlesssea.ext.franime

import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.Episode
import dev.endlesssea.extensions.api.model.LinkRequest
import dev.endlesssea.extensions.api.model.MediaDetails
import dev.endlesssea.extensions.api.model.MediaStatus
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.PagedResult
import dev.endlesssea.extensions.api.model.SearchItem
import dev.endlesssea.extensions.api.model.Season
import dev.endlesssea.extensions.api.model.VideoLink
import fr.endlesssea.common.EsProvider
import fr.endlesssea.common.HomeRow
import fr.endlesssea.common.JsonNode
import fr.endlesssea.common.ServerEntry
import fr.endlesssea.common.Text
import java.text.Normalizer
import java.util.Base64

/**
 * FRAnime (franime.fr) — portage **natif** Endless Sea.
 *
 * Le site est un Next.js derrière Cloudflare, mais tout passe par l'API
 * publique `https://api.franime.fr/api`, qui indexe les **mêmes identifiants
 * Kitsu** que `kitsu.io` :
 *
 *  - catalogue : listes Kitsu (tendances, en cours, populaires, notes, films) ;
 *  - recherche : index du catalogue FRAnime (`/api/animes`, titres FR inclus —
 *    `filter[text]` de Kitsu échoue sur les titres français), repli Kitsu ;
 *  - fiche : `/api/anime-seasons/{id}` (3 formes connues) + métadonnées Kitsu ;
 *  - lecture : `/api/anime/{id}/{s}/{e}/{vo|vf}/{index}` → URL d'embed, ou une
 *    URL `watch2` chiffrée (base64 → hex → XOR 1 octet, clé brute-forcée).
 *
 * Charge utile d'épisode : `{animeId}|{saisonIndex}|{episodeIndex}`.
 */
class FranimeExtension(ctx: ExtensionContext) : EsProvider(ctx) {

    override val defaultUrl = "https://franime.fr"
    override val providerName = "FRAnime"
    override val extensionId = "fr.endlesssea.ext.franime"
    override val versionCode = 14
    override val descriptionText = "Animes VF et VOSTFR, catalogue FRAnime et métadonnées Kitsu."
    override val supportedTypes = setOf(MediaType.ANIME, MediaType.MOVIE)

    private val kitsu = "https://kitsu.io/api/edge"
    private fun apiBase() = "https://api." + Text.host(mainUrl).removePrefix("www.") + "/api"
    private val apiHeaders get() = mapOf("Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/")
    private val kitsuHeaders = mapOf("Accept" to "application/vnd.api+json")

    override val homeRows = listOf(
        HomeRow("trending", "Tendances", "trending"),
        HomeRow("airing", "En cours de diffusion", "airing"),
        HomeRow("popular", "Les plus populaires", "popular"),
        HomeRow("rated", "Les mieux notés", "rated"),
        HomeRow("movies", "Films d'animation", "movies"),
    )

    // -----------------------------------------------------------------------
    // Catalogue (Kitsu)
    // -----------------------------------------------------------------------

    private suspend fun kitsuList(url: String): List<JsonNode> =
        http.getOrNull(url, kitsuHeaders)?.asJsonOrNull()?.get("data")?.list.orEmpty()

    private fun kitsuCard(node: JsonNode): SearchItem? {
        val id = node.str("id") ?: return null
        val a = node["attributes"]
        val title = a["titles"].str("en") ?: a["titles"].str("en_jp") ?: a.str("canonicalTitle") ?: return null
        return item(
            title, "$mainUrl/anime/$id",
            if (a.str("subtype") == "movie") MediaType.MOVIE else MediaType.ANIME,
            a["posterImage"].let { it.str("large") ?: it.str("medium") ?: it.str("original") },
            a.str("startDate")?.substringBefore("-")?.toIntOrNull(),
            altTitles = listOfNotNull(a["titles"].str("en_jp")?.takeIf { it != title }),
        )
    }

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> {
        val offset = (page - 1) * 20
        val url = when (row.path) {
            "trending" -> {
                if (page > 1) return PagedResult(emptyList(), page, false)
                "$kitsu/trending/anime?limit=20"
            }
            "airing" -> "$kitsu/anime?filter%5Bstatus%5D=current&sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            "popular" -> "$kitsu/anime?sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            "rated" -> "$kitsu/anime?sort=-averageRating&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
            else -> "$kitsu/anime?filter%5Bsubtype%5D=movie&sort=-userCount&page%5Blimit%5D=20&page%5Boffset%5D=$offset"
        }
        val nodes = kitsuList(url)
        return PagedResult(nodes.mapNotNull { kitsuCard(it) }, page, nodes.size >= 20)
    }

    // -----------------------------------------------------------------------
    // Recherche : index du catalogue FRAnime (titres FR) puis Kitsu
    // -----------------------------------------------------------------------

    private class CatalogEntry(val id: String, val fr: String?, val en: String?, val poster: String?)

    @Volatile private var catalogIndex: List<CatalogEntry>? = null
    @Volatile private var catalogIndexAt = 0L

    private fun normalize(s: String): String {
        val low = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
        return Regex("\\s+").replace(low, " ").trim()
    }

    /** `/api/animes` téléchargé une fois toutes les 12 h, réduit à id + titres. */
    private suspend fun catalog(): List<CatalogEntry> {
        val now = System.currentTimeMillis()
        catalogIndex?.let { if (now - catalogIndexAt < 12 * 3600_000L) return it }
        val root = http.getOrNull("${apiBase()}/animes", apiHeaders)?.asJsonOrNull() ?: return emptyList()
        val entries = root.list.mapNotNull { n ->
            val id = n.str("id") ?: return@mapNotNull null
            val fr = n["titles"].str("fr_fr")
            val en = n["titles"].str("en") ?: n["titles"].str("en_us") ?: n.str("titleO")
            if (fr == null && en == null) return@mapNotNull null
            CatalogEntry(id, fr, en, n.str("affiche"))
        }
        catalogIndex = entries
        catalogIndexAt = now
        return entries
    }

    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> {
        if (page > 1) return emptyList()
        val q = normalize(query)
        val words = q.split(" ").filter { it.length > 1 }
        if (words.isNotEmpty()) {
            val hits = catalog().mapNotNull { e ->
                var best = 0
                listOfNotNull(e.fr, e.en).map(::normalize).forEach { c ->
                    if (c.isEmpty() || !words.all { w -> c.contains(w) }) return@forEach
                    val score = when {
                        c == q -> 100
                        c.startsWith(q) -> 80
                        else -> 40
                    } + (if (e.fr != null && c == normalize(e.fr)) 20 else 0)
                    if (score > best) best = score
                }
                if (best == 0) null else (e to best)
            }.sortedByDescending { it.second }.take(25)
            if (hits.isNotEmpty()) {
                return hits.map { (e, _) ->
                    val display = e.fr ?: e.en.orEmpty()
                    item(
                        display, "$mainUrl/anime/${e.id}", MediaType.ANIME, e.poster,
                        altTitles = listOfNotNull(e.en?.takeIf { it != display }),
                    )
                }
            }
        }
        // Repli : recherche Kitsu (titres anglais / romaji)
        return kitsuList("$kitsu/anime?filter%5Btext%5D=${java.net.URLEncoder.encode(query, "UTF-8")}&page%5Blimit%5D=20")
            .mapNotNull { kitsuCard(it) }
    }

    // -----------------------------------------------------------------------
    // Fiche
    // -----------------------------------------------------------------------

    private class FrSeason(val index: Int, val number: Int, val episodes: List<JsonNode>)

    /** `/api/anime-seasons/{id}` : forme principale + deux replis connus. */
    private suspend fun fetchSeasons(animeId: String): List<FrSeason> {
        val root = http.getOrNull("${apiBase()}/anime-seasons/$animeId", apiHeaders)?.asJsonOrNull()
            ?: return emptyList()
        val out = ArrayList<FrSeason>()
        root["episodeDetails"].list.forEachIndexed { i, s ->
            val eps = s["episodes"].list
            if (eps.isNotEmpty()) out += FrSeason(i, s["seasonNumber"].int ?: (i + 1), eps)
        }
        if (out.isEmpty()) {
            root["episodes"].list.takeIf { it.isNotEmpty() }?.let { out += FrSeason(0, 1, it) }
        }
        if (out.isEmpty()) {
            root["saisons"].list.forEachIndexed { i, s ->
                val eps = s["episodes"].list
                if (eps.isNotEmpty()) out += FrSeason(i, i + 1, eps)
            }
        }
        return out
    }

    override suspend fun load(url: String): MediaDetails {
        val id = url.trimEnd('/').substringAfterLast('/')
        val seasons = fetchSeasons(id)
        if (seasons.isEmpty()) throw SourceException.VideoUnavailable("anime indisponible sur FRAnime")

        val attr = http.getOrNull("$kitsu/anime/$id", kitsuHeaders)?.asJsonOrNull()?.get("data")?.get("attributes")
        val title = attr?.let { it["titles"].str("en") ?: it["titles"].str("en_jp") ?: it.str("canonicalTitle") }
            ?: "Anime $id"
        val poster = attr?.get("posterImage")?.let { it.str("large") ?: it.str("medium") }
        val backdrop = attr?.get("coverImage")?.let { it.str("large") ?: it.str("original") }
        val plot = attr?.str("synopsis")
        val year = attr?.str("startDate")?.substringBefore("-")?.toIntOrNull()
        val status = when (attr?.str("status")) {
            "current" -> MediaStatus.ONGOING
            "finished" -> MediaStatus.COMPLETED
            else -> MediaStatus.UNKNOWN
        }
        val isMovie = attr?.str("subtype") == "movie" ||
            (seasons.size == 1 && seasons[0].episodes.size == 1)

        if (isMovie) {
            return movieDetails(
                url = url, title = title, payload = "$id|${seasons[0].index}|0",
                poster = poster, synopsis = plot, year = year, type = MediaType.MOVIE, banner = backdrop,
            )
        }

        val mediaId = Text.idOf("fran", url)
        val outSeasons = seasons.map { s ->
            Season(
                s.number, "Saison ${s.number}",
                s.episodes.mapIndexed { epIdx, ep ->
                    val num = ep["number"].double?.toInt() ?: (epIdx + 1)
                    Episode(
                        id = "$mediaId:s${s.number}e$num", number = num.toFloat(), season = s.number,
                        title = ep.str("title")?.takeIf { !it.startsWith("Épisode") },
                        thumbnailUrl = ep.str("thumbnail") ?: poster,
                        data = "$id|${s.index}|$epIdx",
                    )
                },
            )
        }

        return MediaDetails(
            id = mediaId, url = url, title = Text.decodeHtml(title),
            synopsis = plot?.let { Text.stripHtml(it) }, posterUrl = poster, bannerUrl = backdrop,
            type = MediaType.ANIME, year = year, status = status,
            episodeCount = outSeasons.sumOf { it.episodes.size }, seasons = outSeasons,
            languages = listOf(AudioLang.VOSTFR, AudioLang.VF),
        )
    }

    // -----------------------------------------------------------------------
    // Lecture
    // -----------------------------------------------------------------------

    /**
     * Décode un paramètre d'URL `watch2` : base64 → chaîne hexadécimale → XOR
     * sur un octet. La clé 1 est la plus fréquente, les 255 autres sont testées.
     */
    private fun decodeWatch2(watchUrl: String): String? {
        val query = watchUrl.substringAfter("?", "")
        if (query.isBlank()) return null
        for (pair in query.split("&")) {
            val raw = pair.substringAfter("=", "")
            if (raw.isBlank()) continue
            val b64 = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            val hex = runCatching { String(Base64.getDecoder().decode(b64), Charsets.ISO_8859_1) }.getOrNull() ?: continue
            if (hex.length < 8 || hex.length % 2 != 0) continue
            if (!hex.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) continue
            val bytes = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            val keys = sequenceOf(1) + (0..255).asSequence().filter { it != 1 }
            for (key in keys) {
                val sb = StringBuilder(bytes.size)
                var printable = true
                for (b in bytes) {
                    val c = (b.toInt() and 0xFF) xor key
                    if (c < 0x20 || c > 0x7E) { printable = false; break }
                    sb.append(c.toChar())
                }
                if (!printable) continue
                val candidate = sb.toString()
                if (candidate.startsWith("http") && "franime.fr" !in candidate) return candidate
            }
        }
        return null
    }

    private suspend fun resolvePlayer(animeId: String, s: Int, e: Int, lang: String, index: Int): String? {
        val body = http.getOrNull("${apiBase()}/anime/$animeId/$s/$e/$lang/$index", apiHeaders)?.text ?: return null
        var target = body.trim().trim('"')
        if (target.startsWith("{")) {
            target = Regex(""""(?:url|iframe)"\s*:\s*"([^"]+)"""")
                .find(target)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
        }
        if (!target.startsWith("http")) return null
        if ("/watch2" !in target) return target
        // L'URL watch2 contient parfois déjà l'embed ; sinon on la suit (302).
        return decodeWatch2(target)
            ?: http.getOrNull(target, apiHeaders)?.url?.let { decodeWatch2(it) }
    }

    override suspend fun servers(payload: String): List<ServerEntry> {
        val parts = payload.split("|")
        val animeId = parts.getOrNull(0) ?: return emptyList()
        val s = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val e = parts.getOrNull(2)?.toIntOrNull() ?: 0

        val out = LinkedHashMap<String, ServerEntry>()
        for ((lang, label) in listOf("vo" to "VOSTFR", "vf" to "VF")) {
            for (i in 0..4) {
                val url = resolvePlayer(animeId, s, e, lang, i) ?: continue
                out.putIfAbsent(
                    url,
                    ServerEntry(
                        "${Text.host(url).removePrefix("www.")} · $label", url,
                        if (lang == "vf") AudioLang.VF else AudioLang.VOSTFR,
                        referer = "$mainUrl/",
                    ),
                )
            }
        }
        return out.values.toList()
    }

    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> {
        val entries = servers(data.episode.data)
        if (entries.isEmpty()) throw SourceException.VideoUnavailable("aucun lecteur pour cet épisode")
        return resolveServers(entries, data.preferredServer)
    }
}
