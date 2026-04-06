package com.magstic.mldplayer;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public final class AndroidMidiPlayer {
    public interface Listener {
        void onPlaybackStarted();

        void onPlaybackLoopEntered();

        void onPlaybackCompleted();

        void onPlaybackError(String message);
    }

    private static final int MEDIA_ERROR_UNKNOWN = 1;
    private static final int MEDIA_ERROR_UNSUPPORTED = -1010;
    private static final int LOOP_CHECK_INTERVAL_MS = 8;
    private static final int DEFAULT_LOOP_TRIGGER_SAFETY_MS = 28;
    private static final int MIN_LOOP_TRIGGER_SAFETY_MS = 12;
    private static final int MAX_LOOP_TRIGGER_SAFETY_MS = 96;

    private final Context context;
    private final HandlerThread loopThread;
    private final Handler loopHandler;
    private final Runnable loopCheckRunnable;

    private MediaPlayer mediaPlayer;
    private File tempMidiFile;
    private boolean paused;
    private boolean prepared;
    private boolean manualLoopActive;
    private boolean manualLoopEntered;
    private boolean manualLoopSeeking;
    private int manualLoopStartMs;
    private int manualLoopEndMs;
    private int manualLoopTriggerMs;
    private int estimatedSeekLatencyMs;
    private long manualLoopSeekRequestedAtMs;
    private boolean deleteSourceFileOnRelease;
    private Listener listener;

    public AndroidMidiPlayer(Context context) {
        this.context = context.getApplicationContext();
        this.loopThread = new HandlerThread("mld-loop-monitor");
        this.loopThread.start();
        this.loopHandler = new Handler(loopThread.getLooper());
        this.loopCheckRunnable = new Runnable() {
            @Override
            public void run() {
                pollManualLoop();
            }
        };
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void play(byte[] midiBytes, boolean looping, String fileHint) throws IOException {
        File cacheFile = writeTempMidi(midiBytes, fileHint);
        preparePlayer(cacheFile, looping, true);
    }

    public void playFile(File audioFile, boolean looping) throws IOException {
        preparePlayer(audioFile, looping, false);
    }

    public void playWithManualLoop(
            byte[] midiBytes,
            int loopStartMs,
            int loopEndMs,
            String fileHint) throws IOException {
        File cacheFile = writeTempMidi(midiBytes, fileHint);
        prepareManualLoopPlayer(cacheFile, loopStartMs, loopEndMs, true);
    }

    public void playFileWithManualLoop(
            File audioFile,
            int loopStartMs,
            int loopEndMs) throws IOException {
        prepareManualLoopPlayer(audioFile, loopStartMs, loopEndMs, false);
    }

    private void preparePlayer(File sourceFile, boolean looping, boolean deleteOnRelease) throws IOException {
        MediaPlayer next = new MediaPlayer();

        releasePlayer();
        tempMidiFile = sourceFile;
        deleteSourceFileOnRelease = deleteOnRelease;
        paused = false;
        prepared = false;
        clearManualLoopState();

        try {
            setDataSource(next, sourceFile);
            next.setLooping(looping);
            configureCommonCallbacks(next);
            mediaPlayer = next;
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            next.release();
            deleteTempFile(sourceFile);
            tempMidiFile = null;
            throw e;
        } catch (RuntimeException e) {
            next.release();
            deleteTempFile(sourceFile);
            tempMidiFile = null;
            throw new IOException("Cannot prepare MIDI playback", e);
        }
    }

    private void prepareManualLoopPlayer(
            File sourceFile,
            int loopStartMs,
            int loopEndMs,
            boolean deleteOnRelease) throws IOException {
        MediaPlayer next = new MediaPlayer();

        releasePlayer();
        tempMidiFile = sourceFile;
        deleteSourceFileOnRelease = deleteOnRelease;
        paused = false;
        prepared = false;
        manualLoopActive = loopStartMs >= 0 && loopEndMs > loopStartMs;
        manualLoopEntered = false;
        manualLoopSeeking = false;
        manualLoopStartMs = Math.max(0, loopStartMs);
        manualLoopEndMs = Math.max(manualLoopStartMs + 1, loopEndMs);
        estimatedSeekLatencyMs = DEFAULT_LOOP_TRIGGER_SAFETY_MS;
        manualLoopSeekRequestedAtMs = 0L;
        refreshManualLoopTriggerMs();

        try {
            setDataSource(next, sourceFile);
            next.setLooping(false);
            configureCommonCallbacks(next);
            next.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer player) {
                    paused = false;
                    prepared = true;
                    if (manualLoopActive && performManualLoopJump(true)) {
                        return;
                    }
                    stopLoopChecks();
                    if (listener != null) {
                        listener.onPlaybackCompleted();
                    }
                }
            });
            next.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
                @Override
                public void onSeekComplete(MediaPlayer player) {
                    if (player != mediaPlayer) {
                        return;
                    }
                    updateEstimatedSeekLatency();
                    manualLoopSeeking = false;
                    if (manualLoopActive && !manualLoopEntered) {
                        manualLoopEntered = true;
                        if (listener != null) {
                            listener.onPlaybackLoopEntered();
                        }
                    }
                    if (!paused) {
                        try {
                            if (!player.isPlaying()) {
                                player.start();
                            }
                        } catch (IllegalStateException ignored) {
                        }
                    }
                    startLoopChecks();
                }
            });
            mediaPlayer = next;
            mediaPlayer.prepareAsync();
        } catch (IOException e) {
            next.release();
            deleteTempFile(sourceFile);
            tempMidiFile = null;
            clearManualLoopState();
            throw e;
        } catch (RuntimeException e) {
            next.release();
            deleteTempFile(sourceFile);
            tempMidiFile = null;
            clearManualLoopState();
            throw new IOException("Cannot prepare MIDI playback", e);
        }
    }

    public void pause() {
        if (mediaPlayer != null && prepared && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            paused = true;
            stopLoopChecks();
        }
    }

    public void resume() {
        if (mediaPlayer != null && prepared && paused) {
            mediaPlayer.start();
            paused = false;
            startLoopChecks();
        }
    }

    public void stop() {
        releasePlayer();
        paused = false;
    }

    public void release() {
        stop();
        listener = null;
        loopThread.quitSafely();
    }

    public boolean isPlaying() {
        return mediaPlayer != null && prepared && mediaPlayer.isPlaying();
    }

    public boolean isPaused() {
        return mediaPlayer != null && prepared && paused && !mediaPlayer.isPlaying();
    }

    public int getCurrentPosition() {
        if (mediaPlayer == null || !prepared) {
            return 0;
        }
        try {
            return mediaPlayer.getCurrentPosition();
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    public int getDuration() {
        if (mediaPlayer == null || !prepared) {
            return -1;
        }
        try {
            return mediaPlayer.getDuration();
        } catch (IllegalStateException ignored) {
            return -1;
        }
    }

    private void configureCommonCallbacks(MediaPlayer player) {
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer preparedPlayer) {
                prepared = true;
                preparedPlayer.start();
                startLoopChecks();
                if (listener != null) {
                    listener.onPlaybackStarted();
                }
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer completedPlayer) {
                paused = false;
                prepared = true;
                stopLoopChecks();
                if (listener != null) {
                    listener.onPlaybackCompleted();
                }
            }
        });
        attachErrorListener(player);
    }

    private void pollManualLoop() {
        MediaPlayer player = mediaPlayer;

        if (!manualLoopActive || manualLoopSeeking || player == null || !prepared || paused) {
            return;
        }
        try {
            if (!player.isPlaying()) {
                loopHandler.postDelayed(loopCheckRunnable, LOOP_CHECK_INTERVAL_MS);
                return;
            }
            int position = player.getCurrentPosition();
            if (position >= manualLoopTriggerMs) {
                performManualLoopJump(false);
                return;
            }
        } catch (IllegalStateException ignored) {
            return;
        }
        loopHandler.postDelayed(loopCheckRunnable, LOOP_CHECK_INTERVAL_MS);
    }

    private boolean performManualLoopJump(boolean forceFromCompletion) {
        MediaPlayer player = mediaPlayer;

        if (!manualLoopActive || manualLoopSeeking || player == null || !prepared) {
            return false;
        }

        manualLoopSeeking = true;
        manualLoopSeekRequestedAtMs = SystemClock.uptimeMillis();
        stopLoopChecks();
        try {
            player.seekTo(manualLoopStartMs, MediaPlayer.SEEK_CLOSEST);
            if (forceFromCompletion && !player.isPlaying() && !paused) {
                player.start();
            }
            return true;
        } catch (RuntimeException e) {
            manualLoopSeeking = false;
            stopLoopChecks();
            if (listener != null) {
                listener.onPlaybackError("Loop seek failed");
            }
            return false;
        }
    }

    private void startLoopChecks() {
        stopLoopChecks();
        if (manualLoopActive && !manualLoopSeeking && !paused && mediaPlayer != null && prepared) {
            loopHandler.postDelayed(loopCheckRunnable, LOOP_CHECK_INTERVAL_MS);
        }
    }

    private void stopLoopChecks() {
        loopHandler.removeCallbacks(loopCheckRunnable);
    }

    private void clearManualLoopState() {
        stopLoopChecks();
        manualLoopActive = false;
        manualLoopEntered = false;
        manualLoopSeeking = false;
        manualLoopStartMs = 0;
        manualLoopEndMs = 0;
        manualLoopTriggerMs = 0;
        estimatedSeekLatencyMs = DEFAULT_LOOP_TRIGGER_SAFETY_MS;
        manualLoopSeekRequestedAtMs = 0L;
    }

    private File writeTempMidi(byte[] midiBytes, String fileHint) throws IOException {
        File output = File.createTempFile(safePrefix(fileHint), ".mid", context.getCacheDir());
        FileOutputStream stream = new FileOutputStream(output);

        try {
            stream.write(midiBytes);
            stream.flush();
        } finally {
            stream.close();
        }
        return output;
    }

    private void releasePlayer() {
        MediaPlayer oldPlayer = mediaPlayer;
        File oldTempFile = tempMidiFile;
        boolean deleteFile = deleteSourceFileOnRelease;

        mediaPlayer = null;
        tempMidiFile = null;
        deleteSourceFileOnRelease = false;
        prepared = false;
        clearManualLoopState();
        releaseSinglePlayer(oldPlayer, oldTempFile, deleteFile);
    }

    private void refreshManualLoopTriggerMs() {
        int loopLengthMs = Math.max(1, manualLoopEndMs - manualLoopStartMs);
        int desiredLeadMs = estimatedSeekLatencyMs + (LOOP_CHECK_INTERVAL_MS * 2);
        int maxLeadMs = Math.min(MAX_LOOP_TRIGGER_SAFETY_MS, Math.max(MIN_LOOP_TRIGGER_SAFETY_MS, loopLengthMs / 6));

        if (desiredLeadMs < MIN_LOOP_TRIGGER_SAFETY_MS) {
            desiredLeadMs = MIN_LOOP_TRIGGER_SAFETY_MS;
        }
        if (desiredLeadMs > maxLeadMs) {
            desiredLeadMs = maxLeadMs;
        }
        manualLoopTriggerMs = Math.max(manualLoopStartMs, manualLoopEndMs - desiredLeadMs);
    }

    private void updateEstimatedSeekLatency() {
        if (manualLoopSeekRequestedAtMs <= 0L) {
            refreshManualLoopTriggerMs();
            return;
        }

        long elapsedMs = SystemClock.uptimeMillis() - manualLoopSeekRequestedAtMs;
        manualLoopSeekRequestedAtMs = 0L;
        if (elapsedMs <= 0L) {
            refreshManualLoopTriggerMs();
            return;
        }

        int measuredMs = (int) Math.min(Integer.MAX_VALUE, elapsedMs);
        estimatedSeekLatencyMs = ((estimatedSeekLatencyMs * 3) + measuredMs) / 4;
        if (estimatedSeekLatencyMs < MIN_LOOP_TRIGGER_SAFETY_MS) {
            estimatedSeekLatencyMs = MIN_LOOP_TRIGGER_SAFETY_MS;
        }
        if (estimatedSeekLatencyMs > MAX_LOOP_TRIGGER_SAFETY_MS) {
            estimatedSeekLatencyMs = MAX_LOOP_TRIGGER_SAFETY_MS;
        }
        refreshManualLoopTriggerMs();
    }

    private static void deleteTempFile(File file) {
        if (file != null && file.exists()) {
            file.delete();
        }
    }

    private void attachErrorListener(MediaPlayer player) {
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer failedPlayer, int what, int extra) {
                String message = errorMessage(what, extra);

                stop();
                if (listener != null) {
                    listener.onPlaybackError(message);
                }
                return true;
            }
        });
    }

    private static void setDataSource(MediaPlayer player, File file) throws IOException {
        java.io.FileInputStream input = new java.io.FileInputStream(file);

        try {
            player.setDataSource(input.getFD());
        } finally {
            input.close();
        }
    }

    private static void releaseSinglePlayer(MediaPlayer player, File file, boolean deleteFile) {
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {
            }
            try {
                player.reset();
            } catch (IllegalStateException ignored) {
            }
            player.release();
        }
        if (deleteFile) {
            deleteTempFile(file);
        }
    }

    private static String errorMessage(int what, int extra) {
        if (what == MEDIA_ERROR_UNSUPPORTED || extra == MEDIA_ERROR_UNSUPPORTED) {
            return "MIDI unsupported on this device";
        }
        if (what == MEDIA_ERROR_UNKNOWN && extra == -38) {
            return "Playback initialization failed";
        }
        return "MIDI playback failed";
    }

    private static String safePrefix(String fileHint) {
        String cleaned = fileHint == null ? "" : fileHint.replaceAll("[^a-zA-Z0-9_\\-]", "");

        if (cleaned.length() < 3) {
            cleaned = "mldtrack";
        } else if (cleaned.length() > 24) {
            cleaned = cleaned.substring(0, 24);
        }
        return cleaned;
    }
}
