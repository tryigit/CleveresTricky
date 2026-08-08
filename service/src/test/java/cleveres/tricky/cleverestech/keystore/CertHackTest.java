package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class CertHackTest {

    private static final String EC_KEY = TestKeyboxFixtures.INSTANCE.getEcPrivateKey();
    private static final String TEST_CERT = TestKeyboxFixtures.INSTANCE.getCertificate();

    @Test
    public void testReadFromXml() {
        // Setup Logger to print to stdout so we can see what happens
        Logger.setImpl(new Logger.LogImpl() {
            @Override public void d(String tag, String msg) { System.out.println("D/" + tag + ": " + msg); }
            @Override public void e(String tag, String msg) { System.out.println("E/" + tag + ": " + msg); }
            @Override public void e(String tag, String msg, Throwable t) { System.out.println("E/" + tag + ": " + msg); t.printStackTrace(); }
            @Override public void i(String tag, String msg) { System.out.println("I/" + tag + ": " + msg); }
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
}
