package app;

import java.io.IOException;
import java.util.Vector;

import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.StringItem;
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

public final class PlaybackController implements CommandListener, J2meMidiPlayer.Listener {
    private final MIDlet midlet;
    private final Display display;
    private final FileConnectionLoader fileLoader = new FileConnectionLoader();
    private final MldParser parser = new MldParser();
    private final TrackDecoder decoder = new TrackDecoder();
    private final TimelineCompiler compiler = new TimelineCompiler();
    private final MidiBytesBuilder midiBytesBuilder = new MidiBytesBuilder();
    private final J2meMidiPlayer midiPlayer = new J2meMidiPlayer();

    private final List browserList = new List("MLD Files", List.IMPLICIT);
    private final Form playbackForm = new Form("MLD Player");
    private final StringItem titleItem = new StringItem("Title", "-");
    private final StringItem copyrightItem = new StringItem("Copyright", "-");
    private final StringItem fileItem = new StringItem("File", "-");
    private final StringItem statusItem = new StringItem("Status", "Stopped");

    private final Command openCommand = new Command("Open", Command.OK, 1);
    private final Command playCommand = new Command("Play", Command.SCREEN, 1);
    private final Command stopCommand = new Command("Stop", Command.SCREEN, 2);
    private final Command backCommand = new Command("Back", Command.BACK, 1);
    private final Command exitCommand = new Command("Exit", Command.EXIT, 9);

    private final Vector directoryStack = new Vector();
    private Vector currentEntries = new Vector();

    private String currentDirectoryUrl;
    private LoadedTrack currentTrack;
    private boolean loopBodyPhaseActive;
    private boolean loading;
    private boolean destroyed;
    private int loadToken;

    public PlaybackController(MIDlet midlet) {
        this.midlet = midlet;
        this.display = Display.getDisplay(midlet);

        browserList.addCommand(openCommand);
        browserList.addCommand(backCommand);
        browserList.addCommand(exitCommand);
        browserList.setCommandListener(this);

        playbackForm.append(titleItem);
        playbackForm.append(copyrightItem);
        playbackForm.append(fileItem);
        playbackForm.append(statusItem);
        playbackForm.addCommand(playCommand);
        playbackForm.addCommand(stopCommand);
        playbackForm.addCommand(backCommand);
        playbackForm.addCommand(exitCommand);
        playbackForm.setCommandListener(this);
    }

    public void start() {
        if (destroyed) {
            return;
        }
        if (display.getCurrent() == null) {
            showRootBrowser();
        }
    }

    public void pause() {
        stopPlayback();
    }

    public void destroy() {
        destroyed = true;
        loadToken++;
        closePlayer();
    }

    public void commandAction(Command command, Displayable displayable) {
        if (displayable == browserList) {
            handleBrowserCommand(command);
        } else if (displayable == playbackForm) {
            handlePlaybackCommand(command);
        }
    }

