package bridge.mld;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MldContainerWriter {
    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final Charset SHIFT_JIS = Charset.forName("MS932");

    public byte[] write(GeneratedMldSong song) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeInfoChunk(body, "vers", "0501".getBytes(ASCII));
        writeInfoChunk(body, "sorc", new byte[] { 0x00 });
        writeInfoChunk(body, "titl", encodeText(song.title));
        writeInfoChunk(body, "copy", encodeText(song.copyright));
        writeInfoChunk(body, "note", be16(1));
        writeInfoChunk(body, "exst", be16(0));
        for (GeneratedMldSong.GeneratedMldTrack track : song.tracks) {
            writeTrackChunk(body, track.payload);
        }

        byte[] chunkBytes = body.toByteArray();
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        writeAscii(file, "melo");
        writeBe32(file, chunkBytes.length + 5);
        writeBe16(file, 3);
        file.write(1);
        file.write(1);
        file.write(song.trackCount & 0xFF);
        file.write(chunkBytes);
        return file.toByteArray();
    }

    public void writeToPath(GeneratedMldSong song, Path outputPath) throws IOException {
        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(outputPath, write(song));
    }

    private static void writeInfoChunk(ByteArrayOutputStream output, String id, byte[] payload) throws IOException {
        writeAscii(output, id);
        writeBe16(output, payload.length);
        output.write(payload);
    }

    private static void writeTrackChunk(ByteArrayOutputStream output, byte[] payload) throws IOException {
        writeAscii(output, "trac");
        writeBe32(output, payload.length);
        output.write(payload);
    }

    private static byte[] encodeText(String value) {
        return value == null ? new byte[0] : value.getBytes(SHIFT_JIS);
    }

    private static byte[] be16(int value) {
        return new byte[] {
                (byte) ((value >>> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    private static void writeAscii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(ASCII));
    }

    private static void writeBe16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }

    private static void writeBe32(ByteArrayOutputStream output, int value) {
        output.write((value >>> 24) & 0xFF);
        output.write((value >>> 16) & 0xFF);
        output.write((value >>> 8) & 0xFF);
        output.write(value & 0xFF);
    }
}
