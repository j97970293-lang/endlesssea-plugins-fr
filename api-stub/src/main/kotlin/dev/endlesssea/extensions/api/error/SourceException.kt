package dev.endlesssea.extensions.api.error

/**
 * Every failure an extension can produce, mapped to a user-comprehensible UI message (spec §22).
 */
sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** "Connexion impossible" — network layer failure (DNS, timeout, TLS…). */
    class NetworkError(cause: Throwable) : SourceException(cause.message ?: "network error", cause)

    /** "Source indisponible" — the site/API is down or refuses us. */
    class SourceUnavailable(val httpCode: Int? = null) : SourceException("source unavailable ($httpCode)")

    /** "Aucun résultat". */
    object NoResults : SourceException("no results")

    /**
     * A human verification is required. The app opens a WebView on [pageUrl],
     * the user verifies manually, cookies are persisted and the call is retried (spec §3).
     */
    class CaptchaRequired(
        val pageUrl: String,
        val headers: Map<String, String> = emptyMap(),
    ) : SourceException("verification required: $pageUrl")

    /** "Vidéo non disponible". */
    class VideoUnavailable(val reason: String? = null) : SourceException(reason ?: "video unavailable")

    /** "Sous-titre introuvable". */
    object SubtitleNotFound : SourceException("subtitle not found")

    /** Legitimate sign-in required (official APIs only, spec §2). */
    class AuthRequired(val loginUrl: String? = null) : SourceException("authentication required")

    class RateLimited(val retryAfterSec: Int? = null) : SourceException("rate limited")

    /** Extension bug / site layout change; reported to the extension's error journal. */
    class ParseError(val what: String, cause: Throwable? = null) : SourceException("parse error: $what", cause)
}
