package dev.daisy.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * [Fetcher] over OkHttp.
 *
 * Deliberately thin: everything discovery does with a response -- sniff, link
 * scan, resolve -- is already tested without a network. This class exists only
 * to turn a URL into a [FetchResult], so it should stay boring.
 *
 * Timeouts are set explicitly rather than trusted to defaults. OkHttp's
 * defaults (10s connect, 10s read) are reasonable, but discovery fires up to
 * eight candidates serially at one host, and eight candidate timeouts at 10s is
 * over a minute of spinner with nothing to show. The whole CHAIN gets a budget
 * in [Discover]; this is the per-request ceiling.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = defaultClient(),
    private val userAgent: String = DEFAULT_UA,
) : Fetcher {

    override suspend fun get(url: String): FetchResult {
        val request = try {
            Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                // Some hosts vary the feed list by Accept; asking for anything
                // is more honest than lying about being a browser.
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/html;q=0.8, */*;q=0.5")
                .build()
        } catch (e: IllegalArgumentException) {
            // Malformed URL. OkHttp throws this before any network work.
            return FetchResult.Failed(null, "bad url: ${e.message}")
        }

        return try {
            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString()

                if (!response.isSuccessful) {
                    return FetchResult.Failed(response.code, response.message.ifEmpty { "HTTP ${response.code}" })
                }

                // OkHttp 5 declares body non-null, so an elvis here could
                // never fire -- it was carrying a null check from the OkHttp 4
                // signature. An empty body still has to be rejected; that is
                // a length check, not a null check.
                val body = response.body

                // Bounded read. A feed page that streams forever must not
                // exhaust memory; discovery only needs the head anyway.
                val text = try {
                    body.source().let { src ->
                        src.request(MAX_BYTES.toLong())
                        src.buffer.snapshot().utf8()
                    }
                } catch (e: IOException) {
                    return FetchResult.Failed(response.code, "read failed: ${e.message}")
                }

                FetchResult.Ok(
                    finalUrl = finalUrl,
                    contentType = response.header("Content-Type"),
                    body = text,
                    status = response.code,
                )
            }
        } catch (e: IOException) {
            FetchResult.Failed(null, e.message ?: e::class.simpleName ?: "io error")
        }
    }

    companion object {
        private const val DEFAULT_UA =
            "Daisy/0.1 (+https://github.com/richard-zed/daisy; feed reader)"

        /** 512 KB. Enough for any feed head or <head> section. */
        const val MAX_BYTES = 512 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
