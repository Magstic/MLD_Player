package main;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import mld.format.MldReader;

/** Content-based MLD discovery through arbitrary binary framing and nested compression. */
final class EmbeddedMldScanner {
    private static final int MAX_DEPTH = 12;
    private static final int MAX_DECODED_BLOB_BYTES = 128 * 1024 * 1024;
    private static final long MAX_TOTAL_DECODED_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_TOTAL_MLD_BYTES = 512L * 1024L * 1024L;

    private final MldReader reader = new MldReader();

    List<FoundMld> scan(Path input) throws IOException {
        if (input == null) {
            throw new IllegalArgumentException("Input path is required.");
        }
        Path normalized = input.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        if (size == 0L) {
            return new ArrayList<FoundMld>();
        }
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Container exceeds the 2 GiB scanner address space: " + normalized);
        }
        ScanState state = new ScanState();
        try (FileChannel channel = FileChannel.open(normalized, StandardOpenOption.READ)) {
            MappedByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size);
            scanBlob(mapped, normalized.getFileName().toString(), 0, state);
        }
        return state.results;
    }

    private void scanBlob(ByteBuffer source, String origin, int depth, ScanState state) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new ScanLimitException("Nested compression exceeds 12 layers");
        }
        int length = source.capacity();
        for (int offset = 0; offset <= length - 4; offset++) {
            int first = u8(source, offset);
            if (first == 'm') {
                int span = tryMld(source, offset, origin, state);
                if (span > 0) {
                    offset += span - 1;
                    continue;
                }
            }
            if (first == 'P' && isZipLocalHeader(source, offset)) {
                DecodedBlob decoded = tryZipMember(source, offset, origin, state);
                if (decoded != null) {
                    scanBlob(ByteBuffer.wrap(decoded.data), decoded.origin, depth + 1, state);
                    if (decoded.consumedHint > 1) {
                        offset += decoded.consumedHint - 1;
                    }
                    continue;
                }
            }
            if (first == 0x1F && isGzipHeader(source, offset)) {
                DecodedBlob decoded = tryGzip(source, offset, origin, state);
                if (decoded != null) {
                    scanBlob(ByteBuffer.wrap(decoded.data), decoded.origin, depth + 1, state);
                }
            }
        }
    }

    private int tryMld(ByteBuffer source, int offset, String origin, ScanState state) throws IOException {
        final int span;
        try {
            span = reader.containerLengthAt(source, offset);
        } catch (IOException invalidFrame) {
            return 0;
        }
        if (state.totalMldBytes + span > MAX_TOTAL_MLD_BYTES) {
            throw new ScanLimitException("Discovered MLD data exceeds 512 MiB safety limit");
        }
        byte[] data = copy(source, offset, span);
        try {
            reader.read(data);
        } catch (IOException invalid) {
            return 0;
        }
        state.results.add(new FoundMld(data, origin + "@0x" + Integer.toHexString(offset)));
        state.totalMldBytes += span;
        return span;
    }

    private DecodedBlob tryZipMember(ByteBuffer source, int offset, String origin, ScanState state)
            throws IOException {
        int flags = readLe16(source, offset + 6);
        int method = readLe16(source, offset + 8);
        int headerLength = 30 + readLe16(source, offset + 26) + readLe16(source, offset + 28);
        if ((flags & 1) != 0 || (method != 0 && method != 8) || offset + headerLength > source.capacity()) {
            return null;
        }
        try (ZipInputStream zip = new ZipInputStream(open(source, offset))) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            byte[] decoded = readBounded(zip, state);
            if (decoded.length == 0) {
                return null;
            }
            String name = entry.getName();
            if (name == null || name.isEmpty()) {
                name = "zip-member@0x" + Integer.toHexString(offset);
            }
            return new DecodedBlob(
                    decoded,
                    origin + "!" + name,
                    zipConsumedHint(source, offset, flags, headerLength));
        } catch (ScanLimitException limit) {
            throw limit;
        } catch (IOException invalidZipCandidate) {
            return null;
        }
    }

    private DecodedBlob tryGzip(ByteBuffer source, int offset, String origin, ScanState state)
            throws IOException {
        try (GZIPInputStream gzip = new GZIPInputStream(open(source, offset))) {
            byte[] decoded = readBounded(gzip, state);
            return decoded.length == 0
                    ? null
                    : new DecodedBlob(decoded, origin + "!gzip@0x" + Integer.toHexString(offset), 1);
        } catch (ScanLimitException limit) {
            throw limit;
        } catch (IOException invalidGzipCandidate) {
            return null;
        }
    }

    private static byte[] readBounded(InputStream in, ScanState state) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[16384];
        int total = 0;
        for (int read; (read = in.read(buffer)) >= 0;) {
            if (read == 0) {
                continue;
            }
            total += read;
            if (total > MAX_DECODED_BLOB_BYTES) {
                throw new ScanLimitException("Decoded member exceeds 128 MiB safety limit");
            }
            if (state.totalDecodedBytes + total > MAX_TOTAL_DECODED_BYTES) {
                throw new ScanLimitException("Recursive decoded data exceeds 512 MiB safety limit");
            }
            out.write(buffer, 0, read);
        }
        state.totalDecodedBytes += total;
        return out.toByteArray();
    }

    private static int zipConsumedHint(ByteBuffer source, int offset, int flags, int headerLength) {
        if ((flags & 0x0008) != 0) {
            return 1;
        }
        long compressedSize = readLe32(source, offset + 18);
        long span = headerLength + compressedSize;
        return compressedSize != 0xFFFFFFFFL
                && span <= Integer.MAX_VALUE
                && span <= source.capacity() - offset
                ? (int) span
                : 1;
    }

    private static boolean isZipLocalHeader(ByteBuffer source, int offset) {
        if (offset + 30 > source.capacity()
                || u8(source, offset) != 'P'
                || u8(source, offset + 1) != 'K'
                || u8(source, offset + 2) != 0x03
                || u8(source, offset + 3) != 0x04) {
            return false;
        }
        int headerLength = 30 + readLe16(source, offset + 26) + readLe16(source, offset + 28);
        int method = readLe16(source, offset + 8);
        return readLe16(source, offset + 4) <= 63
                && (method == 0 || method == 8)
                && offset + (long) headerLength <= source.capacity();
    }

    private static boolean isGzipHeader(ByteBuffer source, int offset) {
        return offset + 10 <= source.capacity()
                && u8(source, offset) == 0x1F
                && u8(source, offset + 1) == 0x8B
                && u8(source, offset + 2) == 0x08
                && (u8(source, offset + 3) & 0xE0) == 0;
    }

    private static int u8(ByteBuffer source, int offset) {
        return source.get(offset) & 0xFF;
    }

    private static int readLe16(ByteBuffer source, int offset) {
        return u8(source, offset) | (u8(source, offset + 1) << 8);
    }

    private static long readLe32(ByteBuffer source, int offset) {
        return (long) u8(source, offset)
                | ((long) u8(source, offset + 1) << 8)
                | ((long) u8(source, offset + 2) << 16)
                | ((long) u8(source, offset + 3) << 24);
    }

    private static byte[] copy(ByteBuffer source, int offset, int length) {
        byte[] data = new byte[length];
        ByteBuffer view = source.asReadOnlyBuffer();
        view.position(offset);
        view.get(data);
        return data;
    }

    private static InputStream open(ByteBuffer source, int offset) {
        ByteBuffer view = source.asReadOnlyBuffer();
        view.position(offset);
        return new ByteBufferInputStream(view.slice());
    }

    static final class FoundMld {
        private final byte[] data;
        final String origin;

        FoundMld(byte[] data, String origin) {
            this.data = data.clone();
            this.origin = origin;
        }

        byte[] copyData() {
            return data.clone();
        }
    }

    private static final class ScanState {
        final List<FoundMld> results = new ArrayList<FoundMld>();
        long totalDecodedBytes;
        long totalMldBytes;
    }

    private static final class DecodedBlob {
        final byte[] data;
        final String origin;
        final int consumedHint;

        DecodedBlob(byte[] data, String origin, int consumedHint) {
            this.data = data;
            this.origin = origin;
            this.consumedHint = consumedHint;
        }
    }

    private static final class ByteBufferInputStream extends InputStream {
        private final ByteBuffer data;

        ByteBufferInputStream(ByteBuffer data) {
            this.data = data;
        }

        @Override
        public int read() {
            return data.hasRemaining() ? data.get() & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (!data.hasRemaining()) {
                return -1;
            }
            int count = Math.min(length, data.remaining());
            data.get(buffer, offset, count);
            return count;
        }
    }

    private static final class ScanLimitException extends IOException {
        private static final long serialVersionUID = 1L;

        ScanLimitException(String message) {
            super(message);
        }
    }
}
