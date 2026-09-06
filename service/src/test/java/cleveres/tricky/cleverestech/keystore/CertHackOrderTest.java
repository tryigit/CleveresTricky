package cleveres.tricky.cleverestech.keystore;

import cleveres.tricky.cleverestech.Config;
import cleveres.tricky.cleverestech.ManagedCertificateBackendOracle;
import cleveres.tricky.cleverestech.ManagedOpaqueKeyOracle;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(JUnit4.class)
public class CertHackOrderTest {
    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Before
    public void installCertificateBackendOracle() {
        ManagedCertificateBackendOracle.install();
    }

    @After
    public void resetCertificateBackendOracle() {
        ManagedCertificateBackendOracle.reset();
    }

    private static byte[] validBootDigest(int marker) {
        byte[] digest = new byte[32];
        digest[0] = (byte) marker;
        return digest;
    }

    private void setAttestationId(String tag, byte[] value) throws Exception {
        Field field = Config.class.getDeclaredField("attestationIds");
        field.setAccessible(true);
        Map<String, byte[]> map = new HashMap<>();
        if (value != null) {
            map.put(tag, value);
        }
        field.set(Config.INSTANCE, map);
    }

    private void setSpoofEnabled(boolean enabled) throws Exception {
        Field field = Config.class.getDeclaredField("isSpoofEnabled");
        field.setAccessible(true);
        field.setBoolean(Config.INSTANCE, enabled);
        Config.INSTANCE.setRootForTesting(
                new File(System.getProperty("java.io.tmpdir"), "cleverestricky-cert-hack-order"));
    }

    private void resetConfig() {
        Config.INSTANCE.reset();
    }

    private X509Certificate generateCertWithIdentityAndPatchLevels(KeyPair kp) throws Exception {
        X500Name issuer = new X500Name("CN=Test");
        BigInteger serial = BigInteger.ONE;
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 100000);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, issuer, kp.getPublic());

        ASN1EncodableVector keyDesc = new ASN1EncodableVector();
        keyDesc.add(new ASN1Integer(100));
        keyDesc.add(new ASN1Enumerated(1));
        keyDesc.add(new ASN1Integer(100));
        keyDesc.add(new ASN1Enumerated(1));
        keyDesc.add(new DEROctetString(new byte[0]));
        keyDesc.add(new DEROctetString(new byte[0]));
        keyDesc.add(new DERSequence());

        ASN1EncodableVector teeEnforced = new ASN1EncodableVector();
        ASN1EncodableVector rootOfTrust = new ASN1EncodableVector();
        rootOfTrust.add(new DEROctetString(validBootDigest(0x11)));
        rootOfTrust.add(ASN1Boolean.TRUE);
        rootOfTrust.add(new ASN1Enumerated(0));
        rootOfTrust.add(new DEROctetString(validBootDigest(0x22)));
        teeEnforced.add(new DERTaggedObject(true, 704, new DERSequence(rootOfTrust)));
        teeEnforced.add(new DERTaggedObject(true, 706, new ASN1Integer(202401)));
        teeEnforced.add(new DERTaggedObject(
                true,
                710,
                new DEROctetString("OriginalBrand".getBytes(StandardCharsets.UTF_8))));
        teeEnforced.add(new DERTaggedObject(true, 718, new ASN1Integer(20240205)));
        teeEnforced.add(new DERTaggedObject(true, 719, new ASN1Integer(20240305)));
        keyDesc.add(new DERSequence(teeEnforced));

        ASN1ObjectIdentifier oid = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
        builder.addExtension(oid, false, new DERSequence(keyDesc));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @Test
    public void testAttestationIdOrdering() throws Exception {
        resetConfig();
        byte[] expectedBrand = "Google".getBytes(StandardCharsets.UTF_8);
        setAttestationId("BRAND", expectedBrand);
        setSpoofEnabled(true);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X509Certificate cert = generateCertWithIdentityAndPatchLevels(kp);
        CertHack.KeyBox keyBox = ManagedOpaqueKeyOracle.wrap(
                kp, Collections.singletonList(cert), "test.xml");

        Map<String, List<CertHack.KeyBox>> newKeyboxes = new HashMap<>();
        newKeyboxes.put("RSA", Collections.singletonList(keyBox));
        Map<String, List<CertHack.KeyBox>> newKeyboxFiles = new HashMap<>();

        Class<?> stateClass = Class.forName("cleveres.tricky.cleverestech.keystore.CertHack$State");
        Constructor<?> ctor = stateClass.getDeclaredConstructor(Map.class, Map.class);
        ctor.setAccessible(true);
        Object newState = ctor.newInstance(newKeyboxes, newKeyboxFiles);

        Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object previousState = stateField.get(null);
        stateField.set(null, newState);

        try {
            Certificate[] hackedChain = CertHack.hackCertificateChain(new Certificate[]{cert}, 0);
            X509Certificate hackedCert = (X509Certificate) hackedChain[0];
            byte[] extBytes = hackedCert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
            ASN1Primitive extStruct = ASN1Primitive.fromByteArray(
                    ASN1OctetString.getInstance(extBytes).getOctets());
            ASN1Sequence seq = ASN1Sequence.getInstance(extStruct);
            ASN1Sequence teeEnforced = (ASN1Sequence) seq.getObjectAt(7);

            int lastRelevantTag = -1;
            boolean foundBrand = false;
            Integer systemPatch = null;
            Integer vendorPatch = null;
            Integer bootPatch = null;
            for (ASN1Encodable encodable : teeEnforced) {
                ASN1TaggedObject taggedObject = (ASN1TaggedObject) encodable;
                int tag = taggedObject.getTagNo();
                if (tag == 710) {
                    foundBrand = true;
                    byte[] actualBrand = ASN1OctetString.getInstance(
                            taggedObject.getBaseObject()).getOctets();
                    Assert.assertArrayEquals(expectedBrand, actualBrand);
                }
                if (tag == 706) {
                    systemPatch = ASN1Integer.getInstance(taggedObject.getBaseObject()).getValue().intValueExact();
                }
                if (tag == 718) {
                    vendorPatch = ASN1Integer.getInstance(taggedObject.getBaseObject()).getValue().intValueExact();
                }
                if (tag == 719) {
                    bootPatch = ASN1Integer.getInstance(taggedObject.getBaseObject()).getValue().intValueExact();
                }
                if (tag == 706 || tag == 710 || tag == 718 || tag == 719) {
                    if (lastRelevantTag != -1) {
                        Assert.assertTrue(
                                "Tags out of order: " + lastRelevantTag + " came before " + tag,
                                lastRelevantTag < tag);
                    }
                    lastRelevantTag = tag;
                }
            }
            Assert.assertTrue("BRAND (710) missing", foundBrand);
            Assert.assertEquals(Integer.valueOf(202401), systemPatch);
            Assert.assertEquals(Integer.valueOf(20240205), vendorPatch);
            Assert.assertEquals(Integer.valueOf(20240305), bootPatch);
        } finally {
            stateField.set(null, previousState);
            resetConfig();
        }
    }
}
