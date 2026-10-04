package fr.endlesssea.common

import dev.endlesssea.extensions.api.EsRequest
import dev.endlesssea.extensions.api.EsResponse
import dev.endlesssea.extensions.api.ExtensionContext
import dev.endlesssea.extensions.api.error.SourceException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * Passerelle HTTP native des extensions Endless Sea.
 *
 * Remplace `com.lagradost.cloudstream3.app` (NiceHttp) de CloudStream :
 *  - toutes les requêtes passent par [ExtensionContext.http] (journal, UA, permissions) ;
 *  - les cookies persistés par l'app (après vérification WebView) sont réinjectés ;
 *  - un défi anti-bot est traduit en [SourceException.CaptchaRequired], ce qui déclenche
 *    la WebView de vérification de l'app puis le rejeu automatique de l'appel
 *    (équivalent natif de `CloudflareKiller`).
 */
class Http(internal val ctx: ExtensionContext) {

    /** Cache disque partagé (app 0.7.0+ ; inopérant et sans effet avant). */
    val cache: Cache by lazy { Cache(ctx) }


    val userAgent: String get() = ctx.http.userAgent

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        verify: Boolean = true,
    ): Res = call(EsRequest.Method.GET, url, headers, referer, params, cookies, null, verify)

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String>? = null,
        json: String? = null,
        verify: Boolean = true,
    ): Res {
        val (body, contentType) = when {
            json != null -> json.toByteArray() to "application/json; charset=utf-8"
            data != null -> data.entries.joinToString("&") {
                "${it.key.urlEncode()}=${it.value.urlEncode()}"
            }.toByteArray() to "application/x-www-form-urlencoded; charset=utf-8"
            else -> ByteArray(0) to "application/x-www-form-urlencoded"
        }
        return call(
            EsRequest.Method.POST, url,
            headers + mapOf("Content-Type" to contentType),
            referer, params, cookies, body, verify,
        )
    }

    suspend fun head(url: String, headers: Map<String, String> = emptyMap(), referer: String? = null): Res =
        call(EsRequest.Method.HEAD, url, headers, referer, emptyMap(), emptyMap(), null, false)

    /** Variante tolérante : `null` au lieu d'une exception réseau (le code appelant décide). */
    suspend fun getOrNull(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        params: Map<String, String> = emptyMap(),
    ): Res? = runCatching { get(url, headers, referer, params) }
        .getOrElse { if (it is SourceException.CaptchaRequired) throw it else null }

    private suspend fun call(
        method: EsRequest.Method,
        rawUrl: String,
        headers: Map<String, String>,
        referer: String?,
        params: Map<String, String>,
        cookies: Map<String, String>,
        body: ByteArray?,
        verify: Boolean,
    ): Res {
        val url = if (params.isEmpty()) rawUrl else buildString {
            append(rawUrl)
            append(if (rawUrl.contains('?')) '&' else '?')
            append(params.entries.joinToString("&") { "${it.key.urlEncode()}=${it.value.urlEncode()}" })
        }
        val host = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        val jarCookies = runCatching { ctx.http.dumpCookies(host) }.getOrDefault(emptyMap())
        val allCookies = jarCookies + cookies

        val finalHeaders = buildMap {
            put("User-Agent", ctx.http.userAgent)
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            put("Accept-Language", "${ctx.locale},fr;q=0.9,en;q=0.8")
            if (referer != null) put("Referer", referer)
            if (allCookies.isNotEmpty()) {
                put("Cookie", allCookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }
            putAll(headers)
        }

        val response: EsResponse = try {
            ctx.http.execute(EsRequest(url, method, finalHeaders, body))
        } catch (e: SourceException) {
            throw e
        } catch (e: Throwable) {
            throw SourceException.NetworkError(e)
        }

        val res = Res(response)
        if (verify) res.verifyNotBlocked()
        return res
    }

    /** Réponse enrichie (équivalent de `NiceResponse`). */
    class Res(private val raw: EsResponse) {
        val code: Int get() = raw.code
        val text: String get() = raw.body
        val url: String get() = raw.finalUrl
        val headers: Map<String, String> get() = raw.headers
        val isSuccessful: Boolean get() = raw.code in 200..299

        val document: Document by lazy { Jsoup.parse(raw.body, raw.finalUrl) }

        fun asJson(): JsonNode = Json.parse(raw.body)
        fun asJsonOrNull(): JsonNode? = runCatching { Json.parse(raw.body) }.getOrNull()

        /**
         * Détecte Cloudflare / DDoS-Guard / Sucuri et bascule vers la vérification
         * manuelle de l'app (spec §3) plutôt que d'échouer silencieusement.
         */
        fun verifyNotBlocked(): Res {
            if (isSuccessful) return this
            val blocked = code == 403 || code == 503 || code == 429
            if (!blocked) return this
            val body = raw.body.take(4000)
            val cfMarkers = listOf(
                "Just a moment", "cf-browser-verification", "challenge-platform",
                "cf_chl_opt", "_cf_chl", "DDoS-Guard", "ddos-guard", "sucuri_cloudproxy",
                "Checking your browser", "Attention Required! | Cloudflare",
            )
            val headerMark = raw.headers.keys.any { it.equals("cf-mitigated", true) } ||
                raw.headers["server"]?.contains("cloudflare", true) == true
            if (cfMarkers.any { body.contains(it, ignoreCase = true) } || (headerMark && code != 429)) {
                throw SourceException.CaptchaRequired(raw.finalUrl, mapOf("User-Agent" to (raw.headers["user-agent"] ?: "")))
            }
            if (code == 429) {
                throw SourceException.RateLimited(raw.headers["retry-after"]?.toIntOrNull())
            }
            return this
        }

        fun requireOk(): Res {
            verifyNotBlocked()
            if (!isSuccessful) throw SourceException.SourceUnavailable(code)
            return this
        }
    }
}

fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
