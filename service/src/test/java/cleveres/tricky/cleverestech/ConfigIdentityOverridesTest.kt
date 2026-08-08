package cleveres.tricky.cleverestech

import cleveres.tricky.cleverestech.util.RandomUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

class ConfigIdentityOverridesTest {
    @Before
    fun setUp() {
        Config.reset()
    }

    @After
    fun tearDown() {
        Config.reset()
    }

    @Test
    fun `identity snapshot is atomic and slot aware`() {
        val imei = RandomUtils.generateLuhn(15, "35")
        val imei2 = RandomUtils.generateLuhn(15, "35")
        val imsi = RandomUtils.generateDigits(15, "310260")
        val imsi2 = RandomUtils.generateDigits(15, "310260")
        val iccid = RandomUtils.generateLuhn(20, "8901")
        val iccid2 = RandomUtils.generateLuhn(20, "8901")
        val file = File.createTempFile("identity_overrides", ".txt").apply { deleteOnExit() }
        file.writeText(
            """
            ATTESTATION_ID_IMEI=$imei
            ATTESTATION_ID_IMEI2=$imei2
            ATTESTATION_ID_IMSI=$imsi
            ATTESTATION_ID_IMSI2=$imsi2
            ATTESTATION_ID_ICCID=$iccid
            ATTESTATION_ID_ICCID2=$iccid2
            ATTESTATION_ID_MEID=A100000927F4E1
            ATTESTATION_ID_MEID2=A100000927F4E2
            ATTESTATION_ID_PHONE_NUMBER=+12025550123
            ATTESTATION_ID_PHONE_NUMBER2=+12025550124
            ATTESTATION_ID_SERIAL=DEVICE_01
            """.trimIndent(),
        )

        Config.updateBuildVars(file)

        val identity = Config.getIdentityOverrides()
        assertEquals(imei, identity.imeiForSlot(0))
        assertEquals(imei2, identity.imeiForSlot(1))
        assertEquals(imsi, identity.imsiForSlot(0))
        assertEquals(imsi2, identity.imsiForSlot(1))
        assertEquals(iccid, identity.iccidForSlot(0))
        assertEquals(iccid2, identity.iccidForSlot(1))
        assertEquals("A100000927F4E2", identity.meidForSlot(1))
        assertEquals("+12025550124", identity.phoneNumberForSlot(1))
        assertNull(identity.imeiForSlot(2))

        file.writeText("ATTESTATION_ID_IMEI=not-valid")
        Config.updateBuildVars(file)
        assertEquals(identity, Config.getIdentityOverrides())
    }
}
