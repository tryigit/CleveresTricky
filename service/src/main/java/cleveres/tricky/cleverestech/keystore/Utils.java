package cleveres.tricky.cleverestech.keystore;

import android.os.Parcel;
import android.system.keystore2.IKeystoreSecurityLevel;
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
    private static final String ANDROID_ATTESTATION_EXTENSION_OID = "1.3.6.1.4.1.11129.2.1.17";
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

    /** Reads the generateKey AIDL prefix without changing the caller's parcel position. */
    public static boolean usesDefaultAttestationKey(Parcel request) {
        int position = request.dataPosition();
        try {
            request.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR);
            if (!skipStableTypedParcelable(request) || request.dataAvail() < Integer.BYTES) {
                return false;
            }
            // Parcel.writeTypedObject writes zero for null and a nonzero presence marker otherwise.
            // We only need to distinguish the optional attestationKey, so do not instantiate its
            // hidden platform class or depend on a generated CREATOR field that may change by API.
            return request.readInt() == 0;
        } catch (RuntimeException invalidRequest) {
            return false;
        } finally {
            request.setDataPosition(position);
        }
    }

    /**
     * Skips one non-null stable-AIDL typed parcelable without allocating or binding to its Java ABI.
     * Stable parcelables are size-prefixed; reject missing, undersized, overflowing, or truncated
     * values instead of allowing an ambiguous prefix to select the default attestation key.
     */
    private static boolean skipStableTypedParcelable(Parcel request) {
        if (request.dataAvail() < Integer.BYTES || request.readInt() != 1 ||
                request.dataAvail() < Integer.BYTES) {
            return false;
        }

        int parcelableStart = request.dataPosition();
        int parcelableSize = request.readInt();
        if (parcelableSize < Integer.BYTES ||
                parcelableStart > Integer.MAX_VALUE - parcelableSize) {
            return false;
        }

        int parcelableEnd = parcelableStart + parcelableSize;
        if (parcelableEnd > request.dataSize()) return false;
        request.setDataPosition(parcelableEnd);
        return true;
    }

    /**
     * KeyCreationResult's caller-provided ATTEST_KEY case returns only the signed leaf; its
     * issuer chain belongs to the caller. Non-attested keys also have no issuer chain.
     * Neither case permits substituting a generic keybox issuer, including after cache eviction
     * or restart. Check the raw metadata before cache lookup, X.509 parsing or backend IPC.
     */
    public static boolean isCertificateChainRewriteCandidate(KeyMetadata metadata) {
        return metadata != null &&
                isCertificateChainRewriteCandidate(metadata.certificate, metadata.certificateChain);
    }

    /** Raw-byte form keeps platform-contract tests independent of hidden KeyMetadata constructors. */
    public static boolean isCertificateChainRewriteCandidate(
            byte[] certificate, byte[] certificateChain) {
        return certificate != null && certificate.length > 0 &&
                certificate.length <= MAX_CERTIFICATE_BYTES &&
                certificateChain != null && certificateChain.length > 0 &&
                certificateChain.length <= MAX_CHAIN_BYTES;
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

    /**
     * Returns whether the already-parsed leaf carries Android's attestation extension.
     *
     * This check intentionally stays in-process. A normal AndroidKeyStore asymmetric key also
     * carries a self-signed X.509 certificate, but forwarding that certificate to the Rust
     * attestation parser creates a measurable UDS/parser cost on the non-attested generateKey path.
     * The platform X509Certificate implementation has already parsed the certificate, so checking
     * the fixed extension OID is bounded and avoids any backend IPC for that common negative case.
     */
    public static boolean hasAndroidAttestationExtension(Certificate certificate) {
        if (!(certificate instanceof X509Certificate x509Certificate)) return false;
        try {
            return x509Certificate.getExtensionValue(ANDROID_ATTESTATION_EXTENSION_OID) != null;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not inspect Android attestation extension");
            return false;
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

    static byte[] encodeIssuerChain(Certificate[] chain) throws CertificateException {
        if (chain.length == 1) return new byte[0];

        IdentityHashMap<Certificate, EncodedIssuerChain> cache = ENCODED_ISSUER_CHAINS.get();
        Certificate cacheKey = chain[1];
        EncodedIssuerChain cached = cache.get(cacheKey);
        if (cached != null && cached.matches(chain)) return cached.encoded;

        FastByteArrayOutputStream output = new FastByteArrayOutputStream(2048);
        try {
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
        } finally {
            output.wipe();
        }
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
