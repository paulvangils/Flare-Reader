package dev.dimension.flare.ui.screen.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.dimension.flare.data.network.ktorClient
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.route.DeeplinkRoute
import dev.dimension.flare.ui.route.toUri
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * An additive search-screen mode: reuses the existing DeeplinkRoute.Search serializer rather than
 * adding fields to persisted ActionMenu items or changing cache, timeline or navigation schemas.
 */
internal data class BlueskyEngagementQuery(
    val kind: String,
    val uri: String,
) {
    companion object {
        const val PREFIX = "__reader_bluesky_engagement__"

        fun fromSearchQuery(query: String): BlueskyEngagementQuery? {
            if (!query.startsWith(PREFIX)) return null
            val rest = query.removePrefix(PREFIX)
            val kind = rest.substringBefore(':')
            val uri = rest.substringAfter(':', "")
            if (kind !in setOf("reposts", "quotes") || !uri.startsWith("at://")) return null
            return BlueskyEngagementQuery(kind, uri)
        }
    }
}

private data class EngagementItem(
    val did: String,
    val handle: String,
    val displayName: String,
    val postUri: String? = null,
    val text: String? = null,
)

private data class EngagementPage(
    val items: List<EngagementItem>,
    val nextCursor: String?,
)

private fun JsonObject.string(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull

private fun parseEngagementPage(json: String, kind: String): EngagementPage {
    val root = Json.parseToJsonElement(json).jsonObject
    val entries = (root[if (kind == "reposts") "repostedBy" else "posts"]?.jsonArray).orEmpty()
    val items = entries.mapNotNull { element ->
        val entry = element.jsonObject
        val profile = if (kind == "quotes") entry["author"]?.jsonObject else entry
        val did = profile?.string("did") ?: return@mapNotNull null
        val handle = profile.string("handle").orEmpty()
        val displayName = profile.string("displayName").orEmpty().ifBlank { handle }
        val record = entry["record"]?.jsonObject
        EngagementItem(
            did = did,
            handle = handle,
            displayName = displayName,
            postUri = if (kind == "quotes") entry.string("uri") else null,
            text = if (kind == "quotes") record?.string("text") else null,
        )
    }
    return EngagementPage(items, root.string("cursor"))
}

@Composable
internal fun BlueskyEngagementScreen(
    accountType: AccountType,
    query: BlueskyEngagementQuery,
    onUserClick: (AccountType, MicroBlogKey) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val host = (accountType as? AccountType.Specific)?.accountKey?.host
    var kind by remember(query) { mutableStateOf(query.kind) }
    var items by remember(query, kind) { mutableStateOf<List<EngagementItem>>(emptyList()) }
    var cursor by remember(query, kind) { mutableStateOf<String?>(null) }
    var nextCursor by remember(query, kind) { mutableStateOf<String?>(null) }
    var loading by remember(query, kind) { mutableStateOf(false) }
    var error by remember(query, kind) { mutableStateOf<String?>(null) }
    var requested by remember(query, kind) { mutableStateOf(false) }

    LaunchedEffect(query.uri, kind, cursor) {
        loading = true
        error = null
        try {
            val client = ktorClient()
            try {
                val endpoint = if (kind == "reposts") {
                    "app.bsky.feed.getRepostedBy"
                } else {
                    "app.bsky.feed.getQuotes"
                }
                val response = client.get("https://public.api.bsky.app/xrpc/$endpoint") {
                    parameter("uri", query.uri)
                    parameter("limit", 50)
                    cursor?.let { parameter("cursor", it) }
                }
                if (response.status.value !in 200..299) {
                    error = "Bluesky kon de gegevens niet ophalen (HTTP ${response.status.value})."
                } else {
                    val page = parseEngagementPage(response.bodyAsText(), kind)
                    items = if (cursor == null) page.items else items + page.items
                    nextCursor = page.nextCursor?.takeIf { it != cursor }
                }
            } finally {
                client.close()
            }
        } catch (exception: Exception) {
            error = "Ophalen mislukt: ${exception.message ?: "onbekende fout"}"
        } finally {
            loading = false
            requested = true
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Bluesky — retweets en citaten",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Button(onClick = { kind = "reposts" }, enabled = kind != "reposts") {
                Text("Retweets")
            }
            Button(onClick = { kind = "quotes" }, enabled = kind != "quotes") {
                Text("Citaten")
            }
        }
        if (host != null) {
            TextButton(
                onClick = {
                    uriHandler.openUri(
                        DeeplinkRoute.Status.Detail(
                            MicroBlogKey(query.uri, host),
                            accountType,
                        ).toUri(),
                    )
                },
            ) {
                Text("Oorspronkelijke Bluesky-post bekijken")
            }
        }
        HorizontalDivider()
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items, key = { item -> item.postUri ?: item.did }) { item ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (kind == "quotes" && item.postUri != null && host != null) {
                                uriHandler.openUri(
                                    DeeplinkRoute.Status.Detail(
                                        MicroBlogKey(item.postUri, host),
                                        accountType,
                                    ).toUri(),
                                )
                            } else if (host != null) {
                                onUserClick(accountType, MicroBlogKey(item.did, host))
                            }
                        }
                        .padding(16.dp),
                ) {
                    Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                    Text("@${item.handle}", style = MaterialTheme.typography.bodySmall)
                    if (!item.text.isNullOrBlank()) {
                        Text(item.text, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                HorizontalDivider()
            }
            if (loading) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                }
            }
            if (error != null) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                        TextButton(
                            onClick = {
                                // Re-trigger on failure, preserving the first page.
                                requested = false
                                cursor = null
                                items = emptyList()
                            },
                        ) { Text("Opnieuw proberen") }
                    }
                }
            }
            if (requested && items.isEmpty() && !loading && error == null) {
                item { Text("Geen ${if (kind == "quotes") "citaten" else "retweets"} gevonden.", modifier = Modifier.padding(16.dp)) }
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
