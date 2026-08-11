package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ConfigEnhancementTest {
    @Test
    fun testInvalidCustomTemplateIsRejectedTransactionally() {
        Config.updateCustomTemplates(null)
        val before = Config.getTemplateNames()
        val file = File.createTempFile("custom_templates_invalid", null)
        file.deleteOnExit()
        file.writeText("[bad\"name]\nMODEL=Injected")

        Config.updateCustomTemplates(file)

        assertEquals(before, Config.getTemplateNames())
        assertFalse(Config.getTemplateNames().contains("bad\"name"))
    }

    @Test
    fun testCustomTemplates() {
        // Create temp file
        val f = File.createTempFile("custom_templates", null)
        f.deleteOnExit()
        f.writeText(
            """
            [MyTemplate]
            MANUFACTURER=MyMan
            MODEL=MyModel

            [Another]
            BRAND=SomeBrand
            """.trimIndent(),
        )

        Config.updateCustomTemplates(f)

        val templates = Config.getTemplateNames()
        assertTrue(templates.contains("mytemplate"))
        assertTrue(templates.contains("another"))

        val myTemplate = Config.getTemplate("mytemplate")
        assertNotNull(myTemplate)
        assertEquals("MyMan", myTemplate!!["MANUFACTURER"])
        assertEquals("MyModel", myTemplate["MODEL"])

        val another = Config.getTemplate("another")
        assertEquals("SomeBrand", another!!["BRAND"])
    }

    @Test
    fun testAttestationIdFallback() {
        val root = Files.createTempDirectory("config_enhancement_").toFile()
        try {
            Config.reset()
            Config.setRootForTesting(root)
            File(root, "spoof_enabled").createNewFile()
            Config.refreshRuntimeSetting("spoof_enabled")

            // Clear build vars
            Config.updateBuildVars(null)

            // Create temp file for build vars
            val f = File(root, "spoof_build_vars")
            f.writeText(
                """
                MANUFACTURER=FallbackMan
                MODEL=FallbackModel
                ATTESTATION_ID_BRAND=ExplicitBrand
                """.trimIndent(),
            )

            Config.updateBuildVars(f)

            // Verify Build Vars
            assertEquals("FallbackMan", Config.getBuildVar("MANUFACTURER"))

            // Verify Fallback to Build Var
            val manBytes = Config.getAttestationId("MANUFACTURER", 0)
            assertNotNull(manBytes)
            assertEquals("FallbackMan", String(manBytes!!))

            // Verify Explicit Override
            val brandBytes = Config.getAttestationId("BRAND", 0)
            assertNotNull(brandBytes)
            assertEquals("ExplicitBrand", String(brandBytes!!))

            // Verify Missing
            assertNull(Config.getAttestationId("MISSING", 0))
        } finally {
            root.deleteRecursively()
            Config.reset()
        }
    }
}
