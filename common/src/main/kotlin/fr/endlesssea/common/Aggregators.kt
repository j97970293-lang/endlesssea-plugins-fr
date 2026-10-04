package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.SubtitleTrack
import dev.endlesssea.extensions.api.model.VideoLink
import dev.endlesssea.extensions.api.ExtractorApi
import java.util.Base64

/**
 * Agrégateurs publics indexés par TMDB utilisés par plusieurs sites FR
 * (Zenix, Movix, WaveWatch, Xalaflix…). Factorisés ici : dans les plugins
 * CloudStream d'origine, chaque provider en embarquait sa propre copie.
 */
object Aggregators {

    /** apis.wavewatch.top/playerix.php — boutons `data-url` (base64) + langue. */
    suspend fun playerix(
        http: Http,
        tmdb: String,
        season: Int? = null,
        episode: Int? = null,
        m3u8Only: Boolean = false,
    ): List<ServerEntry> {
        val url = if (season != null && episode != null) {
            "https://apis.wavewatch.top/playerix.php?type=tv&id=$tmdb&season=$season&episode=$episode"
        } else {
            "https://apis.wavewatch.top/playerix.php?type=movie&id=$tmdb"
        }
        val html = http.getOrNull(url)?.text ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        val seen = HashSet<String>()
        Regex("""<button([^>]*data-url[^>]*)>(.*?)</button>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html).forEach { m ->
                val attrs = m.groupValues[1].replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")
                val content = m.groupValues[2]
                if (Regex("""data-alive="0"""").containsMatchIn(attrs)) return@forEach
                val dataUrl = Regex("""data-url="([^"]+)"""").find(attrs)?.groupValues?.get(1) ?: return@forEach
                val fmt = Regex("""data-fmt="([^"]*)"""").find(attrs)?.groupValues?.get(1) ?: "iframe"
                if (m3u8Only && fmt != "m3u8") return@forEach
                val lang = Regex("""class="lang"[^>]*>\s*([^<]+)""").find(content)?.groupValues?.get(1)
                    ?.trim()?.split(" ")?.lastOrNull()?.takeIf { it.isNotBlank() && it != "?" }
                val b64 = Regex("""u=([A-Za-z0-9+/=_-]{16,})""").find(dataUrl)?.groupValues?.get(1) ?: return@forEach
                val decoded = decodeB64(b64)?.takeIf { it.startsWith("http") } ?: return@forEach
                val host = Text.host(decoded).ifBlank { "Lecteur" }
                if (fmt == "m3u8") {
                    out += ServerEntry("$host · ${lang ?: "MULTI"}", decoded, Text.audioLang(lang), direct = true)
                } else {
                    val key = "$host|${lang ?: "?"}"
                    if (!seen.add(key)) return@forEach
                    out += ServerEntry(host + (lang?.let { " · $it" } ?: ""), decoded, Text.audioLang(lang))
                }
            }
        return out.take(40)
    }

    /** api.movix.men (ex api.movix.cash) — liens hébergeurs FR avec qualité. */
    suspend fun movix(http: Http, tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val path = if (season != null && episode != null) "tv/$tmdb?season=$season&episode=$episode" else "movie/$tmdb"
        val json = http.getOrNull("https://api.movix.men/api/tmdb/$path")?.asJsonOrNull()
            ?: http.getOrNull("https://api.movix.cash/api/tmdb/$path")?.asJsonOrNull()
            ?: return emptyList()
        val links = json["playerLinks"].list.ifEmpty { json["currentEpisode"]["playerLinks"].list }
        return links.mapNotNull { p ->
            val u = p.str("decodedUrl") ?: p.str("url") ?: return@mapNotNull null
            if (!u.startsWith("http")) return@mapNotNull null
            val lang = p.str("language")
            ServerEntry(
                name = (p.str("quality")?.take(40) ?: "Movix") + (lang?.let { " · ${it.uppercase()}" } ?: ""),
                url = u,
                lang = Text.audioLang(lang),
            )
        }
    }

    /** moviesapi.to — lecteur public TMDB (API interne /api/vidora). */
    suspend fun moviesApi(http: Http, tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val path = if (season != null && episode != null) "/v1/tv/$tmdb/$season/$episode" else "/v1/movie/$tmdb"
        val json = http.getOrNull(
            "https://moviesapi.to/api/vidora$path",
            mapOf(
                "x-player-key" to "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13",
                "Accept" to "application/json",
            ),
        )?.text ?: return emptyList()
        val url = Regex(""""url"\s*:\s*"(https?://[^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
        return listOf(ServerEntry("MoviesApi", url, direct = url.contains(".m3u8")))
    }

    private val wantedLang = Regex("""(vf|vff|vfq|truefrench|french|multi|vostfr|vo)""", RegexOption.IGNORE_CASE)
    private val unwantedLang = Regex(
        """(spanish|espagnol|german|allemand|italian|italien|portug|russ|turk|arab|hindi|korean|japonais\s*only)""",
        RegexOption.IGNORE_CASE,
    )

    private fun langOk(label: String?): Boolean {
        val l = label.orEmpty()
        if (l.isBlank()) return true
        if (unwantedLang.containsMatchIn(l) && !wantedLang.containsMatchIn(l)) return false
        return true
    }

    /**
     * Sources du réseau `api.movix.men` : purstream (HLS direct), wiflix,
     * frenchstream, cpasmal et liens directs — tous indexés par id TMDB.
     */
    suspend fun movixNetwork(
        http: Http,
        api: String,
        tmdb: String,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
    ): List<ServerEntry> {
        val site = api.removePrefix("https://api.").substringBefore('/')
        val h = mapOf("Accept" to "application/json", "Origin" to "https://$site", "Referer" to "https://$site/")
        val s = season ?: 1
        val e = episode ?: 1
        val out = ArrayList<ServerEntry>()

        // 1) Purstream — flux HLS directs
        val purUrl = if (isTv) "$api/purstream/tv/$tmdb/stream?season=$s&episode=$e"
        else "$api/purstream/movie/$tmdb/stream"
        http.getOrNull(purUrl, h)?.asJsonOrNull()?.get("sources")?.list?.forEach { src ->
            val u = src.str("url")?.takeIf { it.startsWith("http") } ?: return@forEach
            val n = src.str("name")
            if (!langOk(n)) return@forEach
            out += ServerEntry("Purstream · ${n ?: "stream"}", u, Text.audioLang(n), direct = true)
        }

        // 2) Wiflix / 3) FrenchStream / 4) CpasMal — maps langue → lecteurs
        suspend fun langMap(url: String, pick: (JsonNode) -> JsonNode, origin: String) {
            val root = http.getOrNull(url, h)?.asJsonOrNull() ?: return
            val node = pick(root)
            node.keys.filter { wantedLang.containsMatchIn(it) }.forEach { lang ->
                node[lang].list.forEach { p ->
                    val u = p.str("url")?.takeIf { it.startsWith("http") } ?: return@forEach
                    val label = p.str("name") ?: p.str("player") ?: Text.host(u)
                    out += ServerEntry("$origin · $label · ${lang.uppercase()}", u, Text.audioLang(lang))
                }
            }
        }

        langMap(
            if (isTv) "$api/wiflix/tv/$tmdb/$s" else "$api/wiflix/movie/$tmdb",
            { root ->
                if (isTv) {
                    root["episodes"][episode?.toString() ?: "1"].let {
                        if (it.isNull) root["episodes"][root["episodes"].keys.firstOrNull() ?: ""] else it
                    }
                } else {
                    if (root["players"].keys.isEmpty()) root["movie"] else root["players"]
                }
            },
            "Wiflix",
        )
        langMap(
            if (isTv) "$api/fstream/tv/$tmdb/season/$s" else "$api/fstream/movie/$tmdb",
            { root -> if (isTv) root["episodes"][e.toString()]["languages"] else root["links"] },
            "FrenchStream",
        )
        langMap(
            if (isTv) "$api/cpasmal/tv/$tmdb/$s/$e" else "$api/cpasmal/movie/$tmdb",
            { root -> root["links"] },
            "CpasMal",
        )

        // 5) liens directs
        val q = if (isTv) "?season=$s&episode=$e" else ""
        http.getOrNull("$api/links/${if (isTv) "tv" else "movie"}/$tmdb$q", h)?.asJsonOrNull()?.let { root ->
            val nodes = if (isTv) root["data"].list.flatMap { it["links"].list } else root["data"]["links"].list
            nodes.forEach { n ->
                val u = (n.string ?: n.str("url"))?.takeIf { it.startsWith("http") } ?: return@forEach
                out += ServerEntry("Movix · ${Text.host(u)}", u, direct = Text.streamType(u) != dev.endlesssea.extensions.api.model.StreamType.EMBED)
            }
        }

        return out.distinctBy { it.url }
    }

    /**
     * `apis.wavewatch.top/zeus.php` en Server-Sent Events : le serveur ferme le
     * flux après l'événement « done » (~10 s), la réponse arrive donc d'un bloc
     * et chaque ligne `data: {json}` porte une liste de `sources`.
     */
    suspend fun zeus(http: Http, tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val url = "https://apis.wavewatch.top/zeus.php?sse&type=${if (season != null) "tv" else "movie"}" +
            "&id=$tmdb&s=${season ?: 1}&e=${episode ?: 1}"
        val body = http.getOrNull(url)?.text ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        Regex("""data:\s*(\{.*?})\s*\n""", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
            Json.parseOrNull(m.groupValues[1])?.get("sources")?.list.orEmpty().forEach { s ->
                val u = s.str("url")?.takeIf { it.startsWith("http") } ?: return@forEach
                if (s["premium"].bool == true) return@forEach
                val lang = s.str("lang")
                val format = s.str("format")
                val direct = format in setOf("hls", "mp4", "dash") && s["iframe"].bool != true
                out += ServerEntry(
                    name = Text.host(u) + (lang?.let { " · ${it.uppercase()}" } ?: ""),
                    url = u, lang = Text.audioLang(lang), direct = direct,
                )
            }
        }
        return out
    }

    /** `apis.wavewatch.top/mouve.php?json=1` — HLS (parfois proxysés) + hébergeurs. */
    suspend fun mouve(http: Http, tmdb: String, season: Int? = null, episode: Int? = null): List<ServerEntry> {
        val url = "https://apis.wavewatch.top/mouve.php?json=1&type=${if (season != null) "tv" else "movie"}" +
            "&id=$tmdb&s=${season ?: 1}&e=${episode ?: 1}"
        val root = http.getOrNull(url)?.asJsonOrNull() ?: return emptyList()
        val out = ArrayList<ServerEntry>()
        val seen = HashSet<String>()
        root["streams"].list.forEach { s ->
            val u = s.str("url") ?: return@forEach
            if (s.str("format")?.lowercase() == "hls") {
                // flux parfois proxysé : mouve.php?ep=m3u8&url={réel}
                val real = Regex("""[?&]url=([^&]+)""").find(u)?.groupValues?.get(1)
                    ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: u
                if (real.startsWith("http") && ".m3u8" in real) {
                    out += ServerEntry(Text.host(real), real, direct = true)
                }
                return@forEach
            }
            if (!u.startsWith("http")) return@forEach
            val host = Text.host(u)
            if (!seen.add(host)) return@forEach // un seul miroir par hébergeur
            out += ServerEntry(host, u)
        }
        return out
    }

    /** Réseau Frembed : `links[]` → `/api/stream?…` → redirection vers l'hôte réel. */
    suspend fun frembedNetwork(
        http: Http,
        tmdb: String,
        isTv: Boolean,
        season: Int? = null,
        episode: Int? = null,
        origin: String = "https://frembed.surf",
    ): List<ServerEntry> {
        val contentPage = if (isTv) "$origin/series?id=$tmdb" else "$origin/films?id=$tmdb"
        val apiUrl = when {
            isTv -> "$origin/api/series?id=$tmdb&sa=${season ?: 1}&epi=${episode ?: 1}&idType=tmdb"
            else -> "$origin/api/films?id=$tmdb&idType=tmdb"
        }
        http.getOrNull(contentPage) // amorce les cookies de session
        val json = http.getOrNull(apiUrl, mapOf("Accept" to "application/json"), referer = "$origin/")
            ?.asJsonOrNull() ?: return emptyList()
        return json["links"].list.mapNotNull { l ->
            val raw = l.str("url") ?: return@mapNotNull null
            val streamUrl = if (raw.startsWith("http")) raw else origin + raw
            val hostName = l["host"].str("name") ?: l.str("label") ?: "Serveur"
            val lang = l.str("lang")
            ServerEntry(
                name = "Frembed · $hostName" + (lang?.let { " · ${it.uppercase()}" } ?: ""),
                url = streamUrl,
                lang = Text.audioLang(lang),
                referer = contentPage,
            )
        }
    }

    fun decodeB64(s: String): String? =
        runCatching { String(Base64.getUrlDecoder().decode(s)) }.getOrNull()
            ?: runCatching { String(Base64.getDecoder().decode(s)) }.getOrNull()
}

