package playback;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.media.Manager;
import javax.microedition.media.MediaException;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;

public final class J2meMidiPlayer implements PlayerListener {
    public static interface Listener {
        void onPlaybackCompleted();

        void onPlaybackError(String message);
    }

    private Player player;
    private InputStream playerStream;
    private Listener listener;

    public synchronized void play(byte[] midiBytes, int loopCount, Listener callback)
            throws IOException, MediaException {
        ByteArrayInputStream stream;
        Player createdPlayer;

        close();
        if (midiBytes == null || midiBytes.length == 0) {
            throw new IOException("Empty MIDI data");
        }

        stream = new ByteArrayInputStream(midiBytes);
        try {
            createdPlayer = Manager.createPlayer(stream, "audio/midi");
        } catch (IOException e) {
            closeQuietly(stream);
            throw e;
        } catch (MediaException e) {
            closeQuietly(stream);
            throw e;
        }

        try {
            createdPlayer.addPlayerListener(this);
        } catch (Throwable ignored) {
        }

        try {
            createdPlayer.prefetch();
        } catch (Throwable ignored) {
        }

        try {
            createdPlayer.setLoopCount(loopCount < 0 ? -1 : max(1, loopCount));
        } catch (Throwable ignored) {
        }

        player = createdPlayer;
        playerStream = stream;
        listener = callback;

        try {
            createdPlayer.start();
        } catch (MediaException e) {
            close();
            throw e;
        }
    }

    public synchronized void close() {
        Player currentPlayer = player;
        InputStream currentStream = playerStream;

        player = null;
        playerStream = null;
        listener = null;

        if (currentPlayer != null) {
            try {
                currentPlayer.stop();
            } catch (Throwable ignored) {
            }
            try {
                currentPlayer.close();
            } catch (Throwable ignored) {
            }
        }
        closeQuietly(currentStream);
    }

    public void playerUpdate(Player source, String event, Object eventData) {
        if (event == null) {
            return;
        }
        if (PlayerListener.END_OF_MEDIA.equals(event)) {
            handleEndOfMedia(source);
        } else if (PlayerListener.ERROR.equals(event)) {
            handleError(source, eventData);
        }
    }

    private void handleEndOfMedia(Player source) {
        Listener callback;
        synchronized (this) {
            if (source == null || source != player) {
                return;
            }
            callback = listener;
            close();
        }
        if (callback != null) {
            callback.onPlaybackCompleted();
        }
    }

    private void handleError(Player source, Object eventData) {
        Listener callback;
        String message;
        synchronized (this) {
            if (source == null || source != player) {
                return;
            }
            callback = listener;
            message = eventData == null ? "MIDI unsupported" : eventData.toString();
            close();
        }
        if (callback != null) {
            callback.onPlaybackError(message);
        }
    }

    private static int max(int left, int right) {
        return left > right ? left : right;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
