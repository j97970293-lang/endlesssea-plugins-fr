package fr.endlesssea.common

import dev.endlesssea.extensions.api.ExtractorApi
import dev.endlesssea.extensions.api.error.SourceException
import dev.endlesssea.extensions.api.model.Quality
import dev.endlesssea.extensions.api.model.StreamType
import dev.endlesssea.extensions.api.model.SubtitleFormat
import dev.endlesssea.extensions.api.model.SubtitleTrack
import dev.endlesssea.extensions.api.model.VideoLink

/**
 * Extracteurs d'hébergeurs **natifs** Endless Sea.
 *
 * Remplace intégralement `com.lagradost.cloudstream3.utils.loadExtractor` et les
 * extracteurs embarqués de CloudStream : chaque hébergeur est ici une implémentation
 * de [ExtractorApi] (contrat Endless Sea), déclarée par l'extension via
 * `EsExtension.extractors()` et appelée par [Extractors.resolve].
 */
abstract class EsExtractor(protected val http: Http) : ExtractorApi() {

    override val requiresReferer: Boolean get() = true

    /** Raccourci : construit un lien en déduisant type de flux et qualité. */
    protected fun link(
        url: String,
        quality: Quality = Quality.UNKNOWN,
        referer: String? = null,
        serverName: String = name,
        extraHeaders: Map<String, String> = emptyMap(),
    ) = VideoLink(
        url = url,
        streamType = Text.streamType(url),
        quality = if (quality == Quality.UNKNOWN) Text.quality(url) else quality,
        server = Text.serverLabel(serverName),
        headers = buildMap {
            put("User-Agent", http.userAgent)
            if (referer != null) put("Referer", referer)
            putAll(extraHeaders)
        },
    )

