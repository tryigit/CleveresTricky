package cleveres.tricky.cleverestech.keystore;

import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.UtilKt;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

/** Test-only managed/BC compatibility oracle. Never packaged in the production service APK. */
public final class ManagedKeyboxOracle {
    private static final int MAX_KEYBOXES_PER_FILE = 64;
    private static final int MAX_KEYS_PER_KEYBOX = 4;
    private static final int MAX_CERTIFICATES_PER_CHAIN = 16;
    private static final int MAX_PEM_CHARS = 256 * 1024;
    private static final ThreadLocal<CertificateFactory> CERTIFICATE_FACTORY =
            new ThreadLocal<CertificateFactory>() {
                @Override
                protected CertificateFactory initialValue() {
                    try {
                        return CertificateFactory.getInstance("X.509");
                    } catch (Exception e) {
                        throw new IllegalStateException("X.509 certificate factory is unavailable", e);
                    }
                }
            };

    private ManagedKeyboxOracle() {}

    public static List<CertHack.KeyBox> parse(Reader reader) {
        return parse(reader, "unknown.xml");
    }

    public static List<CertHack.KeyBox> parse(Reader reader, String filename) {
        return parse(reader, filename, false);
    }

    public static List<CertHack.KeyBox> parse(
            Reader reader,
            String filename,
            boolean authenticatedRkpProvenance) {
        if (reader == null) return Collections.emptyList();
        List<CertHack.KeyBox> parsedList = new ArrayList<>();
        try {
            XMLParser xmlParser = new XMLParser(reader);
            XMLParser.Element root = xmlParser.getRoot();
            if (root == null || (!"AndroidAttestation".equals(root.name) && !"Keybox".equals(root.name))) return Collections.emptyList();

            List<XMLParser.Element> keyboxes;
            if ("Keybox".equals(root.name)) {
                keyboxes = Collections.singletonList(root);
            } else {
                XMLParser.Element numKeyboxes = root.getChild("NumberOfKeyboxes");
                keyboxes = root.getChildren("Keybox");
                int declaredKeyboxes = numKeyboxes != null && numKeyboxes.getText() != null
                        ? Integer.parseInt(Objects.requireNonNull(numKeyboxes.getText()))
                        : keyboxes.size();
                if (declaredKeyboxes < 1 || declaredKeyboxes > MAX_KEYBOXES_PER_FILE || keyboxes.size() != declaredKeyboxes) {
                    return Collections.emptyList();
                }
            }

            for (XMLParser.Element keybox : keyboxes) {
                List<XMLParser.Element> keys = keybox.getChildren("Key");
                if (keys.isEmpty() || keys.size() > MAX_KEYS_PER_KEYBOX) return Collections.emptyList();
                for (XMLParser.Element key : keys) {
                    String declaredAlgorithm = key.attributes.get("algorithm");
                    XMLParser.Element privateKeyElement = key.getChild("PrivateKey");
                    String privateKey = privateKeyElement != null ? privateKeyElement.getText() : null;
                    if (privateKey == null || privateKey.length() > MAX_PEM_CHARS) return Collections.emptyList();

                    XMLParser.Element certChain = key.getChild("CertificateChain");
                    if (certChain == null) return Collections.emptyList();
                    List<XMLParser.Element> certificates = certChain.getChildren("Certificate");
                    XMLParser.Element numCertsElement = certChain.getChild("NumberOfCertificates");
                    int numberOfCertificates = numCertsElement != null && numCertsElement.getText() != null
                            ? Integer.parseInt(Objects.requireNonNull(numCertsElement.getText()))
                            : certificates.size();
                    if (numberOfCertificates < 1 || numberOfCertificates > MAX_CERTIFICATES_PER_CHAIN || certificates.size() != numberOfCertificates) {
                        return Collections.emptyList();
                    }

                    LinkedList<Certificate> certificateChain = new LinkedList<>();
                    for (int index = 0; index < numberOfCertificates; index++) {
                        String certPem = certificates.get(index).getText();
                        if (certPem == null || certPem.length() > MAX_PEM_CHARS) return Collections.emptyList();
                        certificateChain.add(parseCert(certPem));
                    }
                    KeyPair pair = parseKeyPair(privateKey, certificateChain.getFirst().getPublicKey());
                    if (!isValidKeybox(pair, certificateChain, declaredAlgorithm)) return Collections.emptyList();
                    parsedList.add(new CertHack.KeyBox(pair, certificateChain, filename, authenticatedRkpProvenance));
                }
            }
            return parsedList;
        } catch (Throwable error) {
            Logger.e("Managed keybox oracle rejected XML: " + error.getClass().getName());
            return Collections.emptyList();
        }
    }

