package cleveres.tricky.cleverestech.keystore;

import android.system.keystore2.KeyMetadata;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import cleveres.tricky.cleverestech.TestKeyboxFixtures;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;

public class UtilsLeafFastPathTest {
    private static X509Certificate fixtureCertificate() throws Exception {
        String pem = TestKeyboxFixtures.INSTANCE.getCertificate();
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void leafOnlyParserIgnoresUnusedIssuerBytes() throws Exception {
        X509Certificate expected = fixtureCertificate();

        KeyMetadata metadata = new KeyMetadata();
        metadata.certificate = expected.getEncoded();
        metadata.certificateChain = new byte[] {0x01, 0x02, 0x03, 0x04};

        X509Certificate leaf = Utils.getLeafCertificate(metadata);

        assertNotNull(leaf);
        assertArrayEquals(expected.getEncoded(), leaf.getEncoded());
        assertArrayEquals(new byte[] {0x01, 0x02, 0x03, 0x04}, metadata.certificateChain);
    }

    @Test
    public void repeatedIssuerChainSerializationKeepsIdenticalOutput() throws Exception {
        X509Certificate certificate = fixtureCertificate();
        Certificate[] chain = new Certificate[] {certificate, certificate};
        byte[] expectedIssuer = certificate.getEncoded();

        KeyMetadata first = new KeyMetadata();
        KeyMetadata second = new KeyMetadata();

        Utils.putCertificateChain(first, chain);
        Utils.putCertificateChain(second, chain);

        assertArrayEquals(certificate.getEncoded(), first.certificate);
        assertArrayEquals(expectedIssuer, first.certificateChain);
        assertArrayEquals(first.certificate, second.certificate);
        assertArrayEquals(first.certificateChain, second.certificateChain);
    }
}