    /** Heuristique commune : déballe le packer puis ramasse m3u8/mp4 dans la page. */
    protected suspend fun genericSniff(html: String, pageUrl: String): List<VideoLink> {
        val expanded = html + "\n" + Unpacker.unpackAll(html)
        val found = LinkedHashSet<String>()

        Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").findAll(expanded)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""file\s*:\s*["'](https?://[^"']+)["']""").findAll(expanded)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""src\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""").findAll(expanded)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""").findAll(expanded)
            .forEach { found.add(it.groupValues[1]) }
        Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""").findAll(expanded)
            .forEach { found.add(Text.fixUrl(it.groupValues[1], pageUrl)) }
        // Lecteurs « videojs XOR » (fsvid.lol, vidzy.cc…) : }("<base64>")
        Regex("""\}\("([A-Za-z0-9+/=]{40,})"\)""").findAll(expanded).forEach { m ->
            decodeXorSource(m.groupValues[1], Text.host(pageUrl))?.let { found.add(it) }
        }

        return found.filterNot { junk.containsMatchIn(it) }
            .flatMap { u ->
                if (u.contains(".m3u8")) M3u8.variants(http, u, name, pageUrl)
                else listOf(link(u, referer = pageUrl))
            }
    }

    companion object {
        private val junk = Regex(
            """(?i)(\.jpe?g|\.png|\.gif|\.webp|\.svg|\.vtt|\.srt|/ads?/|adserve|adservice|""" +
                """adsystem|doubleclick|banner|/pixel|analytics|/thumb|poster|trailer)"""
        )

        /**
         * Décodeur « videojs XOR » utilisé par fsvid.lol, vidzy.cc et plusieurs
         * lecteurs FR : base64 inversé puis XOR d'une clé dérivée du nom d'hôte.
         */
        fun decodeXorSource(b64: String, hostname: String): String? = runCatching {
            val h = hostname.sumOf { it.code } and 0xFF
            val a = java.util.Base64.getDecoder().decode(b64).reversed()
            val out = StringBuilder()
            for (i in a.indices) {
                val kk = (0x3d + i * 89 + h) and 0xFF
                out.append(((a[i].toInt() and 0xFF) xor kk).toChar())
            }
            out.toString().takeIf { it.startsWith("http") }
        }.getOrNull()
    }
}

object Extractors {

    /** Tous les extracteurs natifs connus, instanciés pour une extension donnée. */
    fun all(http: Http): List<EsExtractor> = listOf(
        Uqload(http), Vidmoly(http), Voe(http), DoodStream(http), Sibnet(http),
        SendVid(http), FileMoon(http), StreamWishFamily(http), Streamtape(http),
        MixDrop(http), Vidzy(http), Vidara(http), Upstream(http), Okru(http),
        Dailymotion(http), MyviTop(http), GenericHost(http),
    )

    /**
     * Résout une URL d'embed quelconque (équivalent natif de `loadExtractor`).
     * Essaie l'extracteur dont l'hôte correspond, puis le renifleur générique.
     */
    suspend fun resolve(
        http: Http,
        url: String,
        referer: String? = null,
        serverName: String? = null,
        subtitleCallback: (SubtitleTrack) -> Unit = {},
    ): List<VideoLink> {
        if (url.isBlank()) return emptyList()
        val clean = Text.fixUrl(url, referer?.let { Regex("^https?://[^/]+").find(it)?.value } ?: "https://")
        val host = Text.host(clean).removePrefix("www.")

        val candidates = all(http).filter { ex ->
            ex.hosts.any { h -> host.contains(h.substringAfter("://").removePrefix("www."), true) }
        }
        val chain = candidates.ifEmpty { listOf(GenericHost(http)) }

        for (extractor in chain) {
            val links = runCatching {
                extractor.getUrl(clean, referer, subtitleCallback) {}
            }.getOrDefault(emptyList())
            if (links.isNotEmpty()) {
                return if (serverName == null) links
                else links.map { it.copy(server = Text.serverLabel(serverName)) }
            }
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------------------
// Hébergeurs
// ---------------------------------------------------------------------------

/** Uqload — `sources: ["https://…/v.mp4"]`, lecture avec Referer obligatoire. */
class Uqload(http: Http) : EsExtractor(http) {
    override val name = "Uqload"
    override val mainUrl = "https://uqload.net"
    override val hosts = listOf("uqload.net", "uqload.com", "uqload.co", "uqload.io", "uqload.to", "uqload.cx")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer ?: mainUrl)?.text ?: return emptyList()
        val src = Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Unpacker.unpack(html)?.let {
                Regex("""sources\s*:\s*\[\s*["'](https?://[^"']+)["']""").find(it)?.groupValues?.get(1)
            }
            ?: return genericSniff(html, url)
        return listOf(link(src, referer = url).copy(streamType = StreamType.DIRECT_FILE))
            .also { it.forEach(callback) }
    }
}

/** Vidmoly — playlist HLS dans `sources:[{file:"…m3u8"}]` + pistes de sous-titres. */
class Vidmoly(http: Http) : EsExtractor(http) {
    override val name = "Vidmoly"
    override val mainUrl = "https://vidmoly.to"
    override val hosts = listOf("vidmoly.to", "vidmoly.me", "vidmoly.net")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer ?: mainUrl)?.text ?: return emptyList()
        Regex("""\{file:"([^"]+)",label:"([^"]*)",kind:"captions"""").findAll(html).forEach { m ->
            subtitleCallback(
                SubtitleTrack(m.groupValues[1], "fr", m.groupValues[2], SubtitleFormat.VTT)
            )
        }
        val master = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return genericSniff(html, url)
        return M3u8.variants(http, master, name, referer = url, headers = mapOf("Origin" to mainUrl))
            .onEach(callback)
    }
}

/** Voe — redirection JS puis source HLS (parfois encodée en base64). */
class Voe(http: Http) : EsExtractor(http) {
    override val name = "Voe"
    override val mainUrl = "https://voe.sx"
    override val hosts = listOf("voe.sx", "voe-un-block.com", "voeunblk.com", "voeunblock.com", "donaldlineelse.com", "maxfinishseveral.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        var html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        // Voe redirige souvent vers un miroir via window.location.href = '…'
        Regex("""window\.location\.href\s*=\s*'([^']+)'""").find(html)?.groupValues?.get(1)?.let { next ->
            html = http.getOrNull(next, referer = url)?.text ?: html
        }
        val direct = Regex("""(?:'hls'|"hls"|'source'|"source")\s*:\s*'?"?([^"',]+)""")
            .find(html)?.groupValues?.get(1)?.trim()
        val resolved = when {
            direct == null -> null
            direct.startsWith("http") -> direct
            else -> runCatching { String(java.util.Base64.getDecoder().decode(direct)) }
                .getOrNull()?.takeIf { it.startsWith("http") }
        }
        val target = resolved ?: return genericSniff(html, url)
        return M3u8.variants(http, target, name, referer = url).onEach(callback)
    }
}

