package export;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import audio.StereoPcm;

/** Deterministic RIFF/WAVE PCM16 stereo encoder for offline sampled-audio export. */
public final class WavEncoder {
    private static final int CHANNELS = 2;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int BLOCK_ALIGN = CHANNELS * (BITS_PER_SAMPLE / 8);

    public void write(StereoPcm pcm, Path outputPath) throws IOException {
        if (pcm == null) {
            throw new IllegalArgumentException("Stereo PCM is required.");
        }
        if (outputPath == null) {
            throw new IllegalArgumentException("WAV output path is required.");
        }
        short[] samples = pcm.copyInterleavedPcm16();
        long dataSizeLong = (long) samples.length * 2L;
        if (dataSizeLong > 0xFFFFFFFFL - 36L) {
            throw new IOException("PCM data is too large for RIFF/WAVE.");
        }
        int dataSize = (int) dataSizeLong;
        int sampleRate = pcm.getSampleRate();
        long byteRateLong = (long) sampleRate * BLOCK_ALIGN;
        if (byteRateLong > 0xFFFFFFFFL) {
            throw new IOException("WAV byte rate exceeds RIFF field range.");
        }

        Path parent = outputPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath));
        try {
            writeAscii(out, "RIFF");
            writeLe32(out, 36L + dataSizeLong);
            writeAscii(out, "WAVE");
            writeAscii(out, "fmt ");
            writeLe32(out, 16L);
            writeLe16(out, 1);
            writeLe16(out, CHANNELS);
            writeLe32(out, sampleRate);
            writeLe32(out, byteRateLong);
            writeLe16(out, BLOCK_ALIGN);
            writeLe16(out, BITS_PER_SAMPLE);
            writeAscii(out, "data");
            writeLe32(out, dataSizeLong);
            for (short sample : samples) {
                int value = sample & 0xFFFF;
                out.write(value & 0xFF);
                out.write((value >>> 8) & 0xFF);
            }
        } finally {
            out.close();
        }
    }

    private static void writeAscii(OutputStream out, String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            out.write(text.charAt(i) & 0x7F);
        }
    }

    private static void writeLe16(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }

    private static void writeLe32(OutputStream out, long value) throws IOException {
        out.write((int) (value & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 24) & 0xFF));
    }
}
