package cleveres.tricky.cleverestech.keystore;

import android.hardware.security.keymint.Tag;
import android.os.Parcel;
import android.system.keystore2.IKeystoreSecurityLevel;
import android.system.keystore2.KeyMetadata;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.security.cert.Certificate;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AttestationRequestContractTest {
    @Test
    public void onlyExplicitNullAttestationKeyPermitsGenericRewrite() {
        Parcel request = request(false);
        assertTrue(Utils.usesDefaultAttestationKey(request));
        verify(request).enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR);
        verify(request).setDataPosition(28);

        request = request(true);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);
    }

    @Test
    public void missingOrMalformedRequestCannotBecomeDefaultIssuer() {
        Parcel request = request(false);
        when(request.dataAvail()).thenReturn(0);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        when(request.dataAvail()).thenReturn(64, 0);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        when(request.readInt()).thenReturn(0);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        when(request.readInt()).thenReturn(1, Integer.BYTES - 1);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        when(request.dataPosition()).thenReturn(28, Integer.MAX_VALUE - 1);
        when(request.readInt()).thenReturn(1, Integer.BYTES);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        when(request.dataSize()).thenReturn(40);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);

        request = request(false);
        doThrow(new SecurityException("wrong interface"))
                .when(request).enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR);
        assertFalse(Utils.usesDefaultAttestationKey(request));
        verify(request).setDataPosition(28);
    }

    @Test
    public void rawChainGateRejectsLeafOnlyAndOverLimitMetadataWithoutMutation() {
        assertFalse(Utils.isCertificateChainRewriteCandidate(null));
        KeyMetadata metadata = new KeyMetadata();
        byte[] leaf = new byte[] {1, 2, 3};
        metadata.certificate = leaf;
        for (byte[] issuers : new byte[][] {null, new byte[0], new byte[512 * 1024 + 1]}) {
            metadata.certificateChain = issuers;
            assertFalse(Utils.isCertificateChainRewriteCandidate(metadata));
            assertFalse(CertHack.applyCachedCertificateChain(metadata));
            assertSame(leaf, metadata.certificate);
            assertSame(issuers, metadata.certificateChain);
        }

        metadata.certificateChain = new byte[512 * 1024];
        metadata.certificate = new byte[64 * 1024];
        assertTrue(Utils.isCertificateChainRewriteCandidate(metadata));
        for (byte[] invalidLeaf : new byte[][] {null, new byte[0], new byte[64 * 1024 + 1]}) {
            metadata.certificate = invalidLeaf;
            assertFalse(Utils.isCertificateChainRewriteCandidate(metadata));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void leafOnlyMetadataCanReuseCachedReplacement() throws Exception {
        Field stateField = CertHack.class.getDeclaredField("state");
        stateField.setAccessible(true);
        Object state = stateField.get(null);
        Field cacheField = state.getClass().getDeclaredField("certificateCache");
        cacheField.setAccessible(true);
        Map<Object, Object> cache = (Map<Object, Object>) cacheField.get(state);
        Constructor<?> keyConstructor = Class.forName(CertHack.class.getName() + "$CacheKey")
                .getDeclaredConstructor(byte[].class);
        keyConstructor.setAccessible(true);
        Constructor<?> valueConstructor = Class.forName(CertHack.class.getName() + "$CachedCertificateChain")
                .getDeclaredConstructor(Certificate[].class, byte[].class, byte[].class);
        valueConstructor.setAccessible(true);
        byte[] original = new byte[] {1, 2, 3};
        Object key = keyConstructor.newInstance((Object) original.clone());
        Object value = valueConstructor.newInstance(new Certificate[0], new byte[] {4}, new byte[] {5});
        Object previous = cache.put(key, value);
        try {
            KeyMetadata metadata = new KeyMetadata();
            metadata.certificate = original.clone();
            metadata.certificateChain = new byte[] {6};
            assertTrue(CertHack.applyCachedCertificateChain(metadata));
            assertArrayEquals(new byte[] {4}, metadata.certificate);
            assertArrayEquals(new byte[] {5}, metadata.certificateChain);

            for (byte[] chain : new byte[][] {null, new byte[0]}) {
                metadata.certificate = original.clone();
                metadata.certificateChain = chain;
                assertTrue(CertHack.applyCachedCertificateChain(metadata));
                assertArrayEquals(new byte[] {4}, metadata.certificate);
                assertArrayEquals(new byte[] {5}, metadata.certificateChain);
            }

            metadata.certificate = null;
            assertFalse(CertHack.applyCachedCertificateChain(metadata));
            metadata.certificate = new byte[0];
            assertFalse(CertHack.applyCachedCertificateChain(metadata));
            metadata.certificate = new byte[64 * 1024 + 1];
            assertFalse(CertHack.applyCachedCertificateChain(metadata));
        } finally {
            if (previous == null) cache.remove(key);
            else cache.put(key, previous);
        }
    }

    @Test
    public void attestationRequestedDetectsChallengeTagInParams() {
        Parcel request = attestedRequest(false);
        assertTrue(Utils.isAttestationRequested(request));
        verify(request).enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR);
        verify(request).setDataPosition(28);

        request = attestedRequest(true);
        assertTrue(Utils.isAttestationRequested(request));
        verify(request).setDataPosition(28);
    }

    @Test
    public void nonAttestedRequestReturnsFalseWithoutAttestationChallenge() {
        Parcel request = request(false);
        assertFalse(Utils.isAttestationRequested(request));
        verify(request).setDataPosition(28);

        assertFalse(Utils.isAttestationRequested(null));
    }

    @Test
    public void attestationRequestedSkipsNonChallengeParams() {
        Parcel request = mock(Parcel.class);
        when(request.dataPosition()).thenReturn(28, 32, 48, 52, 64);
        when(request.dataAvail()).thenReturn(128);
        when(request.readInt()).thenReturn(
                1, 16, // key
                0,     // attestationKey null
                2,     // 2 params
                1, 12, Tag.PURPOSE, // param 0 is not challenge
                1, 12, Tag.ATTESTATION_CHALLENGE // param 1 is challenge
        );
        when(request.dataSize()).thenReturn(256);
        assertTrue(Utils.isAttestationRequested(request));
        verify(request).setDataPosition(28);
    }

    @Test
    public void stripAttestationChallengeOverwritesChallengeTagInParams() {
        Parcel request = attestedRequest(true);
        assertTrue(Utils.stripAttestationChallenge(request));
        verify(request).enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR);
        verify(request).writeInt(Tag.INVALID);
        verify(request).setDataPosition(28);
    }

    @Test
    public void stripAttestationChallengeReturnsFalseWhenNoChallengePresent() {
        Parcel request = request(false);
        assertFalse(Utils.stripAttestationChallenge(request));
        verify(request).setDataPosition(28);

        assertFalse(Utils.stripAttestationChallenge(null));
    }

    static Parcel request(boolean explicitIssuer) {
        Parcel request = mock(Parcel.class);
        when(request.dataPosition()).thenReturn(28, 32);
        when(request.dataAvail()).thenReturn(64);
        when(request.readInt()).thenReturn(1, 16, explicitIssuer ? 1 : 0);
        when(request.dataSize()).thenReturn(128);
        return request;
    }

    static Parcel attestedRequest(boolean explicitIssuer) {
        Parcel request = mock(Parcel.class);
        when(request.dataPosition()).thenReturn(28, 32, 48, 52);
        when(request.dataAvail()).thenReturn(128);
        if (explicitIssuer) {
            when(request.readInt()).thenReturn(
                    1, 16, // key: presence, size
                    1, 16, // attestationKey: presence, size
                    1,     // params: count = 1
                    1, 12, // param 0: presence, size
                    Tag.ATTESTATION_CHALLENGE // param 0 tag
            );
        } else {
            when(request.readInt()).thenReturn(
                    1, 16, // key: presence, size
                    0,     // attestationKey: null presence
                    1,     // params: count = 1
                    1, 12, // param 0: presence, size
                    Tag.ATTESTATION_CHALLENGE // param 0 tag
            );
        }
        when(request.dataSize()).thenReturn(256);
        return request;
    }
}
