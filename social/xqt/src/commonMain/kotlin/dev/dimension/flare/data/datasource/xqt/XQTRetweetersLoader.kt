package dev.dimension.flare.data.datasource.xqt

import dev.dimension.flare.common.encodeJson
import dev.dimension.flare.data.database.cache.mapper.cursor
import dev.dimension.flare.data.database.cache.mapper.users
import dev.dimension.flare.data.datasource.microblog.paging.PagingResult
import dev.dimension.flare.data.network.xqt.XQTService
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.model.mapper.render
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable
internal data class RetweetersVariables(
    val tweetId: String,
    val count: Int = 20,
    val cursor: String? = null,
    @Required val includePromotedContent: Boolean = true,
)

internal suspend fun XQTService.loadReaderRetweeters(
    accountKey: MicroBlogKey,
    tweetId: String,
    cursor: String?,
): PagingResult<UiProfile> {
    val variables = RetweetersVariables(tweetId = tweetId, cursor = cursor).encodeJson()
    // Use the operation declared by the generated, authenticated UserListApi.
    // A changing Twitter GraphQL query identifier may still cause a recoverable
    // service error; do not silently claim an empty retweeter list in that case.
    val response = getRetweeters(variables = variables).body()
        ?: error("Twitter gaf geen geldige retweetersgegevens terug")
    val instructions = response.data.retweetersTimeline.timeline.instructions
    return PagingResult(
        data = instructions.users().map { it.render(accountKey = accountKey) },
        nextKey = instructions.cursor(),
    )
}
