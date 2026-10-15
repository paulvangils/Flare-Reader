package dev.dimension.flare.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.repository.TwitterRetweetersReader
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.model.UiProfile
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import kotlinx.coroutines.CancellationException

internal const val TWITTER_RETWEETERS_QUERY_PREFIX = "__reader_twitter_retweeters__"

@Composable
internal fun TwitterRetweetersScreen(
    accountType: AccountType,
    tweetId: String,
    onUserClick: (AccountType, MicroBlogKey) -> Unit,
) {
    val accountKey = (accountType as? AccountType.Specific)?.accountKey
    val reader = remember { TwitterRetweetersReader() }
    val uriHandler = LocalUriHandler.current
    var profiles by remember(accountKey, tweetId) { mutableStateOf<List<UiProfile>>(emptyList()) }
    var cursor by remember(accountKey, tweetId) { mutableStateOf<String?>(null) }
    var nextCursor by remember(accountKey, tweetId) { mutableStateOf<String?>(null) }
    var loading by remember(accountKey, tweetId) { mutableStateOf(false) }
    var error by remember(accountKey, tweetId) { mutableStateOf<String?>(null) }
    var loaded by remember(accountKey, tweetId) { mutableStateOf(false) }
    var retry by remember(accountKey, tweetId) { mutableIntStateOf(0) }

    LaunchedEffect(accountKey, tweetId, cursor, retry) {
        if (accountKey == null || tweetId.isBlank()) {
            error = "Kies een aangemeld Twitter-account."
            loaded = true
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            val page = reader.load(accountKey, tweetId, cursor)
            profiles = if (cursor == null) page.data else (profiles + page.data).distinctBy { it.key }
            nextCursor = page.nextKey?.takeUnless { it == cursor }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            error = exception.message ?: "Retweets ophalen is mislukt."
        } finally {
            loading = false
            loaded = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Twitter — wie heeft dit geretweet?",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        if (accountKey != null) {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        DeeplinkRoute.Status.Detail(
                            statusKey = MicroBlogKey(tweetId, accountKey.host),
                            accountType = accountType,
                        ).toUri(),
                    )
                },
            ) { Text("Oorspronkelijke tweet bekijken") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize()) {
            items(profiles, key = { it.key.toString() }) { profile ->
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onUserClick(accountType, profile.key) }
                        .padding(16.dp),
                ) {
                    Text(profile.name.raw, style = MaterialTheme.typography.titleMedium)
                    Text(profile.handle.raw, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            if (loading) {
                item { CircularProgressIndicator(Modifier.padding(16.dp)) }
            }
            if (error != null) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { retry++ }) { Text("Opnieuw proberen") }
                    }
                }
            }
            if (loaded && profiles.isEmpty() && !loading && error == null) {
                item { Text("Geen retweeters gevonden.", modifier = Modifier.padding(16.dp)) }
            }
            if (!loading && error == null && nextCursor != null) {
                item {
                    TextButton(
                        onClick = { cursor = nextCursor },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Meer laden") }
                }
            }
        }
    }
}
