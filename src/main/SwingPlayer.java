package main;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
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
import javax.swing.JComboBox;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicSliderUI;

import bridge.midi.MidiBridgeExporter;
import playback.JavaMidiPlayer;
import playback.PlaybackMonitor;
import playback.PlaybackSequenceBuilder;
import timeline.MldTimelineLoader;
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
    private static final int WINDOW_HEIGHT = 600;
    private static final int PLAYER_HEIGHT = 154;
    private static final int CONTROL_BAR_HEIGHT = 52;
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
    private PlayButton playButton;
    private JLabel titleLabel;
    private JLabel copyrightLabel;
    private ProgressBar progressBar;
    private JLabel playlistHintLabel;
    private LoopButton loopButton;
    private ExportButton exportButton;
    private JList<PlaylistEntry> playlistList;
    private DefaultListModel<PlaylistEntry> playlistModel;
    private final JavaMidiPlayer.MasterVolume masterVolume = new JavaMidiPlayer.MasterVolume(127);
    private volatile JavaMidiPlayer.MidiOutput midiOutput;
    private boolean changingMidiOutput;
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
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                stopRequested = true;
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BACKGROUND_COLOR);
        root.setPreferredSize(new Dimension(WINDOW_WIDTH, WINDOW_HEIGHT));

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel playerPanel = new JPanel(new BorderLayout());
        playerPanel.setBackground(BACKGROUND_COLOR);
        playerPanel.setBorder(new EmptyBorder(16, 16, 14, 16));
        playerPanel.setPreferredSize(new Dimension(WINDOW_WIDTH, PLAYER_HEIGHT));
        playerPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, PLAYER_HEIGHT));

        JPanel playArea = new JPanel(new GridBagLayout());
        playArea.setBackground(BACKGROUND_COLOR);
        playArea.setPreferredSize(new Dimension(LEFT_PANEL_WIDTH, PLAYER_HEIGHT));
        playArea.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, DIVIDER_COLOR));
        playButton = new PlayButton(BUTTON_SIZE);
        playButton.addActionListener(e -> handlePlayButton());
        playArea.add(playButton);
        playerPanel.add(playArea, BorderLayout.WEST);

        JPanel trackInfo = new JPanel();
        trackInfo.setOpaque(false);
        trackInfo.setBorder(new EmptyBorder(4, 18, 2, 4));
        trackInfo.setLayout(new BoxLayout(trackInfo, BoxLayout.Y_AXIS));

        titleLabel = new JLabel("No Track Loaded");
        titleLabel.setForeground(TITLE_COLOR);
        titleLabel.setFont(getTitleFont());
        titleLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        trackInfo.add(titleLabel);
        trackInfo.add(Box.createVerticalStrut(14));

        progressBar = new ProgressBar();
        progressBar.setMaximum(1000);
        progressBar.setValue(0);
        progressBar.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        trackInfo.add(progressBar);
        trackInfo.add(Box.createVerticalGlue());

        copyrightLabel = new JLabel("Tap Play to load MLD (MFi)");
        copyrightLabel.setForeground(COPYRIGHT_COLOR);
        copyrightLabel.setFont(getCopyrightFont());
        copyrightLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        copyrightLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        copyrightLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        trackInfo.add(copyrightLabel);
        playerPanel.add(trackInfo, BorderLayout.CENTER);
        top.add(playerPanel);

        JPanel controls = new JPanel();
        controls.setBackground(BACKGROUND_COLOR);
        controls.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 1, 0, DIVIDER_COLOR),
                new EmptyBorder(7, 16, 7, 16)));
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.setPreferredSize(new Dimension(WINDOW_WIDTH, CONTROL_BAR_HEIGHT));
        controls.setMaximumSize(new Dimension(Integer.MAX_VALUE, CONTROL_BAR_HEIGHT));

        loopButton = new LoopButton();
        loopButton.addActionListener(e -> toggleLoopMode());
        updateLoopModeButton();
        controls.add(loopButton);
        controls.add(Box.createHorizontalStrut(16));

        final VolumeIcon volumeIcon = new VolumeIcon(masterVolume.getValue());
        controls.add(volumeIcon);
        controls.add(Box.createHorizontalStrut(8));

        final JSlider volumeSlider = new JSlider(0, 127, masterVolume.getValue());
        volumeSlider.setOpaque(false);
        volumeSlider.setUI(new MinimalSliderUI(volumeSlider));
        volumeSlider.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        volumeSlider.setPreferredSize(new Dimension(180, 28));
        volumeSlider.setMaximumSize(new Dimension(220, 28));
        volumeSlider.getAccessibleContext().setAccessibleName("Master volume");
        volumeSlider.addChangeListener(e -> {
            int value = volumeSlider.getValue();
            masterVolume.setValue(value);
            volumeIcon.setVolume(value);
            volumeSlider.setToolTipText("Master volume " + Math.round(value * 100f / 127f) + "%");
        });
        volumeSlider.setToolTipText("Master volume 100%");
        controls.add(volumeSlider);
        controls.add(Box.createHorizontalGlue());

        List<JavaMidiPlayer.MidiOutput> outputs = JavaMidiPlayer.availableMidiOutputs();
        final JComboBox<JavaMidiPlayer.MidiOutput> outputCombo = new JComboBox<JavaMidiPlayer.MidiOutput>(
                outputs.toArray(new JavaMidiPlayer.MidiOutput[outputs.size()]));
        midiOutput = outputs.get(0);
        outputCombo.setSelectedItem(midiOutput);
        outputCombo.setBackground(PLAYLIST_SURFACE_COLOR);
        outputCombo.setForeground(PLAYLIST_HEADER_COLOR);
        outputCombo.setFont(findFont(Font.PLAIN, 12));
        outputCombo.setMaximumSize(new Dimension(210, 30));
        outputCombo.setPreferredSize(new Dimension(210, 30));
        outputCombo.setToolTipText("MIDI output / tone library");
        outputCombo.getAccessibleContext().setAccessibleName("MIDI output");
        outputCombo.addActionListener(e -> {
            if (changingMidiOutput) {
                return;
            }
            JavaMidiPlayer.MidiOutput selected = (JavaMidiPlayer.MidiOutput) outputCombo.getSelectedItem();
            if (selected == null || selected == midiOutput) {
                return;
            }
            if (selected.needsSoundFont()) {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Choose a SoundFont for FluidSynth");
                chooser.setFileFilter(new FileNameExtensionFilter("SoundFont (SF2 / SF3)", "sf2", "sf3"));
                chooser.setAcceptAllFileFilterUsed(false);
                if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION
                        || chooser.getSelectedFile() == null) {
                    changingMidiOutput = true;
                    try {
                        outputCombo.setSelectedItem(midiOutput);
                    } finally {
                        changingMidiOutput = false;
                    }
                    return;
                }
                try {
                    selected = selected.withSoundFont(chooser.getSelectedFile().toPath());
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(
                            frame, ex.getMessage(), "Invalid SoundFont", JOptionPane.ERROR_MESSAGE);
                    changingMidiOutput = true;
                    try {
                        outputCombo.setSelectedItem(midiOutput);
                    } finally {
                        changingMidiOutput = false;
                    }
                    return;
                }

                int selectedIndex = outputCombo.getSelectedIndex();
                changingMidiOutput = true;
                try {
                    outputCombo.removeItemAt(selectedIndex);
                    outputCombo.insertItemAt(selected, selectedIndex);
                    outputCombo.setSelectedIndex(selectedIndex);
                } finally {
                    changingMidiOutput = false;
                }
            }
            midiOutput = selected;
            restartCurrentPlayback();
        });
        controls.add(outputCombo);
        controls.add(Box.createHorizontalStrut(8));

        exportButton = new ExportButton();
        exportButton.addActionListener(e -> exportPlaylist());
        controls.add(exportButton);
        top.add(controls);
        root.add(top, BorderLayout.NORTH);

        JPanel playlistPanel = new JPanel(new BorderLayout(0, 8));
        playlistPanel.setBackground(BACKGROUND_COLOR);
        playlistPanel.setBorder(new EmptyBorder(10, 12, 12, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);

        JLabel headerTitle = new JLabel("Playlist");
        headerTitle.setForeground(TITLE_COLOR);
        headerTitle.setFont(findFont(Font.PLAIN, 16));
        header.add(headerTitle, BorderLayout.WEST);

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

        playlistPanel.setTransferHandler(transferHandler);
        playlistPanel.add(header, BorderLayout.NORTH);
        playlistPanel.add(scrollPane, BorderLayout.CENTER);
        root.setTransferHandler(transferHandler);
        root.add(playlistPanel, BorderLayout.CENTER);

        updatePlaylistHint();
        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(500, 480));
        frame.setLocationRelativeTo(null);
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
        if (loopButton == null) {
            return;
        }
        loopButton.setMode(loopMode);
    }

    private void toggleLoopMode() {
        loopMode = loopMode.next();
        updateLoopModeButton();
        restartCurrentPlayback();
    }

    private void restartCurrentPlayback() {
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

    private static Font findFont(int style, int size) {
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
    }

    private void exportPlaylist() {
        if (playlistEntries.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Add MLD / MFi files to the playlist first.",
                    "Nothing to export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export playlist as MIDI");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return;
        }

        final List<Path> inputPaths = new ArrayList<Path>();
        for (PlaylistEntry entry : playlistEntries) {
            inputPaths.add(entry.inputPath);
        }
        final Path selectedDirectory = chooser.getSelectedFile().toPath();
        exportButton.setEnabled(false);
        playlistHintLabel.setText("Exporting " + inputPaths.size() + " tracks...");

        SwingWorker<MidiBridgeExporter.BatchArtifacts, Void> worker =
                new SwingWorker<MidiBridgeExporter.BatchArtifacts, Void>() {
            @Override
            protected MidiBridgeExporter.BatchArtifacts doInBackground() throws Exception {
                return new MidiBridgeExporter().exportBatch(inputPaths, selectedDirectory);
            }

            @Override
            protected void done() {
                exportButton.setEnabled(true);
                updatePlaylistHint();
                try {
                    MidiBridgeExporter.BatchArtifacts result = get();
                    String message = "Exported " + result.exportedCount + " of " + inputPaths.size()
                            + " tracks to:\n" + result.outputDir;
                    if (!result.failures.isEmpty()) {
                        message += "\n\nFailed:\n" + String.join("\n", result.failures);
                    }
                    JOptionPane.showMessageDialog(
                            frame,
                            message,
                            "Export complete",
                            result.failures.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    debugException("Export interrupted", e);
                    JOptionPane.showMessageDialog(
                            frame, "Export interrupted.", "Export failed", JOptionPane.ERROR_MESSAGE);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    String message = cause.getMessage();
                    debugException("Export failed", e);
                    JOptionPane.showMessageDialog(
                            frame,
                            message == null || message.trim().isEmpty() ? cause.getClass().getSimpleName() : message,
                            "Export failed",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
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
                    showLoadFailure(entry.inputPath);
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

    private void showLoadFailure(Path inputPath) {
        currentTrack = null;
        PlaylistEntry entry = findPlaylistEntry(inputPath);
        if (entry != null) {
            entry.failed = true;
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

                int loopCount = loopMode == LoopMode.PLAYLIST ? 0 : -1;
                JavaMidiPlayer midiPlayer = new JavaMidiPlayer();
                PlaybackSequenceBuilder sequenceBuilder = new PlaybackSequenceBuilder();
                completed = midiPlayer.play(
                        sequenceBuilder.build(track.timeline, loopCount),
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

    private CurrentTrack buildTrack(Path inputPath) throws Exception {
        PlaybackTimeline timeline = MldTimelineLoader.load(inputPath);
        return new CurrentTrack(
                inputPath, timeline,
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

    private abstract static class ControlButton extends JButton {
        ControlButton(String description) {
            setPreferredSize(new Dimension(32, 32));
            setMinimumSize(new Dimension(32, 32));
            setMaximumSize(new Dimension(32, 32));
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setDescription(description);
        }

        final void setDescription(String description) {
            setToolTipText(description);
            getAccessibleContext().setAccessibleName(description);
        }

        @Override
        protected final void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (isEnabled() && getModel().isRollover()) {
                g2d.setColor(BUTTON_HOVER_COLOR);
                g2d.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
            g2d.setColor(isEnabled() ? BUTTON_FG_COLOR : PLAYLIST_HINT_COLOR);
            g2d.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            paintIcon(g2d);
            g2d.dispose();
        }

        protected abstract void paintIcon(Graphics2D graphics);
    }

    private static final class LoopButton extends ControlButton {
        private LoopMode mode;

        LoopButton() {
            super("Repeat one");
        }

        void setMode(LoopMode mode) {
            this.mode = mode;
            setDescription(mode == LoopMode.SINGLE_TRACK ? "Repeat one" : "Repeat playlist");
            repaint();
        }

        @Override
        protected void paintIcon(Graphics2D g2d) {
            int left = 6;
            int right = getWidth() - 6;
            int top = 9;
            int bottom = getHeight() - 9;
            g2d.drawLine(left + 3, top, right - 3, top);
            g2d.drawLine(right - 2, top + 3, right - 2, bottom - 3);
            g2d.drawLine(right - 3, bottom, left + 3, bottom);
            g2d.drawLine(left + 2, bottom - 3, left + 2, top + 3);
            g2d.fillPolygon(
                    new int[] {right, right - 5, right - 5},
                    new int[] {top, top - 4, top + 4},
                    3);
            g2d.fillPolygon(
                    new int[] {left, left + 5, left + 5},
                    new int[] {bottom, bottom - 4, bottom + 4},
                    3);
            if (mode == LoopMode.SINGLE_TRACK) {
                g2d.setFont(findFont(Font.BOLD, 10));
                String one = "1";
                int x = (getWidth() - g2d.getFontMetrics().stringWidth(one)) / 2;
                int y = (getHeight() + g2d.getFontMetrics().getAscent() - g2d.getFontMetrics().getDescent()) / 2;
                g2d.drawString(one, x, y);
            }
        }
    }

    private static final class ExportButton extends ControlButton {
        ExportButton() {
            super("Export playlist as MIDI");
        }

        @Override
        protected void paintIcon(Graphics2D g2d) {
            g2d.drawLine(6, 9, 13, 9);
            g2d.drawLine(6, 15, 13, 15);
            g2d.drawLine(6, 21, 13, 21);
            g2d.drawLine(17, 15, 26, 15);
            g2d.drawLine(22, 10, 26, 15);
            g2d.drawLine(22, 20, 26, 15);
        }
    }

    private static final class VolumeIcon extends JComponent {
        private int volume;

        VolumeIcon(int volume) {
            setPreferredSize(new Dimension(24, 24));
            setMinimumSize(new Dimension(24, 24));
            setMaximumSize(new Dimension(24, 24));
            setVolume(volume);
        }

        void setVolume(int volume) {
            this.volume = Math.max(0, Math.min(127, volume));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(BUTTON_FG_COLOR);
            g2d.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2d.fillPolygon(new int[] {3, 7, 12, 12, 7, 3}, new int[] {9, 9, 5, 19, 15, 15}, 6);
            if (volume == 0) {
                g2d.drawLine(15, 9, 21, 15);
                g2d.drawLine(21, 9, 15, 15);
            } else {
                g2d.drawArc(10, 7, 8, 10, -55, 110);
                if (volume >= 64) {
                    g2d.drawArc(10, 4, 13, 16, -55, 110);
                }
            }
            g2d.dispose();
        }
    }

    private static final class MinimalSliderUI extends BasicSliderUI {
        MinimalSliderUI(JSlider slider) {
            super(slider);
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(14, 14);
        }

        @Override
        protected TrackListener createTrackListener(JSlider slider) {
            return new TrackListener() {
                private boolean dragging;

                @Override
                public void mousePressed(MouseEvent event) {
                    if (!SwingUtilities.isLeftMouseButton(event) || !slider.isEnabled()) {
                        return;
                    }
                    if (slider.isRequestFocusEnabled()) {
                        slider.requestFocusInWindow();
                    }
                    slider.setValueIsAdjusting(true);
                    if (thumbRect.contains(event.getPoint())) {
                        offset = event.getX() - thumbRect.x;
                    } else {
                        slider.setValue(valueForXPosition(event.getX()));
                        offset = thumbRect.width / 2;
                    }
                    dragging = true;
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    if (dragging && slider.isEnabled()) {
                        slider.setValue(valueForXPosition(
                                event.getX() - offset + thumbRect.width / 2));
                    }
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    if (dragging) {
                        slider.setValueIsAdjusting(false);
                    }
                    dragging = false;
                    offset = 0;
                }
            };
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int centerY = trackRect.y + trackRect.height / 2;
            int startX = trackRect.x;
            int endX = trackRect.x + trackRect.width;
            int thumbX = thumbRect.x + thumbRect.width / 2;
            g2d.setColor(PROGRESS_BG_COLOR);
            g2d.fillRoundRect(startX, centerY - 2, endX - startX, 4, 4, 4);
            g2d.setColor(PROGRESS_FG_COLOR);
            if (slider.getComponentOrientation().isLeftToRight()) {
                g2d.fillRoundRect(startX, centerY - 2, Math.max(0, thumbX - startX), 4, 4, 4);
            } else {
                g2d.fillRoundRect(thumbX, centerY - 2, Math.max(0, endX - thumbX), 4, 4, 4);
            }
            g2d.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2d = (Graphics2D) g.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Rectangle thumb = thumbRect;
            g2d.setColor(BUTTON_FG_COLOR);
            g2d.fillOval(thumb.x, thumb.y, thumb.width, thumb.height);
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
        SINGLE_TRACK,
        PLAYLIST;

        LoopMode next() {
            return this == SINGLE_TRACK ? PLAYLIST : SINGLE_TRACK;
        }
    }

    private static final class CurrentTrack {
        final Path inputPath;
        final PlaybackTimeline timeline;
        final String title;
        final String copyright;
        final long durationMillis;

        CurrentTrack(Path inputPath, PlaybackTimeline timeline,
                String title, String copyright, long durationMillis) {
            this.inputPath = inputPath;
            this.timeline = timeline;
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
            return !timeline.notes.isEmpty();
        }

        private static String fileStem(Path path) {
            if (path == null || path.getFileName() == null) return "(untitled)";
            String fileName = path.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            return dot > 0 ? fileName.substring(0, dot) : fileName;
        }
    }
}
