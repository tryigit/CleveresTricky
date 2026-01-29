package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;

public class CertHackTest {

    private static final String EC_KEY = "-----BEGIN EC PRIVATE KEY-----\n" +
            "MHcCAQEEIBN+UV4uBUNLhszEC84F0PYJ+KUJMTbdUgfbMbG/ayq0oAoGCCqGSM49\n" +
            "AwEHoUQDQgAEWXMGzZNcUHT4he/QEGCA8WB5LPVSJzpu0Bbqgkk5b3TmzlW0Emqu\n" +
            "SboYcnWjC9j+RpY0LhI22LR4U5AaFuklSw==\n" +
            "-----END EC PRIVATE KEY-----";

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
                "<NumberOfCertificates>0</NumberOfCertificates>\n" +
                "</CertificateChain>\n" +
                "</Key>\n" +
                "</Keybox>\n" +
                "</AndroidAttestation>";

        CertHack.readFromXml(new StringReader(xml));

        assertTrue(CertHack.canHack());
    }
}
