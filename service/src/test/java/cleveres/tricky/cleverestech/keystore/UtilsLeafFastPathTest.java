package cleveres.tricky.cleverestech.keystore;

import android.system.keystore2.KeyMetadata;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import cleveres.tricky.cleverestech.TestKeyboxFixtures;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

public class UtilsLeafFastPathTest {
    @Test
    public void leafOnlyParserIgnoresUnusedIssuerBytes() throws Exception {
        String pem = TestKeyboxFixtures.INSTANCE.getCertificate();
        X509Certificate expected = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));

        KeyMetadata metadata = new KeyMetadata();
        metadata.certificate = expected.getEncoded();
        metadata.certificateChain = new byte[] {0x01, 0x02, 0x03, 0x04};

        X509Certificate leaf = Utils.getLeafCertificate(metadata);

        assertNotNull(leaf);
        assertArrayEquals(expected.getEncoded(), leaf.getEncoded());
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04}, metadata.certificateChain);
    }
}
