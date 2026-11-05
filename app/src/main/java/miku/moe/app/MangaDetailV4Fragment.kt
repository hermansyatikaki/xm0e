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
import androidx.compose.foundation.lazy.items as columnItems
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

class MangaDetailV4Fragment : Fragment() {
    private var initialManga: MangaPost? = null
    private var systemBarsApplied = false

    companion object {
        @JvmStatic
        fun newInstance(manga: MangaPost): MangaDetailV4Fragment {
            val fragment = MangaDetailV4Fragment()
            val args = Bundle()
            args.putSerializable("manga", manga)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialManga = arguments?.getSerializable("manga") as? MangaPost
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        applyV4SystemBars()
        val swipeRefreshLayout = SwipeRefreshLayout(requireContext()).apply {
            setColorSchemeColors(MaterialColors.getColor(requireContext(), androidx.appcompat.R.attr.colorPrimary, android.graphics.Color.rgb(103, 80, 164)))
            setProgressBackgroundColorSchemeColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorSurface, android.graphics.Color.rgb(28, 27, 32)))
        }
        val composeView = ComposeView(requireContext())
        swipeRefreshLayout.addView(composeView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        composeView.setContent {
            var refreshKey by remember { mutableIntStateOf(0) }
            DisposableEffect(swipeRefreshLayout) {
                swipeRefreshLayout.setOnRefreshListener {
                    initialManga?.getSourceId()?.let { MangaSourceFactory.invalidateSourceCaches(it) }
                    refreshKey++
                }
                onDispose { swipeRefreshLayout.setOnRefreshListener { } }
            }
            MikuMangaDetailV4Theme {
                MangaDetailV4Screen(
                    initial = initialManga,
                    refreshKey = refreshKey,
                    onRefreshFinished = { swipeRefreshLayout.isRefreshing = false },
                    onCanRefreshChange = { swipeRefreshLayout.isEnabled = it },
                    onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
                    onChapterClick = { manga, chapter, chapters -> openChapter(manga, chapter, chapters) },
                    onMangaClick = { openMangaDetail(it) },
                    onGenreClick = { sourceId, sourceLabel, title, value -> openGenre(sourceId, sourceLabel, title, value) },
                    onAniListSearch = { sourceId, sourceLabel, q -> (activity as? MainActivity)?.openMangaBrowseSource(sourceId, sourceLabel, q) }
                )
            }
        }
        return swipeRefreshLayout
    }

    override fun onResume() {
        super.onResume()
        applyV4SystemBars()
    }

    override fun onPause() {
        restoreV4SystemBars()
        super.onPause()
    }

    override fun onDestroyView() {
        restoreV4SystemBars()
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) restoreV4SystemBars() else applyV4SystemBars()
    }

    private fun applyV4SystemBars() {
        val host = activity ?: return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = true
    }

    private fun restoreV4SystemBars() {
        val host = activity ?: return
        if (!systemBarsApplied) return
        ThemeManager.applySystemBars(host)
        systemBarsApplied = false
    }

    private fun openChapter(manga: MangaPost, chapter: MangaChapter, chapters: List<MangaChapter>) {
        val list = ArrayList(chapters)
        val position = list.indexOfFirst { kotlin.math.abs(it.index - chapter.index) < 0.001f }.coerceAtLeast(0)
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaReader(manga, list, position)
            is MikuAll -> activity.openMangaReader(manga, list, position)
        }
    }

    private fun openMangaDetail(manga: MangaPost) {
        val activity = requireActivity()
        when (activity) {
            is MainActivity -> activity.openMangaDetail(manga)
            is MikuAll -> activity.openMangaDetail(manga)
        }
    }

    private fun openGenre(sourceId: String, sourceLabel: String, title: String, value: String) {
        when (val activity = activity) {
            is MainActivity -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
            is MikuAll -> activity.openMangaGenreResult(sourceId, sourceLabel, title, value)
        }
    }
}

@Composable
private fun MikuMangaDetailV4Theme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    fun themeColor(attr: Int, fallback: Int): Color = Color(MaterialColors.getColor(context, attr, fallback))
    val colors = remember(context) {
        darkColorScheme(
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
    }
    val shapes = remember {
        Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
    }
    MaterialTheme(colorScheme = colors, shapes = shapes, content = content)
}

