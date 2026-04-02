package util;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class JsonSink {
    public void write(Object value, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
        try {
            writeValue(writer, value, 0);
            writer.write(System.lineSeparator());
        } finally {
            writer.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void writeValue(Writer writer, Object value, int indent) throws IOException {
        if (value == null) {
            writer.write("null");
            return;
        }
        if (value instanceof String) {
            writeString(writer, (String) value);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            writer.write(String.valueOf(value));
            return;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> map = (Map<String, Object>) value;
            writer.write("{");
            if (!map.isEmpty()) {
                writer.write(System.lineSeparator());
                Iterator<Map.Entry<String, Object>> iterator = map.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<String, Object> entry = iterator.next();
                    writeIndent(writer, indent + 1);
                    writeString(writer, entry.getKey());
                    writer.write(": ");
                    writeValue(writer, entry.getValue(), indent + 1);
                    if (iterator.hasNext()) {
                        writer.write(",");
                    }
                    writer.write(System.lineSeparator());
                }
                writeIndent(writer, indent);
            }
            writer.write("}");
            return;
        }
        if (value instanceof List<?>) {
            List<Object> list = (List<Object>) value;
            writer.write("[");
            if (!list.isEmpty()) {
                writer.write(System.lineSeparator());
                for (int i = 0; i < list.size(); i++) {
                    writeIndent(writer, indent + 1);
                    writeValue(writer, list.get(i), indent + 1);
                    if (i + 1 < list.size()) {
                        writer.write(",");
                    }
                    writer.write(System.lineSeparator());
                }
                writeIndent(writer, indent);
            }
            writer.write("]");
            return;
        }

        writeString(writer, String.valueOf(value));
    }

    private void writeString(Writer writer, String value) throws IOException {
        writer.write('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    writer.write("\\\\");
                    break;
                case '"':
                    writer.write("\\\"");
                    break;
                case '\b':
                    writer.write("\\b");
                    break;
                case '\f':
                    writer.write("\\f");
                    break;
                case '\n':
                    writer.write("\\n");
                    break;
                case '\r':
                    writer.write("\\r");
                    break;
                case '\t':
                    writer.write("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        writer.write(String.format("\\u%04x", (int) ch));
                    } else {
                        writer.write(ch);
                    }
                    break;
            }
        }
        writer.write('"');
    }

    private void writeIndent(Writer writer, int indent) throws IOException {
        for (int i = 0; i < indent; i++) {
            writer.write("  ");
        }
    }
}