/** DoodStream & clones — jeton `pass_md5` + suffixe aléatoire. */
class DoodStream(http: Http) : EsExtractor(http) {
    override val name = "DoodStream"
    override val mainUrl = "https://dood.li"
    override val hosts = listOf(
        "dood.li", "dood.to", "dood.so", "dood.ws", "dood.watch", "dood.yt", "dood.re",
        "doodstream.com", "dooood.com", "ds2play.com", "d0o0d.com", "d000d.com", "vide0.net",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val embed = url.replace("/d/", "/e/").replace("/f/", "/e/")
        val res = http.getOrNull(embed, referer = referer) ?: return emptyList()
        val base = Regex("^(https?://[^/]+)").find(res.url)?.value ?: mainUrl
        val passPath = Regex("""(/pass_md5/[^'"]+)""").find(res.text)?.groupValues?.get(1)
            ?: return emptyList()
        val token = passPath.substringAfterLast('/')
        val md5 = http.getOrNull("$base$passPath", referer = embed)?.text ?: return emptyList()
        val rnd = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val final = "$md5$rnd?token=$token&expiry=${System.currentTimeMillis()}"
        return listOf(
            link(final, Quality.Q720, referer = base, extraHeaders = mapOf("Origin" to base))
                .copy(streamType = StreamType.DIRECT_FILE)
        ).onEach(callback)
    }
}

/** Sibnet — `player.src([{src: "/v/xxx.mp4"}])`, chemin relatif. */
class Sibnet(http: Http) : EsExtractor(http) {
    override val name = "Sibnet"
    override val mainUrl = "https://video.sibnet.ru"
    override val hosts = listOf("video.sibnet.ru", "sibnet.ru")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val rel = Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""["'](/v/[^"']+\.mp4[^"']*)["']""").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val direct = if (rel.startsWith("http")) rel else mainUrl + rel
        return listOf(
            link(direct, Quality.UNKNOWN, referer = url)
                .copy(streamType = StreamType.DIRECT_FILE)
        ).onEach(callback)
    }
}

/** SendVid — `<source src="…">` ou og:video. */
class SendVid(http: Http) : EsExtractor(http) {
    override val name = "SendVid"
    override val mainUrl = "https://sendvid.com"
    override val hosts = listOf("sendvid.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val src = Regex("""var\s+video_source\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<source src="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex("""property="og:video(?::secure_url)?" content="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return genericSniff(html, url)
        val full = if (src.startsWith("//")) "https:$src" else src
        return (if (full.contains(".m3u8")) M3u8.variants(http, full, name, url) else listOf(link(full, referer = url)))
            .onEach(callback)
    }
}

/** FileMoon / FileMoon-like — packer + `sources:[{file:"…m3u8"}]`. */
class FileMoon(http: Http) : EsExtractor(http) {
    override val name = "FileMoon"
    override val mainUrl = "https://filemoon.sx"
    override val hosts = listOf("filemoon.sx", "filemoon.to", "filemoon.in", "moonplayer", "kerapoxy.cc", "fmoonembed")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val script = Unpacker.unpack(html) ?: html
        val file = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: return genericSniff(html, url)
        return M3u8.variants(http, file, name, referer = url).onEach(callback)
    }
}

/**
 * Famille « playerwish » : StreamWish, FileLions, VidHide, Lulustream, Luluvdo,
 * Savefiles, Streamhub, BigWarp… même gabarit (jwplayer packé + m3u8).
 */
