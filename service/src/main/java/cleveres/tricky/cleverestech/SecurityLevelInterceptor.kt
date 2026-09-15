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
 * Rewrites only the certificate chain returned by a successful, genuine TEE
 * or StrongBox KeyMint key generation. The private key and every later cryptographic
 * operation remain owned by the platform security level.
 *
 * This interceptor is registered on both the TEE and StrongBox child binders.
 */
class SecurityLevelInterceptor : BinderInterceptor() {
    companion object {
        private val generateKeyTransaction =
            getTransactCode(IKeystoreSecurityLevel.Stub::class.java, "generateKey")

        val INTERCEPTED_CODES = validTransactCodes(generateKeyTransaction)
    }

    override val requiresPostRequestPayload: Boolean = true

    override fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result {
        if (code == generateKeyTransaction) {
            if (!CertHack.canHack()) {
                CertHack.noteAttestFailure(callingUid, 41)
                return Skip
            }
            if (!Config.needHack(callingUid)) {
                return Skip
            }

            return Continue
        }
        return Skip
    }

    private fun rewriteChildWithParentRecovery(
        original: Array<Certificate>,
        callingUid: Int,
        isAttestKey: Boolean,
        parentKeyId: ByteArray,
        childKeyId: ByteArray?,
        platformSecurityLevel: Int,
        parentDescriptor: android.system.keystore2.KeyDescriptor? = null,
    ): Array<Certificate> {
        val first =
            rewriteChildWithParentRecoveryInternal(
                original,
                callingUid,
                isAttestKey,
                parentKeyId,
                childKeyId,
                platformSecurityLevel,
            )
        if (first !== original || parentDescriptor == null) {
            return first
        }

        val parentCert = KeystoreInterceptor.queryParentCertificate(parentDescriptor, callingUid) ?: return first
        val parentCertObj = Utils.toCertificate(parentCert) ?: return first
        val parentChain = arrayOf<Certificate>(parentCertObj)
        val parentRewritten =
            CertHack.hackAttestKeyCertificateChain(
                parentChain,
                callingUid,
                true,
                parentKeyId,
                platformSecurityLevel,
            )
        if (parentRewritten === parentChain || parentRewritten.isEmpty()) {
            return first
        }
        val parentRewrittenDer = runCatching { parentRewritten[0].encoded }.getOrNull()
        ManagedAttestKeyRegistry.remember(
            callingUid,
            parentKeyId,
            null,
            parentCert,
            true,
            platformSecurityLevel,
            parentRewrittenDer,
        )

        return rewriteChildWithParentRecoveryInternal(
            original,
            callingUid,
            isAttestKey,
            parentKeyId,
            childKeyId,
            platformSecurityLevel,
        )
    }

