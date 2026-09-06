package cleveres.tricky.cleverestech.keystore;

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
    public void removingIssuerChainCannotReuseAnOlderGenericReplacement() throws Exception {
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

            for (byte[] chain : new byte[][] {null, new byte[0]}) {
                metadata.certificate = original.clone();
                metadata.certificateChain = chain;
                assertFalse(CertHack.applyCachedCertificateChain(metadata));
                assertArrayEquals(original, metadata.certificate);
                assertSame(chain, metadata.certificateChain);
            }
        } finally {
            if (previous == null) cache.remove(key);
            else cache.put(key, previous);
        }
    }

    static Parcel request(boolean explicitIssuer) {
        Parcel request = mock(Parcel.class);
        when(request.dataPosition()).thenReturn(28, 32);
        when(request.dataAvail()).thenReturn(64);
        when(request.readInt()).thenReturn(1, 16, explicitIssuer ? 1 : 0);
        when(request.dataSize()).thenReturn(128);
        return request;
    }
}
