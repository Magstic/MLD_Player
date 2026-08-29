package main;

import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.swing.SwingWorker;

import export.ExportService;
import playback.MasterVolume;
import playback.MidiOutput;
import playback.MidiOutputCatalog;
import playback.PlaybackContent;
import playback.PlaybackMonitor;
import playback.PlaybackSession;

/** Swing application controller; owns playlist/load/export/playback orchestration. */
final class SwingPlayerController implements SwingPlayerView.Listener {
    private static final boolean DEBUG_LOG = Boolean.getBoolean("mld.gui.debug");

    private final String[] startupArgs;
    private final MldApplicationWorkflow workflow = new MldApplicationWorkflow();
    private final EmbeddedMldScanner embeddedMldScanner = new EmbeddedMldScanner();
    private final PlaylistState playlist = new PlaylistState();
    private final MasterVolume masterVolume = new MasterVolume(127);
    private final Map<PlaylistState.Entry, SwingWorker<ApplicationTrack, Void>> inFlightLoads =
            new HashMap<PlaylistState.Entry, SwingWorker<ApplicationTrack, Void>>();
    private final SwingPlayerView view;
    private volatile MidiOutput midiOutput;
    private volatile boolean loading;
    private volatile boolean discoveringInputs;
    private volatile boolean sessionActive;
    private volatile boolean paused;
    private volatile boolean stopRequested;
    private volatile ApplicationTrack currentTrack;
    private volatile PlaylistState.Entry currentEntry;
    private volatile PlaylistState.Entry pendingPlaybackEntry;
    private PlaylistState.Entry foregroundLoadEntry;
    private boolean foregroundAutoPlay;
    private Path embeddedTempDirectory;
    private int embeddedTempSequence;
    private String playlistSearchQuery = "";

    SwingPlayerController(String[] startupArgs) {
        this.startupArgs = startupArgs == null ? new String[0] : startupArgs.clone();
        List<MidiOutput> outputs = MidiOutputCatalog.availableOutputs();
        midiOutput = outputs.get(0);
        view = new SwingPlayerView(this, masterVolume.getValue(), outputs, midiOutput);
        view.setLoopMode(playlist.loopMode());
    }

    void show() {
        view.show();
        if (startupArgs.length > 0 && startupArgs[0] != null && !startupArgs[0].trim().isEmpty()) {
            scanAndAddInputs(
                    Collections.singletonList(Paths.get(startupArgs[0]).toAbsolutePath().normalize()),
                    true);
        }
    }

    @Override
    public void onPlayRequested() {
        if (loading) {
            return;
        }
        if (sessionActive) {
            paused = !paused;
            view.setPaused(paused);
            return;
        }
        PlaylistState.Entry selectedEntry = view.selectedPlaylistEntry();
        if (selectedEntry != null && (currentEntry == null || selectedEntry != currentEntry)) {
            openPlaylistEntry(selectedEntry, true);
            return;
        }
        if (currentTrack == null || !currentTrack.isPlayable()) {
            if (!discoveringInputs) {
                chooseAndOpenTracks();
            }
        } else {
            startPlayback(currentTrack);
        }
    }

    @Override
    public void onLoopModeToggleRequested() {
        view.setLoopMode(playlist.toggleLoopMode());
        restartCurrentPlayback();
    }

    @Override
    public void onExportRequested() {
        exportPlaylist();
    }

    @Override
    public void onMidiOutputSelected(MidiOutput output) {
        if (output == null || output == midiOutput) {
            return;
        }
        midiOutput = output;
        restartCurrentPlayback();
    }

    @Override
    public void onMasterVolumeChanged(int value) {
        masterVolume.setValue(value);
    }

    @Override
    public void onPlaylistEntryActivated(PlaylistState.Entry entry) {
        if (entry != null) {
            view.selectPlaylistEntry(entry);
            openPlaylistEntry(entry, true);
        }
    }

    @Override
    public void onPlaylistSearchChanged(String query) {
        playlistSearchQuery = query == null ? "" : query.trim();
        refreshPlaylistView();
    }

