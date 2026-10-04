package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.Quality
import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.VideoLink

/**
 * Lecture des playlists HLS maîtresses : une variante = un [VideoLink] de qualité
 * connue (remplace `M3u8Helper.generateM3u8` de CloudStream).
 */
object M3u8 {

    private val streamInf = Regex("""#EXT-X-STREAM-INF:([^\n]*)\n([^\n#]+)""")

    /**
     * Si [masterUrl] est une playlist maîtresse, renvoie une variante par qualité ;
     * sinon renvoie le lien unique tel quel.
     */
    suspend fun variants(
        http: Http,
        masterUrl: String,
        serverName: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): List<VideoLink> {
        val base = VideoLink(
            url = masterUrl,
            streamType = StreamType.HLS,
            quality = Quality.UNKNOWN,
            server = serverName,
            headers = headers + listOfNotNull(referer?.let { "Referer" to it }).toMap(),
        )
        val body = runCatching { http.get(masterUrl, headers = headers, referer = referer).text }
            .getOrNull() ?: return listOf(base)
        if (!body.contains("#EXT-X-STREAM-INF")) return listOf(base)

        val out = streamInf.findAll(body).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val rel = m.groupValues[2].trim()
            if (rel.isEmpty()) return@mapNotNull null
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(attrs)?.groupValues?.get(1)
            val name = Regex("NAME=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
            VideoLink(
                url = resolve(masterUrl, rel),
                streamType = StreamType.HLS,
                quality = Text.quality(height ?: name),
                server = serverName,
                headers = base.headers,
            )
        }.sortedByDescending { it.quality.pixels }.toList()

        return out.ifEmpty { listOf(base) }
    }

    fun resolve(baseUrl: String, relative: String): String = when {
        relative.startsWith("http") -> relative
        relative.startsWith("//") -> "https:$relative"
        relative.startsWith("/") -> Regex("^(https?://[^/]+)").find(baseUrl)?.groupValues?.get(1).orEmpty() + relative
        else -> baseUrl.substringBeforeLast('/') + "/" + relative
    }
}
