package dev.dimension.flare.ui.presenter

import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.paging.LoadState
import androidx.paging.LoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.paging.compose.collectAsLazyPagingItems
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import dev.dimension.flare.common.PagingState
import dev.dimension.flare.common.isRefreshing
import dev.dimension.flare.common.refreshSuspend
import dev.dimension.flare.common.toPagingState
import dev.dimension.flare.data.datastore.model.ReaderTimelinePosition
import dev.dimension.flare.data.repository.ReaderPositionStore
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.render.toUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineWithLazyListStateTest {

    @Test
    fun refreshFromStaleTopRestoresThePreviouslyVisiblePost() =
        withTimelineState(
            initialIndex = 0,
            onRefresh = { pages ->
                pages.value = page(-3..1)
            },
        ) { _, states, scrollState ->
            assertEquals(0, scrollState.firstVisibleItemIndex)
            assertEquals("post-0", assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState).peek(0)?.itemKey)

            states.last().refreshSync()
            runCurrent()

            assertEquals(3, scrollState.firstVisibleItemIndex, "Refresh must keep the old top post visible")
            assertEquals(3, states.last().newPostsCount, "Prepended posts should remain unread after restoring the anchor")
            assertTrue(states.last().showNewToots)
        }

    @Test
    fun refreshLoadsContinuationPagesUntilTheOldAnchorCanBeRestored() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var timeline = (0..40).toList()
            var generation = 0
            val loadedPages = mutableListOf<Pair<Int, Int>>()

            fun item(index: Int): UiTimelineV2 =
                UiTimelineV2.Feed(
                    title = "post-$index",
                    description = null,
                    url = "https://example.com/posts/$index",
                    createdAt = Instant.fromEpochMilliseconds(0).toUi(),
                    source = UiTimelineV2.Feed.Source(name = "test", icon = null),
                    accountType = AccountType.Guest,
                    itemKey = "post-$index",
                )

            val pager =
                Pager(
                    config =
                        PagingConfig(
                            pageSize = 5,
                            initialLoadSize = 5,
                            prefetchDistance = 1,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = {
                        val sourceGeneration = generation++
                        val sourceItems = timeline.toList()
                        object : PagingSource<Int, UiTimelineV2>() {
                            override fun getRefreshKey(state: androidx.paging.PagingState<Int, UiTimelineV2>): Int? = null

                            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, UiTimelineV2> {
                                val start = params.key ?: 0
                                val end = minOf(start + params.loadSize, sourceItems.size)
                                loadedPages += sourceGeneration to start
                                return LoadResult.Page(
                                    data = sourceItems.subList(start, end).map(::item),
                                    prevKey = null,
                                    nextKey = end.takeIf { it < sourceItems.size },
                                )
                            }
                        }
                    },
                )
            val scrollState = LazyStaggeredGridState(initialFirstVisibleItemIndex = 3)
            scrollState.requestScrollToItem(3, 19)
            val states = mutableListOf<TimelineWithLazyListState>()
            val job =
                launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        val pagingState = pager.flow.collectAsLazyPagingItems().toPagingState()
                        val baseState =
                            object : TimelineItemPresenter.State {
                                override val listState = pagingState
                                override val isRefreshing = pagingState.isRefreshing

                                override fun refreshSync() = Unit

                                override suspend fun refreshSuspend() {
                                    pagingState.refreshSuspend()
                                }
                            }
                        rememberTimelineWithLazyListState(baseState, scrollState)
                    }.collect { states += it }
                }
            try {
                advanceUntilIdle()
                assertEquals("post-3", assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState).peek(3)?.itemKey)

                // Twelve new posts exceed the five-item refresh page. The old anchor (post-3)
                // is now at index 15 and can only be found after three continuation loads.
                timeline = (-12 until 0).toList() + (0..40).toList()
                val refreshGeneration = generation
                states.last().refreshSync()
                advanceUntilIdle()

                val refreshed = assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState)
                assertEquals("post--12", refreshed.peek(0)?.itemKey)
                assertEquals("post-3", refreshed.peek(15)?.itemKey)
                assertEquals(15, scrollState.firstVisibleItemIndex)
                assertEquals(19, scrollState.firstVisibleItemScrollOffset)
                assertEquals(12, states.last().newPostsCount)
                assertTrue(states.last().showNewToots)
                assertEquals(
                    listOf(0, 5, 10, 15),
                    loadedPages.filter { it.first == refreshGeneration }.map { it.second },
                    "Refresh should keep appending pages until the old anchor key is loaded",
                )
            } finally {
                job.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun persistedPositionRestoresAfterFreshPresenterAndLoadsOlderPages() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val loadedStarts = mutableListOf<Int>()
            val store =
                FakeReaderPositionStore(
                    ReaderTimelinePosition(
                        timelineId = "home",
                        itemKey = "post-18",
                        scrollOffset = 23,
                    ),
                )

            fun item(index: Int): UiTimelineV2 =
                UiTimelineV2.Feed(
                    title = "post-$index",
                    description = null,
                    url = "https://example.com/posts/$index",
                    createdAt = Instant.fromEpochMilliseconds(0).toUi(),
                    source = UiTimelineV2.Feed.Source(name = "test", icon = null),
                    accountType = AccountType.Guest,
                    itemKey = "post-$index",
                )

            val pager =
                Pager(
                    config =
                        PagingConfig(
                            pageSize = 5,
                            initialLoadSize = 5,
                            prefetchDistance = 1,
                            enablePlaceholders = false,
                        ),
                    pagingSourceFactory = {
                        object : PagingSource<Int, UiTimelineV2>() {
                            override fun getRefreshKey(state: androidx.paging.PagingState<Int, UiTimelineV2>): Int? = null

                            override suspend fun load(params: LoadParams<Int>): LoadResult<Int, UiTimelineV2> {
                                val start = params.key ?: 0
                                val source = (0..40).toList()
                                val end = minOf(start + params.loadSize, source.size)
                                loadedStarts += start
                                return LoadResult.Page(
                                    data = source.subList(start, end).map(::item),
                                    prevKey = null,
                                    nextKey = end.takeIf { it < source.size },
                                )
                            }
                        }
                    },
                )

            val scrollState = LazyStaggeredGridState()
            val states = mutableListOf<TimelineWithLazyListState>()
            val job =
                launch {
                    moleculeFlow(RecompositionMode.Immediate) {
                        val pagingState = pager.flow.collectAsLazyPagingItems().toPagingState()
                        val baseState =
                            object : TimelineItemPresenter.State {
                                override val listState = pagingState
                                override val isRefreshing = pagingState.isRefreshing

                                override fun refreshSync() = Unit

                                override suspend fun refreshSuspend() = Unit
                            }
                        rememberTimelineWithLazyListState(
                            baseState = baseState,
                            lazyListState = scrollState,
                            timelineId = "home",
                            readerPositionStore = store,
                        )
                    }.collect { states += it }
                }

            try {
                advanceUntilIdle()

                assertEquals(18, scrollState.firstVisibleItemIndex)
                assertEquals(23, scrollState.firstVisibleItemScrollOffset)
                assertEquals(18, states.last().newPostsCount)
                assertTrue(listOf(0, 5, 10, 15).all { it in loadedStarts })

                scrollState.requestScrollToItem(19, 7)
                advanceUntilIdle()
                states.last().saveCurrentReadPosition()
                advanceUntilIdle()

                assertEquals("post-19", store.position?.itemKey)
                assertEquals(7, store.position?.scrollOffset)
            } finally {
                job.cancelAndJoin()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun newHeadShowsBannerWithoutReplacingScrollState() =
        withTimelineState { pages, states, scrollState ->
            assertFalse(states.last().showNewToots)

            pages.value = page(-1..1)
            runCurrent()

            val updated = states.last()
            val items = assertIs<PagingState.Success<UiTimelineV2>>(updated.listState)
            assertEquals("post--1", items.peek(0)?.itemKey)
            assertEquals(1, scrollState.firstVisibleItemIndex)
            assertTrue(updated.showNewToots, "A new head should show the banner while reading older posts")
            assertEquals(1, updated.newPostsCount, "One prepended post should be counted without another scroll event")
            assertTrue(states.all { !it.showNewToots || it.newPostsCount > 0 }, "The visible banner must never report zero posts")
        }

    @Test
    fun newPostsAreCountedWhenScrollPositionUpdatesFirst() =
        withTimelineState { pages, states, scrollState ->
            scrollState.requestScrollToItem(2)
            runCurrent()

            pages.value = page(-1..1)
            runCurrent()

            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)
        }

    @Test
    fun newPostsAreCountedWhenScrollPositionUpdatesLast() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-1..1)
            runCurrent()

            scrollState.requestScrollToItem(2)
            runCurrent()

            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)
        }

    @Test
    fun prependingPostsAndDroppingTheTailPreservesTheNewPostsCount() =
        withTimelineState { pages, states, _ ->
            pages.value = page(-1..0)
            runCurrent()

            assertEquals(2, assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState).itemCount)
            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)
        }

    @Test
    fun newPostsCountAccumulatesAcrossRefreshes() =
        withTimelineState { pages, states, _ ->
            pages.value = page(-1..1)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)

            pages.value = page(-3..1)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)

            pages.value = page(-3..1)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)
        }

    @Test
    fun scrollingThroughNewPostsDecreasesTheCount() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-3..1)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)

            scrollState.requestScrollToItem(4)
            runCurrent()
            assertEquals(3, states.last().newPostsCount, "Keeping the old visible post after a prepend must not consume new posts")

            scrollState.requestScrollToItem(2)
            runCurrent()
            assertEquals(2, states.last().newPostsCount, "Only the two new posts above the visible post should remain unread")

            scrollState.requestScrollToItem(1)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)

            scrollState.requestScrollToItem(0, 20)
            runCurrent()
            assertEquals(0, states.last().newPostsCount)
            assertFalse(states.last().showNewToots)
        }

    @Test
    fun scrollingAwayDoesNotRestoreReadPosts() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-3..1)
            runCurrent()
            scrollState.requestScrollToItem(4)
            runCurrent()
            scrollState.requestScrollToItem(2)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)

            scrollState.requestScrollToItem(4)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)

            scrollState.requestScrollToItem(3)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)

            scrollState.requestScrollToItem(1)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)
        }

    @Test
    fun refreshAfterReadingSomePostsPreservesCountWhenScrollUpdatesFirst() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-3..1)
            runCurrent()
            scrollState.requestScrollToItem(4)
            runCurrent()
            scrollState.requestScrollToItem(2)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)

            scrollState.requestScrollToItem(4)
            runCurrent()
            pages.value = page(-5..1)
            runCurrent()
            assertEquals(4, states.last().newPostsCount)

            scrollState.requestScrollToItem(3)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)
        }

    @Test
    fun refreshAfterReadingSomePostsPreservesCountWhenScrollUpdatesLast() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-3..1)
            runCurrent()
            scrollState.requestScrollToItem(4)
            runCurrent()
            scrollState.requestScrollToItem(2)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)

            pages.value = page(-5..1)
            runCurrent()
            assertEquals(4, states.last().newPostsCount)
            scrollState.requestScrollToItem(4)
            runCurrent()
            assertEquals(4, states.last().newPostsCount)

            scrollState.requestScrollToItem(3)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)
        }

    @Test
    fun scrollingAmongOlderPostsDoesNotConsumeNewPosts() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(0..9)
            runCurrent()
            scrollState.requestScrollToItem(5)
            runCurrent()

            pages.value = page(-3..9)
            runCurrent()
            scrollState.requestScrollToItem(8)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)

            scrollState.requestScrollToItem(6)
            runCurrent()
            assertEquals(3, states.last().newPostsCount)

            scrollState.requestScrollToItem(2)
            runCurrent()
            assertEquals(2, states.last().newPostsCount)
        }

    @Test
    fun scrollingBeforeTheNewPostKeepsItUnread() =
        withTimelineState { pages, states, scrollState ->
            pages.value = page(-1..5)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)

            scrollState.requestScrollToItem(4)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)

            scrollState.requestScrollToItem(1)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)

            scrollState.requestScrollToItem(0)
            runCurrent()
            assertFalse(states.last().showNewToots)
            assertEquals(0, states.last().newPostsCount)
        }

    @Test
    fun loadingOlderPostsDoesNotIncreaseTheNewPostsCount() =
        withTimelineState { pages, states, _ ->
            pages.value = page(-1..5)
            runCurrent()

            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)

            pages.value = page(-1..9)
            runCurrent()
            assertEquals(1, states.last().newPostsCount)
        }

    @Test
    fun replacingTheLoadedPageCountsTheNewlyPresentedPosts() =
        withTimelineState { pages, states, _ ->
            pages.value = page(-3..-1)
            runCurrent()

            assertTrue(states.last().showNewToots)
            assertEquals(3, states.last().newPostsCount)
        }

    @Test
    fun removingTheFirstPostDoesNotReportNewPosts() =
        withTimelineState { pages, states, _ ->
            pages.value = page(1..1)
            runCurrent()

            assertFalse(states.last().showNewToots)
            assertEquals(0, states.last().newPostsCount)
        }

    @Test
    fun appendingItemsDoesNotShowTheNewPostsBanner() =
        withTimelineState { pages, states, _ ->
            pages.value = page(0..2)
            runCurrent()

            assertEquals(3, assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState).itemCount)
            assertFalse(states.last().showNewToots)
            assertEquals(0, states.last().newPostsCount)
        }

    @Test
    fun newHeadDoesNotKeepTheBannerVisibleAtTheTop() =
        withTimelineState(initialIndex = 0) { pages, states, _ ->
            pages.value = page(-1..1)
            runCurrent()

            assertEquals("post--1", assertIs<PagingState.Success<UiTimelineV2>>(states.last().listState).peek(0)?.itemKey)
            assertFalse(states.last().showNewToots)
            assertEquals(0, states.last().newPostsCount)
        }

    @Test
    fun dismissedBannerCanAppearForTheNextNewHead() =
        withTimelineState { pages, states, _ ->
            pages.value = page(-1..1)
            runCurrent()
            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)

            states.last().onNewTootsShown()
            runCurrent()
            assertFalse(states.last().showNewToots)
            assertEquals(0, states.last().newPostsCount)

            pages.value = page(-2..1)
            runCurrent()
            assertTrue(states.last().showNewToots)
            assertEquals(1, states.last().newPostsCount)
        }

    private fun withTimelineState(
        initialIndex: Int = 1,
        onRefresh: suspend (MutableStateFlow<PagingData<UiTimelineV2>>) -> Unit = {},
        block: suspend TestScope.(
            MutableStateFlow<PagingData<UiTimelineV2>>,
            List<TimelineWithLazyListState>,
            LazyStaggeredGridState,
        ) -> Unit,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val scrollState = LazyStaggeredGridState(initialFirstVisibleItemIndex = initialIndex)
        val pages = MutableStateFlow(page(0..1))
        val states = mutableListOf<TimelineWithLazyListState>()
        val job =
            launch {
                moleculeFlow(RecompositionMode.Immediate) {
                    val pagingState = pages.collectAsLazyPagingItems().toPagingState()
                    val baseState =
                        object : TimelineItemPresenter.State {
                            override val listState = pagingState
                            override val isRefreshing = pagingState.isRefreshing

                            override fun refreshSync() = Unit

                            override suspend fun refreshSuspend() {
                                onRefresh(pages)
                            }
                        }
                    rememberTimelineWithLazyListState(baseState, scrollState)
                }.collect { states += it }
            }
        try {
            runCurrent()
            block(pages, states, scrollState)
        } finally {
            job.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }

    private class FakeReaderPositionStore(
        initialPosition: ReaderTimelinePosition? = null,
    ) : ReaderPositionStore {
        var position: ReaderTimelinePosition? = initialPosition
            private set

        override suspend fun getPosition(timelineId: String): ReaderTimelinePosition? =
            position?.takeIf { it.timelineId == timelineId }

        override suspend fun savePosition(
            timelineId: String,
            itemKey: String,
            scrollOffset: Int,
        ) {
            position =
                ReaderTimelinePosition(
                    timelineId = timelineId,
                    itemKey = itemKey,
                    scrollOffset = scrollOffset,
                )
        }
    }

    private fun page(indices: IntRange): PagingData<UiTimelineV2> =
        PagingData.from(
            data =
                indices.map { index ->
                    UiTimelineV2.Feed(
                        title = "post-$index",
                        description = null,
                        url = "https://example.com/posts/$index",
                        createdAt = Instant.fromEpochMilliseconds(0).toUi(),
                        source = UiTimelineV2.Feed.Source(name = "test", icon = null),
                        accountType = AccountType.Guest,
                        itemKey = "post-$index",
                    )
                },
            sourceLoadStates =
                LoadStates(
                    refresh = LoadState.NotLoading(endOfPaginationReached = false),
                    prepend = LoadState.NotLoading(endOfPaginationReached = true),
                    append = LoadState.NotLoading(endOfPaginationReached = false),
                ),
        )
}