    @Override
    public void onFilesDropped(List<Path> files) {
        scanAndAddInputs(files, false);
    }

    @Override
    public void onWindowClosing() {
        stopRequested = true;
    }

    private void restartCurrentPlayback() {
        if (sessionActive && currentEntry != null) {
            pendingPlaybackEntry = currentEntry;
            paused = false;
            view.setPaused(false);
            stopRequested = true;
        }
    }

    private void openPlaylistEntry(PlaylistState.Entry entry, boolean autoPlayAfterLoad) {
        if (entry == null) {
            return;
        }
        view.selectPlaylistEntry(entry);
        if (sessionActive) {
            if (entry == currentEntry) {
                return;
            }
            pendingPlaybackEntry = entry;
            paused = false;
            view.setPaused(false);
            stopRequested = true;
            return;
        }
        loadTrack(entry.inputPath, autoPlayAfterLoad);
    }

    private PlaylistState.Entry ensurePlaylistEntry(Path inputPath) {
        if (inputPath == null) {
            return null;
        }
        Path normalized = inputPath.toAbsolutePath().normalize();
        PlaylistState.Entry existing = playlist.find(normalized);
        if (existing != null) {
            return existing;
        }
        PlaylistState.Entry entry = playlist.ensure(normalized);
        refreshPlaylistView();
        requestLoad(entry);
        if (playlist.size() == 1) {
            view.selectPlaylistEntry(entry);
        }
        return entry;
    }

    private void chooseAndOpenTracks() {
        List<Path> selected = view.chooseOpenPaths();
        if (!selected.isEmpty()) {
            scanAndAddInputs(selected, true);
        }
    }

    private void scanAndAddInputs(final List<Path> selected, final boolean autoPlayFirst) {
        if (selected == null || selected.isEmpty() || discoveringInputs) {
            return;
        }
        discoveringInputs = true;
        SwingWorker<List<Path>, Void> worker = new SwingWorker<List<Path>, Void>() {
            @Override
            protected List<Path> doInBackground() throws Exception {
                return discoverInputs(selected);
            }

            @Override
            protected void done() {
                discoveringInputs = false;
                try {
                    List<Path> files = get();
                    if (files.isEmpty()) {
                        if (autoPlayFirst) {
                            view.showInformation("No MLD / MFi data were found.", "Nothing to open");
                        }
                        return;
                    }
                    addFilesToPlaylist(files, autoPlayFirst);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    debugException("Input discovery failed", cause);
                    if (autoPlayFirst) {
                        view.showError(
                                cause.getMessage() == null ? "Unable to scan the selected input." : cause.getMessage(),
                                "Open failed");
                    }
                }
            }
        };
        worker.execute();
    }

