package container;

import java.util.Arrays;

public final class InfoChunk {
    public final String id;
    public final int offset;
    public final int length;
    public final byte[] payload;
    public final String decodedText;

    public InfoChunk(String id, int offset, int length, byte[] payload, String decodedText) {
        this.id = id;
        this.offset = offset;
        this.length = length;
        this.payload = Arrays.copyOf(payload, payload.length);
        this.decodedText = decodedText;
    }
}
