package dev.dimension.flare.ui.screen.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.dimension.flare.model.AccountType
import dev.dimension.flare.model.MicroBlogKey
import dev.dimension.flare.ui.route.Route

@Composable
internal fun StatusEngagementSheet(
    statusKey: MicroBlogKey,
    accountType: AccountType,
    navigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "Reposts",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        ListItem(
            headlineContent = {
                Text("Wie heeft dit gerepost?")
            },
            supportingContent = {
                Text("Open de lijst met reposters op X")
            },
            modifier =
                Modifier.clickable {
                    uriHandler.openUri(
                        "https://${statusKey.host}/i/status/${statusKey.id}/retweets",
                    )
                },
        )

        ListItem(
            headlineContent = {
                Text("Geciteerde reposts")
            },
            supportingContent = {
                Text("Lees posts waarin dit bericht wordt geciteerd")
            },
            modifier =
                Modifier.clickable {
                    onBack()
                    navigate(
                        Route.Search(
                            accountType = accountType,
                            query = "quoted_tweet_id:${statusKey.id}",
                        ),
                    )
                },
        )

        Text(
            text = "Geciteerde reposts worden via de zoekindex van X opgehaald; daardoor kan de lijst afwijken van de teller.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
