package dev.daisy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.daisy.core.DiscoverState
import dev.daisy.core.Item
import java.text.DateFormat
import java.util.Date

/**
 * The root of the UI: a feed list, and a reader pushed over it.
 *
 * Navigation is one nullable field rather than Navigation-Compose. Daisy has
 * two destinations and one level of depth; a nav graph would add a dependency
 * and a back-stack concept to carry a single Long.
 */
@Composable
fun DaisyApp(vm: DaisyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var openFeedId by remember { mutableStateOf<Long?>(null) }

    val feedId = openFeedId
    if (feedId != null) {
        ReaderScreen(
            feedId = feedId,
            vm = vm,
            onBack = { openFeedId = null },
        )
    } else {
        FeedListScreen(
            state = state,
            onSync = vm::sync,
            onAdd = vm::addFeed,
            onDismissDiscover = vm::dismissDiscover,
            onOpenFeed = { openFeedId = it },
            onRemoveFeed = vm::removeFeed,
            onImportOpml = { body -> vm.importOpml(body) },
            onExportOpml = { vm.exportOpml() },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedListScreen(
    state: UiState,
    onSync: () -> Unit,
    onAdd: (String) -> Unit,
    onDismissDiscover: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    onRemoveFeed: (Long) -> Unit,
    onImportOpml: suspend (String) -> Unit,
    onExportOpml: suspend () -> String,
) {
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // OPML import. The file is read here and the TEXT handed to the view model,
    // so the view model needs no ContentResolver and stays testable.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val body = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().decodeToString()
                    }
                }.getOrNull()
            }
            if (body == null) {
                snackbar.showSnackbar("Could not read that file")
            } else {
                onImportOpml(body)
            }
        }
    }

    // Export. Same shape: the view model produces the document, this writes it.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-opml"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val body = onExportOpml()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(body.encodeToByteArray())
                    }
                }.isSuccess
            }
            snackbar.showSnackbar(if (ok) "Exported" else "Could not write that file")
        }
    }

    // One-shot: showing the message and clearing the field is what stops it
    // re-firing on the next recomposition.
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Daisy") },
                actions = {
                    // Text rather than icons on purpose. material-icons-core
                    // ships only a handful of glyphs and neither Refresh nor
                    // Add is reliably among them; the -extended artifact adds
                    // several megabytes for two VectorDrawables, so the
                    // labels cost less in every sense.
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .width(20.dp)
                                .height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(onClick = onSync) { Text("Refresh") }
                    }
                    TextButton(onClick = { adding = true }) { Text("Add") }
                    // Import/Export rather than icons: material-icons is not a
                    // dependency, and labels cost less to explain than glyphs.
                    TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import")
                    }
                    TextButton(
                        onClick = {
                            exportLauncher.launch("daisy-subscriptions.opml")
                        },
                    ) {
                        Text("Export")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {

            // Discovery progress, inline under the bar. It names the URL being
            // tried because a silent spinner during a seven-candidate walk
            // reads as a hang.
            when (val d = state.discover) {
                is DiscoverState.Looking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        text = d.current ?: "Looking for a feed…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                is DiscoverState.NotFound -> {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "No feed found at that address",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        d.tried.take(6).forEach { a ->
                            Text(
                                "${a.url}  ${a.detail.orEmpty()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "Dismiss",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .clickable(onClick = onDismissDiscover),
                        )
                    }
                }

                else -> Unit
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.isEmpty -> EmptyState()

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(
                        state.buckets,
                        // Index-suffixed ON PURPOSE. A bucket key must be
                        // unique or the measure pass throws, and a duplicate
                        // day is a data problem (DayBuckets no longer emits
                        // one, but a future caller can) -- a stutter in the
                        // list is survivable, a crash is not. Two headers is a
                        // visible symptom; the duplicate-key exception is not.
                        key = { i, b -> "day-${b.dayStart}-$i" },
                    ) { _, bucket ->
                        DayHeader(bucket.dayStart)
                        // Kotlin resolves an unqualified items() inside the
                        // outer items() block against LazyItemScope, which has
                        // no such member, so the compiler refuses it rather
                        // than silently picking one. this@LazyColumn names the
                        // scope that does have it.
                        this@LazyColumn.items(
                            bucket.items,
                            // Item.id is only unique within a feed, so the key
                            // has to combine both. A bare id would collide as
                            // soon as two feeds carry an item numbered 1.
                            key = { "${it.feedId}:${it.id}" },
                        ) { item ->
                            val feed = state.feeds.firstOrNull { it.id == item.feedId }
                            ItemRow(
                                item = item,
                                feedTitle = feed?.title.orEmpty(),
                                failure = state.failures[item.feedId],
                                onClick = { onOpenFeed(item.feedId) },
                            )
                        }
                    }

                    item("feeds-header") {
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                        Text(
                            "Feeds",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }

                    items(state.feeds, key = { "feed-${it.id}" }) { feed ->
                        ListItem(
                            headlineContent = {
                                Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val detail = feed.lastFetched?.let {
                                    "Checked " + DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT,
                                        DateFormat.SHORT,
                                    ).format(Date(it))
                                } ?: "Never checked"
                                Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = {
                                // TextButton rather than a Text with clickable.
                                // Compose delivers the touch to the child first, so
                                // the original did receive taps -- but a bare Text
                                // with clickable gets only the width of the word as
                                // its touch target, and a TextButton has the 48dp
                                // minimum plus a ripple, so a tap that misses the
                                // glyph by a few dp now still lands.
                                //
                                // No icon: material-icons was deliberately left out
                                // of the build, and pulling it in for one glyph is
                                // not worth it.
                                TextButton(onClick = { onRemoveFeed(feed.id) }) {
                                    Text(
                                        "Remove",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            },
                            modifier = Modifier.clickable { onOpenFeed(feed.id) },
                        )
                    }
                }
            }
        }
    }

    if (adding) {
        AddFeedDialog(
            onDismiss = { adding = false },
            onSubmit = { url ->
                adding = false
                onAdd(url)
            },
        )
    }
}

