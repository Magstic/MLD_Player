package mld.format;

import java.util.Arrays;

/** One authoritative top-level chunk record from the MLD container. */
public final class TopLevelChunk {
    private final byte[] payload;
    public final String id;
    public final int offset;
    public final int length;
    public final int lengthFieldBytes;
    public final String category;
    public final String decodedText;

    public TopLevelChunk(String id, int offset, int length, int lengthFieldBytes, String category, byte[] payload, String decodedText) {
        this.id=id; this.offset=offset; this.length=length; this.lengthFieldBytes=lengthFieldBytes; this.category=category;
        this.payload=Arrays.copyOf(payload,payload.length); this.decodedText=decodedText;
    }
    public boolean isCategory(String expected){ return expected!=null && expected.equals(category); }
    public int payloadLength(){ return payload.length; }
    public int payloadByte(int index){ return payload[index]&0xFF; }
    public byte[] copyPayload(){ return Arrays.copyOf(payload,payload.length); }
}
