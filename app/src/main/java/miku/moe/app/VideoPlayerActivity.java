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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;

public class VideoPlayerActivity extends AppCompatActivity {
    public static final String EXTRA_VIDEO_URL = "video_url";
    public static final String EXTRA_VIDEO_TITLE = "video_title";
    public static final String EXTRA_IMAGE_URL = "image_url";
    public static final String EXTRA_CHANNEL_ID = "channel_id";
    public static final String EXTRA_CATEGORY_ID = "category_id";
    public static final String EXTRA_CATEGORY_NAME = "category_name";
    public static final String EXTRA_START_POSITION = "start_position";
    public static final String EXTRA_QUALITY = "quality";
    public static final String EXTRA_VIDEO_AUTH = "video_auth";

    private static final String TAG = "VideoPlayerActivity";
    private static String categoryUrl() {
        return miku.moe.app.api.AnxBaseUrl.apiBase() + "get_category_posts_secure/";
    }

    private static String descriptionUrl() {
        return miku.moe.app.api.AnxBaseUrl.apiBase() + "get_post_description/";
    }

    private PlayerView playerView;
    private AnimePlayerChrome playerChrome;
    private ExoPlayer player;
    private RequestQueue requestQueue;
    private String videoAuth;
    private String lastDescriptionAuth;
    private Bundle createSavedState;
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
    private String lastResolvedQuality = PlaybackQualityManager.QUALITY_HD;
    private boolean playlistLoaded = false;
    private boolean playlistLoading = false;
    private boolean playlistReady = false;
    private boolean navigating = false;
    private final ArrayList<int[]> episodeOrderRows = new ArrayList<>();
    private int playlistRetry = 0;
    private boolean qualityOptionsLoading = false;
    private boolean autoLandscapeActive = false;
    private EpisodeMedia currentEpisodeMedia;
    private long currentEpisodeDuration = 0L;

