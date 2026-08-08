package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import java.io.StringReader;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class ConcurrencyTest {

    private static final String VALID_XML = "<?xml version=\"1.0\"?>\n" +
            "<AndroidAttestation>\n" +
            "<NumberOfKeyboxes>1</NumberOfKeyboxes>\n" +
            "<Keybox>\n" +
            "<Key algorithm=\"ecdsa\">\n" +
            "<PrivateKey>\n" +
            TestKeyboxFixtures.INSTANCE.getEcPrivateKey() + "\n" +
            "</PrivateKey>\n" +
            "<CertificateChain>\n" +
            "<NumberOfCertificates>1</NumberOfCertificates>\n" +
            "<Certificate>\n" +
            TestKeyboxFixtures.INSTANCE.getCertificate() + "\n" +
            "</Certificate>\n" +
            "</CertificateChain>\n" +
            "</Key>\n" +
            "</Keybox>\n" +
            "</AndroidAttestation>";

    @Test
    public void testKeyboxesConcurrency() throws InterruptedException {
        // Initialize with valid keybox
        CertHack.readFromXml(new StringReader(VALID_XML));
        assertTrue(CertHack.canHack());

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicBoolean failed = new AtomicBoolean(false);

        Thread reader = new Thread(() -> {
            while (running.get()) {
                if (!CertHack.canHack()) {
                    failed.set(true);
                    // running.set(false); // Don't stop immediately to stress more
                }
                // Also could try hackCertificateChain if I could mock args, but canHack() checks !keyboxes.isEmpty()
                // If readFromXml clears it, canHack() returns false.
            }
        });

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                CertHack.readFromXml(new StringReader(VALID_XML));
                try { Thread.sleep(1); } catch (InterruptedException e) {}
            }
            running.set(false);
        });

        reader.start();
        writer.start();

        reader.join();
        writer.join();

        assertFalse("CertHack.canHack() returned false during reload (race condition)", failed.get());
    }
}
