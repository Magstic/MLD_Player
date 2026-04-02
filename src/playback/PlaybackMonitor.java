package playback;

public interface PlaybackMonitor {
    PlaybackMonitor NONE = new PlaybackMonitor() {
        @Override
        public void onPlaybackPrepared(Descriptor descriptor) {
        }

        @Override
        public void onPlaybackProgress(Progress progress) {
        }

        @Override
        public boolean isStopRequested() {
            return false;
        }
    };

    void onPlaybackPrepared(Descriptor descriptor);

    void onPlaybackProgress(Progress progress);

    boolean isStopRequested();

    default boolean isPauseRequested() {
        return false;
    }

    final class Descriptor {
        public final String mode;
        public final String loopDescription;
        public final String backend;
        public final String output;

        public Descriptor(String mode, String loopDescription, String backend, String output) {
            this.mode = mode;
            this.loopDescription = loopDescription;
            this.backend = backend;
            this.output = output;
        }
    }

    final class Progress {
        public final long position;
        public final long total;
        public final double fraction;
        public final String label;

        public Progress(long position, long total, double fraction, String label) {
            this.position = position;
            this.total = total;
            this.fraction = fraction;
            this.label = label;
        }
    }
}