    private static class EpisodeMedia {
        final int channelId;
        final int categoryId;
        final String categoryName;
        final String title;
        final String imageUrl;
        final String videoUrl;
        final int episodeNumber;
        final int sourceIndex;
        final String auth;

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl) {
            this(channelId, categoryId, categoryName, title, imageUrl, videoUrl, Integer.MAX_VALUE, Integer.MAX_VALUE, null);
        }

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl, int episodeNumber, int sourceIndex) {
            this(channelId, categoryId, categoryName, title, imageUrl, videoUrl, episodeNumber, sourceIndex, null);
        }

        EpisodeMedia(int channelId, int categoryId, String categoryName, String title, String imageUrl, String videoUrl, int episodeNumber, int sourceIndex, String auth) {
            this.channelId = channelId;
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.title = title;
            this.imageUrl = imageUrl;
            this.videoUrl = videoUrl;
            this.episodeNumber = episodeNumber;
            this.sourceIndex = sourceIndex;
            this.auth = auth;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(AnimeSettingsManager.isPlayerStyleV2(this) ? R.layout.activity_video_player_v2 : R.layout.activity_video_player);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        playerView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applyVideoScale(false);
        });
        playerChrome = new AnimePlayerChrome(findViewById(R.id.player_shell), this::finish, this::hideSystemBars);
        playerChrome.setRotateState(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
        requestQueue = Volley.newRequestQueue(getApplicationContext(), new OkHttpStack(new okhttp3.OkHttpClient.Builder().addInterceptor(new miku.moe.app.api.AnxRetryInterceptor()).addInterceptor(new miku.moe.app.api.AnxGuardInterceptor()).addInterceptor(new miku.moe.app.api.AnxResponseDecryptInterceptor()).build()));
        videoUrl = getIntent().getStringExtra(EXTRA_VIDEO_URL);
        videoTitle = getIntent().getStringExtra(EXTRA_VIDEO_TITLE);
        imageUrl = getIntent().getStringExtra(EXTRA_IMAGE_URL);
        channelId = getIntent().getIntExtra(EXTRA_CHANNEL_ID, -1);
        categoryId = getIntent().getIntExtra(EXTRA_CATEGORY_ID, -1);
        categoryName = getIntent().getStringExtra(EXTRA_CATEGORY_NAME);
        playbackPosition = getIntent().getLongExtra(EXTRA_START_POSITION, 0L);
        selectedQuality = getIntent().getStringExtra(EXTRA_QUALITY);
        videoAuth = AnxVideoAuth.normalize(getIntent().getStringExtra(EXTRA_VIDEO_AUTH));
        if (selectedQuality == null || selectedQuality.trim().isEmpty()) selectedQuality = PlaybackQualityManager.getQuality(this);
        if (videoTitle == null) videoTitle = "";
        selectedQualityLabel = resolutionLabelFromTitle(videoTitle, PlaybackQualityManager.getQualityLabel(selectedQuality));
        if (playerChrome != null) playerChrome.setTitle(categoryName, videoTitle);
        if (imageUrl == null) imageUrl = "";
        if (categoryName == null) categoryName = "";

        createSavedState = savedInstanceState;
        if (channelId > 0 && isAvailable(videoUrl) && !AnxVideoAuth.isSignedUrl(videoUrl) && videoAuth == null) {
            resolveInitialAuthAndStart();
            return;
        }
        continueOnCreate();
    }

    private void continueOnCreate() {
        Bundle savedInstanceState = createSavedState;
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
            String restoredAuth = savedInstanceState.getString("video_auth");
            if (restoredAuth != null && !restoredAuth.isEmpty()) videoAuth = restoredAuth;
        }

        hideSystemBars();
        initializePlayer();
        loadEpisodePlaylist();
    }

    private void resolveInitialAuthAndStart() {
        StringRequest request = new StringRequest(Request.Method.POST, descriptionUrl(), response -> {
            try {
                JSONObject json = new JSONObject(response);
                if ("ok".equalsIgnoreCase(json.optString("status"))) {
                    String auth = AnxVideoAuth.fromDescription(json);
                    if (auth != null) videoAuth = auth;
                    String resolved = resolveVideoUrl(json);
                    if (isAvailable(resolved)) {
                        videoUrl = resolved;
                        if (lastResolvedQuality != null && !lastResolvedQuality.equals(selectedQuality)) {
                            selectedQuality = lastResolvedQuality;
                            selectedQualityLabel = PlaybackQualityManager.getQualityLabel(selectedQuality);
                            if (videoTitle != null && !videoTitle.isEmpty()) videoTitle = stripQualitySuffix(videoTitle) + " \u2022 " + selectedQualityLabel;
                        }
                    }
                }
            } catch (Exception ignored) {}
            if (!isFinishing() && !isDestroyed()) continueOnCreate();
        }, error -> {
            if (!isFinishing() && !isDestroyed()) continueOnCreate();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(channelId));
                p.put("isAPKvalid", "true");
                p.put("gone", "1");
                p.put("category_id", String.valueOf(categoryId));
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
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
            Intent intent = createExternalPlayerIntent(buildAuthenticatedVideoUri(videoUrl));
            String packageName = getPlayerPackage(player);
            if (packageName != null) intent.setPackage(packageName);
            if (PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) startActivity(Intent.createChooser(intent, "Pilih player anime"));
            else startActivity(intent);
            finish();
            return true;
        } catch (ActivityNotFoundException e) {
            if (!PlaybackQualityManager.PLAYER_CHOOSER.equals(player)) {
                try {
                    Intent fallback = createExternalPlayerIntent(buildAuthenticatedVideoUri(videoUrl));
                    startActivity(Intent.createChooser(fallback, "Pilih player anime"));
                    finish();
                    return true;
                } catch (Exception ignored) {}
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
        String auth = AnxVideoAuth.forUrl(videoUrl, videoAuth);
        if (auth != null) {
            HashMap<String, String> headerMap = new HashMap<>();
            headerMap.put("Authorization", auth);
            Bundle headerBundle = new Bundle();
            headerBundle.putString("Authorization", auth);
            intent.putExtra("headers", headerMap);
            intent.putExtra("android.media.intent.extra.HTTP_HEADERS", headerBundle);
            intent.putExtra("org.videolan.vlc.HTTP_HEADERS", headerBundle);
            intent.putExtra("org.videolan.vlc.headers", headerMap);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    private Uri buildAuthenticatedVideoUri(String url) {
        Uri uri = Uri.parse(url);
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) return uri;
        if (uri.getUserInfo() != null && !uri.getUserInfo().trim().isEmpty()) return uri;
        String[] credentials = AnxVideoAuth.decodeBasicCredentials(AnxVideoAuth.forUrl(url, videoAuth));
        if (credentials == null) return uri;
        String host = uri.getHost();
        if (host == null || host.trim().isEmpty()) return uri;
        String authority = Uri.encode(credentials[0]) + ":" + Uri.encode(credentials[1]) + "@" + host;
        if (uri.getPort() != -1) authority += ":" + uri.getPort();
        return uri.buildUpon().encodedAuthority(authority).build();
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
            playerChrome.setQualityLabel(selectedQualityLabel);
            playerChrome.setNavigationListener(this::handleNavigationUnavailable);
            playerChrome.setAvailabilityProvider(forward -> adjacentEpisodeId(forward) > 0);
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

        if (videoUrl == null || videoUrl.trim().isEmpty()) {
            finish();
            return;
        }

        EpisodeMedia current = new EpisodeMedia(channelId, categoryId, categoryName, videoTitle, imageUrl, videoUrl, extractEpisodeNumber(videoTitle), 0, videoAuth);
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
                    playbackPosition = 0L;
                    selectedQualityLabel = resolutionLabelFromTitle(videoTitle, PlaybackQualityManager.getQualityLabel(selectedQuality));
                    setTitle(videoTitle);
                    if (playerChrome != null) {
                        playerChrome.setTitle(categoryName, videoTitle);
                        playerChrome.setQualityLabel(selectedQualityLabel);
                    }
                    showEpisodeChangedMessage(episode);
                    updateNavigationButtons();
                }
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (player != null) currentEpisodeDuration = Math.max(currentEpisodeDuration, Math.max(0L, player.getDuration()));
                if (playbackState == Player.STATE_ENDED) saveHistory();
            }
        });
    }

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
        loadAvailableQualityOptions(anchor);
    }

    private void loadAvailableQualityOptions(View anchor) {
        if (qualityOptionsLoading) return;
        if (requestQueue == null || channelId <= 0) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        qualityOptionsLoading = true;
        StringRequest request = new StringRequest(Request.Method.POST, descriptionUrl(), response -> {
            qualityOptionsLoading = false;
            if (isFinishing() || isDestroyed()) return;
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
                    return;
                }
                lastDescriptionAuth = AnxVideoAuth.fromDescription(json);
                ArrayList<String[]> options = availableQualityOptions(json);
                if (options.isEmpty()) {
                    Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (options.size() == 1) {
                    String[] only = options.get(0);
                    if (!only[2].equals(videoUrl)) {
                        applyAvailableQuality(only);
                    } else {
                        selectedQuality = only[0];
                        selectedQualityLabel = only[1];
                        if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
                        Toast.makeText(this, "Hanya tersedia " + selectedQualityLabel, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                showAvailableQualityMenu(anchor, options);
            } catch (Exception e) {
                Log.e(TAG, "Gagal membaca resolusi video", e);
                Toast.makeText(this, "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            qualityOptionsLoading = false;
            if (!isFinishing() && !isDestroyed()) Toast.makeText(this, "Gagal memuat resolusi", Toast.LENGTH_SHORT).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> params = new HashMap<>();
                params.put("channel_id", String.valueOf(channelId));
                params.put("isAPKvalid", "true");
                params.put("gone", "1");
                params.put("category_id", String.valueOf(categoryId));
                return params;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private ArrayList<String[]> availableQualityOptions(JSONObject json) {
        ArrayList<String[]> options = new ArrayList<>();
        if (json == null) return options;
        String secretKey = json.optString("secretKey", "");
        addAvailableQuality(options, PlaybackQualityManager.QUALITY_SD, "SD 360p",
                firstAvailable(AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url", ""), secretKey),
                        AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_ori", ""), secretKey)));
        addAvailableQuality(options, PlaybackQualityManager.QUALITY_HD, "HD 720p",
                firstAvailable(AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd", ""), secretKey),
                        AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd_ori", ""), secretKey)));
        addAvailableQuality(options, PlaybackQualityManager.QUALITY_FHD, "FHD 1080p",
                firstAvailable(AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd", ""), secretKey),
                        AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd_ori", ""), secretKey)));
        return options;
    }

    private void addAvailableQuality(ArrayList<String[]> options, String quality, String label, String url) {
        if (!isAvailable(url)) return;
        String normalized = url.trim();
        for (String[] existing : options) {
            if (normalized.equals(existing[2])) return;
        }
        options.add(new String[]{quality, label, normalized});
    }

    private void showAvailableQualityMenu(View anchor, ArrayList<String[]> options) {
        if (player == null || isFinishing() || options == null || options.isEmpty()) return;
        String[] labels = new String[options.size()];
        int checked = 0;
        boolean foundCurrentUrl = false;
        for (int i = 0; i < options.size(); i++) {
            String[] option = options.get(i);
            labels[i] = option[1];
            if (isAvailable(videoUrl) && videoUrl.equals(option[2])) {
                checked = i;
                foundCurrentUrl = true;
            } else if (!foundCurrentUrl && option[0].equals(selectedQuality)) {
                checked = i;
            }
        }
        showChoiceMenu(anchor, labels, checked, which -> applyAvailableQuality(options.get(which)));
    }

    private void applyAvailableQuality(String[] option) {
        if (option == null || option.length < 3 || !isAvailable(option[2])) return;
        selectedQuality = option[0];
        selectedQualityLabel = option[1];
        PlaybackQualityManager.setQuality(this, selectedQuality);
        if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
        if (option[2].equals(videoUrl)) return;

        String rawTitle = stripQualitySuffix(videoTitle);
        String title = rawTitle + " • " + selectedQualityLabel;
        EpisodeMedia episode = new EpisodeMedia(channelId, categoryId, categoryName, title, imageUrl,
                option[2], extractEpisodeNumber(rawTitle), 0, lastDescriptionAuth);
        replaceCurrentMedia(episode);
        playlistLoaded = false;
        loadEpisodePlaylist();
    }

    private String stripQualitySuffix(String title) {
        String value = title == null ? "" : title.trim();
        int separator = value.lastIndexOf(" • ");
        return separator > 0 ? value.substring(0, separator).trim() : value;
    }

    private String resolutionLabelFromTitle(String title, String fallback) {
        String value = title == null ? "" : title.trim();
        int separator = value.lastIndexOf(" • ");
        if (separator >= 0 && separator + 3 < value.length()) return value.substring(separator + 3).trim();
        return fallback == null || fallback.trim().isEmpty() ? PlaybackQualityManager.getQualityLabel(selectedQuality) : fallback.trim();
    }

    private void changeQuality(String quality) {
        if (quality == null || quality.equals(selectedQuality)) return;
        selectedQuality = quality;
        selectedQualityLabel = PlaybackQualityManager.getQualityLabel(selectedQuality);
        PlaybackQualityManager.setQuality(this, selectedQuality);
        if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
        fetchAndReplaceCurrentQuality();
    }

    private void fetchAndReplaceCurrentQuality() {
        if (requestQueue == null || channelId <= 0) {
            Toast.makeText(this, "Resolusi episode ini tidak tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        StringRequest request = new StringRequest(Request.Method.POST, descriptionUrl(), response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                String resolvedUrl = resolveVideoUrl(json);
                if (!isAvailable(resolvedUrl)) {
                    Toast.makeText(this, "Resolusi tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (lastResolvedQuality != null && !lastResolvedQuality.equals(selectedQuality)) {
                    selectedQuality = lastResolvedQuality;
                    selectedQualityLabel = PlaybackQualityManager.getQualityLabel(selectedQuality);
                    if (playerChrome != null) playerChrome.setQualityLabel(selectedQualityLabel);
                }
                String rawTitle = json.optString("channel_name", videoTitle);
                String title = rawTitle + " • " + PlaybackQualityManager.getQualityLabel(lastResolvedQuality);
                EpisodeMedia episode = new EpisodeMedia(json.optInt("channel_id", channelId), json.optInt("category_id", categoryId), json.optString("category_name", categoryName), title, json.optString("img_url", imageUrl), resolvedUrl, extractEpisodeNumber(rawTitle), 0, AnxVideoAuth.fromDescription(json));
                replaceCurrentMedia(episode);
                playlistLoaded = false;
                loadEpisodePlaylist();
            } catch (Exception e) {
                Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show();
            }
        }, error -> Toast.makeText(this, "Gagal mengubah resolusi", Toast.LENGTH_SHORT).show()) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(channelId));
                p.put("isAPKvalid", "true");
                p.put("gone", "1");
                p.put("category_id", String.valueOf(categoryId));
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
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
            playerChrome.setQualityLabel(selectedQualityLabel);
        }
        player.setMediaSource(createMediaSource(episode), pos);
        player.setPlayWhenReady(ready);
        player.prepare();
        Toast.makeText(this, selectedQualityLabel, Toast.LENGTH_SHORT).show();
    }

    private MediaSource createMediaSource(EpisodeMedia episode) {
        MediaItem mediaItem = new MediaItem.Builder().setUri(Uri.parse(episode.videoUrl)).setTag(episode).build();
        return new ProgressiveMediaSource.Factory(dataSourceFactoryFor(episode)).createMediaSource(mediaItem);
    }

    private DefaultHttpDataSource.Factory dataSourceFactoryFor(EpisodeMedia episode) {
        DefaultHttpDataSource.Factory factory = new DefaultHttpDataSource.Factory();
        factory.setUserAgent(AnxPlayerUa.userAgent());
        String auth = AnxVideoAuth.forUrl(episode.videoUrl, episode.auth != null ? episode.auth : videoAuth);
        if (auth != null) {
            factory.setDefaultRequestProperties(Collections.singletonMap("Authorization", auth));
        }
        return factory;
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
        StringRequest request = new StringRequest(Request.Method.POST, descriptionUrl(), response -> {
            navigating = false;
            if (isFinishing() || isDestroyed()) return;
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    Toast.makeText(this, "Episode tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                String resolvedUrl = resolveVideoUrl(json);
                if (!isAvailable(resolvedUrl)) {
                    Toast.makeText(this, "Episode tidak tersedia", Toast.LENGTH_SHORT).show();
                    return;
                }
                String rawTitle = json.optString("channel_name", "Episode");
                String title = rawTitle + " • " + PlaybackQualityManager.getQualityLabel(lastResolvedQuality);
                switchToEpisode(new EpisodeMedia(json.optInt("channel_id", targetId), json.optInt("category_id", categoryId), json.optString("category_name", categoryName), title, json.optString("img_url", imageUrl), resolvedUrl, extractEpisodeNumber(rawTitle), 0, AnxVideoAuth.fromDescription(json)));
            } catch (Exception e) {
                Log.e(TAG, "Adjacent episode parse error", e);
                Toast.makeText(this, "Gagal memuat episode", Toast.LENGTH_SHORT).show();
            }
        }, error -> {
            navigating = false;
            if (!isFinishing() && !isDestroyed()) Toast.makeText(this, "Gagal memuat episode", Toast.LENGTH_SHORT).show();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(targetId));
                p.put("isAPKvalid", "true");
                p.put("gone", "1");
                p.put("category_id", String.valueOf(categoryId));
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
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
        selectedQualityLabel = resolutionLabelFromTitle(videoTitle, PlaybackQualityManager.getQualityLabel(selectedQuality));
        setTitle(videoTitle);
        if (playerChrome != null) {
            playerChrome.setTitle(categoryName, videoTitle);
            playerChrome.setQualityLabel(selectedQualityLabel);
        }
        player.setMediaSource(createMediaSource(episode));
        player.setPlayWhenReady(true);
        player.prepare();
        updateNavigationButtons();
        loadEpisodePlaylist();
    }

    private void handleNavigationUnavailable(boolean next) {
        if (navigating) return;
        int targetId = adjacentEpisodeId(next);
        if (targetId > 0) {
            loadAdjacentEpisode(targetId);
            return;
        }
        if (orderKnown()) {
            Toast.makeText(this, next ? "Ini episode terakhir" : "Ini episode pertama", Toast.LENGTH_SHORT).show();
            return;
        }
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
        loadEpisodePlaylist();
    }

    private void onPlaylistFailed() {
        playlistLoaded = false;
        playlistLoading = false;
        if (playlistRetry >= 3 || isFinishing() || isDestroyed() || playerView == null) return;
        playlistRetry++;
        playerView.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed() && !playlistLoaded && !playlistReady) loadEpisodePlaylist();
        }, 1500L * playlistRetry);
    }

    private void loadEpisodePlaylist() {
        if (playlistLoaded || categoryId <= 0 || channelId <= 0 || requestQueue == null) return;
        playlistLoaded = true;
        playlistLoading = true;
        StringRequest request = new StringRequest(Request.Method.POST, categoryUrl(), response -> {
            try {
                JSONObject json = new JSONObject(response);
                if (!"ok".equalsIgnoreCase(json.optString("status"))) {
                    onPlaylistFailed();
                    return;
                }
                JSONArray posts = json.optJSONArray("posts");
                if (posts == null || posts.length() <= 1) {
                    playlistLoading = false;
                    return;
                }
                ArrayList<Integer> ids = new ArrayList<>();
                ArrayList<int[]> orderRows = new ArrayList<>();
                String[] titleKeys = new String[]{"channel_name", "video_title", "episode_title", "post_title", "title", "name"};
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject post = posts.getJSONObject(i);
                    int id = post.optInt("channel_id", -1);
                    if (id > 0 && !ids.contains(id)) {
                        ids.add(id);
                        String postTitle = "";
                        for (String key : titleKeys) {
                            postTitle = post.optString(key, "").trim();
                            if (!postTitle.isEmpty()) break;
                        }
                        orderRows.add(new int[]{id, extractEpisodeNumber(postTitle), i});
                    }
                }
                Collections.sort(orderRows, (a, b) -> a[1] != b[1] ? Integer.compare(a[1], b[1]) : Integer.compare(a[2], b[2]));
                episodeOrderRows.clear();
                episodeOrderRows.addAll(orderRows);
                fetchEpisodeMediaList(ids, 0, new ArrayList<>());
            } catch (Exception e) {
                Log.e(TAG, "Playlist parse error", e);
                onPlaylistFailed();
            }
        }, error -> {
            Log.e(TAG, "Playlist network error", error);
            onPlaylistFailed();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("id", String.valueOf(categoryId));
                p.put("isAPKvalid", "true");
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void fetchEpisodeMediaList(List<Integer> ids, int index, ArrayList<EpisodeMedia> result) {
        if (ids == null || ids.isEmpty()) {
            playlistLoading = false;
            return;
        }
        if (index >= ids.size()) { applyPlaylist(result); return; }
        int nextIndex = Math.min(ids.size(), index + 4);
        AtomicInteger remaining = new AtomicInteger(nextIndex - index);
        for (int requestIndex = index; requestIndex < nextIndex; requestIndex++) {
            final int episodeIndex = requestIndex;
            final int id = ids.get(episodeIndex);
            Runnable complete = () -> {
                if (remaining.decrementAndGet() == 0) {
                    if (nextIndex >= ids.size()) applyPlaylist(result);
                    else fetchEpisodeMediaList(ids, nextIndex, result);
                }
            };
            requestEpisodeMedia(id, episodeIndex, 0, result, complete);
        }
    }

    private void requestEpisodeMedia(int id, int episodeIndex, int attempt, ArrayList<EpisodeMedia> result, Runnable complete) {
        Runnable retryOrComplete = () -> {
            if (attempt < 2 && !isFinishing() && !isDestroyed()) requestEpisodeMedia(id, episodeIndex, attempt + 1, result, complete);
            else complete.run();
        };
        StringRequest request = new StringRequest(Request.Method.POST, descriptionUrl(), response -> {
            boolean failed = false;
            try {
                JSONObject json = new JSONObject(response);
                if ("ok".equalsIgnoreCase(json.optString("status"))) {
                    int resolvedCategoryId = json.optInt("category_id", categoryId);
                    if (resolvedCategoryId == categoryId) {
                        String resolvedUrl = resolveVideoUrl(json);
                        if (isAvailable(resolvedUrl)) {
                            int resolvedChannelId = json.optInt("channel_id", id);
                            String resolvedCategoryName = json.optString("category_name", categoryName);
                            String rawTitle = json.optString("channel_name", "Episode " + (episodeIndex + 1));
                            int episodeNumber = extractEpisodeNumber(rawTitle);
                            String title = rawTitle + " • " + PlaybackQualityManager.getQualityLabel(lastResolvedQuality);
                            String img = json.optString("img_url", imageUrl);
                            synchronized (result) {
                                result.add(new EpisodeMedia(resolvedChannelId, resolvedCategoryId, resolvedCategoryName, title, img, resolvedUrl, episodeNumber, episodeIndex, AnxVideoAuth.fromDescription(json)));
                            }
                        }
                    }
                } else {
                    failed = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Episode media parse error", e);
                failed = true;
            }
            if (failed) retryOrComplete.run(); else complete.run();
        }, error -> {
            Log.e(TAG, "Episode media network error", error);
            retryOrComplete.run();
        }) {
            @Override protected Map<String, String> getParams() {
                Map<String, String> p = new HashMap<>();
                p.put("channel_id", String.valueOf(id));
                p.put("isAPKvalid", "true");
                p.put("gone", "1");
                p.put("category_id", String.valueOf(categoryId));
                return p;
            }
            @Override public Map<String, String> getHeaders() { return headers(); }
        };
        request.setShouldCache(false);
        request.setTag(TAG);
        requestQueue.add(request);
    }

    private void applyPlaylist(ArrayList<EpisodeMedia> episodes) {
        playlistLoading = false;
        if (player == null || episodes == null || episodes.size() <= 1) {
            onPlaylistFailed();
            return;
        }

        LinkedHashMap<Integer, EpisodeMedia> unique = new LinkedHashMap<>();
        for (EpisodeMedia episode : episodes) {
            if (episode != null && episode.channelId > 0 && !unique.containsKey(episode.channelId)) unique.put(episode.channelId, episode);
        }

        ArrayList<EpisodeMedia> ordered = new ArrayList<>(unique.values());
        if (ordered.size() <= 1) {
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
        if (currentIndex < 0) {
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
            try { return Integer.parseInt(matcher.group(1)); } catch (Exception ignored) {}
        }
        matcher = Pattern.compile("\\b(\\d{1,4})\\b").matcher(text);
        while (matcher.find()) {
            try {
                int n = Integer.parseInt(matcher.group(1));
                if (n != 360 && n != 720 && n != 1080) return n;
            } catch (Exception ignored) {}
        }
        return Integer.MAX_VALUE;
    }

    private String resolveVideoUrl(JSONObject json) {
        String secretKey = json.optString("secretKey", "");
        String sd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_ori"), secretKey)
        );
        String hd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_hd_ori"), secretKey)
        );
        String fhd = firstAvailable(
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd"), secretKey),
                AnimeXnontonUrlDecoder.decryptUrl(json.optString("channel_url_fhd_ori"), secretKey)
        );
        String[] qualities;
        String[] urls;
        if (PlaybackQualityManager.QUALITY_SD.equals(selectedQuality)) {
            qualities = new String[]{PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_FHD};
            urls = new String[]{sd, hd, fhd};
        } else if (PlaybackQualityManager.QUALITY_FHD.equals(selectedQuality)) {
            qualities = new String[]{PlaybackQualityManager.QUALITY_FHD, PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_SD};
            urls = new String[]{fhd, hd, sd};
        } else {
            qualities = new String[]{PlaybackQualityManager.QUALITY_HD, PlaybackQualityManager.QUALITY_SD, PlaybackQualityManager.QUALITY_FHD};
            urls = new String[]{hd, sd, fhd};
        }
        for (int i = 0; i < urls.length; i++) {
            if (isAvailable(urls[i])) {
                lastResolvedQuality = qualities[i];
                return urls[i];
            }
        }
        lastResolvedQuality = selectedQuality;
        return "";
    }

    private String firstAvailable(String... urls) {
        if (urls == null) return "";
        for (String url : urls) if (isAvailable(url)) return url;
        return "";
    }

    private boolean isAvailable(String url) {
        return url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim()) && url.startsWith("http");
    }

    private Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "max-age=0");
        h.put("Data-Agent", "AnimeXNonton 26.10.7/25");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("User-Agent", "AnimeXNonton/26.10.7 (25; Android 14; Pixel 8)");
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
        HistoryManager.save(this, new HistoryItem(episode.channelId, episode.categoryId, episode.categoryName, episode.title == null || episode.title.trim().isEmpty() ? "Anime" : episode.title, episode.imageUrl, episode.videoUrl, safePosition, safeDuration, System.currentTimeMillis()));
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
        outState.putString("video_auth", videoAuth);
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
