package cleveres.tricky.cleverestech.keystore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

public class CertHackSecondImeiTest {
    @Test
    public void secondImeiUsesKeyMintTag723() throws Exception {
        Field namesField = CertHack.class.getDeclaredField("ATTESTATION_ID_NAMES");
        Field tagsField = CertHack.class.getDeclaredField("ATTESTATION_ID_TAGS");
        namesField.setAccessible(true);
        tagsField.setAccessible(true);

        String[] names = (String[]) namesField.get(null);
        int[] tags = (int[]) tagsField.get(null);
        assertEquals(names.length, tags.length);

        int index = Arrays.asList(names).indexOf("IMEI2");
        assertTrue("IMEI2 must be part of the attestation ID mapping", index >= 0);
        assertEquals(723, tags[index]);
    }
}
