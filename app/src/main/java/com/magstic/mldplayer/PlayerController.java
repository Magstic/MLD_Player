package com.magstic.mldplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import container.MldFile;
import container.MldParser;
import event.TrackDecodeResult;
import event.TrackDecoder;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;
import playback.MidiFileEncoder;
import playback.MidiMessageConstants;
import timeline.PlaybackTimeline;
import timeline.TimelineCompiler;

public final class PlayerController implements PlaybackEngine.Listener {
    private static final String TAG = "MLDPlayer";
    public interface Host {
        Context getHostContext();

        void requestOpenFolderPicker();

        void requestOpenSf2Picker();

        void showMessage(String message);
    }

    public interface UiListener {
        default void onPlayerUiStateChanged(PlayerUiState state) {
        }

        default void onImportUiStateChanged(ImportUiState state) {
        }

        default void onSettingsUiStateChanged(SettingsUiState state) {
        }
    }

    public static final class PlayerUiState {
        public final String pageSubtitle;
        public final String title;
        public final String copyright;
        public final String fileName;
        public final String status;
        public final int positionMs;
        public final int durationMs;
        public final boolean playing;
        public final boolean paused;
        public final boolean loading;
        public final boolean controlsEnabled;
        public final boolean previousEnabled;
        public final boolean nextEnabled;
        public final boolean trackLoaded;
        public final List<PlaylistEntry> playlistEntries;
        public final int currentIndex;

        PlayerUiState(
                String pageSubtitle,
                String title,
                String copyright,
                String fileName,
                String status,
                int positionMs,
                int durationMs,
                boolean playing,
                boolean paused,
                boolean loading,
                boolean controlsEnabled,
                boolean previousEnabled,
                boolean nextEnabled,
                boolean trackLoaded,
                List<PlaylistEntry> playlistEntries,
                int currentIndex) {
            this.pageSubtitle = pageSubtitle;
            this.title = title;
            this.copyright = copyright;
            this.fileName = fileName;
            this.status = status;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.playing = playing;
            this.paused = paused;
            this.loading = loading;
            this.controlsEnabled = controlsEnabled;
            this.previousEnabled = previousEnabled;
            this.nextEnabled = nextEnabled;
            this.trackLoaded = trackLoaded;
            this.playlistEntries = playlistEntries;
            this.currentIndex = currentIndex;
        }
    }

    public static final class ImportUiState {
        public final String sourceTitle;
        public final String sourceSubtitle;
        public final boolean sourceAvailable;
        public final boolean scanning;
        public final List<RecentImportEntry> recentImports;

        ImportUiState(
                String sourceTitle,
                String sourceSubtitle,
                boolean sourceAvailable,
                boolean scanning,
                List<RecentImportEntry> recentImports) {
            this.sourceTitle = sourceTitle;
            this.sourceSubtitle = sourceSubtitle;
            this.sourceAvailable = sourceAvailable;
            this.scanning = scanning;
            this.recentImports = recentImports;
        }
    }

    public static final class SettingsUiState {
        public final String currentSf2Name;
        public final String currentLoopMode;

        SettingsUiState(String currentSf2Name, String currentLoopMode) {
            this.currentSf2Name = currentSf2Name;
            this.currentLoopMode = currentLoopMode;
        }
    }

    private static final String PREFS_NAME = "mld_player_android";
    private static final String PREF_TREE_URI = "tree_uri";
    private static final String PREF_CURRENT_URI = "current_uri";
    private static final String PREF_PLAYLIST_CACHE = "playlist_cache";
    private static final String PREF_FOLDER_PLAYLIST_CACHE = "folder_playlist_cache";
    private static final String PREF_RECENT_IMPORTS = "recent_imports";
    private static final String PREF_CUSTOM_SF2_PATH = "custom_sf2_path";
    private static final String PREF_CUSTOM_SF2_NAME = "custom_sf2_name";
    private static final String PREF_LOOP_MODE = "loop_mode";
    private static final String LOOP_MODE_LIST = "LIST";
    private static final String LOOP_MODE_ONE = "ONE";
    private static final int MAX_RECENT_IMPORTS = 10;
    private static final Comparator<PreparedTrack.SynthEvent> SYNTH_EVENT_COMPARATOR =
            new Comparator<PreparedTrack.SynthEvent>() {
                @Override
                public int compare(PreparedTrack.SynthEvent left, PreparedTrack.SynthEvent right) {
                    int byFrame = Integer.compare(left.sampleFrame, right.sampleFrame);
                    if (byFrame != 0) {
                        return byFrame;
                    }
                    int byPriority = Integer.compare(left.priority, right.priority);
                    if (byPriority != 0) {
                        return byPriority;
                    }
                    return Integer.compare(left.order, right.order);
                }
            };

    private final Context context;
    private final PlaylistRepository repository;
    private final SystemMidiPlaybackEngine systemPlaybackEngine;
    private final Sf2StreamingPlaybackEngine sf2PlaybackEngine;
    private final MldParser parser;
    private final TrackDecoder decoder;
    private final MdNormalizer normalizer;
    private final TimelineCompiler compiler;
    private final MidiFileEncoder midiEncoder;
    private final SharedPreferences preferences;
    private final ExecutorService ioExecutor;
    private final ExecutorService preloadExecutor;
    private final Handler mainHandler;
    private final ArrayList<PlaylistEntry> playlistEntries;
    private final ArrayList<RecentImportEntry> recentImports;
    private final ArrayList<UiListener> listeners;
    private final Runnable progressRunnable;

    private PreparedTrack currentTrack;
    private PlaybackEngine activePlaybackEngine;
    private WarmTrackSlot previousWarmTrack;
    private WarmTrackSlot nextWarmTrack;
    private int playlistCurrentIndex;
    private boolean loading;
    private boolean scanning;
    private boolean loopBodyActive;
    private boolean destroyed;
    private boolean initialized;
    private boolean playbackServiceRunning;
    private int preloadGeneration;
    private String statusText;
    private String pendingFileName;
    private String currentSourceTitle;
    private String currentSourceSubtitle;
    private String loopMode;
    private Host host;

