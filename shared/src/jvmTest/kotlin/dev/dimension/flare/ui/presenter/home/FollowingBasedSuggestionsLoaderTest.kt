package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.createSampleUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FollowingBasedSuggestionsLoaderTest {
    private fun user(id: String, fans: Long = 0L): UiProfile =
        createSampleUser().copy(
            key = MicroBlogKey(id, "bsky.social"),
            matrices = UiProfile.Matrices(
                fansCount = fans,
                followsCount = 0L,
                statusesCount = 0L,
            ),
        )

    @Test
    fun excludesAccountsAlreadyFollowedAndSelf() {
        val found = rankFollowGraphSuggestions(
            ownKey = user("me").key,
            alreadyFollowed = listOf(user("friend")),
            followedBySeeds = listOf(
                listOf(user("me"), user("friend"), user("new"), user("new")),
            ),
            limit = 10,
        )
        assertEquals(listOf("new"), found.map { it.key.id })
    }

    @Test
    fun ranksAccountsFollowedByMultipleFriendsFirst() {
        val found = rankFollowGraphSuggestions(
            ownKey = user("me").key,
            alreadyFollowed = listOf(user("friend1"), user("friend2")),
            followedBySeeds = listOf(
                listOf(user("single", fans = 100000), user("shared")),
                listOf(user("shared"), user("other")),
            ),
            limit = 10,
        )
        assertEquals("shared", found.first().key.id)
        assertEquals(3, found.size)
    }

    @Test
    fun deDuplicatesWithinEachFriendsFollowListAndHonorsLimit() {
        val found = rankFollowGraphSuggestions(
            ownKey = user("me").key,
            alreadyFollowed = emptyList(),
            followedBySeeds = listOf(
                listOf(user("a"), user("a"), user("b")),
                listOf(user("b"), user("c")),
            ),
            limit = 2,
        )
        assertEquals(listOf("b", "a"), found.map { it.key.id })
    }

    @Test
    fun emptyFollowGraphYieldsNoSuggestions() {
        assertTrue(
            rankFollowGraphSuggestions(
                ownKey = user("me").key,
                alreadyFollowed = emptyList(),
                followedBySeeds = emptyList(),
                limit = 10,
            ).isEmpty(),
        )
    }
}
