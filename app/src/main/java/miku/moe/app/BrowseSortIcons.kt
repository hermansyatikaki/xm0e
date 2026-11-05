package miku.moe.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.ui.graphics.vector.ImageVector

internal fun browseSortIcon(key: String, label: String): ImageVector? = when {
    key in setOf("latest", "update", "new-update", "new-upload") -> Icons.Outlined.NewReleases
    key in setOf("popular", "popularity", "trending") -> Icons.Filled.Favorite
    label == "Top Views" -> Icons.Filled.Favorite
    else -> null
}
