package dev.dimension.flare.ui.screen.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlueskyEngagementQueryTest {
    @Test
    fun parsesRepostAndQuoteRequests() {
        val uri = "at://did:plc:abc123/app.bsky.feed.post/3kexample"
        val repost = BlueskyEngagementQuery.fromSearchQuery(
            "__reader_bluesky_engagement__reposts:$uri"
        )
        val quote = BlueskyEngagementQuery.fromSearchQuery(
            "__reader_bluesky_engagement__quotes:$uri"
        )
        assertEquals("reposts", repost?.kind)
        assertEquals("quotes", quote?.kind)
        assertEquals(uri, repost?.uri)
        assertEquals(uri, quote?.uri)
    }

    @Test
    fun doesNotCaptureOrdinarySearchOrInvalidLinks() {
        assertNull(BlueskyEngagementQuery.fromSearchQuery("quoted_tweet_id:12345"))
        assertNull(BlueskyEngagementQuery.fromSearchQuery("cats"))
        assertNull(BlueskyEngagementQuery.fromSearchQuery(
            "__reader_bluesky_engagement__quotes:https://example.com"
        ))
        assertNull(BlueskyEngagementQuery.fromSearchQuery(
            "__reader_bluesky_engagement__unknown:at://did:plc:x/post/1"
        ))
    }
}
