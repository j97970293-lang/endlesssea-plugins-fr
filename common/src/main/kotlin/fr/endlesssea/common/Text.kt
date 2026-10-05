package fr.endlesssea.common

import dev.endlesssea.extensions.api.model.AudioLang
import dev.endlesssea.extensions.api.model.MediaStatus
import dev.endlesssea.extensions.api.model.MediaType
import dev.endlesssea.extensions.api.model.Quality
import dev.endlesssea.extensions.api.model.StreamType
import java.net.URI

/** Petits utilitaires de normalisation (équivalents natifs de `fixUrl`, `getQualityFromName`…). */
object Text {

    private val entityRegex = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val namedEntities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "agrave" to "à",
        "acirc" to "â", "ccedil" to "ç", "ugrave" to "ù", "ucirc" to "û", "icirc" to "î",
        "iuml" to "ï", "ocirc" to "ô", "euml" to "ë", "laquo" to "«", "raquo" to "»",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "ndash" to "–", "mdash" to "—", "deg" to "°", "euro" to "€", "copy" to "©",
        "reg" to "®", "trade" to "™", "middot" to "·", "bull" to "•", "times" to "×",
    )

    /** Décode entités HTML nommées et numériques (`&#8217;`, `&eacute;`, `&amp;`…). */
    fun decodeHtml(input: String?): String {
        if (input.isNullOrEmpty()) return ""
        return entityRegex.replace(input) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x", true) -> e.drop(2).toIntOrNull(16)?.toChar()?.toString() ?: m.value
                e.startsWith("#") -> e.drop(1).toIntOrNull()?.toChar()?.toString() ?: m.value
                else -> namedEntities[e.lowercase()] ?: m.value
            }
        }
    }

    /** Supprime les balises et condense les espaces. */
    fun stripHtml(html: String?): String =
        decodeHtml(html.orEmpty().replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " "))
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** Absolutise une URL relative par rapport à [mainUrl] (équivalent de `fixUrl`). */
    fun fixUrl(url: String?, mainUrl: String): String {
        val u = url?.trim().orEmpty()
        return when {
            u.isEmpty() -> ""
            u.startsWith("http://") || u.startsWith("https://") -> u
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl.trimEnd('/') + u
            else -> mainUrl.trimEnd('/') + "/" + u
        }
    }

    fun fixUrlNull(url: String?, mainUrl: String): String? = fixUrl(url, mainUrl).takeIf { it.isNotBlank() }

    fun host(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    /** Devine la qualité à partir d'un libellé quelconque ("HD 1080", "FULLHD", "4K"…). */
    fun quality(label: String?): Quality {
        val l = label?.lowercase()?.trim().orEmpty()
        return when {
            l.isEmpty() -> Quality.UNKNOWN
            "2160" in l || "4k" in l || "uhd" in l -> Quality.Q4K
            "1440" in l || "2k" in l -> Quality.Q1440
            "1080" in l || "fhd" in l || "fullhd" in l || "full hd" in l -> Quality.Q1080
            "720" in l || l == "hd" || "hdrip" in l || "hdlight" in l -> Quality.Q720
            "480" in l || "sd" in l -> Quality.Q480
            "360" in l -> Quality.Q360
            else -> Quality.fromLabel(l).takeIf { it != Quality.UNKNOWN } ?: Quality.UNKNOWN
        }
    }

    /** Déduit le type de flux de l'URL (HLS / DASH / fichier direct / embed). */
    fun streamType(url: String): StreamType {
        val u = url.substringBefore('?').lowercase()
        return when {
            u.endsWith(".m3u8") || u.contains(".m3u8") -> StreamType.HLS
            u.endsWith(".mpd") -> StreamType.DASH
            u.startsWith("magnet:") || u.endsWith(".torrent") -> StreamType.TORRENT
            u.endsWith(".mp4") || u.endsWith(".mkv") || u.endsWith(".webm") || u.endsWith(".avi") ||
                u.endsWith(".mov") || u.endsWith(".flv") || u.endsWith(".ogv") -> StreamType.DIRECT_FILE
            else -> StreamType.EMBED
        }
    }

    /** VF / VOSTFR / VO à partir d'un libellé de lecteur ou d'épisode. */
    fun audioLang(label: String?): AudioLang {
        val l = label?.lowercase().orEmpty()
        return when {
            "multi" in l -> AudioLang.MULTI
            "vostfr" in l || "vost" in l || "sub" in l || "subbed" in l ||
                "sous-titr" in l || "vosta" in l -> AudioLang.VOSTFR
            // « truefrench » contient déjà « french » ; « vfi »/« vfq »/« vff » sont
            // des variantes de doublage ; « fr »/« français » arrivent tels quels
            // des agrégateurs.
            "vf" in l || "french" in l || "francais" in l || "français" in l ||
                "dub" in l || l.trim() in setOf("fr", "fr-fr", "fra") -> AudioLang.VF
            "vo" in l || "raw" in l || "original" in l ||
                l.trim() in setOf("en", "en-us", "jp", "ja") -> AudioLang.VO
            else -> AudioLang.OTHER
        }
    }

    fun mediaType(label: String?, fallback: MediaType = MediaType.OTHER): MediaType {
        val l = label?.lowercase().orEmpty()
        return when {
            "film" in l || "movie" in l -> if ("anime" in l) MediaType.ANIME else MediaType.MOVIE
            "serie" in l || "série" in l || "series" in l || "tv" in l -> MediaType.SERIES
            "ova" in l -> MediaType.OVA
            "ona" in l -> MediaType.ONA
            "special" in l || "spécial" in l -> MediaType.SPECIAL
            "anime" in l || "animé" in l -> MediaType.ANIME
            else -> fallback
        }
    }

    fun status(label: String?): MediaStatus {
        val l = label?.lowercase().orEmpty()
        return when {
            "en cours" in l || "ongoing" in l || "diffusion" in l || "airing" in l -> MediaStatus.ONGOING
            "termin" in l || "complet" in l || "completed" in l || "finished" in l -> MediaStatus.COMPLETED
            "bientôt" in l || "prochainement" in l || "upcoming" in l || "à venir" in l -> MediaStatus.UPCOMING
            "pause" in l || "hiatus" in l -> MediaStatus.HIATUS
            "annul" in l || "cancel" in l -> MediaStatus.CANCELLED
            else -> MediaStatus.UNKNOWN
        }
    }

    /** Premier millésime plausible trouvé dans un texte. */
    fun year(text: String?): Int? =
        Regex("(19\\d{2}|20\\d{2})").find(text.orEmpty())?.value?.toIntOrNull()

    /** Durée "1h 52min", "112 min", "01:52:00" → minutes. */
    fun durationMin(text: String?): Int? {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        Regex("(\\d+)\\s*h\\s*(\\d+)?").find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: 0
            val mi = m.groupValues[2].toIntOrNull() ?: 0
            return h * 60 + mi
        }
        Regex("(\\d{1,2}):(\\d{2}):(\\d{2})").find(t)?.let { m ->
            return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        }
        return Regex("(\\d{2,3})\\s*(?:min|mn|m\\b)").find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Identifiant stable et lisible pour un item (sert d'`id` Endless Sea). */
    fun slug(url: String): String = url.trimEnd('/')
        .substringAfterLast('/')
        .substringBefore('?')
        .ifBlank { url.hashCode().toUInt().toString(16) }

    fun idOf(prefix: String, url: String): String = "$prefix:${slug(url)}"

    // ---------------------------------------------------------------- serveurs

    /** Marques connues : clé = base d'hôte normalisée, valeur = libellé affiché. */
    private val SERVER_BRANDS = mapOf(
        "vidara" to "Vidara", "streamup" to "StreamUp", "streamu" to "StreamUp",
        "sibnet" to "Sibnet", "sendvid" to "Sendvid", "vk" to "VK", "ok" to "OK.ru",
        "okru" to "OK.ru", "youtube" to "YouTube", "youtu" to "YouTube",
        "dailymotion" to "Dailymotion", "dood" to "Doodstream", "doodstream" to "Doodstream",
        "voe" to "Voe", "upstream" to "Upstream", "uqload" to "Uqload", "lulu" to "Luluvdo",
        "luluvdo" to "Luluvdo", "vidmoly" to "Vidmoly", "filemoon" to "Filemoon",
        "moonplayer" to "Filemoon", "streamtape" to "Streamtape", "mixdrop" to "Mixdrop",
        "netu" to "Netu", "vudeo" to "Vudeo", "fsvid" to "FSVid", "movearnpre" to "Movearnpre",
        "playerix" to "Playerix", "frembed" to "Frembed", "vidsrc" to "VidSrc",
        "embed" to "2Embed", "videasy" to "Videasy", "peachify" to "Peachify",
        "vidfast" to "VidFast", "vidnest" to "VidNest", "wiflix" to "Wiflix",
        "purstream" to "Purstream", "movix" to "Movix", "zeus" to "Zeus", "mouve" to "Mouve",
        "oneembed" to "OneEmbed", "wwembed" to "WaveWatch", "wavewatch" to "WaveWatch",
        "anime-sama" to "Anime-Sama", "animesama" to "Anime-Sama",
    )

    private val LANG_TOKENS = setOf(
        "vf", "vff", "vfq", "vostfr", "vost", "vo", "multi", "sub", "subfr",
        "fr", "french", "truefrench", "vf1", "vf2", "vostfr1", "vostfr2",
    )

    /**
     * Libellé de serveur **canonique et stable**.
     *
     * Depuis Endless Sea 0.9.0, la fiche « Serveurs & priorité » regroupe les
     * liens par `VideoLink.server` et mémorise l'ordre choisi par l'utilisateur
     * sous forme de chaînes exactes. Un libellé contenant le miroir du jour
     * (`vidara23.site`), la langue (`Vidara VF`) ou la qualité ferait donc
     * exploser la liste et perdrait l'ordre à chaque rotation de domaine —
     * la langue est déjà portée par `VideoLink.audioLang`, la qualité par
     * `VideoLink.quality`.
     *
     * On ramène donc chaque libellé à la marque : `https://vidara23.site/e/x`,
     * `vidara19.com`, `VIDARA vostfr` → `Vidara`.
     */
    fun serverLabel(raw: String?): String {
        val input = raw?.trim().orEmpty()
        if (input.isEmpty()) return "Lecteur"
        // Libellés composites « Source · Serveur » : on normalise chaque moitié.
        if (input.contains('·')) {
            val parts = input.split('·').map { serverLabel(it) }.filter { it != "Lecteur" }.distinct()
            return parts.joinToString(" · ").ifBlank { "Lecteur" }
        }
        val base = if (input.startsWith("http", true)) host(input) else input
        val cleaned = base.removePrefix("www.").substringBefore('/').substringBefore('?').trim()
        // Hôte (« vidara23.site », « wwembed.wavewatch.top ») : on garde l'étiquette
        // de marque, sans les chiffres du miroir du jour.
        val label = if (!cleaned.contains(' ') && cleaned.contains('.')) {
            val parts = cleaned.split('.').filter { it.isNotEmpty() }
            val head = parts.firstOrNull().orEmpty().trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
            // Sous-domaine générique (« wwembed.wavewatch.top ») : on tente aussi le domaine.
            val domain = parts.getOrNull(parts.size - 2).orEmpty()
                .trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
            SERVER_BRANDS[head.lowercase()]?.let { return it }
            SERVER_BRANDS[domain.lowercase()]?.let { return it }
            head
        } else {
            // Libellé humain : on retire les jetons de langue et de qualité,
            // déjà portés par VideoLink.audioLang / VideoLink.quality.
            cleaned.split(' ', '_', '|')
                .map { it.trim() }
                .filter { w ->
                    w.isNotEmpty() &&
                        w.lowercase() !in LANG_TOKENS &&
                        !Regex("^\\d{3,4}p$", RegexOption.IGNORE_CASE).matches(w)
                }
                .joinToString(" ")
        }
        val key = label.lowercase().trim()
        SERVER_BRANDS[key]?.let { return it }
        if (key.isEmpty()) return "Lecteur"
        return label.trim().replaceFirstChar { it.uppercase() }
    }
}
