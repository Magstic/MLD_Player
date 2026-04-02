package container;

import java.util.Arrays;

public final class TrackChunk {
    public final int index;
    public final int offset;
    public final int length;
    public final byte[] payload;

    public TrackChunk(int index, int offset, int length, byte[] payload) {
        this.index = index;
        this.offset = offset;
        this.length = length;
        this.payload = Arrays.copyOf(payload, payload.length);
    }
}
