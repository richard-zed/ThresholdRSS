package dev.daisy.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.daisy.core.Item
import dev.daisy.core.Readability
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A single article.
 *
 * Shows the feed's summary immediately, then upgrades to the extracted page
 * body when extraction succeeds. Order matters: the summary is already
 * downloaded, so the screen has something to read on the first frame, while
 * extracting the original page is a network round trip and a parse that can
 * fail. Never a spinner with nothing behind it.
 *
 * No top bar. The title, the date and the back affordance all already exist in
 * the article itself or on the bottom bar, and a bar here costs 64dp of a
 * phone screen to repeat them. The system back gesture closes it, which is the
 * gesture people already use to leave a page.
 */
@Composable
internal fun ArticleScreen(
    item: Item,
    onBack: () -> Unit,
    // The busier call site (DaisyApp) has to keep reading other state, so the
    // article is composed in two places. Both need the destination bar, and
    // neither can build the other's: it lives private to DaisyApp. A slot
    // keeps the bar in one implementation and lets each caller say which
    // destination is lit.
    bar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current

    // Back gesture and back button both unwind the reader. Without this the
    // system back falls through to whatever is beneath, which is not the list
    // this article came from.
    BackHandler(enabled = true, onBack = onBack)

    val extracted by produceState<Readability.Article?>(initialValue = null, item.link) {
        val link = item.link ?: return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { fetchAndExtract(context, link) }.getOrNull()
        }
    }

    // No top bar: the title and the date are in the article itself, and 64dp
    // of a phone screen is a lot to spend repeating them. The gesture and the
    // hardware back button both close this, which is how people leave a page.
    Scaffold(bottomBar = bar) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            item.published?.let {
                Text(
                    text = DateFormat.getDateTimeInstance().format(Date(it)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // The extracted body wins when it is long enough to be the article.
            // A short result is a caption or a stray label, and showing it
            // instead of the summary would be a downgrade.
            val article = extracted?.takeIf { it.textLength >= Readability.MIN_USEFUL_CHARS }
            val body = article?.html ?: item.summary

            if (body.isNullOrBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        "This feed carries no article body.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val html = if (article != null) {
                    buildString {
                        article.title?.let { append("<h1>").append(escape(it)).append("</h1>") }
                        article.byline?.let { append("<p class=byline>").append(escape(it)).append("</p>") }
                        append(body)
                    }
                } else {
                    body
                }
                ArticleBody(html, Modifier.weight(1f).fillMaxWidth())
            }

            item.link?.let { link ->
                // Same treatment as import and export: a divider, then a
                // full-width text button at one height. A bare button here was
                // a third style for the same kind of control.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TextButton(
                    onClick = {
                        // Handing off to the browser rather than an in-app
                        // WebView: the user gets their own tabs, their own
                        // password manager and their own reader settings.
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Open original")
                }
            }
        }
    }
}

/**
 * Fetches a page and extracts its article.
 *
 * Kept out of the composable so the network and the parse are together and the
 * UI only deals with the result. Any failure returns null, which the caller
 * treats as "keep the summary".
 */
private fun fetchAndExtract(context: android.content.Context, url: String): Readability.Article? {
    val html = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        // Some sites serve a bare page to an unrecognised agent.
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Daisy/1.0",
        )
        conn.inputStream.use { it.readBytes().decodeToString() }
    }.getOrNull() ?: return null
    return Readability.extract(html, url)
}

/** Minimal escaping for the title and byline, which come from the page. */
private fun escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * Renders a feed body in a WebView.
 *
 * `@SuppressLint("SetJavaScriptEnabled")` is deliberately NOT applied: JS stays
 * off. A feed body is untrusted HTML from a third party, and nothing Daisy
 * shows requires scripting.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun ArticleBody(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = false
                settings.loadsImagesAutomatically = true
                settings.blockNetworkImage = false
            }
        },
        update = { view ->
            // Only reload when the content actually changed, otherwise every
            // recomposition would re-parse the document and reset scroll.
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, wrapForDisplay(html), "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * Wraps a fragment so it renders sanely at phone width in a dynamic palette.
 *
 * Injected CSS rather than parsing the HTML: the document is the feed's, and
 * our only interest is that it does not demand a desktop viewport or use a
 * colour that vanishes against a dark theme.
 */
private fun wrapForDisplay(fragment: String): String = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      :root { color-scheme: light dark; }
      html, body { margin: 0; padding: 12px; }
      body { font: 17px/1.5 system-ui, sans-serif; -webkit-text-size-adjust: 100%; }
      img, video, iframe { max-width: 100%; height: auto; }
      pre, code { overflow-x: auto; }
      table { max-width: 100%; display: block; overflow-x: auto; }
      a { color: #2F6F4E; }
      @media (prefers-color-scheme: dark) { a { color: #97D5AC; } }
    </style>
    </head><body>$fragment</body></html>
""".trimIndent()
