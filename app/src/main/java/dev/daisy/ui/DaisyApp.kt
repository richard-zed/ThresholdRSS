package dev.daisy.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableFloatStateOf
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.Color
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
 * How long a removed feed's row is left alone before it is actually deleted.
 *
 * The row is swiped away by [SwipeToDismissBox], which animates. Deleting from
 * the list at the same moment cancels that animation, because the row stops
 * existing part-way through it. Holding the delete back for the animation's own
 * duration lets the row finish leaving before it is told to go.
 */

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
    //
    // The filter has to be cleared with the screen. Every explicit "go home"
    // button already called selectFeed(null), but back did not -- so backing
    // out of one feed's view landed on All while the list stayed filtered to
    // that feed, and tapping Add then confirming a feed left you looking at
    // the old one. The screen and the filter are two halves of one piece of
    // navigation state and cannot be changed separately.
    // Enabled while an article is open as well as while a sub-screen is.
    // An article opened from the merged list leaves `screen` on All, so the
    // old condition was false and the system back closed the whole app
    // instead of returning to the list. ArticleScreen has its own handler for
    // the same gesture; both call the same close, and Compose uses the
    // innermost enabled one, so they cannot disagree about the destination.
    BackHandler(enabled = screen != Screen.All || openEntry != null) {
        if (openEntry != null) {
            openEntry = null
        } else {
            screen = Screen.All
            vm.selectFeed(null)
        }
    }

    val entry = openEntry
    when {
        entry != null -> ArticleScreen(
            item = entry,
            onBack = { openEntry = null },
            // Home: the merged list is where this was opened from, and it is
            // the destination this article belongs to.
            bar = {
                DestinationBar(
                    current = Screen.All,
                    // Clearing openEntry is what actually leaves the article:
                    // `entry != null` is tested first, so setting the screen
                    // alone changed nothing and the Feeds button looked dead.
                    // All and Add cleared it; this one did not.
                    onShowFeeds = { openEntry = null; screen = Screen.Feeds },
                    onShowAll = {
                        openEntry = null
                        vm.selectFeed(null)
                        screen = Screen.All
                    },
                    onShowAdd = { openEntry = null; screen = Screen.Add },
                )
            },
        )

        screen is Screen.Add -> AddFeedScreen(
            discover = state.discover,
            onFind = vm::findFeed,
            onConfirm = vm::confirmAdd,
            onDismissDiscover = vm::dismissDiscover,
            onImportOpml = { body -> vm.importOpml(body) },
            onBack = { screen = Screen.All },
            onShowAll = {
                vm.selectFeed(null)
                screen = Screen.All
            },
            onShowFeeds = { screen = Screen.Feeds },
        )

        screen is Screen.Feeds -> FeedPickerScreen(
            state = state,
            onShowAll = {
                vm.selectFeed(null)
                screen = Screen.All
            },
            onOpenFeed = { feedId ->
                vm.selectFeed(feedId)
                screen = Screen.OneFeed(feedId)
            },
            onRemoveFeed = vm::removeFeed,
            onMoveFeed = vm::moveFeed,
            onExportOpml = { vm.exportOpml() },
            onShowAdd = { screen = Screen.Add },
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
            onOpenFeed = { feedId ->
                vm.selectFeed(feedId)
                screen = Screen.OneFeed(feedId)
            },
            onShowFeeds = { screen = Screen.Feeds },
            onShowAdd = { screen = Screen.Add },
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
    onOpenFeed: (Long) -> Unit,
    onShowFeeds: () -> Unit,
    onShowAdd: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

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

    // Pull-to-refresh reports through this flag rather than reading
    // `state.syncing` directly. The spinner has to retract when the sync
    // finishes, and `state.syncing` clears in a finally block a frame later --
    // reading it directly would leave the indicator spinning after the work
    // was done.
    //
    // The trap: this effect keys on `state.syncing`, and onRefresh sets
    // pullRefreshing = true in the SAME event that calls sync(). sync() sets
    // syncing = true inside a coroutine, so the effect does not necessarily
    // re-run before the sync completes -- but the very first run of this effect
    // happens at first composition, when syncing is false, and clears the flag.
    // Worse, `if (!state.syncing) pullRefreshing = false` fires on EVERY
    // recomposition where syncing is false, which includes the recomposition
    // caused by onRefresh setting the flag. The flag was being cleared before
    // it was ever painted, so isRefreshing never went true and the indicator
    // never appeared.
    //
    // Fix: only retract on a true -> false EDGE of state.syncing. Tracking the
    // previous value means a recomposition with syncing still false is not an
    // edge and does nothing.
    var pullRefreshing by remember { mutableStateOf(false) }
    var wasSyncing by remember { mutableStateOf(state.syncing) }
    LaunchedEffect(state.syncing) {
        if (wasSyncing && !state.syncing) pullRefreshing = false
        wasSyncing = state.syncing
    }

    val oneFeed = screen as? Screen.OneFeed

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            DestinationBar(
                current = screen,
                onShowFeeds = onShowFeeds,
                onShowAll = onShowAll,
                onShowAdd = onShowAdd,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = pullRefreshing,
            onRefresh = {
                pullRefreshing = true
                // A sync with no feeds returns immediately without touching
                // `syncing`, so no edge would ever arrive and the indicator
                // would spin forever. Ask before arming it.
                if (state.feeds.isEmpty()) {
                    pullRefreshing = false
                } else {
                    onSync()
                }
            },
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                // The top bar is gone, so nothing else reserves the status
                // bar. Without this the first date rule slides under the
                // clock.
                .statusBarsPadding(),
        ) {
            // Every branch has to be a SCROLLABLE, including the empty ones.
            // PullToRefreshBox does not run its own gesture detector -- it
            // reads nested-scroll from its content, so a plain Box gives it
            // nothing to listen to and the drag is swallowed. That is why
            // refresh appeared dead on an empty list while working on a
            // populated one. A LazyColumn with a single item weighs nothing
            // and scrolls, so the gesture comes back.
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.isEmpty -> FillingList { EmptyState() }

                state.buckets.isEmpty() -> FillingList {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Text(
                            "Nothing here yet. Pull down to refresh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    // FLAT list: a header row then its entries, appended into
                    // one stream. The previous version emitted a DayHeader and
                    // then called items() INSIDE an itemsIndexed() block.
                    // Emitting extra composables from an item slot is legal,
                    // but a nested items() there does not create lazy rows --
                    // it paints them all into that one slot's composable.
                    // The result laid out, so it looked plausible, but the
                    // bucket was a single giant row: recycling broke, and
                    // entries could not be placed relative to their own
                    // separator once the list scrolled.
                    //
                    // Header keys are index-suffixed so a duplicate dayStart
                    // is a visible stutter rather than a measure-pass crash.
                    state.buckets.forEachIndexed { bi, bucket ->
                        item(key = "day-${bucket.dayStart}-$bi") {
                            DayHeader(bucket.dayStart)
                        }
                        items(
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
                                // Tapping the feed name narrows to that feed,
                                // which is the same thing picking it from the
                                // Feeds screen does.
                                onFeedClick = { onOpenFeed(item.feedId) },
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
    onShowAll: () -> Unit,
    onShowAdd: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    onRemoveFeed: (Long) -> Unit,
    onMoveFeed: (Int, Int) -> Unit,
    onExportOpml: suspend () -> String,
) {
    // No top bar, matching the entry list. These three screens are peers, so
    // they carry the same chrome -- a toolbar on two of them made switching
    // between them look like entering and leaving an app.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        // The same bar as every other top-level screen. "Back" was a row of
        // text that only this screen had; the bar's Home tab does the same
        // job in the place the user already looks for it.
        bottomBar = {
            DestinationBar(
                current = Screen.Feeds,
                onShowFeeds = {},
                onShowAll = onShowAll,
                onShowAdd = onShowAdd,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
        if (state.feeds.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                Text("No feeds yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            // weight(1f), not fillMaxSize: the export footer sits below this
            // in the same Column, and a child asking for the whole height
            // leaves it nothing -- the button would be pushed off the bottom
            // edge exactly when there are feeds to export.
            // Reordering. Long-press starts a drag; the row follows the finger
            // vertically and the list reorders when it crosses a row boundary.
            //
            // The index bookkeeping is done here rather than with a library
            // because the rows are simultaneously swipe-to-dismiss targets, so
            // the drag has to stay strictly vertical: a horizontal drag must
            // fall through to SwipeToDismissBox untouched, which is why only
            // dy is consumed.
            val listState = rememberLazyListState()
            var draggingKey by remember { mutableStateOf<String?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }

            fun rowHeightPx(): Float {
                val info = listState.layoutInfo
                return info.visibleItemsInfo.firstOrNull()?.size?.toFloat()
                    ?: info.visibleItemsInfo.lastOrNull()?.size?.toFloat()
                    ?: 0f
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(state.feeds, key = { _, f -> "feed-${f.id}" }) { index, feed ->
                    val key = "feed-${feed.id}"
                    val isDragging = draggingKey == key
                    // Shift accumulated while dragging, converted back to an
                    // index step. Kept in a plain float rather than snapping to
                    // an integer so a drag shorter than a row still shows
                    // movement instead of sitting dead until it crosses over.
                    val nudge = if (isDragging) dragOffset else 0f
                    var committed by remember(feed.id) { mutableStateOf(false) }
                    val dismissState = rememberSwipeToDismissBoxState(
                        // Deleting a feed is not undoable from here, so the
                        // swipe has to be a decision rather than a gesture
                        // that happened. The default threshold fires far too
                        // early on a phone -- a short flick across the row
                        // commits it -- so this demands a swipe most of the
                        // way across, and only counts distance toward the
                        // dismiss direction, which is end-to-start (left).
                        positionalThreshold = { totalDistance -> totalDistance * 0.6f },
                        // Destructive, so a swipe alone does not commit it:
                        // returning TRUE tells the box the row really is
                        // dismissed, which parks it off-screen and lets it play
                        // its dismissal instead of bouncing back.
                        //
                        // The delete is then held back for the animation, so the
                        // row leaves on its own rather than blinking out mid
                        // slide. The row is removed from the list only when the
                        // body actually runs, and `committed` stops a second
                        // callback firing it twice.
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart && !committed) {
                                committed = true
                                // Remove now, not after a delay.
                                //
                                // The delay existed to let the row finish its
                                // dismissal before the list changed, because
                                // the state update used to take long enough
                                // that the two were visibly separate events.
                                // Now that the update is in-memory it lands in
                                // the same frame as the swipe, and
                                // animateItem() animates the exit from there.
                                // Keeping the delay on top of that is what
                                // made the row snap back and then jump.
                                onRemoveFeed(feed.id)
                                true
                            } else {
                                value != SwipeToDismissBoxValue.EndToStart
                            }
                        },
                    )

                    SwipeToDismissBox(
                        state = dismissState,
                        // Slides the rows below up into the freed space instead
                        // of them teleporting. animateItem() is the current
                        // modifier: it replaced animateItemPlacement, which was
                        // deprecated and then removed, so the old name no longer
                        // resolves at all rather than merely warning.
                        // zIndex lifts the dragged row over its neighbours so
                        // the row it slides across does not paint on top of it.
                        modifier = Modifier
                            .animateItem()
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset { IntOffset(0, nudge.roundToInt()) }
                            // Keyed on index as well as id: the lambda closes
                            // over `index`, and after a reorder every row's
                            // index changes while its id does not. Keying on
                            // the id alone leaves the captured index stale, so
                            // a second drag would move the row from where it
                            // used to be.
                            .pointerInput(key, index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingKey = key
                                        dragOffset = 0f
                                    },
                                    onDragEnd = {
                                        // Commit on release rather than mid-drag:
                                        // reordering on every crossed boundary
                                        // rewrites meta.json dozens of times for
                                        // one gesture, and each write publishes a
                                        // new list, which fights the drag itself.
                                        val h = rowHeightPx()
                                        if (h > 0f) {
                                            val step = (dragOffset / h).roundToInt()
                                            val target = (index + step)
                                                .coerceIn(0, state.feeds.lastIndex)
                                            if (target != index) onMoveFeed(index, target)
                                        }
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingKey = null
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        // Only dy is consumed. Consuming the
                                        // horizontal delta would starve
                                        // SwipeToDismissBox, and the two
                                        // gestures would fight over every drag.
                                        change.consume()
                                        dragOffset += amount.y
                                    },
                                )
                            },
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
                            // A visible handle, because the drag is otherwise
                            // undiscoverable: a long-press that reorders
                            // leaves no trace on screen, and nothing about a
                            // feed row suggests it can be held. The icon is
                            // decorative -- the gesture is bound to the whole
                            // row, so the handle does not need to be a target
                            // and should not steal the tap that opens a feed.
                            trailingContent = {
                                Icon(
                                    imageVector = Icons.Default.Menu,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
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
            // Export lives here rather than in the bottom bar. It acts on the
            // subscriptions as a whole, which is what this screen lists, and
            // it is a one-off action -- a bare action next to three
            // destinations is what made the bar read wrong.
            // Import and export are the same action in two directions, so
            // they get the same treatment: a divider, then a full-width
            // button at one height. Export used to carry an icon and a fixed
            // height while import was bare, which made two halves of one
            // feature look like two unrelated controls.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { exportLauncher.launch("daisy-subscriptions.opml") },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Export subscriptions")
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
    onShowAll: () -> Unit,
    onShowFeeds: () -> Unit,
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
        bottomBar = {
            DestinationBar(
                current = Screen.Add,
                onShowFeeds = onShowFeeds,
                onShowAll = onShowAll,
                onShowAdd = {},
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Column(Modifier.padding(16.dp)) {
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

            }

            // Outside the padded column, matching the export footer on the
            // Feeds screen. Inside it the divider stopped at the 16dp inset
            // and the button text sat inset while export's ran edge to edge,
            // so two halves of one feature read as two different controls.
            // The weight is on the spacer inside the scrollable content, so
            // this block pins to the bottom of the screen either way.
            Spacer(Modifier.weight(1f))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text("Import subscriptions")
            }
        }
    }
}

/**
 * The bottom bar, shared by every top-level screen.
 *
 * One bar rendered three times rather than three bars. The destinations do
 * not change with the screen, only which one is lit, so a second copy would
 * be three places to keep in step -- which is the drift this app already
 * showed once.
 *
 * The current screen is passed in so `selected` is derived from one source
 * of truth. When only the entry list had a bar, selection was inferred from
 * a local variable the other screens did not have, which is why this could
 * not simply be copied across.
 *
 * Icon above label, both tinted from the same selected/unselected colour.
 * The labels were briefly dropped -- the glyphs are unambiguous at this size
 * and three words is a lot of chrome -- but the bar reads as unlabelled
 * furniture without them, so they are back and the bar grew to 64dp to hold
 * both. The icon keeps its contentDescription: the visible label is text, not
 * a description, and TalkBack should not announce two names for one target.
 */
@Composable
private fun DestinationBar(
    current: Screen,
    onShowFeeds: () -> Unit,
    onShowAll: () -> Unit,
    onShowAdd: () -> Unit,
) {
    // Compact by hand rather than NavigationBar: the stock bar pads ~80dp
    // tall with generous horizontal insets on every item, which is a lot of
    // chrome for three destinations on a phone.
    //
    // 64dp, not the 56dp toolbar height it used to be. 56 is right for icons
    // alone; an icon plus a label needs the extra 8 or the label crowds the
    // bottom edge and the pair sits high in the box.
    //
    // The background has to be applied BEFORE navigationBarsPadding, not
    // after. Modifier order is outside-in: with the padding first, the
    // padding pushes the background (and the 64dp content) up out of the
    // gesture area, leaving a transparent strip from the bar's bottom edge
    // to the screen bottom. Drawing the background first and letting the
    // inset expand the box inside it makes the tint run to the physical
    // bottom while the icons stay above the gesture handle.
    //
    // For the same reason .height(56.dp) is followed by insetsPadding, not
    // the other way round: height sets the CONTENT row, and the insets are
    // added on top of that height rather than eaten from it.
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Feeds covers a single feed's view too: opening a feed is a
            // detail of the Feeds destination, not a fourth one, so the tab
            // stays lit while you are inside it.
            BarTab(
                label = "Feeds",
                selected = current is Screen.Feeds || current is Screen.OneFeed,
                onClick = onShowFeeds,
                modifier = Modifier.weight(1f),
            ) { tint ->
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = "Feeds",
                    tint = tint,
                )
            }
            BarTab(
                label = "Home",
                selected = current is Screen.All,
                onClick = onShowAll,
                modifier = Modifier.weight(1f),
            ) { tint ->
                Icon(Icons.Filled.Home, contentDescription = "Home", tint = tint)
            }
            BarTab(
                label = "Add",
                selected = current is Screen.Add,
                onClick = onShowAdd,
                modifier = Modifier.weight(1f),
            ) { tint ->
                Icon(Icons.Filled.Add, contentDescription = "Add feed", tint = tint)
            }
        }
    }
}

/**
 * One bottom-bar destination.
 *
 * Hand-rolled rather than NavigationBarItem so the bar can be 64dp: the stock
 * item reserves 80dp of vertical space for a label plus its own padding, and
 * three of those is a fifth of a phone screen spent on chrome.
 *
 * The icon arrives as a lambda taking the tint, so the selected/unselected
 * colour choice happens once here instead of at all three call sites.
 */
@Composable
private fun BarTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon(tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * A date divider between runs of entries.
 *
 * Not a full-width filled band. A tinted slab the width of the screen reads as
 * a section header belonging to a second list -- which is exactly what it
 * looked like, two feeds stacked in one. This is a hairline rule with the
 * label sitting in it, on the list background, so it separates without
 * announcing a new screen.
 *
 * The label is centred and the rules are equal weights either side, which is
 * also why the rule does not simply run edge to edge: a full-bleed line still
 * cuts the page in half.
 */

@Composable
private fun DayHeader(dayStart: Long) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = line)
        Text(
            text = dayLabel(dayStart),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = line)
    }
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
    onFeedClick: () -> Unit = {},
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
                // A post has no title; its text IS the title. Feeds that omit
                // <title> entirely (Mastodon and other status feeds) would
                // otherwise render a wall of "(untitled)". Use the body as the
                // headline and then do not print it twice underneath.
                val body = plain(item.summary)
                val headlined = item.title.ifBlank { body }
                Text(
                    text = headlined.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (body.isNotEmpty() && body != headlined) {
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
                        .clip(CircleShape)
                        .clickable(onClick = onFeedClick),
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
                    // NO WEIGHT. Two weighted children in one Row share the
                    // slack, so the timestamp's position depended on how long
                    // the feed name happened to be -- three names, three
                    // alignments. The spacer below is now the only flexible
                    // element, which pins the time to the right edge for
                    // every row.
                    //
                    // Bounded by fillMaxWidth(0.6f) rather than a weight:
                    // weightless with no bound let a long name push the time
                    // clean off the edge, because maxLines/ellipsis clip
                    // HEIGHT, not width. A fraction of the row leaves the
                    // timestamp its space no matter what the feed is called.
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .clickable(onClick = onFeedClick),
                )
            }
            // The only weighted child in the row, so the time lands hard
            // against the right edge and lines up down the column.
            Spacer(Modifier.weight(1f))
            Text(
                text = entryTime(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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

/**
 * A one-item LazyColumn that fills the screen.
 *
 * Exists so an empty or loading screen still presents a scroll container to
 * PullToRefreshBox, which reads the nested-scroll stream from its content
 * rather than sensing the drag itself. Content that cannot scroll cannot
 * overscroll, so without this the pull gesture is dropped on exactly the
 * screens where a user is most likely to try it.
 */
@Composable
private fun FillingList(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.fillParentMaxSize()) { content() }
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
