package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;

public class CertHackTest {

    private static final String EC_KEY = "-----BEGIN EC PRIVATE KEY-----\n" +
            "MHcCAQEEIAcPs+YkQGT6EDkaEH6Z9StSR7mQuKnh49K0DVqB/ZxYoAoGCCqGSM49\n" +
            "AwEHoUQDQgAEzi23gXvUATkDmPcNPgsqe24eWmSIfuteSk8S5wJxs4ABt+O6QGAO\n" +
            "XHqvCjNpJSbUxgz3SZefi8TWWQ1t32G/1w==\n" +
            "-----END EC PRIVATE KEY-----";

    private static final String TEST_CERT = "-----BEGIN CERTIFICATE-----\n" +
            "MIIBfTCCASOgAwIBAgIUBZ47iWGUbx00hmWBPTYkakbXnigwCgYIKoZIzj0EAwIw\n" +
            "FDESMBAGA1UEAwwJVGVzdCBDZXJ0MB4XDTI2MDEyOTIxNTI0M1oXDTI3MDEyNDIx\n" +
            "NTI0M1owFDESMBAGA1UEAwwJVGVzdCBDZXJ0MFkwEwYHKoZIzj0CAQYIKoZIzj0D\n" +
            "AQcDQgAEzi23gXvUATkDmPcNPgsqe24eWmSIfuteSk8S5wJxs4ABt+O6QGAOXHqv\n" +
            "CjNpJSbUxgz3SZefi8TWWQ1t32G/16NTMFEwHQYDVR0OBBYEFCwifKyDaNaHtKvx\n" +
            "m+0eLn/LZoTaMB8GA1UdIwQYMBaAFCwifKyDaNaHtKvxm+0eLn/LZoTaMA8GA1Ud\n" +
            "EwEB/wQFMAMBAf8wCgYIKoZIzj0EAwIDSAAwRQIgT+CWCLXuIN5XY0c3mFN1p1FM\n" +
            "1KAiK9pMwjbHYxNxDmYCIQDXriCpaafMnkJIqGb8UsI5XlkQD0soXYP7hd9ymW/t\n" +
            "qg==\n" +
            "-----END CERTIFICATE-----";

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
}