@Composable
private fun DayHeader(dayStart: Long) {
    Text(
        text = dayLabel(dayStart),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

// Not private: ReaderScreen.kt renders the same row for a single feed. Two
// copies would drift, and they already carry the same key/callback shape.
@Composable
internal fun ItemRow(
    item: Item,
    feedTitle: String,
    failure: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = item.title.ifBlank { "(untitled)" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )

        val body = plain(item.summary)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (feedTitle.isNotEmpty() || failure != null) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (feedTitle.isNotEmpty()) {
                    Text(
                        text = feedTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (failure != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "· $failure",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No feeds yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add a site address or a feed URL. Daisy works out which.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Strips tags and entities from a feed body.
 *
 * Not a renderer. This is a three-line teaser in a list row, so the only goal
 * is that no tag or `&amp;` is ever visible. Anything richer belongs in the
 * reader, which hands the raw HTML to a WebView.
 */
internal fun plain(html: String?): String {
    if (html.isNullOrBlank()) return ""
    val out = StringBuilder(html.length)
    var i = 0
    while (i < html.length) {
        when (val c = html[i]) {
            '<' -> {
                val close = html.indexOf('>', i + 1)
                if (close == -1) {
                    i = html.length
                    break
                }
                i = close + 1
                out.append(' ')
            }

            '&' -> {
                val semi = html.indexOf(';', i + 1)
                if (semi != -1 && semi - i <= 8) {
                    out.append(
                        when (html.substring(i + 1, semi)) {
                            "amp" -> '&'
                            "lt" -> '<'
                            "gt" -> '>'
                            "quot" -> '"'
                            "apos", "#39" -> '\''
                            "nbsp" -> ' '
                            else -> ' '
                        },
                    )
                    i = semi + 1
                } else {
                    out.append(c)
                    i++
                }
            }

            else -> {
                out.append(c)
                i++
            }
        }
    }
    // Collapse whitespace: stripping a tag leaves one, and feeds carry newlines.
    return out.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }.joinToString(" ")
}

internal fun dayLabel(dayStart: Long): String {
    val dayMs = 24L * 60 * 60 * 1000
    val todayStart = startOfToday()
    return when {
        dayStart >= todayStart -> "Today"
        dayStart >= todayStart - dayMs -> "Yesterday"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(dayStart))
    }
}

private fun startOfToday(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = System.currentTimeMillis()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
