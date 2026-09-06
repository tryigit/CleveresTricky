package cleveres.tricky.cleverestech

import androidx.annotation.VisibleForTesting
import java.io.IOException
import java.io.OutputStream

/** Bounded certificate/attestation wire adapter for the unprivileged Rust backend. */
object CertificateBackend {
    const val SIGNING_EC_P256_SHA256 = 1
    const val SIGNING_RSA_PKCS1_SHA256 = 2
    const val PATCH_KEEP = 0
    const val PATCH_OMIT = 1
    const val PATCH_REPLACE = 2
    const val SECURITY_LEVEL_SOFTWARE = 0
    const val SECURITY_LEVEL_TEE = 1
    const val SECURITY_LEVEL_STRONGBOX = 2

    data class Inspection @JvmOverloads constructor(
        val systemPatch: Int?,
        val vendorPatch: Int?,
        val bootPatch: Int?,
        val presentIdMask: Int,
        val supportsModuleHash: Boolean,
        val originalBootKey: ByteArray?,
        val originalBootHash: ByteArray?,
        val attestationSecurityLevel: Int,
        val keymintSecurityLevel: Int,
        val hasAttestKeyPurpose: Boolean = false,
    ) {
        fun wipe() {
            originalBootKey?.fill(0)
            originalBootHash?.fill(0)
        }
    }

    @VisibleForTesting
    internal data class RewriteRequest(
        val genuineLeafDer: ByteArray,
        val keyId: ByteArray,
        val signingAlgorithm: Int,
        val systemDisposition: Int,
        val systemValue: Int,
        val vendorDisposition: Int,
        val vendorValue: Int,
        val bootDisposition: Int,
        val bootValue: Int,
        val idOverrides: Map<Int, ByteArray>,
        val moduleHash: ByteArray?,
        val verifiedBootKey: ByteArray,
        val verifiedBootHash: ByteArray,
        val preserveIssuerName: Boolean = false,
        val virtualizeSubjectKey: Boolean = false,
    )

    @VisibleForTesting
    internal var inspectionOverride: ((ByteArray) -> Inspection?)? = null

    @VisibleForTesting
    internal var rewriteOverride: ((RewriteRequest) -> ByteArray?)? = null

    @VisibleForTesting
    internal var rewriteTransportOverride: ((Int, (OutputStream) -> Unit) -> ByteArray?)? = null

    @JvmStatic
    fun inspect(leafDer: ByteArray): Inspection? {
        if (leafDer.isEmpty() || leafDer.size > MAX_CERTIFICATE_DER_BYTES) return null
        inspectionOverride?.let { return it(leafDer) }
        val response =
            NativeBackend.transact(
                OP_CERTIFICATE_INSPECT,
                leafDer.size,
                INSPECT_RESPONSE_BYTES,
                propagateTransportFailure = true,
            ) { output ->
                output.write(leafDer)
            } ?: return null
        return decodeInspection(response)
    }

    @JvmStatic
    fun rewrite(
        genuineLeafDer: ByteArray,
        keyId: ByteArray,
        signingAlgorithm: Int,
        systemDisposition: Int,
        systemValue: Int,
        vendorDisposition: Int,
        vendorValue: Int,
        bootDisposition: Int,
        bootValue: Int,
        idOverrides: Map<Int, ByteArray>,
        moduleHash: ByteArray?,
        verifiedBootKey: ByteArray,
        verifiedBootHash: ByteArray,
    ): ByteArray? =
        rewriteWithMode(
            genuineLeafDer,
            keyId,
            signingAlgorithm,
            systemDisposition,
            systemValue,
            vendorDisposition,
            vendorValue,
            bootDisposition,
            bootValue,
            idOverrides,
            moduleHash,
            verifiedBootKey,
            verifiedBootHash,
            preserveIssuerName = false,
            virtualizeSubjectKey = false,
        )

