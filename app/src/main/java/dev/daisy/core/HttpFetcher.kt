package dev.daisy.core

import java.net.HttpURLConnection
import java.net.URI

/**
 * A [Fetcher] over HttpURLConnection, for the command line.
 *
 * Deliberately not the Android client. The Android app wants OkHttp for its
 * connection pool, timeouts and redirect handling; this exists so the same
 * pipeline can run from a terminal with nothing but a JDK, which is how the
 * discovery and parse layers get exercised against real sites.
 *
 * Two behaviours worth knowing:
 *  - Redirects are followed by hand. The default follows them too, but then the
 *    final URL is invisible, and discovery NEEDS it: a youtube /@handle
 *    redirecting to /channel/UC... is how the channel id is learned.
 *  - Non-2xx bodies are still returned. A 404 page can be an HTML page with a
 *    feed link on it, and some servers return 500 with a body we can read. The
 *    status travels with the result so the caller decides.
 */
class HttpFetcher(
    private val timeoutMs: Int = 15_000,
    private val userAgent: String = "Daisy/0.1 (+rss reader)",
    private val maxRedirects: Int = 8,
) : Fetcher {

    override suspend fun get(url: String): FetchResult {
        var current = url
        var hops = 0
        while (true) {
            val conn = try {
                URI(current).toURL().openConnection() as HttpURLConnection
            } catch (e: Exception) {
                return FetchResult.Failed(null, e.message ?: "bad url")
            }

            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty(
                    "Accept",
                    "application/rss+xml, application/atom+xml, application/xml, text/xml, text/html;q=0.8, */*;q=0.1",
                )

                val status = try {
                    conn.responseCode
                } catch (e: Exception) {
                    return FetchResult.Failed(null, e.message ?: "connection failed")
                }

                if (status in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null && hops < maxRedirects) {
                        current = UrlResolve.resolve(current, loc)
                        hops++
                        continue
                    }
                }

                val body = try {
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    stream?.bufferedReader(charsetOf(conn.contentType))?.use { it.readText() }.orEmpty()
                } catch (e: Exception) {
                    ""
                }

                return FetchResult.Ok(
                    finalUrl = conn.url.toString(),
                    contentType = conn.contentType,
                    body = body,
                    status = status,
                )
            } catch (e: Exception) {
                return FetchResult.Failed(null, e.message ?: "request failed")
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * The charset from a Content-Type header, defaulting to UTF-8.
     *
     * Feeds are overwhelmingly UTF-8, and guessing ISO-8859-1 (the HTTP default)
     * would mangle every accented title. A declared charset is honoured when it
     * is one the JVM knows; an unknown name falls back rather than throwing,
     * because a server naming an encoding it invented is not a reason to fail
     * the fetch.
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
