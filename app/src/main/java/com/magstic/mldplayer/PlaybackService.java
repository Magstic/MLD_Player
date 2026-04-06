package com.magstic.mldplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

public final class PlaybackService extends Service implements PlayerController.UiListener {
    private static final String CHANNEL_ID = "mld_player_playback";
    private static final int NOTIFICATION_ID = 1001;
    private static final String ACTION_PREVIOUS = "com.magstic.mldplayer.action.PREVIOUS";
    private static final String ACTION_PLAY_PAUSE = "com.magstic.mldplayer.action.PLAY_PAUSE";
    private static final String ACTION_NEXT = "com.magstic.mldplayer.action.NEXT";

    private PlayerController controller;
    private MediaSessionCompat mediaSession;
    private NotificationSnapshot lastNotificationSnapshot;
    private boolean startedInForeground;

    public static void start(Context context) {
        if (!canPostPlaybackNotification(context)) {
            return;
        }
        Intent intent = new Intent(context, PlaybackService.class);
        try {
            ContextCompat.startForegroundService(context, intent);
        } catch (RuntimeException ignored) {
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, PlaybackService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        controller = PlaybackControllerStore.get(getApplicationContext());
        mediaSession = new MediaSessionCompat(this, "MLDPlayerSession");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(contentIntent());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                PlayerController.PlayerUiState state = controller.currentPlayerUiState();

                if (state == null || !state.playing) {
                    controller.onPlayPauseRequested();
                }
            }

            @Override
            public void onPause() {
                PlayerController.PlayerUiState state = controller.currentPlayerUiState();

                if (state != null && state.playing) {
                    controller.onPlayPauseRequested();
                }
            }

            @Override
            public void onSkipToPrevious() {
                controller.onPreviousRequested();
            }

            @Override
            public void onSkipToNext() {
                controller.onNextRequested();
            }
        });
        mediaSession.setActive(true);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildBootstrapNotification());
        startedInForeground = true;
        controller.addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_PREVIOUS.equals(action)) {
            controller.onPreviousRequested();
        } else if (ACTION_PLAY_PAUSE.equals(action)) {
            controller.onPlayPauseRequested();
        } else if (ACTION_NEXT.equals(action)) {
            controller.onNextRequested();
        }
        updateNotification(controller.currentPlayerUiState());
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        controller.removeListener(this);
        lastNotificationSnapshot = null;
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        startedInForeground = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onPlayerUiStateChanged(PlayerController.PlayerUiState state) {
        updateNotification(state);
    }

    private void updateNotification(PlayerController.PlayerUiState state) {
        Notification notification;
        PlaybackStateCompat playbackState;
        NotificationSnapshot snapshot;
        Bitmap artwork;

        if (mediaSession == null) {
            return;
        }
        if (state == null || (!state.playing && !state.paused)) {
            lastNotificationSnapshot = null;
            stopForeground(STOP_FOREGROUND_REMOVE);
            startedInForeground = false;
            stopSelf();
            return;
        }

        playbackState = new PlaybackStateCompat.Builder()
                .setActions(resolvePlaybackActions(state))
                .setState(
                        state.playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        Math.max(0, state.positionMs),
                        state.playing ? 1f : 0f,
                        SystemClock.elapsedRealtime())
                .build();
        artwork = NotificationArtworkFactory.create(this, state.title, state.copyright);
        mediaSession.setPlaybackState(playbackState);
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.copyright)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, Math.max(0, state.durationMs))
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
                .putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
                .build());
        snapshot = NotificationSnapshot.from(state);
        if (startedInForeground && snapshot.equals(lastNotificationSnapshot)) {
            return;
        }

        notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(state.title)
                .setContentText(state.copyright)
                .setContentIntent(contentIntent())
                .setLargeIcon(artwork)
                .setColor(NotificationArtworkFactory.accentColor(state.title, state.copyright))
                .setColorized(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setOngoing(state.playing)
                .addAction(R.drawable.ic_skip_previous, getString(R.string.previous), actionIntent(ACTION_PREVIOUS, 1))
                .addAction(
                        state.playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow,
                        getString(state.playing ? R.string.pause : R.string.play),
                        actionIntent(ACTION_PLAY_PAUSE, 2))
                .addAction(R.drawable.ic_skip_next, getString(R.string.next), actionIntent(ACTION_NEXT, 3))
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();

        if (!startedInForeground) {
            try {
                startForeground(NOTIFICATION_ID, notification);
                startedInForeground = true;
                lastNotificationSnapshot = snapshot;
            } catch (RuntimeException ignored) {
                stopSelf();
            }
        } else {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                try {
                    manager.notify(NOTIFICATION_ID, notification);
                    lastNotificationSnapshot = snapshot;
                } catch (RuntimeException ignored) {
                    stopSelf();
                }
            }
        }
    }

    private Notification buildBootstrapNotification() {
        Bitmap artwork = NotificationArtworkFactory.create(this, getString(R.string.app_name), getString(R.string.state_preparing));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.state_preparing))
                .setContentIntent(contentIntent())
                .setLargeIcon(artwork)
                .setColor(NotificationArtworkFactory.accentColor(getString(R.string.app_name), getString(R.string.state_preparing)))
                .setColorized(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private PendingIntent contentIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(this, 0, intent, flags);
    }

    private PendingIntent actionIntent(String action, int requestCode) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, intent, flags);
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.playback_channel_description));
        manager.createNotificationChannel(channel);
    }

    private static boolean canPostPlaybackNotification(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (manager != null && !manager.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static long resolvePlaybackActions(PlayerController.PlayerUiState state) {
        long actions = PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT;

        if (state != null && state.playing) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        } else {
            actions |= PlaybackStateCompat.ACTION_PLAY;
        }
        return actions;
    }

    private static final class NotificationSnapshot {
        final String title;
        final String subtitle;
        final boolean playing;
        final boolean paused;

        NotificationSnapshot(String title, String subtitle, boolean playing, boolean paused) {
            this.title = title == null ? "" : title;
            this.subtitle = subtitle == null ? "" : subtitle;
            this.playing = playing;
            this.paused = paused;
        }

        static NotificationSnapshot from(PlayerController.PlayerUiState state) {
            return new NotificationSnapshot(state.title, state.copyright, state.playing, state.paused);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof NotificationSnapshot)) {
                return false;
            }
            NotificationSnapshot that = (NotificationSnapshot) other;
            return playing == that.playing
                    && paused == that.paused
                    && title.equals(that.title)
                    && subtitle.equals(that.subtitle);
        }

        @Override
        public int hashCode() {
            int result = title.hashCode();

            result = 31 * result + subtitle.hashCode();
            result = 31 * result + (playing ? 1 : 0);
            result = 31 * result + (paused ? 1 : 0);
            return result;
        }
    }
}
