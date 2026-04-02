package main;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import playback.JavaMidiPlayer;
import playback.PlaybackMonitor;
import playback.PlaybackSequenceBuilder;
import timeline.PlaybackTimeline;

public final class SwingPlayer {
    private static final boolean DEBUG_LOG = Boolean.getBoolean("mld.gui.debug");
    private static final Color BACKGROUND_COLOR = new Color(245, 245, 245);
    private static final Color DIVIDER_COLOR = new Color(200, 200, 200);
    private static final Color TITLE_COLOR = new Color(60, 60, 60);
    private static final Color COPYRIGHT_COLOR = new Color(100, 100, 100);
    private static final Color PLAYLIST_SURFACE_COLOR = new Color(252, 252, 252);
    private static final Color PLAYLIST_HEADER_COLOR = new Color(88, 88, 88);
    private static final Color PLAYLIST_HINT_COLOR = new Color(140, 140, 140);
    private static final Color PLAYLIST_ROW_COLOR = new Color(250, 250, 250);
    private static final Color PLAYLIST_ROW_SELECTED_COLOR = new Color(238, 238, 238);
    private static final Color PLAYLIST_ROW_ACTIVE_COLOR = new Color(228, 232, 236);
    private static final Color PLAYLIST_BORDER_COLOR = new Color(220, 220, 220);
    private static final Color PLAYLIST_SUBTEXT_COLOR = new Color(132, 132, 132);
    private static final Color PROGRESS_BG_COLOR = new Color(220, 220, 220);
    private static final Color PROGRESS_FG_COLOR = new Color(100, 100, 100);
    private static final Color BUTTON_BG_COLOR = new Color(230, 230, 230);
    private static final Color BUTTON_FG_COLOR = new Color(80, 80, 80);
    private static final Color BUTTON_HOVER_COLOR = new Color(210, 210, 210);

    private static final int WINDOW_WIDTH = 600;
    private static final int WINDOW_HEIGHT = 200;
    private static final int PLAYLIST_WINDOW_HEIGHT = 400;
    private static final int LEFT_PANEL_WIDTH = 100;
    private static final int BUTTON_SIZE = 64;

    private static final String[] FONT_FALLBACKS = {
        "Microsoft YaHei",
        "SimHei",
        "Noto Sans CJK SC",
        "WenQuanYi Micro Hei",
        "Arial Unicode MS",
        "SansSerif"
    };

    private final String[] startupArgs;
    private JFrame frame;
    private JFrame playlistFrame;
    private PlayButton playButton;
    private JLabel titleLabel;
    private JLabel copyrightLabel;
    private ProgressBar progressBar;
    private JLabel playlistHintLabel;
    private JButton playlistLoopButton;
    private JList<PlaylistEntry> playlistList;
    private DefaultListModel<PlaylistEntry> playlistModel;
    private volatile boolean loading;
    private volatile boolean sessionActive;
    private volatile boolean paused;
    private volatile boolean stopRequested;
    private volatile CurrentTrack currentTrack;
    private volatile PlaylistEntry currentEntry;
    private volatile PlaylistEntry pendingPlaybackEntry;
    private volatile LoopMode loopMode = LoopMode.SINGLE_TRACK;
    private final List<PlaylistEntry> playlistEntries = new ArrayList<PlaylistEntry>();

    public static void main(String[] args) {
        final String[] launchArgs = args == null ? new String[0] : args.clone();
        installLookAndFeel();
        EventQueue.invokeLater(() -> new SwingPlayer(launchArgs).show());
    }

    SwingPlayer(String[] startupArgs) {
        this.startupArgs = startupArgs == null ? new String[0] : startupArgs.clone();
        buildUi();
    }

