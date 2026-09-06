package cleveres.tricky.cleverestech.keystore;

import android.security.keystore.KeyProperties;
import android.system.keystore2.KeyMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import cleveres.tricky.cleverestech.CertificateBackend;
import cleveres.tricky.cleverestech.Config;
import cleveres.tricky.cleverestech.KeyboxActivation;
import cleveres.tricky.cleverestech.KeyboxLoader;
import cleveres.tricky.cleverestech.Logger;
import cleveres.tricky.cleverestech.PolicyState;
import cleveres.tricky.cleverestech.UtilKt;

public final class CertHack {
    private static final int MAX_CERTIFICATE_CACHE_ENTRIES = 64;
    private static final int MAX_LEAF_CERTIFICATE_BYTES = 64 * 1024;
    private static final int BACKEND_KEY_ID_BYTES = 16;
    private static final String BACKEND_KEY_FORMAT = "CleveresTricky-KeyId-v1";
    private static final String[] ATTESTATION_ID_NAMES =
            {"BRAND", "DEVICE", "PRODUCT", "SERIAL", "IMEI", "MEID", "MANUFACTURER", "MODEL", "IMEI2"};
    private static final int[] ATTESTATION_ID_TAGS = {710, 711, 712, 713, 714, 715, 716, 717, 723};

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

    private static final class PreparedKeyBox {
        final String signatureAlgorithm;
        final Certificate[] issuerChain;

