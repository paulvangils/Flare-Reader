package dev.dimension.flare.data.datasource.microblog.paging

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator.MediatorResult
import dev.dimension.flare.common.SnowflakeIdGenerator
import dev.dimension.flare.data.database.cache.CacheDatabase
import dev.dimension.flare.data.database.cache.mapper.saveToDatabase
import dev.dimension.flare.data.database.cache.model.DbPagingTimelineWithStatus
import dev.dimension.flare.data.translation.NoopPreTranslationService
import dev.dimension.flare.data.translation.PreTranslationService
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.model.ReferenceType
import dev.dimension.flare.ui.model.UiTimelineV2
import dev.dimension.flare.ui.model.asTimelinePostItem
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

@OptIn(ExperimentalPagingApi::class)
internal open class TimelineRemoteMediator(
    private val loader: CacheableRemoteLoader<UiTimelineV2>,
    private val database: CacheDatabase,
    private val allowLongText: Boolean,
    private val notifyError: (Throwable) -> Unit = {},
    private val preTranslationService: PreTranslationService = NoopPreTranslationService,
    private val refreshOnInitialize: suspend () -> Boolean = { true },
) : BasePagingRemoteMediator<
        OffsetFromStartPagingKey,
        TimelinePageItem,
        DbPagingTimelineWithStatus,
    >(
        database = database,
    ),
    RemoteLoader<DbPagingTimelineWithStatus> {
    private var suppressInitialPrepend = false

    override val pagingKey: String
        get() = loader.pagingKey

    init {
        if (loader is ReportableRemoteLoader) {
            loader.reportError = notifyError
        }
    }

    override fun onError(e: Throwable) {
        notifyError(e)
    }

    override suspend fun initialize(): InitializeAction {
        val hasCache = database.pagingTimelineDao().anyPaging(loader.pagingKey)
        if (!hasCache) {
            return InitializeAction.LAUNCH_INITIAL_REFRESH
        }

        val shouldRefresh = refreshOnInitialize()
        suppressInitialPrepend = loader.supportPrepend && !shouldRefresh
        return if (!shouldRefresh || loader.supportPrepend) {
            InitializeAction.SKIP_INITIAL_REFRESH
        } else {
            InitializeAction.LAUNCH_INITIAL_REFRESH
        }
    }

    override suspend fun doLoad(
        loadType: LoadType,
        state: PagingState<OffsetFromStartPagingKey, TimelinePageItem>,
    ): MediatorResult {
        if (loadType == LoadType.PREPEND && suppressInitialPrepend) {
            suppressInitialPrepend = false
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        if (loadType == LoadType.REFRESH) {
            suppressInitialPrepend = false
        }
        return super.doLoad(loadType, state)
    }

    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<DbPagingTimelineWithStatus> {
        val result =
            if (request is PagingRequest.Refresh) {
                loadRefreshUntilCachedOverlap(pageSize)
            } else {
                timeline(
                    pageSize = pageSize,
                    request = request,
                )
            }
        val sortIdProvider = loader as? SortIdProvider
        val sortIds =
            when {
                sortIdProvider != null -> result.data.map { sortIdProvider.sortId(it) }
                request is PagingRequest.Refresh -> {
                    val minimumSortId = database.pagingTimelineDao().getMinSortId(pagingKey)
                    if (minimumSortId != null && minimumSortId >= Long.MIN_VALUE + result.data.size) {
                        val firstSortId = minimumSortId - result.data.size
                        result.data.indices.map { index -> firstSortId + index }
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }
        val data =
            TimelinePagingMapper.toDb(
                data = result.data,
                pagingKey = pagingKey,
                sortIds = sortIds,
            )
        return PagingResult(
            data = data,
            nextKey = result.nextKey,
            previousKey = result.previousKey,
        )
    }

    private suspend fun loadRefreshUntilCachedOverlap(
        pageSize: Int,
    ): PagingResult<UiTimelineV2> {
        val cachedStatusIds =
            database
                .pagingTimelineDao()
                .getByPagingKey(pagingKey)
                .mapTo(mutableSetOf()) { it.statusId }

        var page =
            timeline(
                pageSize = pageSize,
                request = PagingRequest.Refresh,
            )
        if (cachedStatusIds.isEmpty()) {
            return page
        }

        val combined = ArrayList<UiTimelineV2>()
        val previousKey = page.previousKey
        var pagesLoaded = 0

        while (true) {
            combined += page.data
            pagesLoaded += 1

            val pageStatusIds =
                TimelinePagingMapper
                    .toDb(
                        data = page.data,
                        pagingKey = pagingKey,
                    ).mapTo(mutableSetOf()) { it.timeline.statusId }

            val overlapsCache = pageStatusIds.any { it in cachedStatusIds }
            val nextKey = page.nextKey

            if (
                overlapsCache ||
                nextKey == null ||
                pagesLoaded >= MAX_REFRESH_CATCH_UP_PAGES
            ) {
                return PagingResult(
                    data = combined.distinctBy { it.itemKey },
                    nextKey = nextKey,
                    previousKey = previousKey,
                )
            }

            page =
                timeline(
                    pageSize = pageSize,
                    request = PagingRequest.Append(nextKey),
                )
        }
    }

    suspend fun timeline(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiTimelineV2> =
        loader
            .load(
                pageSize = pageSize,
                request = request,
            ).let { result ->
                result.copy(
                    data =
                        if (loader.collapseReplyChains) {
                            result.data.collapseReplyChains()
                        } else {
                            result.data
                        },
                )
            }

    override suspend fun onSaveCache(
        request: PagingRequest,
        data: List<DbPagingTimelineWithStatus>,
    ) {
        val dataToSave =
            if (request is PagingRequest.Prepend && loader.supportPrepend && data.isNotEmpty()) {
                val minimumSortId = database.pagingTimelineDao().getMinSortId(pagingKey)
                if (minimumSortId != null && minimumSortId >= Long.MIN_VALUE + data.size) {
                    val firstSortId = minimumSortId - data.size
                    data
                        .sortedBy { it.timeline.sortId }
                        .mapIndexed { index, item ->
                            item.copy(timeline = item.timeline.copy(sortId = firstSortId + index))
                        }
                } else {
                    if (minimumSortId != null) {
                        val rebasedRows =
                            database.pagingTimelineDao().getByPagingKey(pagingKey).map { row ->
                                row.copy(sortId = SnowflakeIdGenerator.nextId())
                            }
                        database.pagingTimelineDao().insertAll(rebasedRows)
                    }
                    data
                }
            } else {
                data
            }
        // Reader refreshes are continuity-preserving: update/insert the fetched range, but
        // never delete older cached rows merely because they were outside the newest page(s).
        saveToDatabase(database, dataToSave)
        enqueuePreTranslation(dataToSave)
    }

    protected fun enqueuePreTranslation(dataToSave: List<DbPagingTimelineWithStatus>) {
        preTranslationService.enqueueStatuses(
            dataToSave
                .flatMap { item ->
                    listOfNotNull(item.status.status.data) +
                        item.status.references.mapNotNull { it.status?.data } +
                        item.presentationReferences.flatMap { reference ->
                            listOfNotNull(reference.status?.status?.data) +
                                reference.status
                                    ?.references
                                    .orEmpty()
                                    .mapNotNull { it.status?.data }
                        }
                }.distinctBy { it.id },
            allowLongText = allowLongText,
        )
    }
}

private fun List<UiTimelineV2>.collapseReplyChains(): List<UiTimelineV2> {
    fun UiTimelineV2.TimelinePostItem.key(): Pair<AccountType, MicroBlogKey> = accountType to statusKey

    val rootPosts =
        asSequence()
            .mapNotNull { it.asTimelinePostItem() }
            .associateBy { it.key() }
    if (rootPosts.isEmpty()) {
        return this
    }

    val collapsedPosts = mutableMapOf<Pair<AccountType, MicroBlogKey>, UiTimelineV2.TimelinePostItem>()
    val ancestorKeys = mutableSetOf<Pair<AccountType, MicroBlogKey>>()

    fun UiTimelineV2.TimelinePostItem.directParentKey(): Pair<AccountType, MicroBlogKey>? =
        presentation
            .inlineParents
            .lastOrNull()
            ?.let { it.key() }
            ?.takeIf { it in rootPosts }
            ?: post
                .references
                .firstOrNull { it.type == ReferenceType.Reply }
                ?.let { post.accountType to it.statusKey }
                ?.takeIf { it in rootPosts }

    fun collapse(start: UiTimelineV2.TimelinePostItem): UiTimelineV2.TimelinePostItem {
        val startKey = start.key()
        collapsedPosts[startKey]?.let {
            return it
        }

        val path = mutableListOf<UiTimelineV2.TimelinePostItem>()
        val activeKeys = mutableSetOf<Pair<AccountType, MicroBlogKey>>()
        var current = start
        var collapsed: UiTimelineV2.TimelinePostItem

        while (true) {
            val currentKey = current.key()
            collapsedPosts[currentKey]?.let {
                if (path.isNotEmpty()) {
                    ancestorKeys += currentKey
                }
                collapsed = it
                break
            }

            activeKeys += currentKey
            val directParentKey = current.directParentKey()
            val directParent =
                directParentKey
                    ?.takeUnless { it in activeKeys }
                    ?.let { rootPosts.getValue(it) }

            if (directParent == null || directParent.accountType != current.accountType) {
                collapsed =
                    if (directParentKey in activeKeys) {
                        current.copy(
                            presentation =
                                current.presentation.copy(
                                    inlineParents =
                                        current.presentation.inlineParents
                                            .dropLast(1)
                                            .toImmutableList(),
                                ),
                        )
                    } else {
                        current
                    }
                collapsedPosts[currentKey] = collapsed
                break
            }

            ancestorKeys += directParent.key()
            path += current
            current = directParent
        }

        for (post in path.asReversed()) {
            collapsed =
                post.copy(
                    presentation =
                        post.presentation.copy(
                            inlineParents =
                                (
                                    post.presentation.inlineParents.dropLast(1) +
                                        collapsed.presentation.inlineParents +
                                        listOf(
                                            collapsed.copy(presentation = collapsed.presentation.copy(inlineParents = persistentListOf())),
                                        )
                                ).distinctBy { it.statusKey }
                                    .toImmutableList(),
                        ),
                )
            collapsedPosts[post.key()] = collapsed
        }
        return collapsedPosts.getValue(startKey)
    }

    val collapsedItems =
        map { item ->
            val post = item.asTimelinePostItem()
            if (post != null) {
                post.key() to collapse(post)
            } else {
                null to item
            }
        }
    return collapsedItems.mapNotNull { (key, item) ->
        item.takeUnless { key in ancestorKeys }
    }
}


private const val MAX_REFRESH_CATCH_UP_PAGES = 50