    @JvmStatic
    fun rewriteWithMode(
        genuineLeafDer: ByteArray,
        keyId: ByteArray,
        signingAlgorithm: Int,
        systemDisposition: Int,
        systemValue: Int,
        vendorDisposition: Int,
        vendorValue: Int,
        bootDisposition: Int,
        bootValue: Int,
        idOverrides: Map<Int, ByteArray>,
        moduleHash: ByteArray?,
        verifiedBootKey: ByteArray,
        verifiedBootHash: ByteArray,
        preserveIssuerName: Boolean,
        virtualizeSubjectKey: Boolean,
    ): ByteArray? {
        // The paired Rust backend implements v2 only. Unsupported modes must not silently
        // become default-issuer rewrites or change the wire format of ordinary requests.
        if (preserveIssuerName || virtualizeSubjectKey) return null
        if (genuineLeafDer.isEmpty() || genuineLeafDer.size > MAX_CERTIFICATE_DER_BYTES ||
            keyId.size != KEY_ID_BYTES || keyId.all { it == 0.toByte() } ||
            signingAlgorithm !in SIGNING_EC_P256_SHA256..SIGNING_RSA_PKCS1_SHA256 ||
            !validPatch(systemDisposition, systemValue) ||
            !validPatch(vendorDisposition, vendorValue) ||
            !validPatch(bootDisposition, bootValue) ||
            idOverrides.size > MAX_ID_OVERRIDES ||
            moduleHash?.let { it.isEmpty() || it.size > MAX_MODULE_HASH_BYTES } == true ||
            verifiedBootKey.size != BOOT_DIGEST_BYTES ||
            verifiedBootHash.size != BOOT_DIGEST_BYTES ||
            verifiedBootKey.all { it == 0.toByte() } ||
            verifiedBootHash.all { it == 0.toByte() }
        ) {
            return null
        }

        val orderedIds = idOverrides.entries.sortedBy { it.key }
        var idWireBytes = 0
        for ((tag, value) in orderedIds) {
            if (tag !in ATTESTATION_ID_TAGS || value.isEmpty() || value.size > MAX_ATTESTATION_ID_BYTES) {
                return null
            }
            idWireBytes = checkedAdd(idWireBytes, ID_HEADER_BYTES, value.size) ?: return null
        }
        val payloadLength =
            checkedAdd(
                REWRITE_FIXED_BYTES,
                idWireBytes,
                moduleHash?.size ?: 0,
                genuineLeafDer.size,
            ) ?: return null
        if (payloadLength > MAX_REWRITE_REQUEST_BYTES) return null

        rewriteOverride?.let { override ->
            return override(
                RewriteRequest(
                    genuineLeafDer = genuineLeafDer,
                    keyId = keyId,
                    signingAlgorithm = signingAlgorithm,
                    systemDisposition = systemDisposition,
                    systemValue = systemValue,
                    vendorDisposition = vendorDisposition,
                    vendorValue = vendorValue,
                    bootDisposition = bootDisposition,
                    bootValue = bootValue,
                    idOverrides = idOverrides,
                    moduleHash = moduleHash,
                    verifiedBootKey = verifiedBootKey,
                    verifiedBootHash = verifiedBootHash,
                    preserveIssuerName = preserveIssuerName,
                    virtualizeSubjectKey = virtualizeSubjectKey,
                ),
            )
        }

        val writePayload: (OutputStream) -> Unit = { output ->
            output.write(REWRITE_WIRE_VERSION)
            output.write(signingAlgorithm)
            writePatch(output, systemDisposition, systemValue)
            writePatch(output, vendorDisposition, vendorValue)
            writePatch(output, bootDisposition, bootValue)
            output.write(orderedIds.size)
            writeU16(output, moduleHash?.size ?: 0)
            writeI32(output, genuineLeafDer.size)
            output.write(keyId)
            output.write(verifiedBootKey)
            output.write(verifiedBootHash)
            for ((tag, value) in orderedIds) {
                writeU16(output, tag)
                writeU16(output, value.size)
                output.write(value)
            }
            if (moduleHash != null) output.write(moduleHash)
            output.write(genuineLeafDer)
        }
        rewriteTransportOverride?.let { return it(payloadLength, writePayload) }
        return NativeBackend.transact(
            OP_CERTIFICATE_REWRITE,
            payloadLength,
            MAX_CERTIFICATE_DER_BYTES,
            propagateTransportFailure = true,
            writePayload = writePayload,
        )
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        inspectionOverride = null
        rewriteOverride = null
        rewriteTransportOverride = null
    }

    internal fun decodeInspection(response: ByteArray): Inspection {
        var key: ByteArray? = null
        var hash: ByteArray? = null
        try {
            if (response.size != INSPECT_RESPONSE_BYTES ||
                (response[0].toInt() and 0xff) != INSPECT_WIRE_VERSION
            ) {
                throw RustBackendUnavailableException(IOException("Invalid certificate inspection response"))
            }
            val flags = response[1].toInt() and 0xff
            if (flags and INSPECT_RESERVED_FLAGS != 0) {
                throw RustBackendUnavailableException(IOException("Invalid certificate inspection flags"))
            }
            val presentIdMask = readU16(response, 2)
            if (presentIdMask and PRESENT_ID_RESERVED_MASK != 0) {
                throw RustBackendUnavailableException(IOException("Invalid certificate ID mask"))
            }
            val systemPatch = readOptionalI32(response, 4)
            val vendorPatch = readOptionalI32(response, 9)
            val bootPatch = readOptionalI32(response, 14)
            // Validate cheap scalar provenance before copying either boot digest out of the
            // transport buffer. Any later decode failure wipes whichever digest was already made.
            val attestationSecurityLevel = decodeSecurityLevel(response[83])
            val keymintSecurityLevel = decodeSecurityLevel(response[84])
            key = decodeOptionalDigest(response, 19, flags and FLAG_BOOT_KEY_PRESENT != 0)
            hash = decodeOptionalDigest(response, 51, flags and FLAG_BOOT_HASH_PRESENT != 0)
            return Inspection(
                systemPatch,
                vendorPatch,
                bootPatch,
                presentIdMask,
                flags and FLAG_MODULE_HASH_SUPPORTED != 0,
                key,
                hash,
                attestationSecurityLevel,
                keymintSecurityLevel,
            )
        } catch (error: Throwable) {
            key?.fill(0)
            hash?.fill(0)
            throw error
        } finally {
            response.fill(0)
        }
    }

