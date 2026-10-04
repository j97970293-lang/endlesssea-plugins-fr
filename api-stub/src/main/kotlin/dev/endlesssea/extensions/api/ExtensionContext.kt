package dev.endlesssea.extensions.api

import java.io.File

/**
 * Everything the host app injects into an extension instance.
 * Extensions receive ONE context at construction (see the loader) and must never
 * create their own raw HTTP clients (so the user keeps full visibility/control).
 */
class ExtensionContext(
    val http: ExtensionHttpClient,
    /** Sandboxed private directory (requires FILES permission). */
    val filesDir: File,
    /** App locale (e.g. "fr") so extensions may localize content. */
    val locale: String,
) {
    /**
     * Valeurs utilisateur des réglages déclarés via [EsExtension.settings].
     * Propriété hors constructeur → compatibilité binaire avec les extensions
     * déjà compilées (apiVersion 1). L'app la réécrit à chaque instanciation.
     */
    var settings: Map<String, String> = emptyMap()

    /**
     * Dossier cache privé dédié à l'extension (persistant entre sessions).
     * Champ additif hors constructeur (compatibilité binaire apiVersion 1).
     */
    var cacheDir: File? = null

    /** Raccourci : valeur d'un réglage (ou [fallback] si non défini). */
    fun setting(key: String, fallback: String = ""): String = settings[key] ?: fallback
}

data class EsRequest(
    val url: String,
    val method: Method = Method.GET,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
) { enum class Method { GET, POST, HEAD } }

data class EsResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>,
    val finalUrl: String,
)

/**
 * App-provided HTTP gateway: UA/cookies per extension, polite timeouts & retries,
 * permission enforcement (INTERNET), per-extension traffic journal.
 */
interface ExtensionHttpClient {
    suspend fun execute(request: EsRequest): EsResponse

    /** Current persisted cookies for this extension (post-CAPTCHA sessions, spec §3). */
    fun dumpCookies(host: String): Map<String, String>

    val userAgent: String
}