class StreamWishFamily(http: Http) : EsExtractor(http) {
    override val name = "StreamWish"
    override val mainUrl = "https://streamwish.to"
    override val hosts = listOf(
        "streamwish.to", "streamwish.com", "swishsrv.com", "filelions.to", "filelions.com",
        "filelions.live", "vidhide.com", "vidhidepro.com", "vidhidevip.com", "lulustream.com",
        "luluvdo.com", "lulu.st", "savefiles.com", "streamhub.gg", "bigwarp.io", "playerwish.com",
        "awish.pro", "dhtpre.com", "embedwish.com", "wishfast.top", "ajmidyadfihayh.sbs",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val script = (Unpacker.unpack(html) ?: "") + "\n" + html
        Regex("""\{file:"([^"]+\.(?:vtt|srt))",label:"([^"]*)"""").findAll(script).forEach { m ->
            subtitleCallback(
                SubtitleTrack(
                    m.groupValues[1], "fr", m.groupValues[2],
                    if (m.groupValues[1].endsWith("srt")) SubtitleFormat.SRT else SubtitleFormat.VTT,
                )
            )
        }
        val file = Regex("""(?:file|source)\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: return genericSniff(html, url)
        return M3u8.variants(http, file, name, referer = url).onEach(callback)
    }
}

/** Streamtape — lien reconstitué depuis `robotlink` + complément JS. */
class Streamtape(http: Http) : EsExtractor(http) {
    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val hosts = listOf("streamtape.com", "streamtape.net", "stape.fun", "streamadblocker.xyz", "tapewithadblock.org")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url.replace("/v/", "/e/"), referer = referer)?.text ?: return emptyList()
        val m = Regex("""robotlink'\)\.innerHTML = '([^']*)'\s*\+\s*\('([^']*)'""").find(html)
            ?: Regex("""innerHTML\s*=\s*"([^"]*)"\s*\+\s*\('([^']*)'""").find(html)
            ?: return emptyList()
        val part1 = m.groupValues[1]
        val part2 = m.groupValues[2].substring(minOf(4, m.groupValues[2].length))
        val direct = "https:$part1$part2".replace("https:https:", "https:") + "&stream=1"
        return listOf(link(direct, referer = url).copy(streamType = StreamType.DIRECT_FILE)).onEach(callback)
    }
}

/** MixDrop — `MDCore.wurl = "//…"` (parfois packé). */
class MixDrop(http: Http) : EsExtractor(http) {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.co"
    override val hosts = listOf("mixdrop.co", "mixdrop.to", "mixdrop.sx", "mixdrop.bz", "mixdrop.ps")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url.replace("/f/", "/e/"), referer = referer)?.text ?: return emptyList()
        val script = Unpacker.unpack(html) ?: html
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?: return emptyList()
        val direct = if (wurl.startsWith("//")) "https:$wurl" else wurl
        return listOf(link(direct, referer = url, extraHeaders = mapOf("Origin" to mainUrl))).onEach(callback)
    }
}

/** Vidzy / Vidara-like : jwplayer packé, m3u8 ou mp4. */
class Vidzy(http: Http) : EsExtractor(http) {
    override val name = "Vidzy"
    override val mainUrl = "https://vidzy.org"
    override val hosts = listOf("vidzy.org", "vidzy.to", "vidzy.net")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        return genericSniff(html, url).onEach(callback)
    }
}

/**
 * Vidara / famille « StreamUp » — lecteur maison des réseaux FR (CineStream,
 * Flemmix, 1jour1film…). Tous les miroirs partagent l'API `POST /api/stream`
 * {filecode, device} → `{streaming_url, subtitles:[{file_path, language}]}`.
 */
class Vidara(http: Http) : EsExtractor(http) {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val hosts = listOf(
        "vidara.to", "vidaraa.cc", "vidaraw.com", "vidarax.cc", "vidara.so", "vidara.pro",
        "vidavaca.net", "vidaarax.net", "vidaarax.com", "vidaratem.com", "odysseusa.cc",
        "handfacesnap.cc", "namefacesnap.cc", "thebesthosterv.com", "vidmatrixa.com",
        "vidchampions.com", "antarcticadocs.com", "nameitweb.com",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val base = Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: mainUrl
        val fileCode = url.substringAfterLast('/').substringBefore('?')
        val body = """{"filecode":${Json.quote(fileCode)},"device":"web"}"""
        val res = runCatching { http.post("$base/api/stream", referer = base, json = body).text }
            .getOrNull()

        if (res != null) {
            val stream = Regex(""""streaming_url"\s*:\s*"([^"]+)"""").find(res)?.groupValues?.get(1)
                ?.replace("\\/", "/")
            if (stream != null && stream.startsWith("http")) {
                val subsBlock = Regex(""""subtitles"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
                    .find(res)?.groupValues?.get(1).orEmpty()
                val paths = Regex(""""file_path"\s*:\s*"([^"]+)"""").findAll(subsBlock)
                    .map { it.groupValues[1].replace("\\/", "/") }.toList()
                val langs = Regex(""""language"\s*:\s*"([^"]+)"""").findAll(subsBlock)
                    .map { it.groupValues[1] }.toList()
                val tracks = paths.zip(langs).map { (path, lang) ->
                    SubtitleTrack(
                        path, lang.take(2).lowercase(), lang,
                        if (path.endsWith(".srt")) SubtitleFormat.SRT else SubtitleFormat.VTT,
                    )
                }
                tracks.forEach(subtitleCallback)
                val links =
                    if (stream.contains(".m3u8")) M3u8.variants(http, stream, name, referer = base)
                    else listOf(link(stream, referer = base))
                return links.map { it.copy(subtitles = tracks) }.onEach(callback)
            }
        }
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        return genericSniff(html, url).onEach(callback)
    }
}

/** Upstream / Uptostream. */
class Upstream(http: Http) : EsExtractor(http) {
    override val name = "Upstream"
    override val mainUrl = "https://upstream.to"
    override val hosts = listOf("upstream.to", "uptostream.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val script = Unpacker.unpack(html) ?: html
        val file = Regex("""file\s*:\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
            ?: return emptyList()
        return (if (file.contains(".m3u8")) M3u8.variants(http, file, name, url) else listOf(link(file, referer = url)))
            .onEach(callback)
    }
}

