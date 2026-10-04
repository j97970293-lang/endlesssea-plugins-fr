package dev.endlesssea.extensions.api.captcha

/**
 * CAPTCHA / anti-bot contract holder (spec §3, docs/en/05 §4).
 *
 * Flow: extension throws SourceException.CaptchaRequired → the host opens
 * CaptchaActivity (system WebView) → the user verifies MANUALLY → cookies persist
 * into the extension's cookie jar → the original call is retried automatically.
 */
data class CaptchaChallenge(
    val pageUrl: String,
    val headers: Map<String, String> = emptyMap(),
)

/** Result code contract for the host's verification activity. */
object CaptchaContract {
    const val EXTRA_PAGE_URL = "dev.endlesssea.extra.PAGE_URL"
    const val EXTRA_HEADERS_JSON = "dev.endlesssea.extra.HEADERS_JSON"
    const val RESULT_SOLVED = 1
    const val RESULT_CANCELLED = 0
}
