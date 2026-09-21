package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.paging.LoadState
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.isRefreshing
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.datastore.model.ReaderTimelinePosition
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.data.repository.ReaderPositionRepository
import dev.dimension.flare.data.repository.ReaderPositionStore
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.tlaster.precompose.molecule.producePresenter

@Immutable
public interface TimelineWithLazyListState : TimelineItemPresenter.State {
    public val showNewToots: Boolean
    public val lazyListState: LazyStaggeredGridState
    public val newPostsCount: Int

    public fun onNewTootsShown()

    public fun saveCurrentReadPosition()

    public fun jumpToLatest()
}

/**
 * UI-side composable that exposes the timeline paging state plus scroll-bound indicator state
 * (new-toots banner, scroll-to-top etc.) bound to the supplied [lazyStaggeredGridState].
 *
 * The paging/refresh portion runs inside a molecule presenter scoped to a `ViewModel`
 * (so it survives configuration changes), while the lazyListState-dependent effects run in
 * plain Composition. This avoids capturing a stale `LazyStaggeredGridState` across Activity
 * recreation — every fresh Composition rebinds its own [lazyStaggeredGridState] to the effects.
 */
@Composable
public fun rememberTimelineItemPresenterWithLazyListState(
    item: UiTimelineTabItem,
    isHomeTimeline: Boolean = false,
    lazyStaggeredGridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
): TimelineWithLazyListState {
    val readerPositionRepository by koinInject<ReaderPositionRepository>()
    val baseState by producePresenter("timeline_${item.id}_$isHomeTimeline") {
        remember(item, isHomeTimeline) { TimelineItemPresenter(item, isHomeTimeline) }.invoke()
    }
    return rememberTimelineWithLazyListState(
        baseState = baseState,
        lazyListState = lazyStaggeredGridState,
        timelineId = item.id,
        readerPositionStore = readerPositionRepository,
    )
}

