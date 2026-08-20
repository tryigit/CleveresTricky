#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


def write(path, text):
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected one exact match, found {count}")
    write(path, text.replace(old, new, 1))


def sub_once(path, pattern, replacement, flags=re.S):
    text = read(path)
    updated, count = re.subn(pattern, replacement, text, count=1, flags=flags)
    if count != 1:
        raise RuntimeError(f"{path}: expected one regex match, found {count}: {pattern[:80]}")
    write(path, updated)


def mutate_region(path, start_marker, end_marker, transform):
    text = read(path)
    start = text.index(start_marker)
    end = text.index(end_marker, start)
    region = text[start:end]
    updated = transform(region)
    if updated == region:
        raise RuntimeError(f"{path}: transform made no change in {start_marker}")
    write(path, text[:start] + updated + text[end:])


# CT-BUG-01: BootLogic must surface startup failure so Main's supervisor retry path is real.
boot_path = "service/src/main/java/cleveres/tricky/cleverestech/BootLogic.kt"
sub_once(
    boot_path,
    r"    fun run\(\) \{.*?\n    \}\n\n    private fun readBootPropsMode",
    '''    fun run(): Boolean {
        if (ran.get()) return true
        if (!running.compareAndSet(false, true)) return false

        return try {
            val mode = readBootPropsMode()
            val requestedBuildIdentity = PolicyState.isFeatureEnabled(PolicyState.Feature.BUILD_IDENTITY)
            val buildIdentity = requestedBuildIdentity && shouldApplyBuildIdentity(mode)
            val spoofCn =
                PolicyState.isFeatureEnabled(PolicyState.Feature.REGION_IDENTITY) &&
                    mode != BootPropsMode.DISABLE

            // Bootloader / verified-boot property protection is a core module feature.
            // It is deliberately not tied to Spoof Engine, hide_sensitive_props, or
            // boot_props_mode. The latter only controls optional identity properties.
            applyPropertyCompatibility(spoofCn, buildIdentity)

            if (requestedBuildIdentity && !buildIdentity) {
                Logger.i("Identity build properties were skipped by the ${mode.name.lowercase()} compatibility policy")
            }
            ran.set(true)
            true
        } catch (e: Exception) {
            Logger.e("BootLogic failed", e)
            false
        } finally {
            running.set(false)
        }
    }

    private fun readBootPropsMode''',
)
replace_once(
    "service/src/main/java/cleveres/tricky/cleverestech/Main.kt",
    "            BootLogic.run()\n",
    "            check(BootLogic.run()) { \"Boot property compatibility initialization failed\" }\n",
)

startup_test = "service/src/test/java/cleveres/tricky/cleverestech/MainStartupContractTest.kt"
replace_once(
    startup_test,
    '''    @Test
    fun `web ui adapter registers before backend readiness gate`() {''',
    '''    @Test
    fun `boot compatibility failure is routed to supervisor retry`() {
        val root = locateRoot()
        val mainSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/Main.kt").readText()
        val bootSource = File(root, "service/src/main/java/cleveres/tricky/cleverestech/BootLogic.kt").readText()

        assertTrue(bootSource.contains("fun run(): Boolean"))
        assertTrue(bootSource.contains("Logger.e(\\\"BootLogic failed\\\", e)\\n            false"))
        assertTrue(mainSource.contains("check(BootLogic.run())"))
        assertTrue(mainSource.contains("Main: Exiting so the module supervisor can retry initialization"))
    }

    @Test
    fun `web ui adapter registers before backend readiness gate`() {''',
)

