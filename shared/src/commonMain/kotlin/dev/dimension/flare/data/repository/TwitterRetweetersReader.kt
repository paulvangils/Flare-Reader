package dev.dimension.flare.data.repository

import dev.dimension.flare.data.datasource.microblog.RetweetersDataSource
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.di.koinInject
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import kotlinx.coroutines.flow.first
import kotlin.native.HiddenFromObjC

/** Retrieves a user's saved X/Twitter session without exposing credentials to the Android UI. */
@HiddenFromObjC
public class TwitterRetweetersReader {
    private val accounts: AccountRepository by koinInject()

    public suspend fun load(
        accountKey: MicroBlogKey,
        tweetId: String,
        cursor: String? = null,
    ): PagingResult<UiProfile> {
        val account = accounts.allAccounts.first().firstOrNull { it.accountKey == accountKey }
            ?: error("Geen gekoppeld Twitter-account gevonden")
        val datasource = accounts.getOrCreateDataSource(account)
        val reader = datasource as? RetweetersDataSource
            ?: error("Deze account ondersteunt het ophalen van retweeters niet")
        return reader.loadRetweeters(tweetId, cursor)
    }
}
