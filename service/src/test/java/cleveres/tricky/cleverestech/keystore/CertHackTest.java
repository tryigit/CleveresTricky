package cleveres.tricky.cleverestech.keystore;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.ManagedCertificateBackendOracle;
import cleveres.tricky.cleverestech.ManagedOpaqueKeyOracle;
import cleveres.tricky.cleverestech.TestKeyboxFixtures;

public class CertHackTest {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Before
    public void setUp() {
        ManagedCertificateBackendOracle.install();
    }

    @After
    public void tearDown() {
        ManagedCertificateBackendOracle.reset();
        ManagedKeyboxStateOracle.readFromXml(null);
    }

    private static final String EC_KEY = TestKeyboxFixtures.INSTANCE.getEcPrivateKey();
    private static final String TEST_CERT = TestKeyboxFixtures.INSTANCE.getCertificate();

    @Test
    public void testReadFromXml() {
        Logger.setImpl(new Logger.LogImpl() {
            @Override public void d(String tag, String msg) { /* no-op */ }
            @Override public void e(String tag, String msg) { /* no-op */ }
            @Override public void e(String tag, String msg, Throwable t) { /* no-op */ }
            @Override public void i(String tag, String msg) { /* no-op */ }
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

        ManagedKeyboxStateOracle.readFromXml(new StringReader(xml));

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

        assertEquals(0, ManagedKeyboxStateOracle.parse(new StringReader(mixedXml), "mixed.xml").size());
    }

    @Test
    public void testVerifiedBootDigestSelectionWithFallback() {
        byte[] runtime = new byte[32];
        byte[] original = new byte[32];
        byte[] persistent = new byte[32];
        java.util.Arrays.fill(runtime, (byte) 0x11);
        java.util.Arrays.fill(original, (byte) 0x22);
        java.util.Arrays.fill(persistent, (byte) 0x33);

        assertSame(runtime, CertHack.selectVerifiedBootDigest(runtime, original, persistent));
        assertSame(original, CertHack.selectVerifiedBootDigest(null, original, persistent));
        assertSame(original, CertHack.selectVerifiedBootDigest(new byte[32], original, persistent));
        assertSame(persistent, CertHack.selectVerifiedBootDigest(null, null, persistent));
        assertSame(persistent, CertHack.selectVerifiedBootDigest(new byte[32], new byte[32], persistent));
        assertNull(CertHack.selectVerifiedBootDigest(null, null, null));
        assertNull(CertHack.selectVerifiedBootDigest(new byte[32], new byte[31], new byte[32]));
    }

    @Test
    public void testCertificateCacheClearInvalidatesInFlightPublicationEpoch() {
        Object capturedEpoch = CertHack.captureCertificateCacheEpochForTesting();
        assertTrue(CertHack.isCertificateCacheEpochCurrentForTesting(capturedEpoch));

        CertHack.clearCertificateCache();

        assertFalse(CertHack.isCertificateCacheEpochCurrentForTesting(capturedEpoch));
    }

    @Test
    public void testIsStrongBoxKeybox() {
        assertFalse(CertHack.isStrongBoxKeybox(null));

        java.security.KeyPair keyPair = org.mockito.Mockito.mock(java.security.KeyPair.class);
        CertHack.KeyBox emptyBox = new CertHack.KeyBox(keyPair, List.of(), "empty.xml");
        assertFalse(CertHack.isStrongBoxKeybox(emptyBox));

        java.security.cert.X509Certificate teeCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(teeCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Android KeyMint CA, O=Google LLC, C=US"));
        org.mockito.Mockito.when(teeCert.getIssuerX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google Root CA, O=Google LLC, C=US"));
        CertHack.KeyBox teeBox = new CertHack.KeyBox(keyPair, List.of(teeCert), "tee.xml");
        assertFalse(CertHack.isStrongBoxKeybox(teeBox));

        java.security.cert.X509Certificate strongboxCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(strongboxCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google StrongBox KeyMint CA, O=Google LLC, C=US"));
        CertHack.KeyBox strongBox = new CertHack.KeyBox(keyPair, List.of(strongboxCert), "sb.xml");
        assertTrue(CertHack.isStrongBoxKeybox(strongBox));

        CertHack.KeyBox strongBoxByFilename = new CertHack.KeyBox(keyPair, List.of(teeCert), "keybox_strongbox.xml");
        assertTrue(CertHack.isStrongBoxKeybox(strongBoxByFilename));
    }

    @Test
    public void testGetKeyboxSecurityLevelDefaultsSafely() {
        assertEquals("Unknown", CertHack.getKeyboxSecurityLevel(null));
        assertEquals("Unknown", CertHack.getKeyboxSecurityLevel("non_existent.xml"));
    }

    @Test
    public void testClassifyKeyboxSecurityLevel() {
        assertEquals(CertHack.KeyboxSecurityLevel.UNKNOWN, CertHack.classifyKeyboxSecurityLevel(null));

        java.security.KeyPair keyPair = org.mockito.Mockito.mock(java.security.KeyPair.class);
        CertHack.KeyBox emptyBox = new CertHack.KeyBox(keyPair, List.of(), "empty.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.UNKNOWN, CertHack.classifyKeyboxSecurityLevel(emptyBox));

        java.security.cert.X509Certificate teeCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(teeCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Android KeyMint CA, O=Google LLC, C=US"));
        CertHack.KeyBox teeBox = new CertHack.KeyBox(keyPair, List.of(teeCert), "custom.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.TEE, CertHack.classifyKeyboxSecurityLevel(teeBox));

        java.security.cert.X509Certificate sbCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(sbCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google StrongBox KeyMint CA, O=Google LLC, C=US"));
        CertHack.KeyBox sbBox = new CertHack.KeyBox(keyPair, List.of(sbCert), "custom.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.STRONGBOX, CertHack.classifyKeyboxSecurityLevel(sbBox));

        java.security.cert.X509Certificate plainCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(plainCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Generic Unbranded CA, O=Custom, C=US"));
        CertHack.KeyBox filenameSbBox = new CertHack.KeyBox(keyPair, List.of(plainCert), "device_strongbox.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.STRONGBOX, CertHack.classifyKeyboxSecurityLevel(filenameSbBox));

        CertHack.KeyBox filenameTeeBox = new CertHack.KeyBox(keyPair, List.of(plainCert), "device_tee.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.TEE, CertHack.classifyKeyboxSecurityLevel(filenameTeeBox));

        CertHack.KeyBox standardBox = new CertHack.KeyBox(keyPair, List.of(plainCert), "keybox.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.TEE, CertHack.classifyKeyboxSecurityLevel(standardBox));
        assertTrue(CertHack.isTeeKeybox(standardBox));
        assertFalse(CertHack.isStrongBoxKeybox(standardBox));
        assertEquals(List.of(standardBox), CertHack.filterKeyboxesBySecurityLevel(List.of(standardBox), false));
        assertTrue(CertHack.filterKeyboxesBySecurityLevel(List.of(standardBox), true).isEmpty());

        CertHack.KeyBox arbitraryNamedBox = new CertHack.KeyBox(keyPair, List.of(plainCert), "unknown.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.TEE, CertHack.classifyKeyboxSecurityLevel(arbitraryNamedBox));
        assertTrue(CertHack.isTeeKeybox(arbitraryNamedBox));
        assertFalse(CertHack.isStrongBoxKeybox(arbitraryNamedBox));
    }

    @Test
    public void testFilterKeyboxesBySecurityLevelDoesNotFallback() {
        java.security.KeyPair keyPair = org.mockito.Mockito.mock(java.security.KeyPair.class);
        java.security.cert.X509Certificate strongboxCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(strongboxCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google StrongBox KeyMint CA, O=Google LLC, C=US"));
        org.mockito.Mockito.when(strongboxCert.getIssuerX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google Root CA, O=Google LLC, C=US"));
        CertHack.KeyBox strongBox = new CertHack.KeyBox(keyPair, List.of(strongboxCert), "sb.xml");

        List<CertHack.KeyBox> resultForTee = CertHack.filterKeyboxesBySecurityLevel(List.of(strongBox), false);
        assertTrue("TEE request with only StrongBox candidates must return empty list without falling back", resultForTee.isEmpty());

        List<CertHack.KeyBox> resultForStrongBox = CertHack.filterKeyboxesBySecurityLevel(List.of(strongBox), true);
        assertEquals(1, resultForStrongBox.size());
        assertSame(strongBox, resultForStrongBox.get(0));
    }

    @Test
    public void testScopeAwareKeyboxIdentifiersResolveAccurately() throws Exception {
        java.security.KeyPair keyPair = org.mockito.Mockito.mock(java.security.KeyPair.class);
        java.security.cert.X509Certificate strongboxCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(strongboxCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google StrongBox KeyMint CA, O=Google LLC, C=US"));
        org.mockito.Mockito.when(strongboxCert.getIssuerX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google Root CA, O=Google LLC, C=US"));

        java.security.cert.X509Certificate teeCert = org.mockito.Mockito.mock(java.security.cert.X509Certificate.class);
        org.mockito.Mockito.when(teeCert.getSubjectX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Android KeyMint CA, O=Google LLC, C=US"));
        org.mockito.Mockito.when(teeCert.getIssuerX500Principal())
                .thenReturn(new javax.security.auth.x500.X500Principal("CN=Google Root CA, O=Google LLC, C=US"));

        CertHack.KeyBox rootKeybox = new CertHack.KeyBox(keyPair, List.of(strongboxCert), "root:keybox.xml");
        CertHack.KeyBox managedKeybox = new CertHack.KeyBox(keyPair, List.of(teeCert), "keyboxes:keybox.xml");

        Map<String, List<CertHack.KeyBox>> keyboxes = new HashMap<>();
        keyboxes.put("EC", List.of(rootKeybox, managedKeybox));
        Map<String, List<CertHack.KeyBox>> keyboxFiles = new HashMap<>();
        keyboxFiles.put("root:keybox.xml", List.of(rootKeybox));
        keyboxFiles.put("keyboxes:keybox.xml", List.of(managedKeybox));
        keyboxFiles.put("keybox.xml", List.of(rootKeybox, managedKeybox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(keyboxes, keyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            assertEquals("StrongBox", CertHack.getKeyboxSecurityLevel("root:keybox.xml"));
            assertEquals("TEE", CertHack.getKeyboxSecurityLevel("keyboxes:keybox.xml"));
            assertEquals("StrongBox", CertHack.getKeyboxSecurityLevel("keybox.xml"));
            assertEquals("Unknown", CertHack.getKeyboxSecurityLevel("custom:keybox.xml"));
            assertNull(CertHack.getDeviceCertificateSerial("custom:keybox.xml"));
            assertTrue(CertHack.isStrongBoxKeybox(rootKeybox));
            assertFalse(CertHack.isStrongBoxKeybox(managedKeybox));
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testEmptyStateSecurityLevelFastPath() {
        assertEquals("Unknown", CertHack.getKeyboxSecurityLevel("any.xml"));
    }

    @Test
    public void testIsRkpKeybox() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate attCert = generateAttestationCert(kp, 1, 1);
        X509Certificate rkpCert = generateIssuerCert(kp, "CN=Droid CA2, O=Google LLC, C=US");
        X509Certificate standardCert = generateIssuerCert(kp, "CN=Google Root CA, O=Google Inc, C=US");

        CertHack.KeyBox rkpBox = new CertHack.KeyBox(kp, List.of(attCert, rkpCert), "rkp.xml");
        CertHack.KeyBox standardBox = new CertHack.KeyBox(kp, List.of(attCert, standardCert), "standard.xml");
        CertHack.KeyBox emptyBox = new CertHack.KeyBox(kp, List.of(), "empty.xml");

        X509Certificate remoteProvCert = generateIssuerCert(kp, "CN=Google Remote Key Provisioning, O=Google LLC, C=US");
        CertHack.KeyBox remoteProvBox = new CertHack.KeyBox(kp, List.of(attCert, remoteProvCert), "keybox.xml");
        CertHack.KeyBox filenameRkpBox = new CertHack.KeyBox(kp, List.of(attCert, standardCert), "custom_rkp_keybox.xml");

        assertTrue(CertHack.isRkpKeybox(rkpBox));
        assertTrue(CertHack.isRkpKeybox(remoteProvBox));
        assertTrue(CertHack.isRkpKeybox(filenameRkpBox));
        assertFalse(CertHack.isRkpKeybox(standardBox));
        assertFalse(CertHack.isRkpKeybox(emptyBox));
        assertFalse(CertHack.isRkpKeybox((CertHack.KeyBox) null));
        assertFalse(CertHack.isRkpKeybox((String) null));
        assertFalse(CertHack.isRkpKeybox("non_existent.xml"));
    }

    @Test
    public void testIsRkpKeyboxByStringIdentifier() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate rkpCert = generateIssuerCert(kp, "CN=Droid CA3, O=Google LLC, C=US");
        X509Certificate plainCert = generateIssuerCert(kp, "CN=Google Root CA, O=Google Inc, C=US");

        CertHack.KeyBox rkpBox = new CertHack.KeyBox(kp, List.of(rkpCert), "rkp.xml");
        CertHack.KeyBox plainBox = new CertHack.KeyBox(kp, List.of(plainCert), "plain.xml");

        Map<String, List<CertHack.KeyBox>> keyboxes = new HashMap<>();
        keyboxes.put("RSA", List.of(rkpBox, plainBox));
        Map<String, List<CertHack.KeyBox>> keyboxFiles = new HashMap<>();
        keyboxFiles.put("rkp.xml", List.of(rkpBox));
        keyboxFiles.put("plain.xml", List.of(plainBox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(keyboxes, keyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            assertTrue(CertHack.isRkpKeybox("rkp.xml"));
            assertFalse(CertHack.isRkpKeybox("plain.xml"));
            assertFalse(CertHack.isRkpKeybox("unknown.xml"));
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testHackCertificateChainTeeWithOnlyStrongBoxKeyboxDoesNotRewrite() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate strongboxCert = generateIssuerCert(kp, "CN=Google StrongBox KeyMint CA, O=Google LLC, C=US");
        CertHack.KeyBox strongBoxKeybox = ManagedOpaqueKeyOracle.wrap(
                kp, List.of(strongboxCert), "sb.xml");

        Map<String, List<CertHack.KeyBox>> newKeyboxes = new HashMap<>();
        newKeyboxes.put("RSA", List.of(strongBoxKeybox));
        Map<String, List<CertHack.KeyBox>> newKeyboxFiles = new HashMap<>();
        newKeyboxFiles.put("sb.xml", List.of(strongBoxKeybox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(newKeyboxes, newKeyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            X509Certificate teeLeaf = generateAttestationCert(kp, 1, 1);
            Certificate[] inputChain = new Certificate[]{teeLeaf};
            Certificate[] resultChain = CertHack.hackCertificateChain(inputChain, 0);
            assertSame("TEE leaf must not fall back to StrongBox keybox", inputChain, resultChain);
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testHackCertificateChainStrongBoxWithOnlyTeeKeyboxFallsBackToTee() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate teeCert = generateIssuerCert(kp, "CN=Android KeyMint CA, O=Google LLC, C=US");
        CertHack.KeyBox teeKeybox = ManagedOpaqueKeyOracle.wrap(
                kp, List.of(teeCert), "tee.xml");

        Map<String, List<CertHack.KeyBox>> newKeyboxes = new HashMap<>();
        newKeyboxes.put("RSA", List.of(teeKeybox));
        Map<String, List<CertHack.KeyBox>> newKeyboxFiles = new HashMap<>();
        newKeyboxFiles.put("tee.xml", List.of(teeKeybox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(newKeyboxes, newKeyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            X509Certificate sbLeaf = generateAttestationCert(kp, 2, 2);
            Certificate[] inputChain = new Certificate[]{sbLeaf};
            Certificate[] resultChain = CertHack.hackCertificateChain(inputChain, 0);
            assertNotEquals("StrongBox leaf must fall back to TEE keybox and rewrite", inputChain, resultChain);
            assertEquals(2, resultChain.length);
            assertEquals(teeCert, resultChain[1]);
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testHackCertificateChainWithOnlyUnknownKeyboxDoesNotRewrite() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate swCert = generateAttestationCert(kp, 0, 0);
        CertHack.KeyBox unknownKeybox = ManagedOpaqueKeyOracle.wrap(
                kp, List.of(swCert), "unknown.xml");

        Map<String, List<CertHack.KeyBox>> newKeyboxes = new HashMap<>();
        newKeyboxes.put("RSA", List.of(unknownKeybox));
        Map<String, List<CertHack.KeyBox>> newKeyboxFiles = new HashMap<>();
        newKeyboxFiles.put("unknown.xml", List.of(unknownKeybox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(newKeyboxes, newKeyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            X509Certificate teeLeaf = generateAttestationCert(kp, 1, 1);
            Certificate[] teeChain = new Certificate[]{teeLeaf};
            Certificate[] teeResult = CertHack.hackCertificateChain(teeChain, 0);
            assertSame("TEE leaf must not rewrite when only UNKNOWN keybox is available", teeChain, teeResult);

            X509Certificate sbLeaf = generateAttestationCert(kp, 2, 2);
            Certificate[] sbChain = new Certificate[]{sbLeaf};
            Certificate[] sbResult = CertHack.hackCertificateChain(sbChain, 0);
            assertSame("StrongBox leaf must not rewrite when only UNKNOWN keybox is available", sbChain, sbResult);
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testClassifyKeyboxSecurityLevelStrictProvenance() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate teeCert = generateAttestationCert(kp, 1, 1);
        CertHack.KeyBox teeBox = ManagedOpaqueKeyOracle.wrap(kp, List.of(teeCert), "custom.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.TEE, CertHack.classifyKeyboxSecurityLevel(teeBox));

        X509Certificate sbCert = generateAttestationCert(kp, 2, 2);
        CertHack.KeyBox sbBox = ManagedOpaqueKeyOracle.wrap(kp, List.of(sbCert), "custom.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.STRONGBOX, CertHack.classifyKeyboxSecurityLevel(sbBox));

        X509Certificate mixedCert = generateAttestationCert(kp, 1, 2);
        CertHack.KeyBox mixedBox = ManagedOpaqueKeyOracle.wrap(kp, List.of(mixedCert), "custom.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.STRONGBOX, CertHack.classifyKeyboxSecurityLevel(mixedBox));

        X509Certificate reversedCert = generateAttestationCert(kp, 2, 1);
        CertHack.KeyBox reversedBox = ManagedOpaqueKeyOracle.wrap(kp, List.of(reversedCert), "tee_strongbox.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.UNKNOWN, CertHack.classifyKeyboxSecurityLevel(reversedBox));

        X509Certificate swCert = generateAttestationCert(kp, 0, 0);
        CertHack.KeyBox swBox = ManagedOpaqueKeyOracle.wrap(kp, List.of(swCert), "tee_keybox.xml");
        assertEquals(CertHack.KeyboxSecurityLevel.UNKNOWN, CertHack.classifyKeyboxSecurityLevel(swBox));
    }

    @Test
    public void testSourceCountDeduplicatesCanonicalFilenamesAcrossAliases() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = generateIssuerCert(kp, "CN=Canonical Test");
        CertHack.KeyBox box1 = ManagedOpaqueKeyOracle.wrap(kp, List.of(cert), "shared_file.xml");

        Map<String, List<CertHack.KeyBox>> keyboxes = Map.of("RSA", List.of(box1));
        Map<String, List<CertHack.KeyBox>> keyboxFiles = Map.of(
                "shared_file.xml", List.of(box1),
                "alias_name", List.of(box1)
        );

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(keyboxes, keyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            assertEquals(1, CertHack.getKeyboxSourceCount());
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testCertificateCacheBridgesBothSizeAndRetainedByteBudgets() throws Exception {
        Class<?> cacheClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State$CertificateCache");
        java.lang.reflect.Constructor<?> cacheCtor = cacheClass.getDeclaredConstructor();
        cacheCtor.setAccessible(true);
        Object cacheObj = cacheCtor.newInstance();

        Class<?> keyClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$CacheKey");
        java.lang.reflect.Constructor<?> keyCtor = keyClass.getDeclaredConstructor(byte[].class);
        keyCtor.setAccessible(true);

        Class<?> chainClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$CachedCertificateChain");
        java.lang.reflect.Constructor<?> chainCtor = chainClass.getDeclaredConstructor(
                Certificate[].class,
                byte[].class,
                byte[].class,
                boolean.class,
                boolean.class
        );
        chainCtor.setAccessible(true);

        java.util.Map<Object, Object> cache = (java.util.Map<Object, Object>) cacheObj;
        java.lang.reflect.Method retainedBytesMethod = cacheClass.getDeclaredMethod("retainedBytes");
        retainedBytesMethod.setAccessible(true);

        for (int i = 0; i < 70; i++) {
            byte[] id = new byte[]{(byte) i};
            Object k = keyCtor.newInstance((Object) id);
            Object v = chainCtor.newInstance(new Certificate[0], id, id, true, true);
            cache.put(k, v);
        }
        assertTrue(cache.size() <= 64);

        cache.clear();
        assertEquals(0, ((Number) retainedBytesMethod.invoke(cacheObj)).intValue());
        byte[] bigLeaf = new byte[1024 * 1024];
        byte[] bigIssuer = new byte[512 * 1024];
        for (int i = 0; i < 6; i++) {
            byte[] id = new byte[]{(byte) (i + 100)};
            Object k = keyCtor.newInstance((Object) id);
            Object v = chainCtor.newInstance(new Certificate[0], bigLeaf, bigIssuer, true, true);
            cache.put(k, v);
        }
        int retained = ((Number) retainedBytesMethod.invoke(cacheObj)).intValue();
        assertTrue("Retained bytes " + retained + " must be <= 4 MiB", retained <= 4 * 1024 * 1024);
        assertTrue("Cache size must have been trimmed to fit budget", cache.size() <= 4);

        cache.clear();
        byte[] testKey = new byte[1000];
        Object lk = keyCtor.newInstance((Object) testKey);
        Object lv = chainCtor.newInstance(new Certificate[0], new byte[200], new byte[300], true, true);
        cache.put(lk, lv);
        assertEquals(1500, ((Number) retainedBytesMethod.invoke(cacheObj)).intValue());
        cache.remove(lk);
        assertEquals(0, ((Number) retainedBytesMethod.invoke(cacheObj)).intValue());
    }

    @Test
    public void testReversedSecurityLevelRejectsRewrite() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate plainCert = generateIssuerCert(kp, "CN=StrongBox Intermediate, O=Android, C=US");
        CertHack.KeyBox sbKeybox = ManagedOpaqueKeyOracle.wrap(
                kp, List.of(plainCert), "strongbox_keybox.xml");

        Map<String, List<CertHack.KeyBox>> newKeyboxes = new HashMap<>();
        newKeyboxes.put("RSA", List.of(sbKeybox));
        Map<String, List<CertHack.KeyBox>> newKeyboxFiles = new HashMap<>();
        newKeyboxFiles.put("strongbox_keybox.xml", List.of(sbKeybox));

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(newKeyboxes, newKeyboxFiles);

        java.lang.reflect.Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            X509Certificate reversedCert = generateAttestationCert(kp, 2, 1);
            Certificate[] reversedChain = new Certificate[]{reversedCert};
            Certificate[] result = CertHack.hackCertificateChain(reversedChain, 0);
            assertSame("Reversed security level (STRONGBOX, TEE) must return original chain without rewrite", reversedChain, result);
        } finally {
            stateField.set(null, previousState);
        }
    }

    @Test
    public void testPreparedIssuerChainsAreLazyAndBudgetNeverDropsValidKeyboxes() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate largeCert = generateLargeIssuerCert(kp, "CN=Budget Test", 60000);
        List<Certificate> certs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            certs.add(largeCert);
        }

        Map<String, List<CertHack.KeyBox>> boxMap = new HashMap<>();
        List<CertHack.KeyBox> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(ManagedOpaqueKeyOracle.wrap(kp, certs, "box" + i + ".xml"));
        }
        boxMap.put("RSA", list);

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        java.lang.reflect.Method prepareMethod = stateClass.getDeclaredMethod("prepareKeyboxesForState", Map.class);
        prepareMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<?, ?> prepared = (Map<?, ?>) prepareMethod.invoke(null, boxMap);
        assertEquals("Memory budget must not change the valid keybox set", 10, prepared.size());

        CertHack.setKeyboxes(list);
        assertEquals(10, CertHack.getPublishedKeyboxCountForTesting());
        assertEquals("Issuer DER must not be retained eagerly at publication", 0,
                CertHack.getPreparedIssuerChainRetainedBytesForTesting());
    }

    private X509Certificate generateLargeIssuerCert(KeyPair kp, String subjectDn, int extensionSize) throws Exception {
        X500Name name = new X500Name(subjectDn);
        BigInteger serial = BigInteger.valueOf(2);
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 100000);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, notBefore, notAfter, name, kp.getPublic());
        if (extensionSize > 0) {
            builder.addExtension(new ASN1ObjectIdentifier("1.2.3.4.5.6.7"), false, new DEROctetString(new byte[extensionSize]));
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private X509Certificate generateAttestationCert(KeyPair kp, int attLevel, int kmLevel) throws Exception {
        X500Name issuer = new X500Name("CN=Test Issuer");
        BigInteger serial = BigInteger.ONE;
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 100000);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, kp.getPublic());

        ASN1EncodableVector keyDesc = new ASN1EncodableVector();
        keyDesc.add(new ASN1Integer(100));
        keyDesc.add(new ASN1Enumerated(attLevel));
        keyDesc.add(new ASN1Integer(100));
        keyDesc.add(new ASN1Enumerated(kmLevel));
        keyDesc.add(new DEROctetString(new byte[0]));
        keyDesc.add(new DEROctetString(new byte[0]));
        keyDesc.add(new DERSequence());

        ASN1EncodableVector teeEnforced = new ASN1EncodableVector();
        ASN1EncodableVector rootOfTrust = new ASN1EncodableVector();
        byte[] bootKey = new byte[32];
        bootKey[0] = 1;
        byte[] bootHash = new byte[32];
        bootHash[0] = 2;
        rootOfTrust.add(new DEROctetString(bootKey));
        rootOfTrust.add(ASN1Boolean.TRUE);
        rootOfTrust.add(new ASN1Enumerated(0));
        rootOfTrust.add(new DEROctetString(bootHash));
        teeEnforced.add(new DERTaggedObject(true, 704, new DERSequence(rootOfTrust)));
        keyDesc.add(new DERSequence(teeEnforced));

        ASN1ObjectIdentifier oid = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
        builder.addExtension(oid, false, new DERSequence(keyDesc));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private X509Certificate generateIssuerCert(KeyPair kp, String subjectDn) throws Exception {
        X500Name name = new X500Name(subjectDn);
        BigInteger serial = BigInteger.valueOf(2);
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 100000);
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, notBefore, notAfter, name, kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }
}
