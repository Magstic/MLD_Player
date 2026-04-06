package com.magstic.mldplayer;

import java.io.IOException;

public interface PlaybackEngine {
    interface Listener {
        void onPlaybackStarted();

        void onPlaybackLoopEntered();

        void onPlaybackCompleted();

        void onPlaybackError(String message);
    }

    void setListener(Listener listener);

    void playTrack(PreparedTrack track, boolean loopCurrentTrack) throws IOException;

    void pause();

    void resume();

    void stop();

    void release();

    boolean isPlaying();

    boolean isPaused();

    int getCurrentPosition();

    int getDuration();
}
