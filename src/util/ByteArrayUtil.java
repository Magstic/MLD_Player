package util;

public final class ByteArrayUtil {
    private ByteArrayUtil() {
    }

    public static byte[] copy(byte[] source) {
        if (source == null) {
            return null;
        }
        byte[] copy = new byte[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    public static byte[] copyRange(byte[] source, int start, int end) {
        int length = end - start;
        byte[] copy = new byte[length];
        System.arraycopy(source, start, copy, 0, length);
        return copy;
    }
}
