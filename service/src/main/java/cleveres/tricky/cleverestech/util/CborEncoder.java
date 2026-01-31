package cleveres.tricky.cleverestech.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * A simple, standalone CBOR encoder to support RKP structures.
 * Implements RFC 8949 subsets required for COSE/RKP.
 */
public class CborEncoder {
    
    // Major types
    private static final int MT_UNSIGNED = 0;
    private static final int MT_NEGATIVE = 1;
    private static final int MT_BYTE_STRING = 2;
    private static final int MT_TEXT_STRING = 3;
    private static final int MT_ARRAY = 4;
    private static final int MT_MAP = 5;
    private static final int MT_TAG = 6;
    private static final int MT_SIMPLE = 7;

    public static byte[] encode(Object object) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            encodeItem(baos, object);
        } catch (IOException e) {
            throw new RuntimeException("CBOR encoding failed", e);
        }
        return baos.toByteArray();
    }

    private static void encodeItem(ByteArrayOutputStream os, Object value) throws IOException {
        if (value == null) {
            writeTypeAndArgument(os, MT_SIMPLE, 22); // null
        } else if (value instanceof Integer) {
            long val = (Integer) value;
            if (val >= 0) {
                writeTypeAndArgument(os, MT_UNSIGNED, val);
            } else {
                writeTypeAndArgument(os, MT_NEGATIVE, -1 - val);
            }
        } else if (value instanceof Long) {
            long val = (Long) value;
            if (val >= 0) {
                writeTypeAndArgument(os, MT_UNSIGNED, val);
            } else {
                writeTypeAndArgument(os, MT_NEGATIVE, -1 - val);
            }
        } else if (value instanceof String) {
            byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            writeTypeAndArgument(os, MT_TEXT_STRING, bytes.length);
            os.write(bytes);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            writeTypeAndArgument(os, MT_BYTE_STRING, bytes.length);
            os.write(bytes);
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            writeTypeAndArgument(os, MT_ARRAY, list.size());
            for (Object item : list) {
                encodeItem(os, item);
            }
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            writeTypeAndArgument(os, MT_MAP, map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                encodeItem(os, entry.getKey());
                encodeItem(os, entry.getValue());
            }
        } else if (value instanceof Boolean) {
            writeTypeAndArgument(os, MT_SIMPLE, (Boolean) value ? 21 : 20);
        } else {
            throw new IllegalArgumentException("Unsupported CBOR type: " + value.getClass().getName());
        }
    }

    private static void writeTypeAndArgument(ByteArrayOutputStream os, int majorType, long value) {
        int mt = majorType << 5;
        if (value < 24) {
            os.write(mt | (int) value);
        } else if (value <= 0xFF) {
            os.write(mt | 24);
            os.write((int) value);
        } else if (value <= 0xFFFF) {
            os.write(mt | 25);
            os.write((int) (value >> 8));
            os.write((int) (value & 0xFF));
        } else if (value <= 0xFFFFFFFFL) {
            os.write(mt | 26);
            os.write((int) (value >> 24));
            os.write((int) (value >> 16));
            os.write((int) (value >> 8));
            os.write((int) (value & 0xFF));
        } else {
            os.write(mt | 27);
            os.write((int) (value >> 56));
            os.write((int) (value >> 48));
            os.write((int) (value >> 40));
            os.write((int) (value >> 32));
            os.write((int) (value >> 24));
            os.write((int) (value >> 16));
            os.write((int) (value >> 8));
            os.write((int) (value & 0xFF));
        }
    }
}
