package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeWiringContractTest {
    @Test
    fun `module supervisor launches authenticated rust runtime`() {
        val root = locateRoot()
        val service = File(root, "module/template/service.sh").readText()
        val daemon = File(root, "module/template/daemon").readText()
        val dollar = '$'

        assertTrue(service.contains("generate_backend_auth()"))
        assertTrue(service.contains("export CLEVERES_TRICKY_BACKEND_AUTH"))
        assertTrue(service.contains("\"${dollar}MODDIR/daemon\""))
        assertTrue(service.contains("unset CLEVERES_TRICKY_BACKEND_AUTH"))
        assertTrue(daemon.contains("exec \"${dollar}MODDIR/cleverestrickyd\" \"${dollar}MODDIR\""))
    }

    @Test
    fun `daemon supervises adapter and backend generations`() {
        val root = locateRoot()
        val daemon = File(root, "rust/daemon/src/main.rs").readText()

        assertTrue(daemon.contains("spawn_android_adapter(&module_dir)"))
        assertTrue(daemon.contains("adapter_identity.publish(adapter.id())"))
        assertTrue(daemon.contains("supervise_backend(backend_dir"))
        assertTrue(daemon.contains("ADAPTER_MAX_BACKOFF"))
        assertTrue(daemon.contains("BACKEND_MAX_BACKOFF"))
        assertTrue(daemon.contains("AdapterChanged"))
    }

    @Test
    fun `android broker client authenticates direct daemon parent without procfs dependency`() {
        val root = locateRoot()
        val secureFile = secureFileSource(root)

        assertTrue(secureFile.contains("Os.getppid()"))
        assertTrue(secureFile.contains("peer.uid != 0"))
        assertTrue(secureFile.contains("peer.gid != 0"))
        assertTrue(secureFile.contains("peer.pid != parentPid"))
        assertTrue(secureFile.contains("awaitAdapterRegistration()"))
        assertTrue(secureFile.contains("STARTUP_RETRY_ATTEMPTS"))
        assertFalse(secureFile.contains("/proc/"))
    }

    @Test
    fun `web ui staging stays on an explicit bounded capability path`() {
        val root = locateRoot()
        val secureFile = secureFileSource(root)
        val webUiBridge =
            File(root, "service/src/main/java/cleveres/tricky/cleverestech/WebUiBridge.kt").readText()
        val broker = File(root, "rust/daemon/src/config_file_broker.rs").readText()

        assertTrue(webUiBridge.contains("SecureFile.mkdirs(bridgeDir, DIRECTORY_MODE)"))
        assertTrue(webUiBridge.contains("SecureFile.mkdirs(stagingDir, DIRECTORY_MODE)"))
        assertTrue(webUiBridge.contains("SecureFile.writeStream(stagedFile, combined, MAX_RESPONSE_BYTES.toLong())"))

        assertTrue(secureFile.contains("ACTION_STAGE_CREATE = 4"))
        assertTrue(secureFile.contains("ACTION_STAGE_APPEND = 5"))
        assertTrue(secureFile.contains("streamBoundedChunks(inputStream, limit, scratch)"))
        assertTrue(secureFile.contains("WEBUI_DOWNLOAD_SUFFIX = \".download\""))

        assertTrue(broker.contains("WEBUI_STAGING_PATH"))
        assertTrue(broker.contains("ACTION_STAGE_CREATE"))
        assertTrue(broker.contains("ACTION_STAGE_APPEND"))
        assertTrue(broker.contains("validate_webui_download_name"))
        assertTrue(broker.contains("append_bounded(name, &scratch[..body_len], MAX_FILE_BYTES)"))
    }

    @Test
    fun `backend requires capability and unprivileged peer identity`() {
        val root = locateRoot()
        val backendClient =
            File(
                root,
                "service/src/main/java/cleveres/tricky/cleverestech/NativeBackend.kt",
            ).readText()
        val backendServer = File(root, "rust/backend/src/main.rs").readText()
        val backendInstance = File(root, "rust/backend/src/backend_instance.rs").readText()

        assertTrue(backendClient.contains("BackendAuth.fromEnvironment()"))
        assertTrue(backendClient.contains("peer.uid != ANDROID_AID_NOBODY"))
        assertTrue(backendClient.contains("peer.gid != ANDROID_GID_NOBODY"))
        assertTrue(backendServer.contains("setgid(ANDROID_GID_NOBODY)"))
        assertTrue(backendServer.contains("setuid(ANDROID_AID_NOBODY)"))
        assertTrue(backendInstance.contains("BACKEND_AUTH_ENV"))
        assertTrue(backendInstance.contains("backend handshake request rejected"))
    }

    @Test
    fun `web ui registration precedes backend readiness`() {
        val root = locateRoot()
        val source = mainSource(root)
        val entry = source.indexOf("fun main(args: Array<String>)")
        val registration = source.indexOf("startWebUiBridge(configDir, isTampered, webUiReady)", entry)
        val backendWait = source.indexOf("NativeBackend.awaitReady(BACKEND_STARTUP_TIMEOUT_MS)", entry)

        assertTrue(entry >= 0)
        assertTrue(registration > entry)
        assertTrue(backendWait > registration)
        assertTrue(source.contains("val webUiReady = CountDownLatch(1)"))
        assertTrue(source.contains("startWebUiBridge(configDir, isTampered, webUiReady)"))
        assertTrue(source.contains("webUiReady.countDown()"))
    }

    @Test
    fun `always-on keystore interception remains after backend and config initialization`() {
        val root = locateRoot()
        val source = mainSource(root)
        val entry = source.indexOf("fun main(args: Array<String>)")
        val backendWait = source.indexOf("NativeBackend.awaitReady(BACKEND_STARTUP_TIMEOUT_MS)", entry)
        val configInitialization = source.indexOf("Config.initialize()", backendWait)
        val currentHookState = source.indexOf("var ksSuccess = KeystoreInterceptor.isRunning()", configInitialization)
        val hookStart = source.indexOf("KeystoreInterceptor.tryRunKeystoreInterceptor()", currentHookState)

        assertTrue(entry >= 0)
        assertTrue(backendWait > entry)
        assertTrue(configInitialization > backendWait)
        assertTrue(currentHookState > configInitialization)
        assertTrue(hookStart > currentHookState)
    }

    private fun mainSource(root: File): String =
        File(root, "service/src/main/java/cleveres/tricky/cleverestech/Main.kt").readText()

    private fun secureFileSource(root: File): String =
        File(
            root,
            "service/src/main/java/cleveres/tricky/cleverestech/util/RustSecureFileOperations.kt",
        ).readText()

    private fun locateRoot(): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            if (File(current, "service").isDirectory && File(current, "rust").isDirectory) return current
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
