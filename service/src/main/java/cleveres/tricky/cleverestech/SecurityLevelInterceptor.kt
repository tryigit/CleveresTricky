package cleveres.tricky.cleverestech

import android.hardware.security.keymint.KeyParameter
import android.hardware.security.keymint.KeyParameterValue
import android.hardware.security.keymint.Tag
import android.os.IBinder
import android.os.Parcel
import android.system.keystore2.Authorization
import android.system.keystore2.IKeystoreSecurityLevel
import android.system.keystore2.KeyDescriptor
import android.system.keystore2.KeyEntryResponse
import android.system.keystore2.KeyMetadata
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import cleveres.tricky.cleverestech.keystore.CertHack.KeyGenParameters
import cleveres.tricky.cleverestech.keystore.Utils
import java.security.KeyPair
import java.security.cert.Certificate
class SecurityLevelInterceptor(
    private val original: IKeystoreSecurityLevel,
    private val level: Int
) : BinderInterceptor() {
    companion object {
        private val generateKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")
        private val keys = KeyCache<Key, Info>(1000)

        fun getKeyResponse(uid: Int, alias: String): KeyEntryResponse? =
            keys[Key(uid, alias)]?.response
    }

    data class Key(val uid: Int, val alias: String)
    data class Info(val keyPair: KeyPair, val response: KeyEntryResponse)

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel
    ): Result {
        if (code == generateKeyTransaction && Config.needGenerate(callingUid)) {
            Logger.i("intercept key gen uid=$callingUid pid=$callingPid")
            kotlin.runCatching {
                data.enforceInterface(IKeystoreSecurityLevel.DESCRIPTOR)
                val keyDescriptor =
                    data.readTypedObject(KeyDescriptor.CREATOR) ?: return@runCatching
                val attestationKeyDescriptor = data.readTypedObject(KeyDescriptor.CREATOR)
                val params = data.createTypedArray(KeyParameter.CREATOR)!!
                // val aFlags = data.readInt()
                // val entropy = data.createByteArray()
                val kgp = KeyGenParameters(params)
                if (kgp.attestationChallenge != null) {
                    if (attestationKeyDescriptor != null) {
                        Logger.e("warn: attestation key not supported now")
                    } else {
                        val pair = CertHack.generateKeyPair(callingUid, keyDescriptor, kgp)
                            ?: return@runCatching
                        val response = buildResponse(pair.second, kgp, keyDescriptor, callingUid)
                        keys[Key(callingUid, keyDescriptor.alias)] = Info(pair.first, response)
                        val p = Parcel.obtain()
                        p.writeNoException()
                        p.writeTypedObject(response.metadata, 0)
                        return OverrideReply(0, p)
                    }
                }
            }.onFailure {
                Logger.e("parse key gen request", it)
            }
        }
        return Skip
    }

    private fun buildResponse(
        chain: List<Certificate>,
        params: KeyGenParameters,
        descriptor: KeyDescriptor,
        callingUid: Int
    ): KeyEntryResponse {
        val response = KeyEntryResponse()
        val metadata = KeyMetadata()
        metadata.keySecurityLevel = level
        Utils.putCertificateChain(metadata, chain.toTypedArray<Certificate>())
        val d = KeyDescriptor()
        d.domain = descriptor.domain
        d.nspace = descriptor.nspace
        metadata.key = d
        val authorizations = ArrayList<Authorization>(params.purpose.size + params.digest.size + 6)

        fun addAuth(tag: Int, value: KeyParameterValue) {
            val a = Authorization()
            a.keyParameter = KeyParameter()
            a.keyParameter.tag = tag
            a.keyParameter.value = value
            a.securityLevel = level
            authorizations.add(a)
        }

        val purposeSize = params.purpose.size
        for (idx in 0 until purposeSize) {
            val i = params.purpose[idx]
            addAuth(Tag.PURPOSE, KeyParameterValue.keyPurpose(i))
        }
        val digestSize = params.digest.size
        for (idx in 0 until digestSize) {
            val i = params.digest[idx]
            addAuth(Tag.DIGEST, KeyParameterValue.digest(i))
        }
        addAuth(Tag.ALGORITHM, KeyParameterValue.algorithm(params.algorithm))
        addAuth(Tag.KEY_SIZE, KeyParameterValue.integer(params.keySize))
        addAuth(Tag.EC_CURVE, KeyParameterValue.ecCurve(params.ecCurve))
        if (params.isNoAuthRequired) {
            addAuth(Tag.NO_AUTH_REQUIRED, KeyParameterValue.boolValue(true))
        }
        addAuth(Tag.ORIGIN, KeyParameterValue.origin(0 /* KeyOrigin.GENERATED */))
        addAuth(Tag.OS_VERSION, KeyParameterValue.integer(osVersion))
        addAuth(Tag.OS_PATCHLEVEL, KeyParameterValue.integer(patchLevel))
        addAuth(Tag.VENDOR_PATCHLEVEL, KeyParameterValue.integer(patchLevelLong))
        addAuth(Tag.BOOT_PATCHLEVEL, KeyParameterValue.integer(patchLevelLong))
        addAuth(Tag.CREATION_DATETIME, KeyParameterValue.dateTime(System.currentTimeMillis()))
        addAuth(Tag.USER_ID, KeyParameterValue.integer(callingUid / 100000))

        metadata.authorizations = authorizations.toTypedArray<Authorization>()
        response.metadata = metadata
        response.iSecurityLevel = original
        return response
    }
}
