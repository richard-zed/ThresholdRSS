package dev.daisy.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.daisy.core.DiscoverState
import dev.daisy.core.Item
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The root of the UI: entries, the feed list, and the add flow.
 *
 * Navigation is one sealed [Screen] plus a nullable open entry, not
 * Navigation-Compose. Daisy has three flat destinations and one level of
 * depth; a nav graph would add a dependency and a back-stack concept to carry
 * a single Long.
 */
/**
 * The destination the back stack is currently showing.
 *
 * One sealed value rather than Navigation-Compose: Daisy has three flat
 * destinations and no arguments beyond a feed id, so a nav graph would add a
 * dependency and a back-stack concept to carry one Long. `BackHandler` in the
 * app root unwinds it.
 */
private sealed interface Screen {
    data object All : Screen
    data class OneFeed(val feedId: Long) : Screen
    data object Feeds : Screen
    data object Add : Screen
}

@Composable
fun DaisyApp(vm: DaisyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.All) }
    var openEntry by remember { mutableStateOf<Item?>(null) }

    // Every screen above All pops to All. Attempting to leave the app from a
    // sub-screen instead would close it, which is not what a top bar's back
    // arrow does.
    BackHandler(enabled = screen != Screen.All) { screen = Screen.All }

    val entry = openEntry
    when {
        entry != null -> ArticleScreen(item = entry, onBack = { openEntry = null })

        screen is Screen.Add -> AddFeedScreen(
            discover = state.discover,
            onFind = vm::findFeed,
            onConfirm = vm::confirmAdd,
            onDismissDiscover = vm::dismissDiscover,
            onImportOpml = { body -> vm.importOpml(body) },
            onBack = { screen = Screen.All },
        )

        screen is Screen.Feeds -> FeedPickerScreen(
            state = state,
            onBack = { screen = Screen.All },
            onShowAll = {
                vm.selectFeed(null)
                screen = Screen.All
            },
            onOpenFeed = { feedId ->
                vm.selectFeed(feedId)
                screen = Screen.OneFeed(feedId)
            },
            onRemoveFeed = vm::removeFeed,
        )

        else -> FeedListScreen(
            state = state,
            screen = screen,
            onSync = vm::sync,
            onShowAll = {
                vm.selectFeed(null)
                screen = Screen.All
            },
            onOpenEntry = { openEntry = it },
            onShowFeeds = { screen = Screen.Feeds },
            onShowAdd = { screen = Screen.Add },
            onExportOpml = { vm.exportOpml() },
            onMessageShown = vm::dismissMessage,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedListScreen(
    state: UiState,
    screen: Screen,
    onSync: () -> Unit,
    onShowAll: () -> Unit,
    onOpenEntry: (Item) -> Unit,
    onShowFeeds: () -> Unit,
    onShowAdd: () -> Unit,
    onExportOpml: suspend () -> String,
    onMessageShown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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

    // One-shot. Clearing the field is not optional: UiState.message has no
    // timer, so without the dismiss call this effect re-fires on every
    // recomposition that touches `state` -- the same snackbar, again and again,
    // for the rest of the session. showSnackbar suspends until the toast is
    // gone, which is why the clear goes after it rather than before.
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        onMessageShown()
    }

    // Pull-to-refresh reports through this flag rather than the state stream,
    // because the indicator has to retract the moment the sync finishes and
    // `state.syncing` is cleared in a finally block one frame later.
    var pullRefreshing by remember { mutableStateOf(false) }
    LaunchedEffect(state.syncing) {
        if (!state.syncing) pullRefreshing = false
    }

    val oneFeed = screen as? Screen.OneFeed
    val feedTitle = oneFeed?.let { s ->
        state.feeds.firstOrNull { it.id == s.feedId }?.title
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            oneFeed != null -> feedTitle ?: "Feed"
                            else -> "Daisy"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (oneFeed != null) {
                        IconButton(onClick = onShowAll) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All feeds")
                        }
                    }
                },
                actions = {
                    // Refresh lives in the pull gesture now. The spinner stays
                    // so a sync started by another screen is still visible.
                    if (state.syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .width(20.dp)
                                .height(20.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = oneFeed != null,
                    onClick = onShowFeeds,
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Feeds") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onShowAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = { Text("Add") },
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { exportLauncher.launch("daisy-subscriptions.opml") },
                    icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    label = { Text("Export") },
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                pullRefreshing = true
                onSync()
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.isEmpty -> EmptyState()

                state.buckets.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Text(
                        "Nothing here yet. Pull down to refresh.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
                                faviconUrl = feed?.faviconUrl,
                                failure = state.failures[item.feedId],
                                onClick = { onOpenEntry(item) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The feed list, as its own screen.
 *
 * Separate from the entries screen rather than a section tacked onto the
 * bottom of it. When the two shared one scroll container the merged entry
 * stream and the feed rows interleaved badly, and a feed could appear between
 * two entries from itself.
 *
 * Swipe-to-remove is the only affordance: the row is a feed, and rows that
 * double as a button and a delete target are how taps get misread.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedPickerScreen(
    state: UiState,
    onBack: () -> Unit,
    onShowAll: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    onRemoveFeed: (Long) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Feeds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "All entries")
                    }
                },
                actions = {
                    TextButton(onClick = onShowAll) { Text("All") }
                },
            )
        },
    ) { padding ->
        if (state.feeds.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), Alignment.Center) {
                Text("No feeds yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(state.feeds, key = { "feed-${it.id}" }) { feed ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        // Destructive, so a swipe alone does not commit it: the
                        // row springs back and the delete runs only once the
                        // user is far enough past the edge to have meant it.
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                onRemoveFeed(feed.id)
                                true
                            } else {
                                false
                            }
                        },
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.errorContainer)
                                    .padding(horizontal = 24.dp),
                                Alignment.CenterEnd,
                            ) {
                                Text(
                                    "Remove",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        },
                    ) {
                        ListItem(
                            headlineContent = {
                                Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                // A failing feed says so here rather than only
                                // on its rows: the picker is where a broken
                                // feed is diagnosed, and "Checked 3:41 PM"
                                // reads as healthy even when every refresh
                                // has thrown since. Failure wins over the
                                // timestamp because the timestamp is not the
                                // thing the user needs to know.
                                val failure = state.failures[feed.id]
                                Text(
                                    failure
                                        ?: feed.lastFetched?.let { stamp ->
                                            "Checked " + DateFormat.getDateTimeInstance(
                                                DateFormat.SHORT,
                                                DateFormat.SHORT,
                                            ).format(Date(stamp))
                                        }
                                        ?: "Never checked",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (failure != null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            },
                            leadingContent = feed.faviconUrl?.let { href ->
                                {
                                    AsyncImage(
                                        model = href,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape),
                                    )
                                }
                            },
                            // The row's own background, so the red swipe
                            // background does not show through while idle.
                            colors = ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            modifier = Modifier.clickable { onOpenFeed(feed.id) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Add a feed: type an address, find it, confirm it, or import a file.
 *
 * Full screen rather than a dialog because the flow has three states that the
 * user needs to read -- the walking spinner with the candidate being tried,
 * then the resolved URL and title, then failure with the list of what was
 * attempted. A dialog sized for a text field cannot show any of that.
 *
 * Discovery does NOT subscribe. It resolves to [DiscoverState.Found] and
 * stops; [onConfirm] is a separate deliberate tap. Previously the feed was
 * added as soon as the walk succeeded, so a site offering several feeds
 * attached whichever the sniffer reached first, with nothing shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddFeedScreen(
    discover: DiscoverState,
    onFind: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismissDiscover: () -> Unit,
    onImportOpml: suspend (String) -> Unit,
    onBack: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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
                onBack()
            }
        }
    }

    val busy = discover is DiscoverState.Looking
    val canSubmit = text.isNotBlank() && !busy

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Add feed") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                enabled = !busy,
                label = { Text("Site or feed address") },
                placeholder = { Text("example.com") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Search,
                ),
                keyboardActions = KeyboardActions(onSearch = { if (canSubmit) onFind(text.trim()) }),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { onFind(text.trim()) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Find feed")
            }

            Spacer(Modifier.height(24.dp))

            // The three result states. Each one replaces the block below it
            // rather than stacking, so a failure after a success does not
            // leave the confirm button on screen.
            when (discover) {
                is DiscoverState.Looking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            discover.current ?: "Looking for a feed…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                is DiscoverState.Found -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Feed found", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        if (!discover.title.isNullOrBlank()) {
                            Text(discover.title, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            discover.feedUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                onConfirm()
                                onBack()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Add this feed")
                        }
                    }
                }

                is DiscoverState.NotFound -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "No feed found",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        // Detail first, falling back to the outcome name: a
                        // 404 already reads "404" in detail, but a NOT_A_FEED
                        // usually has no detail string and "not a feed" is the
                        // only thing that explains why the walk moved on.
                        discover.tried.take(8).forEach { a ->
                            Text(
                                "${a.url}  ${a.detail ?: a.outcome.name.lowercase().replace('_', ' ')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onDismissDiscover) { Text("Dismiss") }
                    }
                }

                DiscoverState.Idle -> Unit
            }

            Spacer(Modifier.weight(1f))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            TextButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Import from OPML file")
            }
        }
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

