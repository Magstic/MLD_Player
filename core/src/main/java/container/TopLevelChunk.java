package container;

import java.util.Arrays;

public final class TopLevelChunk {
    public final String id;
    public final int offset;
    public final int length;
    public final int lengthFieldBytes;
    public final String category;
    public final byte[] payload;
    public final String decodedText;

    public TopLevelChunk(
            String id,
            int offset,
            int length,
            int lengthFieldBytes,
            String category,
            byte[] payload,
            String decodedText) {
        this.id = id;
        this.offset = offset;
        this.length = length;
        this.lengthFieldBytes = lengthFieldBytes;
        this.category = category;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.decodedText = decodedText;
    }
}
