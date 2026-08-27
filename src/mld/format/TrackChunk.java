package mld.format;

import java.util.Arrays;

/** Raw payload for one parsed top-level trac chunk. */
public final class TrackChunk {
    private final byte[] payload;
    public final int index;
    public final int offset;
    public final int length;
    public TrackChunk(int index,int offset,int length,byte[] payload){this.index=index;this.offset=offset;this.length=length;this.payload=Arrays.copyOf(payload,payload.length);}
    public byte[] copyPayload(){return Arrays.copyOf(payload,payload.length);}
}
