package miku.moe.app

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.util.Locale

/**
 * Seksi AniList (dipakai Detail Anime V2, Detail Anime Style V2, Detail Manga V4).
 *
 * Layout dibuat STABIL: semua tab (Info / Karakter / Rekomendasi) memakai tinggi body yang sama
 * ([BODY_HEIGHT]), jadi konten di bawahnya (Episode / Chapter) tidak naik-turun saat ganti tab.
 * Tab diganti lewat chip (tanpa pager), konten karakter & rekomendasi digeser horizontal 2 baris.
 */
private val BODY_HEIGHT = 412.dp
private val BODY_PADDING = 12.dp
private val CHARACTER_TILE_WIDTH = 96.dp
private val RECOMMENDATION_TILE_WIDTH = 104.dp

private const val TAB_INFO = "Info"
private const val TAB_CHARACTER = "Karakter"
private const val TAB_RECOMMENDATION = "Rekomendasi"

@Composable
fun AniListSection(
    info: AniListClient.AniListInfo,
    horizontalPadding: Dp = 16.dp,
    header: @Composable () -> Unit,
    onRecommendationClick: (String) -> Unit
) {
    val panels = remember(info.anilistId, info.characters.size, info.recommendations.size) {
        val list = ArrayList<String>()
        list.add(TAB_INFO)
        if (info.characters.isNotEmpty()) list.add(TAB_CHARACTER)
        if (info.recommendations.isNotEmpty()) list.add(TAB_RECOMMENDATION)
        list
    }
    var selectedTab by rememberSaveable(info.anilistId) { mutableStateOf(TAB_INFO) }
    val activeTab = if (selectedTab in panels) selectedTab else TAB_INFO

    // Status "tampilkan VA" per karakter; disimpan di level section supaya tidak reset saat ganti tab.
    val showVoiceActor = remember(info.anilistId) { mutableStateMapOf<Int, Boolean>() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = 10.dp)
    ) {
        header()
        Spacer(Modifier.height(10.dp))
        if (panels.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                panels.forEach { label ->
                    val selected = activeTab == label
                    Surface(
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { selectedTab = label }
                    ) {
                        Text(
                            label,
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(BODY_HEIGHT)
            ) {
                Crossfade(targetState = activeTab, label = "anilist_tab") { tab ->
                    when (tab) {
                        TAB_CHARACTER -> AniListCharacterPanel(info, showVoiceActor)
                        TAB_RECOMMENDATION -> AniListRecommendationPanel(info, onRecommendationClick)
                        else -> AniListInfoPanel(info, scrollable = true)
                    }
                }
            }
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AniListInfoPanel(info, scrollable = false)
            }
        }
    }
}

@Composable
private fun AniListInfoPanel(info: AniListClient.AniListInfo, scrollable: Boolean) {
    val context = LocalContext.current
    val scrollModifier = if (scrollable) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()
    Column(scrollModifier.padding(BODY_PADDING)) {
        Row {
            if (info.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(info.imageUrl).crossfade(true).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(84.dp, 118.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    info.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (info.status.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        info.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (info.score > 0) AniListStat("Skor", "★ " + formatAniListScore(info.score))
                    if (info.rank > 0) AniListStat("Peringkat", "#" + info.rank)
                    if (info.popularityRank > 0) AniListStat("Popularitas", "#" + info.popularityRank)
                }
                if (info.users > 0) {
                    Spacer(Modifier.height(5.dp))
                    Text(
                        formatAniListCompact(info.users) + " pengguna AniList",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (info.favourites > 0) {
                    Text(
                        formatAniListCompact(info.favourites) + " favorit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (info.facts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            for ((label, value) in info.facts) {
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        label,
                        Modifier.width(92.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        value,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (info.genres.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(info.genres) { genre ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            genre,
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

/** Grid 2 baris yang digeser horizontal; tinggi tetap = isi body. */
@Composable
private fun AniListCharacterPanel(
    info: AniListClient.AniListInfo,
    showVoiceActor: MutableMap<Int, Boolean>
) {
    val context = LocalContext.current
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(BODY_PADDING),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(info.characters, key = { index, _ -> index }) { index, character ->
            val hasVoiceActor = character.voiceActor.isNotBlank()
            val voiceActorMode = hasVoiceActor && showVoiceActor[index] == true
            val shownName = if (voiceActorMode) character.voiceActor else character.name
            val shownImage = if (voiceActorMode) character.voiceActorImage else character.imageUrl
            val subLabel = if (voiceActorMode) "Pengisi suara" else aniListRoleLabel(character.role)

            Column(
                Modifier
                    .width(CHARACTER_TILE_WIDTH)
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (hasVoiceActor) Modifier.clickable { showVoiceActor[index] = !voiceActorMode }
                        else Modifier
                    )
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    if (shownImage.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(shownImage).crossfade(true).build(),
                            contentDescription = shownName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (hasVoiceActor) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    if (voiceActorMode) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = if (voiceActorMode) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    shownName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                Text(
                    subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (voiceActorMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AniListRecommendationPanel(
    info: AniListClient.AniListInfo,
    onRecommendationClick: (String) -> Unit
) {
    val context = LocalContext.current
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(BODY_PADDING),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(info.recommendations, key = { index, _ -> index }) { _, rec ->
            Column(
                Modifier
                    .width(RECOMMENDATION_TILE_WIDTH)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onRecommendationClick(rec.title) }
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(5f / 7f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    if (rec.imageUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(rec.imageUrl).crossfade(true).build(),
                            contentDescription = rec.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    rec.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    minLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun AniListStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun aniListRoleLabel(role: String): String = when (role) {
    "MAIN" -> "Utama"
    "SUPPORTING" -> "Pendukung"
    "BACKGROUND" -> "Latar"
    else -> ""
}

private val ANILIST_ID_LOCALE: Locale = Locale.forLanguageTag("id-ID")

private fun formatAniListScore(score: Double): String = String.format(ANILIST_ID_LOCALE, "%.1f", score)

private fun formatAniListCompact(value: Int): String = when {
    value >= 1_000_000 -> String.format(ANILIST_ID_LOCALE, "%.1f jt", value / 1_000_000.0)
    value >= 1_000 -> String.format(ANILIST_ID_LOCALE, "%.1f rb", value / 1_000.0)
    else -> value.toString()
}