    private fun decodeSecurityLevel(encoded: Byte): Int {
        val level = encoded.toInt() and 0xff
        if (level !in SECURITY_LEVEL_SOFTWARE..SECURITY_LEVEL_STRONGBOX) {
            throw RustBackendUnavailableException(IOException("Invalid certificate security level"))
        }
        return level
    }

    private fun readOptionalI32(
        bytes: ByteArray,
        offset: Int,
    ): Int? {
        val present = bytes[offset].toInt() and 0xff
        val value = readI32(bytes, offset + 1)
        return when (present) {
            0 -> {
                if (value != 0) {
                    throw RustBackendUnavailableException(IOException("Non-canonical certificate patch field"))
                }
                null
            }
            1 -> value
            else -> throw RustBackendUnavailableException(IOException("Invalid certificate patch presence flag"))
        }
    }

    private fun decodeOptionalDigest(
        bytes: ByteArray,
        offset: Int,
        present: Boolean,
    ): ByteArray? {
        val value = bytes.copyOfRange(offset, offset + BOOT_DIGEST_BYTES)
        if (!present) {
            if (value.any { it != 0.toByte() }) {
                value.fill(0)
                throw RustBackendUnavailableException(IOException("Non-canonical absent boot digest"))
            }
            value.fill(0)
            return null
        }
        if (value.all { it == 0.toByte() }) {
            value.fill(0)
            throw RustBackendUnavailableException(IOException("Invalid zero boot digest"))
        }
        return value
    }

    private fun validPatch(
        disposition: Int,
        value: Int,
    ): Boolean =
        when (disposition) {
            PATCH_KEEP, PATCH_OMIT -> value == 0
            PATCH_REPLACE -> value > 0
            else -> false
        }

    private fun writePatch(
        output: OutputStream,
        disposition: Int,
        value: Int,
    ) {
        output.write(disposition)
        writeI32(output, value)
    }

    private fun writeU16(
        output: OutputStream,
        value: Int,
    ) {
        require(value in 0..0xffff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun writeI32(
        output: OutputStream,
        value: Int,
    ) {
        output.write((value ushr 24) and 0xff)
        output.write((value ushr 16) and 0xff)
        output.write((value ushr 8) and 0xff)
        output.write(value and 0xff)
    }

    private fun readU16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readI32(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun checkedAdd(vararg components: Int): Int? {
        var total = 0
        return try {
            for (component in components) {
                if (component < 0) return null
                total = Math.addExact(total, component)
            }
            total
        } catch (_: ArithmeticException) {
            null
        }
    }

    private const val OP_CERTIFICATE_INSPECT = 25
    private const val OP_CERTIFICATE_REWRITE = 26
    private const val INSPECT_WIRE_VERSION = 2
    private const val REWRITE_WIRE_VERSION = 2
    private const val INSPECT_RESPONSE_BYTES = 85
    private const val FLAG_MODULE_HASH_SUPPORTED = 1
    private const val FLAG_BOOT_KEY_PRESENT = 1 shl 1
    private const val FLAG_BOOT_HASH_PRESENT = 1 shl 2
    private const val INSPECT_RESERVED_FLAGS = 0xf8
    private const val PRESENT_ID_RESERVED_MASK = 0xfe00
    private const val KEY_ID_BYTES = 16
    private const val MAX_CERTIFICATE_DER_BYTES = 256 * 1024
    private const val MAX_ATTESTATION_ID_BYTES = 4 * 1024
    private const val MAX_MODULE_HASH_BYTES = 1024
    private const val MAX_ID_OVERRIDES = 9
    private const val BOOT_DIGEST_BYTES = 32
    private const val ID_HEADER_BYTES = 4
    private const val REWRITE_FIXED_BYTES = 104
    private const val MAX_ID_WIRE_BYTES = MAX_ID_OVERRIDES * (ID_HEADER_BYTES + MAX_ATTESTATION_ID_BYTES)
    private const val MAX_REWRITE_REQUEST_BYTES =
        REWRITE_FIXED_BYTES +
            MAX_ID_WIRE_BYTES +
            MAX_MODULE_HASH_BYTES +
            MAX_CERTIFICATE_DER_BYTES
    private val ATTESTATION_ID_TAGS = setOf(710, 711, 712, 713, 714, 715, 716, 717, 723)
}
