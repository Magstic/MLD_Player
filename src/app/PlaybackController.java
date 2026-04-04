package app;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.midlet.MIDlet;

import container.MldFile;
import container.MldParser;
import container.TrackChunk;
import event.TrackDecodeResult;
import event.TrackDecoder;
import io.FileConnectionLoader;
import playback.J2meMidiPlayer;
import playback.MidiBytesBuilder;
import timeline.PlaybackTimeline;
import timeline.TimelineCompiler;

public final class PlaybackController
        implements CommandListener, J2meMidiPlayer.Listener, PlayerCanvas.ActionHandler {
    private static final int LOOP_MODE_ONE = 0;
    private static final int LOOP_MODE_LIST = 1;
    private static final int BROWSER_MODE_FILE = 0;
    private static final int BROWSER_MODE_FOLDER = 1;
    private static final int PLAYBACK_REFRESH_INTERVAL_MS = 75;
    private static final int LOADING_REFRESH_INTERVAL_MS = 120;
    private static final int IDLE_ANIMATION_REFRESH_INTERVAL_MS = 150;
    private static final int SPECTRUM_FRAME_INTERVAL_MS = 75;
    private static final int SPECTRUM_BAR_COUNT = PlayerTheme.BAR_PROFILE.length;
    private static final String[] EMPTY_PLAYLIST_TITLES = new String[0];

    private final MIDlet midlet;
    private final Display display;
    private final FileConnectionLoader fileLoader = new FileConnectionLoader();
    private final MldParser parser = new MldParser();
    private final TrackDecoder decoder = new TrackDecoder();
    private final TimelineCompiler compiler = new TimelineCompiler();
    private final MidiBytesBuilder midiBytesBuilder = new MidiBytesBuilder();
    private final J2meMidiPlayer midiPlayer = new J2meMidiPlayer();

    private final List browserList = new List("MLD Files", List.IMPLICIT);
    private final PlayerCanvas playerCanvas = new PlayerCanvas(this);

    private final Command openCommand = new Command("Open", Command.OK, 1);
    private final Command useHereCommand = new Command("Use Here", Command.OK, 1);
    private final Command backCommand = new Command("Back", Command.BACK, 1);
    private final Command exitCommand = new Command("Exit", Command.EXIT, 9);

    private final Vector directoryStack = new Vector();
    private final Vector playlistEntries = new Vector();
    private Vector currentEntries = new Vector();
    private String[] playlistTitleCache = EMPTY_PLAYLIST_TITLES;

    private String currentDirectoryUrl;
    private String playlistRootUrl;
    private LoadedTrack currentTrack;
    private boolean loopBodyPhaseActive;
    private boolean loading;
    private boolean playlistScanRunning;
    private boolean destroyed;
    private boolean playerCanvasVisible;
    private boolean refreshThreadRunning;
    private int browserMode = BROWSER_MODE_FILE;
    private int loopMode = LOOP_MODE_ONE;
    private int playlistSelectedIndex = -1;
    private int playlistCurrentIndex = -1;
    private int loadToken;
    private int playlistScanToken;

    private String currentTitle = "MLD Player";
    private String currentCopyright = "";
    private String currentFileName = "";
    private String currentStatusText = "Use Open to choose an MLD";
    private int currentStatusKind = PlayerCanvas.STATUS_STOPPED;
    private long retainedPositionMillis;
    private boolean resumePending;

    public PlaybackController(MIDlet midlet) {
        this.midlet = midlet;
        this.display = Display.getDisplay(midlet);

        configureBrowserCommands();
        browserList.setCommandListener(this);
    }

    public void start() {
        if (destroyed) {
            return;
        }
        if (display.getCurrent() == null) {
            refreshPlayerCanvas(true);
        }
        if (resumePending && currentTrack != null) {
            resumePending = false;
            startPlaybackFromPosition(retainedPositionMillis);
        }
    }

    public void pause() {
        pausePlaybackForLifecycle();
    }

    public void destroy() {
        destroyed = true;
        loadToken++;
        closePlayer();
    }

    public void commandAction(Command command, Displayable displayable) {
        if (displayable == browserList) {
            handleBrowserCommand(command);
        }
    }

    public void onPrimaryActionRequested() {
        if (loading) {
            return;
        }
        if (currentStatusKind == PlayerCanvas.STATUS_PLAYING || midiPlayer.isPlaying()) {
            stopPlayback();
        } else {
            playCurrentTrack();
        }
    }

    public void onOpenRequested() {
        showFileBrowser();
    }

    public void onOpenListRequested() {
        showFolderBrowser();
    }

    public void onLoopModeToggleRequested() {
        loopMode = loopMode == LOOP_MODE_ONE ? LOOP_MODE_LIST : LOOP_MODE_ONE;
        playerCanvas.closeMenu();
        if (isPlaybackActive()
                && loopMode == LOOP_MODE_LIST
                && loopBodyPhaseActive
                && hasValidPlaylistCurrentTrack()) {
            retainedPositionMillis = 0L;
            resumePending = false;
            startPlaybackFromPosition(0L);
            return;
        }
        refreshPlayerCanvas(false);
    }

    public void onPlaylistRequested() {
    }

    public void onPlaylistItemRequested(int index) {
        PlaylistEntry entry;

        if (loading || index < 0 || index >= playlistEntries.size()) {
            return;
        }

        playlistSelectedIndex = index;
        entry = (PlaylistEntry) playlistEntries.elementAt(index);
        playerCanvas.closePlaylist();
        beginLoadFile(entry.url, entry.fileName, true);
    }

    public void onPreviousTrackRequested() {
        switchPlaylistTrack(-1);
    }

    public void onNextTrackRequested() {
        switchPlaylistTrack(1);
    }

    public void onExitRequested() {
        exitApplication();
    }

    public void onPlaybackCanvasVisibilityChanged(boolean visible) {
        playerCanvasVisible = visible;
        if (visible) {
            refreshPlayerCanvas(false);
            ensureRefreshLoopRunning();
        }
    }

    public void onPlaybackCompleted() {
        if (destroyed) {
            return;
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (!destroyed) {
                    handlePlaybackCompletedOnUi();
                }
            }
        });
    }

    public void onPlaybackError(final String message) {
        if (destroyed) {
            return;
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (!destroyed) {
                    loopBodyPhaseActive = false;
                    setPlaybackStatus(PlayerCanvas.STATUS_ERROR, describeThrowableMessage(message, "MIDI unsupported"));
                }
            }
        });
    }

    private void handleBrowserCommand(Command command) {
        if (command == List.SELECT_COMMAND || command == openCommand) {
            openSelection();
        } else if (browserMode == BROWSER_MODE_FOLDER && command == useHereCommand) {
            useCurrentDirectoryForPlaylist();
        } else if (command == backCommand) {
            navigateUp();
        } else if (command == exitCommand) {
            exitApplication();
        }
    }

    private void showFileBrowser() {
        if (loading) {
            return;
        }
        playerCanvas.closeMenu();
        playerCanvas.closePlaylist();
        browserMode = BROWSER_MODE_FILE;
        refreshBrowser(currentDirectoryUrl);
    }

    private void showFolderBrowser() {
        if (loading) {
            return;
        }
        playerCanvas.closeMenu();
        playerCanvas.closePlaylist();
        browserMode = BROWSER_MODE_FOLDER;
        refreshBrowser(currentDirectoryUrl);
    }

    private void refreshBrowser(String directoryUrl) {
        try {
            currentEntries = listBrowserEntries(directoryUrl);
            currentDirectoryUrl = directoryUrl;
            configureBrowserCommands();
            rebuildBrowserList();
            display.setCurrent(browserList);
        } catch (IOException e) {
            showPlaybackState(directoryUrl == null ? "Storage" : directoryUrl, "", "", PlayerCanvas.STATUS_ERROR, "Cannot open file");
        }
    }

    private void rebuildBrowserList() {
        int i;
        browserList.deleteAll();
        browserList.setTitle(browserTitle());
        for (i = 0; i < currentEntries.size(); i++) {
            FileConnectionLoader.Entry entry = (FileConnectionLoader.Entry) currentEntries.elementAt(i);
            if (browserMode == BROWSER_MODE_FILE) {
                browserList.append(entry.directory ? "[" + entry.displayName + "]" : entry.displayName, null);
            } else {
                browserList.append(entry.displayName, null);
            }
        }
    }

    private void openSelection() {
        int index;
        FileConnectionLoader.Entry entry;

        if (loading || currentEntries == null || currentEntries.size() == 0) {
            return;
        }

        index = browserList.getSelectedIndex();
        if (index < 0 || index >= currentEntries.size()) {
            return;
        }

        entry = (FileConnectionLoader.Entry) currentEntries.elementAt(index);
        if (entry.directory) {
            if (currentDirectoryUrl != null) {
                directoryStack.addElement(currentDirectoryUrl);
            }
            refreshBrowser(entry.url);
            return;
        }
        if (browserMode == BROWSER_MODE_FILE) {
            beginLoadFile(entry.url, entry.displayName, false);
        }
    }

    private void navigateUp() {
        if (loading) {
            return;
        }
        if (currentDirectoryUrl == null) {
            display.setCurrent(playerCanvas);
            return;
        }
        if (directoryStack.size() == 0) {
            directoryStack.removeAllElements();
            currentDirectoryUrl = null;
            refreshBrowser(null);
            return;
        }
        currentDirectoryUrl = (String) directoryStack.elementAt(directoryStack.size() - 1);
        directoryStack.removeElementAt(directoryStack.size() - 1);
        refreshBrowser(currentDirectoryUrl);
    }

    private void useCurrentDirectoryForPlaylist() {
        String rootUrl = currentDirectoryUrl;
        int index;
        FileConnectionLoader.Entry entry;

        if (loading) {
            return;
        }

        if (rootUrl == null) {
            index = browserList.getSelectedIndex();
            if (index < 0 || index >= currentEntries.size()) {
                return;
            }
            entry = (FileConnectionLoader.Entry) currentEntries.elementAt(index);
            if (!entry.directory) {
                return;
            }
            rootUrl = entry.url;
        }

        beginPlaylistScan(rootUrl);
    }

    private void beginLoadFile(final String fileUrl, final String displayName, final boolean autoPlay) {
        final int token;

        closePlayer();
        loopBodyPhaseActive = false;
        loading = true;
        loadToken++;
        token = loadToken;
        currentTrack = null;
        retainedPositionMillis = 0L;
        resumePending = false;

        showPlaybackState(displayName, "", "", PlayerCanvas.STATUS_LOADING, "Loading");

        new Thread(new Runnable() {
            public void run() {
                loadTrack(fileUrl, displayName, autoPlay, token);
            }
        }).start();
    }

    private void beginPlaylistScan(final String rootUrl) {
        final int token;

        if (rootUrl == null) {
            return;
        }

        playlistScanRunning = true;
        playlistScanToken++;
        token = playlistScanToken;
        display.setCurrent(playerCanvas);
        refreshPlayerCanvas(false);

        new Thread(new Runnable() {
            public void run() {
                scanPlaylist(rootUrl, token);
            }
        }).start();
    }

    private void scanPlaylist(String rootUrl, int token) {
        Vector entries = new Vector();

        try {
            scanPlaylistEntriesRecursive(rootUrl, entries, token);
            if (shouldAbortPlaylistScan(token)) {
                return;
            }
            postPlaylistScanSuccess(entries, rootUrl, token);
        } catch (IOException e) {
            postPlaylistScanFailure("Cannot open file", token);
        }
    }

    private void scanPlaylistEntriesRecursive(String directoryUrl, Vector output, int token) throws IOException {
        Vector entries;
        int i;

        if (shouldAbortPlaylistScan(token) || directoryUrl == null) {
            return;
        }

        entries = fileLoader.listEntries(directoryUrl);
        for (i = 0; i < entries.size(); i++) {
            FileConnectionLoader.Entry entry = (FileConnectionLoader.Entry) entries.elementAt(i);

            if (shouldAbortPlaylistScan(token)) {
                return;
            }
            if (entry.directory) {
                scanPlaylistEntriesRecursive(entry.url, output, token);
            } else if (isPlaylistFile(entry.displayName)) {
                PlaylistEntry playlistEntry = createPlaylistEntry(entry);
                if (playlistEntry != null) {
                    output.addElement(playlistEntry);
                }
            }
        }
    }

    private PlaylistEntry createPlaylistEntry(FileConnectionLoader.Entry entry) {
        byte[] bytes;
        MldFile file;
        String title;

        if (entry == null || entry.directory) {
            return null;
        }

        try {
            bytes = fileLoader.loadBytes(entry.url);
            file = parser.parse(bytes);
            title = chooseTitle(file, entry.displayName);
        } catch (Throwable ignored) {
            return null;
        }
        return new PlaylistEntry(entry.url, entry.displayName, title);
    }

    private void loadTrack(final String fileUrl, final String displayName, final boolean autoPlay, final int token) {
        byte[] bytes;
        MldFile file;
        Vector decodedTracks;
        PlaybackTimeline timeline;
        byte[] initialMidi;
        byte[] loopMidi = null;
        LoadedTrack loaded;
        long finiteDurationMillis;
        long loopStartMillis = -1L;
        long loopBodyDurationMillis = -1L;
        boolean hasLoop;
        SpectrumModel spectrumModel;
        String title = displayName;
        String copyright = "";

        try {
            bytes = fileLoader.loadBytes(fileUrl);
        } catch (IOException e) {
            postLoadError(displayName, "", "", "Cannot open file", token);
            return;
        }

        try {
            file = parser.parse(bytes);
        } catch (Throwable t) {
            postLoadError(displayName, title, copyright, "Parse: " + describeThrowable(t, "Invalid MLD"), token);
            return;
        }

        title = chooseTitle(file, displayName);
        copyright = chooseCopyright(file);

        try {
            decodedTracks = decodeTracks(file);
        } catch (Throwable t) {
            postLoadError(displayName, title, copyright, "Decode: " + describeThrowable(t, "Invalid MLD"), token);
            return;
        }

        try {
            timeline = compiler.compile(file, decodedTracks);
        } catch (Throwable t) {
            postLoadError(displayName, title, copyright, "Timeline: " + describeThrowable(t, "Invalid MLD"), token);
            return;
        }

        if (timeline.notes == null || timeline.notes.size() == 0) {
            postLoadError(displayName, title, copyright, "No melody tracks", token);
            return;
        }

        try {
            initialMidi = midiBytesBuilder.build(timeline);
            hasLoop = timeline.loopInfo != null && timeline.loopInfo.hasLoop;
            finiteDurationMillis = estimateTimelineDurationMillis(
                    timeline,
                    hasLoop ? timeline.loopInfo.loopEndMidiTick : timeline.totalMidiTicks);
            spectrumModel = buildSpectrumModel(timeline, finiteDurationMillis);
            if (hasLoop) {
                loopMidi = midiBytesBuilder.buildLoopBody(timeline);
                loopStartMillis = estimateTimelineDurationMillis(
                        timeline,
                        timeline.loopInfo.loopStartMidiTick);
                loopBodyDurationMillis = estimateTimelineDurationMillis(
                        timeline,
                        timeline.loopInfo.loopEndMidiTick - timeline.loopInfo.loopStartMidiTick);
            }
        } catch (Throwable t) {
            postLoadError(displayName, title, copyright, "MIDI: " + describeThrowable(t, "Invalid MLD"), token);
            return;
        }

        loaded = new LoadedTrack(
                fileUrl,
                displayName,
                title,
                copyright,
                initialMidi,
                loopMidi,
                hasLoop,
                finiteDurationMillis,
                loopStartMillis,
                loopBodyDurationMillis,
                spectrumModel);
        postLoadSuccess(loaded, token, autoPlay);
    }

    private Vector decodeTracks(MldFile file) throws IOException {
        Vector decoded = new Vector();
        int i;
        for (i = 0; i < file.tracks.size(); i++) {
            TrackChunk track = (TrackChunk) file.tracks.elementAt(i);
            TrackDecodeResult result = decoder.decode(file, track);
            decoded.addElement(result);
        }
        return decoded;
    }

    private void postLoadSuccess(final LoadedTrack loaded, final int token, final boolean autoPlay) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != loadToken) {
                    return;
                }
                loading = false;
                currentTrack = loaded;
                loopBodyPhaseActive = false;
                retainedPositionMillis = 0L;
                resumePending = false;
                syncPlaylistCurrentTrack();
                if (autoPlay) {
                    currentFileName = safeText(loaded.fileName, "");
                    currentTitle = safeText(loaded.title, "");
                    currentCopyright = safeText(loaded.copyright, "");
                    currentStatusKind = PlayerCanvas.STATUS_STOPPED;
                    currentStatusText = "Stopped";
                    startPlaybackFromPosition(0L);
                } else {
                    showPlaybackState(
                            loaded.fileName,
                            loaded.title,
                            loaded.copyright,
                            PlayerCanvas.STATUS_STOPPED,
                            "Stopped");
                }
            }
        });
    }

    private void postLoadError(
            final String fileName,
            final String title,
            final String copyright,
            final String status,
            final int token) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != loadToken) {
                    return;
                }
                loading = false;
                currentTrack = null;
                playlistCurrentIndex = -1;
                loopBodyPhaseActive = false;
                retainedPositionMillis = 0L;
                resumePending = false;
                showPlaybackState(fileName, title, copyright, PlayerCanvas.STATUS_ERROR, status);
            }
        });
    }

    private void postPlaylistScanSuccess(final Vector entries, final String rootUrl, final int token) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != playlistScanToken) {
                    return;
                }
                playlistScanRunning = false;
                replacePlaylist(entries, rootUrl);
                if (playlistEntries.size() == 0) {
                    currentStatusText = "No MLD in folder";
                }
                refreshPlayerCanvas(true);
            }
        });
    }

    private void postPlaylistScanFailure(final String statusText, final int token) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != playlistScanToken) {
                    return;
                }
                playlistScanRunning = false;
                currentStatusText = safeText(statusText, "Cannot open file");
                refreshPlayerCanvas(true);
            }
        });
    }

    private void showPlaybackState(String fileName, String title, String copyright, int statusKind, String statusText) {
        currentFileName = safeText(fileName, "");
        currentTitle = safeText(title, "");
        currentCopyright = safeText(copyright, "");
        currentStatusKind = statusKind;
        currentStatusText = safeText(statusText, "Stopped");
        refreshPlayerCanvas(true);
    }

    private void playCurrentTrack() {
        if (loading || currentTrack == null) {
            return;
        }
        if (resumePending && retainedPositionMillis > 0L) {
            resumePending = false;
            startPlaybackFromPosition(retainedPositionMillis);
            return;
        }
        retainedPositionMillis = 0L;
        startPlaybackFromPosition(0L);
    }

    private void stopPlayback() {
        closePlayer();
        loopBodyPhaseActive = false;
        retainedPositionMillis = 0L;
        resumePending = false;
        if (!loading) {
            setPlaybackStatus(PlayerCanvas.STATUS_STOPPED, "Stopped");
        }
    }

    private void closePlayer() {
        midiPlayer.close();
    }

    private void handlePlaybackCompletedOnUi() {
        retainedPositionMillis = 0L;
        if (advancePlaylistOnCompletion()) {
            return;
        }
        if (currentTrack != null && currentTrack.loopMidi != null && shouldUseTrackLoopBody()) {
            startLoopBodyPlayback(0L);
            return;
        }
        if (currentTrack != null) {
            startPlaybackFromPosition(0L);
            return;
        }

        loopBodyPhaseActive = false;
        setPlaybackStatus(PlayerCanvas.STATUS_STOPPED, "Stopped");
    }

    private void setPlaybackStatus(int statusKind, String statusText) {
        currentStatusKind = statusKind;
        currentStatusText = safeText(statusText, "Stopped");
        refreshPlayerCanvas(false);
    }

    private void refreshPlayerCanvas(boolean makeCurrent) {
        PlayerCanvas.State canvasState = buildPlayerCanvasState();

        playerCanvas.setState(canvasState);
        if (makeCurrent) {
            display.setCurrent(playerCanvas);
        }
        if (canvasState.statusKind == PlayerCanvas.STATUS_PLAYING || playerCanvas.needsAnimation()) {
            ensureRefreshLoopRunning();
        }
    }

    private PlayerCanvas.State buildPlayerCanvasState() {
        boolean playing = isPlaybackActive();
        boolean loopingIndefinitely = playing && currentTrack != null && currentTrack.hasLoop && loopBodyPhaseActive;
        long positionMillis = retainedPositionMillis;
        long durationMillis = -1L;
        int primaryAction = PlayerCanvas.PRIMARY_NONE;
        int[] spectrumBars = createIdleSpectrum();
        String statusText = playlistScanRunning ? "Scanning List" : currentStatusText;

        if (playing) {
            positionMillis = currentPlaybackPositionMillis();
        }

        if (currentTrack != null) {
            if (currentTrack.hasLoop) {
                durationMillis = currentTrack.estimatedFiniteDurationMillis;
            } else {
                durationMillis = midiPlayer.getDurationMillis();
            }
            spectrumBars = currentTrack.spectrumModel.snapshot(positionMillis, playing);
        }

        if (playing) {
            primaryAction = PlayerCanvas.PRIMARY_STOP;
        } else if (!loading && currentTrack != null) {
            primaryAction = PlayerCanvas.PRIMARY_PLAY;
        }

        if (loading) {
            spectrumBars = createLoadingSpectrum();
        } else if (!playing && currentTrack != null) {
            spectrumBars = softenSpectrum(spectrumBars);
        } else if (currentTrack == null) {
            spectrumBars = createIdleSpectrum();
        }

        return new PlayerCanvas.State(
                "MLD Player",
                currentTitle,
                currentCopyright,
                currentFileName,
                statusText,
                currentStatusKind,
                primaryAction,
                currentTrack != null && currentTrack.hasLoop,
                loopingIndefinitely,
                currentTrack != null,
                primaryAction != PlayerCanvas.PRIMARY_NONE,
                positionMillis,
                durationMillis,
                spectrumBars,
                playlistTitleCache,
                playlistSelectedIndex,
                playlistCurrentIndex,
                playlistEntries.size() == 0,
                loopMode == LOOP_MODE_LIST ? "Loop List" : "Loop One");
    }

    private void pausePlaybackForLifecycle() {
        if (loading || !isPlaybackActive()) {
            return;
        }
        retainedPositionMillis = currentPlaybackPositionMillis();
        resumePending = true;
        closePlayer();
        setPlaybackStatus(PlayerCanvas.STATUS_STOPPED, "Paused");
    }

    private void startPlaybackFromPosition(long positionMillis) {
        long clampedPosition = maxLong(0L, positionMillis);

        if (currentTrack == null) {
            return;
        }

        closePlayer();
        try {
            if (currentTrack.hasLoop
                    && currentTrack.loopMidi != null
                    && currentTrack.estimatedLoopStartMillis >= 0L
                    && clampedPosition >= currentTrack.estimatedLoopStartMillis
                    && shouldUseTrackLoopBody()) {
                startLoopBodyPlayback(clampedPosition - currentTrack.estimatedLoopStartMillis);
                return;
            }

            loopBodyPhaseActive = false;
            midiPlayer.play(currentTrack.initialMidi, 1, clampedPosition, this);
            retainedPositionMillis = clampedPosition;
            setPlaybackStatus(PlayerCanvas.STATUS_PLAYING, "Playing");
            ensureRefreshLoopRunning();
        } catch (Throwable t) {
            loopBodyPhaseActive = false;
            setPlaybackStatus(PlayerCanvas.STATUS_ERROR, describeThrowable(t, "MIDI unsupported"));
        }
    }

    private void startLoopBodyPlayback(long offsetMillis) {
        long loopOffset = maxLong(0L, offsetMillis);

        if (currentTrack == null || currentTrack.loopMidi == null) {
            loopBodyPhaseActive = false;
            setPlaybackStatus(PlayerCanvas.STATUS_STOPPED, "Stopped");
            return;
        }

        if (currentTrack.estimatedLoopBodyDurationMillis > 0L) {
            loopOffset = loopOffset % currentTrack.estimatedLoopBodyDurationMillis;
        } else {
            loopOffset = 0L;
        }

        closePlayer();
        try {
            loopBodyPhaseActive = true;
            midiPlayer.play(currentTrack.loopMidi, -1, loopOffset, this);
            retainedPositionMillis = currentTrack.estimatedLoopStartMillis + loopOffset;
            setPlaybackStatus(PlayerCanvas.STATUS_PLAYING, "Playing");
            ensureRefreshLoopRunning();
        } catch (Throwable t) {
            loopBodyPhaseActive = false;
            setPlaybackStatus(PlayerCanvas.STATUS_ERROR, describeThrowable(t, "MIDI unsupported"));
        }
    }

    private long currentPlaybackPositionMillis() {
        long mediaTimeMillis;

        if (currentTrack == null || !isPlaybackActive()) {
            return retainedPositionMillis;
        }

        mediaTimeMillis = maxLong(0L, midiPlayer.getMediaTimeMillis());
        if (loopBodyPhaseActive
                && currentTrack.hasLoop
                && currentTrack.estimatedLoopStartMillis >= 0L) {
            if (currentTrack.estimatedLoopBodyDurationMillis > 0L) {
                mediaTimeMillis = mediaTimeMillis % currentTrack.estimatedLoopBodyDurationMillis;
            }
            return currentTrack.estimatedLoopStartMillis + mediaTimeMillis;
        }
        return mediaTimeMillis;
    }

    private void ensureRefreshLoopRunning() {
        synchronized (this) {
            if (destroyed || refreshThreadRunning || !shouldRunRefreshLoop()) {
                return;
            }
            refreshThreadRunning = true;
        }

        new Thread(new Runnable() {
            public void run() {
                runRefreshLoop();
            }
        }).start();
    }

    private void runRefreshLoop() {
        try {
            while (shouldRunRefreshLoop()) {
                display.callSerially(new Runnable() {
                    public void run() {
                        if (!destroyed) {
                            refreshPlayerCanvas(false);
                        }
                    }
                });
                try {
                    Thread.sleep(currentRefreshIntervalMillis());
                } catch (InterruptedException ignored) {
                }
            }
        } finally {
            synchronized (this) {
                refreshThreadRunning = false;
            }
            if (shouldRunRefreshLoop()) {
                ensureRefreshLoopRunning();
            }
        }
    }

    private synchronized boolean shouldRunRefreshLoop() {
        return !destroyed && playerCanvasVisible && (isPlaybackActive() || playerCanvas.needsAnimation());
    }

    private int currentRefreshIntervalMillis() {
        if (isPlaybackActive()) {
            return PLAYBACK_REFRESH_INTERVAL_MS;
        }
        if (loading) {
            return LOADING_REFRESH_INTERVAL_MS;
        }
        return IDLE_ANIMATION_REFRESH_INTERVAL_MS;
    }

    private boolean isPlaybackActive() {
        return currentStatusKind == PlayerCanvas.STATUS_PLAYING || midiPlayer.isPlaying();
    }

    private Vector listBrowserEntries(String directoryUrl) throws IOException {
        Vector entries = directoryUrl == null ? fileLoader.listRoots() : fileLoader.listEntries(directoryUrl);
        Vector filtered;
        int i;

        if (browserMode == BROWSER_MODE_FILE) {
            return entries;
        }

        filtered = new Vector();
        for (i = 0; i < entries.size(); i++) {
            FileConnectionLoader.Entry entry = (FileConnectionLoader.Entry) entries.elementAt(i);
            if (entry.directory) {
                filtered.addElement(entry);
            }
        }
        return filtered;
    }

    private void configureBrowserCommands() {
        browserList.removeCommand(openCommand);
        browserList.removeCommand(useHereCommand);
        browserList.removeCommand(backCommand);
        browserList.removeCommand(exitCommand);
        browserList.addCommand(openCommand);
        if (browserMode == BROWSER_MODE_FOLDER) {
            browserList.addCommand(useHereCommand);
        }
        browserList.addCommand(backCommand);
        browserList.addCommand(exitCommand);
    }

    private String browserTitle() {
        if (browserMode == BROWSER_MODE_FOLDER) {
            return currentDirectoryUrl == null ? "Choose Folder" : trimDirectoryTitle(currentDirectoryUrl);
        }
        return currentDirectoryUrl == null ? "MLD Files" : trimDirectoryTitle(currentDirectoryUrl);
    }

    private void replacePlaylist(Vector entries, String rootUrl) {
        int i;
        int matchIndex = -1;

        playlistEntries.removeAllElements();
        if (entries != null) {
            for (i = 0; i < entries.size(); i++) {
                playlistEntries.addElement(entries.elementAt(i));
            }
        }

        playlistRootUrl = rootUrl;
        playlistTitleCache = new String[playlistEntries.size()];
        for (i = 0; i < playlistEntries.size(); i++) {
            PlaylistEntry entry = (PlaylistEntry) playlistEntries.elementAt(i);
            playlistTitleCache[i] = entry.displayTitle;
        }

        if (currentTrack != null && currentTrack.sourceUrl != null) {
            matchIndex = indexOfPlaylistEntry(currentTrack.sourceUrl);
        }

        playlistCurrentIndex = matchIndex;
        if (playlistEntries.size() == 0) {
            playlistSelectedIndex = -1;
        } else if (matchIndex >= 0) {
            playlistSelectedIndex = matchIndex;
        } else {
            playlistSelectedIndex = 0;
        }
    }

    private void syncPlaylistCurrentTrack() {
        int matchIndex;

        if (currentTrack == null || currentTrack.sourceUrl == null) {
            playlistCurrentIndex = -1;
            return;
        }

        matchIndex = indexOfPlaylistEntry(currentTrack.sourceUrl);
        playlistCurrentIndex = matchIndex;
        if (matchIndex >= 0) {
            playlistSelectedIndex = matchIndex;
        }
    }

    private int indexOfPlaylistEntry(String sourceUrl) {
        int i;

        if (sourceUrl == null) {
            return -1;
        }

        for (i = 0; i < playlistEntries.size(); i++) {
            PlaylistEntry entry = (PlaylistEntry) playlistEntries.elementAt(i);
            if (sourceUrl.equals(entry.url)) {
                return i;
            }
        }
        return -1;
    }

    private boolean shouldUseTrackLoopBody() {
        return currentTrack != null && currentTrack.loopMidi != null
                && (loopMode == LOOP_MODE_ONE || !hasValidPlaylistCurrentTrack());
    }

    private boolean hasValidPlaylistCurrentTrack() {
        return playlistEntries.size() > 0
                && playlistCurrentIndex >= 0
                && playlistCurrentIndex < playlistEntries.size()
                && currentTrack != null
                && currentTrack.sourceUrl != null;
    }

    private boolean advancePlaylistOnCompletion() {
        PlaylistEntry nextEntry;
        int nextIndex;

        if (loopMode != LOOP_MODE_LIST || !hasValidPlaylistCurrentTrack()) {
            return false;
        }

        nextIndex = playlistCurrentIndex + 1;
        if (nextIndex >= playlistEntries.size()) {
            nextIndex = 0;
        }

        playlistSelectedIndex = nextIndex;
        playlistCurrentIndex = -1;
        nextEntry = (PlaylistEntry) playlistEntries.elementAt(nextIndex);
        beginLoadFile(nextEntry.url, nextEntry.fileName, true);
        return true;
    }

    private void switchPlaylistTrack(int delta) {
        PlaylistEntry entry;
        int count = playlistEntries.size();
        int index = playlistNavigationBaseIndex();

        if (loading || delta == 0 || count <= 1 || index < 0 || index >= count) {
            return;
        }

        index += delta;
        if (index < 0) {
            index = count - 1;
        } else if (index >= count) {
            index = 0;
        }

        playlistSelectedIndex = index;
        entry = (PlaylistEntry) playlistEntries.elementAt(index);
        playerCanvas.closeMenu();
        playerCanvas.closePlaylist();
        beginLoadFile(entry.url, entry.fileName, true);
    }

    private int playlistNavigationBaseIndex() {
        int index;

        if (playlistEntries.size() == 0) {
            return -1;
        }

        index = playlistCurrentIndex;
        if (index >= 0 && index < playlistEntries.size()) {
            return index;
        }

        index = playlistSelectedIndex;
        if (index >= 0 && index < playlistEntries.size()) {
            return index;
        }

        if (currentTrack != null && currentTrack.sourceUrl != null) {
            index = indexOfPlaylistEntry(currentTrack.sourceUrl);
            if (index >= 0) {
                return index;
            }
        }

        return 0;
    }

    private boolean shouldAbortPlaylistScan(int token) {
        return destroyed || token != playlistScanToken;
    }

    private static boolean isPlaylistFile(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.endsWith(".mld");
    }

    private void exitApplication() {
        destroy();
        midlet.notifyDestroyed();
    }

    private static String chooseTitle(MldFile file, String fallback) {
        String title = sanitizeText(file.firstInfoText("titl"));
        return title.length() == 0 ? fallback : title;
    }

    private static String chooseCopyright(MldFile file) {
        String text = sanitizeText(file.firstInfoText("copy"));
        return text;
    }

    private static String sanitizeText(String text) {
        int i;
        StringBuffer buffer;
        String trimmed;

        if (text == null) {
            return "";
        }

        buffer = new StringBuffer();
        for (i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == 0) {
                continue;
            }
            buffer.append(ch);
        }
        trimmed = buffer.toString().trim();
        return trimmed;
    }

    private static String describeThrowable(Throwable throwable, String fallback) {
        if (throwable == null) {
            return fallback;
        }
        return describeThrowableMessage(throwable.getMessage(), safeText(throwable.toString(), fallback));
    }

    private static String describeThrowableMessage(String message, String fallback) {
        String sanitized = sanitizeText(message);
        if (sanitized.length() == 0) {
            sanitized = sanitizeText(fallback);
        }
        if (sanitized.length() == 0) {
            return "Error";
        }
        return sanitized;
    }

    private static String trimDirectoryTitle(String url) {
        int end;
        int index;
        if (url == null) {
            return "MLD Files";
        }
        end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        index = url.lastIndexOf('/', end - 1);
        if (index >= 0 && index < end - 1) {
            return url.substring(index + 1, end);
        }
        return url;
    }

    private static String safeText(String text, String fallback) {
        String value = sanitizeText(text);
        return value.length() == 0 ? fallback : value;
    }

    private static long estimateTimelineDurationMillis(PlaybackTimeline timeline, long endMidiTick) {
        long micros = 0L;
        int i;

        if (timeline == null || timeline.tempoPoints == null || timeline.tempoPoints.size() == 0 || endMidiTick <= 0L) {
            return -1L;
        }

        for (i = 0; i < timeline.tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = (PlaybackTimeline.TempoPoint) timeline.tempoPoints.elementAt(i);
            long segmentStart = maxLong(0L, point.midiTick);
            long segmentEnd = endMidiTick;

            if (segmentStart >= endMidiTick) {
                break;
            }
            if (i + 1 < timeline.tempoPoints.size()) {
                PlaybackTimeline.TempoPoint next = (PlaybackTimeline.TempoPoint) timeline.tempoPoints.elementAt(i + 1);
                segmentEnd = minLong(segmentEnd, next.midiTick);
            }
            if (segmentEnd > segmentStart) {
                micros += ((segmentEnd - segmentStart) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
            }
        }

        if (micros <= 0L) {
            return -1L;
        }
        return maxLong(1L, micros / 1000L);
    }

    private int[] createLoadingSpectrum() {
        int[] bars = new int[SPECTRUM_BAR_COUNT];
        int i;
        int frame = refreshThreadRunning ? 1 : 0;

        for (i = 0; i < bars.length; i++) {
            bars[i] = 14 + ((triangleWave((loadToken * 3) + frame + (i * 4), 20) * 30) / 100);
        }
        return bars;
    }

    private static int[] createIdleSpectrum() {
        int[] bars = new int[SPECTRUM_BAR_COUNT];
        int i;
        for (i = 0; i < bars.length; i++) {
            bars[i] = 5 + ((i % 4) * 2);
        }
        return bars;
    }

    private static int[] softenSpectrum(int[] bars) {
        int[] softened = new int[SPECTRUM_BAR_COUNT];
        int i;

        if (bars == null) {
            return createIdleSpectrum();
        }

        for (i = 0; i < softened.length; i++) {
            int value = i < bars.length ? bars[i] : 0;
            softened[i] = clamp(4, 32, 5 + (value / 3));
        }
        return softened;
    }

    private static SpectrumModel buildSpectrumModel(PlaybackTimeline timeline, long durationMillis) {
        byte[] frames;
        int frameCount;
        int i;

        if (timeline == null || timeline.notes == null || timeline.notes.size() == 0 || durationMillis <= 0L) {
            return SpectrumModel.empty();
        }

        frameCount = max(1, (int) ((durationMillis + SPECTRUM_FRAME_INTERVAL_MS - 1L) / SPECTRUM_FRAME_INTERVAL_MS) + 1);
        frames = new byte[frameCount * SPECTRUM_BAR_COUNT];

        for (i = 0; i < timeline.notes.size(); i++) {
            PlaybackTimeline.CompiledNote note = (PlaybackTimeline.CompiledNote) timeline.notes.elementAt(i);
            applySpectrumNote(frames, frameCount, timeline, note);
        }

        smoothSpectrumFrames(frames, frameCount);
        return new SpectrumModel(frames, frameCount);
    }

    private static void applySpectrumNote(
            byte[] frames,
            int frameCount,
            PlaybackTimeline timeline,
            PlaybackTimeline.CompiledNote note) {
        int bar = spectrumBarForNote(note);
        long startMillis = midiTickToMillis(timeline, note.midiStartTick);
        long endMillis = midiTickToMillis(timeline, note.midiEndTick);
        int startFrame;
        int endFrame;
        int frame;
        int attackLevel;
        int sustainLevel;
        int releaseLevel;

        if (endMillis <= startMillis) {
            endMillis = startMillis + SPECTRUM_FRAME_INTERVAL_MS;
        }

        startFrame = clamp(0, frameCount - 1, (int) (startMillis / SPECTRUM_FRAME_INTERVAL_MS));
        endFrame = clamp(0, frameCount - 1, (int) (maxLong(startMillis, endMillis - 1L) / SPECTRUM_FRAME_INTERVAL_MS));
        attackLevel = clamp(18, 100, 22 + ((note.velocity * 64) / 127));
        sustainLevel = clamp(10, 88, 10 + ((note.velocity * 44) / 127));
        releaseLevel = clamp(8, 64, 8 + ((note.velocity * 28) / 127));

        for (frame = startFrame; frame <= endFrame; frame++) {
            int level = sustainLevel;
            if (frame == startFrame) {
                level = attackLevel;
            } else if (frame == endFrame) {
                level = releaseLevel;
            }
            mixSpectrumLevel(frames, frameCount, frame, bar, level);
            mixSpectrumLevel(frames, frameCount, frame, bar - 1, (level * 3) / 7);
            mixSpectrumLevel(frames, frameCount, frame, bar + 1, (level * 3) / 7);
            mixSpectrumLevel(frames, frameCount, frame, bar - 2, level / 5);
            mixSpectrumLevel(frames, frameCount, frame, bar + 2, level / 5);
        }
    }

    private static void mixSpectrumLevel(byte[] frames, int frameCount, int frameIndex, int barIndex, int level) {
        int offset;
        int current;
        int mixed;

        if (frames == null
                || frameIndex < 0
                || frameIndex >= frameCount
                || barIndex < 0
                || barIndex >= SPECTRUM_BAR_COUNT
                || level <= 0) {
            return;
        }

        offset = (frameIndex * SPECTRUM_BAR_COUNT) + barIndex;
        current = frames[offset] & 0xFF;
        mixed = current + level - ((current * level) / 120);
        frames[offset] = (byte) clamp(0, 100, mixed);
    }

    private static void smoothSpectrumFrames(byte[] frames, int frameCount) {
        int frame;
        int bar;

        if (frames == null) {
            return;
        }

        for (frame = 1; frame < frameCount; frame++) {
            for (bar = 0; bar < SPECTRUM_BAR_COUNT; bar++) {
                int offset = (frame * SPECTRUM_BAR_COUNT) + bar;
                int current = frames[offset] & 0xFF;
                int previous = frames[offset - SPECTRUM_BAR_COUNT] & 0xFF;
                if (current + 18 < previous) {
                    frames[offset] = (byte) clamp(0, 100, previous - 18);
                }
            }
        }
    }

    private static int spectrumBarForNote(PlaybackTimeline.CompiledNote note) {
        int noteValue;

        if (note == null) {
            return 0;
        }

        noteValue = note.midiNote;
        if (note.midiChannel == 9) {
            if (noteValue < 40) {
                noteValue = 30;
            } else if (noteValue < 55) {
                noteValue = 42;
            } else if (noteValue < 70) {
                noteValue = 56;
            } else {
                noteValue = 68;
            }
        }

        return clamp(0, SPECTRUM_BAR_COUNT - 1, ((noteValue - 24) * SPECTRUM_BAR_COUNT) / 84);
    }

    private static long midiTickToMillis(PlaybackTimeline timeline, long midiTick) {
        long micros = 0L;
        int i;

        if (timeline == null || timeline.tempoPoints == null || timeline.tempoPoints.size() == 0 || midiTick <= 0L) {
            return 0L;
        }

        for (i = 0; i < timeline.tempoPoints.size(); i++) {
            PlaybackTimeline.TempoPoint point = (PlaybackTimeline.TempoPoint) timeline.tempoPoints.elementAt(i);
            long segmentStart = maxLong(0L, point.midiTick);
            long segmentEnd = midiTick;

            if (segmentStart >= midiTick) {
                break;
            }
            if (i + 1 < timeline.tempoPoints.size()) {
                PlaybackTimeline.TempoPoint next = (PlaybackTimeline.TempoPoint) timeline.tempoPoints.elementAt(i + 1);
                segmentEnd = minLong(segmentEnd, next.midiTick);
            }
            if (segmentEnd > segmentStart) {
                micros += ((segmentEnd - segmentStart) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
            }
        }

        return maxLong(0L, micros / 1000L);
    }

    private static int triangleWave(int position, int period) {
        int normalized;
        int half;

        if (period <= 1) {
            return 100;
        }

        normalized = position % period;
        if (normalized < 0) {
            normalized += period;
        }
        half = period / 2;
        if (half <= 0) {
            return 100;
        }
        if (normalized <= half) {
            return (normalized * 100) / half;
        }
        return ((period - normalized) * 100) / max(1, period - half);
    }

    private static long minLong(long left, long right) {
        return left < right ? left : right;
    }

    private static long maxLong(long left, long right) {
        return left > right ? left : right;
    }

    private static int max(int left, int right) {
        return left > right ? left : right;
    }

    private static int clamp(int min, int max, int value) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static final class PlaylistEntry {
        final String url;
        final String fileName;
        final String displayTitle;

        PlaylistEntry(String url, String fileName, String displayTitle) {
            this.url = url;
            this.fileName = fileName;
            this.displayTitle = safeText(displayTitle, fileName);
        }
    }

    private static final class LoadedTrack {
        final String sourceUrl;
        final String fileName;
        final String title;
        final String copyright;
        final byte[] initialMidi;
        final byte[] loopMidi;
        final boolean hasLoop;
        final long estimatedFiniteDurationMillis;
        final long estimatedLoopStartMillis;
        final long estimatedLoopBodyDurationMillis;
        final SpectrumModel spectrumModel;

        LoadedTrack(
                String sourceUrl,
                String fileName,
                String title,
                String copyright,
                byte[] initialMidi,
                byte[] loopMidi,
                boolean hasLoop,
                long estimatedFiniteDurationMillis,
                long estimatedLoopStartMillis,
                long estimatedLoopBodyDurationMillis,
                SpectrumModel spectrumModel) {
            this.sourceUrl = sourceUrl;
            this.fileName = fileName;
            this.title = title;
            this.copyright = copyright;
            this.initialMidi = initialMidi;
            this.loopMidi = loopMidi;
            this.hasLoop = hasLoop;
            this.estimatedFiniteDurationMillis = estimatedFiniteDurationMillis;
            this.estimatedLoopStartMillis = estimatedLoopStartMillis;
            this.estimatedLoopBodyDurationMillis = estimatedLoopBodyDurationMillis;
            this.spectrumModel = spectrumModel == null ? SpectrumModel.empty() : spectrumModel;
        }
    }

    private static final class SpectrumModel {
        private static final SpectrumModel EMPTY = new SpectrumModel(new byte[SPECTRUM_BAR_COUNT], 1);

        final byte[] frames;
        final int frameCount;

        SpectrumModel(byte[] frames, int frameCount) {
            this.frames = frames == null ? new byte[SPECTRUM_BAR_COUNT] : frames;
            this.frameCount = max(1, frameCount);
        }

        static SpectrumModel empty() {
            return EMPTY;
        }

        int[] snapshot(long positionMillis, boolean playing) {
            int[] bars = new int[SPECTRUM_BAR_COUNT];
            int frameIndex;
            int offset;
            int i;

            if (frames == null || frames.length == 0) {
                return bars;
            }

            frameIndex = clamp(0, frameCount - 1, (int) (maxLong(0L, positionMillis) / SPECTRUM_FRAME_INTERVAL_MS));
            offset = frameIndex * SPECTRUM_BAR_COUNT;
            for (i = 0; i < bars.length; i++) {
                int value = frames[offset + i] & 0xFF;
                bars[i] = playing ? value : clamp(4, 28, 4 + (value / 4));
            }
            return bars;
        }
    }
}
