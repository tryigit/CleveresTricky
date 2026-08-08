package cleveres.tricky.cleverestech.keystore;

import android.security.keystore.KeyProperties;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import cleveres.tricky.cleverestech.Config;
import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.UtilKt;

public final class CertHack {
    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    private static final int MAX_KEYBOXES_PER_FILE = 64;
    private static final int MAX_KEYS_PER_KEYBOX = 4;
    private static final int MAX_CERTIFICATES_PER_CHAIN = 16;
    private static final int MAX_PEM_CHARS = 256 * 1024;
    private static final int MAX_CERTIFICATE_CACHE_ENTRIES = 128;
    private static final int MAX_LEAF_CERTIFICATE_BYTES = 64 * 1024;
    private static final int MAX_ATTESTATION_EXTENSION_BYTES = 64 * 1024;
    private static final String[] ATTESTATION_ID_NAMES =
            {"BRAND", "DEVICE", "PRODUCT", "SERIAL", "IMEI", "MEID", "MANUFACTURER", "MODEL"};
    private static final int[] ATTESTATION_ID_TAGS = {710, 711, 712, 713, 714, 715, 716, 717};
    private static final Comparator<ASN1TaggedObject> TAG_COMPARATOR =
            Comparator.comparingInt(ASN1TaggedObject::getTagNo);

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
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST =
            new ThreadLocal<MessageDigest>() {
                @Override
                protected MessageDigest initialValue() {
                    try {
                        return MessageDigest.getInstance("SHA-256");
                    } catch (Exception e) {
                        throw new IllegalStateException("SHA-256 digest is unavailable", e);
                    }
                }
            };

    private static class State {
        final Map<String, List<KeyBox>> keyboxes;
        final Map<String, List<KeyBox>> keyboxFiles;
        final Map<CacheKey, Certificate[]> certificateCache;

        State(Map<String, List<KeyBox>> keyboxes, Map<String, List<KeyBox>> keyboxFiles) {
            this.keyboxes = immutableLists(keyboxes);
            this.keyboxFiles = immutableLists(keyboxFiles);
            this.certificateCache = Collections.synchronizedMap(
                    new LinkedHashMap<CacheKey, Certificate[]>(64, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<CacheKey, Certificate[]> eldest) {
                            return size() > MAX_CERTIFICATE_CACHE_ENTRIES;
                        }
                    });
        }

