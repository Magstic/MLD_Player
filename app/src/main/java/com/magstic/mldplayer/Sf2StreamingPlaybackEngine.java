package com.magstic.mldplayer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class Sf2StreamingPlaybackEngine implements PlaybackEngine {
    private static final String TAG = "MLDPlayerSF2";
    private static final int CHANNEL_COUNT = 2;
    private static final int SHORTS_PER_FRAME = CHANNEL_COUNT;
    private static final int BLOCK_FRAMES = 1024;
    private static final int WARM_PREROLL_FRAMES = BLOCK_FRAMES * 2;

    private final Context context;
    private final Object playbackLock = new Object();
    private final ExecutorService loopCursorExecutor;

    private PlaybackEngine.Listener listener;
    private ActivePlayback activePlayback;
    private AudioTrack audioTrack;
    private Thread renderThread;
    private File soundFontFile;
    private boolean paused;
    private boolean stopRequested;
    private boolean released;

    public Sf2StreamingPlaybackEngine(Context context) {
        this.context = context.getApplicationContext();
        this.loopCursorExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void setListener(PlaybackEngine.Listener listener) {
        this.listener = listener;
    }

    public void setSoundFontFile(File soundFontFile) {
        this.soundFontFile = soundFontFile;
    }

    @Override
    public void playTrack(PreparedTrack track, boolean loopCurrentTrack) throws IOException {
        beginPlayback(createActivePlayback(soundFontFile, createCursor(soundFontFile, track), loopCurrentTrack, null, 0));
    }

    public WarmSession warmSession(File soundFontFile, PreparedTrack track) throws IOException {
        PlaybackCursor cursor = createCursor(soundFontFile, track);
        short[] prebuffer = new short[WARM_PREROLL_FRAMES * SHORTS_PER_FRAME];
        int prebufferedFrames = renderFrames(cursor, prebuffer, WARM_PREROLL_FRAMES, false, null);

        return new WarmSession(track, soundFontFile, cursor, prebuffer, prebufferedFrames);
    }

    public void playWarmSession(WarmSession warmSession, boolean loopCurrentTrack) throws IOException {
        if (warmSession == null) {
            throw new IOException("Warm session unavailable");
        }
        beginPlayback(createActivePlayback(
                warmSession.soundFontFile,
                warmSession.takeCursor(),
                loopCurrentTrack,
                warmSession.takePrebuffer(),
                warmSession.takePrebufferedFrames()));
    }

    @Override
    public void pause() {
        synchronized (playbackLock) {
            if (audioTrack == null || paused) {
                return;
            }
            paused = true;
            if (activePlayback != null) {
                activePlayback.pause();
            }
            audioTrack.pause();
        }
    }

    @Override
    public void resume() {
        synchronized (playbackLock) {
            if (audioTrack == null || !paused) {
                return;
            }
            paused = false;
            if (activePlayback != null) {
                activePlayback.resume();
            }
            audioTrack.play();
            playbackLock.notifyAll();
        }
    }

    @Override
    public void stop() {
        stopPlayback(false);
    }

    @Override
    public void release() {
        released = true;
        stopPlayback(false);
        loopCursorExecutor.shutdownNow();
    }

    @Override
    public boolean isPlaying() {
        synchronized (playbackLock) {
            return activePlayback != null && !paused;
        }
    }

    @Override
    public boolean isPaused() {
        synchronized (playbackLock) {
            return activePlayback != null && paused;
        }
    }

    @Override
    public int getCurrentPosition() {
        synchronized (playbackLock) {
            return activePlayback == null ? 0 : activePlayback.currentPositionMs();
        }
    }

    @Override
    public int getDuration() {
        synchronized (playbackLock) {
            return activePlayback == null ? -1 : activePlayback.track.durationMs;
        }
    }

    private void beginPlayback(ActivePlayback nextPlayback) throws IOException {
        AudioTrack nextAudioTrack = buildAudioTrack();
        Thread nextRenderThread;

        stopPlayback(false);
        synchronized (playbackLock) {
            if (released) {
                nextPlayback.release();
                nextAudioTrack.release();
                return;
            }
            activePlayback = nextPlayback;
            audioTrack = nextAudioTrack;
            paused = false;
            stopRequested = false;
            nextPlayback.start();
            audioTrack.play();
            nextRenderThread = new Thread(this::runRenderLoop, "mld-sf2-stream");
            renderThread = nextRenderThread;
        }
        if (listener != null) {
            listener.onPlaybackStarted();
        }
        nextRenderThread.start();
    }

    private void runRenderLoop() {
        short[] renderBuffer = new short[BLOCK_FRAMES * SHORTS_PER_FRAME];
        ActivePlayback playback = null;
        AudioTrack track;
        boolean completedNaturally = false;

        while (true) {
            synchronized (playbackLock) {
                while (!stopRequested && paused) {
                    try {
                        playbackLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        stopRequested = true;
                        break;
                    }
                }
                if (stopRequested) {
                    break;
                }
                playback = activePlayback;
                track = audioTrack;
            }
            if (playback == null || track == null) {
                break;
            }
            try {
                if (playback.prebufferedFrames > 0) {
                    int writtenShorts = playback.prebufferedFrames * SHORTS_PER_FRAME;
                    writeBlocking(track, playback.prebuffer, writtenShorts);
                    playback.clearPrebuffer();
                    continue;
                }
                int renderedFrames = renderFrames(playback.cursor, renderBuffer, BLOCK_FRAMES, playback.loopCurrentTrack, playback);
                if (renderedFrames <= 0) {
                    completedNaturally = !stopRequested;
                    break;
                }
                writeBlocking(track, renderBuffer, renderedFrames * SHORTS_PER_FRAME);
            } catch (IOException e) {
                Log.e(TAG, "Render loop error for " + playback.track.fileName + ": " + e.getMessage());
                if (listener != null) {
                    listener.onPlaybackError(e.getMessage() == null ? "SF2 playback failed" : e.getMessage());
                }
                break;
            }
        }
        if (completedNaturally) {
            Log.d(TAG, "Render loop completed naturally for " + (playback == null ? "<none>" : playback.track.fileName)
                    + " frame=" + (playback == null ? -1 : playback.cursor.framePosition)
                    + " endFrame=" + (playback == null ? -1 : playback.track.playbackEndFrame));
        }
        stopPlayback(completedNaturally);
    }

    private void stopPlayback(boolean notifyCompletion) {
        Thread threadToJoin;
        AudioTrack trackToRelease;
        ActivePlayback playbackToRelease;

        synchronized (playbackLock) {
            threadToJoin = renderThread;
            trackToRelease = audioTrack;
            playbackToRelease = activePlayback;
            stopRequested = true;
            paused = false;
            renderThread = null;
            audioTrack = null;
            activePlayback = null;
            playbackLock.notifyAll();
        }
        if (threadToJoin != null && threadToJoin != Thread.currentThread()) {
            try {
                threadToJoin.join(500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (trackToRelease != null) {
            try {
                trackToRelease.pause();
            } catch (RuntimeException ignored) {
            }
            try {
                trackToRelease.flush();
            } catch (RuntimeException ignored) {
            }
            trackToRelease.release();
        }
        if (playbackToRelease != null) {
            playbackToRelease.release();
        }
        synchronized (playbackLock) {
            stopRequested = false;
        }
        if (notifyCompletion && listener != null && !released) {
            listener.onPlaybackCompleted();
        }
    }

    private AudioTrack buildAudioTrack() {
        int minBufferBytes = AudioTrack.getMinBufferSize(
                NativeSf2Synth.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        int desiredBufferBytes = Math.max(minBufferBytes, BLOCK_FRAMES * SHORTS_PER_FRAME * 4);

        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(NativeSf2Synth.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(desiredBufferBytes)
                .build();
    }

    private void writeBlocking(AudioTrack track, short[] buffer, int shortCount) throws IOException {
        int offset = 0;

        while (offset < shortCount) {
            int written = track.write(buffer, offset, shortCount - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IOException("AudioTrack write failed");
            }
            offset += written;
        }
    }

    private ActivePlayback createActivePlayback(
            File soundFontFile,
            PlaybackCursor cursor,
            boolean loopCurrentTrack,
            short[] prebuffer,
            int prebufferedFrames) throws IOException {
        ActivePlayback playback = new ActivePlayback(soundFontFile, cursor, loopCurrentTrack, prebuffer, prebufferedFrames);

        if (loopCurrentTrack && cursor.track.hasInternalLoop) {
            playback.setLoopResumeCursor(buildLoopResumeCursor(soundFontFile, cursor.track));
        }
        return playback;
    }

    private PlaybackCursor createCursor(File soundFontFile, PreparedTrack track) throws IOException {
        if (soundFontFile == null || !soundFontFile.isFile()) {
            throw new IOException("SF2 file unavailable");
        }
        PlaybackCursor cursor = new PlaybackCursor(track, soundFontFile, new NativeSf2Synth(soundFontFile));

        applyDueEvents(cursor);
        return cursor;
    }

    private int renderFrames(
            PlaybackCursor cursor,
            short[] outBuffer,
            int maxFrames,
            boolean loopCurrentTrack,
            @SuppressWarnings("SameParameterValue") ActivePlayback playback) throws IOException {
        int framesWritten = 0;

        while (framesWritten < maxFrames) {
            int boundaryFrame = cursor.track.playbackEndFrame;
            List<PreparedTrack.SynthEvent> synthEvents = cursor.track.synthEvents;

            applyDueEvents(cursor);
            if (cursor.eventIndex < synthEvents.size()) {
                boundaryFrame = Math.min(boundaryFrame, synthEvents.get(cursor.eventIndex).sampleFrame);
            }
            if (boundaryFrame > cursor.framePosition) {
                int framesToRender = Math.min(maxFrames - framesWritten, boundaryFrame - cursor.framePosition);
                cursor.synth.render(outBuffer, framesWritten * SHORTS_PER_FRAME, framesToRender);
                cursor.framePosition += framesToRender;
                framesWritten += framesToRender;
                continue;
            }
            if (cursor.eventIndex < synthEvents.size()
                    && synthEvents.get(cursor.eventIndex).sampleFrame == cursor.framePosition) {
                applyDueEvents(cursor);
                continue;
            }
            if (cursor.framePosition >= cursor.track.playbackEndFrame) {
                if (!loopCurrentTrack) {
                    break;
                }
                restoreLoopCursor(cursor, playback);
                if (playback != null && !playback.loopEntered) {
                    playback.loopEntered = true;
                    if (listener != null) {
                        listener.onPlaybackLoopEntered();
                    }
                }
                continue;
            }
            break;
        }
        return framesWritten;
    }

    private void applyDueEvents(PlaybackCursor cursor) throws IOException {
        List<PreparedTrack.SynthEvent> synthEvents = cursor.track.synthEvents;

        while (cursor.eventIndex < synthEvents.size()) {
            PreparedTrack.SynthEvent event = synthEvents.get(cursor.eventIndex);

            if (event.sampleFrame != cursor.framePosition) {
                break;
            }
            cursor.synth.sendEvent(event.status, event.channel, event.data1, event.data2);
            cursor.eventIndex++;
        }
    }

    private void restoreLoopCursor(PlaybackCursor cursor, ActivePlayback playback) throws IOException {
        PlaybackCursor loopResumeCursor = playback == null ? null : playback.takeLoopResumeCursor();

        if (loopResumeCursor != null) {
            cursor.replaceWith(loopResumeCursor);
            scheduleLoopResumeCursorRebuild(playback);
            return;
        }
        resetCursorForLoop(cursor);
        scheduleLoopResumeCursorRebuild(playback);
    }

    private void resetCursorForLoop(PlaybackCursor cursor) throws IOException {
        int loopStartFrame = cursor.track.hasInternalLoop ? cursor.track.loopStartFrame : 0;

        cursor.releaseSynth();
        cursor.synth = new NativeSf2Synth(cursor.soundFontFile);
        cursor.eventIndex = 0;
        cursor.framePosition = 0;
        applyDueEvents(cursor);
        fastForwardCursor(cursor, loopStartFrame);
    }

    private PlaybackCursor buildLoopResumeCursor(File soundFontFile, PreparedTrack track) throws IOException {
        PlaybackCursor cursor = createCursor(soundFontFile, track);

        fastForwardCursor(cursor, track.loopStartFrame);
        return cursor;
    }

    private void fastForwardCursor(PlaybackCursor cursor, int targetFrame) throws IOException {
        short[] scratch = new short[BLOCK_FRAMES * SHORTS_PER_FRAME];

        while (cursor.framePosition < targetFrame) {
            int chunkFrames = Math.min(BLOCK_FRAMES, targetFrame - cursor.framePosition);
            int rendered = renderUntil(cursor, scratch, chunkFrames, targetFrame);

            if (rendered <= 0) {
                break;
            }
        }
    }

    private void scheduleLoopResumeCursorRebuild(final ActivePlayback playback) {
        if (playback == null || !playback.loopCurrentTrack || !playback.track.hasInternalLoop) {
            return;
        }
        loopCursorExecutor.execute(new Runnable() {
            @Override
            public void run() {
                PlaybackCursor rebuiltCursor = null;

                try {
                    rebuiltCursor = buildLoopResumeCursor(playback.soundFontFile, playback.track);
                    synchronized (playbackLock) {
                        if (released || activePlayback != playback) {
                            rebuiltCursor.releaseSynth();
                            return;
                        }
                    }
                    playback.setLoopResumeCursor(rebuiltCursor);
                } catch (IOException e) {
                    if (rebuiltCursor != null) {
                        rebuiltCursor.releaseSynth();
                    }
                    Log.w(TAG, "Cannot rebuild loop cursor for " + playback.track.fileName + ": " + e.getMessage());
                }
            }
        });
    }

    private int renderUntil(PlaybackCursor cursor, short[] scratch, int maxFrames, int targetFrame) throws IOException {
        int framesWritten = 0;
        List<PreparedTrack.SynthEvent> synthEvents = cursor.track.synthEvents;

        while (framesWritten < maxFrames && cursor.framePosition < targetFrame) {
            int boundaryFrame = targetFrame;

            applyDueEvents(cursor);
            if (cursor.eventIndex < synthEvents.size()) {
                boundaryFrame = Math.min(boundaryFrame, synthEvents.get(cursor.eventIndex).sampleFrame);
            }
            if (boundaryFrame > cursor.framePosition) {
                int framesToRender = Math.min(maxFrames - framesWritten, boundaryFrame - cursor.framePosition);
                cursor.synth.render(scratch, 0, framesToRender);
                cursor.framePosition += framesToRender;
                framesWritten += framesToRender;
                continue;
            }
            if (cursor.eventIndex < synthEvents.size()
                    && synthEvents.get(cursor.eventIndex).sampleFrame == cursor.framePosition) {
                applyDueEvents(cursor);
                continue;
            }
            break;
        }
        return framesWritten;
    }

    public static final class WarmSession {
        private final PreparedTrack track;
        private final File soundFontFile;
        private PlaybackCursor cursor;
        private short[] prebuffer;
        private int prebufferedFrames;

        WarmSession(
                PreparedTrack track,
                File soundFontFile,
                PlaybackCursor cursor,
                short[] prebuffer,
                int prebufferedFrames) {
            this.track = track;
            this.soundFontFile = soundFontFile;
            this.cursor = cursor;
            this.prebuffer = prebuffer;
            this.prebufferedFrames = prebufferedFrames;
        }

        public PreparedTrack track() {
            return track;
        }

        public void release() {
            if (cursor != null) {
                cursor.releaseSynth();
                cursor = null;
            }
            prebuffer = null;
            prebufferedFrames = 0;
        }

        PlaybackCursor takeCursor() throws IOException {
            PlaybackCursor value = cursor;

            if (value == null) {
                throw new IOException("Warm session already consumed");
            }
            cursor = null;
            return value;
        }

        short[] takePrebuffer() {
            short[] value = prebuffer;

            prebuffer = null;
            return value;
        }

        int takePrebufferedFrames() {
            int value = prebufferedFrames;

            prebufferedFrames = 0;
            return value;
        }
    }

    private static final class PlaybackCursor {
        final PreparedTrack track;
        final File soundFontFile;
        NativeSf2Synth synth;
        int eventIndex;
        int framePosition;

        PlaybackCursor(PreparedTrack track, File soundFontFile, NativeSf2Synth synth) {
            this.track = track;
            this.soundFontFile = soundFontFile;
            this.synth = synth;
        }

        void releaseSynth() {
            if (synth != null) {
                synth.release();
                synth = null;
            }
        }

        void replaceWith(PlaybackCursor other) {
            releaseSynth();
            synth = other.synth;
            eventIndex = other.eventIndex;
            framePosition = other.framePosition;
            other.synth = null;
            other.eventIndex = 0;
            other.framePosition = 0;
        }
    }

    private static final class ActivePlayback {
        final File soundFontFile;
        final PlaybackCursor cursor;
        final PreparedTrack track;
        final boolean loopCurrentTrack;
        short[] prebuffer;
        int prebufferedFrames;
        long startedAtMs;
        long pausedAtMs;
        long totalPausedMs;
        boolean loopEntered;
        PlaybackCursor loopResumeCursor;

        ActivePlayback(
                File soundFontFile,
                PlaybackCursor cursor,
                boolean loopCurrentTrack,
                short[] prebuffer,
                int prebufferedFrames) {
            this.soundFontFile = soundFontFile;
            this.cursor = cursor;
            this.track = cursor.track;
            this.loopCurrentTrack = loopCurrentTrack;
            this.prebuffer = prebuffer;
            this.prebufferedFrames = prebufferedFrames;
        }

        void start() {
            startedAtMs = SystemClock.uptimeMillis();
            pausedAtMs = 0L;
            totalPausedMs = 0L;
        }

        void pause() {
            if (pausedAtMs == 0L) {
                pausedAtMs = SystemClock.uptimeMillis();
            }
        }

        void resume() {
            if (pausedAtMs != 0L) {
                totalPausedMs += SystemClock.uptimeMillis() - pausedAtMs;
                pausedAtMs = 0L;
            }
        }

        int currentPositionMs() {
            long now = pausedAtMs != 0L ? pausedAtMs : SystemClock.uptimeMillis();
            long elapsedMs = Math.max(0L, now - startedAtMs - totalPausedMs);

            if (!loopCurrentTrack) {
                return (int) Math.min(track.durationMs, elapsedMs);
            }
            if (!track.hasInternalLoop) {
                long durationMs = Math.max(1, track.durationMs);
                return (int) (elapsedMs % durationMs);
            }
            if (elapsedMs <= track.durationMs) {
                return (int) elapsedMs;
            }
            long loopWindowMs = Math.max(1, track.durationMs - track.loopStartMs);
            return track.loopStartMs + (int) ((elapsedMs - track.durationMs) % loopWindowMs);
        }

        void clearPrebuffer() {
            prebuffer = null;
            prebufferedFrames = 0;
        }

        PlaybackCursor takeLoopResumeCursor() {
            PlaybackCursor cursor = loopResumeCursor;

            loopResumeCursor = null;
            return cursor;
        }

        void setLoopResumeCursor(PlaybackCursor cursor) {
            if (this.cursor.synth == null) {
                if (cursor != null) {
                    cursor.releaseSynth();
                }
                return;
            }
            if (loopResumeCursor != null) {
                loopResumeCursor.releaseSynth();
            }
            loopResumeCursor = cursor;
        }

        void release() {
            if (loopResumeCursor != null) {
                loopResumeCursor.releaseSynth();
                loopResumeCursor = null;
            }
            cursor.releaseSynth();
        }
    }
}
