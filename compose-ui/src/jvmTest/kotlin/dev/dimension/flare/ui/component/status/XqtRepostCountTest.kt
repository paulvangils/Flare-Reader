package dev.dimension.flare.ui.component.status

import org.junit.Test
import kotlin.test.assertEquals

class XqtRepostCountTest {
    @Test
    fun retweetCountIncludesQuotedRetweets() {
        assertEquals(17L, totalXqtRepostCount(retweets = 12L, quotes = 5L))
    }

    @Test
    fun noQuotesRetainsOrdinaryRetweetCount() {
        assertEquals(12L, totalXqtRepostCount(retweets = 12L, quotes = 0L))
    }

    @Test
    fun noRetweetsStillShowsQuotes() {
        assertEquals(5L, totalXqtRepostCount(retweets = 0L, quotes = 5L))
    }
}
