package mld.format;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;

import mld.decode.DecodedTrack;
import mld.decode.MachineDependentEvent;
import mld.decode.ReservedEvent;
import mld.decode.ResourceEvent;
import mld.decode.TrackDecoder;
import mld.decode.TrackEvent;

/** Regression coverage for the immutable format/decode foundation. */
public final class FormatDecodeFoundationAudit {
    private FormatDecodeFoundationAudit() {}
    public static void main(String[] args) throws Exception {
        byte[] source=fixture(); MldDocument document=new MldReader().read(source);
        eq("magic","melo",document.magic); eq("track count",1,document.trackCount); eq("parsed track count",1,document.tracks.size());
        eq("first note chunk wins",1,document.noteExtraBytes); eq("info chunk view",4,document.infoChunks().size()); eq("last title text","Second",clean(document.lastInfoText("titl")));
        byte originalMagic=document.copyRawBytes()[0]; source[0]=0; eq("input mutation isolation",originalMagic&0xFF,document.copyRawBytes()[0]&0xFF);
        byte[] rawCopy=document.copyRawBytes(); rawCopy[0]=0; eq("raw copy isolation",originalMagic&0xFF,document.copyRawBytes()[0]&0xFF);
        TopLevelChunk title=document.firstTopLevelChunk("titl"); byte[] titlePayload=title.copyPayload(); int titleByte=titlePayload[0]&0xFF; titlePayload[0]=0; eq("top-level payload isolation",titleByte,title.payloadByte(0));
        TrackChunk trackChunk=document.tracks.get(0); byte[] trackPayload=trackChunk.copyPayload(); int firstTrackByte=trackPayload[0]&0xFF; trackPayload[0]=0x7F; eq("track payload isolation",firstTrackByte,trackChunk.copyPayload()[0]&0xFF);
        DecodedTrack decoded=new TrackDecoder().decode(document,trackChunk); eq("decoded event count",3,decoded.events.size()); eq("resource count",1,decoded.resourceCount); eq("machine count",1,decoded.machineCount); eq("system count",1,decoded.systemCount);
        ResourceEvent resource=require(decoded.events,0,ResourceEvent.class); eq("resource body",0x55,resource.bodyByte(0)); byte[] resourceBody=resource.copyBody(); resourceBody[0]=0; eq("resource body isolation",0x55,resource.bodyByte(0));
        MachineDependentEvent machine=require(decoded.events,1,MachineDependentEvent.class); eq("machine payload length",2,machine.payloadLength()); eq("machine payload prefix",0x71,machine.payloadByte(0)); byte[] machinePayload=machine.copyPayload(); machinePayload[0]=0; eq("machine payload isolation",0x71,machine.payloadByte(0));
        assertNoPublicByteArrays(MldDocument.class,TopLevelChunk.class,TrackChunk.class,ResourceEvent.class,MachineDependentEvent.class,ReservedEvent.class);
        System.out.println("FormatDecodeFoundationAudit: PASS");
    }
    private static byte[] fixture() throws Exception { ByteArrayOutputStream chunks=new ByteArrayOutputStream(); DataOutputStream out=new DataOutputStream(chunks); writeBe16Chunk(out,"titl","First".getBytes(StandardCharsets.US_ASCII)); writeBe16Chunk(out,"titl","Second".getBytes(StandardCharsets.US_ASCII)); writeBe16Chunk(out,"note",new byte[]{0,1}); writeBe16Chunk(out,"note",new byte[]{0,2}); writeBe16Chunk(out,"cuep",new byte[]{0,0,0,0}); writeBe32Chunk(out,"trac",new byte[]{0,0x7F,(byte)0x80,0x55,0,(byte)0xFF,(byte)0xFF,0,2,0x71,(byte)0x84,0,(byte)0xFF,(byte)0xDF,0}); out.flush(); byte[] cb=chunks.toByteArray(); ByteArrayOutputStream fb=new ByteArrayOutputStream(); DataOutputStream f=new DataOutputStream(fb); f.writeBytes("melo"); f.writeInt(5+cb.length); f.writeShort(0); f.writeByte(1); f.writeByte(1); f.writeByte(1); f.write(cb); f.flush(); return fb.toByteArray(); }
    private static void writeBe16Chunk(DataOutputStream out,String id,byte[] payload)throws Exception{out.writeBytes(id);out.writeShort(payload.length);out.write(payload);} private static void writeBe32Chunk(DataOutputStream out,String id,byte[] payload)throws Exception{out.writeBytes(id);out.writeInt(payload.length);out.write(payload);}
    private static String clean(String value){return value==null?null:value.replace("\u0000","").trim();}
    private static <T extends TrackEvent> T require(List<TrackEvent> events,int index,Class<T> type){TrackEvent event=events.get(index);if(!type.isInstance(event))fail("event type","expected "+type.getSimpleName()+" at "+index+", got "+event.getClass().getSimpleName());return type.cast(event);}
    private static void assertNoPublicByteArrays(Class<?>... types){for(Class<?> type:types)for(Field field:type.getFields())if(field.getType()==byte[].class&&Modifier.isPublic(field.getModifiers()))fail("byte-array boundary",type.getName()+"."+field.getName()+" is public");}
    private static void eq(String label,int expected,int actual){if(expected!=actual)fail(label,"expected "+expected+", got "+actual);} private static void eq(String label,String expected,String actual){if(expected==null?actual!=null:!expected.equals(actual))fail(label,"expected "+expected+", got "+actual);} private static void fail(String label,String detail){throw new AssertionError(label+": "+detail);}
}
