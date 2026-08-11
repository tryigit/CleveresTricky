package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.SecureFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConfigAppPrivacyTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configDir: File

    @Before
    fun setUp() {
        Config.reset()
        SecureFile.impl = MockSecureFileOperations()
        configDir = tempFolder.newFolder("config")
        Config.setRootForTesting(configDir)
        File(configDir, "spoof_enabled").createNewFile()
        Config.refreshRuntimeSetting("spoof_enabled")
    }

    @After
    fun tearDown() {
        Config.reset()
        SecureFile.impl = SecureFile.DefaultSecureFileOperations()
    }

    @Test
    fun `isolated identities are stable valid and package scoped`() {
        val firstUid = 12001
        val secondUid = 12002
        setPackageCache(firstUid, arrayOf("com.example.first"))
        setPackageCache(secondUid, arrayOf("com.example.second"))
        val rules =
            File(configDir, "app_config").apply {
                writeText(
                    "com.example.first null null isolate\n" +
                        "com.example.second null null isolate\n",
                )
            }

        Config.updateAppConfigs(rules)

        assertEquals(Config.AppPrivacyMode.ISOLATE, Config.getAppPrivacyMode(firstUid))
        assertTrue(Config.needHack(firstUid))
        val first = Config.getTelephonyIdentityOverrides(firstUid)
        val repeated = Config.getTelephonyIdentityOverrides(firstUid)
        val second = Config.getTelephonyIdentityOverrides(secondUid)
        assertEquals(first, repeated)
        assertNotEquals(first.imei, second.imei)
        assertEquals(15, first.imei?.length)
        assertEquals(20, first.iccid?.length)
        assertEquals(14, first.meid?.length)
        assertTrue(isValidLuhn(requireNotNull(first.imei)))
        assertTrue(isValidLuhn(requireNotNull(first.iccid)))
        assertTrue(File(configDir, "privacy_seed").readText().matches(Regex("[0-9a-f]{64}")))
        assertArrayEquals(requireNotNull(first.imei).toByteArray(), Config.getAttestationId("IMEI", firstUid))
        Config.refreshPrivacySeed().getOrThrow()
        assertEquals(first, Config.getTelephonyIdentityOverrides(firstUid))
        File(configDir, "privacy_seed").writeText("invalid")
        assertTrue(Config.refreshPrivacySeed().isFailure)
        assertEquals(first, Config.getTelephonyIdentityOverrides(firstUid))
    }

    @Test
    fun `redaction takes precedence for shared uid`() {
        val uid = 12003
        setPackageCache(uid, arrayOf("com.example.isolated", "com.example.redacted"))
        val rules =
            File(configDir, "app_config").apply {
                writeText(
                    "com.example.isolated null null isolate\n" +
                        "com.example.redacted null null redact\n",
                )
            }

        Config.updateAppConfigs(rules)

        assertEquals(Config.AppPrivacyMode.REDACT, Config.getAppPrivacyMode(uid))
        val identity = Config.getTelephonyIdentityOverrides(uid)
        assertEquals("", identity.imei)
        assertEquals("", identity.iccid)
        assertEquals("", identity.phoneNumber)
        assertTrue(requireNotNull(Config.getAttestationId("SERIAL", uid)).isEmpty())
        assertFalse(File(configDir, "privacy_seed").exists())
    }

    @Test
    fun `uid reuse expires cached application identity`() {
        var now = 100_000L
        Config.clockSource = { now }
        val uid = 12004
        setPackageCache(uid, arrayOf("com.example.original"))
        val rules =
            File(configDir, "app_config").apply {
                writeText(
                    "com.example.original null null isolate\n" +
                        "com.example.replacement null null isolate\n",
                )
            }
        Config.updateAppConfigs(rules)
        val original = Config.getTelephonyIdentityOverrides(uid)

        now += 6_000
        setPackageCache(uid, arrayOf("com.example.replacement"))
        val replacement = Config.getTelephonyIdentityOverrides(uid)

        assertNotEquals(original.imei, replacement.imei)
    }

    @Test
    fun `duplicate package rules fail without replacing active policy`() {
        val uid = 12005
        setPackageCache(uid, arrayOf("com.example.duplicate"))
        val rules = File(configDir, "app_config")
        rules.writeText("com.example.duplicate null null isolate\n")
        assertTrue(Config.updateAppConfigs(rules).isSuccess)
        assertEquals(Config.AppPrivacyMode.ISOLATE, Config.getAppPrivacyMode(uid))

        rules.writeText(
            "com.example.duplicate null null redact\n" +
                "com.example.duplicate null null isolate\n",
        )
        assertTrue(Config.updateAppConfigs(rules).isFailure)
        assertEquals(Config.AppPrivacyMode.ISOLATE, Config.getAppPrivacyMode(uid))
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPackageCache(
        uid: Int,
        packages: Array<String>,
    ) {
        val field = Config::class.java.getDeclaredField("packageCache")
        field.isAccessible = true
        val cache = field.get(Config) as MutableMap<Int, Config.CachedPackage>
        cache[uid] = Config.CachedPackage(packages, Config.clockSource())
    }

    private fun isValidLuhn(value: String): Boolean {
        var sum = 0
        var doubleDigit = false
        for (index in value.lastIndex downTo 0) {
            var digit = value[index] - '0'
            if (doubleDigit) {
                digit *= 2
                if (digit > 9) digit -= 9
            }
            sum += digit
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }
}
