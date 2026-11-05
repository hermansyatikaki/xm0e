@file:OptIn(ExperimentalLayoutApi::class)

package miku.moe.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

class AnimeDetailStyleV2Fragment : Fragment() {
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
        fun newInstance(post: AnimePost): AnimeDetailStyleV2Fragment {
            return AnimeDetailStyleV2Fragment().apply {
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
        applyStyleV2SystemBars()
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
            MikuAnimeDetailStyleV2Theme {
                AnimeDetailStyleV2Screen(
                    initial = initialPost,
                    historyVersion = historyRefreshVersion,
                    refreshKey = refreshKey,
                    onRefreshFinished = { swipeRefreshLayout.isRefreshing = false },
                    onCanRefreshChange = { swipeRefreshLayout.isEnabled = it },
                    onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    onAnimeClick = { openAnimeDetail(it) },
                    onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) },
                    onAniListSearch = { sourceId, sourceLabel, q -> (activity as? MainActivity)?.openAnimeBrowseSource(sourceId, sourceLabel, q) }
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
        applyStyleV2SystemBars()
    }

    override fun onStop() {
        defaultHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        animekuHistoryPreferences?.unregisterOnSharedPreferenceChangeListener(animeHistoryChangeListener)
        defaultHistoryPreferences = null
        animekuHistoryPreferences = null
        super.onStop()
    }

    override fun onPause() {
        restoreStyleV2SystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreStyleV2SystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            restoreStyleV2SystemBars()
        } else {
            historyRefreshVersion++
            view?.postDelayed({ if (isAdded && !isHidden) historyRefreshVersion++ }, 250L)
            applyStyleV2SystemBars()
        }
    }