# CT-BUG-03: observer callbacks that fail must get a bounded self-retry even without another fs event.
poller_path = "service/src/main/java/cleveres/tricky/cleverestech/FilePoller.kt"
replace_once(
    poller_path,
    "    private var scheduledFuture: ScheduledFuture<*>? = null\n    private var observer: FileObserver? = null\n",
    "    private var scheduledFuture: ScheduledFuture<*>? = null\n    private var retryFuture: ScheduledFuture<*>? = null\n    private var observer: FileObserver? = null\n",
)
replace_once(
    poller_path,
    '''                        } catch (error: Throwable) {
                            Logger.e("FilePoller: Observer check failed for ${file.name}", error)
                        }''',
    '''                        } catch (error: Throwable) {
                            Logger.e("FilePoller: Observer check failed for ${file.name}", error)
                            scheduleRetry()
                        }''',
)
replace_once(
    poller_path,
    '''    @Synchronized
    private fun scheduleFallbackPolling() {
        if (scheduledFuture != null) return
        scheduledFuture =''',
    '''    @Synchronized
    private fun scheduleFallbackPolling() {
        if (scheduledFuture != null) return
        retryFuture?.cancel(false)
        retryFuture = null
        scheduledFuture =''',
)
replace_once(
    poller_path,
    '''    @Synchronized
    private fun checkForChange() {''',
    '''    @Synchronized
    private fun scheduleRetry() {
        if (!isRunning || scheduledFuture != null) return
        if (retryFuture?.isDone == false) return
        val delayMs = minOf(intervalMs, CALLBACK_RETRY_DELAY_MS)
        retryFuture =
            scheduler.schedule(
                {
                    synchronized(this) { retryFuture = null }
                    try {
                        checkForChange()
                    } catch (error: Throwable) {
                        Logger.e("FilePoller: Retry check failed for ${file.name}", error)
                        scheduleRetry()
                    }
                },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
    }

    @Synchronized
    private fun checkForChange() {''',
)
replace_once(
    poller_path,
    '''        scheduledFuture?.cancel(false)
        scheduledFuture = null
    }''',
    '''        scheduledFuture?.cancel(false)
        scheduledFuture = null
        retryFuture?.cancel(false)
        retryFuture = null
    }''',
)
replace_once(
    poller_path,
    '''    companion object {
        private val scheduler =''',
    '''    companion object {
        private const val CALLBACK_RETRY_DELAY_MS = 500L
        private val scheduler =''',
)

poller_test = "service/src/test/java/cleveres/tricky/cleverestech/FilePollerTest.kt"
replace_once(
    poller_test,
    "import java.nio.file.StandardCopyOption\n",
    "import java.nio.file.StandardCopyOption\nimport java.util.concurrent.CountDownLatch\nimport java.util.concurrent.TimeUnit\n",
)
replace_once(
    poller_test,
    '''    private fun handleObserverLoss() {
        val method = FilePoller::class.java.getDeclaredMethod("handleObserverLoss")
        method.isAccessible = true
        method.invoke(poller)
    }
''',
    '''    private fun handleObserverLoss() {
        val method = FilePoller::class.java.getDeclaredMethod("handleObserverLoss")
        method.isAccessible = true
        method.invoke(poller)
    }

    private fun scheduleRetry() {
        val method = FilePoller::class.java.getDeclaredMethod("scheduleRetry")
        method.isAccessible = true
        method.invoke(poller)
    }
''',
)
sub_once(
    poller_test,
    r'''    @Test\n    fun testFailedCallbackRetriesSameChange\(\) \{.*?\n    \}\n\}''',
    '''    @Test
    fun testFailedCallbackRetriesSameChangeWithoutAnotherEvent() {
        var callbackCount = 0
        val retried = CountDownLatch(1)
        poller =
            FilePoller(testFile, 25L) {
                callbackCount++
                if (callbackCount == 1) throw IllegalStateException("first attempt fails")
                retried.countDown()
            }
        poller.start()

        testFile.writeText("modified-content")
        try {
            checkForChange()
        } catch (_: InvocationTargetException) {
        }
        scheduleRetry()

        org.junit.Assert.assertTrue("failed callback was not retried", retried.await(2, TimeUnit.SECONDS))
        assertEquals(2, callbackCount)
    }
}''',
)