@Composable
private fun MangaDetailV4Screen(
    initial: MangaPost?,
    refreshKey: Int,
    onRefreshFinished: () -> Unit,
    onCanRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onChapterClick: (MangaPost, MangaChapter, List<MangaChapter>) -> Unit,
    onMangaClick: (MangaPost) -> Unit,
    onGenreClick: (String, String, String, String) -> Unit,
    onAniListSearch: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val detailViewModel = remember(context) { ViewModelProvider(context as FragmentActivity).get(MangaDetailViewModel::class.java) }
    val prefs = remember { context.getSharedPreferences("miku_detail_chapter_prefs", 0) }
    val lifecycleOwner = context as? LifecycleOwner
    val listState = rememberLazyListState()
    val similarState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var manga by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial) }
    var aniListInfo by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<AniListClient.AniListInfo?>(null) }
    var chapters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaChapter>>(emptyList()) }
    var genreItems by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<KomikcastClient.GenreItem>>(emptyList()) }
    var similarItems by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<MangaPost>>(emptyList()) }
    var similarPage by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }
    var similarEndReached by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var similarLoadingMore by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var similarFilters by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<List<String>?>(null) }
    val similarSeen = remember(initial?.slug, initial?.getSourceId()) { HashSet<String>() }
    var loading by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(true) }
    var errorText by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf<String?>(null) }
    var isFavorite by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(initial?.let { MangaFavoriteManager.isFavorite(context, it) } ?: false) }
    var chapterDescending by remember { mutableStateOf(prefs.getBoolean("global_chapter_order_newest_first", true)) }
    var chapterGrid by remember { mutableStateOf(MangaSettingsManager.isChapterGrid2(context)) }
    var historyVersion by remember(initial?.slug, initial?.getSourceId()) { mutableIntStateOf(0) }

    LaunchedEffect(manga?.slug, manga?.getSourceId(), chapters, historyVersion) {
        manga?.let { MangaProgressCache.refresh(context, it) }
    }
    var synopsisExpanded by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var synopsisCanExpand by remember(initial?.slug, initial?.getSourceId()) { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    DisposableEffect(initial?.slug, initial?.getSourceId(), lifecycleOwner) {
        val historyPrefs = context.getSharedPreferences("miku_manga_history", 0)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "items" || key == "chapter_progress") historyVersion++
        }
        val roomListener = MangaRoomEvents.Listener { historyVersion++ }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) historyVersion++
        }
        historyPrefs.registerOnSharedPreferenceChangeListener(listener)
        MangaRoomEvents.addListener(roomListener)
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            historyPrefs.unregisterOnSharedPreferenceChangeListener(listener)
            MangaRoomEvents.removeListener(roomListener)
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { onCanRefreshChange(it) }
    }

    fun requestSimilarNextPage() {
        val active = manga ?: return
        if (similarLoadingMore || similarEndReached) return
        val filters = similarFilters ?: return
        if (filters.isEmpty()) {
            similarEndReached = true
            return
        }
        similarLoadingMore = true
        scope.launch {
            try {
                val nextPage = similarPage + 1
                val result = fetchSimilarPageV4(context, active, filters, nextPage, similarSeen)
                similarItems = similarItems + result.first
                similarPage = nextPage
                if (!result.second || nextPage >= 12 || result.first.isEmpty()) similarEndReached = true
            } catch (e: Throwable) {
                similarEndReached = true
            } finally {
                similarLoadingMore = false
            }
        }
    }

    val aniListEnabled = remember { MangaSettingsManager.isShowAniListEnabled(context) }
    LaunchedEffect(manga?.title, refreshKey, aniListEnabled) {
        if (!aniListEnabled) return@LaunchedEffect
        val aniListTitle = manga?.title?.trim().orEmpty()
        if (aniListTitle.isNotEmpty()) {
            aniListInfo = try { AniListClient.fetch(aniListTitle, manga = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
        }
    }

    LaunchedEffect(similarState, similarItems.size) {
        snapshotFlow {
            val info = similarState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (lastIndex, total) ->
            if (total > 0 && lastIndex >= total - 3) requestSimilarNextPage()
        }
    }

    LaunchedEffect(initial?.slug, initial?.getSourceId(), refreshKey, reloadKey) {
        val base = initial
        if (base == null) {
            loading = false
            errorText = "Detail manga gagal dimuat"
            onRefreshFinished()
            return@LaunchedEffect
        }
        loading = true
        errorText = null
        similarItems = emptyList()
        similarPage = 0
        similarEndReached = false
        similarLoadingMore = false
        similarSeen.clear()
        similarFilters = null
        try {
            val core = detailViewModel.loadCoreDetailData(base).awaitFuture()
            val detail = core.detail ?: base
            detail.totalChapters = maxOf(detail.totalChapters, core.chapters.size)
            manga = detail
            chapters = core.chapters
            isFavorite = MangaFavoriteManager.isFavorite(context, detail)
            historyVersion++
            loading = false
            onRefreshFinished()
            try {
                val extras = detailViewModel.loadDetailExtras(detail, false).awaitFuture()
                genreItems = extras.genres
            } catch (ignored: Throwable) {
            }
            val filters = buildSimilarFiltersV4(detail, genreItems)
            similarFilters = filters
            if (filters.isNotEmpty()) {
                similarLoadingMore = true
                try {
                    val first = fetchSimilarPageV4(context, detail, filters, 1, similarSeen)
                    similarItems = first.first
                    similarPage = 1
                    if (!first.second || first.first.isEmpty()) similarEndReached = true
                } catch (ignored: Throwable) {
                    similarEndReached = true
                } finally {
                    similarLoadingMore = false
                }
            } else {
                similarEndReached = true
            }
        } catch (e: Throwable) {
            manga = base
            errorText = e.message ?: "Detail manga gagal dimuat"
            loading = false
            onRefreshFinished()
        }
    }

    val current = manga
    val resumeIndex = remember(current?.slug, current?.getSourceId(), chapters, historyVersion) {
        val active = current ?: return@remember -1f
        var target = MangaHistoryManager.getLastReadChapterIndex(context, active)
        if (target < 0f && MangaHistoryManager.hasHistoryFor(context, active)) target = active.historyChapterIndex
        target
    }
    val resumeChapter = remember(chapters, resumeIndex) {
        if (resumeIndex < 0f) null else chapters.firstOrNull { kotlin.math.abs(it.index - resumeIndex) < 0.001f }
    }
    val firstChapter = remember(chapters) { chapters.minByOrNull { it.index } }
    val readTarget = resumeChapter ?: firstChapter


    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            val active = current
            if (active != null && readTarget != null && !loading) {
                ExtendedFloatingActionButton(
                    onClick = { onChapterClick(active, readTarget, chapters) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    text = {
                        Text(
                            if (resumeChapter != null) "Lanjut Baca Chapter " + MangaChapter.formatIndex(resumeChapter.index)
                            else "Mulai Membaca",
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            when {
                current == null && loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.primary)
                }
                current == null -> {
                    Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(errorText ?: "Detail manga gagal dimuat", color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { reloadKey++ }) { Text("Coba Lagi", fontWeight = FontWeight.Bold) }
                    }
                }
                else -> {
                    val active = current
                    val genreLabels = remember(active.genre) {
                        active.genre.split(",", "|", "/").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    }
                    val displayChapters = remember(chapters, chapterDescending) {
                        val sorted = if (chapterDescending) chapters.sortedByDescending { it.index } else chapters.sortedBy { it.index }
                        sorted.distinctBy { it.slug + "|" + it.index }
                    }
                    val chapterPairs = remember(displayChapters) { displayChapters.chunked(2) }
                    val uniqueSimilarItems = remember(similarItems) { similarItems.distinctBy { it.getSourceId() + "|" + it.slug } }
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
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, active.title + " — " + active.getSourceLabel())
                                    }
                                    context.startActivity(Intent.createChooser(intent, null))
                                }) {
                                    Icon(Icons.Filled.Share, contentDescription = "Bagikan", tint = MaterialTheme.colorScheme.onSurface)
                                }
                                IconButton(onClick = {
                                    active.totalChapters = maxOf(active.totalChapters, chapters.size)
                                    MangaFavoriteManager.toggle(context, active)
                                    isFavorite = MangaFavoriteManager.isFavorite(context, active)
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
                            HeaderBlockV4(manga = active)
                        }
                        if (active.synopsis.isNotBlank()) {
                            item {
                                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                                    SectionLabelV4("SINOPSIS")
                                    Spacer(Modifier.height(8.dp))
                                    SelectionContainer {
                                        Text(
                                            active.synopsis.trim(),
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
                                    SectionLabelV4("GENRE")
                                    Spacer(Modifier.height(10.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        genreLabels.forEach { label ->
                                            Box(
                                                Modifier
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                                    .clickable {
                                                        val value = genreItems.firstOrNull { it.title.equals(label, true) }?.value ?: label
                                                        onGenreClick(active.getSourceId(), active.getSourceLabel(), label, value)
                                                    }
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (similarItems.isNotEmpty() || similarLoadingMore) {
                            item {
                                Column(Modifier.padding(vertical = 14.dp)) {
                                    Box(Modifier.padding(horizontal = 20.dp)) { SectionLabelV4("CERITA SERUPA") }
                                    Spacer(Modifier.height(10.dp))
                                    LazyRow(
                                        state = similarState,
                                        contentPadding = PaddingValues(horizontal = 20.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        items(uniqueSimilarItems, key = { it.getSourceId() + "|" + it.slug }) { entry ->
                                            SimilarItemV4(item = entry, onClick = { onMangaClick(entry) })
                                        }
                                        if (similarLoadingMore) {
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
                                    header = { SectionLabelV4("ANILIST") },
                                    onRecommendationClick = { recTitle ->
                                        val active = manga
                                        if (active != null) onAniListSearch(active.getSourceId(), active.getSourceLabel(), recTitle)
                                    }
                                )
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SectionLabelV4("CHAPTER")
                                Spacer(Modifier.width(10.dp))
                                Text(chapters.size.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Spacer(Modifier.weight(1f))
                                Row(
                                    Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                                        chapterDescending = !chapterDescending
                                        prefs.edit().putBoolean("global_chapter_order_newest_first", chapterDescending).apply()
                                    }.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.SwapVert, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (chapterDescending) "Terbaru" else "Terlama", color = MaterialTheme.colorScheme.primary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                                }
                                IconButton(onClick = {
                                    val nextGrid = !chapterGrid
                                    chapterGrid = nextGrid
                                    MangaSettingsManager.setChapterLayout(context, if (nextGrid) MangaSettingsManager.CHAPTER_LAYOUT_GRID_2 else MangaSettingsManager.CHAPTER_LAYOUT_DEFAULT)
                                }) {
                                    Icon(
                                        if (chapterGrid) Icons.Filled.ViewList else Icons.Filled.GridView,
                                        contentDescription = if (chapterGrid) "Tampilan list" else "Tampilan grid 2",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                        if (loading && chapters.isEmpty()) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else if (chapters.isEmpty()) {
                            item {
                                Text(
                                    errorText ?: "Chapter belum tersedia",
                                    Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        } else if (chapterGrid) {
                            columnItems(chapterPairs, key = { "pair|" + it.first().slug + "|" + it.first().index }, contentType = { "chapterPair" }) { pair ->
                                Row(Modifier.padding(horizontal = 20.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    ChapterTileV4(
                                        manga = active,
                                        chapter = pair[0],
                                        historyVersion = historyVersion,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onChapterClick(active, pair[0], chapters) }
                                    )
                                    if (pair.size > 1) {
                                        ChapterTileV4(
                                            manga = active,
                                            chapter = pair[1],
                                            historyVersion = historyVersion,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onChapterClick(active, pair[1], chapters) }
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        } else {
                            columnItems(displayChapters, key = { it.slug + "|" + it.index }, contentType = { "chapter" }) { chapter ->
                                ChapterLineV4(
                                    manga = active,
                                    chapter = chapter,
                                    historyVersion = historyVersion,
                                    onClick = { onChapterClick(active, chapter, chapters) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabelV4(text: String) {
    Text(text, fontSize = 11.5.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun statusColorV4(status: String, fallback: Color): Color {
    val value = status.trim().lowercase()
    return when {
        value.contains("ongoing") || value.contains("berlangsung") || value.contains("publishing") -> Color(0xFF66BB6A)
        value.contains("completed") || value.contains("complete") || value.contains("tamat") || value.contains("finished") -> Color(0xFF42A5F5)
        value.contains("hiatus") -> Color(0xFFFFA726)
        value.contains("cancel") || value.contains("drop") -> Color(0xFFEF5350)
        else -> fallback
    }
}

@Composable
private fun HeaderBlockV4(manga: MangaPost) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Box(Modifier.width(104.dp).height(146.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            MangaNetworkImageV4(manga.getCover(), manga.getSourceId(), manga.title, Modifier.fillMaxSize(), 14.dp)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val type = manga.getTypeLabel()
            if (type.isNotBlank()) {
                Text(type, fontSize = 11.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            Text(
                manga.title.ifBlank { "Detail Manga" },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 21.sp,
                lineHeight = 26.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    clipboard.setText(AnnotatedString(manga.title))
                    Toast.makeText(context, "Judul manga disalin", Toast.LENGTH_SHORT).show()
                }
            )
            if (manga.author.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("oleh " + manga.author, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (manga.status.isNotBlank()) {
                    val color = statusColorV4(manga.status, MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(manga.status, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                    Spacer(Modifier.width(12.dp))
                }
                Text(manga.getSourceLabel(), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChapterLineV4(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, onClick: () -> Unit) {
    val context = LocalContext.current
    val progress = MangaProgressCache.progress(manga, chapter.index)
    val fraction = if (progress != null && progress.totalPages > 0) ((progress.page + 1).toFloat() / progress.totalPages.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
    val isFinished = progress != null && progress.totalPages > 0 && percent >= 100
    Column(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 20.dp)) {
        Row(Modifier.padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.width(46.dp).height(46.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (isFinished) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    MangaChapter.formatIndex(chapter.index),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    color = if (isFinished) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    chapter.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (chapter.date.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(chapter.date, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            when {
                isFinished -> Text("Selesai", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                percent > 0 -> Text("$percent%", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (progress != null && progress.totalPages > 0) {
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
private fun ChapterTileV4(manga: MangaPost, chapter: MangaChapter, historyVersion: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val progress = MangaProgressCache.progress(manga, chapter.index)
    val fraction = if (progress != null && progress.totalPages > 0) ((progress.page + 1).toFloat() / progress.totalPages.toFloat()).coerceIn(0f, 1f) else 0f
    val percent = (fraction * 100f).toInt().coerceIn(0, 100)
    val isFinished = progress != null && progress.totalPages > 0 && percent >= 100
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
                    chapter.title,
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
            if (chapter.date.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(chapter.date, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (progress != null && progress.totalPages > 0) {
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
private fun SimilarItemV4(item: MangaPost, onClick: () -> Unit) {
    Column(Modifier.width(100.dp).clickable { onClick() }) {
        Box(Modifier.width(100.dp).height(136.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
            MangaNetworkImageV4(item.getCover(), item.getSourceId(), item.title, Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
    }
}

private fun normalizeSimilarKeyV4(value: String): String =
    value.lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }

private fun similarSortV4(sourceId: String): String = when (sourceId) {
    MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
    MangaSettingsManager.MANGA_SOURCE_KOMIKTAP,
    MangaSettingsManager.MANGA_SOURCE_MANHWAINDO,
    MangaSettingsManager.MANGA_SOURCE_SOULSCANS,
    MangaSettingsManager.MANGA_SOURCE_KUROMANGA,
    MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK,
    MangaSettingsManager.MANGA_SOURCE_IKIRU,
    MangaSettingsManager.MANGA_SOURCE_LUVYAA,
    MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN -> "popular"
    MangaSettingsManager.MANGA_SOURCE_AINZSCANSS -> "views"
    MangaSettingsManager.MANGA_SOURCE_COMICASO -> "latest"
    else -> "popularity"
}

private fun similarFormatTokenV4(typeLabel: String): String {
    val format = when (typeLabel.trim().uppercase(java.util.Locale.ROOT)) {
        "MANHWA" -> "manhwa"
        "MANHUA" -> "manhua"
        "MANGATOON" -> "mangatoon"
        "DOUJINSHI" -> "doujinshi"
        "IMAGE-SET" -> "image-set"
        "ONESHOT", "ONE-SHOT" -> "one-shot"
        "COMIC" -> "comic"
        "NOVEL" -> "novel"
        "MANGA" -> "manga"
        else -> ""
    }
    return if (format.isEmpty()) "" else "type:$format"
}

private fun similarTypeFilterV4(sourceId: String, typeLabel: String): String {
    val token = similarFormatTokenV4(typeLabel)
    if (token.isEmpty()) return ""
    val value = token.removePrefix("type:")
    return when (sourceId) {
        MangaSettingsManager.MANGA_SOURCE_SHINIGAMI -> "format:$value"
        MangaSettingsManager.MANGA_SOURCE_NGOMIK,
        MangaSettingsManager.MANGA_SOURCE_COMICASO -> ""
        MangaSettingsManager.MANGA_SOURCE_DOUJINDESU,
        MangaSettingsManager.MANGA_SOURCE_IKIRU,
        MangaSettingsManager.MANGA_SOURCE_KOMIKU,
        MangaSettingsManager.MANGA_SOURCE_MANGASUSU,
        MangaSettingsManager.MANGA_SOURCE_KOMIKU_ORG,
        MangaSettingsManager.MANGA_SOURCE_COSMICSCANS,
        MangaSettingsManager.MANGA_SOURCE_KIRYUU_OFFICIAL,
        MangaSettingsManager.MANGA_SOURCE_NATSU,
        MangaSettingsManager.MANGA_SOURCE_AINZSCANSS,
        MangaSettingsManager.MANGA_SOURCE_APKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKINDO,
        MangaSettingsManager.MANGA_SOURCE_CROTPEDIA,
        MangaSettingsManager.MANGA_SOURCE_MGKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKTAP,
        MangaSettingsManager.MANGA_SOURCE_MANHWAINDO,
        MangaSettingsManager.MANGA_SOURCE_SOULSCANS,
        MangaSettingsManager.MANGA_SOURCE_KUROMANGA,
        MangaSettingsManager.MANGA_SOURCE_ISEKAIKOMIK,
        MangaSettingsManager.MANGA_SOURCE_KOMIKCAST,
        MangaSettingsManager.MANGA_SOURCE_LUVYAA,
        MangaSettingsManager.MANGA_SOURCE_SEKTEDOUJIN -> token
        else -> ""
    }
}

private fun similarGenreValuesV4(genreText: String, catalog: List<KomikcastClient.GenreItem>): List<String> {
    val labels = genreText.split(",", "|", "/").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (labels.isEmpty()) return emptyList()
    val usable = catalog.filter { it.value.isNotBlank() && !it.value.startsWith("type:") && !it.value.startsWith("status:") }
    val out = LinkedHashSet<String>()
    for (label in labels) {
        val key = normalizeSimilarKeyV4(label)
        if (key.isEmpty()) continue
        val match = usable.firstOrNull { normalizeSimilarKeyV4(it.title) == key }
            ?: usable.firstOrNull { normalizeSimilarKeyV4(it.value) == key }
        if (match != null) out.add(match.value) else if (usable.isEmpty()) out.add(label)
        if (out.size >= 3) break
    }
    return out.toList()
}

private fun buildSimilarFiltersV4(manga: MangaPost, catalog: List<KomikcastClient.GenreItem>): List<String> {
    val sourceId = manga.getSourceId()
    val typeFilter = similarTypeFilterV4(sourceId, manga.getTypeLabel())
    val genreValues = similarGenreValuesV4(manga.genre, catalog)
    val out = LinkedHashSet<String>()
    if (genreValues.isNotEmpty()) {
        genreValues.forEach { genre ->
            out.add(if (typeFilter.isEmpty()) genre else "$genre|$typeFilter")
        }
    } else if (typeFilter.isNotEmpty()) {
        out.add(typeFilter)
    }
    if (out.isEmpty() && genreValues.isNotEmpty()) out.addAll(genreValues)
    return out.toList()
}

private suspend fun fetchSimilarPageV4(
    context: Context,
    active: MangaPost,
    filters: List<String>,
    page: Int,
    seen: MutableSet<String>
): Pair<List<MangaPost>, Boolean> {
    val sourceId = active.getSourceId()
    val sourceLabel = active.getSourceLabel()
    val sort = similarSortV4(sourceId)
    var anyHasNext = false
    val streams = ArrayList<List<MangaPost>>()
    for (filter in filters) {
        val result = try {
            MangaRepository.list(sourceId, page, sort, "", filter)
        } catch (e: Throwable) {
            null
        } ?: continue
        if (result.hasNext) anyHasNext = true
        val items = ArrayList<MangaPost>()
        result.data.forEach { post ->
            val item = post.withSource(sourceId, sourceLabel)
            MangaLabelUtils.applyHiddenLabels(context, item)
            val key = item.getSourceId() + "|" + item.slug
            if (item.slug != active.slug && seen.add(key)) items.add(item)
        }
        if (items.isNotEmpty()) streams.add(items)
    }
    val merged = ArrayList<MangaPost>()
    val iterators = streams.map { it.iterator() }
    var added = true
    while (added) {
        added = false
        for (iterator in iterators) {
            if (iterator.hasNext()) {
                merged.add(iterator.next())
                added = true
            }
        }
    }
    return merged to anyHasNext
}

@Composable
private fun MangaNetworkImageV4(url: String, sourceId: String, contentDescription: String?, modifier: Modifier = Modifier, cornerRadius: androidx.compose.ui.unit.Dp = 12.dp) {
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { cornerRadius.toPx() }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.ImageView(ctx).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
                    }
                }
                clipToOutline = true
            }
        },
        update = { view ->
            view.contentDescription = contentDescription
            loadCoverNoAvifV4(view, url, sourceId)
        },
        onRelease = { view -> cancelCoverNoAvifV4(view) }
    )
}

private val coverDisposablesV4 = java.util.WeakHashMap<android.widget.ImageView, coil.request.Disposable>()

private fun cancelCoverNoAvifV4(view: android.widget.ImageView) {
    coverDisposablesV4.remove(view)?.dispose()
    view.setTag(null)
}

private fun loadCoverNoAvifV4(view: android.widget.ImageView, url: String, sourceId: String) {
    val context = view.context
    val resolved = MangaImageLoader.resolveImageUrl(url, sourceId)
    cancelCoverNoAvifV4(view)
    if (resolved.isBlank()) return
    view.setTag(resolved)
    val key = MangaImageLoader.imageCacheKey(url, sourceId)
    val baseHeaders = MangaImageLoader.headersFor(resolved, sourceId)
    val loader = coil.Coil.imageLoader(context)
    fun enqueue(attempt: Int) {
        if (view.tag != resolved) return
        val hb = okhttp3.Headers.Builder()
        baseHeaders.names().forEach { name ->
            if (name.equals("Accept", true)) {
                if (attempt < 2) hb.add(name, "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            } else {
                baseHeaders.values(name).forEach { value -> hb.add(name, value) }
            }
        }
        val builder = coil.request.ImageRequest.Builder(context)
            .data(resolved)
            .headers(hb.build())
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .allowHardware(true)
            .target(view)
            .listener(onError = { _, _ ->
                if (view.tag == resolved && attempt < 2) {
                    view.postDelayed({ if (view.tag == resolved) enqueue(attempt + 1) }, 900L * (attempt + 1))
                }
            })
        if (attempt > 0) {
            builder.memoryCachePolicy(coil.request.CachePolicy.DISABLED)
            builder.diskCachePolicy(coil.request.CachePolicy.DISABLED)
        }
        coverDisposablesV4[view] = loader.enqueue(builder.build())
    }
    enqueue(0)
}

private suspend fun <T> CompletableFuture<T>.awaitFuture(): T = suspendCancellableCoroutine { cont ->
    whenComplete { value, error ->
        if (!cont.isActive) return@whenComplete
        if (error != null) cont.resumeWithException(error) else cont.resume(value)
    }
    cont.invokeOnCancellation { cancel(true) }
}