    public void onPlaybackCompleted() {
        if (destroyed) {
            return;
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed) {
                    return;
                }
                handlePlaybackCompletedOnUi();
            }
        });
    }

    public void onPlaybackError(final String message) {
        if (destroyed) {
            return;
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed) {
                    return;
                }
                loopBodyPhaseActive = false;
                setStatus("MIDI unsupported");
            }
        });
    }

    private void handleBrowserCommand(Command command) {
        if (command == List.SELECT_COMMAND || command == openCommand) {
            openSelection();
        } else if (command == backCommand) {
            navigateUp();
        } else if (command == exitCommand) {
            exitApplication();
        }
    }

    private void handlePlaybackCommand(Command command) {
        if (command == playCommand) {
            playCurrentTrack();
        } else if (command == stopCommand) {
            stopPlayback();
        } else if (command == backCommand) {
            stopPlayback();
            display.setCurrent(browserList);
        } else if (command == exitCommand) {
            exitApplication();
        }
    }

    private void showRootBrowser() {
        directoryStack.removeAllElements();
        currentDirectoryUrl = null;
        refreshBrowser(null);
    }

    private void refreshBrowser(String directoryUrl) {
        try {
            currentEntries = directoryUrl == null ? fileLoader.listRoots() : fileLoader.listEntries(directoryUrl);
            currentDirectoryUrl = directoryUrl;
            rebuildBrowserList();
            display.setCurrent(browserList);
        } catch (IOException e) {
            showLoadError(directoryUrl == null ? "Storage" : directoryUrl, "Cannot open file");
        }
    }

    private void rebuildBrowserList() {
        int i;
        browserList.deleteAll();
        browserList.setTitle(currentDirectoryUrl == null ? "MLD Files" : trimDirectoryTitle(currentDirectoryUrl));
        for (i = 0; i < currentEntries.size(); i++) {
            FileConnectionLoader.Entry entry = (FileConnectionLoader.Entry) currentEntries.elementAt(i);
            browserList.append(entry.directory ? "[" + entry.displayName + "]" : entry.displayName, null);
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
        beginLoad(entry);
    }

    private void navigateUp() {
        if (loading) {
            return;
        }
        if (currentDirectoryUrl == null) {
            return;
        }
        if (directoryStack.size() == 0) {
            showRootBrowser();
            return;
        }
        currentDirectoryUrl = (String) directoryStack.elementAt(directoryStack.size() - 1);
        directoryStack.removeElementAt(directoryStack.size() - 1);
        refreshBrowser(currentDirectoryUrl);
    }

    private void beginLoad(final FileConnectionLoader.Entry entry) {
        final int token;

        closePlayer();
        loopBodyPhaseActive = false;
        loading = true;
        loadToken++;
        token = loadToken;
        currentTrack = null;

        showPlaybackState(entry.displayName, "-", "-", "Loading");

        new Thread(new Runnable() {
            public void run() {
                loadTrack(entry, token);
            }
        }).start();
    }

    private void loadTrack(final FileConnectionLoader.Entry entry, final int token) {
        byte[] bytes;
        try {
            bytes = fileLoader.loadBytes(entry.url);
        } catch (IOException e) {
            postLoadError(entry.displayName, "Cannot open file", token);
            return;
        }

        try {
            MldFile file = parser.parse(bytes);
            Vector decodedTracks = decodeTracks(file);
            PlaybackTimeline timeline = compiler.compile(file, decodedTracks);
            byte[] initialMidi;
            byte[] loopMidi = null;
            LoadedTrack loaded;

            if (timeline.notes == null || timeline.notes.size() == 0) {
                postLoadError(entry.displayName, "No melody tracks", token);
                return;
            }

            initialMidi = midiBytesBuilder.build(timeline);
            if (timeline.loopInfo != null && timeline.loopInfo.hasLoop) {
                loopMidi = midiBytesBuilder.buildLoopBody(timeline);
            }

            loaded = new LoadedTrack(
                    entry.displayName,
                    chooseTitle(file, entry.displayName),
                    chooseCopyright(file),
                    initialMidi,
                    loopMidi);
            postLoadSuccess(loaded, token);
        } catch (Throwable t) {
            postLoadError(entry.displayName, "Invalid MLD", token);
        }
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

    private void postLoadSuccess(final LoadedTrack loaded, final int token) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != loadToken) {
                    return;
                }
                loading = false;
                currentTrack = loaded;
                loopBodyPhaseActive = false;
                showPlaybackState(loaded.fileName, loaded.title, loaded.copyright, "Stopped");
            }
        });
    }

    private void postLoadError(final String fileName, final String status, final int token) {
        display.callSerially(new Runnable() {
            public void run() {
                if (destroyed || token != loadToken) {
                    return;
                }
                loading = false;
                currentTrack = null;
                loopBodyPhaseActive = false;
                showLoadError(fileName, status);
            }
        });
    }

    private void showLoadError(String fileName, String status) {
        showPlaybackState(fileName, "-", "-", status);
    }

    private void showPlaybackState(String fileName, String title, String copyright, String status) {
        titleItem.setText(safeText(title, "-"));
        copyrightItem.setText(safeText(copyright, "-"));
        fileItem.setText(safeText(fileName, "-"));
        statusItem.setText(safeText(status, "Stopped"));
        display.setCurrent(playbackForm);
    }

    private void playCurrentTrack() {
        if (loading || currentTrack == null) {
            return;
        }

        closePlayer();
        loopBodyPhaseActive = false;

        try {
            midiPlayer.play(currentTrack.initialMidi, 1, this);
            setStatus("Playing");
        } catch (Throwable t) {
            setStatus("MIDI unsupported");
        }
    }

    private void stopPlayback() {
        closePlayer();
        loopBodyPhaseActive = false;
        if (!loading) {
            setStatus("Stopped");
        }
    }

    private void closePlayer() {
        midiPlayer.close();
    }

    private void handlePlaybackCompletedOnUi() {
        if (currentTrack != null && currentTrack.loopMidi != null && !loopBodyPhaseActive) {
            try {
                loopBodyPhaseActive = true;
                midiPlayer.play(currentTrack.loopMidi, -1, this);
                setStatus("Playing");
                return;
            } catch (Throwable t) {
                loopBodyPhaseActive = false;
                setStatus("MIDI unsupported");
                return;
            }
        }

        loopBodyPhaseActive = false;
        setStatus("Stopped");
    }

    private void setStatus(String status) {
        statusItem.setText(safeText(status, "Stopped"));
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
        return text.length() == 0 ? "-" : text;
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

    private static final class LoadedTrack {
        final String fileName;
        final String title;
        final String copyright;
        final byte[] initialMidi;
        final byte[] loopMidi;

        LoadedTrack(
                String fileName,
                String title,
                String copyright,
                byte[] initialMidi,
                byte[] loopMidi) {
            this.fileName = fileName;
            this.title = title;
            this.copyright = copyright;
            this.initialMidi = initialMidi;
            this.loopMidi = loopMidi;
        }
    }
}
