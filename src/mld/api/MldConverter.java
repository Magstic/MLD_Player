package mld.api;

import java.io.IOException;

import mld.compile.MldCompiler;

/** Stable public facade for application-independent MLD/MFi build-time conversion. */
public final class MldConverter {
    private MldConverter() {
    }

    public static MldConversion convert(byte[] bytes) throws IOException {
        if (bytes == null) throw new IllegalArgumentException("bytes == null");
        return new MldConversion(new MldCompiler().compile(bytes));
    }
}
