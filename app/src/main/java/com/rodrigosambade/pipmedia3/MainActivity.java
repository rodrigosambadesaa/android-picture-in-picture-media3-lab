package com.rodrigosambade.pipmedia3;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import java.util.Collections;

public class MainActivity extends Activity {

    private static final String VIDEO_URL =
            "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4";
    private static final Rational VIDEO_ASPECT_RATIO = new Rational(16, 9);

    private ExoPlayer player;
    private LinearLayout chrome;
    private TextView status;
    private ConnectivityAndInternetAccess connectivity;
    private ConnectivityAndInternetAccess.Request connectivityRequest;
    private boolean mediaPrepared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        PlayerView playerView = buildUi();
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                status.setText("Playback error: " + error.getErrorCodeName());
            }
        });

        connectivity = new ConnectivityAndInternetAccess.Builder()
                .setHosts(Collections.singletonList(VIDEO_URL))
                .build();

        configurePictureInPicture();
        verifyConnectivityAndPrepare();
    }

    private PlayerView buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        PlayerView playerView = new PlayerView(this);
        root.addView(
                playerView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        chrome = new LinearLayout(this);
        chrome.setOrientation(LinearLayout.VERTICAL);
        chrome.setPadding(24, 16, 24, 24);

        status = new TextView(this);
        status.setText("Checking connectivity…");
        chrome.addView(status);

        Button retry = new Button(this);
        retry.setText("Retry connectivity and playback");
        retry.setOnClickListener(view -> verifyConnectivityAndPrepare());
        chrome.addView(retry);

        Button enterPip = new Button(this);
        enterPip.setText("Enter Picture-in-Picture");
        enterPip.setOnClickListener(view -> enterPip());
        chrome.addView(enterPip);

        root.addView(chrome);
        setContentView(root);
        return playerView;
    }

    private void configurePictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setPictureInPictureParams(
                    new PictureInPictureParams.Builder()
                            .setAspectRatio(VIDEO_ASPECT_RATIO)
                            .setAutoEnterEnabled(true)
                            .build());
        }
    }

    private void verifyConnectivityAndPrepare() {
        if (connectivityRequest != null) {
            connectivityRequest.cancel();
        }

        ConnectivityAndInternetAccess.NetworkState state =
                ConnectivityAndInternetAccess.snapshotNetworkState(this);

        if (!state.isConnected()) {
            status.setText("No usable network is currently connected.");
            return;
        }
        if (state.isCaptivePortalDetected()) {
            status.setText("A captive portal was detected. Sign in, then retry.");
            return;
        }

        status.setText(
                state.isInternetValidated()
                        ? "Android validated Internet access; probing the video URL…"
                        : "Network connected; actively probing the video URL…");

        connectivityRequest = connectivity.checkInternetAsync(this, result -> {
            connectivityRequest = null;
            if (!result.isReachable()) {
                status.setText(
                        "The network is connected, but the video endpoint is not reachable. "
                                + "Retry after connectivity is restored.");
                return;
            }

            status.setText(
                    "Video endpoint reachable ("
                            + result.getElapsedMilliseconds()
                            + " ms). Playback ready.");
            prepareMediaIfNeeded();
        });
    }

    private void prepareMediaIfNeeded() {
        if (mediaPrepared) {
            player.play();
            return;
        }

        player.setMediaItem(MediaItem.fromUri(VIDEO_URL));
        player.prepare();
        player.play();
        mediaPrepared = true;
    }

    private void enterPip() {
        enterPictureInPictureMode(
                new PictureInPictureParams.Builder()
                        .setAspectRatio(VIDEO_ASPECT_RATIO)
                        .build());
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && mediaPrepared
                && !isInPictureInPictureMode()) {
            enterPip();
        }
    }

    @Override
    public void onPictureInPictureModeChanged(
            boolean inPictureInPictureMode,
            Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        chrome.setVisibility(inPictureInPictureMode ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (connectivityRequest != null) {
            connectivityRequest.cancel();
            connectivityRequest = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