    /**
     * Rewrites child certificates under managed attest keys, releasing the publication lock across IPC.
     */
    private fun rewriteChildWithParentRecoveryInternal(
        original: Array<Certificate>,
        callingUid: Int,
        isAttestKey: Boolean,
        parentKeyId: ByteArray,
        childKeyId: ByteArray?,
        platformSecurityLevel: Int,
    ): Array<Certificate> {
        val first = run {
            KeyboxActivation.lockPublishedSnapshot()
            try {
                CertHack.hackChildKeyCertificate(
                    original,
                    callingUid,
                    isAttestKey,
                    true,
                    parentKeyId,
                    childKeyId,
                    platformSecurityLevel,
                )
            } finally {
                KeyboxActivation.unlockPublishedSnapshot()
            }
        }
        if (first !== original || !ManagedAttestKeyRegistry.isKnown(callingUid, parentKeyId)) {
            return first
        }

        val presence =
            runCatching { CertificateBackend.touchAttestKey(callingUid, parentKeyId) }
                .getOrElse { return first }
        if (presence != CertificateBackend.AttestKeyTouchResult.ABSENT) return first
        if (!ManagedAttestKeyRehydrator.restore(callingUid, parentKeyId)) return first

        KeyboxActivation.lockPublishedSnapshot()
        return try {
            CertHack.hackChildKeyCertificate(
                original,
                callingUid,
                isAttestKey,
                true,
                parentKeyId,
                childKeyId,
                platformSecurityLevel,
            )
        } finally {
            KeyboxActivation.unlockPublishedSnapshot()
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
            reply == null ||
            resultCode != 0
        ) {
            return Skip
        }

        val parsedContext = Utils.parseGenerateKeyRequest(data, callingUid)
        val context = if (parsedContext != null) {
            parsedContext
        } else if (data.dataSize() == 0 || data.dataPosition() != 0) {
            Utils.GenerateKeyRequestInfo(
                Utils.usesDefaultAttestationKey(data),
                Utils.hasAttestKeyPurpose(data),
                null,
                null,
            )
        } else {
            return Skip
        }

        return try {
            reply.readException()

            val parsed = Utils.parseKeyMetadataParcel(reply)
            if (parsed != null) {
                val originalLeaf = cleveres.tricky.cleverestech.keystore.LazyX509Certificate(parsed.leafEncoded, false)
                if (!Utils.hasAndroidAttestationExtension(originalLeaf) && !context.isAttestKeyPurpose) {
                    return Skip
                }

                val originalLeafOnly = arrayOf<Certificate>(originalLeaf)
                val platformSecurityLevel = parsed.keySecurityLevel
                val rewritten =
                    if (!context.usesDefaultAttestationKey) {
                        val parentId = context.parentKeyId
                        if (context.isAttestKeyPurpose && parentId == null) {
                            return Skip
                        }
                        if (parentId == null) {
                            CertHack.hackChildKeyCertificate(
                                originalLeafOnly,
                                callingUid,
                                context.isAttestKeyPurpose,
                                true,
                                null,
                                null,
                                platformSecurityLevel,
                            )
                        } else {
                            rewriteChildWithParentRecovery(
                                originalLeafOnly,
                                callingUid,
                                context.isAttestKeyPurpose,
                                parentId,
                                context.generatedKeyId,
                                platformSecurityLevel,
                                context.parentKeyDescriptor,
                            )
                        }
                    } else if (context.isAttestKeyPurpose) {
                        val keyId = context.generatedKeyId
                        if (keyId == null) {
                            CertHack.hackAttestKeyCertificateChain(
                                originalLeafOnly,
                                callingUid,
                                true,
                                null,
                                platformSecurityLevel,
                            )
                        } else {
                            CertHack.hackAttestKeyCertificateChain(
                                originalLeafOnly,
                                callingUid,
                                true,
                                keyId,
                                platformSecurityLevel,
                            )
                        }
                    } else {
                        CertHack.hackCertificateChain(
                            originalLeafOnly,
                            callingUid,
                            true,
                        )
                    }
                if (rewritten === originalLeafOnly) {
                    return Skip
                }
                if (context.isAttestKeyPurpose || context.parentKeyId != null) {
                    val rewrittenLeafDer = runCatching { rewritten[0].encoded }.getOrNull()
                    ManagedAttestKeyRegistry.remember(
                        callingUid,
                        context.generatedKeyId,
                        context.parentKeyId,
                        parsed.leafEncoded,
                        context.isAttestKeyPurpose,
                        platformSecurityLevel,
                        rewrittenLeafDer,
                    )
                    val assignedKeyId = parsed.getAssignedKeyId(callingUid)
                    if (context.generatedKeyId != null && assignedKeyId != null &&
                        !java.util.Arrays.equals(context.generatedKeyId, assignedKeyId)
                    ) {
                        if (context.isAttestKeyPurpose) {
                            val aliasResult =
                                try {
                                    CertificateBackend.aliasAttestKey(callingUid, context.generatedKeyId, assignedKeyId)
                                } catch (_: Throwable) {
                                    CertHack.noteAttestFailure(callingUid, 43)
                                    return Skip
                                }
                            if (aliasResult != CertificateBackend.AttestKeyAliasResult.ALIASED) {
                                CertHack.noteAttestFailure(callingUid, 43)
                                return Skip
                            }
                        }
                        ManagedAttestKeyRegistry.rememberAlias(callingUid, context.generatedKeyId, assignedKeyId)
                    }
                }

                if (
                    CertHack.applyCachedCertificateChain(reply, parsed) ==
                        CertHack.CachedParcelAction.REWRITTEN
                ) {
                    return OverrideReply(code = 0, reply = reply)
                }

                val newLeaf = rewritten[0].encoded
                val newChain = if (!context.usesDefaultAttestationKey) null else Utils.encodeIssuerChain(rewritten)
                if (!Utils.rewriteKeyMetadataParcel(reply, parsed, newLeaf, newChain)) {
                    return Skip
                }
                return OverrideReply(code = 0, reply = reply)
            }

            val metadata = reply.readTypedObject(KeyMetadata.CREATOR) ?: return Skip
            if (!Utils.isCertificateChainRewriteCandidate(metadata) && !Utils.hasRewritableLeafCertificate(metadata)) {
                if (context.isAttestKeyPurpose && context.generatedKeyId != null) {
                    ManagedAttestKeyRegistry.remember(
                        callingUid,
                        context.generatedKeyId,
                        context.parentKeyId,
                        null,
                        true,
                        metadata.keySecurityLevel,
                    )
                    val assignedDescriptor = metadata.key
                    val assignedKeyId =
                        if (assignedDescriptor != null) {
                            Utils.computeKeyDescriptorIdentity(
                                callingUid,
                                assignedDescriptor.domain,
                                assignedDescriptor.nspace,
                                assignedDescriptor.alias,
                                assignedDescriptor.blob,
                            )
                        } else {
                            null
                        }
                    if (assignedKeyId != null && !java.util.Arrays.equals(context.generatedKeyId, assignedKeyId)) {
                        val aliasResult =
                            try {
                                CertificateBackend.aliasAttestKey(callingUid, context.generatedKeyId, assignedKeyId)
                            } catch (_: Throwable) {
                                CertHack.noteAttestFailure(callingUid, 43)
                                return Skip
                            }
                        if (aliasResult != CertificateBackend.AttestKeyAliasResult.ALIASED) {
                            CertHack.noteAttestFailure(callingUid, 43)
                            return Skip
                        }
                        ManagedAttestKeyRegistry.rememberAlias(callingUid, context.generatedKeyId, assignedKeyId)
                    }
                }
                return Skip
            }

            val originalLeaf = Utils.getLeafCertificate(metadata)
            if (
                originalLeaf == null ||
                (!Utils.hasAndroidAttestationExtension(originalLeaf) && !context.isAttestKeyPurpose)
            ) {
                return Skip
            }

            val originalLeafOnly = arrayOf<Certificate>(originalLeaf)
            val platformSecurityLevel = metadata.keySecurityLevel
            val rewritten =
                if (!context.usesDefaultAttestationKey) {
                    val parentId = context.parentKeyId
                    if (context.isAttestKeyPurpose && parentId == null) {
                        return Skip
                    }
                    if (parentId == null) {
                        CertHack.hackChildKeyCertificate(
                            originalLeafOnly,
                            callingUid,
                            context.isAttestKeyPurpose,
                            true,
                            null,
                            null,
                            platformSecurityLevel,
                        )
                    } else {
                        rewriteChildWithParentRecovery(
                            originalLeafOnly,
                            callingUid,
                            context.isAttestKeyPurpose,
                            parentId,
                            context.generatedKeyId,
                            platformSecurityLevel,
                            context.parentKeyDescriptor,
                        )
                    }
                } else if (context.isAttestKeyPurpose) {
                    val keyId = context.generatedKeyId
                    if (keyId == null) {
                        CertHack.hackAttestKeyCertificateChain(
                            originalLeafOnly,
                            callingUid,
                            true,
                            null,
                            platformSecurityLevel,
                        )
                    } else {
                        CertHack.hackAttestKeyCertificateChain(
                            originalLeafOnly,
                            callingUid,
                            true,
                            keyId,
                            platformSecurityLevel,
                        )
                    }
                } else {
                    CertHack.hackCertificateChain(
                        originalLeafOnly,
                        callingUid,
                        true,
                    )
                }
            if (rewritten === originalLeafOnly) {
                return Skip
            }
            if (context.isAttestKeyPurpose || context.parentKeyId != null) {
                val rewrittenLeafDer = runCatching { rewritten[0].encoded }.getOrNull()
                ManagedAttestKeyRegistry.remember(
                    callingUid,
                    context.generatedKeyId,
                    context.parentKeyId,
                    metadata.certificate,
                    context.isAttestKeyPurpose,
                    platformSecurityLevel,
                    rewrittenLeafDer,
                )
                val assignedDescriptor = metadata.key
                val assignedKeyId =
                    if (assignedDescriptor != null) {
                        Utils.computeKeyDescriptorIdentity(
                            callingUid,
                            assignedDescriptor.domain,
                            assignedDescriptor.nspace,
                            assignedDescriptor.alias,
                            assignedDescriptor.blob,
                        )
                    } else {
                        null
                    }
                if (context.generatedKeyId != null && assignedKeyId != null &&
                    !java.util.Arrays.equals(context.generatedKeyId, assignedKeyId)
                ) {
                    if (context.isAttestKeyPurpose) {
                        val aliasResult =
                            try {
                                CertificateBackend.aliasAttestKey(callingUid, context.generatedKeyId, assignedKeyId)
                            } catch (_: Throwable) {
                                CertHack.noteAttestFailure(callingUid, 43)
                                return Skip
                            }
                        if (aliasResult != CertificateBackend.AttestKeyAliasResult.ALIASED) {
                            CertHack.noteAttestFailure(callingUid, 43)
                            return Skip
                        }
                    }
                    ManagedAttestKeyRegistry.rememberAlias(callingUid, context.generatedKeyId, assignedKeyId)
                }
            }

            if (!CertHack.applyCachedCertificateChain(metadata)) {
                if (!context.usesDefaultAttestationKey) {
                    metadata.certificate = rewritten[0].encoded
                    metadata.certificateChain = null
                } else {
                    Utils.putCertificateChain(metadata, rewritten)
                }
            }
            reply.setDataSize(0)
            reply.setDataPosition(0)
            reply.writeNoException()
            reply.writeTypedObject(metadata, 0)
            OverrideReply(0, reply)
        } catch (e: Throwable) {
            if (KeystoreInterceptor.isSecureHwCommunicationFailure(e)) {
                KeystoreInterceptor.tripTeeCircuitBreaker()
            }
            Skip
        }
    }
}