    private List<Path> discoverInputs(List<Path> selected) throws IOException {
        List<Path> resolved = new ArrayList<Path>();
        for (Path input : selected) {
            if (input == null) {
                continue;
            }
            Path normalized = input.toAbsolutePath().normalize();
            if (Files.isDirectory(normalized)) {
                List<Path> children = new ArrayList<Path>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(normalized)) {
                    for (Path child : stream) {
                        if (Files.isRegularFile(child)) {
                            children.add(child.toAbsolutePath().normalize());
                        }
                    }
                }
                Collections.sort(children);
                for (Path child : children) {
                    discoverFile(child, resolved);
                }
            } else if (Files.isRegularFile(normalized)) {
                discoverFile(normalized, resolved);
            }
        }
        return resolved;
    }

    private void discoverFile(Path input, List<Path> resolved) throws IOException {
        if (PlaylistState.accepts(input)) {
            resolved.add(input);
            return;
        }
        final List<EmbeddedMldScanner.FoundMld> found;
        try {
            found = embeddedMldScanner.scan(input);
        } catch (IOException e) {
            debugException("Skipping unscannable container: " + input, e);
            return;
        }
        for (EmbeddedMldScanner.FoundMld mld : found) {
            resolved.add(materializeEmbeddedMld(mld));
        }
    }

    private Path materializeEmbeddedMld(EmbeddedMldScanner.FoundMld found) throws IOException {
        if (embeddedTempDirectory == null) {
            embeddedTempDirectory = Files.createTempDirectory("mld-player-embedded-");
            embeddedTempDirectory.toFile().deleteOnExit();
        }
        String safeOrigin = found.origin.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (safeOrigin.length() > 72) {
            safeOrigin = safeOrigin.substring(safeOrigin.length() - 72);
        }
        String name = String.format("%04d-%s.mld", ++embeddedTempSequence, safeOrigin);
        Path output = embeddedTempDirectory.resolve(name);
        Files.write(output, found.copyData());
        output.toFile().deleteOnExit();
        return output;
    }

    private void addFilesToPlaylist(List<Path> files, boolean autoPlayFirst) {
        if (files == null || files.isEmpty()) {
            return;
        }
        PlaylistState.Entry firstEntry = null;
        for (Path file : files) {
            if (!PlaylistState.accepts(file)) {
                continue;
            }
            PlaylistState.Entry existing = playlist.find(file);
            PlaylistState.Entry entry = existing != null ? existing : ensurePlaylistEntry(file);
            if (firstEntry == null) {
                firstEntry = entry;
            }
        }
        if (firstEntry == null) {
            return;
        }
        view.selectPlaylistEntry(firstEntry);
        if (autoPlayFirst) {
            openPlaylistEntry(firstEntry, true);
        }
    }

    private void exportPlaylist() {
        if (playlist.isEmpty()) {
            view.showInformation("Add MLD / MFi files to the playlist first.", "Nothing to export");
            return;
        }
        final SwingPlayerView.ExportSelection selection = view.chooseExportSelection();
        if (selection == null) {
            return;
        }
        final List<Path> inputPaths = playlist.inputPaths();
        view.setExporting(true, inputPaths.size());
        SwingWorker<ExportService.BatchArtifacts, Void> worker =
                new SwingWorker<ExportService.BatchArtifacts, Void>() {
            @Override
            protected ExportService.BatchArtifacts doInBackground() throws Exception {
                return new ExportService().exportBatch(inputPaths, selection.directory, selection.mode);
            }

            @Override
            protected void done() {
                view.setExporting(false, inputPaths.size());
                try {
                    ExportService.BatchArtifacts result = get();
                    String message = "Exported " + result.exportedCount + " of " + inputPaths.size()
                            + " tracks to:\n" + result.outputDir;
                    if (!result.failures.isEmpty()) {
                        message += "\n\nFailed:\n" + String.join("\n", result.failures);
                    }
                    view.showExportResult(message, !result.failures.isEmpty());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    debugException("Export interrupted", e);
                    view.showError("Export interrupted.", "Export failed");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = cause.getMessage();
                    debugException("Export failed", e);
                    view.showError(
                            message == null || message.trim().isEmpty()
                                    ? cause.getClass().getSimpleName()
                                    : message,
                            "Export failed");
                }
            }
        };
        worker.execute();
    }

    private void requestLoad(final PlaylistState.Entry entry) {
        if (entry == null || entry.loadedTrack != null || inFlightLoads.containsKey(entry)) {
            return;
        }
        entry.metadataLoading = true;
        entry.failed = false;
        view.repaintPlaylist();
        SwingWorker<ApplicationTrack, Void> worker = new SwingWorker<ApplicationTrack, Void>() {
            @Override
            protected ApplicationTrack doInBackground() throws Exception {
                return workflow.load(entry.inputPath);
            }

            @Override
            protected void done() {
                inFlightLoads.remove(entry);
                entry.metadataLoading = false;
                ApplicationTrack track = null;
                Exception failure = null;
                try {
                    track = get();
                    applyLoadedTrackToEntry(entry, track);
                } catch (Exception e) {
                    failure = e;
                    entry.failed = true;
                    entry.durationText = "--:--";
                }
                if (entry == foregroundLoadEntry) {
                    boolean autoPlay = foregroundAutoPlay;
                    foregroundLoadEntry = null;
                    foregroundAutoPlay = false;
                    loading = false;
                    view.setLoading(false);
                    if (track != null) {
                        currentTrack = track;
                        applyTrack(track);
                        if (autoPlay) {
                            startPlayback(track);
                        }
                    } else {
                        debugException("Load failed", failure);
                        showLoadFailure(entry.inputPath);
                    }
                }
                view.repaintPlaylist();
            }
        };
        inFlightLoads.put(entry, worker);
        worker.execute();
    }

    private void applyLoadedTrackToEntry(PlaylistState.Entry entry, ApplicationTrack track) {
        if (entry == null || track == null) {
            return;
        }
        entry.loadedTrack = track;
        entry.displayTitle = track.displayTitle();
        entry.durationText = formatDuration(track.durationMillis);
        entry.failed = false;
        refreshPlaylistView();
    }

    private void loadTrack(final Path inputPath, final boolean autoPlayAfterLoad) {
        if (inputPath == null) {
            return;
        }
        final PlaylistState.Entry entry = ensurePlaylistEntry(inputPath);
        if (entry == null) {
            return;
        }
        view.selectPlaylistEntry(entry);
        foregroundLoadEntry = entry;
        foregroundAutoPlay = autoPlayAfterLoad;
        if (entry.loadedTrack != null) {
            foregroundLoadEntry = null;
            foregroundAutoPlay = false;
            loading = false;
            view.setLoading(false);
            currentTrack = entry.loadedTrack;
            applyTrack(entry.loadedTrack);
            if (autoPlayAfterLoad) {
                startPlayback(entry.loadedTrack);
            }
            return;
        }
        loading = true;
        entry.metadataLoading = true;
        view.setLoading(true);
        view.repaintPlaylist();
        requestLoad(entry);
    }

    private void applyTrack(ApplicationTrack track) {
        if (track == null) {
            return;
        }
        PlaylistState.Entry entry = playlist.find(track.inputPath);
        if (entry != null) {
            currentEntry = entry;
            view.setActiveEntry(entry);
            refreshPlaylistView();
            view.selectPlaylistEntry(entry);
        }
        view.showTrack(track);
        view.repaintPlaylist();
    }

    private void showLoadFailure(Path inputPath) {
        currentTrack = null;
        PlaylistState.Entry entry = playlist.find(inputPath);
        if (entry != null) {
            entry.failed = true;
            entry.durationText = "--:--";
        }
        refreshPlaylistView();
        view.showLoadFailure();
        view.repaintPlaylist();
    }

    private void startPlayback(final ApplicationTrack track) {
        if (track == null || loading || sessionActive || !track.isPlayable()) {
            return;
        }
        debugLog("Starting playback for: " + track.displayTitle());
        sessionActive = true;
        paused = false;
        stopRequested = false;
        view.setPlaybackStarted();
        view.repaintPlaylist();

        Thread worker = new Thread(() -> {
            debugLog("Playback thread started");
            boolean completed = false;
            String failureMessage = null;
            try {
                PlaybackMonitor monitor = new PlaybackMonitor() {
                    @Override
                    public void onPlaybackPrepared(Descriptor descriptor) {
                    }

                    @Override
                    public void onPlaybackProgress(final Progress progress) {
                        EventQueue.invokeLater(() -> view.setPlaybackProgress(progress.fraction));
                    }

                    @Override
                    public boolean isStopRequested() {
                        return stopRequested;
                    }

                    @Override
                    public boolean isPauseRequested() {
                        return paused;
                    }
                };
                int loopCount = playlist.loopMode() == PlaylistState.LoopMode.PLAYLIST ? 0 : -1;
                PlaybackContent content = workflow.preparePlayback(track);
                completed = new PlaybackSession().play(
                        content,
                        loopCount,
                        monitor,
                        midiOutput,
                        masterVolume);
            } catch (Exception e) {
                failureMessage = e.getMessage();
                if (failureMessage == null || failureMessage.trim().isEmpty()) {
                    failureMessage = e.getClass().getSimpleName();
                }
                debugException("Playback failed: " + failureMessage, e);
            } finally {
                final boolean completedNaturally = completed;
                final String finalFailureMessage = failureMessage;
                EventQueue.invokeLater(() -> onPlaybackFinished(completedNaturally, finalFailureMessage));
            }
        }, "mld-player-swing-playback");
        worker.setDaemon(true);
        worker.start();
    }

    private void onPlaybackFinished(boolean completedNaturally, String failureMessage) {
        sessionActive = false;
        paused = false;
        stopRequested = false;
        view.setPlaybackStopped();
        PlaylistState.Entry queuedEntry = pendingPlaybackEntry;
        pendingPlaybackEntry = null;
        if (failureMessage != null) {
            view.setProgressValue(0);
            view.showPlaybackFailure();
            if (queuedEntry != null) {
                openPlaylistEntry(queuedEntry, true);
            }
            return;
        }
        if (queuedEntry != null) {
            view.setProgressValue(0);
            openPlaylistEntry(queuedEntry, true);
            return;
        }
        if (completedNaturally
                && playlist.loopMode() == PlaylistState.LoopMode.PLAYLIST
                && !playlist.isEmpty()) {
            view.setProgressValue(0);
            playNextPlaylistTrack();
            return;
        }
        view.setProgressValue(completedNaturally ? 1000 : 0);
        view.repaintPlaylist();
    }

    private void playNextPlaylistTrack() {
        int nextIndex = playlist.nextIndex(currentEntry, playlist.indexOf(view.selectedPlaylistEntry()));
        PlaylistState.Entry entry = playlist.get(nextIndex);
        if (entry != null) {
            view.selectPlaylistEntry(entry);
            openPlaylistEntry(entry, true);
        }
    }


    private void refreshPlaylistView() {
        List<PlaylistState.Entry> visible = new ArrayList<PlaylistState.Entry>();
        List<PlaylistState.Entry> allEntries = playlist.entries();
        for (PlaylistState.Entry entry : allEntries) {
            if (matchesPlaylistFilter(entry, playlistSearchQuery)) {
                visible.add(entry);
            }
        }
        PlaylistState.Entry selected = view.selectedPlaylistEntry();
        view.setPlaylistEntries(visible);
        if (selected != null && visible.contains(selected)) {
            view.selectPlaylistEntry(selected);
        } else if (currentEntry != null && visible.contains(currentEntry)) {
            view.selectPlaylistEntry(currentEntry);
        } else if (!visible.isEmpty() && allEntries.size() == 1) {
            view.selectPlaylistEntry(visible.get(0));
        } else {
            view.selectPlaylistEntry(null);
        }
        view.setActiveEntry(currentEntry);
        view.updatePlaylistChrome(visible.size(), allEntries.size());
        view.repaintPlaylist();
    }

    static boolean matchesPlaylistFilter(PlaylistState.Entry entry, String query) {
        if (entry == null) {
            return false;
        }
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        StringBuilder haystack = new StringBuilder();
        if (entry.displayTitle != null) {
            haystack.append(entry.displayTitle).append(' ');
        }
        if (entry.inputPath != null && entry.inputPath.getFileName() != null) {
            haystack.append(entry.inputPath.getFileName().toString()).append(' ');
        }
        if (entry.loadedTrack != null) {
            haystack.append(entry.loadedTrack.displayCopyright()).append(' ');
        }
        return haystack.toString().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String formatDuration(long durationMillis) {
        int rounded = Math.max(0, (int) Math.round(durationMillis / 1000.0));
        int hours = rounded / 3600;
        int minutes = (rounded % 3600) / 60;
        int seconds = rounded % 60;
        if (hours > 0) {
            return String.format(
                    "%d:%02d:%02d",
                    Integer.valueOf(hours), Integer.valueOf(minutes), Integer.valueOf(seconds));
        }
        return String.format("%02d:%02d", Integer.valueOf(minutes), Integer.valueOf(seconds));
    }

    private static void debugLog(String message) {
        if (DEBUG_LOG) {
            System.err.println("[MLD Player GUI] " + message);
        }
    }

    private static void debugException(String message, Throwable error) {
        if (!DEBUG_LOG) {
            return;
        }
        System.err.println("[MLD Player GUI] " + message);
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }
}
