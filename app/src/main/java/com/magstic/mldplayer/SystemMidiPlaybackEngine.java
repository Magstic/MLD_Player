package com.magstic.mldplayer;

import android.content.Context;

import java.io.IOException;

public final class SystemMidiPlaybackEngine implements PlaybackEngine, AndroidMidiPlayer.Listener {
    private final AndroidMidiPlayer player;

    private PlaybackEngine.Listener listener;
    private PreparedTrack currentTrack;

    public SystemMidiPlaybackEngine(Context context) {
        this.player = new AndroidMidiPlayer(context.getApplicationContext());
        this.player.setListener(this);
    }

    @Override
    public void setListener(PlaybackEngine.Listener listener) {
        this.listener = listener;
    }

    @Override
    public void playTrack(PreparedTrack track, boolean loopCurrentTrack) throws IOException {
        if (track == null) {
            throw new IOException("Track unavailable");
        }
        currentTrack = track;
        if (loopCurrentTrack && track.hasInternalLoop) {
            player.playWithManualLoop(track.midiBytes, track.loopStartMs, track.durationMs, track.fileName);
            return;
        }
        player.play(track.midiBytes, loopCurrentTrack, track.fileName);
    }

    @Override
    public void pause() {
        player.pause();
    }

    @Override
    public void resume() {
        player.resume();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public boolean isPlaying() {
        return player.isPlaying();
    }

    @Override
    public boolean isPaused() {
        return player.isPaused();
    }

    @Override
    public int getCurrentPosition() {
        return player.getCurrentPosition();
    }

    @Override
    public int getDuration() {
        int duration = player.getDuration();

        if (duration > 0) {
            return duration;
        }
        return currentTrack == null ? -1 : currentTrack.durationMs;
    }

    @Override
    public void onPlaybackStarted() {
        if (listener != null) {
            listener.onPlaybackStarted();
        }
    }

    @Override
    public void onPlaybackLoopEntered() {
        if (listener != null) {
            listener.onPlaybackLoopEntered();
        }
    }

    @Override
    public void onPlaybackCompleted() {
        if (listener != null) {
            listener.onPlaybackCompleted();
        }
    }

    @Override
    public void onPlaybackError(String message) {
        if (listener != null) {
            listener.onPlaybackError(message);
        }
    }
}
