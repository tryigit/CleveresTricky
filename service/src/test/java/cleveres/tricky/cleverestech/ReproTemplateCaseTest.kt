package cleveres.tricky.cleverestech

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

class ReproTemplateCaseTest {
    @Before
    fun setUp() {
        // Reset Config state
        val tempDir = java.nio.file.Files.createTempDirectory("test_repro_case").toFile()
        tempDir.deleteOnExit()

        // Initialize DeviceTemplateManager with built-ins
        DeviceTemplateManager.initialize(tempDir)

        // Add a custom template with Mixed Case ID
        val customTemplate =
            DeviceTemplate(
                id = "MyTemplate",
                manufacturer = "CustomManu",
                model = "CustomModel",
                fingerprint = "Custom/Fingerprint",
                brand = "CustomBrand",
                product = "CustomProduct",
                device = "CustomDevice",
                release = "14",
                buildId = "ID123",
                incremental = "123",
                securityPatch = "2024-01-01",
            )
        DeviceTemplateManager.addTemplate(customTemplate)

        // Force Config to reload templates from Manager
        // This makes Config.templates have "MyTemplate"
        Config.updateCustomTemplates(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setPackageCache(
        uid: Int,
        packages: Array<String>,
    ) {
        val field = Config::class.java.getDeclaredField("packageCache")
        field.isAccessible = true
        val cache = field.get(Config) as MutableMap<Int, Any>
        cache[uid] = Config.CachedPackage(packages, System.currentTimeMillis())
    }

    private fun updateAppConfigs(file: File) {
        val method =
            Config::class.java.declaredMethods.find {
                it.name.startsWith("updateAppConfigs") && it.parameterCount == 1
            }
                ?: throw NoSuchMethodException("updateAppConfigs")
        method.isAccessible = true
        method.invoke(Config, file)
    }

    @Test
    fun testMixedCaseTemplateLookup() {
        val testUid = 12345
        val testPackage = "com.test.app"

        // 1. Setup App Config with Mixed Case Template ID
        val configFile = File.createTempFile("app_config", "")
        configFile.deleteOnExit()
        // Config.kt splits by whitespace.
        // Format: package template keybox
        configFile.writeText("$testPackage MyTemplate null")

        updateAppConfigs(configFile)

        // 2. Setup Package Cache
        setPackageCache(testUid, arrayOf(testPackage))

        // 3. Verify Mapping
        // Config.kt lowercases the template name from file ("mytemplate").
        // DeviceTemplateManager has "MyTemplate".
        // Config.templates has "MyTemplate".
        // Config.getBuildVar tries templates["mytemplate"], which should fail if map is case-sensitive.

        val model = Config.getBuildVar("MODEL", testUid)

        assertEquals("CustomModel", model)
    }
}
