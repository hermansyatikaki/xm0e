package miku.moe.app;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.content.res.Configuration;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;


import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnimekuVideoPlayerActivity extends AppCompatActivity {
    private static final String ANIMEKU_API_KEY = "cda11y63tfI7rwln8BLeiKTvjsD5g2Mox01RzkhQCEXSGWbqYO";
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_CATEGORY_NAME = "category_name";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_DISABLE_PLAYLIST = "disable_playlist";
    public static final String EXTRA_HISTORY_SOURCE_ID = "history_source_id";
    public static final String EXTRA_ANIMELOVERZ_SLUG = "animeloverz_slug";
    public static final String EXTRA_ANIMELOVERZ_EPISODE_URL = "animeloverz_episode_url";
    public static final String EXTRA_QUALITY_LABEL = "quality_label";
    public static final String EXTRA_HISTORY_SLUG = "history_slug";
    public static final String EXTRA_SOKUJA_EPISODE_SLUG = "sokuja_episode_slug";
    public static final String EXTRA_SOKUJA_EPISODE_LIST = "sokuja_episode_list";
    public static final String EXTRA_SOKUJA_EPISODE_INDEX = "sokuja_episode_index";
    public static final String EXTRA_DRAKORKU_EPISODE_LIST = "drakorku_episode_list";

    private static final String TAG = "AnimekuVideoPlayer";
    private static final String API_BASE = "https://pencarinafkah.xyz/vA6//api/";
    private static final String IMAGE_BASE = "http://elara.whatbox.ca:29318/Duljanah/";
    private static final String ANIMELOVERZ_API_BASE = "https://apps.animekita.org/api/v1.2.5";

    private PlayerView playerView;
    private AnimePlayerChrome playerChrome;
    private ExoPlayer player;
    private RequestQueue requestQueue;
    private DefaultHttpDataSource.Factory dataSourceFactory;
    private long playbackPosition = 0L;
    private boolean playWhenReady = true;
    private String videoUrl;
    private String videoTitle = "";
    private String imageUrl = "";
    private int channelId = -1;
    private int categoryId = -1;
    private String categoryName = "";
    private String selectedQuality = PlaybackQualityManager.QUALITY_HD;
    private String selectedQualityLabel = PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD);
    private boolean playlistLoaded = false;
    private boolean playlistLoading = false;
    private boolean playlistReady = false;
    private boolean navigating = false;
    private boolean episodeOrderLoaded = false;
    private final ArrayList<int[]> episodeOrderRows = new ArrayList<>();
    private final ArrayList<AnimeLoverzEpisodeMeta> animeLoverzMetas = new ArrayList<>();
    private int playlistRetry = 0;
    private boolean autoLandscapeActive = false;
    private EpisodeMedia currentEpisodeMedia;
    private long currentEpisodeDuration = 0L;
    private boolean disablePlaylist = false;
    private String historySourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
    private String animeLoverzSlug = "";
    private String animeLoverzEpisodeUrl = "";
    private String historySlug = "";
    private String sokujaEpisodeSlug = "";
    private final ArrayList<String> sokujaEpisodeSlugs = new ArrayList<>();
    private final ArrayList<String> sokujaEpisodeLabels = new ArrayList<>();
    private int sokujaEpisodeIndex = -1;
    private final ArrayList<Integer> drakorkuEpisodeIds = new ArrayList<>();
    private final ArrayList<String> drakorkuEpisodeLabels = new ArrayList<>();
    private boolean refreshingQuality = false;
    private boolean qualityOptionsLoading = false;
    private boolean qualityMenuRequested = false;
    private long lastQualityPrefetchAt = 0L;
    private String loverzQualityKey = "";
    private final java.util.HashMap<String, ArrayList<String[]>> qualityOptionsCache = new java.util.HashMap<>();
    private final java.util.HashMap<String, ArrayList<SokujaSource.StreamResult>> sokujaQualityCache = new java.util.HashMap<>();

    private static class EpisodeMedia {
        final int channelId;
        final int categoryId;
        final String categoryName;
        final String title;
        final String imageUrl;
        final String videoUrl;
        final int episodeNumber;
        final int sourceIndex;
        final String sourceEpisodeUrl;

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl, int episodeNumber, int sourceIndex) {
            this(channelId, categoryId, categoryName, title, imageUrl, videoUrl, episodeNumber, sourceIndex, "");
        }

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl, int episodeNumber, int sourceIndex, String sourceEpisodeUrl) {
            this.channelId = channelId;
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.title = title;
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.episodeNumber = episodeNumber;
            this.sourceIndex = sourceIndex;
            this.sourceEpisodeUrl = sourceEpisodeUrl == null ? "" : sourceEpisodeUrl;
        }
    }

    private static class AnimeLoverzEpisodeMeta {
        final int id;
        final String url;
        final String episode;
        final String label;
        final int index;

        AnimeLoverzEpisodeMeta(int id, String url, String episode, String label, int index) {
            this.id = id;
            this.url = url == null ? "" : url;
            this.episode = episode == null ? "" : episode;
            this.label = label == null || label.trim().isEmpty() ? "Episode" : label;
            this.index = index;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(AnimeSettingsManager.isPlayerStyleV2(this) ? R.layout.activity_video_player_v2 : R.layout.activity_animeku_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        playerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyVideoScale(false);
        });
        playerChrome = new AnimePlayerChrome(findViewById(R.id.player_shell), this::finish, this::hideSystemBars);
        playerChrome.setRotateState(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        requestQueue = Volley.newRequestQueue(getApplicationContext());
        videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        videoTitle = getIntent().getStringExtra(EXTRA_VIDEO_TITLE);
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        channelId = getIntent().getIntExtra(EXTRA_CHANNEL_ID, -1);
        categoryId = getIntent().getIntExtra(EXTRA_CATEGORY_ID, -1);
        categoryName = getIntent().getStringExtra(EXTRA_CATEGORY_NAME);
        playbackPosition = getIntent().getLongExtra(EXTRA_START_POSITION, 0L);
        selectedQuality = getIntent().getStringExtra(EXTRA_QUALITY);
        disablePlaylist = getIntent().getBooleanExtra(EXTRA_DISABLE_PLAYLIST, false);
        historySourceId = getIntent().getStringExtra(EXTRA_HISTORY_SOURCE_ID);
        animeLoverzSlug = getIntent().getStringExtra(EXTRA_ANIMELOVERZ_SLUG);
        animeLoverzEpisodeUrl = getIntent().getStringExtra(EXTRA_ANIMELOVERZ_EPISODE_URL);
        historySlug = getIntent().getStringExtra(EXTRA_HISTORY_SLUG);
        sokujaEpisodeSlug = getIntent().getStringExtra(EXTRA_SOKUJA_EPISODE_SLUG);
        if (sokujaEpisodeSlug == null) sokujaEpisodeSlug = "";
        parseSokujaEpisodeList(getIntent().getStringExtra(EXTRA_SOKUJA_EPISODE_LIST));
        sokujaEpisodeIndex = getIntent().getIntExtra(EXTRA_SOKUJA_EPISODE_INDEX, -1);
        syncSokujaEpisodeIndex();
        parseDrakorkuEpisodeList(getIntent().getStringExtra(EXTRA_DRAKORKU_EPISODE_LIST));
        if (selectedQuality == null || selectedQuality.trim().isEmpty()) selectedQuality = PlaybackQualityManager.getQuality(this);
        selectedQualityLabel = getIntent().getStringExtra(EXTRA_QUALITY_LABEL);
        if (selectedQualityLabel == null || selectedQualityLabel.trim().isEmpty()) selectedQualityLabel = PlaybackQualityManager.getQualityLabel(selectedQuality);
        if (historySourceId == null || historySourceId.trim().isEmpty() || !AnimeSettingsManager.isValidSource(historySourceId)) historySourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
        if (videoTitle == null) videoTitle = "";
        if (playerChrome != null) playerChrome.setTitle(categoryName, videoTitle);
        if (imageUrl == null) imageUrl = "";
        if (categoryName == null) categoryName = "";
        if (animeLoverzSlug == null) animeLoverzSlug = "";
        if (animeLoverzEpisodeUrl == null) animeLoverzEpisodeUrl = "";
        if (historySlug == null) historySlug = "";
        if (historySlug.trim().isEmpty() && AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) historySlug = animeLoverzSlug;

        if (openExternalPlayerIfNeeded()) return;
        applyAutoLandscapeIfNeeded();

        if (savedInstanceState != null) {
            playbackPosition = savedInstanceState.getLong("player_position", 0L);
            playWhenReady = savedInstanceState.getBoolean("play_when_ready", true);
            String restoredUrl = savedInstanceState.getString("video_url");
            if (restoredUrl != null && !restoredUrl.isEmpty()) videoUrl = restoredUrl;
            videoTitle = savedInstanceState.getString("video_title", videoTitle);
            imageUrl = savedInstanceState.getString("image_url", imageUrl);
            channelId = savedInstanceState.getInt("channel_id", channelId);
            categoryId = savedInstanceState.getInt("category_id", categoryId);
            categoryName = savedInstanceState.getString("category_name", categoryName);
            selectedQuality = savedInstanceState.getString("selected_quality", selectedQuality);
            selectedQualityLabel = savedInstanceState.getString("selected_quality_label", selectedQualityLabel);
            disablePlaylist = savedInstanceState.getBoolean("disable_playlist", disablePlaylist);
            historySourceId = savedInstanceState.getString("history_source_id", historySourceId);
            animeLoverzSlug = savedInstanceState.getString("animeloverz_slug", animeLoverzSlug);
            animeLoverzEpisodeUrl = savedInstanceState.getString("animeloverz_episode_url", animeLoverzEpisodeUrl);
            historySlug = savedInstanceState.getString("history_slug", historySlug);
            sokujaEpisodeSlug = savedInstanceState.getString("sokuja_episode_slug", sokujaEpisodeSlug);
            if (sokujaEpisodeSlug == null) sokujaEpisodeSlug = "";
            parseSokujaEpisodeList(savedInstanceState.getString("sokuja_episode_list"));
            sokujaEpisodeIndex = savedInstanceState.getInt("sokuja_episode_index", sokujaEpisodeIndex);
            syncSokujaEpisodeIndex();
            parseDrakorkuEpisodeList(savedInstanceState.getString("drakorku_episode_list"));
            if (historySourceId == null || historySourceId.trim().isEmpty() || !AnimeSettingsManager.isValidSource(historySourceId)) historySourceId = AnimeSettingsManager.SOURCE_ANIMEKU;
            if (historySlug == null) historySlug = "";
            if (historySlug.trim().isEmpty() && AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) historySlug = animeLoverzSlug;
        }

        hideSystemBars();
        initializePlayer();
        if (!disablePlaylist && !isSokuja()) {
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) loadAnimeLoverzPlaylist(); else loadEpisodePlaylist();
            loadEpisodeOrder();
        }
    }

    private void applyAutoLandscapeIfNeeded() {
        autoLandscapeActive = PlaybackQualityManager.isAutoLandscape(this);
        if (autoLandscapeActive) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if (AnimeSettingsManager.isPlayerStyleV2(this)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private void resetAutoLandscapeIfNeeded() {
        if (autoLandscapeActive) {
            autoLandscapeActive = false;
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    private boolean openExternalPlayerIfNeeded() {
        String player = PlaybackQualityManager.getPlayer(this);
        if (PlaybackQualityManager.PLAYER_INTERNAL.equals(player)) return false;
        if (!isAvailable(videoUrl)) return false;
        try {
            Intent intent = createExternalPlayerIntent(Uri.parse(videoUrl));
            String packageName = getPlayerPackage(player);
            if (packageName != null) intent.setPackage(packageName);
            if (PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) startActivity(Intent.createChooser(intent, "Pilih player anime"));
            else startActivity(intent);
            finish();
            return true;
        } catch (ActivityNotFoundException e) {
            if (!PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) {
                try {
                    Intent fallback = createExternalPlayerIntent(Uri.parse(videoUrl));
                    startActivity(Intent.createChooser(fallback, "Pilih player anime"));
                    finish();
                    return true;
                } catch (Exception ignored) { }
            }
            Toast.makeText(this, PlaybackQualityManager.getPlayerLabel(player) + " tidak ditemukan", Toast.LENGTH_SHORT).show();
            PlaybackQualityManager.setPlayer(this, PlaybackQualityManager.PLAYER_INTERNAL);
            return false;
        } catch (Exception e) {
            Toast.makeText(this, "Gagal membuka external player", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private Intent createExternalPlayerIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "video/*");
        intent.putExtra("title", videoTitle);
        intent.putExtra(Intent.EXTRA_TITLE, videoTitle);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private String getPlayerPackage(String player) {
        if (PlaybackQualityManager.PLAYER_VLC.equals(player)) return "org.videolan.vlc";
        if (PlaybackQualityManager.PLAYER_MPV.equals(player)) return "is.xyz.mpv";
        return null;
    }

    private void applyVideoScale(boolean showToast) {
        String scale = AnimeSettingsManager.getVideoScale(this);
        VideoScaleHelper.apply(playerView, player, scale);
        String label = AnimeSettingsManager.getVideoScaleLabel(scale);
        if (playerChrome != null) playerChrome.setScaleLabel(label);
        if (showToast) Toast.makeText(this, "Skala video: " + label, Toast.LENGTH_SHORT).show();
    }

    private void cycleVideoScale() {
        String next = AnimeSettingsManager.nextVideoScale(AnimeSettingsManager.getVideoScale(this));
        AnimeSettingsManager.setVideoScale(this, next);
        applyVideoScale(true);
    }

    private void initializePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(this).build();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        playerView.setUseController(false);
        playerView.setPlayer(player);
        playerView.setKeepScreenOn(true);
        player.addListener(new Player.Listener() {
            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                applyVideoScale(false);
            }
        });
        applyVideoScale(false);
        if (playerChrome != null) {
            playerChrome.attachPlayer(player);
            playerChrome.setTitle(categoryName, videoTitle);
            playerChrome.setQualityLabel(AnimeSettingsManager.SOURCE_DRAMORA.equals(historySourceId)
                    ? "Otomatis"
                    : (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId) || isSokuja())
                    ? selectedQualityLabel : PlaybackQualityManager.getQualityLabel(selectedQuality));
            playerChrome.setNavigationListener(this::handleNavigationUnavailable);
            playerChrome.setAvailabilityProvider(forward -> isSokuja() ? hasSokujaAdjacent(forward) : isDrakorku() ? drakorkuAdjacentIndex(forward) >= 0 : adjacentEpisodeId(forward) > 0);
            playerChrome.setActionListener(new AnimePlayerChrome.ActionListener() {
                @Override
                public void onQualityClick(View anchor) {
                    showQualityDialog(anchor);
                }

                @Override
                public void onSpeedClick(View anchor) {
                    showSpeedMenu(anchor);
                }

                @Override
                public void onScaleClick() {
                    cycleVideoScale();
                }

                @Override
                public void onRotateClick() {
                    toggleOrientation();
                }
            });
        }

        dataSourceFactory = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(videoHeaders());

        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            finish();
            return;
        }

        EpisodeMedia current = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl,
                extractEpisodeNumber(videoTitle), 0, animeLoverzEpisodeUrl);
        currentEpisodeMedia = current;
        player.setMediaSource(createMediaSource(current));
        player.setPlayWhenReady(playWhenReady);
        player.seekTo(playbackPosition);
        player.prepare();
        player.addListener(new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                if (oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    EpisodeMedia oldEpisode = currentEpisodeMedia;
                    try {
                        MediaItem oldItem = player.getMediaItemAt(oldPosition.mediaItemIndex);
                        if (oldItem.localConfiguration != null && oldItem.localConfiguration.tag instanceof EpisodeMedia) {
                            oldEpisode = (EpisodeMedia) oldItem.localConfiguration.tag;
                        }
                    } catch (Exception ignored) { }
                    long oldDuration = Math.max(0L, currentEpisodeDuration);
                    long oldWatched = Math.max(0L, oldPosition.positionMs);
                    if (oldDuration > 0 && reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && oldWatched < oldDuration - 5000L) oldWatched = oldDuration;
                    saveHistory(oldEpisode, oldWatched, oldDuration);
                }
            }

            @Override
            public void onMediaItemTransition(MediaItem mediaItem, int reason) {
                if (mediaItem != null && mediaItem.localConfiguration != null && mediaItem.localConfiguration.tag instanceof EpisodeMedia) {
                    EpisodeMedia episode = (EpisodeMedia) mediaItem.localConfiguration.tag;
                    currentEpisodeMedia = episode;
                    currentEpisodeDuration = 0L;
                    channelId = episode.channelId;
                    categoryId = episode.categoryId;
                    categoryName = episode.categoryName;
                    videoTitle = episode.title;
                    imageUrl = episode.imageUrl;
                    videoUrl = episode.videoUrl;
                    if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId) && hasText(episode.sourceEpisodeUrl)) {
                        animeLoverzEpisodeUrl = episode.sourceEpisodeUrl;
                    }
                    if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) {
                        selectedQualityLabel = qualityLabelFromTitle(episode.title, selectedQualityLabel);
                    }
                    playbackPosition = 0L;
                    setTitle(videoTitle);
                    if (playerChrome != null) {
                        playerChrome.setTitle(categoryName, videoTitle);
                        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) playerChrome.setQualityLabel(selectedQualityLabel);
                    }
                    showEpisodeChangedMessage(episode);
                    updateNavigationButtons();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (player != null) currentEpisodeDuration = Math.max(0L, player.getDuration());
                if (playbackState == Player.STATE_READY) prefetchQualityOptions();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Throwable cause = error.getCause();
                String detail = error.getErrorCodeName();
                if (cause != null && cause.getMessage() != null && !cause.getMessage().trim().isEmpty()) detail = detail + ": " + cause.getMessage().trim();
                Toast.makeText(AnimekuVideoPlayerActivity.this, "Video gagal diputar (" + detail + ")", Toast.LENGTH_LONG).show();
            }
        });
    }

    private View qualityMenuAnchor;

    private interface MenuPick {
        void onPick(int index);
    }

    private void showChoiceMenu(View anchor, String[] labels, int checked, MenuPick onPick) {
        if (anchor == null || isFinishing() || isDestroyed()) return;
        if (playerChrome != null) playerChrome.setPopupMenuVisible(true);
        PlayerChoiceMenu.show(this, anchor, labels, checked, onPick::onPick, () -> {
            if (playerChrome != null) playerChrome.setPopupMenuVisible(false);
            hideSystemBars();
        });
    }

    private void toggleOrientation() {
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (AnimeSettingsManager.isPlayerStyleV2(this)) {
            PlaybackQualityManager.setAutoLandscape(this, !landscape);
            autoLandscapeActive = !landscape;
        } else {
            autoLandscapeActive = false;
        }
        setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        if (playerChrome != null) playerChrome.setRotateState(!landscape);
    }

    private void showSpeedMenu(View anchor) {
        if (player == null || isFinishing()) return;
        float[] speeds = new float[]{0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
        String[] labels = new String[speeds.length];
        float current = player.getPlaybackParameters().speed;
        int checked = 2;
        for (int i = 0; i < speeds.length; i++) {
            labels[i] = String.format(java.util.Locale.US, "%.2fx", speeds[i]);
            if (Math.abs(current - speeds[i]) < 0.01f) checked = i;
        }
        showChoiceMenu(anchor, labels, checked, which -> player.setPlaybackParameters(new PlaybackParameters(speeds[which])));
    }

    private void showQualityDialog(View anchor) {
        if (player == null || isFinishing()) return;
        qualityMenuAnchor = anchor;
        if (AnimeSettingsManager.SOURCE_DRAMORA.equals(historySourceId)) {
            showDramoraQualityMenu();
            return;
        }
        if (AnimeSettingsManager.SOURCE_DRAKORKU.equals(historySourceId)) {
            loadDrakorkuQualityOptions();
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) {
            qualityMenuRequested = true;
            loadAnimekuQualityOptions();
            return;
        }
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) {
            qualityMenuRequested = true;
            loadAnimeLoverzQualityOptions();
            return;
        }
        if (isSokuja()) {
            qualityMenuRequested = true;
            loadSokujaQualityOptions();
            return;
        }
        String[] qualities = new String[]{PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_FHD};
        String[] labels = new String[qualities.length];
        int checked = 1;
        for (int i = 0; i < qualities.length; i++) {
            labels[i] = PlaybackQualityManager.getQualityLabel(qualities[i]);
            if (qualities[i].equals(selectedQuality)) checked = i;
        }
        showChoiceMenu(anchor, labels, checked, which -> changeQuality(qualities[which]));
    }

    private void loadSokujaQualityOptions() {
        if (sokujaEpisodeSlug == null || sokujaEpisodeSlug.trim().isEmpty()) {
            if (qualityMenuRequested) Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            qualityMenuRequested = false;
            return;
        }
        final String episodeSlug = sokujaEpisodeSlug;
        ArrayList<SokujaSource.StreamResult> cached = sokujaQualityCache.get(episodeSlug);
        if (cached != null && !cached.isEmpty()) {
            if (qualityMenuRequested) {
                qualityMenuRequested = false;
                showSokujaQualityMenu(cached);
            }
            return;
        }
        if (qualityOptionsLoading) {
            if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
            return;
        }
        qualityOptionsLoading = true;
        if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            ArrayList<SokujaSource.StreamResult> options = new ArrayList<>();
            String error = "";
            try {
                SokujaSource.VideoResult video = SokujaSource.video(episodeSlug);
                options.addAll(video.options);
            } catch (Exception e) {
                error = e.getMessage() == null ? "" : e.getMessage();
            }
            final ArrayList<SokujaSource.StreamResult> resolved = options;
            final String failure = error;
            runOnUiThread(() -> {
                qualityOptionsLoading = false;
                boolean show = qualityMenuRequested;
                qualityMenuRequested = false;
                if (!resolved.isEmpty()) sokujaQualityCache.put(episodeSlug, resolved);
                if (isFinishing() || isDestroyed() || !show) return;
                if (resolved.isEmpty()) {
                    Toast.makeText(this, failure.isEmpty() ? "Resolusi episode ini tidak tersedia" : "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
                    return;
                }
                showSokujaQualityMenu(resolved);
            });
        }, "sokuja-quality-list").start();
    }

    private void showSokujaQualityMenu(ArrayList<SokujaSource.StreamResult> source) {
        if (isFinishing() || isDestroyed() || source == null || source.isEmpty()) return;
        final ArrayList<SokujaSource.StreamResult> resolved = new ArrayList<>(source);
        java.util.Collections.sort(resolved, (a, b) -> Integer.compare(qualityHeight(b.quality), qualityHeight(a.quality)));
        String[] labels = new String[resolved.size()];
        int checked = 0;
        for (int i = 0; i < resolved.size(); i++) {
            labels[i] = resolved.get(i).label;
            if (resolved.get(i).label.equals(selectedQualityLabel)) checked = i;
        }
        showChoiceMenu(qualityMenuAnchor, labels, checked, which -> {
            SokujaSource.StreamResult chosen = resolved.get(which);
            selectedQuality = sokujaQualityKey(chosen.quality);
            PlaybackQualityManager.setQuality(this, selectedQuality);
            selectedQualityLabel = chosen.label;
            String baseTitle = videoTitle;
            int separator = baseTitle != null ? baseTitle.lastIndexOf(" • ") : -1;
            if (separator > 0) baseTitle = baseTitle.substring(0, separator);
            EpisodeMedia media = new EpisodeMedia(channelId, categoryId, categoryName, baseTitle + " • " + chosen.label, imageUrl, chosen.url, extractEpisodeNumber(videoTitle), 0);
            replaceCurrentMedia(media);
        });
    }

    private static int qualityHeight(String quality) {
        if (quality == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3,4})").matcher(quality);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int qualityKeyRank(String key) {
        if (PlaybackQualityManager.QUALITY_FHD.equals(key)) return 3;
        if (PlaybackQualityManager.QUALITY_HD.equals(key)) return 2;
        if (PlaybackQualityManager.QUALITY_SD.equals(key)) return 1;
        return 0;
    }

    private static void sortQualityBestFirst(ArrayList<String[]> options) {
        java.util.Collections.sort(options, (a, b) -> Integer.compare(qualityKeyRank(b[0]), qualityKeyRank(a[0])));
    }

    private void prefetchQualityOptions() {
        if (isFinishing() || isDestroyed() || qualityMenuRequested || qualityOptionsLoading) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastQualityPrefetchAt < 10_000L) return;
        lastQualityPrefetchAt = now;
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) loadAnimekuQualityOptions();
        else if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) loadAnimeLoverzQualityOptions();
        else if (isSokuja()) loadSokujaQualityOptions();
    }

    private static String sokujaQualityKey(String quality) {
        if ("1080p".equals(quality)) return PlaybackQualityManager.QUALITY_FHD;
        if ("720p".equals(quality)) return PlaybackQualityManager.QUALITY_HD;
        return PlaybackQualityManager.QUALITY_SD;
    }

    private void showDramoraQualityMenu() {
        showChoiceMenu(qualityMenuAnchor, new String[]{"Otomatis"}, 0, which -> {
        });
    }

    private void loadAnimekuQualityOptions() {
        if (requestQueue == null || channelId <= 0 || categoryId <= 0) {
            qualityMenuRequested = false;
            return;
        }
        final String cacheKey = "animeku:" + channelId + ":" + categoryId;
        ArrayList<String[]> cached = qualityOptionsCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            if (qualityMenuRequested) {
                qualityMenuRequested = false;
                showAnimekuQualityDialog(cached);
            }
            return;
        }
        if (qualityOptionsLoading) {
            if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
            return;
        }
        qualityOptionsLoading = true;
        if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
        String url = API_BASE + "get_post_detail_next_provious?id=" + channelId + "&cat_id=" + categoryId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            qualityOptionsLoading = false;
            boolean show = qualityMenuRequested;
            qualityMenuRequested = false;
            try {
                JSONObject json = new JSONObject(response);
                JSONObject post = "ok".equalsIgnoreCase(json.optString("status")) ? json.optJSONObject("post") : null;
                ArrayList<String[]> options = animekuQualityOptions(post);
                if (options.isEmpty()) {
                    if (show) Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                sortQualityBestFirst(options);
                qualityOptionsCache.put(cacheKey, options);
                if (show) showAnimekuQualityDialog(options);
            } catch (Exception e) {
                if (show) Toast.makeText(this, "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            qualityOptionsLoading = false;
            boolean show = qualityMenuRequested;
            qualityMenuRequested = false;
            if (show) Toast.makeText(this, "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private ArrayList<String[]> animekuQualityOptions(JSONObject post) {
        ArrayList<String[]> indonesian = new ArrayList<>();
        if (post == null) return indonesian;
        addAnimekuQualityOption(indonesian, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD), post.optString("video_url", ""));
        String hdUrl = firstAvailableAnimekuStream(post.optString("video_url_hd", ""), post.optString("video_url_minihd", ""));
        String hdLabel = isAvailableAnimekuStream(post.optString("video_url_hd", "")) ? PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD) : "Mini HD 480p";
        addAnimekuQualityOption(indonesian, PlaybackQualityManager.QUALITY_HD, hdLabel, hdUrl);
        addAnimekuQualityOption(indonesian, PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD), post.optString("video_url_fullhd", ""));
        if (!indonesian.isEmpty()) return indonesian;
        ArrayList<String[]> english = new ArrayList<>();
        addAnimekuQualityOption(english, PlaybackQualityManager.QUALITY_SD, "SD English", post.optString("video_url_eng", ""));
        addAnimekuQualityOption(english, PlaybackQualityManager.QUALITY_HD, "HD English", post.optString("video_url_hdeng", ""));
        return english;
    }

    private void addAnimekuQualityOption(ArrayList<String[]> options, String quality, String label, String url) {
        if (isAvailableAnimekuStream(url)) options.add(new String[]{quality, label});
    }

    private void showAnimekuQualityDialog(ArrayList<String[]> options) {
        if (player == null || isFinishing() || options == null || options.isEmpty()) return;
        String[] qualities = new String[options.size()];
        String[] labels = new String[options.size()];
        int checked = 0;
        for (int i = 0; i < options.size(); i++) {
            qualities[i] = options.get(i)[0];
            labels[i] = options.get(i)[1];
            if (qualities[i].equals(selectedQuality)) checked = i;
        }
        showChoiceMenu(qualityMenuAnchor, labels, checked, which -> changeQuality(qualities[which]));
    }

    private void loadAnimeLoverzQualityOptions() {
        String episodeUrl = currentEpisodeMedia != null && hasText(currentEpisodeMedia.sourceEpisodeUrl)
                ? currentEpisodeMedia.sourceEpisodeUrl : animeLoverzEpisodeUrl;
        if (!hasText(episodeUrl) || !hasText(animeLoverzSlug)) {
            if (qualityMenuRequested) Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            qualityMenuRequested = false;
            return;
        }
        final String cacheKey = "loverz:" + episodeUrl;
        ArrayList<String[]> cached = qualityOptionsCache.get(cacheKey);
        if (cached != null && !cached.isEmpty()) {
            if (qualityMenuRequested) {
                qualityMenuRequested = false;
                showAnimeLoverzQualityDialog(cached);
            }
            return;
        }
        if (qualityOptionsLoading) {
            if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
            return;
        }
        qualityOptionsLoading = true;
        loverzQualityKey = cacheKey;
        if (qualityMenuRequested) Toast.makeText(this, "Memuat resolusi...", Toast.LENGTH_SHORT).show();
        loadAnimeLoverzQualityOptionsVariant(animeLoverzEpisodeRequestVariants(episodeUrl, animeLoverzSlug), 0);
    }

    private void loadAnimeLoverzQualityOptionsVariant(ArrayList<String[]> attempts, int index) {
        if (attempts == null || index >= attempts.size()) {
            qualityOptionsLoading = false;
            boolean show = qualityMenuRequested;
            qualityMenuRequested = false;
            if (show && !isFinishing() && !isDestroyed()) Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        String episodeUrl = attempts.get(index)[0];
        String seriesSlug = attempts.get(index)[1];
        String url = ANIMELOVERZ_API_BASE + "/series/episode/data.php?url=" + Uri.encode(episodeUrl);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            if (isFinishing() || isDestroyed()) {
                qualityOptionsLoading = false;
                qualityMenuRequested = false;
                return;
            }
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstAnimeLoverzDataObject(json);
                ArrayList<String[]> options = animeLoverzQualityOptions(item == null ? null : item.optJSONObject("streams"));
                if (options.isEmpty()) {
                    loadAnimeLoverzQualityOptionsVariant(attempts, index + 1);
                    return;
                }
                qualityOptionsLoading = false;
                sortQualityBestFirst(options);
                qualityOptionsCache.put(loverzQualityKey, options);
                boolean show = qualityMenuRequested;
                qualityMenuRequested = false;
                if (show) showAnimeLoverzQualityDialog(options);
            } catch (Exception e) {
                Log.e(TAG, "Gagal membaca resolusi Animeloverz", e);
                loadAnimeLoverzQualityOptionsVariant(attempts, index + 1);
            }
        }, error -> {
            Log.w(TAG, "Gagal meminta resolusi Animeloverz", error);
            loadAnimeLoverzQualityOptionsVariant(attempts, index + 1);
        }) {
            @Override public Map<String, String> getHeaders() { return animeLoverzHeaders(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                int episodeNumber = extractEpisodeNumber(videoTitle);
                String episode = episodeNumber == Integer.MAX_VALUE ? "" : String.valueOf(episodeNumber);
                String body = "{\"post_type\":\"2\",\"post_id\":\"" + escapeJson(episodeUrl)
                        + "\",\"series_id\":\"" + escapeJson(seriesSlug)
                        + "\",\"series_url\":\"" + escapeJson(seriesSlug)
                        + "\",\"episode\":\"" + escapeJson(episode) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private ArrayList<String[]> animeLoverzQualityOptions(JSONObject streams) {
        ArrayList<String[]> options = new ArrayList<>();
        String low = firstAnimeLoverzStream(streams, "480p");
        String lowLabel = "480p";
        if (!isAvailable(low)) {
            low = firstAnimeLoverzStream(streams, "360p");
            lowLabel = "360p";
        }
        addAnimeLoverzQuality(options, PlaybackQualityManager.QUALITY_SD, lowLabel, low);
        addAnimeLoverzQuality(options, PlaybackQualityManager.QUALITY_HD, "720p", firstAnimeLoverzStream(streams, "720p"));
        addAnimeLoverzQuality(options, PlaybackQualityManager.QUALITY_FHD, "1080p", firstAnimeLoverzStream(streams, "1080p"));
        return options;
    }

    private void addAnimeLoverzQuality(ArrayList<String[]> options, String quality, String label, String url) {
        if (!isAvailable(url)) return;
        String normalized = url.trim();
        for (String[] existing : options) {
            if (normalized.equals(existing[2])) return;
        }
        options.add(new String[]{quality, label, normalized});
    }

    private void showAnimeLoverzQualityDialog(ArrayList<String[]> options) {
        if (player == null || isFinishing() || options == null || options.isEmpty()) return;
        if (options.size() == 1) {
            String[] only = options.get(0);
            if (!only[2].equals(videoUrl)) applyAnimeLoverzQuality(only);
            else {
                selectedQualityLabel = only[1];
                if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
                Toast.makeText(this, "Hanya tersedia " + selectedQualityLabel, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String[] labels = new String[options.size()];
        int checked = 0;
        boolean foundUrl = false;
        for (int i = 0; i < options.size(); i++) {
            String[] option = options.get(i);
            labels[i] = option[1];
            if (option[2].equals(videoUrl)) {
                checked = i;
                foundUrl = true;
            } else if (!foundUrl && option[0].equals(selectedQuality)) {
                checked = i;
            }
        }
        showChoiceMenu(qualityMenuAnchor, labels, checked, which -> applyAnimeLoverzQuality(options.get(which)));
    }

    private void applyAnimeLoverzQuality(String[] option) {
        if (option == null || option.length < 3 || !isAvailable(option[2])) return;
        selectedQuality = option[0];
        selectedQualityLabel = option[1];
        PlaybackQualityManager.setQuality(this, selectedQuality);
        if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
        if (option[2].equals(videoUrl)) return;

        String rawTitle = stripQualitySuffix(videoTitle);
        String title = rawTitle + " • " + selectedQualityLabel;
        String sourceEpisodeUrl = currentEpisodeMedia != null && hasText(currentEpisodeMedia.sourceEpisodeUrl)
                ? currentEpisodeMedia.sourceEpisodeUrl : animeLoverzEpisodeUrl;
        int sourceIndex = currentEpisodeMedia == null ? 0 : currentEpisodeMedia.sourceIndex;
        EpisodeMedia media = new EpisodeMedia(channelId, categoryId, categoryName, title, imageUrl,
                option[2], extractEpisodeNumber(rawTitle), sourceIndex, sourceEpisodeUrl);
        animeLoverzEpisodeUrl = sourceEpisodeUrl;
        replaceCurrentMedia(media);
        playlistLoaded = false;
        refreshingQuality = false;
        loadAnimeLoverzPlaylist();
    }

    private String stripQualitySuffix(String title) {
        String value = title == null ? "" : title.trim();
        int separator = value.lastIndexOf(" • ");
        return separator > 0 ? value.substring(0, separator).trim() : value;
    }

    private String qualityLabelFromTitle(String title, String fallback) {
        String value = title == null ? "" : title.trim();
        int separator = value.lastIndexOf(" • ");
        return separator >= 0 && separator + 3 < value.length() ? value.substring(separator + 3).trim() : fallback;
    }

    private void loadDrakorkuQualityOptions() {
        if (channelId <= 0 || categoryId <= 0 || qualityOptionsLoading) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        qualityOptionsLoading = true;
        new Thread(() -> {
            ArrayList<Drakorku.QualityResult> options = new ArrayList<>();
            String error = "";
            try {
                options = Drakorku.playback(String.valueOf(channelId), categoryId, categoryName);
            } catch (Exception e) {
                error = e.getMessage() == null ? "" : e.getMessage();
            }
            final ArrayList<Drakorku.QualityResult> resolved = options;
            final String failure = error;
            runOnUiThread(() -> {
                qualityOptionsLoading = false;
                if (isFinishing() || isDestroyed()) return;
                if (resolved.isEmpty()) {
                    Toast.makeText(this, failure.isEmpty() ? "Resolusi episode ini tidak tersedia" : "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
                    return;
                }
                showDrakorkuQualityDialog(resolved);
            });
        }, "drakorku-quality-list").start();
    }

    private void showDrakorkuQualityDialog(ArrayList<Drakorku.QualityResult> options) {
        if (player == null || isFinishing() || options == null || options.isEmpty()) return;
        String[] labels = new String[options.size()];
        int checked = 0;
        for (int i = 0; i < options.size(); i++) {
            labels[i] = options.get(i).label;
            if (options.get(i).quality.equals(selectedQuality)) checked = i;
        }
        showChoiceMenu(qualityMenuAnchor, labels, checked, which -> changeQuality(options.get(which).quality));
    }

    private void refreshDrakorkuQuality() {
        if (channelId <= 0 || categoryId <= 0 || refreshingQuality) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshingQuality = true;
        new Thread(() -> {
            ArrayList<Drakorku.QualityResult> options = new ArrayList<>();
            try { options = Drakorku.playback(String.valueOf(channelId), categoryId, categoryName); }
            catch (Exception ignored) { }
            final ArrayList<Drakorku.QualityResult> resolved = options;
            runOnUiThread(() -> {
                refreshingQuality = false;
                if (isFinishing() || isDestroyed()) return;
                if (resolved.isEmpty()) {
                    Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
                    return;
                }
                Drakorku.QualityResult chosen = null;
                for (Drakorku.QualityResult option : resolved) if (option.quality.equals(selectedQuality)) { chosen = option; break; }
                if (chosen == null) {
                    chosen = resolved.get(0);
                    selectedQuality = chosen.quality;
                    PlaybackQualityManager.setQuality(this, selectedQuality);
                }
                EpisodeMedia media = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, chosen.url, extractEpisodeNumber(videoTitle), 0);
                replaceCurrentMedia(media);
                playlistLoaded = false;
            });
        }, "drakorku-quality-switch").start();
    }


    private void changeQuality(String quality) {
        if (quality == null || quality.equals(selectedQuality)) return;
        selectedQuality = quality;
        PlaybackQualityManager.setQuality(this, selectedQuality);
        if (playerChrome != null) playerChrome.setQualityLabel(PlaybackQualityManager.getQualityLabel(selectedQuality));
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) refreshAnimeLoverzQuality();
        else if (AnimeSettingsManager.SOURCE_DRAKORKU.equals(historySourceId)) refreshDrakorkuQuality();
        else fetchAndReplaceCurrentQuality();
    }

    private void refreshAnimeLoverzQuality() {
        if (animeLoverzSlug == null || animeLoverzSlug.trim().isEmpty()) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        refreshingQuality = true;
        playlistLoaded = false;
        Toast.makeText(this, "Mengubah ke " + PlaybackQualityManager.getQualityLabel(selectedQuality), Toast.LENGTH_SHORT).show();
        loadAnimeLoverzPlaylist();
    }

    private void fetchAndReplaceCurrentQuality() {
        if (isSokuja()) {
            loadSokujaQualityOptions();
            return;
        }
        if (requestQueue == null || channelId <= 0 || categoryId <= 0) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        String url = API_BASE + "get_post_detail_next_provious?id=" + channelId + "&cat_id=" + categoryId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                JSONObject post = json.optJSONObject("post");
                if (post == null) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                EpisodeMedia episode = buildEpisodeFromJson(post, 0);
                if (episode == null || !isAvailable(episode.videoUrl)) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                replaceCurrentMedia(episode);
                playlistLoaded = false;
                loadEpisodePlaylist();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show()) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private EpisodeMedia buildEpisodeFromJson(JSONObject json, int index) {
        if (json == null) return null;
        int resolvedChannelId = json.optInt("vid", channelId);
        int resolvedCategoryId = json.optInt("cat_id", categoryId);
        String resolvedUrl = resolveVideoUrl(json);
        if (!isAvailable(resolvedUrl)) return null;
        String rawTitle = json.optString("video_title", videoTitle);
        String label = qualityLabelForResolved(json, resolvedUrl);
        String title = rawTitle + " • " + label;
        String img = imageUrl(firstAvailableText(json.optString("category_image", ""), json.optString("video_thumbnail", ""), imageUrl));
        String name = json.optString("category_name", categoryName);
        return new EpisodeMedia(resolvedChannelId, resolvedCategoryId, name, title, img, resolvedUrl, extractEpisodeNumber(rawTitle), index);
    }

    private void replaceCurrentMedia(EpisodeMedia episode) {
        if (player == null || episode == null || !isAvailable(episode.videoUrl)) return;
        long pos = Math.max(0L, player.getCurrentPosition());
        boolean ready = player.getPlayWhenReady();
        playlistReady = false;
        currentEpisodeMedia = episode;
        channelId = episode.channelId;
        categoryId = episode.categoryId;
        categoryName = episode.categoryName;
        videoTitle = episode.title;
        imageUrl = episode.imageUrl;
        videoUrl = episode.videoUrl;
        if (playerChrome != null) {
            playerChrome.setTitle(categoryName, videoTitle);
            playerChrome.setQualityLabel(AnimeSettingsManager.SOURCE_DRAMORA.equals(historySourceId)
                    ? "Otomatis"
                    : (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId) || isSokuja())
                    ? selectedQualityLabel : PlaybackQualityManager.getQualityLabel(selectedQuality));
        }
        player.setMediaSource(createMediaSource(episode), pos);
        player.setPlayWhenReady(ready);
        player.prepare();
        Toast.makeText(this, (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId) || isSokuja())
                ? selectedQualityLabel : PlaybackQualityManager.getQualityLabel(selectedQuality), Toast.LENGTH_SHORT).show();
    }

    private MediaSource createMediaSource(EpisodeMedia episode) {
        MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.parse(episode.videoUrl)).setTag(episode).build();
        return new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
    }

    private void reloadPlaylist() {
        if (disablePlaylist || isSokuja()) return;
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) loadAnimeLoverzPlaylist(); else loadEpisodePlaylist();
    }

    private void loadEpisodeOrder() {
        if (isSokuja()) return;
        if (episodeOrderLoaded || disablePlaylist || categoryId <= 0 || requestQueue == null) return;
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) return;
        episodeOrderLoaded = true;
        String url = API_BASE + "get_anime_detail?id=" + categoryId + "&api_key=" + ANIMEKU_API_KEY;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONArray suggested = json.optJSONArray("suggested");
                if (suggested == null || suggested.length() == 0) {
                    episodeOrderLoaded = false;
                    return;
                }
                ArrayList<int[]> rows = new ArrayList<>();
                ArrayList<Integer> seen = new ArrayList<>();
                for (int i = 0; i < suggested.length(); i++) {
                    JSONObject item = suggested.optJSONObject(i);
                    if (item == null) continue;
                    int id = item.optInt("vid", -1);
                    if (id <= 0 || seen.contains(id)) continue;
                    seen.add(id);
                    rows.add(new int[]{id, extractEpisodeNumber(item.optString("video_title", "")), i});
                }
                Collections.sort(rows, (x, y) -> x[1] != y[1] ? Integer.compare(x[1], y[1]) : Integer.compare(x[2], y[2]));
                episodeOrderRows.clear();
                episodeOrderRows.addAll(rows);
            } catch (Exception e) {
                Log.e(TAG, "Episode order parse error", e);
                episodeOrderLoaded = false;
            }
        }, error -> {
            Log.e(TAG, "Episode order network error", error);
            episodeOrderLoaded = false;
        }) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private int currentEpisodeNumber() {
        if (currentEpisodeMedia != null && currentEpisodeMedia.episodeNumber != Integer.MAX_VALUE) return currentEpisodeMedia.episodeNumber;
        return extractEpisodeNumber(videoTitle);
    }

    private boolean orderKnown() {
        if (episodeOrderRows.isEmpty()) return false;
        if (currentEpisodeNumber() != Integer.MAX_VALUE) return true;
        for (int[] row : episodeOrderRows) if (row[0] == channelId) return true;
        return false;
    }

    private int adjacentEpisodeId(boolean next) {
        if (episodeOrderRows.isEmpty()) return -1;
        int currentNumber = currentEpisodeNumber();
        if (currentNumber != Integer.MAX_VALUE) {
            if (next) {
                for (int[] row : episodeOrderRows) if (row[1] != Integer.MAX_VALUE && row[1] > currentNumber) return row[0];
                return -1;
            }
            int result = -1;
            for (int[] row : episodeOrderRows) if (row[1] < currentNumber) result = row[0];
            return result;
        }
        int index = -1;
        for (int i = 0; i < episodeOrderRows.size(); i++) {
            if (episodeOrderRows.get(i)[0] == channelId) {
                index = i;
                break;
            }
        }
        if (index < 0) return -1;
        int target = index + (next ? 1 : -1);
        return target >= 0 && target < episodeOrderRows.size() ? episodeOrderRows.get(target)[0] : -1;
    }

    private void loadAdjacentEpisode(int targetId) {
        if (requestQueue == null) return;
        navigating = true;
        Toast.makeText(this, "Memuat episode...", Toast.LENGTH_SHORT).show();
        if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)) {
            AnimeLoverzEpisodeMeta target = null;
            for (AnimeLoverzEpisodeMeta meta : animeLoverzMetas) {
                if (meta.id == targetId) {
                    target = meta;
                    break;
                }
            }
            if (target == null) {
                navigating = false;
                Toast.makeText(this, "Episode tidak tersedia", Toast.LENGTH_SHORT).show();
                return;
            }
            ArrayList<String[]> attempts = animeLoverzEpisodeRequestVariants(target.url, animeLoverzSlug);
            fetchAnimeLoverzPlaylistItem(target, attempts, 0, new ArrayList<>(), new AtomicInteger(1), episode -> {
                navigating = false;
                if (isFinishing() || isDestroyed()) return;
                if (episode == null) {
                    Toast.makeText(this, "Episode tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                switchToEpisode(episode);
            });
            return;
        }
        String url = API_BASE + "get_post_detail?id=" + targetId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            navigating = false;
            if (isFinishing() || isDestroyed()) return;
            try {
                JSONObject json = new JSONObject(response);
                ArrayList<EpisodeMedia> found = new ArrayList<>();
                addEpisodeMedia(found, json.optJSONObject("post"), 0);
                if (found.isEmpty()) {
                    Toast.makeText(this, "Episode tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                switchToEpisode(found.get(0));
            } catch (Exception e) {
                Log.e(TAG, "Adjacent episode parse error", e);
                Toast.makeText(this, "Gagal memuat episode", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            navigating = false;
            if (!isFinishing() && !isDestroyed()) Toast.makeText(this, "Gagal memuat episode", Toast.LENGTH_SHORT).show();
        }) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private boolean isSokuja() {
        return AnimeSettingsManager.SOURCE_SOKUJA.equals(
                AnimeSettingsManager.normalizeSourceId(historySourceId));
    }

    private void parseSokujaEpisodeList(String json) {
        sokujaEpisodeSlugs.clear();
        sokujaEpisodeLabels.clear();
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String slug = item.optString("slug", "").trim();
                if (slug.isEmpty() || sokujaEpisodeSlugs.contains(slug)) continue;
                String label = item.optString("label", "").trim();
                sokujaEpisodeSlugs.add(slug);
                sokujaEpisodeLabels.add(label.isEmpty() ? "Episode" : label);
            }
        } catch (Exception ignored) {
            sokujaEpisodeSlugs.clear();
            sokujaEpisodeLabels.clear();
        }
    }

    private String sokujaEpisodeListJson() {
        JSONArray array = new JSONArray();
        for (int i = 0; i < sokujaEpisodeSlugs.size(); i++) {
            try {
                array.put(new JSONObject().put("slug", sokujaEpisodeSlugs.get(i)).put("label", sokujaEpisodeLabels.get(i)));
            } catch (Exception ignored) { }
        }
        return array.toString();
    }

    private void syncSokujaEpisodeIndex() {
        if (sokujaEpisodeSlug != null && !sokujaEpisodeSlug.trim().isEmpty()) {
            int found = sokujaEpisodeSlugs.indexOf(sokujaEpisodeSlug.trim());
            sokujaEpisodeIndex = found;
            return;
        }
        if (sokujaEpisodeIndex < 0 || sokujaEpisodeIndex >= sokujaEpisodeSlugs.size()) sokujaEpisodeIndex = -1;
    }

    private int sokujaAdjacentIndex(boolean next) {
        if (sokujaEpisodeIndex < 0 || sokujaEpisodeIndex >= sokujaEpisodeSlugs.size()) return -1;
        int target = sokujaEpisodeIndex + (next ? 1 : -1);
        return target >= 0 && target < sokujaEpisodeSlugs.size() ? target : -1;
    }

    private boolean hasSokujaAdjacent(boolean next) {
        return sokujaAdjacentIndex(next) >= 0;
    }

    private void loadSokujaAdjacentEpisode(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= sokujaEpisodeSlugs.size() || navigating) return;
        navigating = true;
        Toast.makeText(this, "Memuat episode...", Toast.LENGTH_SHORT).show();
        final String targetSlug = sokujaEpisodeSlugs.get(targetIndex);
        final String targetLabel = sokujaEpisodeLabels.get(targetIndex);
        final String wantedQuality = selectedQuality;
        new Thread(() -> {
            ArrayList<SokujaSource.StreamResult> options = new ArrayList<>();
            String streamUrl = "";
            String error = "";
            try {
                SokujaSource.VideoResult video = SokujaSource.video(targetSlug);
                options.addAll(video.options);
                streamUrl = video.streamUrl == null ? "" : video.streamUrl;
            } catch (Exception e) {
                error = e.getMessage() == null ? "" : e.getMessage();
            }
            final ArrayList<SokujaSource.StreamResult> resolved = options;
            final String resolvedStreamUrl = streamUrl;
            final String failure = error;
            runOnUiThread(() -> {
                navigating = false;
                if (isFinishing() || isDestroyed() || player == null) return;
                if (resolved.isEmpty() && resolvedStreamUrl.isEmpty()) {
                    Toast.makeText(this, failure.isEmpty() ? "Episode tidak tersedia" : "Gagal memuat episode", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!resolved.isEmpty()) sokujaQualityCache.put(targetSlug, new ArrayList<>(resolved));
                SokujaSource.StreamResult chosen = null;
                for (SokujaSource.StreamResult option : resolved) {
                    if (sokujaQualityKey(option.quality).equals(wantedQuality)) {
                        chosen = option;
                        break;
                    }
                }
                if (chosen == null && !resolvedStreamUrl.isEmpty()) {
                    for (SokujaSource.StreamResult option : resolved) {
                        if (resolvedStreamUrl.equals(option.url)) {
                            chosen = option;
                            break;
                        }
                    }
                }
                String url;
                String label;
                String qualityKey;
                if (chosen != null) {
                    url = chosen.url;
                    label = chosen.label;
                    qualityKey = sokujaQualityKey(chosen.quality);
                } else if (!resolvedStreamUrl.isEmpty()) {
                    url = resolvedStreamUrl;
                    label = selectedQualityLabel;
                    qualityKey = wantedQuality;
                } else {
                    SokujaSource.StreamResult first = resolved.get(0);
                    url = first.url;
                    label = first.label;
                    qualityKey = sokujaQualityKey(first.quality);
                }
                selectedQuality = qualityKey;
                PlaybackQualityManager.setQuality(this, qualityKey);
                selectedQualityLabel = label;
                sokujaEpisodeSlug = targetSlug;
                sokujaEpisodeIndex = targetIndex;
                EpisodeMedia media = new EpisodeMedia(positiveId(targetSlug), categoryId, categoryName,
                        targetLabel + " • " + label, imageUrl, url, extractEpisodeNumber(targetLabel), 0);
                switchToEpisode(media);
            });
        }, "sokuja-adjacent-episode").start();
    }

    private boolean isDrakorku() {
        return AnimeSettingsManager.SOURCE_DRAKORKU.equals(historySourceId);
    }

    private void parseDrakorkuEpisodeList(String json) {
        drakorkuEpisodeIds.clear();
        drakorkuEpisodeLabels.clear();
        if (json == null || json.trim().isEmpty()) return;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                int id = item.optInt("id", -1);
                if (id <= 0 || drakorkuEpisodeIds.contains(id)) continue;
                String label = item.optString("label", "").trim();
                drakorkuEpisodeIds.add(id);
                drakorkuEpisodeLabels.add(label.isEmpty() ? "Episode" : label);
            }
        } catch (Exception ignored) {
            drakorkuEpisodeIds.clear();
            drakorkuEpisodeLabels.clear();
        }
    }

    private String drakorkuEpisodeListJson() {
        JSONArray array = new JSONArray();
        for (int i = 0; i < drakorkuEpisodeIds.size(); i++) {
            try {
                array.put(new JSONObject().put("id", drakorkuEpisodeIds.get(i)).put("label", drakorkuEpisodeLabels.get(i)));
            } catch (Exception ignored) { }
        }
        return array.toString();
    }

    private int drakorkuAdjacentIndex(boolean next) {
        int current = drakorkuEpisodeIds.indexOf(channelId);
        if (current < 0) return -1;
        int target = current + (next ? 1 : -1);
        return target >= 0 && target < drakorkuEpisodeIds.size() ? target : -1;
    }

    private void loadDrakorkuAdjacentEpisode(int targetIndex) {
        if (targetIndex < 0 || targetIndex >= drakorkuEpisodeIds.size() || navigating) return;
        navigating = true;
        Toast.makeText(this, "Memuat episode...", Toast.LENGTH_SHORT).show();
        final int targetId = drakorkuEpisodeIds.get(targetIndex);
        final String targetLabel = drakorkuEpisodeLabels.get(targetIndex);
        final String wantedQuality = selectedQuality;
        final int targetCategoryId = categoryId;
        final String targetCategoryName = categoryName;
        new Thread(() -> {
            ArrayList<Drakorku.QualityResult> options = new ArrayList<>();
            String error = "";
            try {
                options = Drakorku.playback(String.valueOf(targetId), targetCategoryId, targetCategoryName);
            } catch (Exception e) {
                error = e.getMessage() == null ? "" : e.getMessage();
            }
            final ArrayList<Drakorku.QualityResult> resolved = options;
            final String failure = error;
            runOnUiThread(() -> {
                navigating = false;
                if (isFinishing() || isDestroyed() || player == null) return;
                if (resolved.isEmpty()) {
                    Toast.makeText(this, failure.isEmpty() ? "Episode tidak tersedia" : "Gagal memuat episode", Toast.LENGTH_SHORT).show();
                    return;
                }
                Drakorku.QualityResult chosen = null;
                for (Drakorku.QualityResult option : resolved) {
                    if (option.quality.equals(wantedQuality)) {
                        chosen = option;
                        break;
                    }
                }
                if (chosen == null) chosen = resolved.get(0);
                selectedQuality = chosen.quality;
                EpisodeMedia media = new EpisodeMedia(targetId, targetCategoryId, targetCategoryName,
                        targetLabel + " • " + chosen.label, imageUrl, chosen.url, extractEpisodeNumber(targetLabel), 0);
                switchToEpisode(media);
                if (playerChrome != null) playerChrome.setQualityLabel(PlaybackQualityManager.getQualityLabel(selectedQuality));
            });
        }, "drakorku-adjacent-episode").start();
    }

    private void switchToEpisode(EpisodeMedia episode) {
        if (player == null || episode == null || !isAvailable(episode.videoUrl)) return;
        saveHistory();
        playlistReady = false;
        playlistLoaded = false;
        playlistRetry = 0;
        currentEpisodeMedia = episode;
        currentEpisodeDuration = 0L;
        channelId = episode.channelId;
        categoryId = episode.categoryId;
        categoryName = episode.categoryName;
        videoTitle = episode.title;
        imageUrl = episode.imageUrl;
        videoUrl = episode.videoUrl;
        playbackPosition = 0L;
        boolean loverz = AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId);
        if (loverz) {
            if (hasText(episode.sourceEpisodeUrl)) animeLoverzEpisodeUrl = episode.sourceEpisodeUrl;
            selectedQualityLabel = qualityLabelFromTitle(episode.title, selectedQualityLabel);
        }
        setTitle(videoTitle);
        if (playerChrome != null) {
            playerChrome.setTitle(categoryName, videoTitle);
            if (loverz || isSokuja()) playerChrome.setQualityLabel(selectedQualityLabel);
        }
        player.setMediaSource(createMediaSource(episode));
        player.setPlayWhenReady(true);
        player.prepare();
        updateNavigationButtons();
        reloadPlaylist();
    }

    private void handleNavigationUnavailable(boolean next) {
        if (isDrakorku()) {
            if (navigating) return;
            int target = drakorkuAdjacentIndex(next);
            if (target >= 0) {
                loadDrakorkuAdjacentEpisode(target);
                return;
            }
            if (drakorkuEpisodeIds.contains(channelId)) {
                Toast.makeText(this, next ? "Ini episode terakhir" : "Ini episode pertama", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Daftar episode tidak tersedia, buka episode dari detail", Toast.LENGTH_SHORT).show();
            return;
        }
        if (disablePlaylist) {
            Toast.makeText(this, "Navigasi episode tidak tersedia untuk sumber ini", Toast.LENGTH_SHORT).show();
            return;
        }
        if (navigating) return;
        if (isSokuja()) {
            int target = sokujaAdjacentIndex(next);
            if (target >= 0) {
                loadSokujaAdjacentEpisode(target);
                return;
            }
            if (!sokujaEpisodeSlugs.isEmpty() && sokujaEpisodeIndex >= 0) {
                Toast.makeText(this, next ? "Ini episode terakhir" : "Ini episode pertama", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Daftar episode tidak tersedia, buka episode dari detail", Toast.LENGTH_SHORT).show();
            return;
        }
        int targetId = adjacentEpisodeId(next);
        if (targetId > 0) {
            loadAdjacentEpisode(targetId);
            return;
        }
        if (orderKnown()) {
            Toast.makeText(this, next ? "Ini episode terakhir" : "Ini episode pertama", Toast.LENGTH_SHORT).show();
            return;
        }
        loadEpisodeOrder();
        if (playlistReady) {
            Toast.makeText(this, next ? "Ini episode terakhir" : "Ini episode pertama", Toast.LENGTH_SHORT).show();
            return;
        }
        if (playlistLoading) {
            Toast.makeText(this, "Daftar episode sedang dimuat, coba lagi sebentar", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Memuat daftar episode...", Toast.LENGTH_SHORT).show();
        playlistRetry = 0;
        playlistLoaded = false;
        reloadPlaylist();
    }

    private void onPlaylistFailed() {
        playlistLoaded = false;
        playlistLoading = false;
        if (disablePlaylist || playlistRetry >= 3 || isFinishing() || isDestroyed() || playerView == null) return;
        playlistRetry++;
        playerView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && !playlistLoaded && !playlistReady) reloadPlaylist();
        }, 1500L * playlistRetry);
    }

    private void loadAnimeLoverzPlaylist() {
        if (playlistLoaded || channelId <= 0 || requestQueue == null) return;
        if (animeLoverzSlug == null || animeLoverzSlug.trim().isEmpty()) return;
        playlistLoaded = true;
        playlistLoading = true;
        ArrayList<String> variants = animeLoverzSlugVariants(animeLoverzSlug);
        loadAnimeLoverzPlaylistVariant(variants, 0);
    }

    private void loadAnimeLoverzPlaylistVariant(ArrayList<String> variants, int index) {
        if (variants == null || index >= variants.size()) {
            onPlaylistFailed();
            return;
        }
        String slug = variants.get(index);
        String url = ANIMELOVERZ_API_BASE + "/series.php?url=" + Uri.encode(slug);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstAnimeLoverzDataObject(json);
                JSONArray chapters = item == null ? null : firstAnimeLoverzArray(item, "chapter", "episodes", "episode", "episode_list", "daftar_episode", "list_episode");
                ArrayList<AnimeLoverzEpisodeMeta> metas = new ArrayList<>();
                if (chapters != null) {
                    for (int i = 0; i < chapters.length(); i++) {
                        JSONObject chapter = chapters.optJSONObject(i);
                        if (chapter == null) continue;
                        String episodeUrl = firstAvailableText(chapter.optString("url", ""), chapter.optString("link", ""), chapter.optString("slug", ""), chapter.optString("permalink", ""));
                        if (episodeUrl.isEmpty()) continue;
                        int id = chapter.optInt("id", positiveId(episodeUrl));
                        String ch = firstAvailableText(chapter.optString("ch", ""), chapter.optString("episode", ""), chapter.optString("eps", ""), chapter.optString("title", ""), chapter.optString("name", ""));
                        String label = ch.isEmpty() ? "Episode" : ch.toLowerCase(java.util.Locale.US).contains("episode") ? ch : "Episode " + ch;
                        metas.add(new AnimeLoverzEpisodeMeta(id, episodeUrl, ch, label, i));
                    }
                }
                if (metas.isEmpty()) {
                    loadAnimeLoverzPlaylistVariant(variants, index + 1);
                    return;
                }
                animeLoverzSlug = slug;
                animeLoverzMetas.clear();
                animeLoverzMetas.addAll(metas);
                ArrayList<int[]> loverzRows = new ArrayList<>();
                for (AnimeLoverzEpisodeMeta meta : metas) loverzRows.add(new int[]{meta.id, extractEpisodeNumber(meta.label), meta.index});
                Collections.sort(loverzRows, (x, y) -> x[1] != y[1] ? Integer.compare(x[1], y[1]) : Integer.compare(x[2], y[2]));
                episodeOrderRows.clear();
                episodeOrderRows.addAll(loverzRows);
                if (metas.size() <= 1) {
                    playlistLoading = false;
                    return;
                }
                fetchAnimeLoverzPlaylistItems(metas);
            } catch (Exception e) {
                Log.e(TAG, "Animeloverz playlist parse error", e);
                loadAnimeLoverzPlaylistVariant(variants, index + 1);
            }
        }, error -> {
            Log.e(TAG, "Animeloverz playlist network error", error);
            loadAnimeLoverzPlaylistVariant(variants, index + 1);
        }) {
            @Override public Map<String, String> getHeaders() { return animeLoverzHeaders(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                String body = "{\"get\":\"top\",\"post_type\":\"1\",\"post_id\":\"" + escapeJson(slug) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void fetchAnimeLoverzPlaylistItems(ArrayList<AnimeLoverzEpisodeMeta> metas) {
        ArrayList<EpisodeMedia> result = new ArrayList<>();
        if (!refreshingQuality && currentEpisodeMedia != null && currentEpisodeMedia.channelId > 0 && isAvailable(currentEpisodeMedia.videoUrl)) result.add(currentEpisodeMedia);
        else if (!refreshingQuality && channelId > 0 && isAvailable(videoUrl)) result.add(new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0, animeLoverzEpisodeUrl));
        AtomicInteger remaining = new AtomicInteger(metas.size());
        for (AnimeLoverzEpisodeMeta meta : metas) {
            ArrayList<String[]> attempts = animeLoverzEpisodeRequestVariants(meta.url, animeLoverzSlug);
            fetchAnimeLoverzPlaylistItem(meta, attempts, 0, result, remaining, null);
        }
    }

    private void fetchAnimeLoverzPlaylistItem(AnimeLoverzEpisodeMeta meta, ArrayList<String[]> attempts, int index, ArrayList<EpisodeMedia> result, AtomicInteger remaining, java.util.function.Consumer<EpisodeMedia> single) {
        if (attempts == null || index >= attempts.size()) {
            if (single != null) {
                single.accept(null);
                return;
            }
            if (remaining.decrementAndGet() == 0) applyPlaylist(result);
            return;
        }
        String episodeUrl = attempts.get(index)[0];
        String seriesSlug = attempts.get(index)[1];
        String url = ANIMELOVERZ_API_BASE + "/series/episode/data.php?url=" + Uri.encode(episodeUrl);
        StringRequest request = new StringRequest(Request.Method.POST, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                JSONObject item = firstAnimeLoverzDataObject(json);
                JSONObject streams = item == null ? null : item.optJSONObject("streams");
                String resolvedUrl = resolveAnimeLoverzVideoUrl(streams);
                if (isAvailable(resolvedUrl)) {
                    String resolutionLabel = animeLoverzResolutionLabel(streams, resolvedUrl);
                    String label = meta.label + " • " + resolutionLabel;
                    EpisodeMedia built = new EpisodeMedia(meta.id, categoryId, categoryName, label, imageUrl, resolvedUrl,
                            extractEpisodeNumber(meta.label), meta.index, meta.url);
                    if (single != null) {
                        single.accept(built);
                        return;
                    }
                    result.add(built);
                    if (remaining.decrementAndGet() == 0) applyPlaylist(result);
                } else {
                    fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining, single);
                }
            } catch (Exception e) {
                Log.e(TAG, "Animeloverz episode playlist parse error", e);
                fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining, single);
            }
        }, error -> {
            Log.e(TAG, "Animeloverz episode playlist network error", error);
            fetchAnimeLoverzPlaylistItem(meta, attempts, index + 1, result, remaining, single);
        }) {
            @Override public Map<String, String> getHeaders() { return animeLoverzHeaders(); }
            @Override public String getBodyContentType() { return "text/plain; charset=utf-8"; }
            @Override public byte[] getBody() {
                String body = "{\"post_type\":\"2\",\"post_id\":\"" + escapeJson(episodeUrl) + "\",\"series_id\":\"" + escapeJson(seriesSlug) + "\",\"series_url\":\"" + escapeJson(seriesSlug) + "\",\"episode\":\"" + escapeJson(meta.episode) + "\",\"token\":\"\"}";
                return body.getBytes(StandardCharsets.UTF_8);
            }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private JSONObject firstAnimeLoverzDataObject(JSONObject json) {
        if (json == null) return null;
        Object data = json.opt("data");
        if (data instanceof JSONArray) {
            JSONArray array = (JSONArray) data;
            return array.length() == 0 ? null : array.optJSONObject(0);
        }
        if (data instanceof JSONObject) return (JSONObject) data;
        if (json.has("judul") || json.has("chapter") || json.has("streams")) return json;
        return null;
    }

    private JSONArray firstAnimeLoverzArray(JSONObject json, String... names) {
        if (json == null || names == null) return null;
        for (String name : names) {
            JSONArray array = json.optJSONArray(name);
            if (array != null) return array;
        }
        return null;
    }

    private String normalizeAnimeLoverzSlug(String value) {
        String slug = value == null ? "" : Uri.decode(value).trim();
        while (slug.startsWith("/")) slug = slug.substring(1);
        while (slug.endsWith("/")) slug = slug.substring(0, slug.length() - 1);
        return slug;
    }

    private String animeLoverzSlugWithSlash(String value) {
        String slug = normalizeAnimeLoverzSlug(value);
        return slug.isEmpty() ? "" : slug + "/";
    }

    private ArrayList<String> animeLoverzSlugVariants(String value) {
        ArrayList<String> variants = new ArrayList<>();
        String raw = value == null ? "" : Uri.decode(value).trim();
        while (raw.startsWith("/")) raw = raw.substring(1);
        String clean = normalizeAnimeLoverzSlug(raw);
        String slash = animeLoverzSlugWithSlash(raw);
        if (raw.endsWith("/")) {
            addAnimeLoverzVariant(variants, slash);
            addAnimeLoverzVariant(variants, clean);
        } else {
            addAnimeLoverzVariant(variants, clean);
            addAnimeLoverzVariant(variants, slash);
        }
        return variants;
    }

    private ArrayList<String[]> animeLoverzEpisodeRequestVariants(String episode, String series) {
        ArrayList<String[]> attempts = new ArrayList<>();
        ArrayList<String> episodeVariants = animeLoverzSlugVariants(episode);
        ArrayList<String> seriesVariants = animeLoverzSlugVariants(series);
        for (String episodeVariant : episodeVariants) {
            for (String seriesVariant : seriesVariants) addAnimeLoverzEpisodeVariant(attempts, episodeVariant, seriesVariant);
        }
        return attempts;
    }

    private void addAnimeLoverzVariant(ArrayList<String> variants, String value) {
        if (variants == null || !hasText(value)) return;
        String clean = value.trim();
        if (!variants.contains(clean)) variants.add(clean);
    }

    private void addAnimeLoverzEpisodeVariant(ArrayList<String[]> attempts, String episode, String series) {
        if (attempts == null || !hasText(episode) || !hasText(series)) return;
        String cleanEpisode = episode.trim();
        String cleanSeries = series.trim();
        for (String[] attempt : attempts) if (attempt[0].equals(cleanEpisode) && attempt[1].equals(cleanSeries)) return;
        attempts.add(new String[]{cleanEpisode, cleanSeries});
    }

    private int positiveId(String value) {
        int id = value == null ? 1 : value.hashCode();
        return id == Integer.MIN_VALUE ? 1 : Math.abs(id);
    }

    private String resolveAnimeLoverzVideoUrl(JSONObject streams) {
        if (streams == null) return "";
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAnimeLoverzStream(streams, "480p", "360p", "720p", "1080p");
        if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) return firstAnimeLoverzStream(streams, "1080p", "720p", "480p", "360p");
        return firstAnimeLoverzStream(streams, "720p", "480p", "360p", "1080p");
    }

    private String firstAnimeLoverzStream(JSONObject streams, String... keys) {
        if (streams == null || keys == null) return "";
        for (String key : keys) {
            JSONArray array = streams.optJSONArray(key);
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                String link = item == null ? "" : item.optString("link", "");
                if (isAvailable(link)) return link.trim();
            }
        }
        return "";
    }

    private String animeLoverzResolutionLabel(JSONObject streams, String resolvedUrl) {
        if (!isAvailable(resolvedUrl) || streams == null) return selectedQualityLabel;
        String[] resolutions = new String[]{"360p", "480p", "720p", "1080p"};
        for (String resolution : resolutions) {
            if (resolvedUrl.equals(firstAnimeLoverzStream(streams, resolution))) return resolution;
        }
        return selectedQualityLabel == null || selectedQualityLabel.trim().isEmpty()
                ? PlaybackQualityManager.getQualityLabel(selectedQuality) : selectedQualityLabel;
    }

    private void loadEpisodePlaylist() {
        if (playlistLoaded || categoryId <= 0 || channelId <= 0 || requestQueue == null) return;
        playlistLoaded = true;
        playlistLoading = true;
        String url = API_BASE + "get_post_detail_next_provious?id=" + channelId + "&cat_id=" + categoryId;
        StringRequest request = new StringRequest(Request.Method.GET, url, response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    onPlaylistFailed();
                    return;
                }
                ArrayList<EpisodeMedia> episodes = new ArrayList<>();
                addEpisodeMedia(episodes, json.optJSONObject("post"), 0);
                JSONArray suggested = json.optJSONArray("suggested");
                if (suggested != null) for (int i = 0; i < suggested.length(); i++) addEpisodeMedia(episodes, suggested.optJSONObject(i), i + 1);
                applyPlaylist(episodes);
            } catch (Exception e) {
                Log.e(TAG, "Playlist parse error", e);
                onPlaylistFailed();
            }
        }, error -> {
            Log.e(TAG, "Playlist network error", error);
            onPlaylistFailed();
        }) {
            @Override public Map<String, String> getHeaders() { return apiHeaders(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void addEpisodeMedia(ArrayList<EpisodeMedia> result, JSONObject json, int index) {
        if (json == null) return;
        int resolvedChannelId = json.optInt("vid", -1);
        int resolvedCategoryId = json.optInt("cat_id", categoryId);
        if (resolvedChannelId <= 0 || (categoryId > 0 && resolvedCategoryId != categoryId)) return;
        String resolvedUrl = resolveVideoUrl(json);
        if (!isAvailable(resolvedUrl)) return;
        String rawTitle = json.optString("video_title", "Episode " + (index + 1));
        String label = qualityLabelForResolved(json, resolvedUrl);
        String title = rawTitle + " • " + label;
        String img = imageUrl(firstAvailableText(json.optString("category_image", ""), json.optString("video_thumbnail", ""), imageUrl));
        String name = json.optString("category_name", categoryName);
        result.add(new EpisodeMedia(resolvedChannelId, resolvedCategoryId, name, title, img, resolvedUrl, extractEpisodeNumber(rawTitle), index));
    }

    private void applyPlaylist(ArrayList<EpisodeMedia> episodes) {
        playlistLoading = false;
        if (player == null || episodes == null || episodes.size() <= 1) {
            refreshingQuality = false;
            onPlaylistFailed();
            return;
        }
        LinkedHashMap<Integer, EpisodeMedia> unique = new LinkedHashMap<>();
        for (EpisodeMedia episode : episodes) {
            if (episode == null || episode.channelId <= 0) continue;
            if (AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId) && hasText(episode.sourceEpisodeUrl)) {
                String candidateUrl = normalizeAnimeLoverzSlug(episode.sourceEpisodeUrl);
                boolean duplicateUrl = false;
                for (EpisodeMedia existing : unique.values()) {
                    if (hasText(existing.sourceEpisodeUrl)
                            && candidateUrl.equals(normalizeAnimeLoverzSlug(existing.sourceEpisodeUrl))) {
                        duplicateUrl = true;
                        break;
                    }
                }
                if (duplicateUrl) continue;
            }
            if (!unique.containsKey(episode.channelId)) unique.put(episode.channelId, episode);
        }
        ArrayList<EpisodeMedia> ordered = new ArrayList<>(unique.values());
        if (ordered.size() <= 1) {
            refreshingQuality = false;
            onPlaylistFailed();
            return;
        }
        Collections.sort(ordered, (a, b) -> {
            int ea = a.episodeNumber;
            int eb = b.episodeNumber;
            if (ea != eb) return Integer.compare(ea, eb);
            if (a.sourceIndex != b.sourceIndex) return Integer.compare(a.sourceIndex, b.sourceIndex);
            return Integer.compare(a.channelId, b.channelId);
        });
        int currentIndex = -1;
        ArrayList<MediaSource> sources = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            EpisodeMedia episode = ordered.get(i);
            if (episode.channelId == channelId) currentIndex = i;
            sources.add(createMediaSource(episode));
        }
        if (currentIndex < 0 && AnimeSettingsManager.SOURCE_ANIMELOVERZ.equals(historySourceId)
                && currentEpisodeMedia != null && hasText(currentEpisodeMedia.sourceEpisodeUrl)) {
            String currentSourceUrl = normalizeAnimeLoverzSlug(currentEpisodeMedia.sourceEpisodeUrl);
            for (int i = 0; i < ordered.size(); i++) {
                if (currentSourceUrl.equals(normalizeAnimeLoverzSlug(ordered.get(i).sourceEpisodeUrl))) {
                    currentIndex = i;
                    break;
                }
            }
        }
        if (currentIndex < 0) {
            refreshingQuality = false;
            onPlaylistFailed();
            return;
        }
        MediaItem playingItem = player.getCurrentMediaItem();
        EpisodeMedia playing = playingItem != null && playingItem.localConfiguration != null && playingItem.localConfiguration.tag instanceof EpisodeMedia ? (EpisodeMedia) playingItem.localConfiguration.tag : null;
        EpisodeMedia target = ordered.get(currentIndex);
        if (playing != null && playing.channelId == target.channelId && playing.videoUrl != null && playing.videoUrl.equals(target.videoUrl)) {
            int playerIndex = player.getCurrentMediaItemIndex();
            ArrayList<MediaSource> before = new ArrayList<>();
            for (int i = 0; i < currentIndex; i++) before.add(createMediaSource(ordered.get(i)));
            ArrayList<MediaSource> after = new ArrayList<>();
            for (int i = currentIndex + 1; i < ordered.size(); i++) after.add(createMediaSource(ordered.get(i)));
            if (!before.isEmpty()) player.addMediaSources(playerIndex, before);
            if (!after.isEmpty()) player.addMediaSources(playerIndex + before.size() + 1, after);
            playlistReady = true;
            updateNavigationButtons();
            refreshingQuality = false;
            return;
        }
        long pos = Math.max(0L, player.getCurrentPosition());
        boolean ready = player.getPlayWhenReady();
        player.stop();
        player.clearMediaItems();
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        player.setMediaSources(sources, currentIndex, pos);
        player.setPlayWhenReady(ready);
        player.prepare();
        playlistReady = true;
        updateNavigationButtons();
        refreshingQuality = false;
    }

    private void showEpisodeChangedMessage(EpisodeMedia episode) {
        if (episode == null) return;
        // Cukup nomor episode saja. Judul anime sengaja TIDAK ditampilkan di toast.
        Toast.makeText(this, "Kamu menonton " + episodeToastLabel(episode), Toast.LENGTH_SHORT).show();
    }

    /** Hasilnya selalu "Episode N" (atau "episode ini" bila nomor tidak ketemu), tanpa judul anime. */
    private String episodeToastLabel(EpisodeMedia episode) {
        String title = episode == null || episode.title == null ? "" : episode.title;
        int cut = title.indexOf(" \u2022 ");
        if (cut >= 0) title = title.substring(0, cut);
        // Judul anime ada di depan nomor episode, jadi pakai kemunculan kata Episode/Eps/Ep yang TERAKHIR.
        Matcher matcher = Pattern.compile("(?i)(?<!\\p{L})(?:episode|eps|ep)(?!\\p{L})\\.?\\s*[-:#]*\\s*(\\d+(?:[.,]\\d+)?)").matcher(title);
        String number = null;
        while (matcher.find()) number = matcher.group(1);
        if (number != null) return "Episode " + number.replace(',', '.').replaceFirst("^0+(?=\\d)", "");
        if (episode != null && episode.episodeNumber != Integer.MAX_VALUE) return "Episode " + episode.episodeNumber;
        return "episode ini";
    }

    private void updateNavigationButtons() {
        if (player == null) return;
        player.setRepeatMode(Player.REPEAT_MODE_OFF);
        player.setShuffleModeEnabled(false);
        if (playerChrome != null) playerChrome.updateNavigation(player.hasPreviousMediaItem(), player.hasNextMediaItem());
    }

    private int extractEpisodeNumber(String title) {
        if (title == null) return Integer.MAX_VALUE;
        String text = title.toLowerCase();
        Matcher matcher = Pattern.compile("(?:episode|eps|ep|e)\\s*[-:]*\\s*(\\d+)").matcher(text);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) { }
        }
        matcher = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n != 360 && n != 480 && n != 720 && n != 1080) return n;
            } catch (Exception ignored) { }
        }
        return Integer.MAX_VALUE;
    }

    private String resolveVideoUrl(JSONObject json) {
        String sd = json.optString("video_url", "");
        String mini = json.optString("video_url_minihd", "");
        String hd = json.optString("video_url_hd", "");
        String fhd = json.optString("video_url_fullhd", "");
        if (!AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) {
            if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAvailableText(sd, mini, hd, fhd);
            if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) return firstAvailableText(fhd, hd, mini, sd);
            return firstAvailableText(hd, mini, sd, fhd);
        }
        String indonesian;
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) indonesian = firstAvailableAnimekuStream(sd, mini, hd, fhd);
        else if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) indonesian = firstAvailableAnimekuStream(fhd, hd, mini, sd);
        else indonesian = firstAvailableAnimekuStream(hd, mini, sd, fhd);
        if (isAvailableAnimekuStream(indonesian)) return indonesian;
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) return firstAvailableAnimekuStream(json.optString("video_url_eng", ""), json.optString("video_url_hdeng", ""));
        return firstAvailableAnimekuStream(json.optString("video_url_hdeng", ""), json.optString("video_url_eng", ""));
    }

    private String qualityLabelForResolved(JSONObject json, String resolvedUrl) {
        if (sameUrl(resolvedUrl, json.optString("video_url_fullhd", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_FHD);
        if (sameUrl(resolvedUrl, json.optString("video_url_hd", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_HD);
        if (sameUrl(resolvedUrl, json.optString("video_url_minihd", ""))) return "Mini HD 480p";
        if (sameUrl(resolvedUrl, json.optString("video_url", ""))) return PlaybackQualityManager.getQualityLabel(PlaybackQualityManager.QUALITY_SD);
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) {
            if (sameUrl(resolvedUrl, json.optString("video_url_hdeng", ""))) return "HD English";
            if (sameUrl(resolvedUrl, json.optString("video_url_eng", ""))) return "SD English";
        }
        return PlaybackQualityManager.getQualityLabel(selectedQuality);
    }

    private String firstAvailableAnimekuStream(String... values) {
        if (values == null) return "";
        for (String value : values) if (isAvailableAnimekuStream(value)) return value.trim();
        return "";
    }

    private boolean isAvailableAnimekuStream(String url) {
        if (url == null) return false;
        String value = url.trim();
        return !value.isEmpty() && !"null".equalsIgnoreCase(value) && (value.regionMatches(true, 0, "http://", 0, 7) || value.regionMatches(true, 0, "https://", 0, 8));
    }

    private boolean sameUrl(String a, String b) {
        return a != null && b != null && a.trim().equals(b.trim());
    }

    private String firstAvailableText(String... values) {
        if (values == null) return "";
        for (String value : values) if (isAvailable(value)) return value.trim();
        for (String value : values) if (value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim())) return value.trim();
        return "";
    }

    private String imageUrl(String image) {
        if (image == null) return "";
        String value = image.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) return "";
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        return IMAGE_BASE + value;
    }

    private boolean isAvailable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty() && !"null".equalsIgnoreCase(value.trim());
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private Map<String, String> animeLoverzHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("user-agent", "Dart/3.9 (dart:io)");
        h.put("accept", "application/json");
        h.put("access-control-allow-origin", "*");
        h.put("content-type", "text/plain; charset=utf-8");
        return h;
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "Your Videos Channel");
        h.put("User-Agent", "Dalvik/7.1.12.1.0 (com.newanimeku.animechanneldonghuasubindosubenglish U; Android ; 20175 Build/NMF260)");
        h.put("Accept", "application/vnd.yourapi.v1.full+json");
        return h;
    }

    private Map<String, String> videoHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 12; SM-A165F Build/UP1A.231005.007)");
        return h;
    }

    private void applyDisplayCutoutMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) return;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(lp);
    }

    private void hideSystemBars() {
        applyDisplayCutoutMode();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideSystemBars(); }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) {
            playbackPosition = player.getCurrentPosition();
            playWhenReady = player.getPlayWhenReady();
            saveHistory();
            player.pause();
        }
    }

    private void saveHistory() {
        if (player == null) return;
        EpisodeMedia episode = currentEpisodeMedia;
        if (episode == null) episode = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0);
        long position = Math.max(0L, player.getCurrentPosition());
        long duration = Math.max(0L, player.getDuration());
        saveHistory(episode, position, duration);
    }

    private void saveHistory(EpisodeMedia episode, long position, long duration) {
        if (episode == null || episode.videoUrl == null || episode.videoUrl.trim().isEmpty()) return;
        long safeDuration = Math.max(0L, duration);
        long safePosition = Math.max(0L, position);
        long completionThreshold = safeDuration <= 5000L ? Math.max(1L, safeDuration * 9L / 10L) : safeDuration - 5000L;
        if (safeDuration > 0L && safePosition > 0L && safePosition >= completionThreshold) safePosition = safeDuration;
        if (safePosition <= 0L && safeDuration <= 0L) return;
        HistoryItem item = new HistoryItem(episode.channelId, episode.categoryId, episode.categoryName, episode.title == null || episode.title.trim().isEmpty() ? "Animeku" : episode.title, episode.imageUrl, episode.videoUrl, safePosition, safeDuration, System.currentTimeMillis(), historySourceId);
        if (!AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) item.slug = historySlug == null ? "" : historySlug;
        if (AnimeSettingsManager.SOURCE_ANIMEKU.equals(historySourceId)) AnimekuHistoryManager.save(this, item);
        else HistoryManager.save(this, item);
    }

    @Override protected void onResume() { super.onResume(); hideSystemBars(); if (player != null) player.setPlayWhenReady(playWhenReady); }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (player != null) {
            outState.putLong("player_position", player.getCurrentPosition());
            outState.putBoolean("play_when_ready", player.getPlayWhenReady());
        }
        outState.putString("video_url", videoUrl);
        outState.putString("video_title", videoTitle);
        outState.putString("image_url", imageUrl);
        outState.putInt("channel_id", channelId);
        outState.putInt("category_id", categoryId);
        outState.putString("category_name", categoryName);
        outState.putString("selected_quality", selectedQuality);
        outState.putString("selected_quality_label", selectedQualityLabel);
        outState.putBoolean("disable_playlist", disablePlaylist);
        outState.putString("history_source_id", historySourceId);
        outState.putString("animeloverz_slug", animeLoverzSlug);
        outState.putString("animeloverz_episode_url", animeLoverzEpisodeUrl);
        outState.putString("history_slug", historySlug);
        outState.putString("sokuja_episode_slug", sokujaEpisodeSlug);
        outState.putString("sokuja_episode_list", sokujaEpisodeListJson());
        outState.putInt("sokuja_episode_index", sokujaEpisodeIndex);
        outState.putString("drakorku_episode_list", drakorkuEpisodeListJson());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (playerChrome != null) playerChrome.setRotateState(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    @Override
    public void finish() {
        resetAutoLandscapeIfNeeded();
        super.finish();
    }

    @Override
    protected void onStop() {
        if (isFinishing()) resetAutoLandscapeIfNeeded();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        resetAutoLandscapeIfNeeded();
        super.onDestroy();
        if (requestQueue != null) requestQueue.cancelAll(TAG);
        if (playerChrome != null) {
            playerChrome.release();
            playerChrome = null;
        }
        if (player != null) {
            saveHistory();
            player.release();
            player = null;
        }
        playerView = null;
    }
}
