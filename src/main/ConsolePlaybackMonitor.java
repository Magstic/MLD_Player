package main;

import playback.PlaybackMonitor;

/** CLI-only rendering of playback descriptor and transport progress. */
final class ConsolePlaybackMonitor implements PlaybackMonitor {
    private static final String BOX_BORDER = "************************************************************";
    private static final int BAR_WIDTH = 28;
    private boolean renderedOnce;

    @Override
    public void onPlaybackPrepared(Descriptor descriptor) {
        System.out.println("Playback mode: " + descriptor.mode);
        System.out.println("Loop: " + descriptor.loopDescription);
        System.out.println("Backend: " + descriptor.backend);
        System.out.println("Output: " + descriptor.output);
    }

    @Override
    public void onPlaybackProgress(Progress progress) {
        render(progress);
    }

    @Override
    public void onPlaybackFinished(Progress progress, boolean completedNaturally) {
        System.out.println();
        System.out.println(BOX_BORDER);
    }

    @Override
    public boolean isStopRequested() {
        return false;
    }

    private static String formatMicros(long micros) {
        long seconds = Math.max(0L, micros) / 1000000L;
        long minutes = seconds / 60L;
        long remainder = seconds % 60L;
        return String.format("%d:%02d", Long.valueOf(minutes), Long.valueOf(remainder));
    }

    private void render(Progress progress) {
        int filled = (int) Math.round(progress.fraction * BAR_WIDTH);
        filled = Math.max(0, Math.min(BAR_WIDTH, filled));
        StringBuilder bar = new StringBuilder(BAR_WIDTH);
        for (int i = 0; i < BAR_WIDTH; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        String infoLine = String.format(
                "%s/%s  %5.1f%%  %s",
                formatMicros(progress.position),
                formatMicros(progress.total),
                Double.valueOf(progress.fraction * 100.0),
                progress.label);
        String barLine = "[" + bar.toString() + "]";
        if (renderedOnce) {
            System.out.print("\r\u001B[1A\u001B[2K" + infoLine + System.lineSeparator() + "\u001B[2K" + barLine);
        } else {
            System.out.print(infoLine + System.lineSeparator() + barLine);
            renderedOnce = true;
        }
        System.out.flush();
    }
}