    private fun applyStyleV2SystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreStyleV2SystemBars() {
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
private fun MikuAnimeDetailStyleV2Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun themeColor(attr: Int, fallback: Int): Color = Color(MaterialColors.getColor(context, attr, fallback))
    val colors = darkColorScheme(
        primary = themeColor(androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt()),
        onPrimary = themeColor(com.google.android.material.R.attr.colorOnPrimary, 0xFFFFFFFF.toInt()),
        primaryContainer = themeColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFEADDFF.toInt()),
        onPrimaryContainer = themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D.toInt()),
        secondary = themeColor(com.google.android.material.R.attr.colorSecondary, 0xFF625B71.toInt()),
        onSecondary = themeColor(com.google.android.material.R.attr.colorOnSecondary, 0xFFFFFFFF.toInt()),
        secondaryContainer = themeColor(com.google.android.material.R.attr.colorSecondaryContainer, 0xFFE8DEF8.toInt()),
        onSecondaryContainer = themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFF1D192B.toInt()),
        background = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surface = themeColor(com.google.android.material.R.attr.colorSurface, 0xFF1C1B20.toInt()),
        surfaceVariant = themeColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFF49454F.toInt()),
        surfaceContainer = themeColor(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF262429.toInt()),
        surfaceContainerHigh = themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF2E2B31.toInt()),
        onSurface = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFFF2EEF5.toInt()),
        onSurfaceVariant = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFCAC4D0.toInt()),
        outline = themeColor(com.google.android.material.R.attr.colorOutline, 0xFF938F99.toInt()),
        outlineVariant = themeColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFF49454F.toInt())
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp)
    )
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun AnimeDetailStyleV2Screen(
    initial: AnimePost?,
    historyVersion: Int,
    refreshKey: Int,
    onRefreshFinished: () -> Unit,
    onCanRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAnimeClick: (AnimePost) -> Unit,
    onGenreClick: (String, String, String, String) -> Unit,
    onAniListSearch: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val similarState = rememberLazyListState()
    var detail by remember(initial) { mutableStateOf(initialAnimeDetail(initial)) }
    var loading by remember(initial) { mutableStateOf(true) }
    var isFavorite by remember(initial) { mutableStateOf(initial != null && FavoriteManager.isFavorite(context, initial.sourceId, initial.categoryId, initial.slug)) }
    var episodeDescending by remember(initial) { mutableStateOf(readAnimeEpisodeNewestFirst(context, initial)) }
    var episodeGrid by remember(initial) { mutableStateOf(readAnimeEpisodeGridMode(context, initial)) }
    var synopsisExpanded by remember(initial) { mutableStateOf(false) }
    var synopsisCanExpand by remember(initial) { mutableStateOf(false) }
    var playbackLoading by remember { mutableStateOf(false) }
    var pendingPlayback by remember { mutableStateOf<Pair<AnimeEpisodeItem, List<AnimeQualityOption>>?>(null) }
    var aniListInfo by remember(initial) { mutableStateOf<AniListClient.AniListInfo?>(null) }

    val aniListEnabled = remember { AnimeSettingsManager.isShowAniListEnabled(context) }
    LaunchedEffect(detail.post.categoryName, refreshKey, aniListEnabled) {
        if (!aniListEnabled) return@LaunchedEffect
        val aniListTitle = detail.post.categoryName?.trim().orEmpty()
        if (aniListTitle.isNotEmpty()) {
            aniListInfo = try { AniListClient.fetch(aniListTitle, manga = false) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
        }
    }

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
    val similarController = remember(loading, detail.post.sourceId, detail.genres, detail.post.categoryName) {
        if (loading) null else AnimeSimilarSource(context.applicationContext, detail.post, detail.genres).takeUnless { it.isEmptyBasis }
    }
    DisposableEffect(similarController) {
        onDispose { similarController?.destroy() }
    }
    LaunchedEffect(similarController) {
        similarController?.start()
    }
    val similarItems = similarController?.merged { isSameAnimeStyleV2(it, detail.post) } ?: emptyList()
    val similarLoading = similarController?.loading ?: false

    LaunchedEffect(similarState, similarController) {
        snapshotFlow {
            val info = similarState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            if (total > 0 && lastIndex >= total - 3) similarController?.loadNextPage()
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { onCanRefreshChange(it) }
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

    val sourceLabel = AnimeSettingsManager.labelForSourceId(detail.post.sourceId)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            val start = startEpisode
            if (!loading && start != null && detail.episodes.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { playEpisode(start) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = {
                        Text(
                            if (episodeHistory.isNotEmpty()) "Lanjut " + start.title else "Mulai Menonton",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            val genreLabels = detail.genres.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 110.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Kembali", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val title = detail.post.categoryName.orEmpty()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, if (title.isBlank()) sourceLabel else "$title \u2014 $sourceLabel")
                            }
                            context.startActivity(Intent.createChooser(intent, null))
                        }) {
                            Icon(Icons.Filled.Share, contentDescription = "Bagikan", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = {
                            FavoriteManager.toggle(context, detail.post)
                            isFavorite = FavoriteManager.isFavorite(context, detail.post.sourceId, detail.post.categoryId, detail.post.slug)
                        }) {
                            Icon(
                                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                contentDescription = "Favorit",
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                item {
                    HeaderBlockStyleV2(detail = detail, sourceLabel = sourceLabel)
                }
                if (detail.description.isNotBlank()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                            SectionLabelStyleV2("SINOPSIS")
                            Spacer(Modifier.height(8.dp))
                            SelectionContainer {
                                Text(
                                    detail.description.trim(),
                                    fontSize = 14.5.sp,
                                    lineHeight = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f),
                                    maxLines = if (synopsisExpanded) Int.MAX_VALUE else 5,
                                    overflow = TextOverflow.Ellipsis,
                                    onTextLayout = { if (!synopsisExpanded) synopsisCanExpand = it.hasVisualOverflow }
                                )
                            }
                            if (synopsisCanExpand || synopsisExpanded) {
                                Text(
                                    if (synopsisExpanded) "Tutup" else "Baca selengkapnya",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().clickable { synopsisExpanded = !synopsisExpanded }.padding(vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
                if (genreLabels.isNotEmpty()) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            SectionLabelStyleV2("GENRE")
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                genreLabels.forEach { label ->
                                    Box(
                                        Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainer)
                                            .clickable { onGenreClick(detail.post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT, sourceLabel, label, label) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
                if (similarItems.isNotEmpty() || similarLoading) {
                    item {
                        Column(Modifier.padding(vertical = 14.dp)) {
                            Box(Modifier.padding(horizontal = 20.dp)) { SectionLabelStyleV2("ANIME SERUPA") }
                            Spacer(Modifier.height(10.dp))
                            LazyRow(
                                state = similarState,
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(similarItems) { entry ->
                                    SimilarItemStyleV2(post = entry, onClick = { onAnimeClick(entry) })
                                }
                                if (similarLoading) {
                                    item {
                                        Box(Modifier.width(48.dp).height(136.dp), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(26.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (aniListEnabled && aniListInfo != null) {
                    item {
                        AniListSection(
                            info = aniListInfo!!,
                            horizontalPadding = 20.dp,
                            header = { SectionLabelStyleV2("ANILIST") },
                            onRecommendationClick = { recTitle ->
                                val src = detail.post.sourceId ?: AnimeSettingsManager.SOURCE_DEFAULT
                                onAniListSearch(src, AnimeSettingsManager.labelForSourceId(src), recTitle)
                            }
                        )
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionLabelStyleV2("EPISODE")
                        Spacer(Modifier.width(10.dp))
                        Text(detail.episodes.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Row(
                            Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                episodeDescending = !episodeDescending
                                saveAnimeEpisodeNewestFirst(context, detail.post, episodeDescending)
                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (episodeDescending) "Terbaru" else "Terlama", color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = {
                            episodeGrid = !episodeGrid
                            saveAnimeEpisodeGridMode(context, detail.post, episodeGrid)
                        }) {
                            Icon(
                                if (episodeGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = if (episodeGrid) "Tampilan list" else "Tampilan grid 2",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (loading && detail.episodes.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else if (detail.episodes.isEmpty()) {
                    item {
                        Text(
                            detail.error.ifBlank { "Episode belum tersedia" },
                            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                } else if (episodeGrid) {
                    items(shownEpisodes.chunked(2)) { pair ->
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EpisodeTileStyleV2(
                                episode = pair[0],
                                history = episodeHistory[pair[0].id],
                                modifier = Modifier.weight(1f),
                                onClick = { playEpisode(pair[0]) }
                            )
                            if (pair.size > 1) {
                                EpisodeTileStyleV2(
                                    episode = pair[1],
                                    history = episodeHistory[pair[1].id],
                                    modifier = Modifier.weight(1f),
                                    onClick = { playEpisode(pair[1]) }
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    items(shownEpisodes.distinctBy { it.id.toString() + "|" + it.url }, key = { it.id.toString() + "|" + it.url }) { episode ->
                        EpisodeLineStyleV2(
                            episode = episode,
                            history = episodeHistory[episode.id],
                            onClick = { playEpisode(episode) }
                        )
                    }
                }
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

@Composable
private fun SectionLabelStyleV2(text: String) {
    Text(text, fontSize = 11.5.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun statusColorStyleV2(status: String, fallback: Color): Color {
    val value = status.trim().lowercase()
    return when {
        value.contains("ongoing") || value.contains("berlangsung") || value.contains("berjalan") || value.contains("airing") -> Color(0xFF66BB6A)
        value.contains("completed") || value.contains("complete") || value.contains("tamat") || value.contains("finished") || value == "end" -> Color(0xFF42A5F5)
        value.contains("hiatus") -> Color(0xFFFFA726)
        value.contains("cancel") || value.contains("drop") -> Color(0xFFEF5350)
        else -> fallback
    }
}

private fun isSameAnimeStyleV2(candidate: AnimePost, current: AnimePost): Boolean {
    val slug = candidate.slug.orEmpty().trim().trim('/')
    val currentSlug = current.slug.orEmpty().trim().trim('/')
    if (slug.isNotEmpty() && currentSlug.isNotEmpty()) return slug.equals(currentSlug, true)
    if (candidate.categoryId > 0 && current.categoryId > 0) return candidate.categoryId == current.categoryId
    return candidate.categoryName.orEmpty().trim().equals(current.categoryName.orEmpty().trim(), true)
}

private fun episodeNumberStyleV2(title: String): String {
    val match = Regex("(\\d+(?:\\.\\d+)?)").findAll(title).lastOrNull() ?: return ""
    return match.groupValues[1]
}

@Composable
private fun HeaderBlockStyleV2(detail: AnimeDetailData, sourceLabel: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val title = detail.post.categoryName.orEmpty()
    val type = detail.rows.firstOrNull { it.first.equals("Type", true) || it.first.equals("Tipe", true) }?.second.orEmpty()
    val studio = detail.rows.firstOrNull { it.first.equals("Studio", true) }?.second.orEmpty()
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Box(Modifier.width(104.dp).height(146.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            AnimeNetworkImageStyleV2(detail.post.imgUrl.orEmpty(), title, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            if (type.isNotBlank()) {
                Text(type.uppercase(), fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                title.ifBlank { "Detail Anime" },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(title))
                    Toast.makeText(context, "Judul anime disalin", Toast.LENGTH_SHORT).show()
                }
            )
            if (studio.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("oleh $studio", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.status.isNotBlank()) {
                    val color = statusColorStyleV2(detail.status, MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(detail.status, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Spacer(Modifier.width(12.dp))
                }
                Text(sourceLabel, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EpisodeLineStyleV2(episode: AnimeEpisodeItem, history: HistoryItem?, onClick: () -> Unit) {
    val percent = animeHistoryPercent(history)
    val fraction = animeHistoryFraction(history)
    val hasProgress = history != null && history.duration > 0L
    val isFinished = hasProgress && percent >= 100
    val number = episodeNumberStyleV2(episode.title)
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp)) {
        Row(Modifier.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(46.dp).height(46.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isFinished) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                if (number.isNotEmpty()) {
                    Text(
                        number,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp,
                        color = if (isFinished) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (isFinished) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    episode.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val info = if (hasProgress) formatAnimeProgressTime(history!!.position.coerceIn(0L, history.duration)) + " / " + formatAnimeProgressTime(history.duration) else episode.subtitle
                if (info.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(info, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            when {
                isFinished -> Text("Selesai", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                percent > 0 -> Text("$percent%", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (hasProgress) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        } else {
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f), thickness = 0.7.dp)
        }
    }
}

@Composable
private fun EpisodeTileStyleV2(episode: AnimeEpisodeItem, history: HistoryItem?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val percent = animeHistoryPercent(history)
    val fraction = animeHistoryFraction(history)
    val hasProgress = history != null && history.duration > 0L
    val isFinished = hasProgress && percent >= 100
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.title,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                when {
                    isFinished -> Text("Selesai", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    percent > 0 -> Text("$percent%", fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            val info = if (hasProgress) formatAnimeProgressTime(history!!.position.coerceIn(0L, history.duration)) + " / " + formatAnimeProgressTime(history.duration) else episode.subtitle
            if (info.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(info, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (hasProgress) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(2.5.dp).clip(RoundedCornerShape(50)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun SimilarItemStyleV2(post: AnimePost, onClick: () -> Unit) {
    Column(Modifier.width(100.dp).clickable { onClick() }) {
        Box(Modifier.width(100.dp).height(136.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            AnimeNetworkImageStyleV2(post.imgUrl.orEmpty(), post.categoryName, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(7.dp))
        Text(post.categoryName.orEmpty().ifBlank { "-" }, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}

@Composable
private fun AnimeNetworkImageStyleV2(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
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
