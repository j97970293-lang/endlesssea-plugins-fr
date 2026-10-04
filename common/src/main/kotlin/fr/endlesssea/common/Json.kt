package fr.endlesssea.common

/**
 * Mini-parseur JSON autonome (remplace Jackson / `AppUtils.parseJson` de CloudStream).
 *
 * Volontairement sans dépendance : il doit fonctionner dans un .esx dexé comme en
 * test JVM. L'accès est *tolérant* — un champ absent donne [JsonNode.Null], jamais
 * une exception, pour que les extensions restent robustes aux changements de site.
 */
sealed class JsonNode {

    object Null : JsonNode()
    data class Bool(val value: Boolean) : JsonNode()
    data class Num(val value: Double) : JsonNode()
    data class Str(val value: String) : JsonNode()
    data class Arr(val items: List<JsonNode>) : JsonNode()
    data class Obj(val fields: Map<String, JsonNode>) : JsonNode()

    /** Accès objet : `node["data"]["title"]`. */
    operator fun get(key: String): JsonNode = (this as? Obj)?.fields?.get(key) ?: Null

    /** Accès tableau : `node[0]`. */
    operator fun get(index: Int): JsonNode = (this as? Arr)?.items?.getOrNull(index) ?: Null

    val list: List<JsonNode> get() = (this as? Arr)?.items ?: emptyList()
    val keys: Set<String> get() = (this as? Obj)?.fields?.keys ?: emptySet()
    val isNull: Boolean get() = this is Null

    val string: String?
        get() = when (this) {
            is Str -> value
            is Num -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            is Bool -> value.toString()
            else -> null
        }

    val int: Int? get() = (this as? Num)?.value?.toInt() ?: (this as? Str)?.value?.trim()?.toDoubleOrNull()?.toInt()
    val long: Long? get() = (this as? Num)?.value?.toLong() ?: (this as? Str)?.value?.trim()?.toDoubleOrNull()?.toLong()
    val double: Double? get() = (this as? Num)?.value ?: (this as? Str)?.value?.trim()?.toDoubleOrNull()
    val bool: Boolean?
        get() = (this as? Bool)?.value ?: (this as? Str)?.value?.let { it == "true" || it == "1" }

    fun str(key: String): String? = get(key).string?.takeIf { it.isNotBlank() }
    fun arr(key: String): List<JsonNode> = get(key).list

    /** Recherche récursive de la première valeur portant la clé [key] (sites imbriqués). */
    fun find(key: String): JsonNode? = when (this) {
        is Obj -> fields[key] ?: fields.values.firstNotNullOfOrNull { it.find(key) }
        is Arr -> items.firstNotNullOfOrNull { it.find(key) }
        else -> null
    }

    /** Toutes les valeurs portant la clé [key], en profondeur. */
    fun findAll(key: String): List<JsonNode> = when (this) {
        is Obj -> buildList {
            fields[key]?.let { add(it) }
            fields.values.forEach { addAll(it.findAll(key)) }
        }
        is Arr -> items.flatMap { it.findAll(key) }
        else -> emptyList()
    }
}

object Json {

    fun parse(raw: String): JsonNode = Parser(raw).run {
        skipWs()
        val v = readValue()
        v
    }

    fun parseOrNull(raw: String?): JsonNode? =
        if (raw.isNullOrBlank()) null else runCatching { parse(raw) }.getOrNull()

    /** Échappe une chaîne pour l'insérer dans un corps JSON. */
    fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): JsonNode {
            skipWs()
            if (i >= s.length) return JsonNode.Null
            return when (s[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonNode.Str(readString())
                't' -> { expect("true"); JsonNode.Bool(true) }
                'f' -> { expect("false"); JsonNode.Bool(false) }
                'n' -> { expect("null"); JsonNode.Null }
                else -> readNumber()
            }
        }

        private fun expect(word: String) {
            require(s.startsWith(word, i)) { "JSON: attendu '$word' à $i" }
            i += word.length
        }

        private fun readObject(): JsonNode {
            i++ // {
            val map = LinkedHashMap<String, JsonNode>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonNode.Obj(map) }
            while (i < s.length) {
                skipWs()
                val key = readString()
                skipWs()
                require(i < s.length && s[i] == ':') { "JSON: ':' attendu à $i" }
                i++
                map[key] = readValue()
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return JsonNode.Obj(map) }
                    else -> return JsonNode.Obj(map)
                }
            }
            return JsonNode.Obj(map)
        }

        private fun readArray(): JsonNode {
            i++ // [
            val items = ArrayList<JsonNode>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonNode.Arr(items) }
            while (i < s.length) {
                items.add(readValue())
                skipWs()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return JsonNode.Arr(items) }
                    else -> return JsonNode.Arr(items)
                }
            }
            return JsonNode.Arr(items)
        }

        private fun readString(): String {
            require(i < s.length && s[i] == '"') { "JSON: chaîne attendue à $i" }
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                when (val c = s[i]) {
                    '"' -> { i++; return sb.toString() }
                    '\\' -> {
                        i++
                        when (val e = s.getOrNull(i)) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i + 1, (i + 5).coerceAtMost(s.length))
                                sb.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                                i += 4
                            }
                            null -> return sb.toString()
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
            return sb.toString()
        }

        private fun readNumber(): JsonNode {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "-+.eE")) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull()?.let { JsonNode.Num(it) } ?: JsonNode.Null
        }
    }
}
