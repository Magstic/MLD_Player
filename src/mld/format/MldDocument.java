package mld.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable structural representation of one parsed MLD container. */
public final class MldDocument {
    private final byte[] rawBytes;

    public final String magic;
    public final long sizeField;
    public final int headerLength;
    public final int majorType;
    public final int minorType;
    public final int trackCount;
    public final int noteExtraBytes;
    public final int exstSize;
    public final List<Long> cuePointOffsets;
    public final List<TopLevelChunk> topLevelChunks;
    public final List<TrackChunk> tracks;

    public MldDocument(
            byte[] rawBytes,
            String magic,
            long sizeField,
            int headerLength,
            int majorType,
            int minorType,
            int trackCount,
            int noteExtraBytes,
            int exstSize,
            List<Long> cuePointOffsets,
            List<TopLevelChunk> topLevelChunks,
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
        this.cuePointOffsets = Collections.unmodifiableList(new ArrayList<Long>(cuePointOffsets));
        this.topLevelChunks = Collections.unmodifiableList(new ArrayList<TopLevelChunk>(topLevelChunks));
        this.tracks = Collections.unmodifiableList(new ArrayList<TrackChunk>(tracks));
    }

    public int rawLength() { return rawBytes.length; }
    public byte[] copyRawBytes() { return rawBytes.clone(); }

    /** Returns the info-category chunks as views of the authoritative top-level chunk list. */
    public List<TopLevelChunk> infoChunks() {
        List<TopLevelChunk> result = new ArrayList<TopLevelChunk>();
        for (TopLevelChunk chunk : topLevelChunks) if (chunk.isCategory("info")) result.add(chunk);
        return Collections.unmodifiableList(result);
    }

    public String lastInfoText(String id) {
        if (id == null) return null;
        for (int index = topLevelChunks.size() - 1; index >= 0; index--) {
            TopLevelChunk chunk = topLevelChunks.get(index);
            if (chunk.isCategory("info") && id.equals(chunk.id)) return chunk.decodedText;
        }
        return null;
    }

    public TopLevelChunk firstTopLevelChunk(String id) {
        if (id == null) return null;
        for (TopLevelChunk chunk : topLevelChunks) if (id.equals(chunk.id)) return chunk;
        return null;
    }

    public int countTopLevelChunks(String id) {
        if (id == null) return 0;
        int count = 0;
        for (TopLevelChunk chunk : topLevelChunks) if (id.equals(chunk.id)) count++;
        return count;
    }
}
