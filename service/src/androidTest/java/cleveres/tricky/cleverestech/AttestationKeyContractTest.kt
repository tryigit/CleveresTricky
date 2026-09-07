package cleveres.tricky.cleverestech

import android.os.Parcel
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.keystore2.IKeystoreSecurityLevel
import androidx.test.ext.junit.runners.AndroidJUnit4
import cleveres.tricky.cleverestech.keystore.Utils
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttestationKeyContractTest {
    @Test
    fun `real AIDL parcels preserve issuer selection and cursor`() {
        for (explicitIssuer in listOf(false, true)) {
            val request = Parcel.obtain()
            try {
                request.writeInt(42)
                val start = request.dataPosition()
                request.writeInterfaceToken(IKeystoreSecurityLevel.DESCRIPTOR)
                writeKeyDescriptor(request, "child")
                if (explicitIssuer) {
                    writeKeyDescriptor(request, "issuer")
                } else {
                    request.writeInt(0)
                }
                request.setDataPosition(start)
                assertEquals(!explicitIssuer, Utils.usesDefaultAttestationKey(request))
                assertEquals(start, request.dataPosition())
                assertEquals(!explicitIssuer, Utils.usesDefaultAttestationKey(request))
            } finally {
                request.recycle()
            }
        }

        val truncated = Parcel.obtain()
        try {
            truncated.writeInterfaceToken(IKeystoreSecurityLevel.DESCRIPTOR)
            writeKeyDescriptor(truncated, "child")
            truncated.setDataPosition(0)
            assertFalse(Utils.usesDefaultAttestationKey(truncated))
            assertEquals(0, truncated.dataPosition())
        } finally {
            truncated.recycle()
        }

        val invalidSize = Parcel.obtain()
        try {
            invalidSize.writeInterfaceToken(IKeystoreSecurityLevel.DESCRIPTOR)
            invalidSize.writeInt(1)
            invalidSize.writeInt(Int.MAX_VALUE)
            invalidSize.setDataPosition(0)
            assertFalse(Utils.usesDefaultAttestationKey(invalidSize))
            assertEquals(0, invalidSize.dataPosition())
        } finally {
            invalidSize.recycle()
        }
    }

    @Test
    fun `Android Keystore caller signed graph returns leaf only with verifiable edges`() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val prefix = "ct-attest-contract-${UUID.randomUUID()}"
        val aliases = listOf("$prefix-A", "$prefix-B", "$prefix-C", "$prefix-ordinary")
        try {
            generate(aliases[0], KeyProperties.PURPOSE_ATTEST_KEY, null)
            generate(aliases[1], KeyProperties.PURPOSE_ATTEST_KEY, aliases[0])
            generate(aliases[2], KeyProperties.PURPOSE_SIGN, aliases[1])
            generate(aliases[3], KeyProperties.PURPOSE_SIGN, null)

            for ((issuerAlias, childAlias) in listOf(aliases[0] to aliases[1], aliases[1] to aliases[2])) {
                val issuer = store.getCertificate(issuerAlias)
                val child = store.getCertificate(childAlias)
                child.verify(issuer.publicKey)
                assertEquals(1, store.getCertificateChain(childAlias).size)
                assertFalse(Utils.isCertificateChainRewriteCandidate(child.encoded, null))
                repeat(8) {
                    assertArrayEquals(child.encoded, store.getCertificate(childAlias).encoded)
                    store.getCertificate(childAlias).verify(issuer.publicKey)
                }
            }
            val ordinary = store.getCertificate(aliases[3])
            assertFalse(Utils.hasAndroidAttestationExtension(ordinary))
            assertFalse(Utils.isCertificateChainRewriteCandidate(ordinary.encoded, null))
            assertTrue(
                Utils.isCertificateChainRewriteCandidate(
                    ordinary.encoded,
                    store.getCertificate(aliases[0]).encoded,
                ),
            )
        } finally {
            aliases.reversed().forEach(store::deleteEntry)
        }
    }

    /*
     * IKeystoreSecurityLevel.generateKey transports KeyDescriptor as a stable-AIDL typed
     * parcelable. Emit the official presence + size-prefixed wire form directly so this platform
     * contract test does not depend on hidden Java constructors or CREATOR fields that changed in
     * Android 17. Production only needs to skip the first descriptor and inspect whether the
     * optional attestationKey is present, so this is also the smallest exact input for that parser.
     */
    private fun writeKeyDescriptor(parcel: Parcel, alias: String) {
        parcel.writeInt(1)
        writeStableParcelable(parcel) {
            writeInt(0) // Domain.APP.
            writeLong(0L)
            writeString(alias)
            writeByteArray(null)
        }
    }

    private fun writeStableParcelable(parcel: Parcel, writeFields: Parcel.() -> Unit) {
        val start = parcel.dataPosition()
        parcel.writeInt(0)
        parcel.writeFields()
        val end = parcel.dataPosition()
        parcel.setDataPosition(start)
        parcel.writeInt(end - start)
        parcel.setDataPosition(end)
    }

    private fun generate(
        alias: String,
        purpose: Int,
        issuer: String?,
    ) {
        val spec =
            KeyGenParameterSpec
                .Builder(alias, purpose)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setCertificateSubject(X500Principal("CN=$alias"))
        if (issuer != null) {
            spec.setAttestationChallenge(alias.toByteArray(Charsets.UTF_8)).setAttestKeyAlias(issuer)
        }
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec.build())
        }.generateKeyPair()
    }
}