/** 1embed.cc — playlists HLS directes listées dans le payload Next.js. */
class OneEmbed(http: Http) : EsExtractor(http) {
    override val name = "1Embed"
    override val mainUrl = "https://1embed.cc"
    override val hosts = listOf("1embed.cc")
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url)?.text ?: return emptyList()
        val norm = html.replace("\\\"", "\"")
        val servers = LinkedHashMap<String, String>()
        Regex(""""name"\s*:\s*"([^"]+)"\s*,\s*"url"\s*:\s*"([^"]+\.m3u8)"""")
            .findAll(norm).forEach { servers[it.groupValues[2]] = it.groupValues[1] }
        if (servers.isEmpty()) {
            Regex(""""(/v/[a-zA-Z0-9_.-]+\.m3u8)"""").findAll(norm).forEach {
                servers.putIfAbsent(it.groupValues[1], "Serveur")
            }
        }
        return servers.flatMap { (path, serverName) ->
            val absolute = if (path.startsWith("http")) path else "$mainUrl$path"
            M3u8.variants(http, absolute, "$name · $serverName")
                .ifEmpty { listOf(VideoLink(absolute, StreamType.HLS, server = "$name · $serverName")) }
        }.onEach(callback)
    }
}

/** Liste des extracteurs natifs + ceux spécifiques aux agrégateurs. */
fun allExtractorsWithAggregators(http: Http): List<ExtractorApi> = Extractors.all(http) + OneEmbed(http)