        private static Map<String, List<KeyBox>> immutableLists(Map<String, List<KeyBox>> source) {
            Map<String, List<KeyBox>> copy = new HashMap<>();
            source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Map.copyOf(copy);
        }
    }

    private static volatile State state = new State(Collections.emptyMap(), Collections.emptyMap());
    private static final Map<String, AtomicInteger> rotationCounters = new ConcurrentHashMap<>();

    static {
        rotationCounters.put(KeyProperties.KEY_ALGORITHM_EC, new AtomicInteger(0));
        rotationCounters.put(KeyProperties.KEY_ALGORITHM_RSA, new AtomicInteger(0));
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public static boolean canHack() {
        return !state.keyboxes.isEmpty();
    }

    public static int getKeyboxCount() {
        int count = 0;
        for (List<KeyBox> list : state.keyboxes.values()) {
            count += list.size();
        }
        return count;
    }

    private static KeyPair parseKeyPair(String key, PublicKey leafPublicKey) throws Throwable {
        try (PEMParser parser = new PEMParser(new StringReader(UtilKt.trimLine(key)))) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pemKeyPair) {
                return converter.getKeyPair(pemKeyPair);
            }
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
            return CERTIFICATE_FACTORY.get().generateCertificate(
                    new ByteArrayInputStream(pemObject.getContent()));
        }
    }

    private static byte[] getByteArrayFromAsn1(ASN1Encodable asn1Encodable) throws CertificateParsingException {
        if (!(asn1Encodable instanceof DEROctetString derOctectString)) {
            throw new CertificateParsingException("Expected DEROctetString");
        }
        return derOctectString.getOctets();
    }

    /** Stores a fixed-size leaf digest so cache keys do not retain full certificates. */
    private static final class CacheKey {
        private final byte[] leafDigest;
        private final Config.AttestationPatchLevels patchLevels;
        private final int uid;
        private final int hashCode;

        public CacheKey(byte[] leafEncoded, Config.AttestationPatchLevels patchLevels, int uid) {
            this.leafDigest = SHA256_DIGEST.get().digest(leafEncoded);
            this.patchLevels = Objects.requireNonNull(patchLevels, "patchLevels");
            this.uid = uid;
            this.hashCode = 31 * (31 * Arrays.hashCode(leafDigest) + patchLevels.hashCode()) + uid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return uid == cacheKey.uid && patchLevels.equals(cacheKey.patchLevels) &&
                    MessageDigest.isEqual(leafDigest, cacheKey.leafDigest);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static boolean replacesOriginal(Config.AttestationPatchComponent component) {
        return component.getDisposition() != Config.PatchDisposition.KEEP;
    }

    private static void addPatchTag(
            List<ASN1TaggedObject> tags,
            int tag,
            Config.AttestationPatchComponent component
    ) {
        if (component.getDisposition() == Config.PatchDisposition.REPLACE && component.getValue() > 0) {
            tags.add(new DERTaggedObject(true, tag, new ASN1Integer(component.getValue())));
        }
    }

    public static List<KeyBox> parseKeyboxXml(Reader reader) {
        return parseKeyboxXml(reader, "unknown.xml");
    }

    public static List<KeyBox> parseKeyboxXml(Reader reader, String filename) {
        if (reader == null) return Collections.emptyList();
        List<KeyBox> parsedList = new ArrayList<>();
        try {
            XMLParser xmlParser = new XMLParser(reader);
            XMLParser.Element root = xmlParser.getRoot();

            if (root == null || !"AndroidAttestation".equals(root.name)) {
                return Collections.emptyList();
            }

            XMLParser.Element numKeyboxes = root.getChild("NumberOfKeyboxes");
            if (numKeyboxes == null || numKeyboxes.getText() == null) {
                return Collections.emptyList();
            }

            List<XMLParser.Element> keyboxes = root.getChildren("Keybox");
            int declaredKeyboxes = Integer.parseInt(Objects.requireNonNull(numKeyboxes.getText()));
            if (declaredKeyboxes < 1 || declaredKeyboxes > MAX_KEYBOXES_PER_FILE ||
                    keyboxes.size() != declaredKeyboxes) {
                Logger.e("Keybox count is invalid or does not match the XML declaration");
                return Collections.emptyList();
            }

            for (XMLParser.Element keybox : keyboxes) {
                List<XMLParser.Element> keys = keybox.getChildren("Key");
                if (keys.isEmpty() || keys.size() > MAX_KEYS_PER_KEYBOX) {
                    return Collections.emptyList();
                }
                for (XMLParser.Element key : keys) {
                    String keyboxAlgorithm = key.attributes.get("algorithm");

                    XMLParser.Element privateKeyElement = key.getChild("PrivateKey");
                    String privateKey = privateKeyElement != null ? privateKeyElement.getText() : null;
                    if (privateKey == null || privateKey.length() > MAX_PEM_CHARS) {
                        return Collections.emptyList();
                    }

                    XMLParser.Element certChain = key.getChild("CertificateChain");
                    if (certChain == null) return Collections.emptyList();

                    XMLParser.Element numCertsElement = certChain.getChild("NumberOfCertificates");
                    if (numCertsElement == null || numCertsElement.getText() == null) {
                        return Collections.emptyList();
                    }

                    int numberOfCertificates = Integer.parseInt(Objects.requireNonNull(numCertsElement.getText()));
                    if (numberOfCertificates < 1 || numberOfCertificates > MAX_CERTIFICATES_PER_CHAIN) {
                        return Collections.emptyList();
                    }

                    List<XMLParser.Element> certificates = certChain.getChildren("Certificate");
                    if (certificates.size() != numberOfCertificates) {
                        Logger.e("Keybox certificate count does not match its declaration");
                        return Collections.emptyList();
                    }
                    LinkedList<Certificate> certificateChain = new LinkedList<>();
                    for (int j = 0; j < numberOfCertificates; j++) {
                        String certPem = certificates.get(j).getText();
                        if (certPem == null || certPem.length() > MAX_PEM_CHARS) {
                            certificateChain.clear();
                            break;
                        }
                        certificateChain.add(parseCert(certPem));
                    }
                    if (certificateChain.size() != numberOfCertificates) {
                        return Collections.emptyList();
                    }

                    KeyPair kp = parseKeyPair(privateKey, certificateChain.getFirst().getPublicKey());
                    if (isValidKeybox(kp, certificateChain, keyboxAlgorithm)) {
                        parsedList.add(new KeyBox(kp, certificateChain, filename));
                    } else {
                        return Collections.emptyList();
                    }
                }
            }
            return parsedList;
        } catch (Throwable t) {
            Logger.e("Error parsing xml: " + t.getClass().getName());
        }
        return Collections.emptyList();
    }

    private static boolean isValidKeybox(
            KeyPair keyPair,
            List<Certificate> certificateChain,
            String declaredAlgorithm
    ) {
        try {
            if (keyPair == null || certificateChain.isEmpty() ||
                    !(certificateChain.get(0) instanceof X509Certificate leaf)) {
                return false;
            }
            String actualAlgorithm = keyPair.getPublic().getAlgorithm();
            if (!(actualAlgorithm.equalsIgnoreCase("EC") ||
                    actualAlgorithm.equalsIgnoreCase("ECDSA") ||
                    actualAlgorithm.equalsIgnoreCase("RSA"))) {
                return false;
            }
            if (declaredAlgorithm == null ||
                    !(declaredAlgorithm.equalsIgnoreCase(actualAlgorithm) ||
                            (declaredAlgorithm.equalsIgnoreCase("ecdsa") && actualAlgorithm.equalsIgnoreCase("EC")))) {
                return false;
            }
            if (!Arrays.equals(keyPair.getPublic().getEncoded(), leaf.getPublicKey().getEncoded())) {
                return false;
            }
            Signature proof = Signature.getInstance(
                    actualAlgorithm.equalsIgnoreCase("RSA") ? "SHA256withRSA" : "SHA256withECDSA");
            byte[] challenge = "CleveresTricky keybox validation".getBytes(StandardCharsets.UTF_8);
            proof.initSign(keyPair.getPrivate());
            proof.update(challenge);
            byte[] signature = proof.sign();
            proof.initVerify(leaf.getPublicKey());
            proof.update(challenge);
            if (!proof.verify(signature)) return false;
            for (int i = 0; i < certificateChain.size(); i++) {
                if (!(certificateChain.get(i) instanceof X509Certificate certificate)) return false;
                certificate.checkValidity();
                if (i + 1 < certificateChain.size()) {
                    certificate.verify(certificateChain.get(i + 1).getPublicKey());
                }
            }
            return true;
        } catch (Exception e) {
            Logger.e("Keybox cryptographic validation failed: " + e.getClass().getSimpleName());
            return false;
        }
    }

    public static void setKeyboxes(List<KeyBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            Logger.i("clear all keyboxes");
            state = new State(Collections.emptyMap(), Collections.emptyMap());
            return;
        }

        Map<String, List<KeyBox>> newKeyboxes = new HashMap<>();
        Map<String, List<KeyBox>> newKeyboxFiles = new HashMap<>();

        for (KeyBox box : boxes) {
            String algo = box.keyPair.getPublic().getAlgorithm();
            if ("ECDSA".equalsIgnoreCase(algo) || "EC".equalsIgnoreCase(algo)) {
                algo = KeyProperties.KEY_ALGORITHM_EC;
            } else if (KeyProperties.KEY_ALGORITHM_RSA.equalsIgnoreCase(algo)) {
                algo = KeyProperties.KEY_ALGORITHM_RSA;
            } else {
                Logger.e("Ignoring unsupported keybox algorithm: " + algo);
                continue;
            }
            newKeyboxes.computeIfAbsent(algo, k -> new ArrayList<>()).add(box);
            newKeyboxFiles.computeIfAbsent(box.filename, k -> new ArrayList<>()).add(box);
        }

        int ecCount = newKeyboxes.getOrDefault(KeyProperties.KEY_ALGORITHM_EC, Collections.emptyList()).size();
        int rsaCount = newKeyboxes.getOrDefault(KeyProperties.KEY_ALGORITHM_RSA, Collections.emptyList()).size();
        Logger.i("update keyboxes: total=" + boxes.size() + " (EC=" + ecCount + ", RSA=" + rsaCount + ")");

        state = new State(newKeyboxes, newKeyboxFiles);
    }

    public static void readFromXml(Reader reader) {
        if (reader == null) {
            setKeyboxes(Collections.emptyList());
            return;
        }
        setKeyboxes(parseKeyboxXml(reader));
    }

    public static void clearCertificateCache() {
        State currentState = state;
        synchronized (currentState.certificateCache) {
            currentState.certificateCache.clear();
        }
    }

    public static Certificate[] hackCertificateChain(Certificate[] caList, int uid) {
        if (caList == null || caList.length == 0 || caList[0] == null) {
            throw new UnsupportedOperationException("Certificate chain is empty");
        }
        try {
            State currentState = state;
            byte[] leafEncoded = caList[0].getEncoded();
            if (leafEncoded.length == 0 || leafEncoded.length > MAX_LEAF_CERTIFICATE_BYTES) {
                Logger.e("Attestation leaf certificate has an invalid size");
                return caList;
            }
            Config.AttestationPatchLevels patchLevels = Config.INSTANCE.getAttestationPatchLevels(uid);
            CacheKey cacheKey = new CacheKey(leafEncoded, patchLevels, uid);

            Map<CacheKey, Certificate[]> cache = currentState.certificateCache;
            synchronized (cache) {
                Certificate[] cached = cache.get(cacheKey);
                if (cached != null) return cached.clone();
            }

            // Optimization: Avoid redundant parsing if already X509Certificate
            X509Certificate leaf;
            if (caList[0] instanceof X509Certificate) {
                leaf = (X509Certificate) caList[0];
            } else {
                leaf = (X509Certificate) CERTIFICATE_FACTORY.get().generateCertificate(
                        new ByteArrayInputStream(leafEncoded));
            }

            byte[] bytes = leaf.getExtensionValue(OID.getId());
            if (bytes == null) return caList;
            if (bytes.length > MAX_ATTESTATION_EXTENSION_BYTES) {
                Logger.e("Attestation extension exceeds the safety limit");
                return caList;
            }

            // Optimization: Use original encoded bytes to avoid copy/re-encoding
            X509CertificateHolder leafHolder = new X509CertificateHolder(leafEncoded);
            Extension ext = leafHolder.getExtension(OID);
            if (ext == null || ext.getExtnValue() == null) {
                Logger.e("Attestation extension present but holder returned null; skipping rewrite");
                return caList;
            }
            ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            if (encodables.length <= 7 || !(encodables[7] instanceof ASN1Sequence)) {
                Logger.e("Attestation record is missing the TEE-enforced authorization list");
                return caList;
            }
            ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];

            // List to collect all tags for sorting
            List<ASN1TaggedObject> allTags = new ArrayList<>();
            ASN1Encodable rootOfTrust = null;
            byte[] moduleHash = Config.INSTANCE.getModuleHash();

            // Check for ID Attestation overrides
            Map<Integer, byte[]> idAttestationTags = new HashMap<>();
            boolean hasIdAttestation = false;

            for (int i = 0; i < ATTESTATION_ID_NAMES.length; i++) {
                byte[] val = Config.INSTANCE.getAttestationId(ATTESTATION_ID_NAMES[i], uid);
                if (val != null) {
                    idAttestationTags.put(ATTESTATION_ID_TAGS[i], val);
                    hasIdAttestation = true;
                }
            }

            for (ASN1Encodable asn1Encodable : teeEnforced) {
                // Skip non-tagged elements gracefully: some OEM implementations may inject
                // unexpected element types. Silently skipping is safer than crashing the
                // entire attestation chain for one malformed element.
                if (!(asn1Encodable instanceof ASN1TaggedObject taggedObject)) {
                    Logger.e("Unexpected ASN1 element type in TEE enforced: " + asn1Encodable.getClass().getName());
                    continue;
                }
                int tag = taggedObject.getTagNo();
                if (tag == 704) {
                    rootOfTrust = taggedObject.getBaseObject().toASN1Primitive();
                    continue;
                }
                // Component policy decides whether each genuine patch tag is
                // preserved, replaced, or omitted. Unknown tags remain intact.
                if ((tag == 724 && moduleHash != null) ||
                        (tag == 706 && replacesOriginal(patchLevels.getSystem())) ||
                        (tag == 718 && replacesOriginal(patchLevels.getVendor())) ||
                        (tag == 719 && replacesOriginal(patchLevels.getBoot()))) {
                    continue;
                }
                // Filter ID Attestation tags ONLY if we are overriding THAT specific tag
                // If hasIdAttestation is true but idAttestationTags doesn't have this tag, we KEEP it (Patch strategy)
                if (hasIdAttestation && idAttestationTags.containsKey(tag)) {
                    continue;
                }
                allTags.add(taggedObject);
            }

            addPatchTag(allTags, 706, patchLevels.getSystem());
            addPatchTag(allTags, 718, patchLevels.getVendor());
            addPatchTag(allTags, 719, patchLevels.getBoot());

            // Add spoofed ID Attestation tags
            if (hasIdAttestation) {
                for (Map.Entry<Integer, byte[]> entry : idAttestationTags.entrySet()) {
                    allTags.add(new DERTaggedObject(true, entry.getKey(), new DEROctetString(entry.getValue())));
                }
            }

            if (moduleHash != null) {
                allTags.add(new DERTaggedObject(true, 724, new DEROctetString(moduleHash)));
            }

            LinkedList<Certificate> certificates;
            X509v3CertificateBuilder builder;
            ContentSigner signer;

            String leafAlgo = normalizeAlgorithm(leaf.getPublicKey().getAlgorithm());
            if (leafAlgo == null) {
                Logger.e("Unsupported attestation leaf algorithm");
                return caList;
            }

            // App-specific keybox selection
            List<KeyBox> list = null;
            var appConfig = Config.INSTANCE.getAppConfig(uid);
            if (appConfig != null && appConfig.getKeyboxFilename() != null) {
                list = filterKeyboxesByAlgorithm(
                        currentState.keyboxFiles.get(appConfig.getKeyboxFilename()), leafAlgo);
                if (list == null || list.isEmpty()) {
                    throw new UnsupportedOperationException("App-requested keybox is unavailable " +
                            "or does not support " + leafAlgo);
                }
            } else {
                list = currentState.keyboxes.get(leafAlgo);
            }

            if (list == null || list.isEmpty())
                throw new UnsupportedOperationException("unsupported algorithm " + leafAlgo);

            int idx = (rotationCounters.get(leafAlgo).getAndIncrement() & 0x7FFFFFFF) % list.size();
            var k = list.get(idx);

            certificates = new LinkedList<>(k.certificates);
            if (certificates.isEmpty()) {
                throw new UnsupportedOperationException("Keybox has no certificates for algorithm " + leafAlgo);
            }
            builder = new X509v3CertificateBuilder(
                    new X509CertificateHolder(
                            certificates.get(0).getEncoded()
                    ).getSubject(),
                    leafHolder.getSerialNumber(),
                    leafHolder.getNotBefore(),
                    leafHolder.getNotAfter(),
                    leafHolder.getSubject(),
                    leafHolder.getSubjectPublicKeyInfo()
            );
            signer = new JcaContentSignerBuilder(leaf.getSigAlgName())
                    .build(k.keyPair.getPrivate());

            byte[] verifiedBootKey = UtilKt.getBootKey();
            byte[] verifiedBootHash = null;
            try {
                if (rootOfTrust == null || !(rootOfTrust instanceof ASN1Sequence r)) {
                    throw new CertificateParsingException("Expected sequence for root of trust, found "
                            + (rootOfTrust == null ? "null" : rootOfTrust.getClass().getName()));
                }
                if (verifiedBootKey == null) {
                    verifiedBootKey = getByteArrayFromAsn1(r.getObjectAt(0));
                }
                verifiedBootHash = getByteArrayFromAsn1(r.getObjectAt(3));
            } catch (Throwable t) {
                Logger.e("Failed to read the original root-of-trust fields", t);
            }

            if (verifiedBootHash == null) {
                verifiedBootHash = UtilKt.getBootHash();
            }
            if (verifiedBootKey == null || verifiedBootHash == null) {
                Logger.e("Verified boot key/hash is unavailable; preserving the original certificate chain");
                return caList;
            }

            ASN1Encodable[] rootOfTrustEnc = {
                    new DEROctetString(verifiedBootKey),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(verifiedBootHash)
            };

            ASN1Sequence hackedRootOfTrust = new DERSequence(rootOfTrustEnc);
            ASN1TaggedObject rootOfTrustTagObj = new DERTaggedObject(704, hackedRootOfTrust);
            allTags.add(rootOfTrustTagObj);

            // Sort tags by tag number to ensure ASN.1 compliance (and correct order for injection)
            allTags.sort(TAG_COMPARATOR);

            ASN1EncodableVector vector = new ASN1EncodableVector();
            for(ASN1TaggedObject t : allTags) vector.add(t);

            ASN1Sequence hackEnforced = new DERSequence(vector);
            encodables[7] = hackEnforced;
            ASN1Sequence hackedSeq = new DERSequence(encodables);

            ASN1OctetString hackedSeqOctets = new DEROctetString(hackedSeq);
            Extension hackedExt = new Extension(OID, false, hackedSeqOctets);
            // builder.addExtension(hackedExt); // Replaced by in-place loop below

            for (ASN1ObjectIdentifier extensionOID : leafHolder.getExtensions().getExtensionOIDs()) {
                if (OID.getId().equals(extensionOID.getId())) {
                     builder.addExtension(hackedExt);
                } else {
                     builder.addExtension(leafHolder.getExtension(extensionOID));
                }
            }
            certificates.addFirst(new JcaX509CertificateConverter().getCertificate(builder.build(signer)));

            Certificate[] result = certificates.toArray(new Certificate[0]);
            synchronized (cache) {
                cache.put(cacheKey, result.clone());
            }
            return result;

        } catch (Throwable t) {
            Logger.e("Exception in hackCertificateChain", t);
        }
        return caList;
    }

    private static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) return null;
        if (algorithm.equalsIgnoreCase("EC") || algorithm.equalsIgnoreCase("ECDSA")) {
            return KeyProperties.KEY_ALGORITHM_EC;
        }
        if (algorithm.equalsIgnoreCase("RSA")) return KeyProperties.KEY_ALGORITHM_RSA;
        return null;
    }

    private static List<KeyBox> filterKeyboxesByAlgorithm(
            List<KeyBox> candidates,
            String requiredAlgorithm
    ) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<KeyBox> matches = new ArrayList<>();
        for (KeyBox candidate : candidates) {
            if (requiredAlgorithm.equals(normalizeAlgorithm(
                    candidate.keyPair.getPublic().getAlgorithm()))) {
                matches.add(candidate);
            }
        }
        return matches;
    }


    public record KeyBox(KeyPair keyPair, List<Certificate> certificates, String filename) {
        public KeyBox {
            Objects.requireNonNull(keyPair, "keyPair");
            certificates = List.copyOf(Objects.requireNonNull(certificates, "certificates"));
            filename = Objects.requireNonNull(filename, "filename");
        }
    }


}
