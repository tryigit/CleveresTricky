package cleveres.tricky.cleverestech

import android.hardware.security.rkp.DeviceInfo
import android.hardware.security.rkp.IRemotelyProvisionedComponent
import android.hardware.security.rkp.MacedPublicKey
import android.hardware.security.rkp.ProtectedData
import android.hardware.security.rkp.RpcHardwareInfo
import android.os.IBinder
import android.os.Parcel
import cleveres.tricky.cleverestech.binder.BinderInterceptor
import cleveres.tricky.cleverestech.keystore.CertHack
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * Handles RKP (Remote Key Provisioning) interception.
 * This intercepts calls to IRemotelyProvisionedComponent and returns
 * spoofed responses so Play Integrity sees valid attestation.
 */
class RkpInterceptor(
    private val original: IRemotelyProvisionedComponent,
    private val securityLevel: Int
) : BinderInterceptor() {

    companion object {
        private val getHardwareInfoTransaction = 
            getTransactCode(IRemotelyProvisionedComponent.Stub::class.java, "getHardwareInfo")
        private val generateEcdsaP256KeyPairTransaction = 
            getTransactCode(IRemotelyProvisionedComponent.Stub::class.java, "generateEcdsaP256KeyPair")
        private val generateCertificateRequestTransaction = 
            getTransactCode(IRemotelyProvisionedComponent.Stub::class.java, "generateCertificateRequest")
        private val generateCertificateRequestV2Transaction = 
            getTransactCode(IRemotelyProvisionedComponent.Stub::class.java, "generateCertificateRequestV2")
        
        // we cache generated keys so they can be reused in cert requests
        private val keyPairCache = KeyCache<Int, KeyPairInfo>(100)
        private var keyPairCounter = 0
        
        data class KeyPairInfo(
            val keyPair: KeyPair,
            val macedPublicKey: ByteArray,
            val privateKeyHandle: ByteArray
        )
    }

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel
    ): Result {
        if (!Config.shouldBypassRkp()) return Skip
        
        when (code) {
            getHardwareInfoTransaction -> {
                Logger.i("intercepting RKP getHardwareInfo for uid=$callingUid")
                return interceptGetHardwareInfo()
            }
            generateEcdsaP256KeyPairTransaction -> {
                Logger.i("intercepting RKP generateEcdsaP256KeyPair for uid=$callingUid")
                return interceptKeyPairGeneration(callingUid, data)
            }
            generateCertificateRequestTransaction -> {
                Logger.i("intercepting RKP generateCertificateRequest for uid=$callingUid")
                return interceptCertificateRequest(callingUid, data, false)
            }
            generateCertificateRequestV2Transaction -> {
                Logger.i("intercepting RKP generateCertificateRequestV2 for uid=$callingUid")
                return interceptCertificateRequest(callingUid, data, true)
            }
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
        resultCode: Int
    ): Result {
        // nothing to do after the transaction
        return Skip
    }

    // returns fake hardware info that matches what Google expects
    private fun interceptGetHardwareInfo(): Result {
        kotlin.runCatching {
            val info = RpcHardwareInfo().apply {
                versionNumber = 3 // android 14+ uses version 3
                rpcAuthorName = "Google"
                supportedEekCurve = 2 // P-256 curve
                uniqueId = Config.getBuildVar("DEVICE") ?: "generic"
                supportedNumKeysInCsr = 20
            }
            
            val p = Parcel.obtain()
            p.writeNoException()
            p.writeTypedObject(info, 0)
            return OverrideReply(0, p)
        }.onFailure {
            Logger.e("failed to intercept getHardwareInfo", it)
        }
        return Skip
    }

    // generates a new EC P-256 key pair and wraps it in COSE format
    private fun interceptKeyPairGeneration(uid: Int, data: Parcel): Result {
        kotlin.runCatching {
            data.enforceInterface(IRemotelyProvisionedComponent.DESCRIPTOR)
            val testMode = data.readInt() != 0
            
            // create new P-256 key
            val keyPairGen = KeyPairGenerator.getInstance("EC")
            keyPairGen.initialize(ECGenParameterSpec("secp256r1"))
            val keyPair = keyPairGen.generateKeyPair()
            
            // wrap in COSE_Mac0 format
            val macedKey = createMacedPublicKey(keyPair)
            
            // store handle for later use in cert requests
            val handleIndex = keyPairCounter++
            val privateKeyHandle = ByteArray(32)
            privateKeyHandle[0] = (handleIndex shr 24).toByte()
            privateKeyHandle[1] = (handleIndex shr 16).toByte()
            privateKeyHandle[2] = (handleIndex shr 8).toByte()
            privateKeyHandle[3] = handleIndex.toByte()
            
            keyPairCache[handleIndex] = KeyPairInfo(keyPair, macedKey, privateKeyHandle)
            
            Logger.i("generated RKP key pair handle=$handleIndex for uid=$uid")
            
            val p = Parcel.obtain()
            p.writeNoException()
            val mpk = MacedPublicKey(macedKey)
            p.writeTypedObject(mpk, 0)
            p.writeByteArray(privateKeyHandle)
            
            return OverrideReply(0, p)
        }.onFailure {
            Logger.e("failed to intercept key pair generation for uid=$uid", it)
        }
        return Skip
    }

    // handles both v1 and v2 cert request formats
    private fun interceptCertificateRequest(uid: Int, data: Parcel, isV2: Boolean): Result {
        kotlin.runCatching {
            data.enforceInterface(IRemotelyProvisionedComponent.DESCRIPTOR)
            
            val keysToSign: Array<MacedPublicKey>?
            val challenge: ByteArray?
            
            if (isV2) {
                keysToSign = data.createTypedArray(MacedPublicKey.CREATOR)
                challenge = data.createByteArray()
            } else {
                // v1 has extra params we need to skip
                val testMode = data.readInt() != 0
                keysToSign = data.createTypedArray(MacedPublicKey.CREATOR)
                val endpointCertChain = data.createByteArray()
                challenge = data.createByteArray()
            }
            
            val response = createCertificateRequestResponse(keysToSign, challenge, isV2)
            
            Logger.i("generated RKP certificate request response for uid=$uid isV2=$isV2")
            
            val p = Parcel.obtain()
            p.writeNoException()
            
            if (isV2) {
                p.writeByteArray(response)
            } else {
                // v1 needs extra out params
                p.writeByteArray(response)
                val deviceInfo = DeviceInfo(createDeviceInfo())
                p.writeTypedObject(deviceInfo, 0)
                val protectedData = ProtectedData(createProtectedData())
                p.writeTypedObject(protectedData, 0)
            }
            
            return OverrideReply(0, p)
        }.onFailure {
            Logger.e("failed to intercept certificate request for uid=$uid", it)
        }
        return Skip
    }

    private fun createMacedPublicKey(keyPair: KeyPair): ByteArray {
        val pubKey = keyPair.public.encoded
        // wrap in COSE_Mac0 structure
        return CertHack.generateMacedPublicKey(keyPair) ?: pubKey
    }

    private fun createCertificateRequestResponse(
        keysToSign: Array<MacedPublicKey>?,
        challenge: ByteArray?,
        isV2: Boolean
    ): ByteArray {
        return CertHack.createCertificateRequestResponse(
            keysToSign?.map { it.macedKey }?.filterNotNull() ?: emptyList(),
            challenge ?: ByteArray(0),
            createDeviceInfo()
        ) ?: ByteArray(0)
    }

    private fun createDeviceInfo(): ByteArray {
        val brand = Config.getBuildVar("BRAND") ?: "google"
        val manufacturer = Config.getBuildVar("MANUFACTURER") ?: "Google"
        val product = Config.getBuildVar("PRODUCT") ?: "generic"
        val model = Config.getBuildVar("MODEL") ?: "Pixel"
        val device = Config.getBuildVar("DEVICE") ?: "generic"
        
        return CertHack.createDeviceInfoCbor(brand, manufacturer, product, model, device)
            ?: ByteArray(0)
    }

    private fun createProtectedData(): ByteArray {
        // empty for now, only needed if endpoint encryption is used
        return ByteArray(0)
    }
}
