package io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Vector;

import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

import util.SortUtil;

public final class FileConnectionLoader {
    public Vector listRoots() {
        Vector entries = new Vector();
        Enumeration roots = FileSystemRegistry.listRoots();

        while (roots.hasMoreElements()) {
            String rootName = (String) roots.nextElement();
            entries.addElement(new Entry(toRootUrl(rootName), trimTrailingSlash(rootName), true));
        }

        SortUtil.sort(entries, ENTRY_COMPARATOR);
        return entries;
    }

    public Vector listEntries(String directoryUrl) throws IOException {
        Vector entries = new Vector();
        FileConnection connection = null;
        Enumeration listing;

        if (directoryUrl == null) {
            return listRoots();
        }

        try {
            connection = (FileConnection) Connector.open(ensureDirectoryUrl(directoryUrl), Connector.READ);
            if (!connection.exists() || !connection.isDirectory()) {
                throw new IOException("Not a directory");
            }
            listing = connection.list();
            while (listing.hasMoreElements()) {
                String childName = (String) listing.nextElement();
                boolean directory = childName.endsWith("/");
                String displayName = trimTrailingSlash(childName);
                if (!directory && !isSupportedMusicFile(displayName)) {
                    continue;
                }
                entries.addElement(new Entry(buildChildUrl(directoryUrl, childName), displayName, directory));
            }
        } finally {
            closeQuietly(connection);
        }

        SortUtil.sort(entries, ENTRY_COMPARATOR);
        return entries;
    }

    public byte[] loadBytes(String fileUrl) throws IOException {
        FileConnection connection = null;
        InputStream input = null;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int read;

        try {
            connection = (FileConnection) Connector.open(fileUrl, Connector.READ);
            if (!connection.exists() || connection.isDirectory()) {
                throw new IOException("Not a file");
            }
            input = connection.openInputStream();
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            closeQuietly(input);
            closeQuietly(connection);
        }
    }

    private static String toRootUrl(String rootName) {
        return "file:///" + rootName;
    }

    private static String ensureDirectoryUrl(String url) {
        return url != null && url.endsWith("/") ? url : (url + "/");
    }

    private static String buildChildUrl(String directoryUrl, String childName) {
        String base = ensureDirectoryUrl(directoryUrl);
        return base + childName;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        if (value.endsWith("/")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isSupportedMusicFile(String name) {
        String lower = name == null ? "" : name.toLowerCase();
        return lower.endsWith(".mld") || lower.endsWith(".mfi");
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(FileConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static int compareIgnoreCase(String left, String right) {
        String a = left == null ? "" : left.toLowerCase();
        String b = right == null ? "" : right.toLowerCase();
        return a.compareTo(b);
    }

    private static final SortUtil.Comparator ENTRY_COMPARATOR = new SortUtil.Comparator() {
        public int compare(Object left, Object right) {
            Entry a = (Entry) left;
            Entry b = (Entry) right;
            if (a.directory != b.directory) {
                return a.directory ? -1 : 1;
            }
            return compareIgnoreCase(a.displayName, b.displayName);
        }
    };

    public static final class Entry {
        public final String url;
        public final String displayName;
        public final boolean directory;

        public Entry(String url, String displayName, boolean directory) {
            this.url = url;
            this.displayName = displayName;
            this.directory = directory;
        }
    }
}
