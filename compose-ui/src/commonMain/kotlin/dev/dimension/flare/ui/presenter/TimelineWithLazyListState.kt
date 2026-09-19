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
import dev.dimension.flare.common.onSuccess
import dev.dimension.flare.data.model.tab.UiTimelineTabItem
import dev.dimension.flare.ui.model.UiTimelineV2
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
    val baseState by producePresenter("timeline_${item.id}_$isHomeTimeline") {
        remember(item, isHomeTimeline) { TimelineItemPresenter(item, isHomeTimeline) }.invoke()
    }
    return rememberTimelineWithLazyListState(baseState, lazyStaggeredGridState)
}

@Composable
internal fun rememberTimelineWithLazyListState(
    baseState: TimelineItemPresenter.State,
    lazyListState: LazyStaggeredGridState,
): TimelineWithLazyListState {
    var newPostCount by remember { mutableIntStateOf(0) }
    var preservingRefreshPosition by remember { mutableStateOf(false) }
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
                    if (previousKeys.isNotEmpty() && (!isAtTheTop || preservingRefreshPosition)) {
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
    LaunchedEffect(isAtTheTop, preservingRefreshPosition) {
        if (isAtTheTop && !preservingRefreshPosition) {
            newPostCount = 0
        }
    }

    suspend fun refreshPreservingScrollPosition() {
        val beforeState = currentBaseState
        val anchor = captureTimelineScrollAnchor(beforeState.listState, lazyListState)
        preservingRefreshPosition = anchor != null
        try {
            beforeState.refreshSuspend()
            if (anchor == null) return

            // Refresh can replace the loaded page completely. If the previously visible post is
            // outside that first snapshot, keep loading continuation pages until its stable key
            // reappears. This avoids treating a refresh as an instruction to jump to "now".
            var refreshedState =
                withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                    snapshotFlow { currentBaseState }
                        .first { it !== beforeState && !it.isRefreshing }
                } ?: return
            var refreshedIndex =
                (refreshedState.listState as? PagingState.Success)?.indexOfItemKey(anchor.itemKey) ?: -1

            var appendLoads = 0
            while (refreshedIndex < 0 && appendLoads < MAX_ANCHOR_APPEND_LOADS) {
                val pagingState = refreshedState.listState as? PagingState.Success ?: return
                when (pagingState.appendState) {
                    is LoadState.Error -> return
                    is LoadState.Loading -> Unit
                    is LoadState.NotLoading -> {
                        if (pagingState.appendState.endOfPaginationReached || pagingState.itemCount == 0) {
                            return
                        }
                        // Reading the current tail sends Paging an append hint without scrolling
                        // the viewport. Once that load settles we can search the larger snapshot.
                        pagingState[pagingState.itemCount - 1]
                        appendLoads += 1
                    }
                }

                val previousState = refreshedState
                refreshedState =
                    withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                        snapshotFlow { currentBaseState }
                            .first { candidate ->
                                if (candidate === previousState || candidate.isRefreshing) {
                                    false
                                } else {
                                    val candidatePaging = candidate.listState as? PagingState.Success
                                    candidatePaging != null && candidatePaging.appendState !is LoadState.Loading
                                }
                            }
                    } ?: return
                refreshedIndex =
                    (refreshedState.listState as? PagingState.Success)?.indexOfItemKey(anchor.itemKey) ?: -1
            }
            if (refreshedIndex < 0) return

            val targetIndex = (refreshedIndex + anchor.leadingItemCount).coerceAtLeast(0)
            val newlyPrependedPosts = (refreshedIndex - anchor.pagingIndex).coerceAtLeast(0)
            newPostCount = maxOf(newPostCount, newlyPrependedPosts)
            lazyListState.requestScrollToItem(targetIndex, anchor.scrollOffset)

            // Keep the guard active until the requested position is applied, otherwise the
            // transient top-of-list frame can clear the unread/new-post count.
            withTimeoutOrNull(REFRESH_POSITION_WAIT_TIMEOUT_MS) {
                snapshotFlow {
                    lazyListState.layoutInfo.visibleItemsInfo.any { it.key == anchor.itemKey } ||
                        lazyListState.firstVisibleItemIndex == targetIndex
                }.first { it }
            }
        } finally {
            preservingRefreshPosition = false
        }
    }
    return object :
        TimelineWithLazyListState,
        TimelineItemPresenter.State by baseState {
        override val showNewToots = newPostCount > 0
        override val lazyListState = lazyListState
        override val newPostsCount = newPostCount
        override val isRefreshing = baseState.isRefreshing || preservingRefreshPosition

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
    }
}

private const val MAX_ANCHOR_APPEND_LOADS = 50
private const val REFRESH_POSITION_WAIT_TIMEOUT_MS = 30_000L

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

private fun PagingState.Success<UiTimelineV2>.indexOfItemKey(itemKey: String): Int =
    (0 until itemCount).indexOfFirst { peek(it)?.itemKey == itemKey }
