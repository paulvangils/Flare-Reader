package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.data.datasource.microblog.MicroblogDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingRequest
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.datasource.microblog.paging.RemoteLoader
import dev.dimension.flare.model.MicroBlogKey
import androidx.compose.runtime.Immutable
import dev.dimension.flare.ui.model.UiProfile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Recommendations from the user's actual Bluesky follow graph.
 *
 * Start with the user's followed accounts, then sample accounts followed by
 * those people. Exclude everyone already followed and rank common connections
 * ahead of one-off suggestions. One bounded page feeds the Discover carousel.
 */
@Immutable
public data class FollowGraphSuggestion(
    val profile: UiProfile,
    /** People followed by the signed-in user who also follow this profile. */
    val followedBy: ImmutableList<UiProfile>,
)

internal class FollowingBasedSuggestionsLoader(
    private val service: MicroblogDataSource,
    private val accountKey: MicroBlogKey,
) : RemoteLoader<FollowGraphSuggestion> {
    override suspend fun load(
        pageSize: Int,
        request: PagingRequest,
    ): PagingResult<UiProfile> {
        if (request !is PagingRequest.Refresh) {
            return PagingResult(endOfPaginationReached = true)
        }

        // Limit requests: at most two pages of own follows and eight small
        // friends-of-friends pages, all read-only.
        val ownFollows = service.following(accountKey)
        val first = ownFollows.load(OWN_FOLLOWS_PAGE_SIZE, PagingRequest.Refresh)
        val followed = first.data.toMutableList()
        first.nextKey?.let { cursor ->
            try {
                followed += ownFollows.load(
                    OWN_FOLLOWS_PAGE_SIZE,
                    PagingRequest.Append(cursor),
                ).data
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // First page still gives useful recommendations.
            }
        }
        if (followed.isEmpty()) {
            return PagingResult(endOfPaginationReached = true)
        }

        val seedCount = minOf(MAX_SEED_ACCOUNTS, followed.size)
        val seeds = (0 until seedCount).map { followed[it * followed.size / seedCount] }
        val followedBySeeds = coroutineScope {
            seeds.map { seed ->
                async {
                    try {
                        service.following(seed.key)
                            .load(FOLLOWS_PER_SEED, PagingRequest.Refresh)
                            .data
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll()
        }

        return PagingResult(
            data = rankFollowGraphSuggestionsWithConnections(
                ownKey = accountKey,
                seeds = seeds,
                alreadyFollowed = followed,
                followedBySeeds = followedBySeeds,
                limit = pageSize.coerceIn(12, MAX_RECOMMENDATIONS),
            ),
            endOfPaginationReached = true,
        )
    }

    private companion object {
        const val OWN_FOLLOWS_PAGE_SIZE = 100
        const val FOLLOWS_PER_SEED = 60
        const val MAX_SEED_ACCOUNTS = 8
        const val MAX_RECOMMENDATIONS = 40
    }
}

/** Kept pure to test exclusion, deduplication and connection-based ranking. */
internal fun rankFollowGraphSuggestions(
    ownKey: MicroBlogKey,
    alreadyFollowed: List<UiProfile>,
    followedBySeeds: List<List<UiProfile>>,
    limit: Int,
): List<UiProfile> {
    val excluded = alreadyFollowed.mapTo(mutableSetOf(ownKey)) { it.key }
    val candidates = linkedMapOf<MicroBlogKey, Pair<UiProfile, Int>>()
    followedBySeeds.forEach { seedFollows ->
        // Count mutual follow connections, never duplicate entries in one seed.
        seedFollows.distinctBy { it.key }.forEach { profile ->
            if (profile.key !in excluded) {
                val previous = candidates[profile.key]
                candidates[profile.key] = profile to ((previous?.second ?: 0) + 1)
            }
        }
    }
    return candidates.values
        .sortedWith(
            compareByDescending<Pair<UiProfile, Int>> { it.second }
                .thenByDescending { it.first.matrices.fansCount },
        )
        .take(limit.coerceAtLeast(0))
        .map { it.first }
}

/**
 * Retains the actual seed-account identities instead of losing them after
 * ranking. Each matched connection is a verified "seed follows candidate" edge
 * returned from Bluesky; only the bounded, inspected portion of the graph counts.
 */
internal fun rankFollowGraphSuggestionsWithConnections(
    ownKey: MicroBlogKey,
    alreadyFollowed: List<UiProfile>,
    seeds: List<UiProfile>,
    followedBySeeds: List<List<UiProfile>>,
    limit: Int,
): List<FollowGraphSuggestion> {
    val excluded = alreadyFollowed.mapTo(mutableSetOf(ownKey)) { it.key }
    val connections = linkedMapOf<MicroBlogKey, LinkedHashMap<MicroBlogKey, UiProfile>>()
    seeds.zip(followedBySeeds).forEach { (seed, followedBySeed) ->
        followedBySeed.distinctBy { it.key }.forEach { candidate ->
            if (candidate.key !in excluded) {
                connections.getOrPut(candidate.key) { linkedMapOf() }[seed.key] = seed
            }
        }
    }

    return rankFollowGraphSuggestions(
        ownKey = ownKey,
        alreadyFollowed = alreadyFollowed,
        followedBySeeds = followedBySeeds,
        limit = limit,
    ).mapNotNull { profile ->
        val followedBy = connections[profile.key]?.values?.toImmutableList()
        followedBy?.let { FollowGraphSuggestion(profile, it) }
    }
}
