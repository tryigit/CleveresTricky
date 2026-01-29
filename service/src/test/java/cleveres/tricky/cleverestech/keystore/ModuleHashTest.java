package cleveres.tricky.cleverestech.keystore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import cleveres.tricky.cleverestech.Config;
import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.security.cert.Certificate;
import org.bouncycastle.asn1.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import java.math.BigInteger;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.junit.Assert;
import java.util.Collections;

@RunWith(JUnit4.class)
public class ModuleHashTest {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private void setModuleHash(byte[] hash) throws Exception {
        Field field = Config.class.getDeclaredField("moduleHash");
        field.setAccessible(true);
        field.set(Config.INSTANCE, hash);
    }

    private X509Certificate generateSelfSignedCert(KeyPair kp) throws Exception {
        X500Name issuer = new X500Name("CN=Test");
        BigInteger serial = BigInteger.ONE;
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 100000);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
            issuer, serial, notBefore, notAfter, issuer, kp.getPublic());

        ASN1EncodableVector keyDesc = new ASN1EncodableVector();
        keyDesc.add(new ASN1Integer(100)); // version
        keyDesc.add(new ASN1Enumerated(1)); // security level
        keyDesc.add(new ASN1Integer(100));
        keyDesc.add(new ASN1Enumerated(1));
        keyDesc.add(new DEROctetString(new byte[0])); // challenge
        keyDesc.add(new DEROctetString(new byte[0])); // uniqueId
        keyDesc.add(new DERSequence()); // softwareEnforced

        ASN1EncodableVector teeEnforced = new ASN1EncodableVector();
        // Add RootOfTrust (704)
        ASN1EncodableVector rootOfTrust = new ASN1EncodableVector();
        rootOfTrust.add(new DEROctetString(new byte[32])); // key
        rootOfTrust.add(ASN1Boolean.TRUE);
        rootOfTrust.add(new ASN1Enumerated(0));
        rootOfTrust.add(new DEROctetString(new byte[32])); // hash
        teeEnforced.add(new DERTaggedObject(true, 704, new DERSequence(rootOfTrust)));

        keyDesc.add(new DERSequence(teeEnforced));

        ASN1ObjectIdentifier OID = new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17");
        builder.addExtension(OID, false, new DERSequence(keyDesc));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    @Test
    public void testHackCertificateChainWithModuleHash() throws Exception {
        byte[] expectedHash = new byte[] { (byte)0xDE, (byte)0xAD, (byte)0xBE, (byte)0xEF };
        setModuleHash(expectedHash);

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X509Certificate cert = generateSelfSignedCert(kp);

        // Inject keybox
        CertHack.KeyBox keyBox = new CertHack.KeyBox(kp, Collections.singletonList(cert));
        Field keyboxesField = CertHack.class.getDeclaredField("keyboxes");
        keyboxesField.setAccessible(true);
        Map<String, CertHack.KeyBox> keyboxes = (Map) keyboxesField.get(null);
        keyboxes.put("RSA", keyBox);

        Certificate[] chain = new Certificate[] { cert };
        Certificate[] hackedChain = CertHack.hackCertificateChain(chain);

        X509Certificate hackedCert = (X509Certificate) hackedChain[0];
        byte[] extBytes = hackedCert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
        ASN1Primitive extStruct = ASN1Primitive.fromByteArray(ASN1OctetString.getInstance(extBytes).getOctets());
        ASN1Sequence seq = ASN1Sequence.getInstance(extStruct);
        ASN1Sequence teeEnforced = (ASN1Sequence) seq.getObjectAt(7);

        boolean found = false;
        for(ASN1Encodable e : teeEnforced) {
            ASN1TaggedObject t = (ASN1TaggedObject) e;
            if (t.getTagNo() == 724) {
                found = true;
                ASN1OctetString val = (ASN1OctetString) t.getBaseObject();
                Assert.assertArrayEquals(expectedHash, val.getOctets());
            }
        }
        Assert.assertTrue("ModuleHash tag 724 not found", found);
    }
}
