package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Recommend Bluesky profiles followed by people the current account follows.
 * No network data is cached into a timeline, and no follow/unfollow action is sent.
 */
internal class BlueskyFollowSuggestionsLoader(
    private val service: MicroblogDataSource,
    private val accountKey: MicroBlogKey,
) : RemoteLoader<UiProfile> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiProfile> {
        if (request != PagingRequest.Refresh) {
            return PagingResult(endOfPaginationReached = true)
        }

        val followed = loadOwnFollows()
        if (followed.isEmpty()) {
            return PagingResult(endOfPaginationReached = true)
        }

        val seeds = selectSuggestionSeeds(followed, MAX_SEEDS)
        val circles = coroutineScope {
            seeds.map { seed ->
                async {
                    runCatching {
                        service.following(seed.key)
                            .load(FOLLOWING_PAGE_SIZE, PagingRequest.Refresh)
                            .data
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
        }

        return PagingResult(
            data = rankBlueskyFollowSuggestions(
                groups = circles,
                alreadyFollowing = followed.map { it.key }.toSet(),
                ownAccount = accountKey,
                limit = pageSize.coerceIn(10, 30),
            ),
            endOfPaginationReached = true,
        )
    }

    private suspend fun loadOwnFollows(): List<UiProfile> {
        val loader = service.following(accountKey)
        val result = ArrayList<UiProfile>()
        var request: PagingRequest = PagingRequest.Refresh
        var lastCursor: String? = null
        repeat(MAX_OWN_FOLLOW_PAGES) {
            val page = runCatching {
                loader.load(FOLLOWING_PAGE_SIZE, request)
            }.getOrElse { return result.distinctBy { it.key } }
            result.addAll(page.data)
            val next = page.nextKey
            if (next == null || next == lastCursor || page.data.isEmpty()) {
                return result.distinctBy { it.key }
            }
            lastCursor = next
            request = PagingRequest.Append(next)
        }
        return result.distinctBy { it.key }
    }

    private companion object {
        const val FOLLOWING_PAGE_SIZE = 100
        const val MAX_OWN_FOLLOW_PAGES = 5
        const val MAX_SEEDS = 8
    }
}

/** Evenly sample followed accounts, rather than picking only the most recent follows. */
internal fun <T> selectSuggestionSeeds(followed: List<T>, count: Int): List<T> =
    when {
        count <= 0 || followed.isEmpty() -> emptyList()
        followed.size <= count -> followed
        count == 1 -> listOf(followed.first())
        else -> (0 until count).map { index ->
            followed[index * (followed.lastIndex) / (count - 1)]
        }
    }

/**
 * Prioritize profiles appearing in several follow networks. Never recommend the
 * signed-in user or any profile present in their fetched following list.
 */
internal fun rankBlueskyFollowSuggestions(
    groups: List<List<UiProfile>>,
    alreadyFollowing: Set<MicroBlogKey>,
    ownAccount: MicroBlogKey,
    limit: Int,
): List<UiProfile> {
    val candidates = groups
        .flatMap { group -> group.distinctBy { it.key } }
        .filter { profile ->
            profile.key != ownAccount && profile.key !in alreadyFollowing
        }
    val counts = candidates.groupingBy { it.key }.eachCount()
    return candidates
        .distinctBy { it.key }
        .sortedWith(
            compareByDescending<UiProfile> { counts[it.key] ?: 0 }
                .thenByDescending { it.matrices.fansCount }
                .thenBy { it.key.toString() },
        ).take(limit.coerceAtLeast(0))
}