# CT-BUG-02 + CT-BUG-04: generation-guarded state commits and striped fetch locks.
server_path = "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt"
replace_once(
    server_path,
    '''    )

    private val serversList = CopyOnWriteArrayList<ServerConfig>()''',
    '''    )

    private data class FetchContext(
        val target: ServerConfig,
        val snapshot: ServerConfig,
        val generation: Long,
    )

    private val serversList = CopyOnWriteArrayList<ServerConfig>()''',
)
replace_once(
    server_path,
    '''    private val serverKeyboxes = ConcurrentHashMap<String, List<CertHack.KeyBox>>()
    private val serverFile get() = File(Config.keyboxDirectory.parentFile, "servers.json")''',
    '''    private val serverKeyboxes = ConcurrentHashMap<String, List<CertHack.KeyBox>>()
    private var stateGeneration = 0L
    private val fetchLocks = Array(FETCH_LOCK_STRIPES) { Any() }
    private val serverFile get() = File(Config.keyboxDirectory.parentFile, "servers.json")''',
)
replace_once(
    server_path,
    '''    fun initialize() {
        loadServers()
        loadCachedKeyboxes()
        startScheduler()
    }''',
    '''    @Synchronized
    fun initialize() {
        loadServers()
        // Invalidate every fetch snapshot captured before this reload. Recovery can run while
        // a server request is in flight, but that request must never publish into rebuilt state.
        stateGeneration++
        loadCachedKeyboxes()
        startScheduler()
    }''',
)

for start_marker, end_marker in [
    ("    @Synchronized\n    fun addServer", "    @Synchronized\n    fun removeServer"),
    ("    @Synchronized\n    fun removeServer", "    @Synchronized\n    fun updateServer"),
    ("    @Synchronized\n    fun updateServer", "    private fun validateServer"),
]:
    def add_generation(region):
        needle = "                saveServers()" if "fun updateServer" in region else "            saveServers()"
        if needle not in region:
            raise RuntimeError(f"saveServers call not found in mutation region {start_marker}")
        return region.replace(needle, needle + "\n" + ("                " if "fun updateServer" in region else "            ") + "stateGeneration++", 1)
    mutate_region(server_path, start_marker, end_marker, add_generation)

