package rip.failed.daisy.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import rip.failed.daisy.R
import androidx.compose.ui.viewinterop.AndroidView
import rip.failed.daisy.core.Feed
import rip.failed.daisy.core.Item
import rip.failed.daisy.core.Readability
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
    // The feed this article came from, for the name and icon in the header.
    // Nullable because the item can outlive its feed: removing a feed leaves
    // its stored items until the next cleanup, and the reader must still draw
    // something for them rather than crash or leave a hole.
    feed: Feed? = null,
    // The destination bar lives private to DaisyApp, so it cannot be built
    // here. A slot lets the caller supply it and say which destination is
    // lit, without this screen knowing anything about navigation.
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

            // Date left, feed identity right. The Row always renders so the
            // feed name does not vanish on an item with no date -- only the
            // date text is conditional.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                item.published?.let {
                    Text(
                        text = DateFormat.getDateTimeInstance().format(Date(it)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // let(), not a null check: `feed` is a parameter captured by
                // this lambda, so the compiler will not smart-cast it.
                feed?.takeIf { it.title.isNotEmpty() }?.let { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // Bounded so a long feed name cannot push the date
                        // off the left edge: maxLines clips height, not
                        // width, so the limit has to be here.
                        modifier = Modifier.fillMaxWidth(0.5f),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = f.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Spacer(Modifier.width(6.dp))
                        // Same reasoning as ItemRow: error catches a favicon
                        // that 404s, fallback catches a null model, so the
                        // slot is never blank.
                        AsyncImage(
                            model = f.faviconUrl,
                            contentDescription = null,
                            error = painterResource(R.drawable.ic_daisy),
                            fallback = painterResource(R.drawable.ic_daisy),
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape),
                        )
                    }
                }
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
                ArticleBody(
                    html = html,
                    palette = ArticlePalette.of(MaterialTheme.colorScheme),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
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
            "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) ThresholdRSS/1.0",
        )
        conn.inputStream.use { it.readBytes().decodeToString() }
    }.getOrNull() ?: return null
    return Readability.extract(html, url)
}

/** Minimal escaping for the title and byline, which come from the page. */
private fun escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


/**
 * The four colours the article's WebView needs, resolved from the app theme.
 *
 * A WebView is a separate document: it inherits nothing from Compose, so the
 * reader's colours have to be written into the page. Before this the link
 * colour was a literal green pair chosen against the system scheme, which
 * meant the article ignored the Material You accent and could disagree with
 * the app around it.
 *
 * Read straight from [ColorScheme], so a theme change repaints the article
 * with no extra state to keep in step.
 */
private data class ArticlePalette(
    val background: Int,
    val backgroundCss: String,
    val textCss: String,
    val linkCss: String,
    val scheme: String,
) {
    /** Cheap change key; a paint only happens when this differs. */
    val key: String get() = backgroundCss + textCss + linkCss + scheme

    companion object {
        fun of(cs: ColorScheme): ArticlePalette {
            // luminance() rather than isLight: the dynamic palette's surface
            // decides this, and a user can force a dark scheme on a light
            // device (or the reverse).
            val dark = cs.background.luminance() < 0.5f
            val link = cs.primary
            return ArticlePalette(
                background = cs.background.toArgb(),
                backgroundCss = cs.background.toCssHex(),
                textCss = cs.onBackground.toCssHex(),
                // Primary is tuned for composables on surface, and reads fine
                // as body text; secondary is the fallback when primary sits too
                // close to the background to read as a link.
                linkCss = (if (link.contrastAgainst(cs.background) >= 3f) link else cs.secondary).toCssHex(),
                scheme = if (dark) "dark" else "light",
            )
        }
    }
}

/**
 * `#RRGGBB` from a Compose colour, for injection into CSS.
 *
 * Built by hand rather than with [String.format]: the format is locale
 * sensitive on Android (an Arabic locale renders non-ASCII digits), and this
 * runs on every theme resolve.
 */
private fun Color.toCssHex(): String {
    fun hex(v: Float): String {
        val n = (v * 255f).toInt().coerceIn(0, 255)
        return HEX_DIGITS[n shr 4].toString() + HEX_DIGITS[n and 0x0F]
    }
    return "#" + hex(red) + hex(green) + hex(blue)
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/**
 * WCAG-ish contrast ratio between two colours.
 *
 * Used only to pick between two theme roles, so the exact formula matters
 * less than having one: an accent that matches the page background is
 * unreadable as a link regardless of which rounding is used.
 */
private fun Color.contrastAgainst(other: Color): Float {
    val a = luminance()
    val b = other.luminance()
    val hi = maxOf(a, b)
    val lo = minOf(a, b)
    return (hi + 0.05f) / (lo + 0.05f)
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
private fun ArticleBody(
    html: String,
    palette: ArticlePalette,
    modifier: Modifier = Modifier,
) {
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
            // Only reload when the content or the colours actually changed,
            // otherwise every recomposition re-parses the document and resets
            // the reader's scroll position.
            val key = palette.key + "\u0000" + html
            if (view.tag != key) {
                view.tag = key
                view.setBackgroundColor(palette.background)
                view.loadDataWithBaseURL(null, wrapForDisplay(html, palette), "text/html", "utf-8", null)
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
private fun wrapForDisplay(fragment: String, palette: ArticlePalette): String = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <style>
      :root { color-scheme: ${palette.scheme}; }
      html, body { margin: 0; padding: 12px; background: ${palette.backgroundCss}; }
      body { color: ${palette.textCss}; font: 17px/1.5 system-ui, sans-serif; -webkit-text-size-adjust: 100%; }
      img, video, iframe { max-width: 100%; height: auto; }
      pre, code { overflow-x: auto; }
      table { max-width: 100%; display: block; overflow-x: auto; }
      a { color: ${palette.linkCss}; }
      blockquote { border-left: 3px solid ${palette.linkCss}; margin-left: 0; padding-left: 12px; opacity: .85; }
    </style>
    </head><body>$fragment</body></html>
""".trimIndent()
