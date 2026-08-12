package cleveres.tricky.cleverestech.keystore;

import android.system.keystore2.KeyEntryResponse;
import android.system.keystore2.KeyMetadata;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;

import cleveres.tricky.cleverestech.util.FastByteArrayOutputStream;

public final class Utils {
    private static final String TAG = "Utils";
    private static final int MAX_CERTIFICATE_BYTES = 64 * 1024;
    private static final int MAX_CHAIN_BYTES = 512 * 1024;
    private static final int MAX_CERTIFICATES = 16;
    private static final int MAX_THREAD_ISSUER_CACHE_ENTRIES = 8;

    private static final ThreadLocal<CertificateFactory> CERTIFICATE_FACTORY =
            new ThreadLocal<CertificateFactory>() {
                @Override
                protected CertificateFactory initialValue() {
                    try {
                        return CertificateFactory.getInstance("X.509");
                    } catch (CertificateException error) {
                        Log.e(TAG, "X.509 certificate factory is unavailable");
                        return null;
                    }
                }
            };

    private static final class EncodedIssuerChain {
        final Certificate[] issuers;
        final byte[] encoded;

        EncodedIssuerChain(Certificate[] issuers, byte[] encoded) {
            this.issuers = issuers;
            this.encoded = encoded;
        }

        boolean matches(Certificate[] chain) {
            if (chain.length != issuers.length + 1) return false;
            for (int index = 0; index < issuers.length; index++) {
                if (chain[index + 1] != issuers[index]) return false;
            }
            return true;
        }
    }

    private static final ThreadLocal<IdentityHashMap<Certificate, EncodedIssuerChain>>
            ENCODED_ISSUER_CHAINS = ThreadLocal.withInitial(IdentityHashMap::new);

    private Utils() {
    }

    static X509Certificate toCertificate(byte[] encoded) {
        if (encoded == null || encoded.length == 0 ||
                encoded.length > MAX_CERTIFICATE_BYTES) {
            return null;
        }
        try {
            CertificateFactory factory = CERTIFICATE_FACTORY.get();
            if (factory == null) return null;
            return (X509Certificate) factory.generateCertificate(
                    new ByteArrayInputStream(encoded));
        } catch (CertificateException | ClassCastException error) {
            Log.w(TAG, "Could not parse an X.509 certificate");
            return null;
        }
    }

    private static List<X509Certificate> toCertificates(byte[] encoded) {
        if (encoded == null || encoded.length == 0 ||
                encoded.length > MAX_CHAIN_BYTES) {
            return List.of();
        }
        try {
            CertificateFactory factory = CERTIFICATE_FACTORY.get();
            if (factory == null) return List.of();
            Collection<? extends Certificate> parsed = factory.generateCertificates(
                    new ByteArrayInputStream(encoded));
            if (parsed.size() > MAX_CERTIFICATES) return List.of();

            List<X509Certificate> certificates = new ArrayList<>(parsed.size());
            for (Certificate certificate : parsed) {
                if (!(certificate instanceof X509Certificate x509Certificate)) {
                    return List.of();
                }
                certificates.add(x509Certificate);
            }
            return certificates;
        } catch (CertificateException error) {
            Log.w(TAG, "Could not parse an X.509 certificate chain");
            return List.of();
        }
    }

    /**
     * Parses only the leaf certificate from KeyMetadata. The attestation rewrite path replaces
     * the issuer chain with the selected keybox chain, so decoding the genuine issuer chain first
     * is unnecessary work on the latency-sensitive generateKey reply path.
     */
    public static X509Certificate getLeafCertificate(KeyMetadata metadata) {
        return metadata == null ? null : toCertificate(metadata.certificate);
    }

    public static Certificate[] getCertificateChain(KeyEntryResponse response) {
        return response == null ? null : getCertificateChain(response.metadata);
    }

    public static Certificate[] getCertificateChain(KeyMetadata metadata) {
        if (metadata == null) return null;
        X509Certificate leaf = getLeafCertificate(metadata);
        if (leaf == null) return null;

        List<X509Certificate> issuers =
                metadata.certificateChain == null
                        ? List.of()
                        : toCertificates(metadata.certificateChain);
        if (metadata.certificateChain != null && issuers.isEmpty()) return null;

        Certificate[] chain = new Certificate[issuers.size() + 1];
        chain[0] = leaf;
        for (int index = 0; index < issuers.size(); index++) {
            chain[index + 1] = issuers.get(index);
        }
        return chain;
    }

    private static byte[] encodeIssuerChain(Certificate[] chain) throws CertificateException {
        if (chain.length == 1) return new byte[0];

        IdentityHashMap<Certificate, EncodedIssuerChain> cache = ENCODED_ISSUER_CHAINS.get();
        Certificate cacheKey = chain[1];
        EncodedIssuerChain cached = cache.get(cacheKey);
        if (cached != null && cached.matches(chain)) return cached.encoded;

        FastByteArrayOutputStream output = new FastByteArrayOutputStream(2048);
        int total = 0;
        Certificate[] issuerReferences = new Certificate[chain.length - 1];
        for (int index = 1; index < chain.length; index++) {
            Certificate certificate = chain[index];
            byte[] encoded = certificate.getEncoded();
            if (encoded.length == 0 || encoded.length > MAX_CERTIFICATE_BYTES ||
                    encoded.length > MAX_CHAIN_BYTES - total) {
                throw new CertificateException("Invalid certificate-chain size");
            }
            output.write(encoded, 0, encoded.length);
            total += encoded.length;
            issuerReferences[index - 1] = certificate;
        }

        byte[] encodedChain = output.toByteArray();
        if (cache.size() >= MAX_THREAD_ISSUER_CACHE_ENTRIES && !cache.containsKey(cacheKey)) {
            cache.clear();
        }
        cache.put(cacheKey, new EncodedIssuerChain(issuerReferences, encodedChain));
        return encodedChain;
    }

    public static void putCertificateChain(KeyEntryResponse response, Certificate[] chain)
            throws CertificateException {
        if (response == null) throw new CertificateException("Missing key response");
        putCertificateChain(response.metadata, chain);
    }

    public static void putCertificateChain(KeyMetadata metadata, Certificate[] chain)
            throws CertificateException {
        if (metadata == null || chain == null || chain.length == 0 ||
                chain.length > MAX_CERTIFICATES) {
            throw new CertificateException("Invalid certificate chain");
        }

        byte[] leaf = chain[0].getEncoded();
        if (leaf.length == 0 || leaf.length > MAX_CERTIFICATE_BYTES) {
            throw new CertificateException("Invalid leaf certificate size");
        }

        metadata.certificate = leaf;
        metadata.certificateChain = encodeIssuerChain(chain);
    }
}
