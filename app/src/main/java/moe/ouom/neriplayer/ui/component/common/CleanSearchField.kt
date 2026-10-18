package moe.ouom.neriplayer.ui.component.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.ouom.neriplayer.ui.theme.LocalHasCustomBackground
import moe.ouom.neriplayer.ui.theme.NeriMotion
import moe.ouom.neriplayer.common.R as CoreCommonR

/**
 * Capsule search field for the clean style: a quiet ink fill instead of an outline, a focus
 * ring that fades in, and a round clear control that scales in once there is text.
 */
@Composable
fun CleanSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    val focusManager = LocalFocusManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    val ring by animateColorAsState(
        targetValue = if (focused) colors.primary.copy(alpha = 0.5f) else Color.Transparent,
        animationSpec = NeriMotion.smooth(),
        label = "clean_search_ring"
    )
    val iconTint by animateColorAsState(
        targetValue = if (focused) colors.primary else colors.onSurfaceVariant,
        animationSpec = NeriMotion.smooth(),
        label = "clean_search_icon"
    )
    val fill = if (LocalHasCustomBackground.current) Color.Transparent else colors.onSurface.copy(alpha = 0.06f)
    NeriSearchFieldGlass(modifier = modifier.height(46.dp), shape = CircleShape) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            interactionSource = interactionSource,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSearch?.invoke()
                focusManager.clearFocus()
            }),
            modifier = Modifier
                .fillMaxSize()
                .background(fill, CircleShape)
                .border(1.dp, ring, CircleShape),
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 15.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                    AnimatedVisibility(
                        visible = query.isNotEmpty(),
                        enter = fadeIn() + scaleIn(initialScale = 0.6f),
                        exit = fadeOut() + scaleOut(targetScale = 0.6f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { onQueryChange("") },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .background(colors.onSurfaceVariant.copy(alpha = 0.55f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = stringResource(CoreCommonR.string.action_clear),
                                    tint = colors.surface,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        )
    }
}
