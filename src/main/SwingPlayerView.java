package main;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import export.ExportMode;
import playback.MidiOutput;

/** Swing widget construction and presentation only. */
final class SwingPlayerView {
    interface Listener {
        void onPlayRequested();
        void onLoopModeToggleRequested();
        void onExportRequested();
        void onMidiOutputSelected(MidiOutput output);
        void onMasterVolumeChanged(int value);
        void onPlaylistEntryActivated(PlaylistState.Entry entry);
        void onPlaylistSearchChanged(String query);
        void onFilesDropped(List<Path> files);
        void onWindowClosing();
    }

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
    private static final int WINDOW_WIDTH = 600;
    private static final int WINDOW_HEIGHT = 600;
    private static final int PLAYER_HEIGHT = 154;
    private static final int CONTROL_BAR_HEIGHT = 52;
    private static final int LEFT_PANEL_WIDTH = 100;
    private static final int BUTTON_SIZE = 64;

    private final Listener listener;
    private final int initialVolume;
    private JFrame frame;
    private SwingPlayerWidgets.PlayButton playButton;
    private JLabel titleLabel;
    private JLabel copyrightLabel;
    private SwingPlayerWidgets.ProgressBar progressBar;
    private JLabel playlistCountLabel;
    private SwingPlayerWidgets.LoopButton loopButton;
    private SwingPlayerWidgets.ExportButton exportButton;
    private JList<PlaylistState.Entry> playlistList;
    private DefaultListModel<PlaylistState.Entry> playlistModel;
    private CardLayout playlistCardLayout;
    private JPanel playlistContentPanel;
    private SearchTextField playlistSearchField;
    private int lastVisiblePlaylistCount;
    private int lastTotalPlaylistCount;
    private JComboBox<MidiOutput> outputCombo;
    private boolean changingMidiOutput;
    private MidiOutput currentMidiOutput;
    private PlaylistState.Entry activeEntry;

    SwingPlayerView(
            Listener listener,
            int initialVolume,
            List<MidiOutput> outputs,
            MidiOutput initialOutput) {
        this.listener = listener;
        this.initialVolume = Math.max(0, Math.min(127, initialVolume));
        this.currentMidiOutput = initialOutput;
        buildUi(outputs);
    }

    void show() {
        frame.setVisible(true);
    }

