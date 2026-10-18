package dev.dimension.flare.ui.component.status

import org.junit.Test
import kotlin.test.assertEquals

class XqtRepostCountTest {
    @Test
    fun retweetCountIncludesQuotedRetweets() {
        assertEquals(17L, totalRepostCount(retweets = 12L, quotes = 5L))
    }

    @Test
    fun blueskyCountAlsoIncludesQuotes() {
        assertEquals(17L, totalRepostCount(retweets = 12L, quotes = 5L))
    }

    @Test
    fun noQuotesRetainsOrdinaryRetweetCount() {
        assertEquals(12L, totalRepostCount(retweets = 12L, quotes = 0L))
    }

    @Test
    fun noRetweetsStillShowsQuotes() {
        assertEquals(5L, totalRepostCount(retweets = 0L, quotes = 5L))
    }
}
