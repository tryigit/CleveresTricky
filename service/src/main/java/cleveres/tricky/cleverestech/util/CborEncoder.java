package cleveres.tricky.cleverestech.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

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

    public static void encodeItem(ByteArrayOutputStream os, Object value) throws IOException {
        if (value == null) {
            encodeTypeAndLength(os, MT_SIMPLE, 22); // null
        } else if (value instanceof Integer) {
            encodeInteger(os, (Integer) value);
        } else if (value instanceof Long) {
            encodeInteger(os, (Long) value);
        } else if (value instanceof String) {
            byte[] bytes = ((String) value).getBytes(StandardCharsets.UTF_8);
            encodeTypeAndLength(os, MT_TEXT_STRING, bytes.length);
            os.write(bytes);
        } else if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            encodeTypeAndLength(os, MT_BYTE_STRING, bytes.length);
            os.write(bytes);
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            encodeTypeAndLength(os, MT_ARRAY, list.size());
            for (Object item : list) {
                encodeItem(os, item);
            }
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            encodeTypeAndLength(os, MT_MAP, map.size());
            // Canonical CBOR requires sorting keys.
            // In RKP, keys can be Integers or Strings.
            // We'll collect entries and sort them.
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort((e1, e2) -> {
                Object k1 = e1.getKey();
                Object k2 = e2.getKey();
                // Compare Integers
                if (k1 instanceof Integer && k2 instanceof Integer) {
                    return Integer.compare((Integer) k1, (Integer) k2);
                }
                // Compare Strings
                if (k1 instanceof String && k2 instanceof String) {
                    return ((String) k1).compareTo((String) k2);
                }
                // Mixed keys: Int < String per standard CBOR canonical rules usually? 
                // RFC 7049: "If two keys have different types, the one with the lower major type sorts earlier."
                // Int is major 0/1, String is major 3. So Int comes first.
                if (k1 instanceof Integer && k2 instanceof String) return -1;
                if (k1 instanceof String && k2 instanceof Integer) return 1;
                
                // Fallback for negatives (which are Integers in our logic usually)
                return 0; 
            });
            
            for (Map.Entry<?, ?> entry : entries) {
                encodeItem(os, entry.getKey());
                encodeItem(os, entry.getValue());
            }
        } else if (value instanceof Boolean) {
            boolean b = (Boolean) value;
            encodeTypeAndLength(os, MT_SIMPLE, b ? 21 : 20);
        } else {
            throw new IOException("Unknown type in CborEncoder: " + value.getClass().getSimpleName());
        }
    }

    private static void encodeInteger(ByteArrayOutputStream os, long value) {
        if (value >= 0) {
            encodeTypeAndLength(os, MT_UNSIGNED, value);
        } else {
            encodeTypeAndLength(os, MT_NEGATIVE, -1 - value);
        }
    }

    private static void encodeTypeAndLength(ByteArrayOutputStream os, int majorType, long value) {
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