@Composable
internal fun rememberTimelineWithLazyListState(
    baseState: TimelineItemPresenter.State,
    lazyListState: LazyStaggeredGridState,
    timelineId: String? = null,
    readerPositionStore: ReaderPositionStore? = null,
): TimelineWithLazyListState {
    var newPostCount by remember { mutableIntStateOf(0) }
    var preservingRefreshPosition by remember { mutableStateOf(false) }
    var restoringReadPosition by remember(timelineId) {
        mutableStateOf(timelineId != null && readerPositionStore != null)
    }
    var initialReadPositionRestored by remember(timelineId) {
        mutableStateOf(timelineId == null || readerPositionStore == null)
    }
    var positionPersistenceEnabled by remember(timelineId) {
        mutableStateOf(timelineId == null || readerPositionStore == null)
    }
    var readBoundaryItemKey by remember(timelineId) {
        mutableStateOf<String?>(null)
    }
    val currentBaseState by rememberUpdatedState(baseState)
    val scope = rememberCoroutineScope()
    val isAtTheTop by remember(lazyListState) {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex == 0 &&
                lazyListState.firstVisibleItemScrollOffset == 0
        }
    }
    baseState.listState.onSuccess {
        val currentPagingState by rememberUpdatedState(this)
        LaunchedEffect(lazyListState) {
            var previousKeys = emptySet<String>()
            snapshotFlow {
                val pagingState = currentPagingState
                (0 until pagingState.itemCount).mapNotNull { pagingState.peek(it)?.itemKey }
            }.collect { keys ->
                if (keys.isNotEmpty()) {
                    // Count the new prefix before the first previously loaded post.
                    // Scroll indices can already have moved by the time this snapshot arrives.
                    if (
                        previousKeys.isNotEmpty() &&
                        (!isAtTheTop || preservingRefreshPosition || restoringReadPosition)
                    ) {
                        newPostCount += keys.takeWhile { it !in previousKeys }.size
                    }
                    previousKeys = keys.toSet()
                }
            }
        }
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                val index = lazyListState.firstVisibleItemIndex
                index to
                    lazyListState.layoutInfo.visibleItemsInfo
                        .firstOrNull { it.index == index }
                        ?.key
            }.drop(1)
                .collect { (index, key) ->
                    if (preservingRefreshPosition || restoringReadPosition) {
                        return@collect
                    }
                    // Consume posts on viewport changes, not paging updates whose
                    // new indices may arrive before the grid preserves its position.
                    // A measured item's key also excludes any leading header cards.
                    val pagingState = currentPagingState
                    val postIndex =
                        if (key == null) {
                            index
                        } else {
                            (0 until pagingState.itemCount).indexOfFirst { pagingState.peek(it)?.itemKey == key }
                        }
                    if (postIndex >= 0) {
                        newPostCount = minOf(newPostCount, postIndex)
                    }
                }
        }
    }
    LaunchedEffect(isAtTheTop, preservingRefreshPosition, restoringReadPosition) {
        if (isAtTheTop && !preservingRefreshPosition && !restoringReadPosition) {
            newPostCount = 0
        }
    }

    suspend fun applyTimelineAnchor(
        anchor: TimelineScrollAnchor,
        refreshedIndex: Int,
    ): Boolean {
        val targetIndex = (refreshedIndex + anchor.leadingItemCount).coerceAtLeast(0)
        val newlyPrependedPosts = (refreshedIndex - anchor.pagingIndex).coerceAtLeast(0)
        newPostCount = maxOf(newPostCount, newlyPrependedPosts)

        lazyListState.requestScrollToItem(targetIndex, anchor.scrollOffset)

        // requestScrollToItem is applied on the next remeasure. In the real rendered grid,
        // layoutInfo may still describe the pre-refresh layout for a short time, so do not
        // accept "the anchor is visible somewhere" as proof that restoration has happened.
        return withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
            snapshotFlow {
                val measuredItems = lazyListState.layoutInfo.visibleItemsInfo
                val positionApplied =
                    lazyListState.firstVisibleItemIndex == targetIndex &&
                        lazyListState.firstVisibleItemScrollOffset == anchor.scrollOffset
                val measuredAnchorApplied =
                    measuredItems.isEmpty() ||
                        measuredItems.any { it.index == targetIndex && it.key == anchor.itemKey }
                positionApplied && measuredAnchorApplied
            }.first { it }
        } != null
    }

    suspend fun findTimelineItemAcrossPages(itemKey: String): Pair<PagingState.Success<UiTimelineV2>, Int>? {
        var pagingState = currentBaseState.listState
        var appendLoads = 0

        while (appendLoads <= MAX_ANCHOR_APPEND_LOADS) {
            val success = pagingState as? PagingState.Success
            if (success != null && !pagingState.isRefreshing) {
                val index = success.indexOfItemKey(itemKey)
                if (index >= 0) {
                    return success to index
                }

                when (success.appendState) {
                    is LoadState.Error -> return null
                    is LoadState.Loading -> Unit
                    is LoadState.NotLoading -> {
                        if (success.appendState.endOfPaginationReached || success.itemCount == 0) {
                            return null
                        }
                        // A tail read is the normal Paging hint for loading the next page.
                        // It does not move the LazyStaggeredGrid viewport.
                        success[success.itemCount - 1]
                        appendLoads += 1
                    }
                }
            }

            val previousState = pagingState
            pagingState =
                withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                    snapshotFlow { currentBaseState.listState }
                        .first { candidate ->
                            if (candidate === previousState || candidate.isRefreshing) {
                                false
                            } else {
                                val candidatePaging = candidate as? PagingState.Success
                                candidatePaging != null &&
                                    candidatePaging.appendState !is LoadState.Loading
                            }
                        }
                } ?: return null
        }

        return null
    }

    suspend fun refreshPreservingScrollPosition() {
        if (restoringReadPosition) {
            withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                snapshotFlow { restoringReadPosition }.first { !it }
            }
        }

        val beforeState = currentBaseState
        val beforePagingState = beforeState.listState
        val anchor = captureTimelineScrollAnchor(beforePagingState, lazyListState)
        preservingRefreshPosition = anchor != null
        try {
            beforeState.refreshSuspend()
            if (anchor == null) return

            // A cache-backed timeline can publish more than one paging generation for a single
            // refresh (network refresh -> database invalidation -> new PagingSource generation).
            // Keep the anchor alive across those generations instead of restoring once and then
            // allowing a later snapshot to move the viewport to the newest item.
            var pagingState =
                withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                    snapshotFlow { currentBaseState.listState }
                        .first { candidate ->
                            candidate !== beforePagingState && !candidate.isRefreshing
                        }
                } ?: currentBaseState.listState

            var appendLoads = 0
            while (appendLoads <= MAX_ANCHOR_APPEND_LOADS) {
                val success = pagingState as? PagingState.Success
                if (success == null || pagingState.isRefreshing) {
                    val previousState = pagingState
                    pagingState =
                        withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                            snapshotFlow { currentBaseState.listState }
                                .first { candidate ->
                                    candidate !== previousState && !candidate.isRefreshing
                                }
                        } ?: return
                    continue
                }

                val refreshedIndex = success.indexOfItemKey(anchor.itemKey)
                if (refreshedIndex < 0) {
                    when (success.appendState) {
                        is LoadState.Error -> return
                        is LoadState.Loading -> Unit
                        is LoadState.NotLoading -> {
                            if (success.appendState.endOfPaginationReached || success.itemCount == 0) {
                                return
                            }
                            // Reading the tail emits a Paging append hint without moving the grid.
                            success[success.itemCount - 1]
                            appendLoads += 1
                        }
                    }

                    val previousState = pagingState
                    pagingState =
                        withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                            snapshotFlow { currentBaseState.listState }
                                .first { candidate ->
                                    if (candidate === previousState || candidate.isRefreshing) {
                                        false
                                    } else {
                                        val candidatePaging = candidate as? PagingState.Success
                                        candidatePaging != null &&
                                            candidatePaging.appendState !is LoadState.Loading
                                    }
                                }
                        } ?: return
                    continue
                }

                if (!applyTimelineAnchor(anchor, refreshedIndex)) {
                    return
                }

                // Do not release the preservation guard immediately. Paging + RemoteMediator can
                // emit another generation after the first restored layout. Require a quiet period;
                // if a new generation arrives, loop and restore the same stable itemKey again.
                val restoredAgainst = currentBaseState.listState
                delay(REFRESH_POSITION_STABILITY_MS)
                val latestState = currentBaseState.listState
                val latestSuccess = latestState as? PagingState.Success
                val latestIndex = latestSuccess?.indexOfItemKey(anchor.itemKey) ?: -1

                if (
                    latestState === restoredAgainst &&
                    !latestState.isRefreshing &&
                    latestIndex >= 0
                ) {
                    // Final re-application after the quiet period protects against a late grid
                    // remeasure using stale pre-refresh indices.
                    applyTimelineAnchor(anchor, latestIndex)
                    return
                }

                pagingState = latestState
            }
        } finally {
            preservingRefreshPosition = false
        }
    }
    LaunchedEffect(timelineId, readerPositionStore, lazyListState) {
        val persistentTimelineId = timelineId ?: return@LaunchedEffect
        val positionStore = readerPositionStore ?: return@LaunchedEffect

        restoringReadPosition = true
        initialReadPositionRestored = false
        positionPersistenceEnabled = false

        val savedPosition = positionStore.getPosition(persistentTimelineId)
        readBoundaryItemKey = savedPosition?.itemKey
        var restored = savedPosition == null

        if (savedPosition != null) {
            var found = findTimelineItemAcrossPages(savedPosition.itemKey)
            if (found != null) {
                var (pagingState, itemIndex) = found
                var leadingItemCount = currentLeadingItemCount(pagingState, lazyListState)

                // Everything above the saved item is unread/new from the reader's perspective.
                newPostCount = maxOf(newPostCount, itemIndex)

                restored =
                    applyTimelineAnchor(
                        anchor =
                            TimelineScrollAnchor(
                                itemKey = savedPosition.itemKey,
                                pagingIndex = itemIndex,
                                leadingItemCount = leadingItemCount,
                                scrollOffset = savedPosition.scrollOffset,
                            ),
                        refreshedIndex = itemIndex,
                    )

                // A cache/database invalidation can replace the paging generation just after the
                // first restore. Keep re-applying the persisted key until the generation is quiet.
                repeat(MAX_RESTORE_GENERATION_RETRIES) {
                    if (!restored) return@repeat
                    val restoredAgainst = currentBaseState.listState
                    delay(RESTORE_POSITION_STABILITY_MS)
                    val latestState = currentBaseState.listState
                    if (latestState === restoredAgainst && !latestState.isRefreshing) {
                        return@repeat
                    }

                    found = findTimelineItemAcrossPages(savedPosition.itemKey)
                    if (found == null) {
                        restored = false
                        return@repeat
                    }

                    pagingState = found.first
                    itemIndex = found.second
                    leadingItemCount = currentLeadingItemCount(pagingState, lazyListState)
                    newPostCount = maxOf(newPostCount, itemIndex)
                    restored =
                        applyTimelineAnchor(
                            anchor =
                                TimelineScrollAnchor(
                                    itemKey = savedPosition.itemKey,
                                    pagingIndex = itemIndex,
                                    leadingItemCount = leadingItemCount,
                                    scrollOffset = savedPosition.scrollOffset,
                                ),
                            refreshedIndex = itemIndex,
                        )
                }
            }
        }

        initialReadPositionRestored = true
        restoringReadPosition = false
        positionPersistenceEnabled = restored

        if (!restored && savedPosition != null) {
            // Do not overwrite a valid stored key with the transient newest post when restore
            // failed because the network/cache was temporarily incomplete. Once the user scrolls
            // deliberately, that interaction becomes the new authoritative resume position.
            withTimeoutOrNull(USER_OVERRIDE_WAIT_TIMEOUT_MS) {
                snapshotFlow { lazyListState.isScrollInProgress }.first { it }
                snapshotFlow { lazyListState.isScrollInProgress }.first { !it }
            }?.let {
                positionPersistenceEnabled = true
            }
        }
    }

    suspend fun persistVisibleReadPosition(force: Boolean = false) {
        val persistentTimelineId = timelineId ?: return
        val positionStore = readerPositionStore ?: return
        if (
            !initialReadPositionRestored ||
            !positionPersistenceEnabled ||
            preservingRefreshPosition ||
            restoringReadPosition
        ) {
            return
        }

        val pagingState = currentBaseState.listState
        val visiblePosition =
            captureVisibleTimelinePosition(
                timelineId = persistentTimelineId,
                pagingState = pagingState,
                lazyListState = lazyListState,
            ) ?: return

        if (!force) {
            val savedPosition = positionStore.getPosition(persistentTimelineId)
            val success = pagingState as? PagingState.Success
            if (savedPosition != null && success != null) {
                val savedIndex = success.indexOfItemKey(savedPosition.itemKey)
                val visibleIndex = success.indexOfItemKey(visiblePosition.itemKey)

                // Index 0 is newest. Looking back at an older/larger index must never move the
                // durable read boundary backwards. Only equal/newer visible posts may advance it.
                if (savedIndex >= 0 && visibleIndex >= 0 && visibleIndex > savedIndex) {
                    return
                }
                // If the saved boundary is temporarily absent from this Paging generation, keep
                // it rather than replacing it with an unproven transient position.
                if (savedIndex < 0) {
                    return
                }
            }
        }

        positionStore.savePosition(
            timelineId = visiblePosition.timelineId,
            itemKey = visiblePosition.itemKey,
            scrollOffset = visiblePosition.scrollOffset,
        )
    }

    // Persist only positions the user actually navigated to. Paging/database updates are allowed
    // to move indices internally without silently redefining the user's read boundary.
    LaunchedEffect(
        timelineId,
        readerPositionStore,
        lazyListState,
        initialReadPositionRestored,
        positionPersistenceEnabled,
    ) {
        if (
            timelineId == null ||
            readerPositionStore == null ||
            !initialReadPositionRestored ||
            !positionPersistenceEnabled
        ) {
            return@LaunchedEffect
        }

        var userScrollSeen = false
        snapshotFlow { lazyListState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) {
                    userScrollSeen = true
                } else if (userScrollSeen) {
                    persistVisibleReadPosition()
                    userScrollSeen = false
                }
            }
    }

    return object :
        TimelineWithLazyListState,
        TimelineItemPresenter.State by baseState {
        override val showNewToots = newPostCount > 0
        override val lazyListState = lazyListState
        override val newPostsCount = newPostCount
        override val isRefreshing =
            baseState.isRefreshing || preservingRefreshPosition || restoringReadPosition

        override fun refreshSync() {
            scope.launch {
                refreshPreservingScrollPosition()
            }
        }

        override suspend fun refreshSuspend() {
            refreshPreservingScrollPosition()
        }

        override fun onNewTootsShown() {
            newPostCount = 0
        }

        override fun saveCurrentReadPosition() {
            scope.launch {
                persistVisibleReadPosition()
            }
        }

        override fun jumpToLatest() {
            scope.launch {
                lazyListState.scrollToItem(0)
                persistVisibleReadPosition(force = true)
            }
        }
    }
}

