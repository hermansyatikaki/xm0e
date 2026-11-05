@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package miku.moe.app

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay


// Sensitivitas gestur geser vertikal di player (1.0 = geser setinggi layar mengubah 100%).
private const val BRIGHTNESS_GESTURE_GAIN = 0.55f
private const val VOLUME_GESTURE_GAIN = 0.45f

private const val PIP_V3_PREV = "miku.moe.app.v3pip.PREV"
private const val PIP_V3_TOGGLE = "miku.moe.app.v3pip.TOGGLE"
private const val PIP_V3_NEXT = "miku.moe.app.v3pip.NEXT"

private enum class AspectModeV3(val label: String, val resizeMode: Int, val scaleValue: String) {
    PAS("Pas", AspectRatioFrameLayout.RESIZE_MODE_FIT, AnimeSettingsManager.VIDEO_SCALE_NORMAL),
    POTONG("Potong", AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AnimeSettingsManager.VIDEO_SCALE_CROP),
    REGANG("Regang", AspectRatioFrameLayout.RESIZE_MODE_FILL, AnimeSettingsManager.VIDEO_SCALE_STRETCH);

    companion object {
        fun fromScale(scale: String?): AspectModeV3 = when (scale) {
            AnimeSettingsManager.VIDEO_SCALE_CROP -> POTONG
            AnimeSettingsManager.VIDEO_SCALE_STRETCH -> REGANG
            else -> PAS
        }
    }
}

