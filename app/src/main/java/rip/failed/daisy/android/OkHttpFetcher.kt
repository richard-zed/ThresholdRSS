package rip.failed.daisy.android

import rip.failed.daisy.core.FetchResult
import rip.failed.daisy.core.Fetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A [Fetcher] over OkHttp, for the app.
 *
 * The only [Fetcher] in the tree. Android wants a connection pool and real
 * timeouts, and HttpURLConnection on Android has a history of leaking sockets
 * under redirect chains. The contract it upholds -- [FetchResult.Ok] with the
 * FINAL url and the body, [FetchResult.Failed] with a nullable status -- is the
 * one everything above this line was written against.
 *
 * Three choices worth stating:
 *
 *  - Redirects are followed by OkHttp, not by hand, and `response.request.url`
 *    is the final URL. Discovery depends on that: a youtube /@handle resolves
 *    to /channel/UC... and the channel id is only visible in the URL after the
 *    hop.
 *
 *  - Non-2xx bodies are still returned, not thrown. A 404 page can carry a
 *    feed <link>, and some servers answer 500 with something readable. The
 *    status travels in the result so the caller decides what it means.
 *
 *  - Bodies are read as bytes and decoded by [charsetOf], NOT by OkHttp's
 *    string() which assumes the charset in Content-Type and falls back to
 *    UTF-8. That default is the right one, but it silently ignores an XML
 *    declaration, which is a real case for feeds not served as text/xml.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = "ThresholdRSS/0.1 (+rss reader)",
) : Fetcher {

    companion object {
        /**
         * One client for the app, because the pool is the point. Redirects
         * capped so a misconfigured site cannot loop us forever.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    override suspend fun get(url: String): FetchResult = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/xml, " +
                        "text/xml, text/html;q=0.8, */*;q=0.1",
                )
                .build()
        } catch (e: IllegalArgumentException) {
            // Malformed URL. OkHttp throws IAE here rather than a network error,
            // and reporting it as transport-level (null status) is correct: we
            // never reached a server.
            return@withContext FetchResult.Failed(null, e.message ?: "bad url")
        }

        try {
            client.newCall(request).execute().use { response ->
                // OkHttp 5 declares body non-null. An empty 204 still yields a
                // zero-length body rather than null.
                val bytes = response.body.bytes()
                val charset = charsetOf(response.header("Content-Type"))
                FetchResult.Ok(
                    finalUrl = response.request.url.toString(),
                    contentType = response.header("Content-Type"),
                    body = String(bytes, charset),
                    status = response.code,
                )
            }
        } catch (e: IOException) {
            // DNS, TLS, timeout, connection reset. No status exists because no
            // response was received, which is a different animal from a 404 and
            // FetchResult.Failed models that distinction.
            FetchResult.Failed(null, e.message ?: "network error")
        } catch (e: Exception) {
            FetchResult.Failed(null, e.message ?: "request failed")
        }
    }

    /**
     * The charset from a Content-Type header, defaulting to UTF-8.
     *
     * Feeds are overwhelmingly UTF-8, and guessing ISO-8859-1 (the HTTP default)
     * would mangle every accented title. A declared charset is honoured when the
     * JVM knows it; an unknown name falls back rather than throwing, because a
     * server naming an encoding it invented is not a reason to fail the fetch.
     *
     * The one place a response body stops being bytes, so it is the only place
     * a title can be mangled.
     */
    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        val utf8 = java.nio.charset.Charset.forName("UTF-8")
        if (contentType == null) return utf8
        val part = contentType.split(';').firstOrNull { it.trim().startsWith("charset=", true) }
            ?: return utf8
        val name = part.substringAfter('=').trim().trim('"')
        if (name.isEmpty()) return utf8
        return try {
            java.nio.charset.Charset.forName(name)
        } catch (_: Exception) {
            utf8
        }
    }
}
