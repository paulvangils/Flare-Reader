package dev.dimension.flare.ui.presenter.home

import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SearchAccountScopeTest {
    private val twitter = MicroBlogKey("user1", "x.com")
    private val bluesky = MicroBlogKey("user2", "bsky.social")

    @Test
    fun ordinarySearchStartsAcrossAccountsEvenWhenOpenedFromTwitter() {
        assertNull(
            resolveSearchAccountScope(
                initialAccountType = AccountType.Specific(twitter),
                selectedAccountKey = null,
                federatedByDefault = true,
            ),
        )
    }

    @Test
    fun ordinarySearchStartsAcrossAccountsEvenWhenOpenedFromGuestDiscover() {
        assertNull(
            resolveSearchAccountScope(
                initialAccountType = AccountType.Guest,
                selectedAccountKey = null,
                federatedByDefault = true,
            ),
        )
    }

    @Test
    fun selectingAccountRestrictsSearchAndChoosingAllRestoresCombinedSearch() {
        assertEquals(
            AccountType.Specific(bluesky),
            resolveSearchAccountScope(
                initialAccountType = AccountType.Guest,
                selectedAccountKey = bluesky,
                federatedByDefault = true,
            ),
        )
        assertNull(
            resolveSearchAccountScope(
                initialAccountType = AccountType.Guest,
                selectedAccountKey = null,
                federatedByDefault = true,
            ),
        )
    }

    @Test
    fun specialSearchCanKeepItsOriginalAccount() {
        assertEquals(
            AccountType.Specific(twitter),
            resolveSearchAccountScope(
                initialAccountType = AccountType.Specific(twitter),
                selectedAccountKey = null,
                federatedByDefault = false,
            ),
        )
    }
}