    private void buildUi() {
        frame = new JFrame("MLD Player");
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setResizable(false);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopRequested = true;
                if (playlistFrame != null) {
                    playlistFrame.dispose();
                }
            }
        });
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent event) {
                syncPlaylistWindow(true);
            }

            @Override
            public void componentShown(ComponentEvent event) {
                syncPlaylistWindow(true);
            }
        });

        JPanel content = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Draw divider line manually
                g.setColor(DIVIDER_COLOR);
                g.fillRect(116, 16, 1, getHeight() - 32);
            }
        };
        content.setBackground(BACKGROUND_COLOR);
        content.setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));

        // Manually position components
        int padding = 16;
        int rightPanelX = 116 + 1 + 16; // divider x + divider width + padding
        int rightPanelWidth = WINDOW_WIDTH - rightPanelX - padding;

        // Play Button
        playButton = new PlayButton(BUTTON_SIZE);
        playButton.setBounds((116 - BUTTON_SIZE) / 2, (WINDOW_HEIGHT - BUTTON_SIZE) / 2 - padding, BUTTON_SIZE, BUTTON_SIZE);
        playButton.addActionListener(e -> handlePlayButton());
        content.add(playButton);

        // Title Label
        titleLabel = new JLabel("No Track Loaded");
        titleLabel.setForeground(TITLE_COLOR);
        titleLabel.setFont(getTitleFont());
        titleLabel.setBounds(rightPanelX, 28, rightPanelWidth, 40);
        content.add(titleLabel);

        // Progress Bar
        progressBar = new ProgressBar();
        progressBar.setMaximum(1000);
        progressBar.setValue(0);
        progressBar.setBounds(rightPanelX, 80, rightPanelWidth - 20, 12);
        content.add(progressBar);

        // Copyright Label
        copyrightLabel = new JLabel("Tap Play to load MLD (MFi)");
        copyrightLabel.setForeground(COPYRIGHT_COLOR);
        copyrightLabel.setFont(getCopyrightFont());
        copyrightLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        copyrightLabel.setBounds(rightPanelX, WINDOW_HEIGHT - 70, rightPanelWidth - 20, 22);
        content.add(copyrightLabel);

        applyFrameContentSize(frame, content, new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT), null);
        frame.setLocationRelativeTo(null);
        buildPlaylistWindow();
    }

    private void buildPlaylistWindow() {
        playlistFrame = new JFrame("Playlist");
        playlistFrame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        playlistFrame.setAutoRequestFocus(false);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(BACKGROUND_COLOR);
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        root.setPreferredSize(new Dimension(WINDOW_WIDTH, PLAYLIST_WINDOW_HEIGHT));
        root.setMinimumSize(new Dimension(420, 220));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel headerTitle = new JLabel("Playlist");
        headerTitle.setForeground(TITLE_COLOR);
        headerTitle.setFont(findFont(Font.PLAIN, 18));

        JPanel headerLeft = new JPanel();
        headerLeft.setOpaque(false);
        headerLeft.setLayout(new BoxLayout(headerLeft, BoxLayout.X_AXIS));
        headerLeft.add(headerTitle);
        headerLeft.add(Box.createHorizontalStrut(10));

        playlistLoopButton = new JButton();
        playlistLoopButton.setFont(findFont(Font.PLAIN, 12));
        playlistLoopButton.setFocusPainted(false);
        playlistLoopButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(PLAYLIST_BORDER_COLOR),
                new EmptyBorder(4, 10, 4, 10)));
        playlistLoopButton.setBackground(PLAYLIST_SURFACE_COLOR);
        playlistLoopButton.setForeground(PLAYLIST_HEADER_COLOR);
        playlistLoopButton.addActionListener(e -> toggleLoopMode());
        updateLoopModeButton();
        headerLeft.add(playlistLoopButton);

        header.add(headerLeft, BorderLayout.WEST);

        playlistHintLabel = new JLabel();
        playlistHintLabel.setForeground(PLAYLIST_HINT_COLOR);
        playlistHintLabel.setFont(findFont(Font.PLAIN, 12));
        playlistHintLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(playlistHintLabel, BorderLayout.EAST);

        playlistModel = new DefaultListModel<PlaylistEntry>();
        playlistList = new JList<PlaylistEntry>(playlistModel);
        playlistList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistList.setFixedCellHeight(56);
        playlistList.setBackground(PLAYLIST_SURFACE_COLOR);
        playlistList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        playlistList.setCellRenderer(new PlaylistCellRenderer());
        playlistList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() >= 2) {
                    int index = playlistList.locationToIndex(event.getPoint());
                    if (index >= 0
                            && index < playlistEntries.size()
                            && playlistList.getCellBounds(index, index) != null
                            && playlistList.getCellBounds(index, index).contains(event.getPoint())) {
                        selectPlaylistEntry(index);
                        openPlaylistEntry(playlistEntries.get(index), true);
                    }
                }
            }
        });

        TransferHandler transferHandler = new PlaylistTransferHandler();
        playlistList.setTransferHandler(transferHandler);

        JScrollPane scrollPane = new JScrollPane(playlistList);
        scrollPane.setBorder(BorderFactory.createLineBorder(PLAYLIST_BORDER_COLOR));
        scrollPane.getViewport().setBackground(PLAYLIST_SURFACE_COLOR);
        scrollPane.setTransferHandler(transferHandler);

        root.setTransferHandler(transferHandler);
        root.add(header, BorderLayout.NORTH);
        root.add(scrollPane, BorderLayout.CENTER);

        applyFrameContentSize(
                playlistFrame,
                root,
                new Dimension(WINDOW_WIDTH, PLAYLIST_WINDOW_HEIGHT),
                new Dimension(420, 248));
        updatePlaylistHint();
        positionPlaylistWindow();
    }

    private void applyFrameContentSize(
            JFrame targetFrame,
            JPanel content,
            Dimension preferredContentSize,
            Dimension minimumContentSize) {
        if (targetFrame == null || content == null) {
            return;
        }
        if (preferredContentSize != null) {
            content.setPreferredSize(preferredContentSize);
        }
        if (minimumContentSize != null) {
            content.setMinimumSize(minimumContentSize);
        }
        targetFrame.setContentPane(content);
        targetFrame.pack();
        if (minimumContentSize != null) {
            Insets insets = targetFrame.getInsets();
            targetFrame.setMinimumSize(new Dimension(
                    minimumContentSize.width + insets.left + insets.right,
                    minimumContentSize.height + insets.top + insets.bottom));
        }
    }

    private void positionPlaylistWindow() {
        if (frame == null || playlistFrame == null) {
            return;
        }
        playlistFrame.setLocation(frame.getX(), frame.getY() + frame.getHeight());
    }

    private void syncPlaylistWindow(boolean reveal) {
        if (frame == null || playlistFrame == null) {
            return;
        }
        positionPlaylistWindow();
        if (!reveal || !frame.isVisible()) {
            return;
        }
        if (!playlistFrame.isVisible()) {
            playlistFrame.setVisible(true);
        }
        int state = playlistFrame.getExtendedState();
        if ((state & Frame.ICONIFIED) != 0) {
            playlistFrame.setExtendedState(state & ~Frame.ICONIFIED);
        }
        playlistFrame.toFront();
        playlistFrame.repaint();
    }

    private void updatePlaylistHint() {
        if (playlistHintLabel == null) {
            return;
        }
        playlistHintLabel.setText(playlistEntries.isEmpty()
                ? "Drag MLD / MFi files here"
                : "Double-click a track to play");
    }

    private void updateLoopModeButton() {
        if (playlistLoopButton == null) {
            return;
        }
        playlistLoopButton.setText(loopMode.buttonText());
    }

    private void toggleLoopMode() {
        loopMode = loopMode.next();
        updateLoopModeButton();
        if (sessionActive && currentEntry != null) {
            pendingPlaybackEntry = currentEntry;
            paused = false;
            playButton.setPaused(false);
            stopRequested = true;
        }
    }

    private Font getTitleFont() {
        return findFont(Font.PLAIN, 28);
    }

    private Font getCopyrightFont() {
        return findFont(Font.PLAIN, 16);
    }

    private Font findFont(int style, int size) {
        for (String name : FONT_FALLBACKS) {
            Font font = new Font(name, style, size);
            if (font.getFamily().equals(name) || !font.getFamily().equals("Dialog")) {
                return font;
            }
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private void show() {
        frame.setVisible(true);
        syncPlaylistWindow(true);
        if (startupArgs.length > 0 && startupArgs[0] != null && !startupArgs[0].trim().isEmpty()) {
            openPath(Paths.get(startupArgs[0]).toAbsolutePath().normalize(), true);
        }
    }

    private void handlePlayButton() {
        if (loading) return;
        if (sessionActive) {
            paused = !paused;
            playButton.setPaused(paused);
            return;
        }
        PlaylistEntry selectedEntry = getSelectedPlaylistEntry();
        if (selectedEntry != null && (currentEntry == null || selectedEntry != currentEntry)) {
            openPlaylistEntry(selectedEntry, true);
            return;
        }
        if (currentTrack == null || !sessionActive) {
            if (currentTrack == null) {
                chooseAndLoadFile();
            } else if (!currentTrack.isPlayable()) {
                chooseAndLoadFile();
            } else {
                startPlayback(currentTrack);
            }
            return;
        }
    }

    private void chooseAndLoadFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open MLD");
        chooser.setFileFilter(new FileNameExtensionFilter("MLD / MFI Files", "mld", "mfi"));
        int result = chooser.showOpenDialog(frame);
        if (result != JFileChooser.APPROVE_OPTION) return;
        File selectedFile = chooser.getSelectedFile();
        if (selectedFile != null) {
            openPath(selectedFile.toPath().toAbsolutePath().normalize(), true);
        }
    }

    private PlaylistEntry getSelectedPlaylistEntry() {
        if (playlistList == null) {
            return null;
        }
        int index = playlistList.getSelectedIndex();
        if (index < 0 || index >= playlistEntries.size()) {
            return null;
        }
        return playlistEntries.get(index);
    }

    private void openPath(Path inputPath, boolean autoPlayAfterLoad) {
        if (!isAcceptedPlaylistPath(inputPath)) {
            return;
        }
        PlaylistEntry entry = ensurePlaylistEntry(inputPath);
        openPlaylistEntry(entry, autoPlayAfterLoad);
    }

    private void openPlaylistEntry(PlaylistEntry entry, boolean autoPlayAfterLoad) {
        if (entry == null) {
            return;
        }
        selectPlaylistEntry(indexOf(entry));
        if (sessionActive) {
            if (entry == currentEntry) {
                return;
            }
            pendingPlaybackEntry = entry;
            paused = false;
            playButton.setPaused(false);
            stopRequested = true;
            return;
        }
        loadTrack(entry.inputPath, autoPlayAfterLoad);
    }

    private PlaylistEntry ensurePlaylistEntry(Path inputPath) {
        if (inputPath == null) {
            return null;
        }
        Path normalized = inputPath.toAbsolutePath().normalize();
        PlaylistEntry existing = findPlaylistEntry(normalized);
        if (existing != null) {
            return existing;
        }

        PlaylistEntry entry = new PlaylistEntry(normalized);
        playlistEntries.add(entry);
        if (playlistModel != null) {
            playlistModel.addElement(entry);
        }
        updatePlaylistHint();
        loadPlaylistMetadata(entry);
        if (playlistEntries.size() == 1) {
            selectPlaylistEntry(0);
        }
        return entry;
    }

    private PlaylistEntry findPlaylistEntry(Path inputPath) {
        if (inputPath == null) {
            return null;
        }
        Path normalized = inputPath.toAbsolutePath().normalize();
        for (PlaylistEntry entry : playlistEntries) {
            if (entry.inputPath.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    private int indexOf(PlaylistEntry entry) {
        if (entry == null) {
            return -1;
        }
        for (int i = 0; i < playlistEntries.size(); i++) {
            if (playlistEntries.get(i) == entry) {
                return i;
            }
        }
        return -1;
    }

    private void selectPlaylistEntry(int index) {
        if (playlistList == null || index < 0 || index >= playlistEntries.size()) {
            return;
        }
        playlistList.setSelectedIndex(index);
        playlistList.ensureIndexIsVisible(index);
        playlistList.repaint();
    }

    private void addDroppedFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        int firstAddedIndex = -1;
        for (Path file : files) {
            if (!isAcceptedPlaylistPath(file)) {
                continue;
            }
            PlaylistEntry existing = findPlaylistEntry(file);
            PlaylistEntry entry = existing != null ? existing : ensurePlaylistEntry(file);
            if (firstAddedIndex < 0) {
                firstAddedIndex = indexOf(entry);
            }
        }
        if (firstAddedIndex >= 0) {
            selectPlaylistEntry(firstAddedIndex);
        }
        syncPlaylistWindow(true);
    }

    private void loadPlaylistMetadata(final PlaylistEntry entry) {
        if (entry == null || entry.metadataLoading || entry.loadedTrack != null) {
            return;
        }
        entry.metadataLoading = true;
        if (playlistList != null) {
            playlistList.repaint();
        }

        SwingWorker<CurrentTrack, Void> worker = new SwingWorker<CurrentTrack, Void>() {
            @Override
            protected CurrentTrack doInBackground() throws Exception {
                return buildTrack(entry.inputPath);
            }

            @Override
            protected void done() {
                entry.metadataLoading = false;
                try {
                    CurrentTrack track = get();
                    applyLoadedTrackToEntry(entry, track);
                } catch (Exception e) {
                    entry.failed = true;
                    entry.failureMessage = e.getMessage();
                    entry.durationText = "--:--";
                }
                if (playlistList != null) {
                    playlistList.repaint();
                }
            }
        };
        worker.execute();
    }

    private void applyLoadedTrackToEntry(PlaylistEntry entry, CurrentTrack track) {
        if (entry == null || track == null) {
            return;
        }
        entry.loadedTrack = track;
        entry.displayTitle = track.displayTitle();
        entry.durationText = formatDuration(track.durationMillis);
        entry.failed = false;
        entry.failureMessage = null;
        if (playlistList != null) {
            playlistList.repaint();
        }
    }

    private void loadTrack(final Path inputPath, final boolean autoPlayAfterLoad) {
        if (inputPath == null || loading) return;
        final PlaylistEntry entry = ensurePlaylistEntry(inputPath);
        if (entry == null) return;
        selectPlaylistEntry(indexOf(entry));
        if (entry.loadedTrack != null) {
            currentTrack = entry.loadedTrack;
            applyTrack(entry.loadedTrack);
            if (autoPlayAfterLoad) {
                startPlayback(entry.loadedTrack);
            }
            return;
        }
        loading = true;
        entry.metadataLoading = true;
        progressBar.setIndeterminate(true);
        titleLabel.setText("Loading...");
        playButton.setEnabled(false);
        if (playlistList != null) {
            playlistList.repaint();
        }

        SwingWorker<CurrentTrack, Void> worker = new SwingWorker<CurrentTrack, Void>() {
            @Override
            protected CurrentTrack doInBackground() throws Exception {
                return buildTrack(entry.inputPath);
            }

            @Override
            protected void done() {
                CurrentTrack track = null;
                try {
                    track = get();
                    currentTrack = track;
                    applyLoadedTrackToEntry(entry, track);
                    applyTrack(track);
                } catch (Exception e) {
                    debugException("Load failed", e);
                    showLoadFailure(entry.inputPath, e);
                } finally {
                    loading = false;
                    entry.metadataLoading = false;
                    progressBar.setIndeterminate(false);
                    playButton.setEnabled(true);
                    if (playlistList != null) {
                        playlistList.repaint();
                    }
                }
                if (track != null && autoPlayAfterLoad) {
                    startPlayback(track);
                }
            }
        };
        worker.execute();
    }

    private void applyTrack(CurrentTrack track) {
        if (track == null) return;
        PlaylistEntry entry = findPlaylistEntry(track.inputPath);
        if (entry != null) {
            currentEntry = entry;
            selectPlaylistEntry(indexOf(entry));
        }
        titleLabel.setText(track.displayTitle());
        copyrightLabel.setText(track.displayCopyright());
        progressBar.setValue(0);
        if (playlistList != null) {
            playlistList.repaint();
        }
    }

    private void showLoadFailure(Path inputPath, Exception failure) {
        currentTrack = null;
        PlaylistEntry entry = findPlaylistEntry(inputPath);
        if (entry != null) {
            entry.failed = true;
            entry.failureMessage = failure == null ? null : failure.getMessage();
            entry.durationText = "--:--";
        }
        titleLabel.setText("Failed to Load");
        copyrightLabel.setText("Tap Play to load MLD (MFi)");
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);
        if (playlistList != null) {
            playlistList.repaint();
        }
    }

    private void startPlayback(final CurrentTrack track) {
        if (track == null || loading || sessionActive || !track.isPlayable()) return;

        debugLog("Starting playback for: " + track.displayTitle());
        sessionActive = true;
        paused = false;
        stopRequested = false;
        progressBar.setValue(0);
        playButton.setPlaying(true);
        playButton.setPaused(false);
        if (playlistList != null) {
            playlistList.repaint();
        }

        Thread worker = new Thread(() -> {
            debugLog("Playback thread started");
            boolean completed = false;
            String failureMessage = null;
            try {
                PlaybackMonitor monitor = new PlaybackMonitor() {
                    @Override
                    public void onPlaybackPrepared(Descriptor descriptor) {}

                    @Override
                    public void onPlaybackProgress(final Progress progress) {
                        EventQueue.invokeLater(() -> {
                            progressBar.setValue((int) Math.round(progress.fraction * 1000.0));
                        });
                    }

                    @Override
                    public boolean isStopRequested() { return stopRequested; }

                    @Override
                    public boolean isPauseRequested() { return paused; }
                };

                int loopCount = resolveLoopCount(track);
                JavaMidiPlayer midiPlayer = new JavaMidiPlayer();
                PlaybackSequenceBuilder sequenceBuilder = new PlaybackSequenceBuilder();
                completed = midiPlayer.play(sequenceBuilder.build(track.timeline, loopCount), loopCount, monitor);
            } catch (Exception e) {
                failureMessage = e.getMessage();
                if (failureMessage == null || failureMessage.trim().isEmpty()) {
                    failureMessage = e.getClass().getSimpleName();
                }
                debugException("Playback failed: " + failureMessage, e);
            } finally {
                final boolean completedNaturally = completed;
                final String finalFailureMessage = failureMessage;
                EventQueue.invokeLater(() -> onPlaybackFinished(track, completedNaturally, finalFailureMessage));
            }
        }, "mld-player-swing-playback");
        worker.setDaemon(true);
        worker.start();
    }

    private void onPlaybackFinished(CurrentTrack track, boolean completedNaturally, String failureMessage) {
        sessionActive = false;
        paused = false;
        stopRequested = false;
        playButton.setPlaying(false);
        playButton.setPaused(false);
        PlaylistEntry queuedEntry = pendingPlaybackEntry;
        pendingPlaybackEntry = null;
        if (failureMessage != null) {
            progressBar.setValue(0);
            titleLabel.setText("Playback Failed");
            if (queuedEntry != null) {
                openPlaylistEntry(queuedEntry, true);
            }
            return;
        }
        if (queuedEntry != null) {
            progressBar.setValue(0);
            openPlaylistEntry(queuedEntry, true);
            return;
        }
        if (completedNaturally && loopMode == LoopMode.PLAYLIST && !playlistEntries.isEmpty()) {
            progressBar.setValue(0);
            playNextPlaylistTrack();
            return;
        }
        progressBar.setValue(completedNaturally ? 1000 : 0);
        if (playlistList != null) {
            playlistList.repaint();
        }
    }

    private void playNextPlaylistTrack() {
        if (playlistEntries.isEmpty()) {
            return;
        }
        int currentIndex = indexOf(currentEntry);
        if (currentIndex < 0 && playlistList != null) {
            currentIndex = playlistList.getSelectedIndex();
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        int nextIndex = playlistEntries.size() == 1 ? currentIndex : (currentIndex + 1) % playlistEntries.size();
        selectPlaylistEntry(nextIndex);
        openPlaylistEntry(playlistEntries.get(nextIndex), true);
    }

    private int resolveLoopCount(CurrentTrack track) {
        if (track == null) {
            return 0;
        }
        return loopMode == LoopMode.PLAYLIST ? 0 : -1;
    }

    private CurrentTrack buildTrack(Path inputPath) throws Exception {
        PlaybackTimeline timeline = Cli.buildTimeline(inputPath);
        PlaybackSequenceBuilder.BuiltSequence builtSequence = null;
        if (!timeline.notes.isEmpty()) {
            builtSequence = new PlaybackSequenceBuilder().build(timeline);
        }

        return new CurrentTrack(
                inputPath, timeline, builtSequence,
                cleanInfoText(timeline.file.lastInfoText("titl")),
                cleanInfoText(timeline.file.lastInfoText("copy")),
                estimateDurationMillis(timeline));
    }

    private static boolean isAcceptedPlaylistPath(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mld") || fileName.endsWith(".mfi");
    }

    private static List<Path> extractDroppedPaths(Transferable transferable) throws Exception {
        List<Path> paths = new ArrayList<Path>();
        if (transferable == null || !transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return paths;
        }
        @SuppressWarnings("unchecked")
        List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
        for (File file : files) {
            if (file != null) {
                paths.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private static String formatDuration(long durationMillis) {
        int rounded = Math.max(0, (int) Math.round(durationMillis / 1000.0));
        int hours = rounded / 3600;
        int minutes = (rounded % 3600) / 60;
        int seconds = rounded % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", Integer.valueOf(hours), Integer.valueOf(minutes), Integer.valueOf(seconds));
        }
        return String.format("%02d:%02d", Integer.valueOf(minutes), Integer.valueOf(seconds));
    }

    private static String cleanInfoText(String value) {
        if (value == null) return "";
        int nul = value.indexOf('\0');
        String cleaned = nul >= 0 ? value.substring(0, nul) : value;
        return cleaned.trim();
    }

    private static long estimateDurationMillis(PlaybackTimeline timeline) {
        long endTick = timeline.loopInfo.hasLoop
                ? Math.max(1L, timeline.loopInfo.loopEndMidiTick)
                : Math.max(1L, timeline.totalMidiTicks);
        double seconds = 0.0;
        long previousTick = 0L;
        int currentMpqn = timeline.tempoPoints.get(0).mpqn;

        for (PlaybackTimeline.TempoPoint point : timeline.tempoPoints) {
            if (point.midiTick <= 0L) {
                currentMpqn = point.mpqn;
                continue;
            }
            if (point.midiTick >= endTick) break;
            long deltaTicks = point.midiTick - previousTick;
            seconds += ticksToSeconds(deltaTicks, currentMpqn);
            previousTick = point.midiTick;
            currentMpqn = point.mpqn;
        }

        seconds += ticksToSeconds(endTick - previousTick, currentMpqn);
        return Math.max(0L, Math.round(seconds * 1000.0));
    }

    private static double ticksToSeconds(long deltaTicks, int mpqn) {
        return ((double) deltaTicks / (double) PlaybackTimeline.MIDI_PPQ) * ((double) mpqn / 1000000.0);
    }

    private static void installLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
    }

    private static void debugLog(String message) {
        if (!DEBUG_LOG) {
            return;
        }
        System.err.println("[MLD Player GUI] " + message);
    }

    private static void debugException(String message, Exception error) {
        if (!DEBUG_LOG) {
            return;
        }
        System.err.println("[MLD Player GUI] " + message);
        if (error != null) {
            error.printStackTrace(System.err);
        }
    }

    private static class PlayButton extends JButton {
        private boolean isPlaying = false;
        private boolean isPaused = false;
        private int size;

        PlayButton(int size) {
            this.size = size;
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        void setPlaying(boolean playing) {
            this.isPlaying = playing;
            repaint();
        }

        void setPaused(boolean paused) {
            this.isPaused = paused;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.setColor(getModel().isRollover() ? BUTTON_HOVER_COLOR : BUTTON_BG_COLOR);
            g2d.fillOval(0, 0, size, size);

            g2d.setColor(BUTTON_FG_COLOR);
            if (!isPlaying || isPaused) {
                int margin = size / 4;
                int[] xPoints = {margin + 2, margin + 2, size - margin + 2};
                int[] yPoints = {margin, size - margin, size / 2};
                g2d.fillPolygon(xPoints, yPoints, 3);
            } else {
                int barWidth = size / 6;
                int barHeight = size / 2;
                int margin = (size - barWidth * 3) / 2;
                g2d.fillRoundRect(margin, (size - barHeight) / 2, barWidth, barHeight, 2, 2);
                g2d.fillRoundRect(margin + barWidth * 2, (size - barHeight) / 2, barWidth, barHeight, 2, 2);
            }
            g2d.dispose();
        }
    }

    private static class ProgressBar extends JComponent {
        private int maximum = 100;
        private int value = 0;
        private volatile boolean indeterminate = false;
        private volatile float animationOffset = 0;
        private Thread animationThread = null;

        ProgressBar() {
            setPreferredSize(new Dimension(1, 12));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 12));
            setMinimumSize(new Dimension(50, 12));
        }

        void setMaximum(int max) {
            this.maximum = max;
            repaint();
        }

        void setValue(int val) {
            this.value = Math.max(0, Math.min(val, maximum));
            repaint();
        }

        void setIndeterminate(boolean ind) {
            if (this.indeterminate == ind) return;
            this.indeterminate = ind;
            if (ind) {
                startAnimation();
            }
            repaint();
        }

        private synchronized void startAnimation() {
            if (animationThread != null && animationThread.isAlive()) return;
            animationThread = new Thread(() -> {
                while (indeterminate) {
                    animationOffset += 0.05f;
                    if (animationOffset > 1) animationOffset = 0;
                    EventQueue.invokeLater(() -> repaint());
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });
            animationThread.setDaemon(true);
            animationThread.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2d.setColor(PROGRESS_BG_COLOR);
            g2d.fillRoundRect(0, 0, w, h, h, h);

            if (indeterminate) {
                int barWidth = Math.max(w / 4, 30);
                int x = (int) ((w - barWidth) * animationOffset);
                g2d.setColor(PROGRESS_FG_COLOR);
                g2d.fillRoundRect(x, 0, barWidth, h, h, h);
            } else if (value > 0) {
                int fillWidth = (int) ((long) value * w / maximum);
                g2d.setColor(PROGRESS_FG_COLOR);
                g2d.fillRoundRect(0, 0, fillWidth, h, h, h);
            }
            g2d.dispose();
        }
    }

    private final class PlaylistTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                addDroppedFiles(extractDroppedPaths(support.getTransferable()));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    private final class PlaylistCellRenderer implements ListCellRenderer<PlaylistEntry> {
        private final JPanel panel;
        private final JLabel titleLabel;
        private final JLabel durationLabel;
        private final JLabel subtitleLabel;

        PlaylistCellRenderer() {
            panel = new JPanel(new BorderLayout(10, 0));
            panel.setBorder(new EmptyBorder(8, 12, 8, 12));

            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

            titleLabel = new JLabel();
            titleLabel.setFont(findFont(Font.PLAIN, 14));

            subtitleLabel = new JLabel();
            subtitleLabel.setFont(findFont(Font.PLAIN, 11));
            subtitleLabel.setForeground(PLAYLIST_SUBTEXT_COLOR);

            durationLabel = new JLabel();
            durationLabel.setFont(findFont(Font.PLAIN, 12));
            durationLabel.setForeground(PLAYLIST_SUBTEXT_COLOR);

            textPanel.add(titleLabel);
            textPanel.add(Box.createVerticalStrut(2));
            textPanel.add(subtitleLabel);

            panel.add(textPanel, BorderLayout.CENTER);
            panel.add(durationLabel, BorderLayout.EAST);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<? extends PlaylistEntry> list,
                PlaylistEntry value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            String title = value.displayTitle;
            if (value.metadataLoading && value.loadedTrack == null) {
                title = value.displayTitle + "  (loading...)";
            } else if (value.failed && value.loadedTrack == null) {
                title = value.displayTitle + "  (failed)";
            }

            titleLabel.setText(title);
            subtitleLabel.setText(value.inputPath.getFileName() == null ? "" : value.inputPath.getFileName().toString());
            durationLabel.setText(value.durationText == null ? "--:--" : value.durationText);

            if (value == currentEntry) {
                panel.setBackground(PLAYLIST_ROW_ACTIVE_COLOR);
                titleLabel.setForeground(TITLE_COLOR);
            } else if (isSelected) {
                panel.setBackground(PLAYLIST_ROW_SELECTED_COLOR);
                titleLabel.setForeground(TITLE_COLOR);
            } else {
                panel.setBackground(PLAYLIST_ROW_COLOR);
                titleLabel.setForeground(PLAYLIST_HEADER_COLOR);
            }
            panel.setOpaque(true);
            return panel;
        }
    }

    private static final class PlaylistEntry {
        final Path inputPath;
        volatile CurrentTrack loadedTrack;
        volatile String displayTitle;
        volatile String durationText;
        volatile boolean metadataLoading;
        volatile boolean failed;
        volatile String failureMessage;

        PlaylistEntry(Path inputPath) {
            this.inputPath = inputPath;
            this.displayTitle = CurrentTrack.fileStem(inputPath);
            this.durationText = "--:--";
        }

        @Override
        public String toString() {
            return displayTitle;
        }
    }

    private enum LoopMode {
        SINGLE_TRACK("Loop One"),
        PLAYLIST("Loop List");

        private final String buttonText;

        LoopMode(String buttonText) {
            this.buttonText = buttonText;
        }

        LoopMode next() {
            return this == SINGLE_TRACK ? PLAYLIST : SINGLE_TRACK;
        }

        String buttonText() {
            return buttonText;
        }
    }

    private static final class CurrentTrack {
        final Path inputPath;
        final PlaybackTimeline timeline;
        final PlaybackSequenceBuilder.BuiltSequence builtSequence;
        final String title;
        final String copyright;
        final long durationMillis;

        CurrentTrack(Path inputPath, PlaybackTimeline timeline,
                PlaybackSequenceBuilder.BuiltSequence builtSequence,
                String title, String copyright, long durationMillis) {
            this.inputPath = inputPath;
            this.timeline = timeline;
            this.builtSequence = builtSequence;
            this.title = title;
            this.copyright = copyright;
            this.durationMillis = durationMillis;
        }

        String displayTitle() {
            if (title != null && !title.isEmpty()) return title;
            return fileStem(inputPath);
        }

        String displayCopyright() {
            if (!isPlayable()) {
                if (copyright != null && !copyright.isEmpty()) return copyright + "  |  melody tracks only";
                return "This ver plays melody tracks only";
            }
            return copyright == null || copyright.isEmpty() ? " " : copyright;
        }

        boolean isPlayable() {
            return builtSequence != null;
        }

        int defaultLoopCount() {
            return timeline.loopInfo.hasLoop ? -1 : 0;
        }

        private static String fileStem(Path path) {
            if (path == null || path.getFileName() == null) return "(untitled)";
            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
    }
}
