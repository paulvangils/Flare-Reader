package dev.dimension.flare.data.datasource.microblog

import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.ui.model.UiProfile
import kotlin.native.HiddenFromObjC

/** On-demand engagement reader; not part of the persisted timeline or post cache. */
@HiddenFromObjC
public interface RetweetersDataSource {
    public suspend fun loadRetweeters(tweetId: String, cursor: String?): PagingResult<UiProfile>
}
