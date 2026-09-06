package cleveres.tricky.cleverestech

import android.hardware.security.keymint.ErrorCode
import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import java.security.cert.Certificate

/**
 * Rewrites only the certificate chain returned by a successful, genuine TEE
 * or StrongBox KeyMint key generation. The private key and every later cryptographic
 * operation remain owned by the platform security level.
 *
 * This interceptor is registered on both the TEE and StrongBox child binders.
 * Targeted generateKey and getKeyEntry calls use the same certificate-compatibility
 * path. No synthetic timing delay is added here; certificate caching in
 * CertHack handles repeated reads without parking Keystore threads.
 */
class SecurityLevelInterceptor : BinderInterceptor() {
    companion object {
        private const val EX_SERVICE_SPECIFIC = -8

        private val generateKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")

        val INTERCEPTED_CODES = validTransactCodes(generateKeyTransaction)
    }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (
            code == generateKeyTransaction &&
            CertHack.canHack() &&
            Config.needHack(callingUid)
        ) {
            return Continue
        }

        return Skip
    }

    override fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result {
        // The native hook only sends POST_TRANSACT after PRE_TRANSACT returned Continue.
        // Target scope was therefore already resolved above; do not repeat Config.needHack()
        // or CertHack.canHack() on the latency-sensitive generateKey reply path.
        if (
            code != generateKeyTransaction ||
            reply == null ||
            resultCode != 0
        ) {
            return Skip
        }

        val replacement = Parcel.obtain()
        return try {
            reply.readException()
            val metadata = reply.readTypedObject(KeyMetadata.CREATOR)
            if (metadata == null) {
                replacement.recycle()
                return Skip
            }
            val isFullChain = Utils.isCertificateChainRewriteCandidate(metadata)
            val isLeafOnly = Utils.hasRewritableLeafCertificate(metadata)
            if (!isFullChain && !isLeafOnly) {
                replacement.recycle()
                return Skip
            }

            // Cryptographic AttestKey Contract:
            // When an application requests attestation using a caller-provided attestationKey,
            // KeyMint hardware signs the child leaf with the private key of that attestationKey.
            // Hardware refuses arbitrary data signing, so the module cannot sign a modified
            // certificate using the caller's attestation key.
            // Returning the genuine hardware leaf leaks the un-spoofed hardware RootOfTrust,
            // creating a divergence against the spoofed default attestation path.
            // Returning CANNOT_ATTEST_KEYS gracefully notifies the caller that user-provided
            // attestation keys are not supported by this security level.
            if (!Utils.usesDefaultAttestationKey(data)) {
                val errorReply = Parcel.obtain()
                errorReply.writeInt(EX_SERVICE_SPECIFIC)
                errorReply.writeString("AttestKey is unsupported")
                errorReply.writeInt(ErrorCode.CANNOT_ATTEST_KEYS)
                replacement.recycle()
                return OverrideReply(0, errorReply)
            }

            // Parse only the leaf first. A normal asymmetric key without an Android attestation
            // challenge still has a self-signed X.509 leaf, but it must never cross the Rust
            // certificate backend boundary. 2.5.8 rejected that case locally; preserving the same
            // zero-backend fast path avoids a measurable non-attested-only UDS/parser cost.
            val originalLeaf = Utils.getLeafCertificate(metadata)
            if (
                originalLeaf == null ||
                !Utils.hasAndroidAttestationExtension(originalLeaf)
            ) {
                replacement.recycle()
                return Skip
            }

            // A successful TEE or StrongBox attestation rewrite discards Android's genuine issuer chain and
            // replaces it with the selected keybox chain. Parsing every genuine issuer first
            // therefore adds work only to attested generateKey calls. Keep the hot path leaf-only
            // until CertHack confirms that a replacement can actually be produced.
            val originalLeafOnly = arrayOf<Certificate>(originalLeaf)
            val rewritten = CertHack.hackCertificateChain(
                originalLeafOnly,
                callingUid,
                true,
                false,
            )
            if (rewritten === originalLeafOnly) {
                replacement.recycle()
                return Skip
            }

            Utils.putCertificateChain(metadata, rewritten)
            replacement.writeNoException()
            replacement.writeTypedObject(metadata, 0)
            OverrideReply(0, replacement)
        } catch (error: Throwable) {
            replacement.recycle()
            if (error.javaClass.simpleName != "ServiceSpecificException") {
                Logger.e("Could not rewrite a generated attestation chain: ${error.javaClass.simpleName}")
            }
            Skip
        }
    }
}
