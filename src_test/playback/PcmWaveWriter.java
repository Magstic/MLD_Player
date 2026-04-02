package playback;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PcmWaveWriter {
    public void write(byte[] pcm16le, int sampleRate, int channels, Path outputPath) throws IOException {
        int byteRate = sampleRate * channels * 2;
        int blockAlign = channels * 2;
        int dataLength = pcm16le.length;
        int riffLength = 36 + dataLength;

        OutputStream stream = Files.newOutputStream(outputPath);
        try {
            writeAscii(stream, "RIFF");
            writeLe32(stream, riffLength);
            writeAscii(stream, "WAVE");
            writeAscii(stream, "fmt ");
            writeLe32(stream, 16);
            writeLe16(stream, 1);
            writeLe16(stream, channels);
            writeLe32(stream, sampleRate);
            writeLe32(stream, byteRate);
            writeLe16(stream, blockAlign);
            writeLe16(stream, 16);
            writeAscii(stream, "data");
            writeLe32(stream, dataLength);
            stream.write(pcm16le);
        } finally {
            stream.close();
        }
    }

    private static void writeAscii(OutputStream stream, String value) throws IOException {
        stream.write(value.getBytes("US-ASCII"));
    }

    private static void writeLe16(OutputStream stream, int value) throws IOException {
        stream.write(value & 0xFF);
        stream.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(OutputStream stream, int value) throws IOException {
        stream.write(value & 0xFF);
        stream.write((value >>> 8) & 0xFF);
        stream.write((value >>> 16) & 0xFF);
        stream.write((value >>> 24) & 0xFF);
    }
}
