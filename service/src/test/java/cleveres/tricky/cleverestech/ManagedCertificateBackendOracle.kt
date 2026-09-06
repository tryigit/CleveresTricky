package cleveres.tricky.cleverestech

import org.bouncycastle.asn1.ASN1Boolean
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1EncodableVector
import org.bouncycastle.asn1.ASN1Enumerated
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.DERTaggedObject
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Test-only compatibility oracle for the managed certificate rewrite that preceded the Rust backend.
 * Production never calls this object; JVM regression tests install it explicitly because Android
 * LocalSocket and the unprivileged backend process are not available in host unit tests.
 */
object ManagedCertificateBackendOracle {
    private val attestationOid = ASN1ObjectIdentifier("1.3.6.1.4.1.11129.2.1.17")
    private val idTags = intArrayOf(710, 711, 712, 713, 714, 715, 716, 717, 723)

    @JvmStatic
    fun install() {
        CertificateBackend.inspectionOverride = ::inspect
        CertificateBackend.rewriteOverride = ::rewrite
    }

    @JvmStatic
    fun reset() {
        CertificateBackend.resetForTesting()
    }

    private fun inspect(leafDer: ByteArray): CertificateBackend.Inspection? =
        runCatching {
            val holder = X509CertificateHolder(leafDer)
            val extension = holder.getExtension(attestationOid) ?: return null
            val sequence = ASN1Sequence.getInstance(extension.extnValue.octets)
            val fields = sequence.toArray()
            require(fields.size > 7)
            val attestationSecurityLevel = decodeSecurityLevel(fields[1])
            val keymintSecurityLevel = decodeSecurityLevel(fields[3])
            val listSix = ASN1Sequence.getInstance(fields[6])
            val listSeven = ASN1Sequence.getInstance(fields[7])
            val sixSummary = summarize(listSix)
            val sevenSummary = summarize(listSeven)
            val tee = if (sixSummary.hasRootOfTrust && !sevenSummary.hasRootOfTrust) listSix else listSeven
            val teeSummary = if (tee === listSix) sixSummary else sevenSummary
            val softwareSummary = if (tee === listSix) sevenSummary else sixSummary

            var presentMask = 0
            for (value in tee) {
                val tagged = value as? ASN1TaggedObject ?: error("Invalid authorization-list element")
                val index = idTags.indexOf(tagged.tagNo)
                if (index >= 0) presentMask = presentMask or (1 shl index)
            }

            val root = findTag(tee, 704)?.let { ASN1Sequence.getInstance(it.baseObject) }
            CertificateBackend.Inspection(
                systemPatch = combinePatch(teeSummary.systemPatch, softwareSummary.systemPatch),
                vendorPatch = combinePatch(teeSummary.vendorPatch, softwareSummary.vendorPatch),
                bootPatch = combinePatch(teeSummary.bootPatch, softwareSummary.bootPatch),
                presentIdMask = presentMask,
                supportsModuleHash =
                    ASN1Integer.getInstance(fields[0]).value.intValueExact() >= 400 &&
                        ASN1Integer.getInstance(fields[2]).value.intValueExact() >= 400,
                originalBootKey = root?.let { usableDigest(it.getObjectAt(0)) },
                originalBootHash = root?.let { usableDigest(it.getObjectAt(3)) },
                attestationSecurityLevel = attestationSecurityLevel,
                keymintSecurityLevel = keymintSecurityLevel,
            )
        }.getOrNull()

