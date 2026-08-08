package cleveres.tricky.cleverestech

import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.Utils

/**
 * Rewrites only the certificate chain returned by a successful, genuine
 * KeyMint key generation. The private key and every later cryptographic
 * operation remain owned by the platform security level.
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
    ): Result =
        if (
            code == generateKeyTransaction &&
            !Config.isRkpPassthroughEnabled &&
            CertHack.canHack() &&
            Config.needHack(callingUid)
        ) {
            Continue
        } else {
            Skip
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
            Config.isRkpPassthroughEnabled ||
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
