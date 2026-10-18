package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.data.datasource.microblog.ActionMenu
import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.PostActionFamily
import dev.dimension.flare.data.datasource.microblog.SearchSortMode
import dev.dimension.flare.data.datasource.microblog.searchStatusSorted
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiTimelineV2
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Network-only mixed search: no timeline/cache writes, one paging cursor per account.
 * Latest is merged chronologically; Popular ranks fetched posts by visible interactions.
 */
internal class FederatedSearchLoader(
    services: List<MicroblogDataSource>,
    query: String,
    private val order: SearchSortMode,
) : RemoteLoader<UiTimelineV2> {
    private data class Source(
        val loader: RemoteLoader<UiTimelineV2>,
        val buffer: ArrayDeque<UiTimelineV2> = ArrayDeque(),
        var cursor: String? = null,
        var fetched: Boolean = false,
        var exhausted: Boolean = false,
    )

    private val initialSources = services.mapNotNull { service ->
        runCatching { Source(service.searchStatusSorted(query, order)) }.getOrNull()
    }
    private val mutex = Mutex()
    private var sources = initialSources
    private val emittedKeys = mutableSetOf<MicroBlogKey>()
    private var page = 0

    override suspend fun load(pageSize: Int, request: PagingRequest): PagingResult<UiTimelineV2> =
        mutex.withLock {
            if (request is PagingRequest.Prepend) {
                return@withLock PagingResult(endOfPaginationReached = true)
            }
            if (request is PagingRequest.Refresh) {
                sources = initialSources.map { Source(it.loader) }
                emittedKeys.clear()
                page = 0
            }
            val response = mutableListOf<UiTimelineV2>()
            var failed = 0
            var rounds = 0
            while (response.size < pageSize.coerceAtLeast(1) && rounds < MAX_FETCH_ROUNDS) {
                rounds++
                val needsPage = sources.filter { it.buffer.isEmpty() && !it.exhausted }
                if (needsPage.isNotEmpty()) {
                    val fetched = coroutineScope {
                        needsPage.map { source ->
                            async {
                                val previousCursor = source.cursor
                                source to runCatching {
                                    source.loader.load(
                                        pageSize = pageSize.coerceIn(15, 50),
                                        request = if (!source.fetched) {
                                            PagingRequest.Refresh
                                        } else {
                                            PagingRequest.Append(checkNotNull(previousCursor))
                                        },
                                    )
                                }
                            }
                        }.awaitAll()
                    }
                    fetched.forEach { (source, result) ->
                        source.fetched = true
                        result.fold(
                            onSuccess = { resultPage ->
                                source.buffer.addAll(resultPage.data)
                                source.exhausted =
                                    resultPage.nextKey == null ||
                                        resultPage.nextKey == source.cursor ||
                                        resultPage.data.isEmpty()
                                source.cursor = resultPage.nextKey
                            },
                            onFailure = {
                                source.exhausted = true
                                failed++
                            },
                        )
                    }
                }
                val next = sources.mapNotNull { source ->
                    source.buffer.firstOrNull()?.let { source to it }
                }.maxWithOrNull(
                    compareBy<Pair<Source, UiTimelineV2>> {
                        if (order == SearchSortMode.Latest) {
                            it.second.createdAt.value.toEpochMilliseconds()
                        } else {
                            it.second.engagementScore()
                        }
                    }.thenBy { it.second.createdAt.value.toEpochMilliseconds() },
                ) ?: break
                next.first.buffer.removeFirst()
                if (emittedKeys.add(next.second.statusKey)) {
                    response.add(next.second)
                }
            }
            if (response.isEmpty() && failed > 0 &&
                sources.all { it.exhausted && it.buffer.isEmpty() }
            ) {
                error("Zoekresultaten konden niet worden opgehaald. Probeer opnieuw.")
            }
            page++
            PagingResult(
                data = response,
                nextKey = if (sources.any { it.buffer.isNotEmpty() || !it.exhausted }) {
                    page.toString()
                } else {
                    null
                },
            )
        }

    private companion object {
        const val MAX_FETCH_ROUNDS = 120
    }
}

/** Comparable cross-service popularity, based on engagement counts returned by each service. */
internal fun UiTimelineV2.engagementScore(): Long {
    val post = when (this) {
        is UiTimelineV2.Post -> this
        is UiTimelineV2.TimelinePostItem -> displayPost
        else -> return 0L
    }
    return post.actions.flatMap { action ->
        when (action) {
            is ActionMenu.Item -> listOf(action)
            is ActionMenu.Group -> action.actions.filterIsInstance<ActionMenu.Item>()
            ActionMenu.Divider -> emptyList()
        }
    }.sumOf { action ->
        val count = action.count?.value ?: 0L
        when (action.actionFamily) {
            PostActionFamily.Like -> count
            PostActionFamily.Repost -> count * 2
            PostActionFamily.Quote -> count * 3
            PostActionFamily.Reply, PostActionFamily.Comment -> count * 2
            else -> 0L
        }
    }
}
