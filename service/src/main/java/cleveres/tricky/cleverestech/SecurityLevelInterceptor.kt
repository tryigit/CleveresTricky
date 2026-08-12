package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import java.security.cert.Certificate

/**
 * Rewrites only the certificate chain returned by a successful, genuine
 * KeyMint key generation. The private key and every later cryptographic
 * operation remain owned by the platform security level.
 *
 * Targeted generateKey and getKeyEntry calls deliberately use the same
 * certificate-compatibility path. The retired RKP passthrough switch must not
 * split those two paths, otherwise the same alias can expose two different
 * attestation leaves. No synthetic timing delay is added here; certificate
 * caching in CertHack handles repeated reads without parking Keystore threads.
 */
class SecurityLevelInterceptor : BinderInterceptor() {
    companion object {
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
        return if (
            code == generateKeyTransaction &&
            CertHack.canHack() &&
            Config.needHack(callingUid)
        ) {
            Continue
        } else {
            Skip
        }
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
        // on the latency-sensitive generateKey reply path.
        if (
            code != generateKeyTransaction ||
            reply == null ||
            resultCode != 0 ||
            !CertHack.canHack()
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

            // A successful attestation rewrite discards Android's genuine issuer chain and
            // replaces it with the selected keybox chain. Parsing every genuine issuer first
            // therefore adds work only to attested generateKey calls. Keep the hot path leaf-only
            // until CertHack confirms that a replacement can actually be produced.
            val originalLeaf = Utils.getLeafCertificate(metadata)
            if (originalLeaf == null) {
                replacement.recycle()
                return Skip
            }
            val originalLeafOnly = arrayOf<Certificate>(originalLeaf)
            val rewritten = CertHack.hackCertificateChain(originalLeafOnly, callingUid)
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
            Logger.e("Could not rewrite a generated attestation chain: ${error.javaClass.simpleName}")
            Skip
        }
    }
}
