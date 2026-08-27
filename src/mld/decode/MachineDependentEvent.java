package mld.decode;

import java.util.Arrays;

public final class MachineDependentEvent extends TrackEvent {
    private final byte[] payload;
    public final int command;
    public MachineDependentEvent(int trackIndex,int eventIndex,int delta,int rawTick,int command,byte[] payload){super(trackIndex,eventIndex,delta,rawTick);this.command=command;this.payload=Arrays.copyOf(payload,payload.length);}
    public int payloadLength(){return payload.length;}
    public int payloadByte(int index){return payload[index]&0xFF;}
    public byte[] copyPayload(){return Arrays.copyOf(payload,payload.length);}
}
