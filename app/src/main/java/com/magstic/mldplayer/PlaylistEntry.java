package com.magstic.mldplayer;

import android.net.Uri;

public final class PlaylistEntry {
    public final Uri uri;
    public final String fileName;
    public final String displayTitle;
    public final int durationMs;

    public PlaylistEntry(Uri uri, String fileName, String displayTitle) {
        this(uri, fileName, displayTitle, -1);
    }

    public PlaylistEntry(Uri uri, String fileName, String displayTitle, int durationMs) {
        this.uri = uri;
        this.fileName = safeText(fileName, "Unknown");
        this.displayTitle = safeText(displayTitle, this.fileName);
        this.durationMs = durationMs > 0 ? durationMs : -1;
    }

    public String uriString() {
        return uri == null ? "" : uri.toString();
    }

    private static String safeText(String text, String fallback) {
        if (text == null) {
            return fallback;
        }
        text = text.trim();
        return text.length() == 0 ? fallback : text;
    }
}
