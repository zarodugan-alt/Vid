package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * Thumbnail slot used by every media row in the app.
 *
 * [model] is either a remote poster URL, an absolute path to a locally generated
 * preview, or null. Whenever the image is missing, still loading or fails to
 * decode, [fallbackIcon] is drawn instead, so the slot never collapses.
 */
@Composable
fun MediaThumbnail(
    model: Any?,
    fallbackIcon: ImageVector,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconSize: Dp = 24.dp,
    contentDescription: String? = null,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    val resolved = remember(model) { resolveModel(model) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val fallback: @Composable () -> Unit = {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = contentDescription,
                tint = iconTint,
                modifier = Modifier.size(iconSize)
            )
        }

        if (resolved == null) {
            fallback()
        } else {
            SubcomposeAsyncImage(
                model = resolved,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
                loading = { fallback() },
                error = { fallback() }
            )
        }

        overlay()
    }
}

/** Local files must be handed to Coil as [File]s, remote posters as URLs. */
private fun resolveModel(model: Any?): Any? = when (model) {
    null -> null
    is String -> model.trim().takeIf { it.isNotBlank() }?.let { value ->
        when {
            value.startsWith("/") -> File(value).takeIf { it.exists() && it.length() > 0 }
            value.startsWith("file://") -> File(value.removePrefix("file://"))
                .takeIf { it.exists() && it.length() > 0 }

            value.startsWith("http://") || value.startsWith("https://") ||
                value.startsWith("content://") || value.startsWith("data:") -> value

            else -> null
        }
    }

    else -> model
}
