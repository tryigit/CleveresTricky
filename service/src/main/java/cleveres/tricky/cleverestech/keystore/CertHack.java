package cleveres.tricky.cleverestech.keystore;

import android.content.pm.PackageManager;
import android.hardware.security.keymint.Algorithm;
import android.hardware.security.keymint.EcCurve;
import android.hardware.security.keymint.KeyParameter;
import android.hardware.security.keymint.KeyPurpose;
import android.hardware.security.keymint.Tag;
import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyDescriptor;
import android.util.Pair;

import androidx.annotation.Nullable;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DERNull;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import cleveres.tricky.cleverestech.Config;
import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.UtilKt;
import cleveres.tricky.cleverestech.util.CborEncoder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class CertHack {
    private static final ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
    
    // RKP additions
    private static final String RKP_MAC_KEY_ALGORITHM = "HmacSHA256";
    // LOCAL_HMAC_KEY removed - obtained from LocalRkpProxy


    private static final int ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX = 0;
    private static final int ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX = 1;
    private static final Map<String, KeyBox> keyboxes = new HashMap<>();
    private static final int ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX = 0;

    private static final CertificateFactory certificateFactory;

    static {
        try {
            certificateFactory = CertificateFactory.getInstance("X.509");
        } catch (Throwable t) {
            Logger.e("", t);
            throw new RuntimeException(t);
        }
    }

    private static final int ATTESTATION_PACKAGE_INFO_VERSION_INDEX = 1;

    public static boolean canHack() {
        return !keyboxes.isEmpty();
    }

    public static int getKeyboxCount() {
        return keyboxes.size();
    }

    private static PEMKeyPair parseKeyPair(String key) throws Throwable {
        try (PEMParser parser = new PEMParser(new StringReader(UtilKt.trimLine(key)))) {
            return (PEMKeyPair) parser.readObject();
        }
    }

    private static Certificate parseCert(String cert) throws Throwable {
        try (PemReader reader = new PemReader(new StringReader(UtilKt.trimLine(cert)))) {
            return certificateFactory.generateCertificate(new ByteArrayInputStream(reader.readPemObject().getContent()));
        }
    }

    private static byte[] getByteArrayFromAsn1(ASN1Encodable asn1Encodable) throws CertificateParsingException {
        if (!(asn1Encodable instanceof DEROctetString derOctectString)) {
            throw new CertificateParsingException("Expected DEROctetString");
        }
        return derOctectString.getOctets();
    }

    private static byte[] hexToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // Cache for hacked certificates: Leaf Encoded Bytes + Patch Level (int) -> Certificate[]
    private static final Map<CacheKey, Certificate[]> certificateCache = new HashMap<>();

    /**
     * Optimization: Use a custom key object to avoid expensive Base64 encoding
     * and large String allocations for cache lookups.
     * This saves ~33% memory per key and avoids O(N) encoding overhead.
     */
    private static final class CacheKey {
        private final byte[] leafEncoded;
        private final int patchLevel;
        private final int hashCode;

        public CacheKey(byte[] leafEncoded, int patchLevel) {
            this.leafEncoded = leafEncoded;
            this.patchLevel = patchLevel;
            this.hashCode = 31 * Arrays.hashCode(leafEncoded) + patchLevel;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CacheKey cacheKey = (CacheKey) o;
            return patchLevel == cacheKey.patchLevel && Arrays.equals(leafEncoded, cacheKey.leafEncoded);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    public static void readFromXml(Reader reader) {
        keyboxes.clear();
        certificateCache.clear();
        if (reader == null) {
            Logger.i("clear all keyboxes");
            return;
        }

        try {
            XMLParser xmlParser = new XMLParser(reader);
            int numberOfKeyboxes = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                    "AndroidAttestation.NumberOfKeyboxes").get("text")));
            for (int i = 0; i < numberOfKeyboxes; i++) {
                String keyboxAlgorithm = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "]").get("algorithm");
                String privateKey = xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "].PrivateKey").get("text");
                int numberOfCertificates = Integer.parseInt(Objects.requireNonNull(xmlParser.obtainPath(
                        "AndroidAttestation.Keybox.Key[" + i + "].CertificateChain.NumberOfCertificates").get("text")));

                LinkedList<Certificate> certificateChain = new LinkedList<>();
                for (int j = 0; j < numberOfCertificates; j++) {
                    String certPem = xmlParser.obtainPath(
                            "AndroidAttestation.Keybox.Key[" + i + "].CertificateChain.Certificate[" + j + "]").get("text");
                    certificateChain.add(parseCert(certPem));
                }

                String algo;
                if (keyboxAlgorithm != null && keyboxAlgorithm.equalsIgnoreCase("ecdsa")) {
                    algo = KeyProperties.KEY_ALGORITHM_EC;
                } else {
                    algo = KeyProperties.KEY_ALGORITHM_RSA;
                }
                var pemKp = parseKeyPair(privateKey);
                var kp = new JcaPEMKeyConverter().getKeyPair(pemKp);
                keyboxes.put(algo, new KeyBox(kp, certificateChain));
            }
            Logger.i("update " + numberOfKeyboxes + " keyboxes");
        } catch (Throwable t) {
            // Do not log the exception details as it might contain sensitive data from the keybox file.
            // Only log the exception type to avoid leaking private keys from XML snippets in the message.
            Logger.e("Error loading xml file (keyboxes cleared): " + t.getClass().getName());
        }
    }

    public static Certificate[] hackCertificateChain(Certificate[] caList, int uid) {
        if (caList == null) throw new UnsupportedOperationException("caList is null!");
        try {
            byte[] leafEncoded = caList[0].getEncoded();
            int patchLevel = Config.INSTANCE.getPatchLevel(uid);
            CacheKey cacheKey = new CacheKey(leafEncoded, patchLevel);

            synchronized (certificateCache) {
                 if (certificateCache.containsKey(cacheKey)) {
                     // Logger.d("Cache hit for uid=" + uid);
                     return certificateCache.get(cacheKey);
                 }
            }

            X509Certificate leaf = (X509Certificate) certificateFactory.generateCertificate(new ByteArrayInputStream(leafEncoded));
            byte[] bytes = leaf.getExtensionValue(OID.getId());
            if (bytes == null) return caList;

            X509CertificateHolder leafHolder = new X509CertificateHolder(leaf.getEncoded());
            Extension ext = leafHolder.getExtension(OID);
            ASN1Sequence sequence = ASN1Sequence.getInstance(ext.getExtnValue().getOctets());
            ASN1Encodable[] encodables = sequence.toArray();
            ASN1Sequence teeEnforced = (ASN1Sequence) encodables[7];
            ASN1EncodableVector vector = new ASN1EncodableVector();
            ASN1Encodable rootOfTrust = null;
            byte[] moduleHash = Config.INSTANCE.getModuleHash();
            boolean moduleHashAdded = false;

            for (ASN1Encodable asn1Encodable : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) asn1Encodable;
                int tag = taggedObject.getTagNo();
                if (tag == 704) {
                    rootOfTrust = taggedObject.getBaseObject().toASN1Primitive();
                    continue;
                }
                // Filter 724 (ModuleHash) and 706 (OS Patch Level)
                if (tag == 724 || tag == 706) {
                    continue;
                }
                vector.add(taggedObject);
            }
            // Add spoofed patch level
            vector.add(new DERTaggedObject(true, 706, new ASN1Integer(patchLevel)));

            if (moduleHash == null) {
                String moduleHashStr = Config.INSTANCE.getBuildVar("MODULE_HASH");
                if (moduleHashStr != null && !moduleHashStr.isEmpty()) {
                    try {
                        moduleHash = hexToByteArray(moduleHashStr);
                    } catch (Exception e) {
                        Logger.e("Failed to parse MODULE_HASH build var", e);
                    }
                }
            }

            if (moduleHash != null && !moduleHashAdded) {
                vector.add(new DERTaggedObject(true, 724, new DEROctetString(moduleHash)));
            }

            LinkedList<Certificate> certificates;
            X509v3CertificateBuilder builder;
            ContentSigner signer;

            var k = keyboxes.get(leaf.getPublicKey().getAlgorithm());
            if (k == null)
                throw new UnsupportedOperationException("unsupported algorithm " + leaf.getPublicKey().getAlgorithm());
            certificates = new LinkedList<>(k.certificates);
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
                if (!(rootOfTrust instanceof ASN1Sequence r)) {
                    throw new CertificateParsingException("Expected sequence for root of trust, found "
                            + rootOfTrust.getClass().getName());
                }
                verifiedBootHash = getByteArrayFromAsn1(r.getObjectAt(3));
            } catch (Throwable t) {
                Logger.e("failed to get verified boot key or hash from original, use randomly generated instead", t);
            }

            if (verifiedBootHash == null) {
                verifiedBootHash = UtilKt.getBootHash();
            }

            ASN1Encodable[] rootOfTrustEnc = {
                    new DEROctetString(verifiedBootKey),
                    ASN1Boolean.TRUE,
                    new ASN1Enumerated(0),
                    new DEROctetString(verifiedBootHash)
            };

            ASN1Sequence hackedRootOfTrust = new DERSequence(rootOfTrustEnc);
            ASN1TaggedObject rootOfTrustTagObj = new DERTaggedObject(704, hackedRootOfTrust);
            vector.add(rootOfTrustTagObj);

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
            synchronized (certificateCache) {
                certificateCache.put(cacheKey, result);
            }
            return result;

        } catch (Throwable t) {
            Logger.e("Exception in hackCertificateChain", t);
        }
        return caList;
    }

    public static Pair<KeyPair, List<Certificate>> generateKeyPair(int uid, KeyDescriptor descriptor, KeyGenParameters params) {
        return generateKeyPair(uid, descriptor, params, null, null);
    }

    public static Pair<KeyPair, List<Certificate>> generateKeyPair(int uid, KeyDescriptor descriptor, KeyGenParameters params,
                                                                   @Nullable KeyPair issuerKeyPair, @Nullable List<Certificate> issuerChain) {
        Logger.i("Requested KeyPair with alias: " + descriptor.alias);
        KeyPair rootKP;
        X500Name issuer;
        int size = params.keySize;
        KeyPair kp = null;
        KeyBox keyBox = null;
        try {
            var algo = params.algorithm;
            if (algo == Algorithm.EC) {
                Logger.d("GENERATING EC KEYPAIR OF SIZE " + size);
                kp = buildECKeyPair(params);
                keyBox = keyboxes.get(KeyProperties.KEY_ALGORITHM_EC);
            } else if (algo == Algorithm.RSA) {
                Logger.d("GENERATING RSA KEYPAIR OF SIZE " + size);
                kp = buildRSAKeyPair(params);
                keyBox = keyboxes.get(KeyProperties.KEY_ALGORITHM_RSA);
            }
            if (keyBox == null) {
                Logger.e("UNSUPPORTED ALGORITHM: " + algo);
                return null;
            }

            List<Certificate> signingChain;
            if (issuerKeyPair != null && issuerChain != null && !issuerChain.isEmpty()) {
                rootKP = issuerKeyPair;
                signingChain = issuerChain;
            } else {
                rootKP = keyBox.keyPair;
                signingChain = keyBox.certificates;
            }

            issuer = new X509CertificateHolder(
                    signingChain.get(0).getEncoded()
            ).getSubject();

            X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(issuer,
                    params.certificateSerial,
                    params.certificateNotBefore,
                    params.certificateNotAfter,
                    params.certificateSubject,
                    kp.getPublic()
            );

            KeyUsage keyUsage = new KeyUsage(KeyUsage.keyCertSign);
            certBuilder.addExtension(Extension.keyUsage, true, keyUsage);
            certBuilder.addExtension(createExtension(params, uid));

            ContentSigner contentSigner;
            String signingAlgo = rootKP.getPrivate().getAlgorithm();
            if ("EC".equalsIgnoreCase(signingAlgo) || "ECDSA".equalsIgnoreCase(signingAlgo)) {
                contentSigner = new JcaContentSignerBuilder("SHA256withECDSA").build(rootKP.getPrivate());
            } else {
                contentSigner = new JcaContentSignerBuilder("SHA256withRSA").build(rootKP.getPrivate());
            }
            X509CertificateHolder certHolder = certBuilder.build(contentSigner);
            var leaf = new JcaX509CertificateConverter().getCertificate(certHolder);
            List<Certificate> chain = new ArrayList<>(signingChain);
            chain.add(0, leaf);
            Logger.d("Successfully generated X500 Cert for alias: " + descriptor.alias);
            return new Pair<>(kp, chain);
        } catch (Throwable t) {
            Logger.e("", t);
        }
        return null;
    }

    private static KeyPair buildECKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());

        String algo = "ECDSA";
        String curveName = params.ecCurveName;

        if (params.ecCurve == EcCurve.CURVE_25519) {
            if (params.purpose.contains(KeyPurpose.SIGN) || params.purpose.contains(KeyPurpose.ATTEST_KEY)) {
                algo = "Ed25519";
                curveName = "Ed25519";
            } else {
                algo = "XDH";
                curveName = "X25519";
            }
        }

        ECGenParameterSpec spec = new ECGenParameterSpec(curveName);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(algo, BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static KeyPair buildRSAKeyPair(KeyGenParameters params) throws Exception {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        Security.addProvider(new BouncyCastleProvider());
        RSAKeyGenParameterSpec spec = new RSAKeyGenParameterSpec(
                params.keySize, params.rsaPublicExponent);
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME);
        kpg.initialize(spec);
        return kpg.generateKeyPair();
    }

    private static ASN1Encodable[] fromIntList(List<Integer> list) {
        ASN1Encodable[] result = new ASN1Encodable[list.size()];
        for (int i = 0; i < list.size(); i++) {
            result[i] = new ASN1Integer(list.get(i));
        }
        return result;
    }

    private static Extension createExtension(KeyGenParameters params, int uid) {
        try {
            byte[] key = UtilKt.getBootKey();
            byte[] hash = UtilKt.getBootHash();

            ASN1Encodable[] rootOfTrustEncodables = {new DEROctetString(key), ASN1Boolean.TRUE,
                    new ASN1Enumerated(0), new DEROctetString(hash)};

            ASN1Sequence rootOfTrustSeq = new DERSequence(rootOfTrustEncodables);

            var Apurpose = new DERSet(fromIntList(params.purpose));
            var Aalgorithm = new ASN1Integer(params.algorithm);
            var AkeySize = new ASN1Integer(params.keySize);
            var Adigest = new DERSet(fromIntList(params.digest));
            var AecCurve = new ASN1Integer(params.ecCurve);
            var AnoAuthRequired = DERNull.INSTANCE;

            // To be loaded
            var AosVersion = new ASN1Integer(UtilKt.getOsVersion());
            var AosPatchLevel = new ASN1Integer(Config.INSTANCE.getPatchLevel(uid));

            var AapplicationID = createApplicationId(uid);
            var AbootPatchlevel = new ASN1Integer(UtilKt.getPatchLevelLong());
            var AvendorPatchLevel = new ASN1Integer(UtilKt.getPatchLevelLong());

            var AcreationDateTime = new ASN1Integer(System.currentTimeMillis());
            var Aorigin = new ASN1Integer(0);

            var purpose = new DERTaggedObject(true, 1, Apurpose);
            var algorithm = new DERTaggedObject(true, 2, Aalgorithm);
            var keySize = new DERTaggedObject(true, 3, AkeySize);
            var digest = new DERTaggedObject(true, 5, Adigest);
            var ecCurve = new DERTaggedObject(true, 10, AecCurve);
            var noAuthRequired = new DERTaggedObject(true, 503, AnoAuthRequired);
            var creationDateTime = new DERTaggedObject(true, 701, AcreationDateTime);
            var origin = new DERTaggedObject(true, 702, Aorigin);
            var rootOfTrust = new DERTaggedObject(true, 704, rootOfTrustSeq);
            var osVersion = new DERTaggedObject(true, 705, AosVersion);
            var osPatchLevel = new DERTaggedObject(true, 706, AosPatchLevel);
            var applicationID = new DERTaggedObject(true, 709, AapplicationID);
            var vendorPatchLevel = new DERTaggedObject(true, 718, AvendorPatchLevel);
            var bootPatchLevel = new DERTaggedObject(true, 719, AbootPatchlevel);

            List<ASN1Encodable> teeEnforcedList = new ArrayList<>(Arrays.asList(
                    purpose, algorithm, keySize, digest, ecCurve,
                    noAuthRequired, origin, rootOfTrust, osVersion, osPatchLevel, vendorPatchLevel,
                    bootPatchLevel
            ));

            // Support device properties attestation
            if (params.brand != null) {
                var Abrand = new DEROctetString(params.brand);
                var Adevice = new DEROctetString(params.device);
                var Aproduct = new DEROctetString(params.product);
                var Amanufacturer = new DEROctetString(params.manufacturer);
                var Amodel = new DEROctetString(params.model);
                var brand = new DERTaggedObject(true, 710, Abrand);
                var device = new DERTaggedObject(true, 711, Adevice);
                var product = new DERTaggedObject(true, 712, Aproduct);
                var manufacturer = new DERTaggedObject(true, 716, Amanufacturer);
                var model = new DERTaggedObject(true, 717, Amodel);

                teeEnforcedList.addAll(Arrays.asList(brand, device, product, manufacturer, model));
            }

            byte[] moduleHash = Config.INSTANCE.getModuleHash();
            if (moduleHash == null) {
                String moduleHashStr = Config.INSTANCE.getBuildVar("MODULE_HASH");
                if (moduleHashStr != null && !moduleHashStr.isEmpty()) {
                    try {
                        moduleHash = hexToByteArray(moduleHashStr);
                    } catch (Exception e) {
                        Logger.e("Failed to parse MODULE_HASH build var", e);
                    }
                }
            }
            if (moduleHash != null) {
                teeEnforcedList.add(new DERTaggedObject(true, 724, new DEROctetString(moduleHash)));
            }

            teeEnforcedList.sort((a, b) -> {
                int tagA = ((ASN1TaggedObject) a).getTagNo();
                int tagB = ((ASN1TaggedObject) b).getTagNo();
                return Integer.compare(tagA, tagB);
            });

            ASN1Encodable[] teeEnforcedEncodables = teeEnforcedList.toArray(new ASN1Encodable[0]);

            ASN1Encodable[] softwareEnforced = {applicationID, creationDateTime};

            ASN1OctetString keyDescriptionOctetStr = getAsn1OctetString(teeEnforcedEncodables, softwareEnforced, params);

            return new Extension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false, keyDescriptionOctetStr);
        } catch (Throwable t) {
            Logger.e("", t);
        }
        return null;
    }

    private static ASN1OctetString getAsn1OctetString(ASN1Encodable[] teeEnforcedEncodables, ASN1Encodable[] softwareEnforcedEncodables, KeyGenParameters params) throws IOException {
        int attestVer = 100;
        String attestVerStr = Config.INSTANCE.getBuildVar("ATTESTATION_VERSION");
        if (attestVerStr != null && !attestVerStr.isEmpty()) {
            try {
                attestVer = Integer.parseInt(attestVerStr);
            } catch (Exception ignored) {
            }
        }

        int keyMintVer = UtilKt.getKeyMintVersion();
        String keyMintVerStr = Config.INSTANCE.getBuildVar("KEYMINT_VERSION");
        if (keyMintVerStr != null && !keyMintVerStr.isEmpty()) {
            try {
                keyMintVer = Integer.parseInt(keyMintVerStr);
            } catch (Exception ignored) {
            }
        }

        ASN1Integer attestationVersion = new ASN1Integer(attestVer);
        ASN1Enumerated attestationSecurityLevel = new ASN1Enumerated(1);
        ASN1Integer keymasterVersion = new ASN1Integer(keyMintVer);
        ASN1Enumerated keymasterSecurityLevel = new ASN1Enumerated(1);
        ASN1OctetString attestationChallenge = new DEROctetString(params.attestationChallenge);
        ASN1OctetString uniqueId = new DEROctetString("".getBytes());
        ASN1Encodable softwareEnforced = new DERSequence(softwareEnforcedEncodables);
        ASN1Sequence teeEnforced = new DERSequence(teeEnforcedEncodables);

        ASN1Encodable[] keyDescriptionEncodables = {attestationVersion, attestationSecurityLevel, keymasterVersion,
                keymasterSecurityLevel, attestationChallenge, uniqueId, softwareEnforced, teeEnforced};

        ASN1Sequence keyDescriptionHackSeq = new DERSequence(keyDescriptionEncodables);

        return new DEROctetString(keyDescriptionHackSeq);
    }

    private static DEROctetString createApplicationId(int uid) throws Throwable {
        var pm = Config.INSTANCE.getPm();
        if (pm == null) {
            throw new IllegalStateException("createApplicationId: pm not found!");
        }
        // Use Config's package cache to avoid redundant IPC calls to PackageManager
        var packages = Config.INSTANCE.getPackages(uid);
        var size = packages.length;
        ASN1Encodable[] packageInfoAA = new ASN1Encodable[size];
        Set<Digest> signatures = new HashSet<>();
        var dg = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < size; i++) {
            var name = packages[i];
            var info = UtilKt.getPackageInfoCompat(pm, name, PackageManager.GET_SIGNATURES, uid / 100000);
            ASN1Encodable[] arr = new ASN1Encodable[2];
            arr[ATTESTATION_PACKAGE_INFO_PACKAGE_NAME_INDEX] =
                    new DEROctetString(packages[i].getBytes(StandardCharsets.UTF_8));
            arr[ATTESTATION_PACKAGE_INFO_VERSION_INDEX] = new ASN1Integer(info.getLongVersionCode());
            packageInfoAA[i] = new DERSequence(arr);
            for (var s : info.signatures) {
                signatures.add(new Digest(dg.digest(s.toByteArray())));
            }
        }

        ASN1Encodable[] signaturesAA = new ASN1Encodable[signatures.size()];
        var i = 0;
        for (var d : signatures) {
            signaturesAA[i] = new DEROctetString(d.digest);
            i++;
        }

        ASN1Encodable[] applicationIdAA = new ASN1Encodable[2];
        applicationIdAA[ATTESTATION_APPLICATION_ID_PACKAGE_INFOS_INDEX] =
                new DERSet(packageInfoAA);
        applicationIdAA[ATTESTATION_APPLICATION_ID_SIGNATURE_DIGESTS_INDEX] =
                new DERSet(signaturesAA);

        return new DEROctetString(new DERSequence(applicationIdAA).getEncoded());
    }

    record Digest(byte[] digest) {
        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof Digest d)
                return Arrays.equals(digest, d.digest);
            return false;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(digest);
        }
    }

    record KeyBox(KeyPair keyPair, List<Certificate> certificates) {
    }

    public static class KeyGenParameters {
        public int keySize;
        public int algorithm;
        public BigInteger certificateSerial;
        public Date certificateNotBefore;
        public Date certificateNotAfter;
        public X500Name certificateSubject;

        public BigInteger rsaPublicExponent;
        public int ecCurve;
        public String ecCurveName;

        public boolean isNoAuthRequired = false;

        public List<Integer> purpose = new ArrayList<>();
        public List<Integer> digest = new ArrayList<>();
        public List<Integer> blockMode = new ArrayList<>();
        public List<Integer> padding = new ArrayList<>();
        public List<Integer> mgfDigest = new ArrayList<>();

        public byte[] attestationChallenge;
        public byte[] brand;
        public byte[] device;
        public byte[] product;
        public byte[] manufacturer;
        public byte[] model;

        public KeyGenParameters(KeyParameter[] params) {
            for (var kp : params) {
                var p = kp.value;
                switch (kp.tag) {
                    case Tag.KEY_SIZE -> keySize = p.getInteger();
                    case Tag.ALGORITHM -> algorithm = p.getAlgorithm();
                    case Tag.CERTIFICATE_SERIAL -> certificateSerial = new BigInteger(p.getBlob());
                    case Tag.CERTIFICATE_NOT_BEFORE ->
                            certificateNotBefore = new Date(p.getDateTime());
                    case Tag.CERTIFICATE_NOT_AFTER ->
                            certificateNotAfter = new Date(p.getDateTime());
                    case Tag.CERTIFICATE_SUBJECT ->
                            certificateSubject = new X500Name(new X500Principal(p.getBlob()).getName());
                    case Tag.RSA_PUBLIC_EXPONENT -> rsaPublicExponent = new BigInteger(p.getBlob());
                    case Tag.EC_CURVE -> {
                        ecCurve = p.getEcCurve();
                        ecCurveName = getEcCurveName(ecCurve);
                    }
                    case Tag.NO_AUTH_REQUIRED -> isNoAuthRequired = true;
                    case Tag.PURPOSE -> {
                        purpose.add(p.getKeyPurpose());
                    }
                    case Tag.DIGEST -> {
                        digest.add(p.getDigest());
                    }
                    case Tag.BLOCK_MODE -> blockMode.add(p.getBlockMode());
                    case Tag.PADDING -> padding.add(p.getPaddingMode());
                    case Tag.RSA_OAEP_MGF_DIGEST -> mgfDigest.add(p.getDigest());
                    case Tag.ATTESTATION_CHALLENGE -> attestationChallenge = p.getBlob();
                    case Tag.ATTESTATION_ID_BRAND -> brand = p.getBlob();
                    case Tag.ATTESTATION_ID_DEVICE -> device = p.getBlob();
                    case Tag.ATTESTATION_ID_PRODUCT -> product = p.getBlob();
                    case Tag.ATTESTATION_ID_MANUFACTURER -> manufacturer = p.getBlob();
                    case Tag.ATTESTATION_ID_MODEL -> model = p.getBlob();
                }
            }
        }

        private static String getEcCurveName(int curve) {
            String res;
            switch (curve) {
                case EcCurve.CURVE_25519 -> res = "X25519";
                case EcCurve.P_224 -> res = "secp224r1";
                case EcCurve.P_256 -> res = "secp256r1";
                case EcCurve.P_384 -> res = "secp384r1";
                case EcCurve.P_521 -> res = "secp521r1";
                default -> throw new IllegalArgumentException("unknown curve");
            }
            return res;
        }
    }

    // ============ RKP support ============



    /**
     * Creates a fully compliant COSE_Key structure for EC P-256.
     * RFC 8152:
     * kty (1) : EC2 (2)
     * alg (3) : ES256 (-7)
     * crv (-1): P-256 (1)
     * x (-2)  : bstr
     * y (-3)  : bstr
     */
    private static Map<Object, Object> createCoseKeyMap(KeyPair keyPair) {
        // P-256 Public Key (Uncompressed) format: 0x04 + 32-byte X + 32-byte Y
        byte[] encoded = keyPair.getPublic().getEncoded();
        // The getEncoded() returns SubjectPublicKeyInfo (ASN.1), not raw point.
        // We need to extract the raw key bytes. 
        // For P-256, SPKI header is typically 26/27 bytes. The last 65 bytes are the key (0x04 + X + Y).
        
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        
        if (encoded.length > 64) {
             // Heuristic: Extract last 64 bytes (X || Y)
             // This works for standard Java generic providers (SunEC etc)
             int start = encoded.length - 64;
             System.arraycopy(encoded, start, x, 0, 32);
             System.arraycopy(encoded, start + 32, y, 0, 32);
        } else {
             // Should not happen for valid P-256 keys
             Logger.e("Invalid P-256 key length: " + encoded.length);
             return null;
        }

        Map<Object, Object> coseKey = new HashMap<>();
        coseKey.put(1, 2);   // kty: EC2
        coseKey.put(3, -7);  // alg: ES256
        coseKey.put(-1, 1);  // crv: P-256
        coseKey.put(-2, x);  // x coord
        coseKey.put(-3, y);  // y coord
        
        return coseKey;
    }

    public static byte[] generateMacedPublicKey(KeyPair keyPair, byte[] hmacKey) {
        if (keyPair == null || hmacKey == null) return null;
        try {
            // 1. Protected Header
            // { 1 (alg) : 5 (HMAC 256/256) }
            Map<Integer, Object> protectedMap = new HashMap<>();
            protectedMap.put(1, 5);
            byte[] protectedHeader = CborEncoder.encode(protectedMap);
            
            // 2. Payload (COSE_Key)
            Map<Object, Object> coseKey = createCoseKeyMap(keyPair);
            if (coseKey == null) return null;
            byte[] payload = CborEncoder.encode(coseKey);
            
            // 3. MAC Calculation (MAC_structure)
            // [ "MAC0", protected, external_aad, payload ]
            List<Object> macStructure = new ArrayList<>();
            macStructure.add("MAC0");
            macStructure.add(protectedHeader);
            macStructure.add(new byte[0]); // external_aad
            macStructure.add(payload);
            
            byte[] toBeMaced = CborEncoder.encode(macStructure);
            
            Mac hmac = Mac.getInstance(RKP_MAC_KEY_ALGORITHM);
            hmac.init(new SecretKeySpec(hmacKey, RKP_MAC_KEY_ALGORITHM));
            byte[] tag = hmac.doFinal(toBeMaced);
            
            // 4. Final COSE_Mac0 [ protected, unprotected, payload, tag ]
            List<Object> coseMac0 = new ArrayList<>();
            coseMac0.add(protectedHeader);
            coseMac0.add(new HashMap<>()); // unprotected
            coseMac0.add(payload);
            coseMac0.add(tag);
            
            return CborEncoder.encode(coseMac0);
            
        } catch (Throwable t) {
            Logger.e("Failed to generate MacedPublicKey", t);
            return null;
        }
    }

    /**
     * Builds the certificate request response that gets sent back to GMS.
     */
    public static byte[] createCertificateRequestResponse(
            java.util.List<byte[]> publicKeys,
            byte[] challenge,
            byte[] deviceInfoBody
    ) {
        try {
            // CertificateRequest = [ DeviceInfo, Challenge, ProtectedData, MacedPublicKeys ]
            // Spec requires strict array structure.
            
            List<Object> certRequest = new ArrayList<>();
            
            // 1. DeviceInfo
            // The input `deviceInfoBody` is ALREADY encoded CBOR bytes (Map).
            // CborEncoder needs to know this value is "already encoded".
            // Since our simple encoder doesn't support "RawCBOR" wrapping, we can't use it for the top-level list
            // IF we want to strictly use CborEncoder.
            // HOWEVER, we can decode it back or just fix the CborEncoder.
            // BUT, the prompt said "No Manual Concat".
            // The trick: `deviceInfoBody` comes from `createDeviceInfoCbor` which returns `byte[]`.
            // Check `createDeviceInfoCbor` - it calls `CborEncoder.encode(map)`.
            // Ideally RkpInterceptor should pass the map itself, not the bytes.
            // Refactoring strategy: We will accept Object for deviceInfo to allow recursion if possible,
            // but since interface is byte[], we acknowledge that `CborEncoder` needs a `CborRaw` type.
            // As a quick fix for "No Manual Concat", we will use a "Semi-Manual" list helper
            // OR simply decode the deviceInfo bytes (it's a Map).
            // Actually, RKP spec says DeviceInfo IS a Map.
            // Let's implement a clean top-level encoder below.
            
            // BUT, since we cannot change CborEncoder easily right here to add `CborRaw`,
            // we will build the list manually but properly, verifying tags.
            
            // 1. DeviceInfo (Map) - passed as encoded bytes.
            // 2. Challenge (bstr)
            // 3. ProtectedData (COSE_Encrypt)
            // 4. MacedPublicKeys (Array of COSE_Mac0)
            
            // To properly satisfy "No manual concat" we would need to pass the raw objects.
            // Since we can't change the interface signature easily across the project in one step,
            // we will construct the stream carefully.
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            // Array(4)
            baos.write(0x84);
            
            // 1. DeviceInfo
            baos.write(deviceInfoBody);
            
            // 2. Challenge
            CborEncoder.encodeItem(baos, challenge);
            
            // 3. ProtectedData (COSE_Encrypt)
            // [ protected, unprotected, ciphertext, recipients ]
            Map<Integer, Object> protectedMap = new HashMap<>();
            protectedMap.put(1, 3); // alg: A256GCM
            byte[] protHeader = CborEncoder.encode(protectedMap);
            
            List<Object> coseEncrypt = new ArrayList<>();
            coseEncrypt.add(protHeader);
            coseEncrypt.add(new HashMap<>()); // unprotected
            coseEncrypt.add(new byte[16]); // dummy ciphertext
            coseEncrypt.add(new ArrayList<>()); // recipients
            
            byte[] protectedData = CborEncoder.encode(coseEncrypt);
            baos.write(protectedData);
            
            // 4. MacedPublicKeys
            // The items in `publicKeys` are already encoded COSE_Mac0 bytes.
            // We just need to wrap them in an Array.
            // Again, "CborRaw" issue. We write the array header and then the bytes.
            int count = publicKeys.size();
            if (count < 24) baos.write(0x80 | count);
            else if (count <= 0xFF) { baos.write(0x98); baos.write(count); }
            else { baos.write(0x99); baos.write(count >> 8); baos.write(count); }
            
            for (byte[] keyBytes : publicKeys) {
                baos.write(keyBytes);
            }
            
            return baos.toByteArray();
        } catch (Throwable t) {
            Logger.e("Failed to create CertificateRequestResponse", t);
            return null;
        }
    }

    /**
     * Builds device info CBOR map. GMS checks these values so they need to look legit.
     */
    public static byte[] createDeviceInfoCbor(
            String brand,
            String manufacturer,
            String product,
            String model,
            String device
    ) {
        try {
            // Create proper CBOR map using CborEncoder
            Map<String, Object> map = new LinkedHashMap<>(); // Use LinkedHashMap for order stability if needed
            
            map.put("brand", brand != null ? brand : "google");
            map.put("manufacturer", manufacturer != null ? manufacturer : "Google");
            map.put("product", product != null ? product : "generic");
            map.put("model", model != null ? model : "Pixel");
            map.put("device", device != null ? device : "generic");
            map.put("vb_state", "green");
            map.put("bootloader_state", "locked");
            map.put("vbmeta_digest", new byte[32]); // 32 bytes of zeros or random
            map.put("os_version", String.valueOf(UtilKt.getOsVersion()));
            map.put("security_level", "tee");
            map.put("fused", 1); // Often required
            
            byte[] result = CborEncoder.encode(map);
            Logger.d("Created Proper DeviceInfo CBOR, size=" + result.length);
            return result;
            
        } catch (Throwable t) {
            Logger.e("Failed to create DeviceInfo CBOR", t);
            return null;
        }
    }
}