    List<Path> chooseOpenPaths() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open MLD / MFi files, game data, or folder");
        chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter(
                "MLD / MFi / JAR / Scratchpad", "mld", "mfi", "jar", "sp", "zip", "gz"));
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return new ArrayList<Path>();
        }
        List<Path> paths = new ArrayList<Path>();
        File[] selectedFiles = chooser.getSelectedFiles();
        if (selectedFiles != null && selectedFiles.length > 0) {
            for (File file : selectedFiles) {
                if (file != null) {
                    paths.add(file.toPath().toAbsolutePath().normalize());
                }
            }
        } else if (chooser.getSelectedFile() != null) {
            paths.add(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
        }
        return paths;
    }

    ExportSelection chooseExportSelection() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export playlist");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        JPanel accessory = new JPanel();
        accessory.setLayout(new BoxLayout(accessory, BoxLayout.Y_AXIS));
        accessory.setBorder(new EmptyBorder(8, 8, 8, 8));
        JLabel formatLabel = new JLabel("Format");
        JComboBox<ExportMode> modeCombo = new JComboBox<ExportMode>(ExportMode.values());
        modeCombo.setSelectedItem(ExportMode.AUTO);
        formatLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        modeCombo.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        accessory.add(formatLabel);
        accessory.add(Box.createVerticalStrut(4));
        accessory.add(modeCombo);
        chooser.setAccessory(accessory);

        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return null;
        }
        ExportMode mode = (ExportMode) modeCombo.getSelectedItem();
        return new ExportSelection(
                chooser.getSelectedFile().toPath().toAbsolutePath().normalize(),
                mode == null ? ExportMode.AUTO : mode);
    }

    void setPlaylistEntries(List<PlaylistState.Entry> entries) {
        playlistModel.clear();
        if (entries != null) {
            for (PlaylistState.Entry entry : entries) {
                playlistModel.addElement(entry);
            }
        }
    }

    PlaylistState.Entry selectedPlaylistEntry() {
        return playlistList.getSelectedValue();
    }

    void selectPlaylistEntry(PlaylistState.Entry entry) {
        if (entry == null) {
            playlistList.clearSelection();
            playlistList.repaint();
            return;
        }
        for (int i = 0; i < playlistModel.size(); i++) {
            if (playlistModel.get(i) == entry) {
                playlistList.setSelectedIndex(i);
                playlistList.ensureIndexIsVisible(i);
                playlistList.repaint();
                return;
            }
        }
        playlistList.clearSelection();
        playlistList.repaint();
    }

    void setActiveEntry(PlaylistState.Entry entry) {
        activeEntry = entry;
        playlistList.repaint();
    }

    void repaintPlaylist() {
        playlistList.repaint();
    }

    void setLoopMode(PlaylistState.LoopMode mode) {
        loopButton.setMode(mode);
    }

    void setLoading(boolean loading) {
        progressBar.setIndeterminate(loading);
        playButton.setEnabled(!loading);
        if (loading) {
            titleLabel.setText("Loading...");
        }
    }

    void showTrack(ApplicationTrack track) {
        titleLabel.setText(track.displayTitle());
        copyrightLabel.setText(track.displayCopyright());
        progressBar.setValue(0);
    }

    void showLoadFailure() {
        titleLabel.setText("Failed to Load");
        copyrightLabel.setText("Unknown Artist");
        progressBar.setValue(0);
        progressBar.setIndeterminate(false);
    }

    void setPlaybackStarted() {
        progressBar.setValue(0);
        playButton.setPlaying(true);
        playButton.setPaused(false);
    }

    void setPaused(boolean paused) {
        playButton.setPaused(paused);
    }

    void setPlaybackProgress(double fraction) {
        progressBar.setValue((int) Math.round(fraction * 1000.0));
    }

    void setPlaybackStopped() {
        playButton.setPlaying(false);
        playButton.setPaused(false);
        playlistList.repaint();
    }

    void setProgressValue(int value) {
        progressBar.setValue(value);
    }

    void showPlaybackFailure() {
        progressBar.setValue(0);
        titleLabel.setText("Playback Failed");
    }

    void setExporting(boolean exporting, int trackCount) {
        exportButton.setEnabled(!exporting);
        if (exporting) {
            playlistCountLabel.setText("Exporting " + trackCount + "...");
        } else {
            updatePlaylistChrome(lastVisiblePlaylistCount, lastTotalPlaylistCount);
        }
    }

    void showInformation(String message, String title) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    void showExportResult(String message, boolean hasFailures) {
        JOptionPane.showMessageDialog(
                frame,
                message,
                "Export complete",
                hasFailures ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
    }

    void showError(String message, String title) {
        JOptionPane.showMessageDialog(frame, message, title, JOptionPane.ERROR_MESSAGE);
    }

    static final class ExportSelection {
        final Path directory;
        final ExportMode mode;

        ExportSelection(Path directory, ExportMode mode) {
            this.directory = directory;
            this.mode = mode;
        }
    }

    private void buildUi(List<MidiOutput> outputs) {
        frame = new JFrame("MLD Player");
        frame.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                listener.onWindowClosing();
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
        playButton = new SwingPlayerWidgets.PlayButton(BUTTON_SIZE);
        playButton.addActionListener(e -> listener.onPlayRequested());
        playArea.add(playButton);
        playerPanel.add(playArea, BorderLayout.WEST);

        JPanel trackInfo = new JPanel();
        trackInfo.setOpaque(false);
        trackInfo.setBorder(new EmptyBorder(4, 18, 2, 4));
        trackInfo.setLayout(new BoxLayout(trackInfo, BoxLayout.Y_AXIS));
        titleLabel = new JLabel("No Track Loaded");
        titleLabel.setForeground(TITLE_COLOR);
        titleLabel.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 28));
        titleLabel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        trackInfo.add(titleLabel);
        trackInfo.add(Box.createVerticalStrut(14));
        progressBar = new SwingPlayerWidgets.ProgressBar();
        progressBar.setMaximum(1000);
        progressBar.setValue(0);
        progressBar.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        trackInfo.add(progressBar);
        trackInfo.add(Box.createVerticalGlue());
        copyrightLabel = new JLabel("Unknown Artist");
        copyrightLabel.setForeground(COPYRIGHT_COLOR);
        copyrightLabel.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 16));
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
        loopButton = new SwingPlayerWidgets.LoopButton();
        loopButton.setMode(PlaylistState.LoopMode.SINGLE_TRACK);
        loopButton.addActionListener(e -> listener.onLoopModeToggleRequested());
        controls.add(loopButton);
        controls.add(Box.createHorizontalStrut(16));

        final SwingPlayerWidgets.VolumeIcon volumeIcon = new SwingPlayerWidgets.VolumeIcon(initialVolume);
        controls.add(volumeIcon);
        controls.add(Box.createHorizontalStrut(8));
        final JSlider volumeSlider = new JSlider(0, 127, initialVolume);
        volumeSlider.setOpaque(false);
        volumeSlider.setUI(new SwingPlayerWidgets.MinimalSliderUI(volumeSlider));
        volumeSlider.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        volumeSlider.setPreferredSize(new Dimension(150, 28));
        volumeSlider.setMaximumSize(new Dimension(170, 28));
        volumeSlider.getAccessibleContext().setAccessibleName("Master volume");
        volumeSlider.addChangeListener(e -> {
            int value = volumeSlider.getValue();
            listener.onMasterVolumeChanged(value);
            volumeIcon.setVolume(value);
            volumeSlider.setToolTipText("Master volume " + Math.round(value * 100f / 127f) + "%");
        });
        volumeSlider.setToolTipText("Master volume "
                + Math.round(initialVolume * 100f / 127f) + "%");
        controls.add(volumeSlider);
        controls.add(Box.createHorizontalGlue());

        List<MidiOutput> safeOutputs = outputs == null ? new ArrayList<MidiOutput>() : outputs;
        outputCombo = new JComboBox<MidiOutput>(safeOutputs.toArray(new MidiOutput[safeOutputs.size()]));
        if (currentMidiOutput != null) {
            outputCombo.setSelectedItem(currentMidiOutput);
        }
        outputCombo.setBackground(PLAYLIST_SURFACE_COLOR);
        outputCombo.setForeground(PLAYLIST_HEADER_COLOR);
        outputCombo.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 12));
        outputCombo.setMaximumSize(new Dimension(210, 30));
        outputCombo.setPreferredSize(new Dimension(210, 30));
        outputCombo.setToolTipText("MIDI output / tone library");
        outputCombo.getAccessibleContext().setAccessibleName("MIDI output");
        outputCombo.addActionListener(e -> handleOutputSelection());
        controls.add(outputCombo);
        controls.add(Box.createHorizontalStrut(8));

        exportButton = new SwingPlayerWidgets.ExportButton();
        exportButton.addActionListener(e -> listener.onExportRequested());
        controls.add(exportButton);
        top.add(controls);
        root.add(top, BorderLayout.NORTH);

        JPanel playlistPanel = new JPanel(new BorderLayout(0, 8));
        playlistPanel.setBackground(BACKGROUND_COLOR);
        playlistPanel.setBorder(new EmptyBorder(10, 12, 12, 12));
        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setOpaque(false);
        JLabel headerTitle = new JLabel("Playlist");
        headerTitle.setForeground(TITLE_COLOR);
        headerTitle.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 16));
        header.add(headerTitle, BorderLayout.WEST);

        playlistSearchField = new SearchTextField();
        playlistSearchField.setColumns(18);
        playlistSearchField.setPreferredSize(new Dimension(210, 32));
        playlistSearchField.setMaximumSize(new Dimension(240, 32));
        playlistSearchField.setToolTipText("Search playlist");
        playlistSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                handleSearchFieldChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                handleSearchFieldChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                handleSearchFieldChanged();
            }
        });
        header.add(playlistSearchField, BorderLayout.CENTER);

        playlistCountLabel = new JLabel();
        playlistCountLabel.setForeground(PLAYLIST_HINT_COLOR);
        playlistCountLabel.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 12));
        playlistCountLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        playlistCountLabel.setBorder(new EmptyBorder(0, 8, 0, 0));
        header.add(playlistCountLabel, BorderLayout.EAST);

        playlistModel = new DefaultListModel<PlaylistState.Entry>();
        playlistList = new JList<PlaylistState.Entry>(playlistModel);
        playlistList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistList.setFixedCellHeight(56);
        playlistList.setBackground(PLAYLIST_SURFACE_COLOR);
        playlistList.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        playlistList.setCellRenderer(new PlaylistCellRenderer());
        playlistList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() < 2) {
                    return;
                }
                int index = playlistList.locationToIndex(event.getPoint());
                if (index >= 0 && index < playlistModel.size()
                        && playlistList.getCellBounds(index, index) != null
                        && playlistList.getCellBounds(index, index).contains(event.getPoint())) {
                    PlaylistState.Entry entry = playlistModel.get(index);
                    selectPlaylistEntry(entry);
                    listener.onPlaylistEntryActivated(entry);
                }
            }
        });

        TransferHandler transferHandler = new PlaylistTransferHandler();
        playlistList.setTransferHandler(transferHandler);
        JScrollPane scrollPane = new JScrollPane(playlistList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(PLAYLIST_SURFACE_COLOR);
        scrollPane.setTransferHandler(transferHandler);

        JPanel emptyState = createPlaylistEmptyState();
        emptyState.setTransferHandler(transferHandler);
        playlistCardLayout = new CardLayout();
        playlistContentPanel = new JPanel(playlistCardLayout);
        playlistContentPanel.setBackground(PLAYLIST_SURFACE_COLOR);
        playlistContentPanel.setBorder(BorderFactory.createLineBorder(PLAYLIST_BORDER_COLOR));
        playlistContentPanel.add(emptyState, "empty");
        playlistContentPanel.add(scrollPane, "list");
        playlistContentPanel.setTransferHandler(transferHandler);

        playlistPanel.setTransferHandler(transferHandler);
        playlistPanel.add(header, BorderLayout.NORTH);
        playlistPanel.add(playlistContentPanel, BorderLayout.CENTER);
        root.setTransferHandler(transferHandler);
        root.add(playlistPanel, BorderLayout.CENTER);

        updatePlaylistChrome(0, 0);
        frame.setContentPane(root);
        frame.pack();
        frame.setMinimumSize(new Dimension(500, 480));
        frame.setLocationRelativeTo(null);
    }

    private void handleOutputSelection() {
        if (changingMidiOutput) {
            return;
        }
        MidiOutput selected = (MidiOutput) outputCombo.getSelectedItem();
        if (selected == null || selected == currentMidiOutput) {
            return;
        }
        if (selected.needsSoundFont()) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Choose a SoundFont for FluidSynth");
            chooser.setFileFilter(new FileNameExtensionFilter("SoundFont (SF2 / SF3)", "sf2", "sf3"));
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
                restoreOutputSelection();
                return;
            }
            try {
                selected = selected.withSoundFont(chooser.getSelectedFile().toPath());
            } catch (IllegalArgumentException ex) {
                showError(ex.getMessage(), "Invalid SoundFont");
                restoreOutputSelection();
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
        currentMidiOutput = selected;
        listener.onMidiOutputSelected(selected);
    }

    private void restoreOutputSelection() {
        changingMidiOutput = true;
        try {
            outputCombo.setSelectedItem(currentMidiOutput);
        } finally {
            changingMidiOutput = false;
        }
    }

    void updatePlaylistChrome(int visibleCount, int totalCount) {
        lastVisiblePlaylistCount = visibleCount;
        lastTotalPlaylistCount = totalCount;
        if (playlistCountLabel != null) {
            if (totalCount <= 0) {
                playlistCountLabel.setText("0 tracks");
            } else if (visibleCount == totalCount) {
                playlistCountLabel.setText(totalCount + (totalCount == 1 ? " track" : " tracks"));
            } else {
                playlistCountLabel.setText(visibleCount + " / " + totalCount);
            }
        }
        if (playlistCardLayout != null && playlistContentPanel != null) {
            playlistCardLayout.show(playlistContentPanel, totalCount == 0 ? "empty" : "list");
        }
    }

    private void handleSearchFieldChanged() {
        if (playlistSearchField != null) {
            playlistSearchField.repaint();
        }
        if (listener != null && playlistSearchField != null) {
            listener.onPlaylistSearchChanged(playlistSearchField.getText());
        }
    }

    private JPanel createPlaylistEmptyState() {
        JPanel emptyState = new JPanel(new GridBagLayout());
        emptyState.setBackground(PLAYLIST_SURFACE_COLOR);

        JPanel lines = new JPanel();
        lines.setOpaque(false);
        lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
        lines.add(emptyStateLine("How to Play ?", new Color(112, 112, 112), 19));
        lines.add(Box.createVerticalStrut(12));
        lines.add(emptyStateLine("Tap Play to load", PLAYLIST_HINT_COLOR, 16));
        lines.add(Box.createVerticalStrut(7));
        lines.add(emptyStateLine("Drag Mld to here", PLAYLIST_HINT_COLOR, 16));
        lines.add(Box.createVerticalStrut(7));
        lines.add(emptyStateLine("Drag JAR or Scratchpad", PLAYLIST_HINT_COLOR, 16));
        emptyState.add(lines);
        return emptyState;
    }

    private JLabel emptyStateLine(String text, Color color, int fontSize) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, fontSize));
        label.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private static final class SearchTextField extends JTextField {
        private static final long serialVersionUID = 1L;
        private static final int CLEAR_REGION_WIDTH = 26;

        SearchTextField() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (hasClearIcon() && event.getX() >= getWidth() - CLEAR_REGION_WIDTH) {
                        setText("");
                        requestFocusInWindow();
                    }
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent event) {
                    setCursor(Cursor.getPredefinedCursor(
                            hasClearIcon() && event.getX() >= getWidth() - CLEAR_REGION_WIDTH
                                    ? Cursor.HAND_CURSOR
                                    : Cursor.TEXT_CURSOR));
                }
            });
        }

        @Override
        public Insets getInsets() {
            Insets insets = super.getInsets();
            int extraRight = hasClearIcon() ? CLEAR_REGION_WIDTH - 4 : 0;
            return new Insets(insets.top, insets.left, insets.bottom, insets.right + extraRight);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!hasClearIcon()) {
                return;
            }
            Graphics2D g2d = (Graphics2D) graphics.create();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = getForeground();
            g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 150));
            g2d.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int centerX = getWidth() - 13;
            int centerY = getHeight() / 2;
            int radius = 4;
            g2d.drawLine(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            g2d.drawLine(centerX + radius, centerY - radius, centerX - radius, centerY + radius);
            g2d.dispose();
        }

        private boolean hasClearIcon() {
            return isEnabled() && getDocument() != null && getDocument().getLength() > 0;
        }
    }

    private final class PlaylistTransferHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;

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
                listener.onFilesDropped(extractDroppedPaths(support.getTransferable()));
                return true;
            } catch (Exception e) {
                return false;
            }
        }
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

    private final class PlaylistCellRenderer implements ListCellRenderer<PlaylistState.Entry> {
        private final JPanel panel = new JPanel(new BorderLayout(10, 0));
        private final JLabel title = new JLabel();
        private final JLabel duration = new JLabel();
        private final JLabel subtitle = new JLabel();

        PlaylistCellRenderer() {
            panel.setBorder(new EmptyBorder(8, 12, 8, 12));
            JPanel textPanel = new JPanel();
            textPanel.setOpaque(false);
            textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
            title.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 14));
            subtitle.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 11));
            subtitle.setForeground(PLAYLIST_SUBTEXT_COLOR);
            duration.setFont(SwingPlayerWidgets.findFont(Font.PLAIN, 12));
            duration.setForeground(PLAYLIST_SUBTEXT_COLOR);
            textPanel.add(title);
            textPanel.add(Box.createVerticalStrut(2));
            textPanel.add(subtitle);
            panel.add(textPanel, BorderLayout.CENTER);
            panel.add(duration, BorderLayout.EAST);
        }

        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<? extends PlaylistState.Entry> list,
                PlaylistState.Entry value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            String display = value.displayTitle;
            if (value.metadataLoading && value.loadedTrack == null) {
                display += "  (loading...)";
            } else if (value.failed && value.loadedTrack == null) {
                display += "  (failed)";
            }
            title.setText(display);
            subtitle.setText(value.inputPath.getFileName() == null ? "" : value.inputPath.getFileName().toString());
            duration.setText(value.durationText == null ? "--:--" : value.durationText);
            if (value == activeEntry) {
                panel.setBackground(PLAYLIST_ROW_ACTIVE_COLOR);
                title.setForeground(TITLE_COLOR);
            } else if (isSelected) {
                panel.setBackground(PLAYLIST_ROW_SELECTED_COLOR);
                title.setForeground(TITLE_COLOR);
            } else {
                panel.setBackground(PLAYLIST_ROW_COLOR);
                title.setForeground(PLAYLIST_HEADER_COLOR);
            }
            panel.setOpaque(true);
            return panel;
        }
    }

}