    private fun rewrite(request: CertificateBackend.RewriteRequest): ByteArray? =
        runCatching {
            val keyMaterial = ManagedOpaqueKeyOracle.lookup(request.keyId) ?: return null
            val genuine = X509CertificateHolder(request.genuineLeafDer)
            val issuer = X509CertificateHolder(keyMaterial.issuerCertificate.encoded)
            val extension = genuine.getExtension(attestationOid) ?: return null
            val sequence = ASN1Sequence.getInstance(extension.extnValue.octets)
            val fields = sequence.toArray()
            require(fields.size > 7)
            val attLevel = decodeSecurityLevel(fields[1])
            val kmLevel = decodeSecurityLevel(fields[3])
            val isTee = attLevel == CertificateBackend.SECURITY_LEVEL_TEE && kmLevel == CertificateBackend.SECURITY_LEVEL_TEE
            val isStrongbox = attLevel == CertificateBackend.SECURITY_LEVEL_STRONGBOX && kmLevel == CertificateBackend.SECURITY_LEVEL_STRONGBOX
            require(isTee || isStrongbox)
            val listSix = ASN1Sequence.getInstance(fields[6])
            val listSeven = ASN1Sequence.getInstance(fields[7])
            val sixSummary = summarize(listSix)
            val sevenSummary = summarize(listSeven)
            val teeIndex = if (sixSummary.hasRootOfTrust && !sevenSummary.hasRootOfTrust) 6 else 7
            val softwareIndex = if (teeIndex == 6) 7 else 6
            val teeOriginal = if (teeIndex == 6) listSix else listSeven
            val softwareOriginal = if (teeIndex == 6) listSeven else listSix
            val teeSummary = if (teeIndex == 6) sixSummary else sevenSummary
            val softwareSummary = if (teeIndex == 6) sevenSummary else sixSummary
            val supportsModuleHash =
                ASN1Integer.getInstance(fields[0]).value.intValueExact() >= 400 &&
                    ASN1Integer.getInstance(fields[2]).value.intValueExact() >= 400

            val tee = ArrayList<ASN1TaggedObject>(teeOriginal.size() + 8)
            val software = ArrayList<ASN1TaggedObject>(softwareOriginal.size() + 4)
            var originalModuleHash: ASN1TaggedObject? = null

            for (value in teeOriginal) {
                val tagged = value as? ASN1TaggedObject ?: error("Invalid TEE authorization-list element")
                val tag = tagged.tagNo
                if (tag == 704) continue
                if (tag == 724 && supportsModuleHash) {
                    originalModuleHash = tagged
                    continue
                }
                if (shouldRemovePatch(tag, request)) continue
                val override = request.idOverrides[tag]
                if (idTags.contains(tag) && override != null) {
                    tee += DERTaggedObject(true, tag, DEROctetString(override))
                    continue
                }
                tee += tagged
            }

            for (value in softwareOriginal) {
                val tagged = value as? ASN1TaggedObject ?: error("Invalid software authorization-list element")
                val tag = tagged.tagNo
                if (tag == 724 && supportsModuleHash) {
                    if (originalModuleHash == null) originalModuleHash = tagged
                    continue
                }
                if (shouldRemovePatch(tag, request)) continue
                software += tagged
            }

            addPatch(
                tee,
                software,
                706,
                request.systemDisposition,
                request.systemValue,
                teeSummary.systemPatch != null,
                softwareSummary.systemPatch != null,
            )
            addPatch(
                tee,
                software,
                718,
                request.vendorDisposition,
                request.vendorValue,
                teeSummary.vendorPatch != null,
                softwareSummary.vendorPatch != null,
            )
            addPatch(
                tee,
                software,
                719,
                request.bootDisposition,
                request.bootValue,
                teeSummary.bootPatch != null,
                softwareSummary.bootPatch != null,
            )

            if (supportsModuleHash) {
                if (request.moduleHash != null) {
                    software += DERTaggedObject(true, 724, DEROctetString(request.moduleHash))
                } else if (originalModuleHash != null) {
                    software += originalModuleHash
                }
            }

            val rootOfTrust = ASN1EncodableVector()
            rootOfTrust.add(DEROctetString(request.verifiedBootKey))
            rootOfTrust.add(ASN1Boolean.TRUE)
            rootOfTrust.add(ASN1Enumerated(0))
            rootOfTrust.add(DEROctetString(request.verifiedBootHash))
            tee += DERTaggedObject(true, 704, DERSequence(rootOfTrust))
            tee.sortBy(ASN1TaggedObject::getTagNo)
            software.sortBy(ASN1TaggedObject::getTagNo)

            fields[teeIndex] = taggedSequence(tee)
            fields[softwareIndex] = taggedSequence(software)
            val rewrittenAttestation = DERSequence(fields)
            val rewrittenExtension = ExtensionCompat.create(attestationOid, rewrittenAttestation)

            val builder =
                X509v3CertificateBuilder(
                    issuer.subject,
                    genuine.serialNumber,
                    genuine.notBefore,
                    genuine.notAfter,
                    genuine.subject,
                    genuine.subjectPublicKeyInfo,
                )
            for (oid in genuine.extensions.extensionOIDs) {
                if (oid == attestationOid) {
                    builder.addExtension(rewrittenExtension)
                } else {
                    builder.addExtension(genuine.getExtension(oid))
                }
            }

            val keyAlgorithm =
                when (request.signingAlgorithm) {
                    CertificateBackend.SIGNING_EC_P256_SHA256 -> "EC"
                    CertificateBackend.SIGNING_RSA_PKCS1_SHA256 -> "RSA"
                    else -> error("Unsupported signing algorithm")
                }
            require(keyMaterial.privateKey.algorithm.equals(keyAlgorithm, ignoreCase = true))
            val signatureAlgorithm = if (keyAlgorithm == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val signer = JcaContentSignerBuilder(signatureAlgorithm).build(keyMaterial.privateKey)
            builder.build(signer).encoded
        }.getOrNull()

    private fun decodeSecurityLevel(field: ASN1Encodable): Int {
        val value = ASN1Enumerated.getInstance(field).value.intValueExact()
        require(value in CertificateBackend.SECURITY_LEVEL_SOFTWARE..CertificateBackend.SECURITY_LEVEL_STRONGBOX)
        return value
    }

    private fun shouldRemovePatch(
        tag: Int,
        request: CertificateBackend.RewriteRequest,
    ): Boolean =
        when (tag) {
            706 -> request.systemDisposition != CertificateBackend.PATCH_KEEP
            718 -> request.vendorDisposition != CertificateBackend.PATCH_KEEP
            719 -> request.bootDisposition != CertificateBackend.PATCH_KEEP
            else -> false
        }

    private fun addPatch(
        tee: MutableList<ASN1TaggedObject>,
        software: MutableList<ASN1TaggedObject>,
        tag: Int,
        disposition: Int,
        value: Int,
        wasTee: Boolean,
        wasSoftware: Boolean,
    ) {
        if (disposition != CertificateBackend.PATCH_REPLACE || value <= 0) return
        val replacement = DERTaggedObject(true, tag, ASN1Integer(value.toLong()))
        if (wasTee || !wasSoftware) tee += replacement
        if (wasSoftware) software += replacement
    }

    private fun taggedSequence(tags: List<ASN1TaggedObject>): DERSequence {
        val vector = ASN1EncodableVector()
        tags.forEach(vector::add)
        return DERSequence(vector)
    }

    private fun summarize(sequence: ASN1Sequence): Summary {
        var summary = Summary()
        for (value in sequence) {
            val tagged = value as? ASN1TaggedObject ?: error("Invalid authorization-list element")
            summary =
                when (tagged.tagNo) {
                    704 -> summary.copy(hasRootOfTrust = true)
                    706 -> summary.copy(systemPatch = mergePatch(summary.systemPatch, patchValue(tagged)))
                    718 -> summary.copy(vendorPatch = mergePatch(summary.vendorPatch, patchValue(tagged)))
                    719 -> summary.copy(bootPatch = mergePatch(summary.bootPatch, patchValue(tagged)))
                    else -> summary
                }
        }
        return summary
    }

    private fun patchValue(tagged: ASN1TaggedObject): Int =
        ASN1Integer.getInstance(tagged.baseObject).value.intValueExact()

    private fun mergePatch(
        current: Int?,
        parsed: Int,
    ): Int {
        require(current == null || current == parsed) { "Conflicting security patch authorizations" }
        return parsed
    }

    private fun combinePatch(
        tee: Int?,
        software: Int?,
    ): Int? {
        require(tee == null || software == null || tee == software) {
            "Conflicting security patch authorization lists"
        }
        return tee ?: software
    }

    private fun findTag(
        sequence: ASN1Sequence,
        target: Int,
    ): ASN1TaggedObject? =
        sequence
            .asSequence()
            .filterIsInstance<ASN1TaggedObject>()
            .firstOrNull { it.tagNo == target }

    private fun usableDigest(value: ASN1Encodable): ByteArray? {
        val digest = ASN1OctetString.getInstance(value).octets
        return digest.takeIf { it.size == 32 && it.any { byte -> byte != 0.toByte() } }?.copyOf()
    }

    private data class Summary(
        val hasRootOfTrust: Boolean = false,
        val systemPatch: Int? = null,
        val vendorPatch: Int? = null,
        val bootPatch: Int? = null,
    )

    /** Keeps Bouncy Castle construction details isolated from the compatibility logic above. */
    private object ExtensionCompat {
        fun create(
            oid: ASN1ObjectIdentifier,
            value: ASN1Sequence,
        ): org.bouncycastle.asn1.x509.Extension =
            org.bouncycastle.asn1.x509.Extension(
                oid,
                false,
                DEROctetString(value),
            )
    }
}
