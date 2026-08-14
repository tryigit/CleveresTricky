package cleveres.tricky.cleverestech.keystore;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.junit.After;
import org.junit.Test;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class CertHackTest {

    @After
    public void tearDown() {
        CertHack.readFromXml(null);
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

        CertHack.readFromXml(new StringReader(xml));

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

        assertEquals(0, CertHack.parseKeyboxXml(new StringReader(mixedXml)).size());
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
    public void testLockedRootOfTrustIsStable() {
        byte[] key = new byte[32];
        byte[] hash = new byte[32];
        key[0] = 1;
        hash[0] = 2;
        ASN1Sequence root = CertHack.buildLockedRootOfTrust(key, hash);
        assertArrayEquals(key, ASN1OctetString.getInstance(root.getObjectAt(0)).getOctets());
        assertTrue(ASN1Boolean.getInstance(root.getObjectAt(1)).isTrue());
        assertEquals(0, ASN1Enumerated.getInstance(root.getObjectAt(2)).getValue().intValue());
        assertArrayEquals(hash, ASN1OctetString.getInstance(root.getObjectAt(3)).getOctets());
    }

    @Test
    public void testSigningKeyAlgorithmUsesCertificateSigner() {
        assertEquals("EC", CertHack.signingKeyAlgorithm("SHA256withECDSA"));
        assertEquals("RSA", CertHack.signingKeyAlgorithm("SHA256withRSA"));
        assertEquals(null, CertHack.signingKeyAlgorithm("Ed25519"));
    }
}
