package playback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

public final class JavaPcmPlayer {
    private static final String BOX_BORDER = "************************************************************";
    private static final int CHUNK_FRAMES = 2048;

    public boolean play(byte[] pcm16le, int sampleRate, int channels, int loopCount) throws Exception {
        return play(pcm16le, sampleRate, channels, loopCount, PlaybackMonitor.NONE);
    }

    public boolean play(
            byte[] pcm16le,
            int sampleRate,
            int channels,
            int loopCount,
            PlaybackMonitor monitor) throws Exception {
        if (pcm16le == null || pcm16le.length == 0) {
            return true;
        }

        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        PlaybackMonitor safeMonitor = monitor == null ? PlaybackMonitor.NONE : monitor;
        boolean completedNaturally = true;
        boolean paused = false;
        int frameSize = Math.max(1, channels * 2);
        int totalFrames = pcm16le.length / frameSize;

        try {
            line.open(format);
            line.start();
            PlaybackMonitor.Descriptor descriptor = new PlaybackMonitor.Descriptor(
                    "resource PCM",
                    describeLoop(loopCount),
                    "Java Sound SourceDataLine",
                    "system default");
            if (safeMonitor == PlaybackMonitor.NONE) {
                printPlaybackSection(descriptor);
            }
            safeMonitor.onPlaybackPrepared(descriptor);
            safeMonitor.onPlaybackProgress(new PlaybackMonitor.Progress(0L, Math.max(1L, totalFrames), 0.0, currentLabel(loopCount, 0)));

            int passes = loopCount < 0 ? Integer.MAX_VALUE : Math.max(1, loopCount + 1);
            for (int passIndex = 0; passIndex < passes; passIndex++) {
                if (safeMonitor.isStopRequested()) {
                    completedNaturally = false;
                    break;
                }

                int offset = 0;
                while (offset < pcm16le.length) {
                    if (safeMonitor.isStopRequested()) {
                        completedNaturally = false;
                        break;
                    }

                    if (safeMonitor.isPauseRequested()) {
                        if (!paused) {
                            line.stop();
                            paused = true;
                        }
                        Thread.sleep(50L);
                        continue;
                    }
                    if (paused) {
                        line.start();
                        paused = false;
                    }

                    int remainingFrames = (pcm16le.length - offset) / frameSize;
                    int chunkFrames = Math.min(CHUNK_FRAMES, remainingFrames);
                    int chunkBytes = chunkFrames * frameSize;
                    line.write(pcm16le, offset, chunkBytes);
                    offset += chunkBytes;

                    int framePosition = offset / frameSize;
                    double fraction = totalFrames <= 0 ? 1.0 : (double) framePosition / (double) totalFrames;
                    safeMonitor.onPlaybackProgress(new PlaybackMonitor.Progress(
                            Math.min(framePosition, totalFrames),
                            Math.max(1, totalFrames),
                            fraction,
                            currentLabel(loopCount, passIndex)));
                }

                if (!completedNaturally) {
                    break;
                }
            }

            if (completedNaturally) {
                line.drain();
                safeMonitor.onPlaybackProgress(new PlaybackMonitor.Progress(
                        Math.max(1, totalFrames),
                        Math.max(1, totalFrames),
                        1.0,
                        currentLabel(loopCount, Math.max(0, passes - 1))));
            }
            return completedNaturally;
        } finally {
            line.stop();
            line.close();
        }
    }

    private void printPlaybackSection(PlaybackMonitor.Descriptor descriptor) {
        System.out.println("Playback mode: " + descriptor.mode);
        System.out.println("Loop: " + descriptor.loopDescription);
        System.out.println("Audio backend: " + descriptor.backend);
        System.out.println("Audio output: " + descriptor.output);
        System.out.println(BOX_BORDER);
    }

    private String describeLoop(int loopCount) {
        if (loopCount < 0) {
            return "infinite";
        }
        if (loopCount == 0) {
            return "once";
        }
        return (loopCount + 1) + " passes";
    }

    private String currentLabel(int loopCount, int passIndex) {
        if (loopCount < 0) {
            return "loop " + (passIndex + 1) + "/inf";
        }
        if (loopCount == 0) {
            return "play";
        }
        return "loop " + (passIndex + 1) + "/" + (loopCount + 1);
    }
}
