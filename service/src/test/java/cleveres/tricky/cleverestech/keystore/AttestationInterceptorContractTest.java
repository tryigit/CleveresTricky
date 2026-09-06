package cleveres.tricky.cleverestech.keystore;

import android.hardware.security.keymint.SecurityLevel;
import android.os.Binder;
import android.os.Parcel;
import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import cleveres.tricky.cleverestech.KeystoreInterceptor;
import cleveres.tricky.cleverestech.SecurityLevelInterceptor;
import cleveres.tricky.cleverestech.binder.BinderInterceptor;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1Enumerated;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AttestationInterceptorContractTest {
    @Test
    public void callerSelectedIssuerWinsEvenIfReplyContainsAnIssuerChain() throws Exception {
        KeyPair issuer = keyPair("EC");
        X509Certificate child = certificate(keyPair("RSA"), issuer, "child", "issuer");
        KeyMetadata metadata = metadata(child, child.getEncoded());
        Parcel reply = generatedReply(metadata);
        Parcel request = AttestationRequestContractTest.request(true);

        try (MockedStatic<CertHack> backend = mockStatic(CertHack.class)) {
            backend.when(() -> CertHack.hackCertificateChain(any(), anyInt()))
                    .thenReturn(new Certificate[] {child, child});
            assertSame(BinderInterceptor.Skip.INSTANCE, generate(request, reply));
            backend.verifyNoInteractions();
        }
        verify(reply, never()).readException();
        verify(request).setDataPosition(28);
        child.verify(issuer.getPublic());
    }

    @Test
    public void leafOnlyAttestKeyGraphAndOrdinaryKeysSurviveRepeatedReadback() throws Exception {
        KeyPair a = keyPair("EC");
        KeyPair b = keyPair("RSA");
        KeyPair c = keyPair("EC");
        X509Certificate ab = certificate(b, a, "B", "A");
        X509Certificate bc = certificate(c, b, "C", "B");
        X509Certificate ordinary = certificate(c, c, "ordinary", "ordinary", false);

        Binder target = new Binder();
        Field keystore = field(KeystoreInterceptor.class, "keystore");
        Object previous = keystore.get(null);
        keystore.set(null, target);
        try (MockedStatic<CertHack> backend = mockStatic(CertHack.class)) {
            backend.when(CertHack::canHack).thenReturn(true);
            // A stale hit must not turn a caller-owned leaf into a generic replacement.
            backend.when(() -> CertHack.applyCachedCertificateChain(any())).thenReturn(true);
            backend.when(() -> CertHack.hackCertificateChain(any(), anyInt()))
                    .thenReturn(new Certificate[] {ab, ab});

            for (X509Certificate child : new X509Certificate[] {ab, bc, ordinary}) {
                for (byte[] chain : new byte[][] {null, new byte[0]}) {
                    KeyMetadata metadata = metadata(child, chain);
                    byte[] original = metadata.certificate.clone();
                    // Also exercise the reply invariant independently of the request guard.
                    assertSame(BinderInterceptor.Skip.INSTANCE,
                            generate(AttestationRequestContractTest.request(false), generatedReply(metadata)));

                    for (int read = 0; read < 320; read++) {
                        KeyEntryResponse response = new KeyEntryResponse();
                        response.metadata = metadata;
                        Parcel reply = mock(Parcel.class);
                        when(reply.readTypedObject(KeyEntryResponse.CREATOR)).thenReturn(response);
                        assertSame(BinderInterceptor.Skip.INSTANCE,
                                KeystoreInterceptor.INSTANCE.onPostTransact(target,
                                        field(KeystoreInterceptor.class, "getKeyEntryTransaction").getInt(null),
                                        0, 10_001, 42, mock(Parcel.class), reply, 0));
                    }
                    assertArrayEquals(original, metadata.certificate);
                    assertSame(chain, metadata.certificateChain);
                }
            }
            backend.verify(() -> CertHack.applyCachedCertificateChain(any()), never());
            backend.verify(() -> CertHack.hackCertificateChain(any(), anyInt()), never());
        } finally {
            keystore.set(null, previous);
        }
        ab.verify(a.getPublic());
        bc.verify(b.getPublic());
    }

    @Test
    public void ordinaryCompleteAttestationStillUsesTheExistingRewritePath() throws Exception {
        KeyPair issuer = keyPair("EC");
        X509Certificate child = certificate(keyPair("EC"), issuer, "child", "issuer");
        KeyMetadata metadata = metadata(child, child.getEncoded());
        Certificate[] replacement = new Certificate[] {child, child};
        try (MockedStatic<CertHack> backend = mockStatic(CertHack.class)) {
            backend.when(() -> CertHack.hackCertificateChain(any(), anyInt())).thenReturn(replacement);
            BinderInterceptor.Result result =
                    generate(AttestationRequestContractTest.request(false), generatedReply(metadata));
            org.junit.Assert.assertTrue(result instanceof BinderInterceptor.OverrideReply);
            backend.verify(() -> CertHack.hackCertificateChain(any(), anyInt()));
            ((BinderInterceptor.OverrideReply) result).getReply().recycle();
        }
    }

    private static BinderInterceptor.Result generate(Parcel request, Parcel reply) throws Exception {
        return new SecurityLevelInterceptor().onPostTransact(new Binder(),
                field(SecurityLevelInterceptor.class, "generateKeyTransaction").getInt(null),
                0, 10_001, 42, request, reply, 0);
    }

    private static Parcel generatedReply(KeyMetadata metadata) {
        Parcel reply = mock(Parcel.class);
        when(reply.readTypedObject(KeyMetadata.CREATOR)).thenReturn(metadata);
        return reply;
    }

    private static KeyMetadata metadata(X509Certificate leaf, byte[] issuers) throws Exception {
        KeyMetadata metadata = new KeyMetadata();
        metadata.keySecurityLevel = SecurityLevel.TRUSTED_ENVIRONMENT;
        metadata.certificate = leaf.getEncoded();
        metadata.certificateChain = issuers;
        return metadata;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static KeyPair keyPair(String algorithm) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        generator.initialize(algorithm.equals("RSA") ? 2048 : 256);
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair subject, KeyPair issuer,
                                               String subjectName, String issuerName) throws Exception {
        return certificate(subject, issuer, subjectName, issuerName, true);
    }

    private static X509Certificate certificate(KeyPair subject, KeyPair issuer,
                                               String subjectName, String issuerName,
                                               boolean attested) throws Exception {
        BouncyCastleProvider provider = new BouncyCastleProvider();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name("CN=" + issuerName), BigInteger.ONE,
                new Date(0), new Date(4_102_444_800_000L),
                new X500Name("CN=" + subjectName), subject.getPublic());
        if (attested) builder.addExtension(new ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17"), false,
                new DERSequence(new ASN1Encodable[] {
                        new ASN1Integer(400), new ASN1Enumerated(1),
                        new ASN1Integer(400), new ASN1Enumerated(1),
                        new DEROctetString(new byte[] {1}), new DEROctetString(new byte[0]),
                        new DERSequence(), new DERSequence()
                }));
        String algorithm = issuer.getPrivate().getAlgorithm().equals("RSA")
                ? "SHA256withRSA" : "SHA256withECDSA";
        return new JcaX509CertificateConverter().setProvider(provider).getCertificate(
                builder.build(new JcaContentSignerBuilder(algorithm).setProvider(provider)
                        .build(issuer.getPrivate())));
    }
}
