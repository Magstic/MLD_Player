package util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public final class Cp932Decoder {
    private static final String TABLE_RESOURCE = "/encoding/cp932.bin";
    private static final int ENTRY_SIZE = 4;
    private static final int LOAD_BUFFER_SIZE = 1024;

    private static boolean loadAttempted;
    private static byte[] tableBytes;

    private Cp932Decoder() {
    }

    public static String decode(byte[] source) {
        StringBuffer decoded;
        int index;

        if (source == null) {
            return "";
        }
        if (!ensureTableLoaded()) {
            return null;
        }

        decoded = new StringBuffer(source.length);
        index = 0;
        while (index < source.length) {
            int first = source[index] & 0xFF;
            if (first <= 0x7F) {
                decoded.append((char) first);
                index++;
                continue;
            }
            if (first >= 0xA1 && first <= 0xDF) {
                decoded.append((char) (0xFF61 + (first - 0xA1)));
                index++;
                continue;
            }
            if (isLeadByte(first)) {
                if (index + 1 < source.length) {
                    int second = source[index + 1] & 0xFF;
                    if (isTrailByte(second)) {
                        char mapped = lookupDoubleByte((first << 8) | second);
                        if (mapped != 0) {
                            decoded.append(mapped);
                            index += 2;
                            continue;
                        }
                        decoded.append('?');
                        index += 2;
                        continue;
                    }
                }
                decoded.append('?');
                index++;
                continue;
            }

            decoded.append('?');
            index++;
        }

        return decoded.toString();
    }

    private static synchronized boolean ensureTableLoaded() {
        InputStream input = null;
        ByteArrayOutputStream output = null;

        if (loadAttempted) {
            return tableBytes != null && tableBytes.length >= ENTRY_SIZE;
        }
        loadAttempted = true;

        try {
            input = Cp932Decoder.class.getResourceAsStream(TABLE_RESOURCE);
            if (input == null) {
                return false;
            }

            output = new ByteArrayOutputStream();
            {
                byte[] buffer = new byte[LOAD_BUFFER_SIZE];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                }
            }

            tableBytes = output.toByteArray();
            return tableBytes.length >= ENTRY_SIZE && (tableBytes.length % ENTRY_SIZE) == 0;
        } catch (IOException ignored) {
            tableBytes = null;
            return false;
        } finally {
            closeQuietly(output);
            closeQuietly(input);
        }
    }

    private static char lookupDoubleByte(int key) {
        int low = 0;
        int high;

        if (tableBytes == null) {
            return 0;
        }

        high = (tableBytes.length / ENTRY_SIZE) - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int entryOffset = middle * ENTRY_SIZE;
            int currentKey = readUnsignedChar(tableBytes, entryOffset);
            if (key < currentKey) {
                high = middle - 1;
            } else if (key > currentKey) {
                low = middle + 1;
            } else {
                return (char) readUnsignedChar(tableBytes, entryOffset + 2);
            }
        }
        return 0;
    }

    private static boolean isLeadByte(int value) {
        return (value >= 0x81 && value <= 0x9F)
                || (value >= 0xE0 && value <= 0xFC);
    }

    private static boolean isTrailByte(int value) {
        return (value >= 0x40 && value <= 0x7E)
                || (value >= 0x80 && value <= 0xFC);
    }

    private static int readUnsignedChar(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8)
                | (data[offset + 1] & 0xFF);
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(ByteArrayOutputStream output) {
        if (output != null) {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }
}
