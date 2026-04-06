package container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MldFile {
    public final byte[] rawBytes;
    public final String magic;
    public final long sizeField;
    public final int headerLength;
    public final int majorType;
    public final int minorType;
    public final int trackCount;
    public final int noteExtraBytes;
    public final int exstSize;
    public final List<TopLevelChunk> topLevelChunks;
    public final List<InfoChunk> infoChunks;
    public final List<TrackChunk> tracks;

    public MldFile(
            byte[] rawBytes,
            String magic,
            long sizeField,
            int headerLength,
            int majorType,
            int minorType,
            int trackCount,
            int noteExtraBytes,
            int exstSize,
            List<TopLevelChunk> topLevelChunks,
            List<InfoChunk> infoChunks,
            List<TrackChunk> tracks) {
        this.rawBytes = rawBytes.clone();
        this.magic = magic;
        this.sizeField = sizeField;
        this.headerLength = headerLength;
        this.majorType = majorType;
        this.minorType = minorType;
        this.trackCount = trackCount;
        this.noteExtraBytes = noteExtraBytes;
        this.exstSize = exstSize;
        this.topLevelChunks = Collections.unmodifiableList(new ArrayList<TopLevelChunk>(topLevelChunks));
        this.infoChunks = Collections.unmodifiableList(new ArrayList<InfoChunk>(infoChunks));
        this.tracks = Collections.unmodifiableList(new ArrayList<TrackChunk>(tracks));
    }

    public InfoChunk firstInfoChunk(String id) {
        if (id == null) {
            return null;
        }
        for (InfoChunk chunk : infoChunks) {
            if (id.equals(chunk.id)) {
                return chunk;
            }
        }
        return null;
    }

    public InfoChunk lastInfoChunk(String id) {
        if (id == null) {
            return null;
        }
        for (int index = infoChunks.size() - 1; index >= 0; index--) {
            InfoChunk chunk = infoChunks.get(index);
            if (id.equals(chunk.id)) {
                return chunk;
            }
        }
        return null;
    }

    public String firstInfoText(String id) {
        InfoChunk chunk = firstInfoChunk(id);
        return chunk != null ? chunk.decodedText : null;
    }

    public String lastInfoText(String id) {
        InfoChunk chunk = lastInfoChunk(id);
        return chunk != null ? chunk.decodedText : null;
    }

    public TopLevelChunk firstTopLevelChunk(String id) {
        if (id == null) {
            return null;
        }
        for (TopLevelChunk chunk : topLevelChunks) {
            if (id.equals(chunk.id)) {
                return chunk;
            }
        }
        return null;
    }

    public int countTopLevelChunks(String id) {
        if (id == null) {
            return 0;
        }
        int count = 0;
        for (TopLevelChunk chunk : topLevelChunks) {
            if (id.equals(chunk.id)) {
                count += 1;
            }
        }
        return count;
    }
}
