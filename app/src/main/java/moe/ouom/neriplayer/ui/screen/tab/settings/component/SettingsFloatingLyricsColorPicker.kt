package moe.ouom.neriplayer.ui.screen.tab.settings.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.R

private data class FloatingLyricsColorOption(
    val hex: String,
    val labelRes: Int,
    val color: Color
)

private val FloatingLyricsColorOptions = listOf(
    FloatingLyricsColorOption("FFFFFF", R.string.settings_floating_lyrics_color_white, Color(0xFFFFFFFF)),
    FloatingLyricsColorOption("000000", R.string.settings_floating_lyrics_color_black, Color(0xFF000000)),
    FloatingLyricsColorOption("FF5A64", R.string.settings_floating_lyrics_color_red, Color(0xFFFF5A64)),
    FloatingLyricsColorOption("FF9F1C", R.string.settings_floating_lyrics_color_orange, Color(0xFFFF9F1C)),
    FloatingLyricsColorOption("FFE566", R.string.settings_floating_lyrics_color_yellow, Color(0xFFFFE566)),
    FloatingLyricsColorOption("57C46A", R.string.settings_floating_lyrics_color_green, Color(0xFF57C46A)),
    FloatingLyricsColorOption("24C6DC", R.string.settings_floating_lyrics_color_cyan, Color(0xFF24C6DC)),
    FloatingLyricsColorOption("4C9BFF", R.string.settings_floating_lyrics_color_blue, Color(0xFF4C9BFF)),
    FloatingLyricsColorOption("A66CFF", R.string.settings_floating_lyrics_color_purple, Color(0xFFA66CFF)),
    FloatingLyricsColorOption("FF6CA8", R.string.settings_floating_lyrics_color_pink, Color(0xFFFF6CA8))
)

@Composable
internal fun FloatingLyricsColorPicker(
    titleRes: Int,
    selectedColorHex: String,
    onColorSelected: (String) -> Unit
) {
    val title = stringResource(titleRes)
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.ColorLens,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        headlineContent = { Text(title) },
        supportingContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FloatingLyricsColorOptions.chunked(5).forEach { rowOptions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowOptions.forEach { option ->
                            FloatingLyricsColorSwatch(
                                option = option,
                                selected = selectedColorHex.equals(option.hex, ignoreCase = true),
                                onClick = { onColorSelected(option.hex) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun FloatingLyricsColorSwatch(
    option: FloatingLyricsColorOption,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(option.color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Text(
                    text = "✓",
                    color = if (option.hex == "000000") Color.White else Color.Black,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        Text(
            text = stringResource(option.labelRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