        PreparedKeyBox(KeyBox keybox) throws Exception {
            if (keybox.certificates.isEmpty()) throw new IOException("Keybox has no certificates");
            this.signatureAlgorithm = signatureAlgorithmForKeybox(keybox);
            if (this.signatureAlgorithm == null) throw new IOException("Unsupported keybox algorithm");
            this.issuerChain = keybox.certificates.toArray(new Certificate[0]);
            if (!BACKEND_KEY_FORMAT.equals(keybox.keyPair.getPrivate().getFormat())) {
                throw new IOException("Production keybox does not use an opaque backend key handle");
            }
            byte[] encoded = keybox.keyPair.getPrivate().getEncoded();
            try {
                if (encoded == null || encoded.length != BACKEND_KEY_ID_BYTES) {
                    throw new IOException("Opaque backend key identifier is invalid");
                }
                int aggregate = 0;
                for (byte value : encoded) aggregate |= value & 0xFF;
                if (aggregate == 0) throw new IOException("Opaque backend key identifier is zero");
            } finally {
                if (encoded != null) Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * One immutable cache value serves both compatibility APIs and the latency-sensitive raw
     * KeyMetadata readback path. Replacement entries retain public encoded certificate bytes.
     * Passthrough entries are marker-only and deliberately retain no certificate or chain arrays.
     */
    private static final class CachedCertificateChain {
        final Certificate[] certificates;
        final byte[] leafEncoded;
        final byte[] issuerChainEncoded;
        final boolean passthrough;

        CachedCertificateChain(
                Certificate[] certificates,
                byte[] leafEncoded,
                byte[] issuerChainEncoded
        ) {
            this.certificates = certificates.clone();
            this.leafEncoded = Objects.requireNonNull(leafEncoded, "leafEncoded");
            this.issuerChainEncoded = Objects.requireNonNull(issuerChainEncoded, "issuerChainEncoded");
            this.passthrough = false;
        }

        private CachedCertificateChain() {
            this.certificates = null;
            this.leafEncoded = null;
            this.issuerChainEncoded = null;
            this.passthrough = true;
        }

        static CachedCertificateChain passthrough() {
            return new CachedCertificateChain();
        }

        Certificate[] certificateCopy() {
            return passthrough ? null : certificates.clone();
        }

        void applyTo(KeyMetadata metadata) {
            if (passthrough) return;
            // Parcel.writeTypedObject copies these byte arrays synchronously. The transient
            // KeyMetadata object never owns or mutates the cache storage after the reply is built.
            metadata.certificate = leafEncoded;
            metadata.certificateChain = issuerChainEncoded;
        }
    }

    private static class State {
        final Map<String, List<KeyBox>> keyboxes;
        final Map<String, List<KeyBox>> keyboxFiles;
        final Map<KeyBox, PreparedKeyBox> preparedKeyboxes;
        final Map<CacheKey, CachedCertificateChain> certificateCache;
        Object certificateCacheEpoch;

        State(Map<String, List<KeyBox>> keyboxes, Map<String, List<KeyBox>> keyboxFiles) {
            this.keyboxes = immutableLists(keyboxes);
            this.keyboxFiles = immutableLists(keyboxFiles);
            IdentityHashMap<KeyBox, PreparedKeyBox> prepared = new IdentityHashMap<>();
            for (List<KeyBox> list : this.keyboxes.values()) {
                for (KeyBox keybox : list) {
                    if (prepared.containsKey(keybox)) continue;
                    try {
                        prepared.put(keybox, new PreparedKeyBox(keybox));
                    } catch (Exception error) {
                        Logger.e("Could not prepare opaque keybox metadata", error);
                    }
                }
            }
            this.preparedKeyboxes = Collections.unmodifiableMap(prepared);
            this.certificateCache = Collections.synchronizedMap(
                    new LinkedHashMap<CacheKey, CachedCertificateChain>(32, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<CacheKey, CachedCertificateChain> eldest
                        ) {
                            return size() > MAX_CERTIFICATE_CACHE_ENTRIES;
                        }
                    });
            this.certificateCacheEpoch = new Object();
        }

        private static Map<String, List<KeyBox>> immutableLists(Map<String, List<KeyBox>> source) {
            Map<String, List<KeyBox>> copy = new HashMap<>();
            source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Map.copyOf(copy);
        }
    }

    private static volatile State state = new State(Collections.emptyMap(), Collections.emptyMap());
    private static volatile byte[] capturedHardwareBootKey = null;

    private static final class CacheKey {
        private final byte[] leafEncoded;
        private final int hashCode;

        CacheKey(byte[] leafEncoded) {
            this.leafEncoded = Objects.requireNonNull(leafEncoded, "leafEncoded");
            this.hashCode = Arrays.hashCode(this.leafEncoded);
        }

        int indexForPool(int size) {
            if (size <= 0) throw new IllegalArgumentException("Keybox pool is empty");
            return (hashCode & 0x7FFFFFFF) % size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            return Arrays.equals(leafEncoded, ((CacheKey) o).leafEncoded);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    public static boolean canHack() {
        return !state.keyboxes.isEmpty();
    }

    public static int getKeyboxCount() {
        if (!KeyboxLoader.isActiveSetHealthy()) {
            throw new IllegalStateException("Rust keybox backend activation is unavailable");
        }
        return getPublishedKeyboxCountForTesting();
    }

    /** Counts active keybox sources, not EC/RSA key records inside each source. */
    public static int getKeyboxSourceCount() {
        if (!KeyboxLoader.isActiveSetHealthy()) {
            throw new IllegalStateException("Rust keybox backend activation is unavailable");
        }
        return state.keyboxFiles.size();
    }

    public static String getDeviceCertificateSerial(String filename) {
        if (filename == null) return null;
        List<KeyBox> boxes = state.keyboxFiles.get(filename);
        if (boxes == null) return null;
        for (KeyBox box : boxes) {
            String serial = getDeviceCertificateSerial(box);
            if (serial != null) return serial;
        }
        return null;
    }

    public static String getDeviceCertificateSerial(KeyBox keybox) {
        if (keybox == null || keybox.certificates().size() < 3) return null;
        Certificate certificate = keybox.certificates().get(2);
        if (!(certificate instanceof X509Certificate x509)) return null;
        return x509.getSerialNumber().toString(16).toUpperCase(Locale.ROOT);
    }

    /**
     * JVM-unit-test seam for inspecting the already-published managed snapshot without
     * probing backend health or triggering backend recovery.
     */
    public static int getPublishedKeyboxCountForTesting() {
        int count = 0;
        for (List<KeyBox> list : state.keyboxes.values()) count += list.size();
        return count;
    }

    /**
     * JVM-unit-test compatibility seam. The managed/BC parser is physically present only in
     * src/test; release builds have no such class and therefore cannot execute a managed parser.
     */
    @SuppressWarnings("unchecked")
    public static List<KeyBox> parseKeyboxXml(java.io.Reader reader, String filename) {
        try {
            Class<?> oracle = Class.forName("cleveres.tricky.cleverestech.keystore.ManagedKeyboxOracle");
            Object parsed = oracle
                    .getMethod("parse", java.io.Reader.class, String.class)
                    .invoke(null, reader, filename);
            return parsed instanceof List<?> ? (List<KeyBox>) parsed : Collections.emptyList();
        } catch (ReflectiveOperationException unavailableOutsideTests) {
            return Collections.emptyList();
        }
    }

    static Map<Integer, byte[]> selectPresentAttestationIdOverrides(
            Map<Integer, byte[]> configured,
            List<Integer> originalTags
    ) {
        if (configured.isEmpty() || originalTags.isEmpty()) return Collections.emptyMap();
        Map<Integer, byte[]> selected = new HashMap<>();
        for (Integer tag : originalTags) {
            byte[] value = configured.get(tag);
            if (value != null) selected.put(tag, value);
        }
        return selected;
    }

    static String signingKeyAlgorithm(String signatureAlgorithm) {
        if (signatureAlgorithm == null) return null;
        String normalized = signatureAlgorithm.toUpperCase(Locale.ROOT);
        if (normalized.contains("ECDSA")) return KeyProperties.KEY_ALGORITHM_EC;
        if (normalized.contains("RSA")) return KeyProperties.KEY_ALGORITHM_RSA;
        return null;
    }

    public static synchronized void setKeyboxes(List<KeyBox> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            Logger.i("clear all keyboxes");
            state = new State(Collections.emptyMap(), Collections.emptyMap());
            return;
        }
        Map<String, List<KeyBox>> newKeyboxes = new HashMap<>();
        Map<String, List<KeyBox>> newKeyboxFiles = new HashMap<>();
        for (KeyBox box : boxes) {
            String algo = normalizeAlgorithm(box.keyPair.getPublic().getAlgorithm());
            if (algo == null) {
                Logger.e("Ignoring unsupported keybox algorithm: " + box.keyPair.getPublic().getAlgorithm());
                continue;
            }
            try {
                new PreparedKeyBox(box);
            } catch (Exception error) {
                Logger.e("Ignoring keybox without a valid opaque backend handle", error);
                continue;
            }
            newKeyboxes.computeIfAbsent(algo, ignored -> new ArrayList<>()).add(box);
            newKeyboxFiles.computeIfAbsent(box.filename, ignored -> new ArrayList<>()).add(box);
        }
        int ecCount = newKeyboxes.getOrDefault(KeyProperties.KEY_ALGORITHM_EC, Collections.emptyList()).size();
        int rsaCount = newKeyboxes.getOrDefault(KeyProperties.KEY_ALGORITHM_RSA, Collections.emptyList()).size();
        Logger.i("update keyboxes: total=" + boxes.size() + " (EC=" + ecCount + ", RSA=" + rsaCount + ")");
        state = new State(newKeyboxes, newKeyboxFiles);
    }

    public static void clearCertificateCache() {
        State currentState = state;
        synchronized (currentState.certificateCache) {
            currentState.certificateCacheEpoch = new Object();
            currentState.certificateCache.clear();
        }
    }

    static Object captureCertificateCacheEpochForTesting() {
        State currentState = state;
        synchronized (currentState.certificateCache) {
            return currentState.certificateCacheEpoch;
        }
    }

    static boolean isCertificateCacheEpochCurrentForTesting(Object expectedEpoch) {
        State currentState = state;
        synchronized (currentState.certificateCache) {
            return currentState.certificateCacheEpoch == expectedEpoch;
        }
    }

    public static boolean hasCachedCertificateChains() {
        return !state.certificateCache.isEmpty();
    }

    /**
     * Applies a cached replacement or passthrough decision directly to raw KeyMetadata bytes.
     * A passthrough hit is intentionally a no-op and still returns true, allowing repeated
     * getKeyEntry calls to avoid X.509 parsing and Rust IPC while preserving the genuine reply.
     */
    public static boolean applyCachedCertificateChain(KeyMetadata metadata) {
        if (!Utils.isCertificateChainRewriteCandidate(metadata)) {
            return false;
        }
        State currentState = state;
        CachedCertificateChain cached;
        synchronized (currentState.certificateCache) {
            cached = currentState.certificateCache.get(new CacheKey(metadata.certificate));
        }
        if (cached == null) return false;
        cached.applyTo(metadata);
        return true;
    }

    public static Certificate[] getCachedCertificateChain(Certificate[] caList) {
        if (caList == null || caList.length == 0 || caList[0] == null) return null;
        try {
            byte[] leafEncoded = caList[0].getEncoded();
            if (leafEncoded.length == 0 || leafEncoded.length > MAX_LEAF_CERTIFICATE_BYTES) return null;
            CachedCertificateChain cached = state.certificateCache.get(new CacheKey(leafEncoded));
            if (cached == null) return null;
            Certificate[] replacement = cached.certificateCopy();
            return replacement == null ? caList : replacement;
        } catch (Throwable error) {
            Logger.e("Could not resolve a cached attestation chain", error);
            return null;
        }
    }

    /**
     * Rewrites one key's attestation chain once per policy snapshot. Portable X.509/DER inspection,
     * authorization-list rewriting and signing are performed by the unprivileged Rust backend.
     * Managed code resolves Android-derived policy facts, selects an opaque key handle and
     * materializes the final JCA X.509 object. Private key bytes never enter this process.
     */
    public static Certificate[] hackCertificateChain(Certificate[] caList, int uid) {
        if (caList == null || caList.length == 0 || caList[0] == null) {
            throw new UnsupportedOperationException("Certificate chain is empty");
        }
        KeyboxActivation.lockPublishedSnapshot();
        CertificateBackend.Inspection inspection = null;
        byte[] keyId = null;
        try {
            State currentState = state;
            byte[] leafEncoded = caList[0].getEncoded();
            if (leafEncoded.length == 0 || leafEncoded.length > MAX_LEAF_CERTIFICATE_BYTES) {
                Logger.e("Attestation leaf certificate has an invalid size");
                return caList;
            }
            CacheKey cacheKey = new CacheKey(leafEncoded);
            Map<CacheKey, CachedCertificateChain> cache = currentState.certificateCache;
            Object cacheEpoch;
            synchronized (cache) {
                CachedCertificateChain cached = cache.get(cacheKey);
                if (cached != null) {
                    Certificate[] replacement = cached.certificateCopy();
                    return replacement == null ? caList : replacement;
                }
                cacheEpoch = currentState.certificateCacheEpoch;
            }

            // Preserve the local non-attested fast path. Only genuine Android attestation leaves
            // cross the Rust certificate-inspection boundary.
            if (!Utils.hasAndroidAttestationExtension(caList[0])) return caList;

            // Security provenance is mandatory before choosing any replacement issuer. This is one
            // bounded inspection for a fresh attested leaf. The existing 64-entry LRU stores both
            // rewrite results and marker-only passthrough decisions, so repeated StrongBox reads do
            // not create recurring IPC, parsing, allocations, timers or background work.
            inspection = CertificateBackend.inspect(leafEncoded);
            if (inspection == null) return caList;
            int attLevel = inspection.getAttestationSecurityLevel();
            int kmLevel = inspection.getKeymintSecurityLevel();
            boolean isSoftware = attLevel == CertificateBackend.SECURITY_LEVEL_SOFTWARE
                    || kmLevel == CertificateBackend.SECURITY_LEVEL_SOFTWARE;
            boolean isStrongbox = attLevel == CertificateBackend.SECURITY_LEVEL_STRONGBOX
                    || kmLevel == CertificateBackend.SECURITY_LEVEL_STRONGBOX;
            boolean isTee = (attLevel == CertificateBackend.SECURITY_LEVEL_TEE
                    || kmLevel == CertificateBackend.SECURITY_LEVEL_TEE) && !isStrongbox;
            boolean isTeeOrStrongbox = !isSoftware && (isTee || isStrongbox);
            if (!isTeeOrStrongbox) {
                synchronized (cache) {
                    if (state == currentState && currentState.certificateCacheEpoch == cacheEpoch) {
                        cache.putIfAbsent(cacheKey, CachedCertificateChain.passthrough());
                    }
                }
                return caList;
            }

            boolean needsCapturedPatchLevels = PolicyState.INSTANCE.isFeatureEnabled(
                    PolicyState.Feature.SECURITY_PATCH, uid);
            byte[] originalBootKey = usableBootDigest(inspection.getOriginalBootKey());
            if (originalBootKey != null) {
                capturedHardwareBootKey = originalBootKey.clone();
            }
            byte[] verifiedBootKey = selectVerifiedBootDigest(
                    UtilKt.getBootKey(),
                    originalBootKey != null ? originalBootKey : capturedHardwareBootKey,
                    UtilKt.getPersistentBootKey());
            byte[] verifiedBootHash = selectVerifiedBootDigest(
                    UtilKt.getBootHash(), inspection.getOriginalBootHash(), UtilKt.getPersistentBootHash());
            Config.AttestationPatchLevels patchLevels = needsCapturedPatchLevels
                    ? PolicyState.INSTANCE.resolveAttestationPatchLevels(
                            uid,
                            inspection.getSystemPatch(),
                            inspection.getVendorPatch(),
                            inspection.getBootPatch())
                    : keepPatchLevels();

            String preferredSignerAlgorithm = KeyProperties.KEY_ALGORITHM_EC;
            var appConfig = Config.INSTANCE.getAppConfig(uid);
            List<KeyBox> list;
            if (appConfig != null && appConfig.getKeyboxFilename() != null) {
                list = selectKeyboxPool(
                        currentState.keyboxFiles.get(appConfig.getKeyboxFilename()), preferredSignerAlgorithm);
            } else {
                list = selectGlobalKeyboxPool(currentState, preferredSignerAlgorithm);
            }
            if (list.isEmpty()) throw new UnsupportedOperationException("No compatible keybox is available");

            KeyBox keybox = list.get(cacheKey.indexForPool(list.size()));
            PreparedKeyBox prepared = currentState.preparedKeyboxes.get(keybox);
            if (prepared == null) throw new UnsupportedOperationException("Keybox metadata is unavailable");
            int signingAlgorithm = signingWireAlgorithm(prepared.signatureAlgorithm);
            if (signingAlgorithm == 0) return caList;

            if (verifiedBootKey == null || verifiedBootHash == null) {
                Logger.e("Verified boot key/hash is unavailable; preserving the original certificate chain");
                return caList;
            }

            Map<Integer, byte[]> idOverrides = presentIdOverrides(uid, inspection.getPresentIdMask());
            byte[] moduleHash = inspection.getSupportsModuleHash()
                    ? Config.INSTANCE.getModuleHash()
                    : null;
            keyId = keybox.keyPair.getPrivate().getEncoded();
            if (keyId == null || keyId.length != BACKEND_KEY_ID_BYTES) return caList;

            byte[] rewrittenDer = CertificateBackend.rewrite(
                    leafEncoded,
                    keyId,
                    signingAlgorithm,
                    patchDisposition(patchLevels.getSystem()), patchLevels.getSystem().getValue(),
                    patchDisposition(patchLevels.getVendor()), patchLevels.getVendor().getValue(),
                    patchDisposition(patchLevels.getBoot()), patchLevels.getBoot().getValue(),
                    idOverrides,
                    moduleHash,
                    verifiedBootKey,
                    verifiedBootHash);
            if (rewrittenDer == null) return caList;
            Certificate rewrittenLeaf = CERTIFICATE_FACTORY.get().generateCertificate(
                    new ByteArrayInputStream(rewrittenDer));
            Certificate[] result = new Certificate[prepared.issuerChain.length + 1];
            result[0] = rewrittenLeaf;
            System.arraycopy(prepared.issuerChain, 0, result, 1, prepared.issuerChain.length);
            byte[] issuerChainEncoded = Utils.encodeIssuerChain(result);
            CachedCertificateChain completed =
                    new CachedCertificateChain(result, rewrittenDer, issuerChainEncoded);
            synchronized (cache) {
                if (state != currentState || currentState.certificateCacheEpoch != cacheEpoch) {
                    return result;
                }
                CachedCertificateChain raced = cache.get(cacheKey);
                if (raced != null) {
                    Certificate[] replacement = raced.certificateCopy();
                    return replacement == null ? caList : replacement;
                }
                cache.put(cacheKey, completed);
            }
            return result;
        } catch (Throwable t) {
            Logger.e("Exception in hackCertificateChain", t);
            return caList;
        } finally {
            if (keyId != null) Arrays.fill(keyId, (byte) 0);
            if (inspection != null) inspection.wipe();
            KeyboxActivation.unlockPublishedSnapshot();
        }
    }

    private static Config.AttestationPatchLevels keepPatchLevels() {
        Config.AttestationPatchComponent keep =
                new Config.AttestationPatchComponent(Config.PatchDisposition.KEEP, 0);
        return new Config.AttestationPatchLevels(keep, keep, keep);
    }

    private static Map<Integer, byte[]> configuredIdOverrides(int uid) {
        Map<Integer, byte[]> overrides = new HashMap<>();
        for (int index = 0; index < ATTESTATION_ID_TAGS.length; index++) {
            byte[] value = Config.INSTANCE.getAttestationId(ATTESTATION_ID_NAMES[index], uid);
            if (value != null) overrides.put(ATTESTATION_ID_TAGS[index], value);
        }
        return overrides;
    }

    private static Map<Integer, byte[]> presentIdOverrides(int uid, int mask) {
        Map<Integer, byte[]> overrides = new HashMap<>();
        for (int index = 0; index < ATTESTATION_ID_TAGS.length; index++) {
            if ((mask & (1 << index)) == 0) continue;
            byte[] value = Config.INSTANCE.getAttestationId(ATTESTATION_ID_NAMES[index], uid);
            if (value != null) overrides.put(ATTESTATION_ID_TAGS[index], value);
        }
        return overrides;
    }

    private static int patchDisposition(Config.AttestationPatchComponent component) {
        return switch (component.getDisposition()) {
            case KEEP -> CertificateBackend.PATCH_KEEP;
            case OMIT -> CertificateBackend.PATCH_OMIT;
            case REPLACE -> CertificateBackend.PATCH_REPLACE;
        };
    }

    private static int signingWireAlgorithm(String signatureAlgorithm) {
        if ("SHA256withECDSA".equals(signatureAlgorithm)) return CertificateBackend.SIGNING_EC_P256_SHA256;
        if ("SHA256withRSA".equals(signatureAlgorithm)) return CertificateBackend.SIGNING_RSA_PKCS1_SHA256;
        return 0;
    }

    static byte[] selectVerifiedBootDigest(byte[] runtime, byte[] original, byte[] persistent) {
        byte[] value = usableBootDigest(runtime);
        if (value != null) return value;
        value = usableBootDigest(original);
        if (value != null) return value;
        return usableBootDigest(persistent);
    }

    private static byte[] usableBootDigest(byte[] value) {
        if (value == null || value.length != 32) return null;
        int aggregate = 0;
        for (byte current : value) aggregate |= current & 0xFF;
        return aggregate == 0 ? null : value;
    }

    private static List<KeyBox> selectKeyboxPool(List<KeyBox> candidates, String preferredAlgorithm) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        if (preferredAlgorithm != null) {
            List<KeyBox> preferred = filterKeyboxesByAlgorithm(candidates, preferredAlgorithm);
            if (!preferred.isEmpty()) return preferred;
        }
        String fallbackAlgorithm = KeyProperties.KEY_ALGORITHM_EC.equals(preferredAlgorithm)
                ? KeyProperties.KEY_ALGORITHM_RSA : KeyProperties.KEY_ALGORITHM_EC;
        List<KeyBox> fallback = filterKeyboxesByAlgorithm(candidates, fallbackAlgorithm);
        if (!fallback.isEmpty()) return fallback;
        return filterKeyboxesByAlgorithm(candidates, KeyProperties.KEY_ALGORITHM_RSA);
    }

    private static List<KeyBox> selectGlobalKeyboxPool(State currentState, String preferredAlgorithm) {
        if (preferredAlgorithm != null) {
            List<KeyBox> preferred = currentState.keyboxes.get(preferredAlgorithm);
            if (preferred != null && !preferred.isEmpty()) return preferred;
        }
        String fallbackAlgorithm = KeyProperties.KEY_ALGORITHM_EC.equals(preferredAlgorithm)
                ? KeyProperties.KEY_ALGORITHM_RSA : KeyProperties.KEY_ALGORITHM_EC;
        List<KeyBox> fallback = currentState.keyboxes.get(fallbackAlgorithm);
        if (fallback != null && !fallback.isEmpty()) return fallback;
        List<KeyBox> rsa = currentState.keyboxes.get(KeyProperties.KEY_ALGORITHM_RSA);
        return rsa == null ? Collections.emptyList() : rsa;
    }

    private static String signatureAlgorithmForKeybox(KeyBox keybox) {
        String algorithm = normalizeAlgorithm(keybox.keyPair.getPrivate().getAlgorithm());
        if (KeyProperties.KEY_ALGORITHM_EC.equals(algorithm)) return "SHA256withECDSA";
        if (KeyProperties.KEY_ALGORITHM_RSA.equals(algorithm)) return "SHA256withRSA";
        return null;
    }

    private static String normalizeAlgorithm(String algorithm) {
        if (algorithm == null) return null;
        if (algorithm.equalsIgnoreCase("EC") || algorithm.equalsIgnoreCase("ECDSA")) {
            return KeyProperties.KEY_ALGORITHM_EC;
        }
        if (algorithm.equalsIgnoreCase("RSA")) return KeyProperties.KEY_ALGORITHM_RSA;
        return null;
    }

    private static List<KeyBox> filterKeyboxesByAlgorithm(List<KeyBox> candidates, String requiredAlgorithm) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<KeyBox> matches = new ArrayList<>();
        for (KeyBox candidate : candidates) {
            String alg = candidate.keyPair.getPublic().getAlgorithm();
            if (requiredAlgorithm.equals(alg) || requiredAlgorithm.equals(normalizeAlgorithm(alg))) {
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
