package com.magstic.mldplayer;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import container.MldFile;
import container.MldParser;
import event.TrackDecodeResult;
import event.TrackDecoder;
import normalize.MdNormalizationResult;
import normalize.MdNormalizer;
import timeline.PlaybackTimeline;
import timeline.TimelineCompiler;

public final class PlaylistRepository {
    private final Context context;
    private final ContentResolver resolver;
    private final MldParser parser = new MldParser();
    private final TrackDecoder decoder = new TrackDecoder();
    private final MdNormalizer normalizer = new MdNormalizer();
    private final TimelineCompiler compiler = new TimelineCompiler();

    public PlaylistRepository(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
    }

    public byte[] readBytes(Uri uri) throws IOException {
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;

        try {
            input = resolver.openInputStream(uri);
            if (input == null) {
                throw new IOException("Cannot open file");
            }
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public void copyUriToFile(Uri uri, File destination) throws IOException {
        InputStream input = null;
        FileOutputStream output = null;
        byte[] buffer = new byte[8192];
        int read;

        if (uri == null || destination == null) {
            throw new IOException("Invalid file target");
        }
        if (destination.getParentFile() != null
                && !destination.getParentFile().exists()
                && !destination.getParentFile().mkdirs()) {
            throw new IOException("Cannot create target directory");
        }

        try {
            input = resolver.openInputStream(uri);
            if (input == null) {
                throw new IOException("Cannot open file");
            }
            output = new FileOutputStream(destination, false);
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                }
            }
            output.flush();
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public String displayName(Uri uri) {
        DocumentFile document = DocumentFile.fromSingleUri(context, uri);
        String name;

        if (document != null) {
            name = document.getName();
            if (hasText(name)) {
                return name;
            }
        }

        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    name = cursor.getString(index);
                    if (hasText(name)) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        String fallback = uri == null ? null : uri.getLastPathSegment();
        return hasText(fallback) ? fallback : "Unknown.mld";
    }

    public String treeDisplayName(Uri treeUri) {
        DocumentFile document = DocumentFile.fromTreeUri(context, treeUri);
        String name;

        if (document != null) {
            name = document.getName();
            if (hasText(name)) {
                return name;
            }
        }

        String fallback = treeUri == null ? null : treeUri.getLastPathSegment();
        return hasText(fallback) ? fallback : "Folder";
    }

    public String treeDisplayPath(Uri treeUri) {
        String docId;
        int separatorIndex;
        String relativePath;

        if (treeUri == null) {
            return "";
        }
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
            separatorIndex = docId.indexOf(':');
            if (separatorIndex >= 0 && separatorIndex + 1 < docId.length()) {
                relativePath = docId.substring(separatorIndex + 1);
                return hasText(relativePath) ? relativePath : treeDisplayName(treeUri);
            }
            return hasText(docId) ? docId : treeDisplayName(treeUri);
        } catch (Throwable ignored) {
            return treeDisplayName(treeUri);
        }
    }

    public List<PlaylistEntry> scanTree(Uri treeUri) throws IOException {
        ArrayList<PlaylistEntry> entries = new ArrayList<PlaylistEntry>();
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);

        if (root == null || !root.canRead()) {
            throw new IOException("Cannot open folder");
        }

        scanDirectory(root, entries);
        return entries;
    }

    private void scanDirectory(DocumentFile directory, List<PlaylistEntry> output) {
        DocumentFile[] children = directory.listFiles();
        int index;

        Arrays.sort(children, DOCUMENT_COMPARATOR);
        for (index = 0; index < children.length; index++) {
            DocumentFile child = children[index];

            if (child == null || !child.canRead()) {
                continue;
            }
            if (child.isDirectory()) {
                scanDirectory(child, output);
                continue;
            }
            if (!isPlaylistCandidate(child.getName())) {
                continue;
            }
            try {
                byte[] bytes = readBytes(child.getUri());
                MldFile file = parser.parse(bytes);
                String fileName = safeText(child.getName(), displayName(child.getUri()));
                output.add(new PlaylistEntry(
                        child.getUri(),
                        fileName,
                        chooseTitle(file, fileName),
                        estimateDurationMillis(file)));
            } catch (Throwable ignored) {
            }
        }
    }

    private int estimateDurationMillis(MldFile file) throws IOException {
        ArrayList<TrackDecodeResult> decodedTracks = new ArrayList<TrackDecodeResult>();
        int index;

        for (index = 0; index < file.tracks.size(); index++) {
            decodedTracks.add(decoder.decode(file, file.tracks.get(index)));
        }

        MdNormalizationResult normalization = normalizer.normalize(decodedTracks);
        PlaybackTimeline timeline = compiler.compile(file, decodedTracks, normalization);
        if (timeline.notes.isEmpty()) {
            return -1;
        }
        if (timeline.loopInfo != null && timeline.loopInfo.hasLoop) {
            return toMillis(timeline, timeline.loopInfo.loopEndMidiTick);
        }
        return toMillis(timeline, timeline.totalMidiTicks);
    }

    private static String chooseTitle(MldFile file, String fallback) {
        String title = cleanInfoText(file.lastInfoText("titl"));
        return hasText(title) ? title : safeText(fallback, "Unknown");
    }

    public static String chooseCopyright(MldFile file) {
        return cleanInfoText(file.lastInfoText("copy"));
    }

    private static String cleanInfoText(String text) {
        String cleaned;

        if (text == null) {
            return "";
        }
        cleaned = text.replace('\u0000', ' ').replace('\r', ' ').replace('\n', ' ').trim();
        return cleaned;
    }

    private static boolean isPlaylistCandidate(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.endsWith(".mld") || lower.endsWith(".mfi");
    }

    private static boolean hasText(String text) {
        return text != null && text.trim().length() > 0;
    }

    private static String safeText(String text, String fallback) {
        return hasText(text) ? text.trim() : fallback;
    }

    private static final Comparator<DocumentFile> DOCUMENT_COMPARATOR =
            new Comparator<DocumentFile>() {
                @Override
                public int compare(DocumentFile left, DocumentFile right) {
                    boolean leftDir = left != null && left.isDirectory();
                    boolean rightDir = right != null && right.isDirectory();
                    String leftName = left == null ? "" : safeText(left.getName(), "");
                    String rightName = right == null ? "" : safeText(right.getName(), "");

                    if (leftDir != rightDir) {
                        return leftDir ? -1 : 1;
                    }
                    return leftName.compareToIgnoreCase(rightName);
                }
            };

    private static int toMillis(PlaybackTimeline timeline, long endTick) {
        List<PlaybackTimeline.TempoPoint> points = timeline.tempoPoints;
        long boundedEnd = Math.max(0L, endTick);
        long totalMicros = 0L;
        int index;

        for (index = 0; index < points.size(); index++) {
            PlaybackTimeline.TempoPoint point = points.get(index);
            long segmentStart = point.midiTick;
            long segmentEnd = boundedEnd;

            if (segmentStart >= boundedEnd) {
                break;
            }
            if (index + 1 < points.size()) {
                segmentEnd = Math.min(boundedEnd, points.get(index + 1).midiTick);
            }
            if (segmentEnd <= segmentStart) {
                continue;
            }
            totalMicros += ((segmentEnd - segmentStart) * point.mpqn) / PlaybackTimeline.MIDI_PPQ;
        }
        return (int) Math.min(Integer.MAX_VALUE, totalMicros / 1000L);
    }
}
