package container;

import java.util.Vector;

public final class MldFile {
    public final int noteExtraBytes;
    public final int exstSize;
    private final Vector infoChunks;
    public final Vector tracks;

    public MldFile(
            int noteExtraBytes,
            int exstSize,
            Vector infoChunks,
            Vector tracks) {
        this.noteExtraBytes = noteExtraBytes;
        this.exstSize = exstSize;
        this.infoChunks = copyVector(infoChunks);
        this.tracks = copyVector(tracks);
    }

    public String firstInfoText(String id) {
        int i;
        if (id == null) {
            return null;
        }
        for (i = 0; i < infoChunks.size(); i++) {
            InfoChunk chunk = (InfoChunk) infoChunks.elementAt(i);
            if (id.equals(chunk.id)) {
                return chunk.decodedText;
            }
        }
        return null;
    }

    private static Vector copyVector(Vector source) {
        Vector copy = new Vector();
        int i;
        for (i = 0; i < source.size(); i++) {
            copy.addElement(source.elementAt(i));
        }
        return copy;
    }
}