/**
 * One entry, as it appears in a list.
 *
 * Layout is a single thumbnail spanning the whole text block, not one image
 * per line: the image is sized to the text's height and sits to its left, so
 * headline, first line and second line all read against it. Below the block,
 * a footer of favicon, feed name and time.
 *
 * The image is optional. Feeds without one get the text at full width rather
 * than an empty column -- a reserved 96dp gutter for an image that will never
 * arrive makes every text-only feed look broken.
 *
 * Not private: ReaderScreen renders the same row for a single feed. Two copies
 * would drift.
 */
@Composable
internal fun ItemRow(
    item: Item,
    feedTitle: String,
    faviconUrl: String?,
    failure: String?,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row {
            val href = item.imageUrl
            if (!href.isNullOrBlank()) {
                AsyncImage(
                    model = href,
                    // Empty: the headline carries the meaning. A description
                    // repeating it is noise for a screen reader.
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        // Aligned to the FIRST line, so the thumbnail and the
                        // headline share a top edge.
                        .clip(MaterialTheme.shapes.small),
                )
                Spacer(Modifier.width(12.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                val body = plain(item.summary)
                if (body.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = body,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Feed identity, one line: icon, name, then the time pushed right
            // so it lines up down the column regardless of name length.
            if (!faviconUrl.isNullOrBlank()) {
                AsyncImage(
                    model = faviconUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.width(6.dp))
            }
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
            Spacer(Modifier.width(8.dp))
            Text(
                text = entryTime(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }

        if (failure != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = failure,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Time of day for today, date otherwise.
 *
 * An entry with no published date falls back to when we stored it. That is not
 * the same fact, but it is the only timestamp such an item has, and showing an
 * empty footer implies the feed is broken when it is merely undated.
 */
private fun entryTime(item: Item): String {
    val stamp = item.published ?: item.fetchedAt
    val sameDay = stamp >= startOfToday()
    return if (sameDay) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(stamp))
    } else {
        DateFormat.getDateInstance(DateFormat.SHORT).format(Date(stamp))
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