private const val MAX_ANCHOR_APPEND_LOADS = 50
private const val REFRESH_POSITION_WAIT_TIMEOUT_MS = 30_000L
private const val REFRESH_POSITION_STABILITY_MS = 1_000L
private const val RESTORE_POSITION_STABILITY_MS = 750L
private const val MAX_RESTORE_GENERATION_RETRIES = 4
private const val USER_OVERRIDE_WAIT_TIMEOUT_MS = 60_000L

private data class ReaderViewportCandidate(
    val itemKey: String,
    val isScrollInProgress: Boolean,
)

private data class TimelineScrollAnchor(
    val itemKey: String,
    val pagingIndex: Int,
    val leadingItemCount: Int,
    val scrollOffset: Int,
)

private fun captureTimelineScrollAnchor(
    pagingState: PagingState<UiTimelineV2>,
    lazyListState: LazyStaggeredGridState,
): TimelineScrollAnchor? {
    val success = pagingState as? PagingState.Success ?: return null

    // Prefer a measured timeline item so an optional leading UI card does not become the anchor.
    for (visibleItem in lazyListState.layoutInfo.visibleItemsInfo.sortedBy { it.index }) {
        val key = visibleItem.key as? String ?: continue
        val pagingIndex = success.indexOfItemKey(key)
        if (pagingIndex >= 0) {
            return TimelineScrollAnchor(
                itemKey = key,
                pagingIndex = pagingIndex,
                leadingItemCount = (visibleItem.index - pagingIndex).coerceAtLeast(0),
                scrollOffset =
                    if (visibleItem.index == lazyListState.firstVisibleItemIndex) {
                        lazyListState.firstVisibleItemScrollOffset
                    } else {
                        0
                    },
            )
        }
    }

    // Before the first layout (and in presenter tests) no measured keys exist yet.
    val fallbackIndex = lazyListState.firstVisibleItemIndex
    val fallbackKey = success.peek(fallbackIndex)?.itemKey ?: return null
    return TimelineScrollAnchor(
        itemKey = fallbackKey,
        pagingIndex = fallbackIndex,
        leadingItemCount = 0,
        scrollOffset = lazyListState.firstVisibleItemScrollOffset,
    )
}