    public PlayerController(Context context) {
        this.context = context.getApplicationContext();
        this.repository = new PlaylistRepository(this.context);
        this.systemPlaybackEngine = new SystemMidiPlaybackEngine(this.context);
        this.sf2PlaybackEngine = new Sf2StreamingPlaybackEngine(this.context);
        this.parser = new MldParser();
        this.decoder = new TrackDecoder();
        this.normalizer = new MdNormalizer();
        this.compiler = new TimelineCompiler();
        this.midiEncoder = new MidiFileEncoder();
        this.preferences = this.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.ioExecutor = Executors.newSingleThreadExecutor();
        this.preloadExecutor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.playlistEntries = new ArrayList<PlaylistEntry>();
        this.recentImports = new ArrayList<RecentImportEntry>();
        this.listeners = new ArrayList<UiListener>();
        this.playlistCurrentIndex = -1;
        this.statusText = text(R.string.state_stopped);
        this.currentSourceTitle = text(R.string.no_source_selected);
        this.currentSourceSubtitle = "";
        this.loopMode = preferences.getString(PREF_LOOP_MODE, LOOP_MODE_LIST);
        this.activePlaybackEngine = systemPlaybackEngine;
        this.progressRunnable = new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                notifyUiChanged();
                if (activePlaybackEngine != null
                        && (activePlaybackEngine.isPlaying() || activePlaybackEngine.isPaused())) {
                    mainHandler.postDelayed(this, 250L);
                }
            }
        };
        systemPlaybackEngine.setListener(this);
        sf2PlaybackEngine.setListener(this);
    }

    public void attachHost(Host host) {
        this.host = host;
        notifyUiChanged();
    }

    public void detachHost(Host host) {
        if (this.host == host) {
            this.host = null;
        }
    }

    public void initialize() {
        if (initialized) {
            notifyUiChanged();
            return;
        }
        initialized = true;
        loadRecentImportsFromPrefs();
        notifyUiChanged();
        restorePersistedState();
    }

    public void addListener(UiListener listener) {
        if (listener == null || listeners.contains(listener)) {
            return;
        }
        listeners.add(listener);
        dispatchStates(listener);
    }

    public void removeListener(UiListener listener) {
        listeners.remove(listener);
    }

    public void onHostPause() {
    }

    public void onHostResume() {
        notifyUiChanged();
    }

    public void onHostDestroy() {
        destroyed = true;
        stopProgressUpdates();
        clearWarmTracks();
        systemPlaybackEngine.release();
        sf2PlaybackEngine.release();
        ioExecutor.shutdownNow();
        preloadExecutor.shutdownNow();
        listeners.clear();
    }

    public void onOpenFolderRequested() {
        if (host != null) {
            host.requestOpenFolderPicker();
        }
    }

    public void onOpenSf2Requested() {
        if (host != null) {
            host.requestOpenSf2Picker();
        }
    }

    public void onLoopModeToggleRequested() {
        boolean restartPlayingTrack = currentTrack != null && activePlaybackEngine != null && activePlaybackEngine.isPlaying();

        loopMode = isLoopListMode() ? LOOP_MODE_ONE : LOOP_MODE_LIST;
        preferences.edit().putString(PREF_LOOP_MODE, loopMode).apply();
        preloadGeneration++;
        clearWarmTracks();
        notifyUiChanged();
        if (restartPlayingTrack) {
            reloadCurrentTrack(true);
            return;
        }
        scheduleAdjacentPreload(playlistCurrentIndex);
    }

    public float[] currentSpectrumLevels() {
        int positionMs;

        if (currentTrack == null || activePlaybackEngine == null || !activePlaybackEngine.isPlaying()) {
            return null;
        }
        positionMs = Math.max(0, activePlaybackEngine.getCurrentPosition());
        return currentTrack.spectrum.sample(positionMs);
    }

    public void onPlayPauseRequested() {
        if (loading || scanning) {
            return;
        }
        if (currentTrack == null) {
            showMessage(text(R.string.open_file_first));
            return;
        }
        if (activePlaybackEngine != null && activePlaybackEngine.isPlaying()) {
            activePlaybackEngine.pause();
            statusText = text(R.string.state_paused);
            notifyUiChanged();
            return;
        }
        if (activePlaybackEngine != null && activePlaybackEngine.isPaused()) {
            activePlaybackEngine.resume();
            statusText = loopBodyActive ? text(R.string.state_looping) : text(R.string.state_playing);
            notifyUiChanged();
            startProgressUpdates();
            return;
        }
        startTrackPlayback(currentTrack);
    }

    public void onPreviousRequested() {
        if (!playlistEntries.isEmpty()) {
            playRelative(-1);
        }
    }

    public void onNextRequested() {
        if (!playlistEntries.isEmpty()) {
            playRelative(1);
        }
    }

    public void onPlaylistItemSelected(int index) {
        if (index < 0 || index >= playlistEntries.size()) {
            return;
        }
        loadEntry(playlistEntries.get(index), true, true);
    }

    public void onRecentImportSelected(int index) {
        if (index < 0 || index >= recentImports.size()) {
            return;
        }
        onRecentImportSelected(recentImports.get(index));
    }

    public void onRecentImportSelected(RecentImportEntry entry) {
        if (entry == null) {
            return;
        }
        if (RecentImportEntry.KIND_FOLDER.equals(entry.kind)) {
            openFolderQueue(
                    entry.uri,
                    repository.treeDisplayName(entry.uri),
                    repository.treeDisplayPath(entry.uri),
                    true);
        }
    }

    public void onFolderPicked(final Uri treeUri) {
        if (treeUri == null) {
            return;
        }
        openFolderQueue(
                treeUri,
                repository.treeDisplayName(treeUri),
                repository.treeDisplayPath(treeUri),
                true);
    }

    public void onSf2Picked(Uri uri) {
        if (uri == null) {
            return;
        }
        takeReadPermission(uri);
        final String displayName = repository.displayName(uri);

        if (!isSf2File(displayName)) {
            showMessage(text(R.string.sf2_invalid));
            return;
        }
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File copiedFile = copyCustomSf2(uri, displayName);

                    preferences.edit()
                            .putString(PREF_CUSTOM_SF2_PATH, copiedFile.getAbsolutePath())
                            .putString(PREF_CUSTOM_SF2_NAME, displayName)
                            .apply();
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(context.getString(R.string.sf2_loaded_message, displayName));
                            reloadCurrentTrackForSoundFont();
                        }
                    });
                } catch (final IOException e) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            showMessage(e.getMessage() == null ? text(R.string.sf2_copy_failed) : e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private void openFolderQueue(
            final Uri treeUri,
            final String treeName,
            final String treePath,
            boolean preferCachedPlaylist) {
        final ArrayList<PlaylistEntry> cachedEntries = preferCachedPlaylist
                ? loadPlaylistCacheForTree(treeUri)
                : new ArrayList<PlaylistEntry>();

        takeReadPermission(treeUri);
        preferences.edit()
                .putString(PREF_TREE_URI, treeUri.toString())
                .remove(PREF_CURRENT_URI)
                .apply();
        if (!cachedEntries.isEmpty()) {
            applyPlaylist(cachedEntries);
            addRecentImport(new RecentImportEntry(
                    RecentImportEntry.KIND_FOLDER,
                    treeUri,
                    treeName,
                    treePath,
                    cachedEntries.size()));
            updateCurrentSource(
                    treeName,
                    playlistEntries.isEmpty()
                            ? text(R.string.queue_empty)
                            : text(R.string.folder_source));
            if (playlistEntries.isEmpty()) {
                statusText = text(R.string.queue_empty);
            } else if (currentTrack == null) {
                statusText = text(R.string.state_stopped);
            }
            notifyUiChanged();
            return;
        }
        updateCurrentSource(
                treeName,
                text(R.string.state_scanning));
        scanning = true;
        statusText = text(R.string.state_scanning);
        notifyUiChanged();

        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final List<PlaylistEntry> scannedEntries = repository.scanTree(treeUri);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            scanning = false;
                            applyPlaylist(scannedEntries);
                            addRecentImport(new RecentImportEntry(
                                    RecentImportEntry.KIND_FOLDER,
                                    treeUri,
                                    treeName,
                                    treePath,
                                    scannedEntries.size()));
                            updateCurrentSource(
                                    treeName,
                                    playlistEntries.isEmpty()
                                            ? text(R.string.queue_empty)
                                            : text(R.string.folder_source));
                            if (playlistEntries.isEmpty()) {
                                statusText = "No MLD in folder";
                            } else if (currentTrack == null) {
                                statusText = text(R.string.state_stopped);
                            }
                            notifyUiChanged();
                        }
                    });
                } catch (final IOException e) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            scanning = false;
                            statusText = e.getMessage();
                            showMessage(e.getMessage());
                            notifyUiChanged();
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onPlaybackStarted() {
        postToMain(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Playback started: " + (currentTrack == null ? "<none>" : currentTrack.fileName));
                statusText = loopBodyActive ? text(R.string.state_looping) : text(R.string.state_playing);
                notifyUiChanged();
                startProgressUpdates();
            }
        });
    }

    @Override
    public void onPlaybackLoopEntered() {
        postToMain(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Playback loop entered: " + (currentTrack == null ? "<none>" : currentTrack.fileName));
                loopBodyActive = true;
                statusText = text(R.string.state_looping);
                notifyUiChanged();
                startProgressUpdates();
            }
        });
    }

    @Override
    public void onPlaybackCompleted() {
        postToMain(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Playback completed: " + (currentTrack == null ? "<none>" : currentTrack.fileName));
                stopProgressUpdates();
                if (destroyed || currentTrack == null) {
                    return;
                }
                if (isLoopListMode() && !playlistEntries.isEmpty()) {
                    playRelative(1);
                    return;
                }
                if (restartCurrentTrack()) {
                    return;
                }
                statusText = text(R.string.state_stopped);
                notifyUiChanged();
            }
        });
    }

    @Override
    public void onPlaybackError(final String message) {
        postToMain(new Runnable() {
            @Override
            public void run() {
                Log.e(TAG, "Playback error: " + message + " track=" + (currentTrack == null ? "<none>" : currentTrack.fileName));
                stopProgressUpdates();
                statusText = message;
                notifyUiChanged();
                showMessage(message);
            }
        });
    }

    public PlayerUiState currentPlayerUiState() {
        PreparedTrack track = currentTrack;
        boolean playing = activePlaybackEngine != null && activePlaybackEngine.isPlaying();
        boolean paused = activePlaybackEngine != null && activePlaybackEngine.isPaused();
        int currentPosition = activePlaybackEngine == null ? 0 : activePlaybackEngine.getCurrentPosition();
        int duration = activePlaybackEngine == null ? -1 : activePlaybackEngine.getDuration();
        String title = text(R.string.nothing_playing);
        String copyright = text(R.string.nothing_copyright);
        String fileName = pendingFileName == null ? "" : pendingFileName;
        String pageSubtitle = track == null ? text(R.string.player_empty_message) : "";
        boolean trackLoaded = track != null;
        boolean controlsEnabled = !loading && !scanning && trackLoaded;
        boolean previousEnabled = !loading && playlistEntries.size() > 1;
        boolean nextEnabled = !loading && playlistEntries.size() > 1;

        if (track != null) {
            title = track.title;
            copyright = track.copyright;
            fileName = track.fileName;
            if (duration <= 0) {
                duration = track.durationMs;
            }
        }
        return new PlayerUiState(
                pageSubtitle,
                title,
                copyright,
                fileName,
                resolveStatusText(playing, paused),
                Math.max(0, currentPosition),
                duration,
                playing,
                paused,
                loading || scanning,
                controlsEnabled,
                previousEnabled,
                nextEnabled,
                trackLoaded,
                new ArrayList<PlaylistEntry>(playlistEntries),
                playlistCurrentIndex);
    }

    public ImportUiState currentImportUiState() {
        return new ImportUiState(
                currentSourceTitle,
                currentSourceSubtitle,
                hasText(currentSourceTitle) && !text(R.string.no_source_selected).equals(currentSourceTitle),
                scanning,
                new ArrayList<RecentImportEntry>(recentImports));
    }

    public SettingsUiState currentSettingsUiState() {
        return new SettingsUiState(currentSf2Name(), currentLoopModeLabel());
    }

    private void restorePersistedState() {
        final String currentUriValue = preferences.getString(PREF_CURRENT_URI, null);
        final String treeUriValue = preferences.getString(PREF_TREE_URI, null);
        Uri treeUri = hasText(treeUriValue) ? Uri.parse(treeUriValue) : null;
        ArrayList<PlaylistEntry> cachedPlaylist = loadPlaylistCacheForTree(treeUri);
        boolean restoredFolderQueue = false;

        if (treeUriValue != null && treeUriValue.length() > 0 && !cachedPlaylist.isEmpty()) {
            restoredFolderQueue = true;
            applyPlaylist(cachedPlaylist);
            updateCurrentSource(
                    repository.treeDisplayName(treeUri),
                    text(R.string.folder_source));
            statusText = playlistEntries.isEmpty() ? text(R.string.queue_empty) : text(R.string.state_stopped);
            notifyUiChanged();
        } else if (treeUriValue != null && treeUriValue.length() > 0) {
            openFolderQueue(
                    treeUri,
                    repository.treeDisplayName(treeUri),
                    repository.treeDisplayPath(treeUri),
                    true);
        }
        if (treeUriValue != null && treeUriValue.length() > 0 && currentUriValue != null && currentUriValue.length() > 0) {
            loadEntry(new PlaylistEntry(
                    Uri.parse(currentUriValue),
                    repository.displayName(Uri.parse(currentUriValue)),
                    repository.displayName(Uri.parse(currentUriValue))), false, restoredFolderQueue);
        } else if (treeUriValue == null || treeUriValue.length() == 0) {
            preferences.edit()
                    .remove(PREF_CURRENT_URI)
                    .remove(PREF_PLAYLIST_CACHE)
                    .apply();
        }
    }

    private void applyPlaylist(List<PlaylistEntry> entries) {
        int index;

        playlistEntries.clear();
        playlistEntries.addAll(entries);
        persistPlaylistCache();
        playlistCurrentIndex = -1;
        if (currentTrack != null) {
            for (index = 0; index < playlistEntries.size(); index++) {
                if (sameUri(playlistEntries.get(index).uri, currentTrack.uri)) {
                    playlistCurrentIndex = index;
                    break;
                }
            }
        }
    }

    private void playRelative(int offset) {
        if (playlistEntries.isEmpty()) {
            return;
        }
        int baseIndex = playlistCurrentIndex >= 0 ? playlistCurrentIndex : 0;
        int nextIndex = (baseIndex + offset) % playlistEntries.size();

        if (nextIndex < 0) {
            nextIndex += playlistEntries.size();
        }
        if (tryStartWarmTrack(nextIndex)) {
            return;
        }
        loadEntry(playlistEntries.get(nextIndex), true, true);
    }

    private void loadEntry(final PlaylistEntry entry, final boolean autoPlay, final boolean preserveSource) {
        final int loadGeneration;

        if (entry == null || entry.uri == null) {
            return;
        }
        loadGeneration = ++preloadGeneration;
        loading = true;
        loopBodyActive = false;
        pendingFileName = entry.fileName;
        statusText = text(R.string.state_loading);
        notifyUiChanged();

        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final PreparedTrack loadedTrack = buildPreparedTrack(entry);
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            pendingFileName = null;
                            applyPreparedTrack(loadedTrack);
                            statusText = autoPlay ? text(R.string.state_preparing) : text(R.string.state_stopped);
                            notifyUiChanged();
                            if (autoPlay) {
                                startTrackPlayback(loadedTrack);
                            } else {
                                scheduleAdjacentPreload(playlistCurrentIndex, loadGeneration);
                            }
                        }
                    });
                } catch (final IOException e) {
                    postToMain(new Runnable() {
                        @Override
                        public void run() {
                            loading = false;
                            currentTrack = null;
                            pendingFileName = entry.fileName;
                            stopAllPlayback();
                            clearWarmTracks();
                            statusText = e.getMessage();
                            notifyUiChanged();
                            showMessage(e.getMessage());
                        }
                    });
                }
            }
        });
    }

    private PreparedTrack buildPreparedTrack(PlaylistEntry entry) throws IOException {
        PreparedTrackData preparedTrack = prepareTrackData(
                entry,
                parser,
                decoder,
                normalizer,
                compiler,
                midiEncoder);
        return new PreparedTrack(
                entry.uri,
                preparedTrack.fileName,
                preparedTrack.displayTitle,
                preparedTrack.copyright,
                preparedTrack.primaryBytes,
                preparedTrack.durationMs,
                preparedTrack.loopStartMs,
                preparedTrack.playbackEndFrame,
                preparedTrack.loopStartFrame,
                preparedTrack.hasInternalLoop,
                preparedTrack.spectrum,
                preparedTrack.synthEvents);
    }

    private PreparedTrackData prepareTrackData(
            PlaylistEntry entry,
            MldParser localParser,
            TrackDecoder localDecoder,
            MdNormalizer localNormalizer,
            TimelineCompiler localCompiler,
            MidiFileEncoder localMidiEncoder) throws IOException {
        byte[] bytes = repository.readBytes(entry.uri);
        MldFile file = localParser.parse(bytes);
        ArrayList<TrackDecodeResult> decodedTracks = new ArrayList<TrackDecodeResult>();
        int index;

        for (index = 0; index < file.tracks.size(); index++) {
            decodedTracks.add(localDecoder.decode(file, file.tracks.get(index)));
        }

        MdNormalizationResult normalization = localNormalizer.normalize(decodedTracks);
        PlaybackTimeline timeline = localCompiler.compile(file, decodedTracks, normalization);
        if (timeline.notes.isEmpty()) {
            throw new IOException("No melody tracks");
        }

        String fileName = repository.displayName(entry.uri);
        String displayTitle = chooseTitle(file, fileName);
        String copyright = PlaylistRepository.chooseCopyright(file);
        boolean hasInternalLoop = timeline.loopInfo != null && timeline.loopInfo.hasLoop;
        byte[] primaryBytes;
        int durationMs;
        int loopStartMs = 0;
        int playbackEndFrame;
        int loopStartFrame = 0;
        List<PreparedTrack.SynthEvent> synthEvents = buildSynthEvents(timeline);
        int lastEventFrame = lastSynthEventFrame(synthEvents);

        if (hasInternalLoop) {
            int loopEndFrame;

            primaryBytes = localMidiEncoder.encode(timeline, 0);
            loopStartMs = estimateDurationMillis(timeline, timeline.loopInfo.loopStartMidiTick);
            durationMs = estimateDurationMillis(timeline, timeline.loopInfo.loopEndMidiTick);
            loopStartFrame = midiTickToSampleFrame(timeline, timeline.loopInfo.loopStartMidiTick);
            loopEndFrame = midiTickToSampleFrame(timeline, timeline.loopInfo.loopEndMidiTick);
            playbackEndFrame = Math.max(loopEndFrame, loopStartFrame + 1);
        } else {
            primaryBytes = localMidiEncoder.encode(timeline);
            durationMs = estimateDurationMillis(timeline, timeline.totalMidiTicks);
            playbackEndFrame = Math.max(
                    midiTickToSampleFrame(timeline, timeline.totalMidiTicks),
                    lastEventFrame + 1);
            durationMs = Math.max(durationMs, sampleFrameToMillis(playbackEndFrame));
        }

        return new PreparedTrackData(
                fileName,
                displayTitle,
                copyright,
                primaryBytes,
                durationMs,
                loopStartMs,
                playbackEndFrame,
                loopStartFrame,
                hasInternalLoop,
                TimelineSpectrum.from(timeline, durationMs),
                synthEvents);
    }

    private void startTrackPlayback(PreparedTrack track) {
        boolean loopCurrentTrack = !isLoopListMode();

        if (track == null) {
            return;
        }
        loopBodyActive = false;
        clearWarmTrack(playlistCurrentIndex - 1);
        clearWarmTrack(playlistCurrentIndex + 1);
        if (hasCustomSf2Configured()) {
            startSf2TrackPlayback(track, loopCurrentTrack, null);
            return;
        }
        startSystemTrackPlayback(track, loopCurrentTrack);
    }

    private void startSystemTrackPlayback(PreparedTrack track, boolean loopCurrentTrack) {
        try {
            switchActiveEngine(systemPlaybackEngine);
            systemPlaybackEngine.playTrack(track, loopCurrentTrack);
            statusText = loopCurrentTrack ? text(R.string.state_looping) : text(R.string.state_preparing);
            notifyUiChanged();
        } catch (IOException e) {
            statusText = e.getMessage() == null ? "MIDI playback failed" : e.getMessage();
            notifyUiChanged();
            showMessage(statusText);
        }
    }

    private void startSf2TrackPlayback(
            PreparedTrack track,
            boolean loopCurrentTrack,
            Sf2StreamingPlaybackEngine.WarmSession warmSession) {
        File sf2File = customSf2File();

        if (sf2File == null || !sf2File.isFile()) {
            startSystemTrackPlayback(track, loopCurrentTrack);
            return;
        }
        try {
            switchActiveEngine(sf2PlaybackEngine);
            sf2PlaybackEngine.setSoundFontFile(sf2File);
            if (warmSession != null) {
                sf2PlaybackEngine.playWarmSession(warmSession, loopCurrentTrack);
            } else {
                sf2PlaybackEngine.playTrack(track, loopCurrentTrack);
            }
            statusText = loopCurrentTrack ? text(R.string.state_looping) : text(R.string.state_preparing);
            notifyUiChanged();
            scheduleAdjacentPreload(playlistCurrentIndex);
        } catch (IOException e) {
            statusText = e.getMessage() == null ? "SF2 playback failed" : e.getMessage();
            notifyUiChanged();
            showMessage(statusText);
        }
    }

    private boolean restartCurrentTrack() {
        if (currentTrack == null) {
            return false;
        }
        startTrackPlayback(currentTrack);
        return true;
    }

    private void scheduleAdjacentPreload(int centerIndex) {
        scheduleAdjacentPreload(centerIndex, preloadGeneration);
    }

    private void scheduleAdjacentPreload(int centerIndex, int generation) {
        final ArrayList<PlaylistEntry> playlistSnapshot;
        final File sf2File;

        clearWarmTracks();
        if (!isLoopListMode()) {
            return;
        }
        sf2File = customSf2File();
        if (sf2File == null || !sf2File.isFile()) {
            return;
        }
        if (centerIndex < 0 || centerIndex >= playlistEntries.size() || playlistEntries.size() < 2) {
            return;
        }
        playlistSnapshot = new ArrayList<PlaylistEntry>(playlistEntries);
        preloadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                preloadAdjacentTracks(playlistSnapshot, centerIndex, generation, sf2File);
            }
        });
    }

    private void preloadAdjacentTracks(
            ArrayList<PlaylistEntry> playlistSnapshot,
            int centerIndex,
            int generation,
            File soundFontFile) {
        LinkedHashSet<Integer> targetIndexes = new LinkedHashSet<Integer>();
        int playlistSize = playlistSnapshot.size();

        if (generation != preloadGeneration || playlistSize < 2 || centerIndex < 0 || centerIndex >= playlistSize) {
            return;
        }
        targetIndexes.add((centerIndex - 1 + playlistSize) % playlistSize);
        targetIndexes.add((centerIndex + 1) % playlistSize);
        for (Integer targetIndex : targetIndexes) {
            if (targetIndex == null || targetIndex.intValue() == centerIndex || generation != preloadGeneration) {
                continue;
            }
            try {
                preloadWarmTrack(playlistSnapshot.get(targetIndex.intValue()), targetIndex.intValue(), soundFontFile, generation);
            } catch (IOException ignored) {
            }
        }
    }

    private void preloadWarmTrack(
            PlaylistEntry entry,
            int index,
            File soundFontFile,
            int generation) throws IOException {
        final PreparedTrack preparedTrack = buildPreparedTrack(
                entry,
                new MldParser(),
                new TrackDecoder(),
                new MdNormalizer(),
                new TimelineCompiler(),
                new MidiFileEncoder());
        final Sf2StreamingPlaybackEngine.WarmSession warmSession = sf2PlaybackEngine.warmSession(soundFontFile, preparedTrack);

        postToMain(new Runnable() {
            @Override
            public void run() {
                if (generation != preloadGeneration || !isLoopListMode() || !sameFile(soundFontFile, customSf2File())) {
                    warmSession.release();
                    return;
                }
                storeWarmTrack(index, preparedTrack, warmSession);
            }
        });
    }

    private PreparedTrack buildPreparedTrack(
            PlaylistEntry entry,
            MldParser localParser,
            TrackDecoder localDecoder,
            MdNormalizer localNormalizer,
            TimelineCompiler localCompiler,
            MidiFileEncoder localMidiEncoder) throws IOException {
        PreparedTrackData preparedTrack = prepareTrackData(
                entry,
                localParser,
                localDecoder,
                localNormalizer,
                localCompiler,
                localMidiEncoder);
        return new PreparedTrack(
                entry.uri,
                preparedTrack.fileName,
                preparedTrack.displayTitle,
                preparedTrack.copyright,
                preparedTrack.primaryBytes,
                preparedTrack.durationMs,
                preparedTrack.loopStartMs,
                preparedTrack.playbackEndFrame,
                preparedTrack.loopStartFrame,
                preparedTrack.hasInternalLoop,
                preparedTrack.spectrum,
                preparedTrack.synthEvents);
    }

    private void applyPreparedTrack(PreparedTrack preparedTrack) {
        currentTrack = preparedTrack;
        playlistCurrentIndex = indexOfUri(preparedTrack.uri);
        preferences.edit().putString(PREF_CURRENT_URI, preparedTrack.uri.toString()).apply();
        if (playlistCurrentIndex >= 0) {
            playlistEntries.set(playlistCurrentIndex, new PlaylistEntry(
                    preparedTrack.uri,
                    preparedTrack.fileName,
                    preparedTrack.title,
                    preparedTrack.durationMs));
            persistPlaylistCache();
            return;
        }
        if (playlistEntries.isEmpty()) {
            playlistEntries.add(new PlaylistEntry(
                    preparedTrack.uri,
                    preparedTrack.fileName,
                    preparedTrack.title,
                    preparedTrack.durationMs));
            persistPlaylistCache();
            playlistCurrentIndex = 0;
        }
    }

    private boolean tryStartWarmTrack(int targetIndex) {
        WarmTrackSlot warmTrack = warmTrackForIndex(targetIndex);

        if (warmTrack == null || warmTrack.session == null) {
            return false;
        }
        applyPreparedTrack(warmTrack.track);
        statusText = text(R.string.state_preparing);
        notifyUiChanged();
        startSf2TrackPlayback(warmTrack.track, false, warmTrack.consumeSession());
        clearOtherWarmTracks(targetIndex);
        scheduleAdjacentPreload(targetIndex);
        return true;
    }

    private WarmTrackSlot warmTrackForIndex(int index) {
        if (previousWarmTrack != null && previousWarmTrack.index == index) {
            return previousWarmTrack;
        }
        if (nextWarmTrack != null && nextWarmTrack.index == index) {
            return nextWarmTrack;
        }
        return null;
    }

    private void storeWarmTrack(
            int index,
            PreparedTrack track,
            Sf2StreamingPlaybackEngine.WarmSession session) {
        if (index == wrapPlaylistIndex(playlistCurrentIndex - 1)) {
            releaseWarmSlot(previousWarmTrack);
            previousWarmTrack = new WarmTrackSlot(index, track, session);
            return;
        }
        if (index == wrapPlaylistIndex(playlistCurrentIndex + 1)) {
            releaseWarmSlot(nextWarmTrack);
            nextWarmTrack = new WarmTrackSlot(index, track, session);
            return;
        }
        session.release();
    }

    private void clearWarmTracks() {
        releaseWarmSlot(previousWarmTrack);
        previousWarmTrack = null;
        releaseWarmSlot(nextWarmTrack);
        nextWarmTrack = null;
    }

    private void clearOtherWarmTracks(int preservedIndex) {
        if (previousWarmTrack != null && previousWarmTrack.index != preservedIndex) {
            releaseWarmSlot(previousWarmTrack);
            previousWarmTrack = null;
        }
        if (nextWarmTrack != null && nextWarmTrack.index != preservedIndex) {
            releaseWarmSlot(nextWarmTrack);
            nextWarmTrack = null;
        }
    }

    private void clearWarmTrack(int expectedIndex) {
        if (previousWarmTrack != null && previousWarmTrack.index == wrapPlaylistIndex(expectedIndex)) {
            releaseWarmSlot(previousWarmTrack);
            previousWarmTrack = null;
        }
        if (nextWarmTrack != null && nextWarmTrack.index == wrapPlaylistIndex(expectedIndex)) {
            releaseWarmSlot(nextWarmTrack);
            nextWarmTrack = null;
        }
    }

    private void releaseWarmSlot(WarmTrackSlot slot) {
        if (slot != null) {
            slot.release();
        }
    }

    private int wrapPlaylistIndex(int index) {
        if (playlistEntries.isEmpty()) {
            return -1;
        }
        if (index < 0) {
            return (playlistEntries.size() + (index % playlistEntries.size())) % playlistEntries.size();
        }
        return index % playlistEntries.size();
    }

    private void switchActiveEngine(PlaybackEngine engine) {
        if (engine == null) {
            return;
        }
        if (engine != systemPlaybackEngine) {
            systemPlaybackEngine.stop();
        }
        if (engine != sf2PlaybackEngine) {
            sf2PlaybackEngine.stop();
        }
        activePlaybackEngine = engine;
    }

    private void stopAllPlayback() {
        systemPlaybackEngine.stop();
        sf2PlaybackEngine.stop();
        activePlaybackEngine = resolvePlaybackEngine();
    }

    private PlaybackEngine resolvePlaybackEngine() {
        return hasCustomSf2Configured() ? sf2PlaybackEngine : systemPlaybackEngine;
    }

    private void notifyUiChanged() {
        int index;

        syncPlaybackService();
        for (index = 0; index < listeners.size(); index++) {
            dispatchStates(listeners.get(index));
        }
    }

    private void dispatchStates(UiListener listener) {
        listener.onPlayerUiStateChanged(currentPlayerUiState());
        listener.onImportUiStateChanged(currentImportUiState());
        listener.onSettingsUiStateChanged(currentSettingsUiState());
    }

    private void startProgressUpdates() {
        stopProgressUpdates();
        mainHandler.post(progressRunnable);
    }

    private void stopProgressUpdates() {
        mainHandler.removeCallbacks(progressRunnable);
    }

    private void takeReadPermission(Uri uri) {
        if (uri == null) {
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
    }

    private void addRecentImport(RecentImportEntry entry) {
        int index;

        if (entry == null || entry.uri == null) {
            return;
        }
        for (index = recentImports.size() - 1; index >= 0; index--) {
            if (sameUri(recentImports.get(index).uri, entry.uri)) {
                recentImports.remove(index);
            }
        }
        recentImports.add(0, entry);
        while (recentImports.size() > MAX_RECENT_IMPORTS) {
            recentImports.remove(recentImports.size() - 1);
        }
        persistRecentImports();
        notifyUiChanged();
    }

    private void persistRecentImports() {
        JSONArray array = new JSONArray();
        int index;

        for (index = 0; index < recentImports.size(); index++) {
            RecentImportEntry entry = recentImports.get(index);
            JSONObject object = new JSONObject();

            try {
                object.put("kind", entry.kind);
                object.put("uri", entry.uri == null ? "" : entry.uri.toString());
                object.put("title", entry.title);
                object.put("subtitle", entry.subtitle);
                object.put("itemCount", entry.itemCount);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        preferences.edit().putString(PREF_RECENT_IMPORTS, array.toString()).apply();
    }

    private void persistPlaylistCache() {
        String activeTreeUri = preferences.getString(PREF_TREE_URI, null);
        JSONArray array = new JSONArray();
        JSONObject folderCache = loadFolderPlaylistCacheObject();
        int index;

        for (index = 0; index < playlistEntries.size(); index++) {
            PlaylistEntry entry = playlistEntries.get(index);
            JSONObject object = new JSONObject();

            try {
                object.put("uri", entry.uriString());
                object.put("fileName", entry.fileName);
                object.put("displayTitle", entry.displayTitle);
                object.put("durationMs", entry.durationMs);
                array.put(object);
            } catch (Throwable ignored) {
            }
        }
        if (hasText(activeTreeUri)) {
            try {
                folderCache.put(activeTreeUri, array);
                pruneFolderPlaylistCache(folderCache, activeTreeUri);
            } catch (Throwable ignored) {
            }
        }
        preferences.edit()
                .putString(PREF_PLAYLIST_CACHE, array.toString())
                .putString(PREF_FOLDER_PLAYLIST_CACHE, folderCache.toString())
                .apply();
    }

    private ArrayList<PlaylistEntry> loadPlaylistCacheFromPrefs() {
        try {
            return parsePlaylistCache(new JSONArray(preferences.getString(PREF_PLAYLIST_CACHE, "[]")));
        } catch (Throwable ignored) {
            return new ArrayList<PlaylistEntry>();
        }
    }

    private ArrayList<PlaylistEntry> loadPlaylistCacheForTree(Uri treeUri) {
        JSONArray cachedArray;
        JSONObject folderCache;
        String treeUriValue;

        if (treeUri == null) {
            return loadPlaylistCacheFromPrefs();
        }
        treeUriValue = treeUri.toString();
        folderCache = loadFolderPlaylistCacheObject();
        cachedArray = folderCache.optJSONArray(treeUriValue);
        if (cachedArray != null) {
            return parsePlaylistCache(cachedArray);
        }
        if (treeUriValue.equals(preferences.getString(PREF_TREE_URI, null))) {
            return loadPlaylistCacheFromPrefs();
        }
        return new ArrayList<PlaylistEntry>();
    }

    private JSONObject loadFolderPlaylistCacheObject() {
        try {
            return new JSONObject(preferences.getString(PREF_FOLDER_PLAYLIST_CACHE, "{}"));
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    private ArrayList<PlaylistEntry> parsePlaylistCache(JSONArray array) {
        ArrayList<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();
        int index;

        if (array == null) {
            return entries;
        }
        for (index = 0; index < array.length(); index++) {
            JSONObject object = array.optJSONObject(index);
            String uriValue;

            if (object == null) {
                continue;
            }
            uriValue = object.optString("uri", "");
            if (uriValue.length() == 0) {
                continue;
            }
            entries.add(new PlaylistEntry(
                    Uri.parse(uriValue),
                    object.optString("fileName", ""),
                    object.optString("displayTitle", ""),
                    object.optInt("durationMs", -1)));
        }
        return entries;
    }

    private void pruneFolderPlaylistCache(JSONObject folderCache, String activeTreeUri) {
        LinkedHashSet<String> allowedUris = new LinkedHashSet<String>();
        ArrayList<String> removableKeys = new ArrayList<String>();
        Iterator<String> keys = folderCache.keys();

        if (hasText(activeTreeUri)) {
            allowedUris.add(activeTreeUri);
        }
        for (int index = 0; index < recentImports.size(); index++) {
            RecentImportEntry entry = recentImports.get(index);
            if (entry != null
                    && RecentImportEntry.KIND_FOLDER.equals(entry.kind)
                    && entry.uri != null
                    && hasText(entry.uri.toString())) {
                allowedUris.add(entry.uri.toString());
            }
        }
        while (keys.hasNext()) {
            String key = keys.next();
            if (!allowedUris.contains(key)) {
                removableKeys.add(key);
            }
        }
        for (int index = 0; index < removableKeys.size(); index++) {
            folderCache.remove(removableKeys.get(index));
        }
    }

    private void loadRecentImportsFromPrefs() {
        String raw = preferences.getString(PREF_RECENT_IMPORTS, "[]");
        int index;

        recentImports.clear();
        try {
            JSONArray array = new JSONArray(raw);
            for (index = 0; index < array.length(); index++) {
                JSONObject object = array.optJSONObject(index);
                if (object == null) {
                    continue;
                }
                String uriValue = object.optString("uri", "");
                if (uriValue.length() == 0) {
                    continue;
                }
                recentImports.add(new RecentImportEntry(
                        object.optString("kind", RecentImportEntry.KIND_FILE),
                        Uri.parse(uriValue),
                        object.optString("title", ""),
                        object.optString("subtitle", ""),
                        object.optInt("itemCount", -1)));
            }
        } catch (Throwable ignored) {
        }
    }

    private void updateCurrentSource(String title, String subtitle) {
        currentSourceTitle = hasText(title) ? title : text(R.string.no_source_selected);
        currentSourceSubtitle = hasText(subtitle) ? subtitle : "";
    }

    private int indexOfUri(Uri uri) {
        int index;

        for (index = 0; index < playlistEntries.size(); index++) {
            if (sameUri(playlistEntries.get(index).uri, uri)) {
                return index;
            }
        }
        return -1;
    }

    private void postToMain(Runnable action) {
        mainHandler.post(action);
    }

    private void syncPlaybackService() {
        boolean shouldRun = currentTrack != null
                && activePlaybackEngine != null
                && (activePlaybackEngine.isPlaying() || activePlaybackEngine.isPaused());

        if (shouldRun == playbackServiceRunning) {
            return;
        }
        playbackServiceRunning = shouldRun;
        if (shouldRun) {
            PlaybackService.start(context);
        } else {
            PlaybackService.stop(context);
        }
    }

    private void showMessage(String message) {
        if (host != null && hasText(message)) {
            host.showMessage(message);
        }
    }

    private String resolveStatusText(boolean playing, boolean paused) {
        if (scanning) {
            return text(R.string.state_scanning);
        }
        if (loading) {
            return text(R.string.state_loading);
        }
        if (playing) {
            return loopBodyActive ? text(R.string.state_looping) : text(R.string.state_playing);
        }
        if (paused) {
            return text(R.string.state_paused);
        }
        return statusText;
    }

    private String text(int resId) {
        return context.getString(resId);
    }

    private String currentSf2Name() {
        String savedName = preferences.getString(PREF_CUSTOM_SF2_NAME, "");
        if (!hasCustomSf2Configured()) {
            return text(R.string.sf2_system_name);
        }
        return hasText(savedName) ? savedName : text(R.string.sf2_system_name);
    }

    private String currentLoopModeLabel() {
        return text(isLoopListMode() ? R.string.loop_list : R.string.loop_one);
    }

    private boolean isLoopListMode() {
        return !LOOP_MODE_ONE.equals(loopMode);
    }

    private boolean hasCustomSf2Configured() {
        File sf2File = customSf2File();
        return sf2File != null && sf2File.isFile();
    }

    private File customSf2File() {
        String path = preferences.getString(PREF_CUSTOM_SF2_PATH, "");

        if (!hasText(path)) {
            return null;
        }
        return new File(path);
    }

    private File copyCustomSf2(Uri uri, String displayName) throws IOException {
        File targetDir = new File(context.getFilesDir(), "soundfonts");
        File targetFile = new File(targetDir, "custom.sf2");

        repository.copyUriToFile(uri, targetFile);
        if (!targetFile.isFile() || targetFile.length() == 0L) {
            throw new IOException(text(R.string.sf2_copy_failed));
        }
        return targetFile;
    }

    private void reloadCurrentTrackForSoundFont() {
        clearWarmTracks();
        reloadCurrentTrack(activePlaybackEngine != null && activePlaybackEngine.isPlaying());
    }

    private void reloadCurrentTrack(boolean autoPlay) {
        PlaylistEntry currentEntry;

        if (currentTrack == null) {
            scheduleAdjacentPreload(playlistCurrentIndex);
            notifyUiChanged();
            return;
        }
        stopAllPlayback();
        currentEntry = new PlaylistEntry(
                currentTrack.uri,
                currentTrack.fileName,
                currentTrack.title,
                currentTrack.durationMs);
        loadEntry(currentEntry, autoPlay, true);
    }

    private static String chooseTitle(MldFile file, String fallback) {
        String title = cleanInfoText(file.lastInfoText("titl"));
        return hasText(title) ? title : fallback;
    }

    private static String cleanInfoText(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u0000', ' ').replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean hasText(String value) {
        return value != null && value.trim().length() > 0;
    }

    private static boolean isSf2File(String displayName) {
        String lowerName = displayName == null ? "" : displayName.trim().toLowerCase();
        return lowerName.endsWith(".sf2");
    }

    private static boolean sameUri(Uri left, Uri right) {
        if (left == null || right == null) {
            return false;
        }
        return left.toString().equals(right.toString());
    }

    private static boolean sameFile(File left, File right) {
        if (left == null || right == null) {
            return false;
        }
        return left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    private static int estimateDurationMillis(PlaybackTimeline timeline, long endTick) {
        List<PlaybackTimeline.TempoPoint> points = timeline.tempoPoints;
        long boundedEnd = Math.max(0L, endTick);
        long totalMicros = 0L;
        int index;

        for (index = 0; index < points.size(); index++) {
            PlaybackTimeline.TempoPoint point = points.get(index);
            long segmentStart = point.midiTick;
            long segmentEnd = boundedEnd;

            if (segmentStart >= boundedEnd) {
                break;
            }
            if (index + 1 < points.size()) {
                segmentEnd = Math.min(boundedEnd, points.get(index + 1).midiTick);
            }
            if (segmentEnd <= segmentStart) {
                continue;
            }
            totalMicros += ((segmentEnd - segmentStart) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
        }
        return (int) Math.min(Integer.MAX_VALUE, totalMicros / 1000L);
    }

    private static int midiTickToSampleFrame(PlaybackTimeline timeline, long targetTick) {
        List<PlaybackTimeline.TempoPoint> points = timeline.tempoPoints;
        long boundedEnd = Math.max(0L, targetTick);
        long totalFrames = 0L;
        int index;

        for (index = 0; index < points.size(); index++) {
            PlaybackTimeline.TempoPoint point = points.get(index);
            long segmentStart = point.midiTick;
            long segmentEnd = boundedEnd;
            long micros;

            if (segmentStart >= boundedEnd) {
                break;
            }
            if (index + 1 < points.size()) {
                segmentEnd = Math.min(boundedEnd, points.get(index + 1).midiTick);
            }
            if (segmentEnd <= segmentStart) {
                continue;
            }
            micros = ((segmentEnd - segmentStart) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
            totalFrames += (micros * NativeSf2Synth.SAMPLE_RATE) / 1000000L;
        }
        return (int) Math.min(Integer.MAX_VALUE, totalFrames);
    }

    private static int sampleFrameToMillis(int sampleFrame) {
        if (sampleFrame <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (sampleFrame * 1000L) / NativeSf2Synth.SAMPLE_RATE);
    }

    private static List<PreparedTrack.SynthEvent> buildSynthEvents(PlaybackTimeline timeline) {
        ArrayList<PreparedTrack.SynthEvent> events = new ArrayList<PreparedTrack.SynthEvent>();
        int order = 0;

        for (int index = 0; index < timeline.mappedControls.size(); index++) {
            PlaybackTimeline.MappedControlEvent control = timeline.mappedControls.get(index);

            events.add(new PreparedTrack.SynthEvent(
                    midiTickToSampleFrame(timeline, control.midiTick),
                    PreparedTrack.SynthEvent.PRIORITY_CONTROL,
                    control.order,
                    control.status,
                    control.midiChannel,
                    control.data1,
                    control.data2));
            order = Math.max(order, control.order + 1);
        }
        for (int index = 0; index < timeline.notes.size(); index++) {
            PlaybackTimeline.CompiledNote note = timeline.notes.get(index);
            int startFrame = midiTickToSampleFrame(timeline, note.midiStartTick);
            int endFrame = Math.max(startFrame + 1, midiTickToSampleFrame(timeline, note.midiEndTick));

            events.add(new PreparedTrack.SynthEvent(
                    startFrame,
                    PreparedTrack.SynthEvent.PRIORITY_NOTE_ON,
                    order++,
                    MidiMessageConstants.NOTE_ON,
                    note.midiChannel,
                    note.midiNote,
                    note.velocity));
            events.add(new PreparedTrack.SynthEvent(
                    endFrame,
                    PreparedTrack.SynthEvent.PRIORITY_NOTE_OFF,
                    order++,
                    MidiMessageConstants.NOTE_OFF,
                    note.midiChannel,
                    note.midiNote,
                    0));
        }
        Collections.sort(events, SYNTH_EVENT_COMPARATOR);
        return events;
    }

    private static int lastSynthEventFrame(List<PreparedTrack.SynthEvent> events) {
        int lastFrame = 0;

        for (int index = 0; index < events.size(); index++) {
            lastFrame = Math.max(lastFrame, events.get(index).sampleFrame);
        }
        return lastFrame;
    }

    private static final class PreparedTrackData {
        final String fileName;
        final String displayTitle;
        final String copyright;
        final byte[] primaryBytes;
        final int durationMs;
        final int loopStartMs;
        final int playbackEndFrame;
        final int loopStartFrame;
        final boolean hasInternalLoop;
        final TimelineSpectrum spectrum;
        final List<PreparedTrack.SynthEvent> synthEvents;

        PreparedTrackData(
                String fileName,
                String displayTitle,
                String copyright,
                byte[] primaryBytes,
                int durationMs,
                int loopStartMs,
                int playbackEndFrame,
                int loopStartFrame,
                boolean hasInternalLoop,
                TimelineSpectrum spectrum,
                List<PreparedTrack.SynthEvent> synthEvents) {
            this.fileName = fileName;
            this.displayTitle = displayTitle;
            this.copyright = copyright;
            this.primaryBytes = primaryBytes;
            this.durationMs = durationMs;
            this.loopStartMs = loopStartMs;
            this.playbackEndFrame = playbackEndFrame;
            this.loopStartFrame = loopStartFrame;
            this.hasInternalLoop = hasInternalLoop;
            this.spectrum = spectrum;
            this.synthEvents = synthEvents;
        }
    }

    private static final class WarmTrackSlot {
        final int index;
        final PreparedTrack track;
        Sf2StreamingPlaybackEngine.WarmSession session;

        WarmTrackSlot(int index, PreparedTrack track, Sf2StreamingPlaybackEngine.WarmSession session) {
            this.index = index;
            this.track = track;
            this.session = session;
        }

        Sf2StreamingPlaybackEngine.WarmSession consumeSession() {
            Sf2StreamingPlaybackEngine.WarmSession value = session;

            session = null;
            return value;
        }

        void release() {
            if (session != null) {
                session.release();
                session = null;
            }
        }
    }
}