sub_once(
    server_path,
    r'''    @Synchronized\n    fun fetchFromServer\(server: ServerConfig\): Boolean \{.*?\n    \}\n\n    internal fun processContent''',
    '''    fun fetchFromServer(server: ServerConfig): Boolean =
        synchronized(fetchLockFor(server.id)) {
            fetchFromServerLocked(server)
        }

    private fun fetchLockFor(id: String): Any =
        fetchLocks[(id.hashCode() and Int.MAX_VALUE) % fetchLocks.size]

    @Synchronized
    private fun beginFetch(server: ServerConfig): FetchContext? {
        val target = serversMap[server.id] ?: return null
        if (!target.enabled) return null
        val snapshot = target.copy(authData = JSONObject(target.authData.toString()))
        snapshot.lastChecked = System.currentTimeMillis()
        return FetchContext(target, snapshot, stateGeneration)
    }

    private fun isFetchCurrent(context: FetchContext): Boolean =
        context.generation == stateGeneration &&
            serversMap[context.snapshot.id] === context.target &&
            context.target.enabled

    @Synchronized
    private fun commitFetchFailure(
        context: FetchContext,
        status: String,
        clearContent: Boolean = false,
        deleteCache: Boolean = false,
    ): Boolean {
        if (!isFetchCurrent(context)) return false
        context.target.lastChecked = context.snapshot.lastChecked
        context.target.lastStatus = status
        if (clearContent) deactivateServerContent(context.snapshot.id, deleteCache)
        persistStatusSafely()
        return false
    }

    @Synchronized
    private fun commitFetchSuccess(
        context: FetchContext,
        keyboxes: List<CertHack.KeyBox>,
        cacheBytes: ByteArray?,
    ): Boolean {
        if (!isFetchCurrent(context)) return false
        val target = context.target
        target.lastChecked = context.snapshot.lastChecked
        target.lastStatus = "OK"
        val cert = keyboxes.firstOrNull()?.certificates?.firstOrNull()
        target.lastAuthor =
            if (cert is X509Certificate) {
                cert.subjectX500Principal.name.take(1024)
            } else {
                "Unknown"
            }
        serverKeyboxes[target.id] = keyboxes
        if (cacheBytes != null) cacheXml(context.snapshot, cacheBytes)
        persistStatusSafely()
        return true
    }

    private fun fetchFromServerLocked(server: ServerConfig): Boolean {
        val context = beginFetch(server) ?: return false
        val snapshot = context.snapshot
        var conn: HttpsURLConnection? = null
        try {
            validateServer(snapshot)
            conn = validatedServerUrl(snapshot.url).openConnection() as HttpsURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept-Encoding", "identity")

            when (snapshot.authType) {
                "BEARER" -> {
                    val token = snapshot.authData.optString("token")
                    if (token.isNotEmpty()) {
                        requireSafeHeader("Authorization", "Bearer $token")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                "BASIC" -> {
                    val user = snapshot.authData.optString("username")
                    val pass = snapshot.authData.optString("password")
                    if (user.isNotEmpty() || pass.isNotEmpty()) {
                        val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
                        requireSafeHeader("Authorization", "Basic $auth")
                        conn.setRequestProperty("Authorization", "Basic $auth")
                    }
                }
                "API_KEY" -> {
                    val key = snapshot.authData.optString("key")
                    val header = snapshot.authData.optString("headerName", "X-API-Key")
                    if (key.isNotEmpty()) {
                        requireSafeHeader(header, key)
                        conn.setRequestProperty(header, key)
                    }
                }
                "CUSTOM" -> {
                    val headers = snapshot.authData.optJSONObject("headers")
                    headers?.keys()?.forEach { key ->
                        val value = headers.getString(key)
                        requireSafeHeader(key, value)
                        conn.setRequestProperty(key, value)
                    }
                }
            }

            if (conn.responseCode != 200) {
                return commitFetchFailure(context, "HTTP_${conn.responseCode}")
            }

            val maxResponseSize = 10 * 1024 * 1024
            val contentLength = conn.contentLengthLong
            if (contentLength > maxResponseSize) {
                return commitFetchFailure(context, "RESPONSE_TOO_LARGE")
            }
            val bytes =
                conn.inputStream.use { input ->
                    val output =
                        FastByteArrayOutputStream(
                            minOf(contentLength.coerceAtLeast(0), 65536L).toInt(),
                        )
                    val chunk = ByteArray(8192)
                    try {
                        var totalRead = 0
                        var count: Int
                        while (input.read(chunk).also { count = it } != -1) {
                            if (count == 0) continue
                            totalRead += count
                            if (totalRead > maxResponseSize) {
                                throw SecurityException("Server response exceeds ${maxResponseSize / 1024 / 1024}MB limit")
                            }
                            output.write(chunk, 0, count)
                        }
                        output.toByteArray()
                    } finally {
                        chunk.fill(0)
                        output.wipe()
                    }
                }

            val result =
                try {
                    processContent(bytes, snapshot)
                } finally {
                    bytes.fill(0)
                }
            val keyboxes = result.first
            val cacheBytes = result.second
            try {
                val crl = KeyboxVerifier.fetchCrl()
                if (crl == null) {
                    return commitFetchFailure(
                        context,
                        "CRL_UNAVAILABLE",
                        clearContent = true,
                        deleteCache = false,
                    )
                }
                val statuses = keyboxes.map { KeyboxVerifier.verifyKeybox(it, crl) }
                if (keyboxes.isEmpty() || statuses.any { it != KeyboxVerifier.Status.VALID }) {
                    return commitFetchFailure(
                        context,
                        "INVALID_CONTENT",
                        clearContent = true,
                        deleteCache = true,
                    )
                }
                return commitFetchSuccess(context, keyboxes, cacheBytes)
            } finally {
                cacheBytes?.fill(0)
            }
        } catch (e: IllegalArgumentException) {
            Logger.e("Invalid server configuration: ${snapshot.name}", e)
            return commitFetchFailure(context, "INVALID_CONFIG")
        } catch (e: Exception) {
            Logger.e("Server fetch failed: ${snapshot.name}", e)
            return commitFetchFailure(context, "NETWORK_ERROR")
        } finally {
            conn?.disconnect()
        }
    }

    internal fun processContent''',
)
replace_once(
    server_path,
    "    private const val MAX_SERVERS = 64\n",
    "    private const val FETCH_LOCK_STRIPES = 16\n    private const val MAX_SERVERS = 64\n",
)

