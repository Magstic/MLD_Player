package main;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Pure playlist model for the Swing controller. */
final class PlaylistState {
    private final List<Entry> entries = new ArrayList<Entry>();
    private LoopMode loopMode = LoopMode.SINGLE_TRACK;

    Entry ensure(Path inputPath) {
        if (inputPath == null) {
            return null;
        }
        Path normalized = inputPath.toAbsolutePath().normalize();
        Entry existing = find(normalized);
        if (existing != null) {
            return existing;
        }
        Entry entry = new Entry(normalized);
        entries.add(entry);
        return entry;
    }

    Entry find(Path inputPath) {
        if (inputPath == null) {
            return null;
        }
        Path normalized = inputPath.toAbsolutePath().normalize();
        for (Entry entry : entries) {
            if (entry.inputPath.equals(normalized)) {
                return entry;
            }
        }
        return null;
    }

    Entry get(int index) {
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    int indexOf(Entry entry) {
        if (entry == null) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) {
                return i;
            }
        }
        return -1;
    }

    int nextIndex(Entry current, int selectedIndex) {
        if (entries.isEmpty()) {
            return -1;
        }
        int currentIndex = indexOf(current);
        if (currentIndex < 0) {
            currentIndex = selectedIndex;
        }
        if (currentIndex < 0) {
            currentIndex = 0;
        }
        return entries.size() == 1 ? currentIndex : (currentIndex + 1) % entries.size();
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }


    List<Path> inputPaths() {
        List<Path> paths = new ArrayList<Path>(entries.size());
        for (Entry entry : entries) {
            paths.add(entry.inputPath);
        }
        return paths;
    }

    LoopMode loopMode() {
        return loopMode;
    }

    LoopMode toggleLoopMode() {
        loopMode = loopMode.next();
        return loopMode;
    }

    static boolean accepts(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".mld") || fileName.endsWith(".mfi");
    }

    enum LoopMode {
        SINGLE_TRACK,
        PLAYLIST;

        LoopMode next() {
            return this == SINGLE_TRACK ? PLAYLIST : SINGLE_TRACK;
        }
    }

    static final class Entry {
        final Path inputPath;
        volatile ApplicationTrack loadedTrack;
        volatile String displayTitle;
        volatile String durationText;
        volatile boolean metadataLoading;
        volatile boolean failed;

        Entry(Path inputPath) {
            this.inputPath = inputPath;
            this.displayTitle = ApplicationTrack.fileStem(inputPath);
            this.durationText = "--:--";
        }

        @Override
        public String toString() {
            return displayTitle;
        }
    }
}
