package moe.ouom.neriplayer.ui.screen.tab.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import moe.ouom.neriplayer.common.R as CommonR
import moe.ouom.neriplayer.ui.theme.NeriDesign

@Composable
internal fun InternationalHomeSpotlight(entry: InternationalHomeEntry, onClick: () -> Unit) {
    val clean = NeriDesign.isClean
    val colors = MaterialTheme.colorScheme
    val playable = entry is InternationalHomeEntry.Track
    val action = if (playable) stringResource(CommonR.string.player_play) else stringResource(entry.kind.labelRes)
    val ink = if (clean) colors.onSurface else Color.White
    val item = entry.item
    Surface(modifier = Modifier.fillMaxWidth().testTag("internationalHomeSpotlight"),
        shape = NeriDesign.tokens.cardShape, color = if (clean) colors.surfaceContainerLow else colors.primaryContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().clickable(onClickLabel = action, role = Role.Button, onClick = onClick)) {
            val height = (maxWidth * if (clean) 0.48f else 0.57f).coerceIn(152.dp, 260.dp)
            val artwork = if (clean) Modifier.align(Alignment.CenterEnd).padding(14.dp).size((height - 28.dp).coerceAtMost(maxWidth * 0.39f))
                .clip(NeriDesign.tokens.coverSmallShape)
            else Modifier.matchParentSize()
            Box(artwork.background(if (clean) colors.secondaryContainer else colors.primaryContainer),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Album, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(46.dp))
                if (item.coverUrl.isNotBlank()) AsyncImage(model = item.coverUrl, contentDescription = null,
                    modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            if (!clean) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(
                Color.Black.copy(alpha = 0.86f), Color.Black.copy(alpha = 0.66f), Color.Black.copy(alpha = 0.16f)
            ))))
            Column(Modifier.fillMaxWidth(if (clean) 0.57f else 0.80f).heightIn(min = height).padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(CommonR.string.international_home_spotlight), style = MaterialTheme.typography.labelLarge,
                    color = if (clean) colors.primary else ink)
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = ink)
                    val subtitle = if (entry is InternationalHomeEntry.Track) entry.song.artist else item.subtitle
                    if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(34.dp).background(if (clean) colors.primary else Color.White, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Icon(if (playable) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null, tint = if (clean) colors.onPrimary else Color.Black, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}
