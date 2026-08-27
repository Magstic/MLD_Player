package mld.semantic;

/** Structured semantic diagnostic with stable source provenance. */
public final class Diagnostic {
    public enum Severity {
        INFO,
        WARNING,
        UNSUPPORTED
    }

    public final String code;
    public final Severity severity;
    public final int sourceTrack;
    public final int eventIndex;
    public final int rawTick;
    public final String message;

    public Diagnostic(
            String code,
            Severity severity,
            int sourceTrack,
            int eventIndex,
            int rawTick,
            String message) {
        this.code = code;
        this.severity = severity;
        this.sourceTrack = sourceTrack;
        this.eventIndex = eventIndex;
        this.rawTick = rawTick;
        this.message = message;
    }
}
