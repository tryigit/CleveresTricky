package cleveres.tricky.cleverestech.keystore;

import org.junit.After;
import org.junit.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class CertHackTest {

    @After
    public void tearDown() {
        ManagedKeyboxStateOracle.readFromXml(null);
    }

    private static final String EC_KEY = TestKeyboxFixtures.INSTANCE.getEcPrivateKey();
    private static final String TEST_CERT = TestKeyboxFixtures.INSTANCE.getCertificate();

    @Test
    public void testReadFromXml() {
        Logger.setImpl(new Logger.LogImpl() {
            @Override public void d(String tag, String msg) { /* no-op */ }
            @Override public void e(String tag, String msg) { /* no-op */ }
            @Override public void e(String tag, String msg, Throwable t) { /* no-op */ }
            @Override public void i(String tag, String msg) { /* no-op */ }
        });

        String xml = "<?xml version=\"1.0\"?>\n" +
                "<AndroidAttestation>\n" +
                "<NumberOfKeyboxes>1</NumberOfKeyboxes>\n" +
                "<Keybox>\n" +
                "<Key algorithm=\"ecdsa\">\n" +
                "<PrivateKey>\n" + EC_KEY + "\n</PrivateKey>\n" +
                "<CertificateChain>\n" +
                "<NumberOfCertificates>1</NumberOfCertificates>\n" +
                "<Certificate>\n" + TEST_CERT + "\n</Certificate>\n" +
                "</CertificateChain>\n" +
                "</Key>\n" +
                "</Keybox>\n" +
                "</AndroidAttestation>";

        ManagedKeyboxStateOracle.readFromXml(new StringReader(xml));

        assertTrue("Keybox should be loaded", CertHack.canHack());
    }

    @Test
    public void testMixedValidAndInvalidKeysRejectsWholeDocument() {
        String invalidKey =
                "<Key algorithm=\"ecdsa\">" +
                "<PrivateKey>not-a-private-key</PrivateKey>" +
                "<CertificateChain><NumberOfCertificates>1</NumberOfCertificates>" +
                "<Certificate>not-a-certificate</Certificate></CertificateChain>" +
                "</Key>";
        String mixedXml = TestKeyboxFixtures.INSTANCE.getValidEcKeyboxXml()
                .replace("</Keybox>", invalidKey + "</Keybox>");

        assertEquals(0, ManagedKeyboxStateOracle.parse(new StringReader(mixedXml), "mixed.xml").size());
    }

    @Test
    public void testAttestationIdOverridesRequireOriginalTag() {
        byte[] serial = "serial".getBytes(StandardCharsets.UTF_8);
        byte[] imei = "imei".getBytes(StandardCharsets.UTF_8);
        Map<Integer, byte[]> configured = new HashMap<>();
        configured.put(713, serial);
        configured.put(714, imei);

        Map<Integer, byte[]> selected =
                CertHack.selectPresentAttestationIdOverrides(configured, List.of(714, 716));

        assertEquals(1, selected.size());
        assertTrue(selected.containsKey(714));
        assertArrayEquals(imei, selected.get(714));
    }

    @Test
    public void testSigningKeyAlgorithmUsesCertificateSigner() {
        assertEquals("EC", CertHack.signingKeyAlgorithm("SHA256withECDSA"));
        assertEquals("RSA", CertHack.signingKeyAlgorithm("SHA256withRSA"));
        assertEquals(null, CertHack.signingKeyAlgorithm("Ed25519"));
    }

    @Test
    public void testVerifiedBootDigestSelectionWithFallback() {
        byte[] runtime = new byte[32];
        byte[] original = new byte[32];
        byte[] persistent = new byte[32];
        java.util.Arrays.fill(runtime, (byte) 0x11);
        java.util.Arrays.fill(original, (byte) 0x22);
        java.util.Arrays.fill(persistent, (byte) 0x33);

        assertSame(runtime, CertHack.selectVerifiedBootDigest(runtime, original, persistent));
        assertSame(original, CertHack.selectVerifiedBootDigest(null, original, persistent));
        assertSame(original, CertHack.selectVerifiedBootDigest(new byte[32], original, persistent));
        assertSame(persistent, CertHack.selectVerifiedBootDigest(null, null, persistent));
        assertSame(persistent, CertHack.selectVerifiedBootDigest(new byte[32], new byte[32], persistent));
        assertNull(CertHack.selectVerifiedBootDigest(null, null, null));
        assertNull(CertHack.selectVerifiedBootDigest(new byte[32], new byte[31], new byte[32]));
    }

    @Test
    public void testCertificateCacheClearInvalidatesInFlightPublicationEpoch() {
        Object capturedEpoch = CertHack.captureCertificateCacheEpochForTesting();
        assertTrue(CertHack.isCertificateCacheEpochCurrentForTesting(capturedEpoch));

        CertHack.clearCertificateCache();

        assertFalse(CertHack.isCertificateCacheEpochCurrentForTesting(capturedEpoch));
    }
}
