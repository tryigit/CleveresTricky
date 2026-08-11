package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils
import java.util.concurrent.locks.LockSupport

/**
 * Rewrites only the certificate chain returned by a successful, genuine
 * KeyMint key generation. The private key and every later cryptographic
 * operation remain owned by the platform security level.
 */
class SecurityLevelInterceptor : BinderInterceptor() {
    companion object {
        private const val GENERATE_KEY_EQUALIZATION_NANOS = 2_000_000L
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
            !PolicyState.rkpPassthrough(callingUid) &&
            CertHack.canHack() &&
            Config.needHack(callingUid)
        ) {
            // Keep attested and non-attested generateKey calls on the same small timing base.
            // Certificate rewriting happens only for attested replies; without this common delay
            // repeated samples expose that branch even though both operations otherwise succeed.
            LockSupport.parkNanos(GENERATE_KEY_EQUALIZATION_NANOS)
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
        if (
            code != generateKeyTransaction ||
            PolicyState.rkpPassthrough(callingUid) ||
            reply == null ||
            resultCode != 0 ||
            !CertHack.canHack() ||
            !Config.needHack(callingUid)
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
            val originalChain = Utils.getCertificateChain(metadata)
            if (originalChain == null) {
                replacement.recycle()
                return Skip
            }
            val rewritten = CertHack.hackCertificateChain(originalChain, callingUid)
            if (rewritten === originalChain) {
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