/** OK.ru — métadonnées `data-options` (flux HLS officiel). */
class Okru(http: Http) : EsExtractor(http) {
    override val name = "OK.ru"
    override val mainUrl = "https://ok.ru"
    override val hosts = listOf("ok.ru", "odnoklassniki.ru")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        val raw = Regex("""data-options="([^"]+)"""").find(html)?.groupValues?.get(1)
            ?.let { Text.decodeHtml(it) } ?: return emptyList()
        val hls = Regex("""hlsManifestUrl\\?"\s*:\s*\\?"([^"\\]+)""").find(raw)?.groupValues?.get(1)
            ?.replace("\\u0026", "&") ?: return emptyList()
        return M3u8.variants(http, hls, name, referer = url).onEach(callback)
    }
}

/** Dailymotion — API officielle de métadonnées. */
class Dailymotion(http: Http) : EsExtractor(http) {
    override val name = "Dailymotion"
    override val mainUrl = "https://www.dailymotion.com"
    override val hosts = listOf("dailymotion.com", "dai.ly", "geo.dailymotion.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val id = Regex("""(?:video|embed/video|dai\.ly)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
            ?: return emptyList()
        val meta = http.getOrNull(
            "https://www.dailymotion.com/player/metadata/video/$id",
            referer = referer ?: mainUrl,
        )?.asJsonOrNull() ?: return emptyList()
        val hls = meta["qualities"]["auto"][0]["url"].string
            ?: meta.findAll("url").mapNotNull { it.string }.firstOrNull { it.contains(".m3u8") }
            ?: return emptyList()
        meta["subtitles"]["data"].let { subs ->
            subs.keys.forEach { lang ->
                subs[lang]["urls"][0].string?.let {
                    subtitleCallback(SubtitleTrack(it, lang, lang.uppercase(), SubtitleFormat.VTT))
                }
            }
        }
        return M3u8.variants(http, hls, name, referer = mainUrl).onEach(callback)
    }
}

/** Myvi.top / Myvi.tv. */
class MyviTop(http: Http) : EsExtractor(http) {
    override val name = "Myvi"
    override val mainUrl = "https://myvi.top"
    override val hosts = listOf("myvi.top", "myvi.tv", "myvi.ru")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val html = http.getOrNull(url, referer = referer)?.text ?: return emptyList()
        return genericSniff(html, url).onEach(callback)
    }
}

/**
 * Dernier recours : n'importe quel embed. Déballe le packer et ramasse les URLs
 * de média. Permet de ne jamais perdre un lecteur inconnu d'un site FR.
 */
class GenericHost(http: Http) : EsExtractor(http) {
    override val name = "Lecteur"
    override val mainUrl = ""
    override val hosts = emptyList<String>()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleTrack) -> Unit,
        callback: (VideoLink) -> Unit,
    ): List<VideoLink> {
        val res = try {
            http.get(url, referer = referer)
        } catch (e: SourceException.CaptchaRequired) {
            throw e
        } catch (_: Throwable) {
            return emptyList()
        }
        val host = Text.host(url).removePrefix("www.").substringBefore('.')
            .replaceFirstChar { it.uppercase() }
        return genericSniff(res.text, url).map { it.copy(server = Text.serverLabel(host)) }
            .onEach(callback)
    }
}