concurrency_test_path = ROOT / "service/src/test/java/cleveres/tricky/cleverestech/ServerManagerConcurrencyRegressionTest.kt"
concurrency_test_path.write_text('''package cleveres.tricky.cleverestech

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerManagerConcurrencyRegressionTest {
    @Test
    fun `server refresh never owns global state monitor across network io`() {
        val source = source()
        assertFalse(source.contains("@Synchronized\\n    fun fetchFromServer"))
        assertTrue(source.contains("synchronized(fetchLockFor(server.id))"))
        assertTrue(source.contains("private fun fetchFromServerLocked"))
        assertTrue(source.contains("conn.responseCode"))
    }

    @Test
    fun `recovery and mutations invalidate stale fetch snapshots`() {
        val source = source()
        assertTrue(source.contains("@Synchronized\\n    fun initialize()"))
        assertTrue(source.contains("stateGeneration++"))
        assertTrue(source.contains("context.generation == stateGeneration"))
        assertTrue(source.contains("serversMap[context.snapshot.id] === context.target"))
        assertTrue(source.contains("val snapshot = target.copy(authData = JSONObject(target.authData.toString()))"))
    }

    private fun source(): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        repeat(6) {
            val candidate = File(current, "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt")
            if (candidate.isFile) return candidate.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Repository root not found")
    }
}
''', encoding="utf-8")

