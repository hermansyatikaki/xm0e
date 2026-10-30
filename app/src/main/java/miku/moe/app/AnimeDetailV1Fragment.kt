@file:OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package miku.moe.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AnimeDetailV1Fragment : Fragment() {
    private var initialPost: AnimePost? = null
    private var systemBarsApplied = false
    private var historyRefreshVersion by mutableIntStateOf(0)
    private var defaultHistoryPreferences: SharedPreferences? = null
    private var animekuHistoryPreferences: SharedPreferences? = null
    private val animeHistoryChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "items") historyRefreshVersion++
    }

    companion object {
        @JvmStatic
        fun newInstance(post: AnimePost): AnimeDetailV1Fragment {
            return AnimeDetailV1Fragment().apply {
                arguments = Bundle().apply {
                    putString("source_id", post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT)
                    putString("image_url", post.imgUrl ?: "")
                    putString("title", post.categoryName ?: "")
                    putInt("category_id", post.categoryId)
                    putInt("channel_id", post.channelId)
                    putString("slug", post.slug ?: "")
                    putString("genre", post.genre ?: "")
                    putString("rating", post.rating ?: "")
                    putInt("year", post.year)
                    putString("views", post.countView ?: "")
                    putString("episode_count", post.episodeCount ?: "")
                    putString("description", post.description ?: "")
                    putString("status", post.statusVideo ?: "")
                    putBoolean("ongoing", post.ongoing)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = arguments
        initialPost = AnimePost(args?.getString("image_url", "").orEmpty(), args?.getString("title", "").orEmpty(), args?.getInt("category_id", -1) ?: -1, args?.getInt("channel_id", -1) ?: -1).apply {
            sourceId = args?.getString("source_id", AnimeSettingsManager.SOURCE_DEFAULT) ?: AnimeSettingsManager.SOURCE_DEFAULT
            slug = args?.getString("slug", "").orEmpty()
            genre = args?.getString("genre", "").orEmpty()
            rating = args?.getString("rating", "").orEmpty()
            year = args?.getInt("year", 0) ?: 0
            countView = args?.getString("views", "").orEmpty()
            episodeCount = args?.getString("episode_count", "").orEmpty()
            description = args?.getString("description", "").orEmpty()
            statusVideo = args?.getString("status", "").orEmpty()
            ongoing = args?.getBoolean("ongoing", false) ?: false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        applyAnimeV1SystemBars()
        val swipeRefreshLayout = SwipeRefreshLayout(requireContext()).apply {
            setColorSchemeColors(MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorPrimary, android.graphics.Color.rgb(103, 80, 164)))
            setProgressBackgroundColorSchemeColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, android.graphics.Color.rgb(28, 27, 32)))
        }
        val composeView = ComposeView(requireContext())
        swipeRefreshLayout.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        composeView.setContent {
            var refreshKey by remember { mutableIntStateOf(0) }
            DisposableEffect(swipeRefreshLayout) {
                swipeRefreshLayout.setOnRefreshListener { refreshKey++ }
                onDispose { swipeRefreshLayout.setOnRefreshListener { } }
            }
            MikuAnimeDetailV1Theme {
                AnimeDetailV1Screen(
                    initial = initialPost,
                    historyVersion = historyRefreshVersion,
                    refreshKey = refreshKey,
                    onRefreshFinished = { swipeRefreshLayout.isRefreshing = false },
                    onCanRefreshChange = { swipeRefreshLayout.isEnabled = it },
                    onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    onAnimeClick = { openAnimeDetail(it) },
                    onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) }
                )
            }
        }
        return swipeRefreshLayout
    }

    override fun onStart() {
        super.onStart()
        val appContext = requireContext().applicationContext
        defaultHistoryPreferences = appContext.getSharedPreferences("anime_watch_history", Context.MODE_PRIVATE)
        animekuHistoryPreferences = appContext.getSharedPreferences("animeku_watch_history", Context.MODE_PRIVATE)
        defaultHistoryPreferences?.registerOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        animekuHistoryPreferences?.registerOnSharedPreferenceChangeListener(animeHistoryChangeListener)
    }

    override fun onResume() {
        super.onResume()
        historyRefreshVersion++
        view?.postDelayed({ if (isAdded && !isHidden) historyRefreshVersion++ }, 250L)
        applyAnimeV1SystemBars()
    }

    override fun onStop() {
        defaultHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        animekuHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        defaultHistoryPreferences = null
        animekuHistoryPreferences = null
        super.onStop()
    }

    override fun onPause() {
        restoreAnimeV1SystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreAnimeV1SystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            restoreAnimeV1SystemBars()
        } else {
            historyRefreshVersion++
            view?.postDelayed({ if (isAdded && !isHidden) historyRefreshVersion++ }, 250L)
            applyAnimeV1SystemBars()
        }
    }

    private fun applyAnimeV1SystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreAnimeV1SystemBars() {
        val host = activity ?: return
        if (!systemBarsApplied) return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = false
    }

    private fun openAnimeDetail(post: AnimePost) {
        when (val activity = activity) {
            is MainActivity -> activity.openAnimeDetailV2(post)
            is AnimexAll -> activity.openAnimeDetailV2(post)
        }
    }

    private fun openGenre(sourceId: String, sourceLabel: String, title: String, value: String) {
        when (val activity = activity) {
            is MainActivity -> activity.openAnimeGenreResult(sourceId, sourceLabel, title, value)
            is AnimexAll -> activity.openAnimeGenreResult(sourceId, sourceLabel, title, value)
        }
    }
}

