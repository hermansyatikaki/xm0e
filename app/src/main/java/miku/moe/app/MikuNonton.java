package miku.moe.app;

import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

public class MikuNonton extends Fragment {
    private static final String ARG_VIDEO_URL = "video_url";
    private PlayerView playerView;
    private ExoPlayer player;

    public static MikuNonton newInstance(String videoUrl) {
        MikuNonton fragment = new MikuNonton();
        Bundle args = new Bundle(); args.putString(ARG_VIDEO_URL, videoUrl); fragment.setArguments(args); return fragment;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.activity_video_player, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        playerView = view.findViewById(R.id.player_view);
        player = new ExoPlayer.Builder(requireContext()).build();
        playerView.setPlayer(player);
        String videoUrl = getArguments() == null ? null : getArguments().getString(ARG_VIDEO_URL);
        if (videoUrl != null && !videoUrl.isEmpty()) playVideo(videoUrl);
    }

    private void playVideo(String videoUrl) {
        // The retired static Basic credential is rejected by the server, and
        // signed whatbox URLs must be fetched WITHOUT an Authorization header.
        // What the CDN actually requires is the official ANX player UA:
        // any other User-Agent gets HTTP 403 even for a valid signed link.
        DefaultHttpDataSource.Factory dataSourceFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(AnxPlayerUa.userAgent());
        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));
        MediaSource mediaSource = new ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem);
        player.setMediaSource(mediaSource);
        player.prepare();
        player.play();
    }

    @Override public void onStop() { super.onStop(); releasePlayer(); }
    @Override public void onDestroyView() { super.onDestroyView(); releasePlayer(); playerView = null; }
    private void releasePlayer() { if (player != null) { player.release(); player = null; } }
}