# CT-BUG-05: AbortSignal remains live through staging, native call and staged response reads.
bridge_path = "module/template/webroot/bridge.js"
replace_once(
    bridge_path,
    "    let callbackCounter = 0;\n",
    '''    let callbackCounter = 0;

    function abortError() {
        return new DOMException('The request was aborted', 'AbortError');
    }

    function throwIfAborted(signal) {
        if (signal && signal.aborted) throw abortError();
    }
''',
)
sub_once(
    bridge_path,
    r'''    function execNative\(args, timeoutMs, expectEnvelope = false\) \{.*?\n    \}\n\n    async function openExternalUrl''',
    '''    function execNative(args, timeoutMs, expectEnvelope = false, signal = null) {
        if (!nativeApi || typeof nativeApi.exec !== 'function') return Promise.reject(new Error('Open this page from the KernelSU or APatch WebUI button'));
        try { throwIfAborted(signal); } catch (error) { return Promise.reject(error); }
        const boundedTimeout = Math.min(Math.max(Number(timeoutMs) || 60000, 1000), 125000);
        return new Promise((resolve, reject) => {
            const callbackName = `ct_exec_${Date.now()}_${callbackCounter++}`;
            let settled = false;
            let abortHandler = null;
            const cleanup = () => {
                clearTimeout(timer);
                delete global[callbackName];
                if (signal && abortHandler) signal.removeEventListener('abort', abortHandler);
            };
            const timer = setTimeout(() => {
                if (settled) return;
                settled = true;
                cleanup();
                reject(new Error('Native bridge timed out'));
            }, boundedTimeout + 5000);
            global[callbackName] = (...values) => {
                if (settled) return;
                settled = true;
                cleanup();
                let result;
                try {
                    result = normalizeExecResult(values, expectEnvelope);
                } catch (error) {
                    reject(error);
                    return;
                }
                if (result.errno === 0) resolve(result.stdout);
                else reject(new Error(result.stderr || result.stdout || `Native bridge failed with code ${result.errno}`));
            };
            if (signal) {
                abortHandler = () => {
                    if (settled) return;
                    settled = true;
                    cleanup();
                    reject(abortError());
                };
                signal.addEventListener('abort', abortHandler, { once: true });
                if (signal.aborted) {
                    abortHandler();
                    return;
                }
            }
            try {
                nativeApi.exec(shellCommand(args), '{}', callbackName);
            } catch (error) {
                if (settled) return;
                settled = true;
                cleanup();
                reject(error);
            }
        });
    }

    async function openExternalUrl''',
)
replace_once(
    bridge_path,
    '''    async function createStage(kind, timeoutMs) {
        const id = await execNative(['stage-create', kind], timeoutMs);''',
    '''    async function createStage(kind, timeoutMs, signal = null) {
        const id = await execNative(['stage-create', kind], timeoutMs, false, signal);''',
)
replace_once(
    bridge_path,
    '''    async function appendStage(kind, id, bytes, timeoutMs) {
        for (let offset = 0; offset < bytes.length; offset += chunkBytes) {
            const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.length));
            await execNative(['stage-append', kind, id, encodeBytes(chunk)], timeoutMs);
        }
    }

    async function stageBlob(kind, blob, timeoutMs) {''',
    '''    async function appendStage(kind, id, bytes, timeoutMs, signal = null) {
        for (let offset = 0; offset < bytes.length; offset += chunkBytes) {
            throwIfAborted(signal);
            const chunk = bytes.subarray(offset, Math.min(offset + chunkBytes, bytes.length));
            await execNative(['stage-append', kind, id, encodeBytes(chunk)], timeoutMs, false, signal);
        }
    }

    async function stageBlob(kind, blob, timeoutMs, signal = null) {''',
)
replace_once(
    bridge_path,
    '''        const id = await createStage(kind, timeoutMs);
        try {
            for (let offset = 0; offset < blob.size; offset += chunkBytes) {
                const bytes = new Uint8Array(await blob.slice(offset, Math.min(offset + chunkBytes, blob.size)).arrayBuffer());
                await execNative(['stage-append', kind, id, encodeBytes(bytes)], timeoutMs);''',
    '''        const id = await createStage(kind, timeoutMs, signal);
        try {
            for (let offset = 0; offset < blob.size; offset += chunkBytes) {
                throwIfAborted(signal);
                const bytes = new Uint8Array(await blob.slice(offset, Math.min(offset + chunkBytes, blob.size)).arrayBuffer());
                await execNative(['stage-append', kind, id, encodeBytes(bytes)], timeoutMs, false, signal);''',
)
replace_once(
    bridge_path,
    '''    async function readDownload(id, size, timeoutMs) {''',
    '''    async function readDownload(id, size, timeoutMs, signal = null) {''',
)
replace_once(
    bridge_path,
    '''            for (let offset = 0; offset < size; offset += chunkBytes) {
                const length = Math.min(chunkBytes, size - offset);
                const encoded = await execNative(['stage-read', 'download', id, String(offset), String(length)], timeoutMs);''',
    '''            for (let offset = 0; offset < size; offset += chunkBytes) {
                throwIfAborted(signal);
                const length = Math.min(chunkBytes, size - offset);
                const encoded = await execNative(['stage-read', 'download', id, String(offset), String(length)], timeoutMs, false, signal);''',
)
replace_once(
    bridge_path,
    '''        constructor(envelope, timeoutMs) {''',
    '''        constructor(envelope, timeoutMs, signal = null) {''',
)
replace_once(
    bridge_path,
    '''            this.timeoutMs = timeoutMs;
            this.cachedBytes = null;''',
    '''            this.timeoutMs = timeoutMs;
            this.signal = signal;
            this.cachedBytes = null;''',
)
replace_once(
    bridge_path,
    '''        async bytes() {
            if (this.cachedBytes) return this.cachedBytes;''',
    '''        async bytes() {
            throwIfAborted(this.signal);
            if (this.cachedBytes) return this.cachedBytes;''',
)
replace_once(
    bridge_path,
    '''                this.cachedBytes = await readDownload(this.downloadId, this.size, this.timeoutMs);''',
    '''                this.cachedBytes = await readDownload(this.downloadId, this.size, this.timeoutMs, this.signal);''',
)
replace_once(
    bridge_path,
    '''    async function prepareRequest(url, options, timeoutMs) {''',
    '''    async function prepareRequest(url, options, timeoutMs, signal = null) {
        throwIfAborted(signal);''',
)
replace_once(
    bridge_path,
    '''                        uploadId = await stageBlob('upload', value, timeoutMs);''',
    '''                        uploadId = await stageBlob('upload', value, timeoutMs, signal);''',
)
replace_once(
    bridge_path,
    '''    async function callRequest(request, timeoutMs) {''',
    '''    async function callRequest(request, timeoutMs, signal = null) {
        throwIfAborted(signal);''',
)
replace_once(
    bridge_path,
    '''            raw = await execNative(['call', encodeBytes(bytes), String(timeoutMs)], timeoutMs, true);''',
    '''            raw = await execNative(['call', encodeBytes(bytes), String(timeoutMs)], timeoutMs, true, signal);''',
)
replace_once(
    bridge_path,
    '''            const stageId = await createStage('request', timeoutMs);''',
    '''            const stageId = await createStage('request', timeoutMs, signal);''',
)
replace_once(
    bridge_path,
    '''                await appendStage('request', stageId, bytes, timeoutMs);
                raw = await execNative(['call-file', stageId, String(timeoutMs)], timeoutMs, true);''',
    '''                await appendStage('request', stageId, bytes, timeoutMs, signal);
                raw = await execNative(['call-file', stageId, String(timeoutMs)], timeoutMs, true, signal);''',
)
replace_once(
    bridge_path,
    '''        const response = new NativeResponse(envelope, timeoutMs);''',
    '''        const response = new NativeResponse(envelope, timeoutMs, signal);''',
)
sub_once(
    bridge_path,
    r'''    async function nativeFetch\(url, options = \{\}\) \{.*?\n    \}\n\n    async function exportBlob''',
    '''    async function nativeFetch(url, options = {}) {
        const requestedTimeout = Number(options.timeoutMs ?? 60000);
        const timeoutMs = Number.isFinite(requestedTimeout) ? Math.min(Math.max(Math.trunc(requestedTimeout), 1000), 120000) : 60000;
        const signal = options.signal || null;
        throwIfAborted(signal);
        let request;
        try {
            request = await prepareRequest(url, options, timeoutMs, signal);
            throwIfAborted(signal);
            return await callRequest(request, timeoutMs, signal);
        } catch (error) {
            if (request && request.uploadId) await dropStage('upload', request.uploadId);
            throw error;
        }
    }

    async function exportBlob''',
)
replace_once(bridge_path, "        revision: 9,\n", "        revision: 10,\n")
replace_once(
    "module/template/webroot/index.html",
    "<script src=\"bridge.js?revision=10\"></script>",
    "<script src=\"bridge.js?revision=11\"></script>",
)

