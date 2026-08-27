package playback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/** Owns one Java Sound stereo PCM output line and its bounded latency policy. */
final class PcmOutputConnection implements AutoCloseable {
    static final int TARGET_BUFFER_MILLIS = 80;

    private final SourceDataLine line;
    private final int sampleRate;

    private PcmOutputConnection(SourceDataLine line, int sampleRate) {
        this.line = line;
        this.sampleRate = sampleRate;
    }

    static PcmOutputConnection open(int sampleRate) throws LineUnavailableException {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate <= 0");
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        int bufferFrames = Math.max(256, (sampleRate * TARGET_BUFFER_MILLIS) / 1000);
        line.open(format, bufferFrames * 4);
        return new PcmOutputConnection(line, sampleRate);
    }

    int availableBytes() {
        return Math.max(0, line.available());
    }

    int write(byte[] data, int offset, int length) {
        return line.write(data, offset, length);
    }

    void start() {
        line.start();
    }

    void stop() {
        line.stop();
    }

    void flush() {
        line.flush();
    }

    void drain() {
        line.drain();
    }

    long framePosition() {
        return Math.max(0L, line.getLongFramePosition());
    }

    int sampleRate() {
        return sampleRate;
    }

    String description() {
        return "system default";
    }

    @Override
    public void close() {
        try {
            line.stop();
        } finally {
            line.close();
        }
    }
}
