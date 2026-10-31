package mobile.racemaster.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mobile.racemaster.BuildConfig
import mobile.racemaster.ui.components.ScreenTopBar

/** Full help index — a short, collapsible-card list over [HelpTopics.all], replacing the old
 *  wall of always-expanded essays nobody read. Each card's content is the exact same
 *  [HelpTopic] data shown by the per-screen "?" popup (see
 *  [mobile.racemaster.ui.components.HelpTopicDialog]), so the two can never drift apart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, onHome: () -> Unit) {
    Scaffold(
        topBar = {
            ScreenTopBar(
                title = {
                    Text(
                        buildAnnotatedString {
                            append("Help ")
                            withStyle(SpanStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal)) {
                                append("v${BuildConfig.VERSION_NAME}")
                            }
                        },
                    )
                },
                onBack = onBack,
                onHome = onHome,
            )
        },
        // MainActivity's outer Scaffold already reserves the nav bar's bottom inset for
        // every screen — without this, this inner Scaffold reserves it a second time.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                buildAnnotatedString {
                    append("RaceMaster Mobile is open source — code and issues at ")
                    withLink(
                        LinkAnnotation.Url(
                            url = "https://github.com/pd-giz-dave/racemaster-mobile",
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                ),
                            ),
                        ),
                    ) {
                        append("github.com/pd-giz-dave/racemaster-mobile")
                    }
                    append(".")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            WhatsNewCard(WhatsNew.versions)
            HelpTopics.all.forEach { topic -> HelpTopicCard(topic) }
        }
    }
}

@Composable
private fun WhatsNewCard(versions: List<VersionNotes>) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("What's New", style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                versions.forEach { version ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "v${version.version}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        version.highlights.forEach { tip ->
                            Text("•  $tip", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun HelpTopicCard(topic: HelpTopic) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(topic.title, style = MaterialTheme.typography.titleMedium)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                topic.tips.forEach { tip -> Text("•  $tip", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
}