private fun formatTimeV3(ms: Long): String {
    if (ms < 0) return "00:00"
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun menuLabelV3(raw: String): String {
    val match = Regex("^(\\d+)\\.(\\d)0x$").matchEntire(raw.trim()) ?: return raw
    return "${match.groupValues[1]}.${match.groupValues[2]}x"
}

class AnimePlayerChromeV3(
    private val root: View,
    private val closeAction: Runnable?,
    private val immersiveAction: Runnable?
) : AnimePlayerUi {

    private val activity: Activity? = root.context as? Activity
    private val composeView: ComposeView = root.findViewById(R.id.player_v3_compose)
    private val speedAnchor: View = root.findViewById(R.id.player_v3_anchor_speed)
    private val qualityAnchor: View = root.findViewById(R.id.player_v3_anchor_quality)
    private val playerViewRef: PlayerView? = root.findViewById(R.id.player_view)

    private val accent: Color = Color(resolveThemePrimary(root.context))
    private val dimText = Color(0xFFB9B9C4)

    private var actionListener: AnimePlayerChrome.ActionListener? = null
    private var navigationListener: AnimePlayerChrome.NavigationListener? = null
    private var availabilityProvider: AnimePlayerChrome.AvailabilityProvider? = null

    private var player by mutableStateOf<Player?>(null)
    private var tick by mutableIntStateOf(0)
    private var titleText by mutableStateOf("Anime")
    private var episodeText by mutableStateOf("")
    private var qualityText by mutableStateOf("AUTO")
    private var popupVisible by mutableStateOf(false)
    private var errorText by mutableStateOf<String?>(null)
    private var navPrev by mutableStateOf(false)
    private var navNext by mutableStateOf(false)
    private var inPip by mutableStateOf(false)

    private var controlsVisible by mutableStateOf(true)
    private var menuOpen by mutableStateOf(false)
    private var menuLabels by mutableStateOf<List<String>>(emptyList())
    private var menuChecked by mutableIntStateOf(-1)
    private var menuAnchor by mutableIntStateOf(0)
    private var menuPickCb: java.util.function.IntConsumer? = null
    private var menuClosedCb: Runnable? = null

    private fun resolveThemePrimary(context: Context): Int {
        val attrId = context.resources.getIdentifier("colorPrimary", "attr", context.packageName)
        if (attrId != 0) {
            val tv = android.util.TypedValue()
            if (context.theme.resolveAttribute(attrId, tv, true)) {
                return if (tv.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT &&
                    tv.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT
                ) {
                    tv.data
                } else {
                    ContextCompat.getColor(context, tv.resourceId)
                }
            }
        }
        return android.graphics.Color.rgb(245, 165, 36)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            tick++
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            tick++
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            tick++
        }

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            tick++
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            errorText = error.message ?: "Tidak dapat memutar video ini."
            tick++
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PIP_V3_PREV -> moveToEpisode(false)
                PIP_V3_NEXT -> moveToEpisode(true)
                PIP_V3_TOGGLE -> togglePlay()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(PIP_V3_PREV)
            addAction(PIP_V3_TOGGLE)
            addAction(PIP_V3_NEXT)
        }
        ContextCompat.registerReceiver(root.context, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        composeView.setContent { V3Content() }
    }

    override fun attachPlayer(player: Player?) {
        this.player?.removeListener(playerListener)
        this.player = player
        this.player?.addListener(playerListener)
        errorText = null
        tick++
    }

    override fun release() {
        player?.removeListener(playerListener)
        player = null
        runCatching { root.context.unregisterReceiver(pipReceiver) }
        composeView.disposeComposition()
    }

    override fun setTitle(title: String?) {
        setTitle("Anime", title)
    }

    override fun setTitle(animeTitle: String?, episodeLine: String?) {
        val title = (animeTitle ?: "").trim()
        titleText = if (title.isEmpty()) "Anime" else title
        var line = (episodeLine ?: "").trim()
        if (title.isNotEmpty() && line.startsWith(title, ignoreCase = true)) {
            line = line.substring(title.length).trimStart(' ', '-', '•', ':', '|', '–', '—')
        }
        if (line.startsWith("Eps ", ignoreCase = true)) {
            line = "Episode " + line.substring(4)
        }
        episodeText = line
    }

    override fun setQualityLabel(label: String?) {
        qualityText = if (label.isNullOrBlank()) "AUTO" else label.trim()
    }

    override fun setScaleLabel(label: String?) {
        // Player Style v3 owns its scale control (Pas/Potong/Regang), like the Nocturne player.
    }

    override fun setRotateState(landscape: Boolean) {
        // The v3 rotate chip has no orientation-specific glyph; nothing to update.
    }

    override fun setPopupMenuVisible(visible: Boolean) {
        popupVisible = visible
        if (visible) controlsVisible = true
        tick++
    }

    override fun showChoiceMenu(
        labels: Array<String>?,
        checkedIndex: Int,
        onPick: java.util.function.IntConsumer?,
        onClosed: Runnable?
    ) {
        menuLabels = labels?.toList() ?: emptyList()
        menuChecked = checkedIndex
        menuPickCb = onPick
        menuClosedCb = onClosed
        controlsVisible = true
        menuOpen = true
        tick++
    }

    override fun setActionListener(listener: AnimePlayerChrome.ActionListener?) {
        actionListener = listener
    }

    override fun setNavigationListener(listener: AnimePlayerChrome.NavigationListener?) {
        navigationListener = listener
    }

    override fun setAvailabilityProvider(provider: AnimePlayerChrome.AvailabilityProvider?) {
        availabilityProvider = provider
    }

    override fun updateNavigation(playerHasPrevious: Boolean, playerHasNext: Boolean) {
        navPrev = playerHasPrevious
        navNext = playerHasNext
    }

    private fun closeMenu(pickedIndex: Int?) {
        if (pickedIndex != null) menuPickCb?.accept(pickedIndex)
        menuOpen = false
        val closed = menuClosedCb
        menuPickCb = null
        menuClosedCb = null
        closed?.run()
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0)
            p.play()
        } else if (p.isPlaying) {
            p.pause()
        } else {
            p.play()
        }
        tick++
    }

    private fun moveToEpisode(next: Boolean) {
        val p = player ?: return
        val available = if (next) p.hasNextMediaItem() else p.hasPreviousMediaItem()
        if (!available) {
            navigationListener?.onNavigationUnavailable(next)
            return
        }
        if (next) p.seekToNextMediaItem() else p.seekToPreviousMediaItem()
        if (p.playerError != null || p.playbackState == Player.STATE_IDLE) p.prepare()
        p.play()
        tick++
    }

    private fun pipAction(action: String, requestCode: Int, iconRes: Int, title: String): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            root.context,
            requestCode,
            Intent(action).setPackage(root.context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return RemoteAction(Icon.createWithResource(root.context, iconRes), title, title, intent)
    }

    private fun buildPipParams(): PictureInPictureParams {
        val playing = player?.isPlaying == true
        val actions = listOf(
            pipAction(PIP_V3_PREV, 201, android.R.drawable.ic_media_previous, "Episode sebelumnya"),
            pipAction(
                PIP_V3_TOGGLE,
                202,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Jeda" else "Putar"
            ),
            pipAction(PIP_V3_NEXT, 203, android.R.drawable.ic_media_next, "Episode berikutnya")
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
    }

    private fun enterPip() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { act.enterPictureInPictureMode(buildPipParams()) }
        }
    }

    private fun refreshPipParams() {
        val act = activity ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && inPip) {
            runCatching { act.setPictureInPictureParams(buildPipParams()) }
        }
    }

    @Composable
    private fun V3Content() {
        val context = LocalContext.current
        val currentPlayer = player
        val accentColor = accent
        @Suppress("UNUSED_EXPRESSION") tick
        @Suppress("UNUSED_EXPRESSION") menuChecked

        var locked by remember { mutableStateOf(false) }
        var lockHint by remember { mutableStateOf(false) }
        var scrubbing by remember { mutableStateOf(false) }
        var scrubFraction by remember { mutableFloatStateOf(0f) }
        var positionMs by remember { mutableLongStateOf(0L) }
        var durationMs by remember { mutableLongStateOf(0L) }
        var speed by remember { mutableFloatStateOf(1f) }
        var aspect by remember { mutableStateOf(AspectModeV3.fromScale(AnimeSettingsManager.getVideoScale(context))) }
        var aspectMenu by remember { mutableStateOf(false) }
        var awaitingMenu by remember { mutableStateOf(false) }
        var awaitingDeadline by remember { mutableLongStateOf(0L) }
        var badgeX by remember { mutableFloatStateOf(0f) }
        var badgeY by remember { mutableFloatStateOf(0f) }
        var badgeText by remember { mutableStateOf("") }
        var badgeKey by remember { mutableIntStateOf(0) }
        var hudKind by remember { mutableIntStateOf(0) }
        var hudPercent by remember { mutableIntStateOf(0) }
        var hudKey by remember { mutableIntStateOf(0) }

        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
        var brightness by remember {
            mutableFloatStateOf(
                (activity?.window?.attributes?.screenBrightness ?: -1f).takeIf { it >= 0f }
                    ?: try {
                        // Window masih "ikut sistem" (-1): ambil kecerlangan sistem agar tidak loncat saat mulai geser.
                        android.provider.Settings.System.getInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS
                        ) / 255f
                    } catch (e: Throwable) {
                        0.5f
                    }
            )
        }

        val isPlaying = currentPlayer?.isPlaying == true
        val isBuffering = currentPlayer?.playbackState == Player.STATE_BUFFERING && currentPlayer?.playerError == null
        val isEnded = currentPlayer?.playbackState == Player.STATE_ENDED
        val hasPrev = navPrev || currentPlayer?.hasPreviousMediaItem() == true ||
            availabilityProvider?.hasAdjacent(false) == true
        val hasNext = navNext || currentPlayer?.hasNextMediaItem() == true ||
            availabilityProvider?.hasAdjacent(true) == true
        val anyMenu = menuOpen || aspectMenu

        LaunchedEffect(aspect) {
            playerViewRef?.resizeMode = aspect.resizeMode
        }

        LaunchedEffect(currentPlayer) {
            playerViewRef?.resizeMode = aspect.resizeMode
            while (true) {
                val p = currentPlayer
                if (p != null) {
                    positionMs = p.currentPosition.coerceAtLeast(0L)
                    durationMs = if (p.duration == C.TIME_UNSET) 0L else p.duration
                    speed = p.playbackParameters.speed
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPip = activity?.isInPictureInPictureMode == true
                }
                if (awaitingMenu && !menuOpen && System.currentTimeMillis() > awaitingDeadline) {
                    awaitingMenu = false
                }
                delay(500)
            }
        }

        LaunchedEffect(isPlaying) { refreshPipParams() }

        LaunchedEffect(controlsVisible, isPlaying, scrubbing, anyMenu, popupVisible, awaitingMenu, locked) {
            if (controlsVisible && isPlaying && !scrubbing && !anyMenu && !popupVisible && !awaitingMenu && !locked) {
                delay(3500)
                controlsVisible = false
                immersiveAction?.run()
            }
        }
        LaunchedEffect(badgeKey) {
            if (badgeKey > 0) {
                delay(750)
                badgeText = ""
            }
        }
        LaunchedEffect(hudKey) {
            if (hudKey > 0) {
                delay(900)
                hudKind = 0
            }
        }
        LaunchedEffect(lockHint) {
            if (lockHint) {
                delay(2600)
                lockHint = false
            }
        }

        fun seekBy(deltaMs: Long, tapOffset: androidx.compose.ui.geometry.Offset) {
            val p = currentPlayer ?: return
            val maxPos = if (durationMs > 0) durationMs else Long.MAX_VALUE
            p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, maxPos))
            badgeX = tapOffset.x
            badgeY = tapOffset.y
            badgeText = if (deltaMs < 0) "-10 detik" else "+10 detik"
            badgeKey++
        }

        Box(modifier = Modifier.fillMaxSize()) {
            if (!locked && !inPip) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    controlsVisible = !controlsVisible
                                    if (!controlsVisible) immersiveAction?.run()
                                },
                                onDoubleTap = { offset ->
                                    if (offset.x < size.width / 2) seekBy(-10_000L, offset) else seekBy(10_000L, offset)
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val edgeWidth = size.width * 0.2f
                                val mode = when {
                                    down.position.x < edgeWidth -> 1
                                    down.position.x > size.width - edgeWidth -> 2
                                    else -> 0
                                }
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                var volumeFraction =
                                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                                var totalDx = 0f
                                var totalDy = 0f
                                var direction = 0
                                val slop = viewConfiguration.touchSlop
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val dx = change.position.x - change.previousPosition.x
                                    val dy = change.position.y - change.previousPosition.y
                                    totalDx += dx
                                    totalDy += dy
                                    if (direction == 0) {
                                        val adx = kotlin.math.abs(totalDx)
                                        val ady = kotlin.math.abs(totalDy)
                                        if (ady > slop && ady > adx * 1.5f) {
                                            direction = 1
                                        } else if (adx > slop && adx >= ady) {
                                            direction = 2
                                        }
                                    }
                                    if (direction == 1) {
                                        change.consume()
                                        // Geser setinggi layar = 55% (cahaya) / 45% (volume), bukan 100%,
                                        // supaya naik-turunnya halus dan tidak loncat.
                                        val delta = -dy / size.height
                                        if (mode == 1) {
                                            brightness = (brightness + delta * BRIGHTNESS_GESTURE_GAIN).coerceIn(0.02f, 1f)
                                            activity?.window?.let { win ->
                                                win.attributes = win.attributes.apply { screenBrightness = brightness }
                                            }
                                            hudKind = 1
                                            hudPercent = (brightness * 100).toInt()
                                            hudKey++
                                        } else if (mode == 2) {
                                            volumeFraction = (volumeFraction + delta * VOLUME_GESTURE_GAIN).coerceIn(0f, 1f)
                                            audioManager.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                kotlin.math.round(volumeFraction * maxVol).toInt().coerceIn(0, maxVol),
                                                0
                                            )
                                            hudKind = 2
                                            hudPercent = (volumeFraction * 100).toInt()
                                            hudKey++
                                        }
                                    }
                                }
                            }
                        }
                )
            } else if (!inPip) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { lockHint = true })
                        }
                )
            }

            if (isBuffering && errorText == null && !inPip) {
                CircularProgressIndicator(
                    color = accentColor,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(52.dp)
                )
            }

            if (errorText != null && !inPip) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Gagal memutar video", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(text = errorText ?: "", color = dimText, fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = accentColor,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { closeAction?.run() }
                    ) {
                        Text(
                            text = "Kembali",
                            color = Color(0xFF1A1206),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            if (badgeText.isNotEmpty() && !inPip) {
                val density = LocalDensity.current
                val badgeOffsetX = with(density) { badgeX.toDp() } - 52.dp
                val badgeOffsetY = with(density) { badgeY.toDp() } - 20.dp
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xB3000000),
                    modifier = Modifier.offset(
                        x = if (badgeOffsetX < 8.dp) 8.dp else badgeOffsetX,
                        y = if (badgeOffsetY < 8.dp) 8.dp else badgeOffsetY
                    )
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }

            if (hudKind != 0 && !inPip) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xB3000000),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 26.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hudKind == 1) Icons.Filled.BrightnessMedium else Icons.Filled.VolumeUp,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(text = "$hudPercent%", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (controlsVisible && !locked && errorText == null && !inPip) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    V3IconButton(icon = Icons.AutoMirrored.Filled.ArrowBack, desc = "Kembali") {
                        closeAction?.run()
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = titleText,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (episodeText.isNotEmpty()) {
                            Text(
                                text = episodeText,
                                color = dimText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Box {
                        V3Chip(
                            icon = Icons.Filled.Speed,
                            text = if (speed % 1f == 0f) "${speed.toInt()}.0x" else "${speed}x",
                            accent = accentColor
                        ) {
                            menuAnchor = 1
                            awaitingMenu = true
                            awaitingDeadline = System.currentTimeMillis() + 6000L
                            controlsVisible = true
                            actionListener?.onSpeedClick(speedAnchor)
                        }
                        DropdownMenu(
                            expanded = menuOpen && menuAnchor == 1,
                            onDismissRequest = { closeMenu(null) }
                        ) {
                            menuLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(menuLabelV3(label)) },
                                    onClick = { closeMenu(index) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box {
                        V3Chip(icon = Icons.Filled.HighQuality, text = qualityText, accent = accentColor) {
                            menuAnchor = 2
                            awaitingMenu = true
                            awaitingDeadline = System.currentTimeMillis() + 6000L
                            controlsVisible = true
                            actionListener?.onQualityClick(qualityAnchor)
                        }
                        DropdownMenu(
                            expanded = menuOpen && menuAnchor == 2,
                            onDismissRequest = { closeMenu(null) }
                        ) {
                            menuLabels.forEachIndexed { index, label ->
                                DropdownMenuItem(
                                    text = { Text(menuLabelV3(label)) },
                                    onClick = { closeMenu(index) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    V3IconButton(icon = Icons.Filled.PictureInPictureAlt, desc = "Picture in picture") { enterPip() }
                }

                if (!isBuffering) {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        V3IconButton(icon = Icons.Filled.SkipPrevious, desc = "Episode sebelumnya", enabled = hasPrev) {
                            moveToEpisode(false)
                        }
                        Spacer(Modifier.width(20.dp))
                        Surface(
                            shape = CircleShape,
                            color = Color(0x8C000000),
                            modifier = Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .clickable {
                                    if (isEnded) {
                                        currentPlayer?.seekTo(0)
                                        currentPlayer?.play()
                                    } else {
                                        togglePlay()
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlaying) "Jeda" else "Putar",
                                    tint = accentColor,
                                    modifier = Modifier.size(42.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(20.dp))
                        V3IconButton(icon = Icons.Filled.SkipNext, desc = "Episode berikutnya", enabled = hasNext) {
                            moveToEpisode(true)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        V3Chip(icon = Icons.Filled.ScreenRotation, text = "Putar", accent = accentColor) {
                            actionListener?.onRotateClick()
                        }
                        Spacer(Modifier.width(8.dp))
                        Box {
                            V3Chip(icon = Icons.Filled.AspectRatio, text = aspect.label, accent = accentColor) {
                                aspectMenu = true
                            }
                            DropdownMenu(expanded = aspectMenu, onDismissRequest = { aspectMenu = false }) {
                                AspectModeV3.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { Text(mode.label) },
                                        onClick = {
                                            aspect = mode
                                            AnimeSettingsManager.setVideoScale(context, mode.scaleValue)
                                            aspectMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        V3Chip(icon = Icons.Filled.FastForward, text = "Skip OP", accent = accentColor) {
                            val p = currentPlayer
                            if (p != null) {
                                val target = if (positionMs < 90_000L) {
                                    90_000L
                                } else if (durationMs > 0) {
                                    minOf(positionMs + 90_000L, durationMs)
                                } else {
                                    positionMs + 90_000L
                                }
                                p.seekTo(target)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        V3Chip(icon = Icons.Filled.Lock, text = "Kunci", accent = accentColor) {
                            locked = true
                            controlsVisible = false
                            lockHint = true
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shownPos = if (scrubbing) (scrubFraction * durationMs).toLong() else positionMs
                        Text(text = formatTimeV3(shownPos), color = Color.White, fontSize = 11.sp)
                        Slider(
                            value = when {
                                scrubbing -> scrubFraction
                                durationMs > 0 -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                                else -> 0f
                            },
                            onValueChange = { value ->
                                scrubbing = true
                                scrubFraction = value
                            },
                            onValueChangeFinished = {
                                if (durationMs > 0) currentPlayer?.seekTo((scrubFraction * durationMs).toLong())
                                scrubbing = false
                            },
                            thumb = {
                                Icon(
                                    imageVector = Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = accentColor,
                                activeTrackColor = accentColor,
                                inactiveTrackColor = Color(0x55FFFFFF)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )
                        Text(text = formatTimeV3(durationMs), color = dimText, fontSize = 11.sp)
                    }
                }
            }

            if (locked && lockHint && !inPip) {
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(22.dp)
                        .size(54.dp)
                        .clip(CircleShape)
                        .clickable {
                            locked = false
                            lockHint = false
                            controlsVisible = true
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.LockOpen,
                            contentDescription = "Buka kunci",
                            tint = Color(0xFF1A1206),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
                Text(
                    text = "Player terkunci",
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 24.dp)
                )
            }
        }
    }

    @Composable
    private fun V3IconButton(
        icon: ImageVector,
        desc: String,
        enabled: Boolean = true,
        onClick: () -> Unit
    ) {
        Surface(
            shape = CircleShape,
            color = Color(0x66000000),
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = desc,
                    tint = if (enabled) Color.White else Color(0x66FFFFFF),
                    modifier = Modifier.size(23.dp)
                )
            }
        }
    }

    @Composable
    private fun V3Chip(icon: ImageVector, text: String, accent: Color, onClick: () -> Unit) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = Color(0xA61B1B22),
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text = text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