    private static KeyPair parseKeyPair(String key, PublicKey leafPublicKey) throws Throwable {
        try (PEMParser parser = new PEMParser(new StringReader(UtilKt.trimLine(key)))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pemKeyPair) return converter.getKeyPair(pemKeyPair);
            if (parsed instanceof PrivateKeyInfo privateKeyInfo) {
                return new KeyPair(leafPublicKey, converter.getPrivateKey(privateKeyInfo));
            }
            throw new IOException("Unsupported private-key PEM object");
        }
    }

    private static Certificate parseCert(String cert) throws Throwable {
        try (PemReader reader = new PemReader(new StringReader(UtilKt.trimLine(cert)))) {
            var pemObject = reader.readPemObject();
            if (pemObject == null) throw new IOException("Certificate PEM is empty");
            return CERTIFICATE_FACTORY.get().generateCertificate(new ByteArrayInputStream(pemObject.getContent()));
        }
    }

    private static boolean isValidKeybox(
            KeyPair keyPair,
            List<Certificate> certificateChain,
            String declaredAlgorithm
    ) {
        try {
            if (keyPair == null || certificateChain.isEmpty() || !(certificateChain.get(0) instanceof X509Certificate leaf)) {
                return false;
            }
            String actualAlgorithm = keyPair.getPublic().getAlgorithm();
            if (!(actualAlgorithm.equalsIgnoreCase("EC") || actualAlgorithm.equalsIgnoreCase("ECDSA") ||
                    actualAlgorithm.equalsIgnoreCase("RSA"))) return false;
            if (declaredAlgorithm == null || !(declaredAlgorithm.equalsIgnoreCase(actualAlgorithm) ||
                    (declaredAlgorithm.equalsIgnoreCase("ecdsa") && actualAlgorithm.equalsIgnoreCase("EC")))) {
                return false;
            }
            if (!Arrays.equals(keyPair.getPublic().getEncoded(), leaf.getPublicKey().getEncoded())) return false;
            Signature proof = Signature.getInstance(
                    actualAlgorithm.equalsIgnoreCase("RSA") ? "SHA256withRSA" : "SHA256withECDSA");
            byte[] challenge = "CleveresTricky keybox validation".getBytes(StandardCharsets.UTF_8);
            proof.initSign(keyPair.getPrivate());
            proof.update(challenge);
            byte[] signature = proof.sign();
            try {
                proof.initVerify(leaf.getPublicKey());
                proof.update(challenge);
                if (!proof.verify(signature)) return false;
            } finally {
                Arrays.fill(signature, (byte) 0);
            }
            for (int index = 0; index < certificateChain.size(); index++) {
                if (!(certificateChain.get(index) instanceof X509Certificate certificate)) return false;
                certificate.checkValidity();
                if (index + 1 < certificateChain.size()) {
                    if (!(certificateChain.get(index + 1) instanceof X509Certificate issuerCert)) return false;
                    certificate.verify(issuerCert.getPublicKey());
                    if (issuerCert.getBasicConstraints() < 0) return false;
                    boolean[] keyUsage = issuerCert.getKeyUsage();
                    if (keyUsage != null && (keyUsage.length <= 5 || !keyUsage[5])) return false;
                }
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
