package mld.decode;

import java.util.Arrays;

public final class ResourceEvent extends TrackEvent {
    private final byte[] body;
    public final int command;
    public final String name;
    public final boolean longForm;
    public ResourceEvent(int trackIndex,int eventIndex,int delta,int rawTick,int command,String name,byte[] body,boolean longForm){super(trackIndex,eventIndex,delta,rawTick);this.command=command;this.name=name;this.body=Arrays.copyOf(body,body.length);this.longForm=longForm;}
    public int bodyLength(){return body.length;}
    public int bodyByte(int index){return body[index]&0xFF;}
    public byte[] copyBody(){return Arrays.copyOf(body,body.length);}
}
