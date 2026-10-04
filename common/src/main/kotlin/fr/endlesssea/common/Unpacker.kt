package fr.endlesssea.common

/**
 * Dé-obfuscateur P.A.C.K.E.R. (`eval(function(p,a,c,k,e,d){…})`), utilisé par de
 * nombreux lecteurs (Uqload, Vidzy, Lulustream, FileMoon…).
 *
 * Remplace `com.lagradost.cloudstream3.utils.JsUnpacker`, implémentation native.
 */
object Unpacker {

    private val packedRegex = Regex(
        """eval\(function\(p,a,c,k,e,[dr]?\)\{.*?\}\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\('\|'\)""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    fun isPacked(script: String): Boolean = packedRegex.containsMatchIn(script)

    /** Renvoie le script déballé, ou `null` si [script] n'est pas packé. */
    fun unpack(script: String): String? {
        val m = packedRegex.find(script) ?: return null
        val payload = m.groupValues[2]
            .replace("\\\\", "\\").replace("\\'", "'").replace("\\\"", "\"")
        val radix = m.groupValues[3].toIntOrNull() ?: return null
        val words = m.groupValues[6].split("|")

        return Regex("\\b\\w+\\b").replace(payload) { match ->
            val index = unbase(match.value, radix) ?: return@replace match.value
            words.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: match.value
        }
    }

    /** Déballe tous les blocs packés présents dans une page et les concatène. */
    fun unpackAll(html: String): String =
        packedRegex.findAll(html).mapNotNull { unpack(it.value) }.joinToString("\n")

    /** Conversion base-N « à la packer » (0-9a-zA-Z puis groupes). */
    private fun unbase(token: String, radix: Int): Int? {
        var value = 0
        for (c in token) {
            val d = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'z' -> c - 'a' + 10
                in 'A'..'Z' -> c - 'A' + 36
                else -> return null
            }
            if (d >= radix) return null
            value = value * radix + d
        }
        return value
    }
}