bridge_test = "module/webui-tests/bridge-base.test.js"
replace_once(
    bridge_test,
    '''    const failing = createBridge(callback => callback(5, '', 'permission denied'));
    await assert.rejects(() => failing.fetch('/api/config'), /permission denied/);
''',
    '''    const failing = createBridge(callback => callback(5, '', 'permission denied'));
    await assert.rejects(() => failing.fetch('/api/config'), /permission denied/);

    let delayedCallback = null;
    const aborting = createBridge(callback => { delayedCallback = callback; });
    const abortController = new AbortController();
    const abortedRequest = aborting.fetch('/api/config', { signal: abortController.signal });
    await new Promise(resolve => setTimeout(resolve, 0));
    abortController.abort();
    await assert.rejects(abortedRequest, error => error && error.name === 'AbortError');
    if (delayedCallback) delayedCallback(0, raw, '');
''',
)
replace_once(
    bridge_test,
    '''    assert.ok(indexSource.includes('<script src="bridge.js?revision=10"></script>'));''',
    '''    assert.ok(indexSource.includes('<script src="bridge.js?revision=11"></script>'));''',
)
replace_once(
    "module/webui-tests/bridge.test.js",
    "assert.match(indexSource, /bridge\\.js\\?revision=10/);",
    "assert.match(indexSource, /bridge\\.js\\?revision=11/);",
)

print("deep bug sweep follow-up patches applied")