@Composable
private fun MikuAnimeDetailV1Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun themeColor(attr: Int, fallback: Int): Color = Color(MaterialColors.getColor(context, attr, fallback))
    val colors = darkColorScheme(
        primary = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFFFF78C8.toInt()),
        onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFF31111F.toInt()),
        primaryContainer = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF5B2F4B.toInt()),
        onPrimaryContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFFFD7EC.toInt()),
        secondary = themeColor(com.google.android.material.R.attr.colorSecondary, 0xFFB7C7FF.toInt()),
        onSecondary = themeColor(com.google.android.material.R.attr.colorOnSecondary, 0xFF1F293D.toInt()),
        secondaryContainer = themeColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFF3D4563.toInt()),
        onSecondaryContainer = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFFE0E6FF.toInt()),
        background = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surface = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surfaceVariant = themeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF302830.toInt()),
        surfaceContainer = themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF262429.toInt()),
        surfaceContainerHigh = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2E2B31.toInt()),
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2EEF5.toInt()),
        onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFD0C7D2.toInt()),
        outline = themeColor(com.google.android.material.R.attr.colorOutline, 0xFF938F99.toInt()),
        outlineVariant = themeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFF4A4450.toInt())
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(30.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun AnimeDetailV1Screen(
    initial: AnimePost?,
    historyVersion: Int,
    refreshKey: Int,
    onRefreshFinished: () -> Unit,
    onCanRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAnimeClick: (AnimePost) -> Unit,
    onGenreClick: (String, String, String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var detail by remember(initial) { mutableStateOf(initialAnimeDetail(initial)) }
    var loading by remember(initial) { mutableStateOf(true) }
    var isFavorite by remember(initial) { mutableStateOf(initial != null && FavoriteManager.isFavorite(context, initial.sourceId, initial.categoryId, initial.slug)) }
    var episodeDescending by remember(initial) { mutableStateOf(readAnimeEpisodeNewestFirst(context, initial)) }
    var episodeGrid by remember(initial) { mutableStateOf(readAnimeEpisodeGridMode(context, initial)) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var playbackLoading by remember { mutableStateOf(false) }
    var pendingPlayback by remember { mutableStateOf<Pair<AnimeEpisodeItem, List<AnimeQualityOption>>?>(null) }

    val infoListState = rememberLazyListState()
    val episodeListState = rememberLazyListState()
    val similarGridState = rememberLazyGridState()

    LaunchedEffect(initial?.sourceId, initial?.categoryId, initial?.channelId, initial?.slug, refreshKey) {
        loading = true
        detail = try {
            withContext(Dispatchers.IO) { loadAnimeDetailData(initial) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            initialAnimeDetail(initial).copy(error = "Gagal memuat detail lengkap")
        }
        if (FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)) FavoriteManager.update(context, detail.post)
        if (detail.post.sourceId == AnimeSettingsManager.SOURCE_ANIMEKU) AnimekuHistoryManager.updateAnimeMetadata(context, detail.post) else HistoryManager.updateAnimeMetadata(context, detail.post)
        isFavorite = FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)
        loading = false
        onRefreshFinished()
    }

    val episodeHistory = remember(detail.post.sourceId, detail.post.categoryId, detail.post.slug, detail.post.categoryName, detail.episodes, historyVersion) {
        loadAnimeEpisodeHistory(context, detail.post, detail.episodes)
    }
    val startEpisode = remember(detail.episodes, episodeHistory) { resolveAnimeStartEpisode(detail.episodes, episodeHistory) }
    val shownEpisodes = remember(detail.episodes, episodeDescending) {
        if (episodeDescending) detail.episodes.asReversed() else detail.episodes
    }

    val primaryGenre = detail.genres.firstOrNull().orEmpty()
    val similarController = remember(loading, detail.post.sourceId, primaryGenre) {
        if (loading || primaryGenre.isBlank()) null else BrowseSourceAnimeController(
            context.applicationContext,
            detail.post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT,
            AnimeSettingsManager.labelForSourceId(detail.post.sourceId),
            "",
            primaryGenre,
            primaryGenre,
            true
        )
    }
    DisposableEffect(similarController) {
        onDispose { similarController?.destroy() }
    }
    LaunchedEffect(similarController, selectedTab) {
        if (selectedTab == 2) similarController?.start()
    }
    val similarItems = similarController?.posts?.filter { !isSameAnimeAv1(it, detail.post) } ?: emptyList()
    val similarLoading = similarController?.loading ?: false
    val similarHasMore = similarController?.hasMore ?: false

    LaunchedEffect(similarGridState, similarController) {
        snapshotFlow {
            val info = similarGridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            if (selectedTab == 2 && total > 0 && lastIndex >= total - 4) similarController?.loadNextPage()
        }
    }

    LaunchedEffect(selectedTab) {
        snapshotFlow {
            when (selectedTab) {
                0 -> infoListState.firstVisibleItemIndex == 0 && infoListState.firstVisibleItemScrollOffset == 0
                1 -> episodeListState.firstVisibleItemIndex == 0 && episodeListState.firstVisibleItemScrollOffset == 0
                else -> similarGridState.firstVisibleItemIndex == 0 && similarGridState.firstVisibleItemScrollOffset == 0
            }
        }.collect { onCanRefreshChange(it) }
    }

    val playEpisode: (AnimeEpisodeItem) -> Unit = { episode ->
        scope.launch {
            playbackLoading = true
            try {
                val options = withContext(Dispatchers.IO) { loadAnimePlaybackOptions(context, detail, episode) }
                handlePlaybackOptions(context, detail, episode, options) { pendingPlayback = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Gagal memuat opsi pemutaran", Toast.LENGTH_SHORT).show()
            } finally {
                playbackLoading = false
            }
        }
    }
    val doShare: () -> Unit = {
        val title = detail.post.categoryName.orEmpty()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) AnimeSettingsManager.labelForSourceId(detail.post.sourceId) else title + " - " + AnimeSettingsManager.labelForSourceId(detail.post.sourceId))
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
    val doFavorite: () -> Unit = {
        FavoriteManager.toggle(context, detail.post)
        isFavorite = FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (selectedTab != 0) {
                DetailTopBarAv1(
                    title = detail.post.categoryName.orEmpty().ifBlank { "Detail Anime" },
                    isFavorite = isFavorite,
                    loading = loading,
                    onBack = onBack,
                    onShare = doShare,
                    onFavorite = doFavorite
                )
            }
        },
        floatingActionButton = {
            val start = startEpisode
            if (!loading && start != null && detail.episodes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { playEpisode(start) },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    text = { Text(if (episodeHistory.isNotEmpty()) "Lanjut " + start.title else "Mulai Menonton", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            } else if (loading) {
                MangaShimmerBlock(Modifier.width(176.dp).height(56.dp), rememberMangaShimmerProgress(), 16.dp)
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> AnimeDetailSkeletonAv1(Modifier, selectedTab, onBack, episodeGrid)
                selectedTab == 0 -> InfoTabAv1(
                    detail = detail,
                    episodeCount = detail.episodes.size,
                    selectedTab = selectedTab,
                    onTabSelect = { selectedTab = it },
                    isFavorite = isFavorite,
                    onBack = onBack,
                    onShare = doShare,
                    onFavorite = doFavorite,
                    onGenreClick = onGenreClick,
                    listState = infoListState
                )
                selectedTab == 1 -> EpisodeTabAv1(
                    episodes = shownEpisodes,
                    history = episodeHistory,
                    episodeGrid = episodeGrid,
                    selectedTab = selectedTab,
                    episodeCount = detail.episodes.size,
                    onTabSelect = { selectedTab = it },
                    onToggleOrder = {
                        episodeDescending = !episodeDescending
                        saveAnimeEpisodeNewestFirst(context, detail.post, episodeDescending)
                    },
                    onToggleLayout = {
                        episodeGrid = !episodeGrid
                        saveAnimeEpisodeGridMode(context, detail.post, episodeGrid)
                    },
                    onEpisodeClick = playEpisode,
                    listState = episodeListState
                )
                else -> SimilarTabAv1(
                    selectedTab = selectedTab,
                    episodeCount = detail.episodes.size,
                    onTabSelect = { selectedTab = it },
                    similar = similarItems,
                    loadingMore = similarLoading,
                    hasMore = similarHasMore,
                    hasGenre = primaryGenre.isNotBlank(),
                    onAnimeClick = onAnimeClick,
                    gridState = similarGridState
                )
            }
            if (playbackLoading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }

    val playback = pendingPlayback
    if (playback != null) {
        AlertDialog(
            onDismissRequest = { pendingPlayback = null },
            title = { Text("Resolusi tidak tersedia") },
            text = { Text("Pilih resolusi yang tersedia untuk episode ini.") },
            confirmButton = {
                Column {
                    playback.second.forEach { option ->
                        TextButton(onClick = {
                            PlaybackQualityManager.setQuality(context, option.quality)
                            openAnimePlayback(context, detail, playback.first, option)
                            pendingPlayback = null
                        }) { Text(option.label) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { pendingPlayback = null }) { Text("Batal") } }
        )
    }
}

private data class StatAv1(val label: String, val value: String, val color: Color)

private fun hasValueAv1(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    return text.isNotEmpty() && text != "-" && text != "0" && !text.equals("null", true)
}

private fun formatViewsAv1(raw: String): String {
    val text = raw.trim()
    if (text.isEmpty() || !text.all { it.isDigit() }) return text
    val number = text.toLongOrNull() ?: return text
    return when {
        number >= 1_000_000L -> String.format(java.util.Locale.US, "%.1fM", number / 1_000_000.0).replace(".0M", "M")
        number >= 1_000L -> String.format(java.util.Locale.US, "%.1fK", number / 1_000.0).replace(".0K", "K")
        else -> text
    }
}

@Composable
private fun QuickStatsGridAv1(detail: AnimeDetailData) {
    val post = detail.post
    val statusText = detail.status.ifBlank { post.statusVideo.orEmpty() }
    val score = post.rating.orEmpty()
    val epCount = if (detail.episodes.isNotEmpty()) "${detail.episodes.size} Ep" else post.episodeCount.orEmpty()
    val views = formatViewsAv1(post.countView.orEmpty())
    val year = if (post.year > 0) post.year.toString() else ""
    val stats = listOf(
        StatAv1("Rating", if (hasValueAv1(score)) "\u2605 $score" else "", Color(0xFFFFB300)),
        StatAv1("Status", statusText, animeStatusTextColorAv1(statusText)),
        StatAv1("Episode", epCount, MaterialTheme.colorScheme.primary),
        StatAv1("View", views, MaterialTheme.colorScheme.secondary),
        StatAv1("Tahun", year, MaterialTheme.colorScheme.tertiary)
    ).filter { hasValueAv1(it.value) }.take(4)
    if (stats.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stats.forEach { stat ->
            QuickStatItemAv1(stat.label, stat.value, stat.color, Modifier.weight(1f))
        }
    }
}

@Composable
private fun QuickStatItemAv1(label: String, value: String, accentColor: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = accentColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun InfoTabAv1(
    detail: AnimeDetailData,
    episodeCount: Int,
    selectedTab: Int,
    onTabSelect: (Int) -> Unit,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onGenreClick: (String, String, String, String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val sourceId = detail.post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT
    val sourceLabel = AnimeSettingsManager.labelForSourceId(sourceId)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item { DetailBannerAv1(detail, isFavorite, onBack, onShare, onFavorite) }
        stickyHeader { DetailTabsAv1(selectedTab, episodeCount, onTabSelect) }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                QuickStatsGridAv1(detail)
                Spacer(Modifier.height(10.dp))
                InfoCardAv1("Sinopsis Cerita") { expanded ->
                    SelectionContainer {
                        Text(
                            detail.description.ifBlank { "Sinopsis belum tersedia untuk anime ini." },
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = if (expanded) Int.MAX_VALUE else 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                val infoRows = listOf("Source" to sourceLabel, "Judul" to detail.post.categoryName.orEmpty()) +
                    detail.rows.filterNot { it.first.equals("Source", true) || it.first.equals("Judul", true) }
                InfoCardAv1("Informasi Lengkap") { expanded ->
                    val visibleRows = if (expanded) infoRows else infoRows.take(((infoRows.size + 1) / 2).coerceAtLeast(1))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        visibleRows.forEach { InfoRowAv1(it.first, it.second) }
                    }
                }
            }
        }
        if (detail.genres.isNotEmpty()) {
            item {
                Column(Modifier.padding(top = 16.dp)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Kategori & Genre",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        detail.genres.forEach { label ->
                            GenreChipAv1(label) { onGenreClick(sourceId, sourceLabel, label, label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeTabAv1(
    episodes: List<AnimeEpisodeItem>,
    history: Map<Int, HistoryItem>,
    episodeGrid: Boolean,
    selectedTab: Int,
    episodeCount: Int,
    onTabSelect: (Int) -> Unit,
    onToggleOrder: () -> Unit,
    onToggleLayout: () -> Unit,
    onEpisodeClick: (AnimeEpisodeItem) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableIntStateOf(0) }

    val filteredEpisodes = remember(episodes, searchQuery, filterMode, history) {
        episodes.filter { ep ->
            val matchesQuery = searchQuery.isBlank() ||
                ep.title.contains(searchQuery, ignoreCase = true) ||
                ep.subtitle.contains(searchQuery, ignoreCase = true) ||
                ep.id.toString().contains(searchQuery.trim())

            if (!matchesQuery) return@filter false

            if (filterMode == 0) true
            else {
                val hist = history[ep.id]
                val hasProgress = hist != null && hist.duration > 0L
                val percent = if (hasProgress) animeHistoryPercent(hist) else 0
                val isFinished = hasProgress && percent >= 100

                if (filterMode == 1) !isFinished else isFinished
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stickyHeader {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column {
                    DetailTabsAv1(selectedTab, episodeCount, onTabSelect)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
                            modifier = Modifier.weight(1f).height(42.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            "Cari episode (cth: 12)...",
                                            fontSize = 13.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Hapus pencarian",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp).clickable { searchQuery = "" }
                                    )
                                }
                            }
                        }

                        EpisodeIconButtonAv1(
                            icon = Icons.Default.SwapVert,
                            onClick = onToggleOrder,
                            tooltip = "Urutan Episode"
                        )
                        EpisodeIconButtonAv1(
                            icon = if (episodeGrid) Icons.Default.ViewList else Icons.Default.GridView,
                            onClick = onToggleLayout,
                            tooltip = if (episodeGrid) "Grid" else "List"
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("Semua", "Belum Ditonton", "Selesai").forEachIndexed { mode, title ->
                            val active = filterMode == mode
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent),
                                modifier = Modifier.clickable { filterMode = mode }
                            ) {
                                Text(
                                    text = title,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        if (filteredEpisodes.isEmpty()) {
            item {
                EmptyStateAv1(
                    if (searchQuery.isNotBlank()) "Episode \"$searchQuery\" tidak ditemukan" else "Belum ada episode yang sesuai",
                    Modifier.fillMaxWidth()
                )
            }
        } else if (episodeGrid) {
            items(filteredEpisodes.chunked(2)) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowItems.forEach { episode ->
                        EpisodeRowAv1(episode, history[episode.id], Modifier.weight(1f), isGrid = true) { onEpisodeClick(episode) }
                    }
                    repeat(2 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(filteredEpisodes, key = { it.id.toString() + it.url }) { episode ->
                EpisodeRowAv1(episode, history[episode.id], Modifier.padding(horizontal = 14.dp), isGrid = false) { onEpisodeClick(episode) }
            }
        }
    }
}

@Composable
private fun SimilarTabAv1(
    selectedTab: Int,
    episodeCount: Int,
    onTabSelect: (Int) -> Unit,
    similar: List<AnimePost>,
    loadingMore: Boolean,
    hasMore: Boolean,
    hasGenre: Boolean,
    onAnimeClick: (AnimePost) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = gridState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(3) }) {
            Surface(color = MaterialTheme.colorScheme.background) {
                DetailTabsAv1(selectedTab, episodeCount, onTabSelect)
            }
        }
        item(span = { GridItemSpan(3) }) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Anime Rekomendasi Serupa",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Genre terkait",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(similar, key = { AnimeHomeV2Labels.itemKey(it) }) { post ->
            SimilarCardAv1(post, onAnimeClick)
        }
        if (hasGenre && (loadingMore || (similar.isEmpty() && hasMore))) {
            items(3) { MangaRelatedSkeletonCard(rememberMangaShimmerProgress()) }
        }
        if (hasGenre && hasMore) {
            item(span = { GridItemSpan(3) }) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (similar.isEmpty()) "Menyiapkan data..." else "Memuat lebih banyak...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (similar.isEmpty()) {
            item(span = { GridItemSpan(3) }) {
                EmptyStateAv1("Belum ada anime serupa", Modifier.fillMaxWidth())
            }
        } else {
            item(span = { GridItemSpan(3) }) {
                Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Semua rekomendasi sudah dimuat",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailBannerAv1(
    detail: AnimeDetailData,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val post = detail.post
    val titleText = post.categoryName.orEmpty()
    val backdrop = MaterialTheme.colorScheme.background
    val statusText = detail.status.ifBlank { post.statusVideo.orEmpty().ifBlank { "-" } }
    val statusColor = animeStatusTextColorAv1(statusText)
    val ratingText = post.rating.orEmpty()
    val yearText = if (post.year > 0) post.year.toString() else ""

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(268.dp)
            .clip(RoundedCornerShape(26.dp))
    ) {
        AnimeNetworkImageAv1(
            url = post.imgUrl.orEmpty(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize().blur(22.dp)
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Black.copy(alpha = 0.60f),
                        backdrop.copy(alpha = 0.98f)
                    )
                )
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BannerIconButtonAv1(Icons.Default.ArrowBack, Color.White, onBack)
            Spacer(Modifier.weight(1f))
            BannerIconButtonAv1(Icons.Default.Share, Color.White, onShare)
            BannerIconButtonAv1(
                if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (isFavorite) MaterialTheme.colorScheme.primary else Color.White,
                onFavorite
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.28f)),
                shadowElevation = 8.dp
            ) {
                AnimeNetworkImageAv1(
                    url = post.imgUrl.orEmpty(),
                    contentDescription = titleText,
                    modifier = Modifier
                        .width(108.dp)
                        .height(156.dp)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = titleText,
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = titleText.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(titleText))
                        Toast.makeText(context, "Judul disalin", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    InfoPillAv1(AnimeSettingsManager.labelForSourceId(post.sourceId), highlighted = true)
                    if (hasValueAv1(ratingText)) {
                        InfoPillAv1("\u2605 $ratingText", scorePill = true)
                    }
                    if (yearText.isNotBlank()) {
                        InfoPillAv1(yearText)
                    }
                    if (hasValueAv1(statusText)) {
                        InfoPillAv1(statusText, dotColor = statusColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeDetailSkeletonAv1(modifier: Modifier, selectedTab: Int, onBack: () -> Unit, episodeGrid: Boolean) {
    val progress = rememberMangaShimmerProgress()
    val base = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .clipToBounds()
    when (selectedTab) {
        0 -> Column(base) {
            SkeletonBannerAv1(progress, onBack)
            SkeletonTabsAv1(selectedTab, progress)
            Column(Modifier.padding(top = 14.dp)) {
                SkeletonInfoCardAv1(96.dp, progress) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(5) { MangaShimmerBlock(Modifier.fillMaxWidth(if (it == 4) 0.6f else 1f).height(14.dp), progress) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SkeletonInfoCardAv1(150.dp, progress) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        SkeletonInfoRowAv1(0.45f, progress)
                        SkeletonInfoRowAv1(0.8f, progress)
                        SkeletonInfoRowAv1(0.3f, progress)
                        SkeletonInfoRowAv1(0.7f, progress)
                    }
                }
            }
            Column(Modifier.padding(top = 16.dp)) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) {
                    MangaShimmerBlock(Modifier.width(80.dp).height(20.dp), progress, 6.dp)
                }
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(76, 92, 112, 84).forEach { w ->
                        MangaShimmerBlock(Modifier.width(w.dp).height(34.dp), progress, 10.dp)
                    }
                }
            }
        }
        1 -> Column(base, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column {
                SkeletonTabsAv1(selectedTab, progress)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MangaShimmerBlock(Modifier.width(160.dp).height(22.dp), progress, 6.dp)
                    Spacer(Modifier.weight(1f))
                    MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    MangaShimmerBlock(Modifier.size(38.dp), progress, 12.dp)
                }
            }
            if (episodeGrid) {
                repeat(7) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SkeletonEpisodeRowAv1(progress, Modifier.weight(1f), isGrid = true)
                        SkeletonEpisodeRowAv1(progress, Modifier.weight(1f), isGrid = true)
                    }
                }
            } else {
                repeat(8) { SkeletonEpisodeRowAv1(progress, Modifier.padding(horizontal = 14.dp), isGrid = false) }
            }
        }
        else -> Column(base) {
            SkeletonTabsAv1(selectedTab, progress)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MangaShimmerBlock(Modifier.width(140.dp).height(20.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.width(110.dp).height(12.dp), progress, 6.dp)
            }
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(3) { Box(Modifier.weight(1f)) { MangaRelatedSkeletonCard(progress) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimilarCardAv1(post: AnimePost, onClick: (AnimePost) -> Unit) {
    val score = post.rating.orEmpty()
    val epCount = post.episodeCount.orEmpty()
    val badgeText = when {
        score.isNotBlank() -> "\u2605 $score"
        epCount.isNotBlank() -> epCount
        else -> ""
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick(post) }
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.68f)) {
                AnimeNetworkImageAv1(
                    url = post.imgUrl.orEmpty(),
                    contentDescription = post.categoryName,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                )
                if (badgeText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 8.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        Text(
                            badgeText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (score.isNotBlank()) Color(0xFFFFD54F) else Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            maxLines = 1
                        )
                    }
                }
            }
            Text(
                post.categoryName.orEmpty().ifBlank { "-" },
                fontSize = 12.sp,
                lineHeight = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun EpisodeRowAv1(
    episode: AnimeEpisodeItem,
    history: HistoryItem?,
    modifier: Modifier = Modifier,
    isGrid: Boolean = false,
    onClick: () -> Unit
) {
    val percent = remember(history?.position, history?.duration) { animeHistoryPercent(history) }
    val fraction = remember(history?.position, history?.duration) { animeHistoryFraction(history) }
    val hasProgress = history != null && history.duration > 0L
    val watchedPosition = if (hasProgress) history!!.position.coerceIn(0L, history.duration) else 0L
    val isFinished = hasProgress && percent >= 100

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isFinished) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f)
                else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            1.dp,
            if (isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
    ) {
        if (isGrid) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        episode.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isFinished) {
                        Text(
                            "\u2713",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else if (hasProgress) {
                        Text(
                            "$percent%",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (episode.subtitle.isNotBlank() || hasProgress) {
                    Text(
                        if (hasProgress) formatAnimeProgressTime(watchedPosition) + " / " + formatAnimeProgressTime(history!!.duration)
                        else episode.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (hasProgress) {
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isFinished) {
                                Text(
                                    "\u2713",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                episode.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (hasProgress) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isFinished) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.padding(start = 6.dp)
                                ) {
                                    Text(
                                        if (isFinished) "\u2713 Selesai" else "$percent%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        if (episode.subtitle.isNotBlank() || hasProgress) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (episode.subtitle.isNotBlank()) {
                                    Text(
                                        episode.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                                if (hasProgress) {
                                    Text(
                                        formatAnimeProgressTime(watchedPosition) + " / " + formatAnimeProgressTime(history!!.duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                if (hasProgress) {
                    LinearProgressIndicator(
                        progress = fraction,
                        modifier = Modifier.fillMaxWidth().height(3.5.dp).clip(RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyStateAv1(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnimeNetworkImageAv1(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(if (url.startsWith("//")) "https:$url" else url)
            .crossfade(true)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Crop,
    ) {
        when (painter.state) {
            is coil.compose.AsyncImagePainter.State.Loading -> MangaShimmerBlock(Modifier.fillMaxSize(), rememberMangaShimmerProgress(), 0.dp)
            is coil.compose.AsyncImagePainter.State.Error -> MangaBrokenImage(Modifier.fillMaxSize())
            else -> SubcomposeAsyncImageContent()
        }
    }
}

private fun isSameAnimeAv1(candidate: AnimePost, current: AnimePost): Boolean {
    val slug = candidate.slug.orEmpty().trim().trim('/')
    val currentSlug = current.slug.orEmpty().trim().trim('/')
    if (slug.isNotEmpty() && currentSlug.isNotEmpty()) return slug.equals(currentSlug, true)
    if (candidate.categoryId > 0 && current.categoryId > 0) return candidate.categoryId == current.categoryId
    return candidate.categoryName.orEmpty().trim().equals(current.categoryName.orEmpty().trim(), true)
}

private fun animeStatusTextColorAv1(status: String): Color = when {
    status.contains("ongoing", true) || status.contains("berjalan", true) || status.contains("publishing", true) -> Color(0xFF66BB6A)
    status.contains("complete", true) || status.contains("tamat", true) || status.contains("finished", true) || status.equals("end", true) -> Color(0xFF64B5F6)
    status.contains("hiatus", true) || status.contains("jeda", true) -> Color(0xFFFFB74D)
    status.contains("cancel", true) || status.contains("drop", true) || status.contains("batal", true) -> Color(0xFFEF5350)
    status.contains("upcoming", true) || status.contains("segera", true) -> Color(0xFFBA68C8)
    else -> Color(0xFF90A4AE)
}

@Composable
private fun BannerIconButtonAv1(icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.40f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun InfoPillAv1(
    text: String,
    highlighted: Boolean = false,
    scorePill: Boolean = false,
    dotColor: Color? = null
) {
    if (text.isBlank()) return
    val bgColor = when {
        highlighted -> MaterialTheme.colorScheme.primaryContainer
        scorePill -> Color(0xFFFFB300).copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.16f)
    }
    val textColor = when {
        highlighted -> MaterialTheme.colorScheme.onPrimaryContainer
        scorePill -> Color(0xFFFFD54F)
        else -> Color.White
    }
    val borderColor = when {
        highlighted -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        scorePill -> Color(0xFFFFB300).copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.22f)
    }
    Surface(
        shape = RoundedCornerShape(99.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (dotColor != null) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(dotColor))
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DetailTabsAv1(selectedTab: Int, chapterCount: Int, onSelect: (Int) -> Unit) {
    DetailTabsRowAv1(selectedTab, listOf("Info", "Episode", "Serupa"), onSelect)
}

@Composable
private fun DetailTabsRowAv1(selectedTab: Int, titles: List<String>, onSelect: (Int) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                    val gap = 4.dp
                    val count = titles.size.coerceAtLeast(1)
                    val tabWidth = (maxWidth - gap * (count - 1)) / count
                    val indicatorX by animateDpAsState(
                        targetValue = (tabWidth + gap) * selectedTab.coerceIn(0, count - 1),
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow),
                        label = "detailTabIndicator"
                    )
                    Box(
                        modifier = Modifier
                            .offset(x = indicatorX)
                            .width(tabWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    titles.forEachIndexed { index, title ->
                        val selected = index == selectedTab
                        val textColor by animateColorAsState(
                            targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            label = "detailTabText"
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onSelect(index) }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                title,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonTabsAv1(selectedTab: Int, progress: State<Float>) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth().padding(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0.9f, 1f, 0.9f).forEachIndexed { index, _ ->
                    val selected = index == selectedTab.coerceIn(0, 2)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MangaShimmerBlock(Modifier.width(60.dp).height(14.dp), progress, 6.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SkeletonBannerAv1(progress: State<Float>, onBack: () -> Unit) {
    val backdrop = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(268.dp)
            .clip(RoundedCornerShape(26.dp))
    ) {
        MangaShimmerBlock(Modifier.fillMaxSize(), progress, 0.dp)
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.45f),
                        Color.Black.copy(alpha = 0.60f),
                        backdrop.copy(alpha = 0.98f)
                    )
                )
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BannerIconButtonAv1(Icons.Default.ArrowBack, Color.White, onBack)
            Spacer(Modifier.weight(1f))
            MangaShimmerBlock(Modifier.size(38.dp), progress, 19.dp, onHero = true)
            MangaShimmerBlock(Modifier.size(38.dp), progress, 19.dp, onHero = true)
        }
        Row(
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            MangaShimmerBlock(
                Modifier
                    .width(108.dp)
                    .height(156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.5.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(16.dp)),
                progress,
                16.dp
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.92f).height(18.dp), progress, onHero = true)
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.62f).height(18.dp), progress, onHero = true)
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MangaShimmerBlock(Modifier.width(72.dp).height(24.dp), progress, 99.dp, onHero = true)
                    MangaShimmerBlock(Modifier.width(58.dp).height(24.dp), progress, 99.dp, onHero = true)
                    MangaShimmerBlock(Modifier.width(70.dp).height(24.dp), progress, 99.dp, onHero = true)
                }
            }
        }
    }
}

@Composable
private fun SkeletonInfoCardAv1(titleWidth: Dp, progress: State<Float>, content: @Composable () -> Unit) {
    MangaShimmerBlock(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        progress,
        18.dp,
        baseColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MangaShimmerBlock(Modifier.width(titleWidth).height(20.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.size(24.dp), progress, 12.dp)
            }
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SkeletonInfoRowAv1(shimmerWidth: Float, progress: State<Float>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(0.42f)) {
            MangaShimmerBlock(Modifier.width(64.dp).height(14.dp), progress, 6.dp)
        }
        Box(Modifier.weight(0.58f)) {
            MangaShimmerBlock(Modifier.fillMaxWidth(shimmerWidth).height(14.dp), progress)
        }
    }
}

@Composable
private fun SkeletonEpisodeRowAv1(progress: State<Float>, modifier: Modifier = Modifier, isGrid: Boolean = false) {
    MangaShimmerBlock(
        modifier = modifier.fillMaxWidth(),
        progress = progress,
        radius = 14.dp,
        baseColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    ) {
        if (isGrid) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MangaShimmerBlock(Modifier.fillMaxWidth(0.85f).height(14.dp), progress, 4.dp)
                MangaShimmerBlock(Modifier.fillMaxWidth(0.5f).height(10.dp), progress, 3.dp)
                MangaShimmerBlock(Modifier.fillMaxWidth().height(4.dp), progress, 2.dp)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MangaShimmerBlock(Modifier.size(34.dp), progress, 10.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.75f).height(14.dp), progress, 4.dp)
                    MangaShimmerBlock(Modifier.fillMaxWidth(0.45f).height(10.dp), progress, 3.dp)
                    MangaShimmerBlock(Modifier.fillMaxWidth().height(3.5.dp), progress, 2.dp)
                }
            }
        }
    }
}

@Composable
private fun InfoCardAv1(title: String, content: @Composable (Boolean) -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            content(expanded)
        }
    }
}

@Composable
private fun InfoRowAv1(label: String, value: String) {
    val valueColor = if (label.contains("status", true)) animeStatusTextColorAv1(value) else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.40f))
        Text(value.ifBlank { "-" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.60f), color = valueColor)
    }
}

@Composable
private fun GenreChipAv1(label: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DetailTopBarAv1(
    title: String,
    isFavorite: Boolean,
    loading: Boolean,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onFavorite: () -> Unit
) {
    val progress = rememberMangaShimmerProgress()
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EpisodeIconButtonAv1(Icons.Default.ArrowBack, onBack, 36.dp)
            Spacer(Modifier.width(8.dp))
            if (loading) {
                MangaShimmerBlock(Modifier.width(180.dp).height(18.dp), progress, 6.dp)
                Spacer(Modifier.weight(1f))
                MangaShimmerBlock(Modifier.size(36.dp), progress, 12.dp)
                Spacer(Modifier.width(6.dp))
                MangaShimmerBlock(Modifier.size(36.dp), progress, 12.dp)
            } else {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                EpisodeIconButtonAv1(Icons.Default.Share, onShare, 36.dp)
                Spacer(Modifier.width(4.dp))
                EpisodeIconButtonAv1(
                    if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    onFavorite,
                    size = 36.dp,
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun EpisodeIconButtonAv1(
    icon: ImageVector,
    onClick: () -> Unit,
    size: Dp = 38.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    tooltip: String? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)),
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = tooltip, tint = tint, modifier = Modifier.size(19.dp))
        }
    }
}
