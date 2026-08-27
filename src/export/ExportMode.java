package export;

/** User-visible export format selection. */
public enum ExportMode {
    AUTO("Auto"),
    MIDI("MIDI"),
    WAV("WAV");

    private final String label;

    ExportMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
