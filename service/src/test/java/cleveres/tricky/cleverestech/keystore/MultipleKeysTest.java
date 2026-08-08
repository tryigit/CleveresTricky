package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.assertEquals;
import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class MultipleKeysTest {

    private static final String EC_KEY = TestKeyboxFixtures.INSTANCE.getEcPrivateKey();
    private static final String TEST_CERT = TestKeyboxFixtures.INSTANCE.getCertificate();

    @Test
    public void testMultipleKeysInMultipleKeyboxes() {
        Logger.setImpl(new Logger.LogImpl() {
            @Override public void d(String tag, String msg) { }
            @Override public void e(String tag, String msg) { System.out.println("E/" + tag + ": " + msg); }
            @Override public void e(String tag, String msg, Throwable t) { System.out.println("E/" + tag + ": " + msg); t.printStackTrace(); }
            @Override public void i(String tag, String msg) { }
        });

        String xml = "<?xml version=\"1.0\"?>\n" +
                "<AndroidAttestation>\n" +
                "<NumberOfKeyboxes>2</NumberOfKeyboxes>\n" +
                "<Keybox>\n" +
                "  <Key algorithm=\"ecdsa\">\n" +
                "    <PrivateKey>\n" + EC_KEY + "\n</PrivateKey>\n" +
                "    <CertificateChain>\n" +
                "      <NumberOfCertificates>1</NumberOfCertificates>\n" +
                "      <Certificate>\n" + TEST_CERT + "\n</Certificate>\n" +
                "    </CertificateChain>\n" +
                "  </Key>\n" +
                "  <Key algorithm=\"ecdsa\">\n" +
                "    <PrivateKey>\n" + EC_KEY + "\n</PrivateKey>\n" +
                "    <CertificateChain>\n" +
                "      <NumberOfCertificates>1</NumberOfCertificates>\n" +
                "      <Certificate>\n" + TEST_CERT + "\n</Certificate>\n" +
                "    </CertificateChain>\n" +
                "  </Key>\n" +
                "</Keybox>\n" +
                "<Keybox>\n" +
                "  <Key algorithm=\"ecdsa\">\n" +
                "    <PrivateKey>\n" + EC_KEY + "\n</PrivateKey>\n" +
                "    <CertificateChain>\n" +
                "      <NumberOfCertificates>1</NumberOfCertificates>\n" +
                "      <Certificate>\n" + TEST_CERT + "\n</Certificate>\n" +
                "    </CertificateChain>\n" +
                "  </Key>\n" +
                "</Keybox>\n" +
                "</AndroidAttestation>";

        CertHack.readFromXml(new StringReader(xml));

        assertEquals("Should load 3 keys", 3, CertHack.getKeyboxCount());
    }
}
