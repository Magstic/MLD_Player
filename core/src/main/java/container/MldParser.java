package container;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class MldParser {
    private static final Charset ASCII = StandardCharsets.US_ASCII;
    private static final Charset SHIFT_JIS = resolveShiftJis();

    private static final Set<String> INFO_CHUNK_IDS = createSet(
            "vers",
            "sorc",
            "prot",
            "auth",
            "titl",
            "copy",
            "date",
            "note",
            "exst",
            "supt");
    private static final Set<String> TOP_LEVEL_BE16_RESOURCE_CHUNK_IDS = createSet(
            "thrd",
            "ainf");

    public MldFile parse(byte[] data) throws IOException {
        if (data.length < 13) {
            throw new IOException("MLD file too small");
        }

        String magic = new String(data, 0, 4, ASCII);
        if (!"melo".equals(magic)) {
            throw new IOException("Unsupported MLD magic: " + magic);
        }

        long sizeField = readBe32(data, 4);
        int headerLength = readBe16(data, 8);
        int majorType = data[10] & 0xFF;
        int minorType = data[11] & 0xFF;
        int trackCount = data[12] & 0xFF;

        int offset = 13;
        int noteExtraBytes = 0;
        int exstSize = 0;
        boolean noteSeen = false;
        boolean exstSeen = false;
        List<TopLevelChunk> topLevelChunks = new ArrayList<TopLevelChunk>();
        List<InfoChunk> infoChunks = new ArrayList<InfoChunk>();
        List<TrackChunk> tracks = new ArrayList<TrackChunk>();

        while (offset < data.length) {
            if (offset + 4 > data.length) {
                throw new IOException("Truncated top-level chunk id at 0x" + Integer.toHexString(offset));
            }

            String chunkId = new String(data, offset, 4, ASCII);
            ChunkSpec chunkSpec = specForTopLevelChunk(chunkId);
            if (chunkSpec == null) {
                throw new IOException("Unsupported top-level chunk: " + chunkId + " at 0x" + Integer.toHexString(offset));
            }

            int payloadLength;
            int payloadStart;
            if (chunkSpec.lengthFieldBytes == 2) {
                if (offset + 6 > data.length) {
                    throw new IOException("Truncated top-level chunk header at 0x" + Integer.toHexString(offset));
                }
                payloadLength = readBe16(data, offset + 4);
                payloadStart = offset + 6;
            } else {
                if (offset + 8 > data.length) {
                    throw new IOException("Truncated top-level chunk header at 0x" + Integer.toHexString(offset));
                }
                payloadLength = (int) readBe32(data, offset + 4);
                payloadStart = offset + 8;
            }

            int payloadEnd = payloadStart + payloadLength;
            ensureRange(data.length, payloadStart, payloadEnd, "top-level chunk " + chunkId);

            byte[] payload = Arrays.copyOfRange(data, payloadStart, payloadEnd);
            String decodedText = decodeTextIfUseful(chunkId, payload);
            TopLevelChunk topLevelChunk = new TopLevelChunk(
                    chunkId,
                    offset,
                    payloadLength,
                    chunkSpec.lengthFieldBytes,
                    chunkSpec.category,
                    payload,
                    decodedText);
            topLevelChunks.add(topLevelChunk);

            if ("note".equals(chunkId)) {
                if (!noteSeen) {
                    if (payloadLength != 2) {
                        throw new IOException("Top-level note chunk must use a 2-byte payload");
                    }
                    noteExtraBytes = readBe16(payload, 0);
                    noteSeen = true;
                }
            } else if ("exst".equals(chunkId)) {
                if (!exstSeen) {
                    if (payloadLength != 2) {
                        throw new IOException("Top-level exst chunk must use a 2-byte payload");
                    }
                    exstSize = readBe16(payload, 0);
                    exstSeen = true;
                }
            }

            if (INFO_CHUNK_IDS.contains(chunkId)) {
                infoChunks.add(new InfoChunk(chunkId, offset, payloadLength, payload, decodedText));
            } else if ("trac".equals(chunkId)) {
                tracks.add(new TrackChunk(tracks.size(), offset, payloadLength, payload));
            }

            offset = payloadEnd;
        }

        return new MldFile(
                data,
                magic,
                sizeField,
                headerLength,
                majorType,
                minorType,
                trackCount,
                noteExtraBytes,
                exstSize,
                topLevelChunks,
                infoChunks,
                tracks);
    }

    private static ChunkSpec specForTopLevelChunk(String chunkId) {
        if (INFO_CHUNK_IDS.contains(chunkId)) {
            return new ChunkSpec(2, "info");
        }
        if ("adat".equals(chunkId)) {
            return new ChunkSpec(4, "resource");
        }
        if (TOP_LEVEL_BE16_RESOURCE_CHUNK_IDS.contains(chunkId)) {
            return new ChunkSpec(2, "resource");
        }
        if ("trac".equals(chunkId)) {
            return new ChunkSpec(4, "track");
        }
        return null;
    }

    private static Set<String> createSet(String... values) {
        Set<String> set = new HashSet<String>();
        set.addAll(Arrays.asList(values));
        return set;
    }

    private static void ensureRange(int length, int start, int end, String label) throws IOException {
        if (start < 0 || end < start || end > length) {
            throw new IOException("Invalid range for " + label);
        }
    }

    private static int readBe16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static long readBe32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24)
                | ((long) (data[offset + 1] & 0xFF) << 16)
                | ((long) (data[offset + 2] & 0xFF) << 8)
                | ((long) (data[offset + 3] & 0xFF));
    }

    private static String decodeTextIfUseful(String chunkId, byte[] payload) {
        if ("titl".equals(chunkId)
                || "copy".equals(chunkId)
                || "date".equals(chunkId)
                || "vers".equals(chunkId)
                || "supt".equals(chunkId)
                || "auth".equals(chunkId)
                || "code".equals(chunkId)
                || "prot".equals(chunkId)) {
            try {
                return new String(payload, SHIFT_JIS);
            } catch (Exception ignored) {
                return new String(payload, ASCII);
            }
        }
        return null;
    }

    private static Charset resolveShiftJis() {
        try {
            return Charset.forName("MS932");
        } catch (Exception ignored) {
            try {
                return Charset.forName("windows-31j");
            } catch (Exception ignoredAgain) {
                return Charset.forName("Shift_JIS");
            }
        }
    }

    private static final class ChunkSpec {
        final int lengthFieldBytes;
        final String category;

        ChunkSpec(int lengthFieldBytes, String category) {
            this.lengthFieldBytes = lengthFieldBytes;
            this.category = category;
        }
    }
}
