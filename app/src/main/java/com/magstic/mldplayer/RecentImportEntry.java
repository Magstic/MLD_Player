package com.magstic.mldplayer;

import android.net.Uri;

public final class RecentImportEntry {
    public static final String KIND_FILE = "file";
    public static final String KIND_FOLDER = "folder";

    public final String kind;
    public final Uri uri;
    public final String title;
    public final String subtitle;
    public final int itemCount;

    public RecentImportEntry(String kind, Uri uri, String title, String subtitle) {
        this(kind, uri, title, subtitle, -1);
    }

    public RecentImportEntry(String kind, Uri uri, String title, String subtitle, int itemCount) {
        this.kind = kind;
        this.uri = uri;
        this.title = title;
        this.subtitle = subtitle;
        this.itemCount = itemCount;
    }
}