private fun captureVisibleTimelinePosition(
    timelineId: String,
    pagingState: PagingState<UiTimelineV2>,
    lazyListState: LazyStaggeredGridState,
): ReaderTimelinePosition? {
    val visibleItems = lazyListState.layoutInfo.visibleItemsInfo.sortedBy { it.index }

    // Prefer the key currently rendered on screen. This remains the user's real visual position
    // even while Paging is swapping generations and indices behind the grid.
    for (visibleItem in visibleItems) {
        val key = visibleItem.key as? String ?: continue
        val success = pagingState as? PagingState.Success
        val isKnownTimelineItem =
            success == null ||
                success.indexOfItemKey(key) >= 0 ||
                visibleItem.index == lazyListState.firstVisibleItemIndex
        if (isKnownTimelineItem) {
            return ReaderTimelinePosition(
                timelineId = timelineId,
                itemKey = key,
                scrollOffset =
                    if (visibleItem.index == lazyListState.firstVisibleItemIndex) {
                        lazyListState.firstVisibleItemScrollOffset
                    } else {
                        0
                    },
            )
        }
    }

    // Before layoutInfo catches up, fall back to the Paging item at the visible index.
    val success = pagingState as? PagingState.Success ?: return null
    val index = lazyListState.firstVisibleItemIndex
    val key = success.peek(index)?.itemKey ?: return null
    return ReaderTimelinePosition(
        timelineId = timelineId,
        itemKey = key,
        scrollOffset = lazyListState.firstVisibleItemScrollOffset,
    )
}

private fun PagingState.Success<UiTimelineV2>.indexOfItemKey(itemKey: String): Int =
    (0 until itemCount).indexOfFirst { peek(it)?.itemKey == itemKey }

private fun currentLeadingItemCount(
    pagingState: PagingState.Success<UiTimelineV2>,
    lazyListState: LazyStaggeredGridState,
): Int {
    for (visibleItem in lazyListState.layoutInfo.visibleItemsInfo.sortedBy { it.index }) {
        val key = visibleItem.key as? String ?: continue
        val pagingIndex = pagingState.indexOfItemKey(key)
        if (pagingIndex >= 0) {
            return (visibleItem.index - pagingIndex).coerceAtLeast(0)
        }
    }
    return 0
}
