package dev.daisy.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import dev.daisy.core.Item
import java.text.DateFormat
import java.util.Date

/**
 * One feed's items, and the article currently open.
 *
 * Two levels inside one composable: the feed's item list, and the article.
 * Neither is deep enough to justify a nav graph, but the article is a distinct
 * destination because it replaces the whole screen.
 *
 * Article bodies are shown in a WebView rather than being rendered. That is the
 * one place a third-party engine is doing real work for us, and it is a
 * deliberate trade: an HTML renderer would be a project of its own, and the
 * fallback -- open the original page -- needs a browser-equivalent anyway.
 * Nothing about the feed list depends on it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    feedId: Long,
    vm: DaisyViewModel,
    onBack: () -> Unit,
) {
    val items by produceState<List<Item>?>(initialValue = null, feedId) {
        value = vm.itemsFor(feedId)
    }
    var open by remember { mutableStateOf<Item?>(null) }
    val feed by produceState(feedId) { value = vm.feedById(feedId) }

    val article = open
    if (article != null) {
        ArticleScreen(item = article, onBack = { open = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        feed?.title ?: "Feed",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    // Label rather than a back arrow: material-icons-core's
                    // availability varies by version and a text button cannot
                    // silently render nothing.
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("No items", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                list.forEach { item ->
                    ItemRow(
                        item = item,
                        feedTitle = "",
                        failure = null,
                        onClick = { open = item },
                    )
                }
            }
        }
    }
}

/**
 * A single article.
 *
 * Prefers the feed-provided summary, because it is already downloaded and
 * therefore instant. Reader-mode extraction of the original page is the
 * better experience when it works, but it is a network round trip and a
 * parse that can fail; showing what we have immediately and offering the
 * original page is honest about which one the user is looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArticleScreen(item: Item, onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    // Label rather than a back arrow: material-icons-core's
                    // availability varies by version and a text button cannot
                    // silently render nothing.
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            item.published?.let {
                Text(
                    text = DateFormat.getDateTimeInstance().format(Date(it)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            val body = item.summary
            if (body.isNullOrBlank()) {
                Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                    Text(
                        "This feed carries no article body.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ArticleBody(body, Modifier.weight(1f).fillMaxWidth())
            }

            item.link?.let { link ->
                TextButton(
                    onClick = {
                        // Handing off to the browser rather than an in-app
                        // WebView: the user gets their own tabs, their own
                        // password manager and their own reader settings.
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        runCatching { context.startActivity(intent) }
                    },
                    modifier = Modifier.padding(8.dp),
                ) {
                    Text("Open original")
                }
            }
        }
    }
}

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
