from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    target.write_text(text.replace(old, new, 1))


# 1) Enforce the XML size bound before content hashing, and keep hashing bounded
# if a file grows between the metadata check and the read.
path = "service/src/main/java/cleveres/tricky/cleverestech/StoredKeyboxInventory.kt"
replace_once(
    path,
    "    const val MAX_FILENAME_BYTES = 255\n"
    "    const val MAX_XML_BYTES = 10L * 1024 * 1024\n",
    "    const val MAX_FILENAME_BYTES = 255\n"
    "    const val MAX_XML_BYTES = 10L * 1024 * 1024\n"
    "    private const val UNCACHEABLE_CONTENT_STAMP = Long.MIN_VALUE\n",
)
replace_once(
    path,
    "        return sources.map { source ->\n"
    "            source.copy(file = ContentStampedFile(source.file, contentStamp(source.file)))\n"
    "        }\n",
    "        return sources.map { source ->\n"
    "            val size = source.file.length()\n"
    "            if (size !in 1..MAX_XML_BYTES) {\n"
    "                source\n"
    "            } else {\n"
    "                val stamp = contentStamp(source.file) ?: UNCACHEABLE_CONTENT_STAMP\n"
    "                source.copy(file = ContentStampedFile(source.file, stamp))\n"
    "            }\n"
    "        }\n",
)
replace_once(
    path,
    "    private fun contentStamp(file: File): Long {\n"
    "        val digest = MessageDigest.getInstance(\"SHA-256\")\n"
    "        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->\n"
    "            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n"
    "            try {\n"
    "                while (true) {\n"
    "                    val count = input.read(buffer)\n"
    "                    if (count < 0) break\n"
    "                    if (count > 0) digest.update(buffer, 0, count)\n"
    "                }\n"
    "            } finally {\n"
    "                buffer.fill(0)\n"
    "            }\n"
    "        }\n",
    "    private fun contentStamp(file: File): Long? {\n"
    "        val digest = MessageDigest.getInstance(\"SHA-256\")\n"
    "        Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use { input ->\n"
    "            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)\n"
    "            var total = 0L\n"
    "            try {\n"
    "                while (true) {\n"
    "                    val count = input.read(buffer)\n"
    "                    if (count < 0) break\n"
    "                    if (count > 0) {\n"
    "                        total += count\n"
    "                        if (total > MAX_XML_BYTES) return null\n"
    "                        digest.update(buffer, 0, count)\n"
    "                    }\n"
    "                }\n"
    "            } finally {\n"
    "                buffer.fill(0)\n"
    "            }\n"
    "        }\n",
)

# 2) If an active refresh throws after a newer generation was requested, launch
# a replacement worker for that pending generation. Cancellation remains terminal.
path = "service/src/main/java/cleveres/tricky/cleverestech/RuntimeWorkCoordinator.kt"
replace_once(
    path,
    "import android.os.FileObserver\n"
    "import kotlinx.coroutines.CoroutineScope\n",
    "import android.os.FileObserver\n"
    "import kotlinx.coroutines.CancellationException\n"
    "import kotlinx.coroutines.CoroutineScope\n",
)
replace_once(
    path,
    "            executionMutex.withLock { refresh() }\n\n"
    "            val finished =\n",
    "            try {\n"
    "                executionMutex.withLock { refresh() }\n"
    "            } catch (error: CancellationException) {\n"
    "                throw error\n"
    "            } catch (error: Throwable) {\n"
    "                synchronized(stateLock) {\n"
    "                    workerJob =\n"
    "                        if (requestedGeneration != generation) {\n"
    "                            scope.launch { drainRequests() }\n"
    "                        } else {\n"
    "                            null\n"
    "                        }\n"
    "                }\n"
    "                throw error\n"
    "            }\n\n"
    "            val finished =\n",
)

# 3) /proc/stat guest and guest_nice are already included in user and nice.
path = "service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt"
replace_once(
    path,
    "    var total = 0L\n"
    "    var count = 0\n"
    "    while (index < stat.length) {\n",
    "    var total = 0L\n"
    "    var count = 0\n"
    "    while (index < stat.length && count < 8) {\n",
)

# 4) Fail closed when a server delivers a signed CBOX without a configured
# verification key. Wipe decrypted payloads on early rejection.
path = "service/src/main/java/cleveres/tricky/cleverestech/util/CboxDecryptor.kt"
replace_once(
    path,
    "        val author: String\n"
    "            get() = openWithoutVerification()?.author.orEmpty()\n\n"
    "        /** Temporary legacy accessor. Prefer [takeXmlContentBytes] for production parsing. */\n",
    "        val author: String\n"
    "            get() = openWithoutVerification()?.author.orEmpty()\n\n"
    "        internal val hasSignature: Boolean\n"
    "            get() = openWithoutVerification()?.hasSignature == true\n\n"
    "        @Synchronized\n"
    "        internal fun discard() {\n"
    "            opened?.xmlContent?.fill(0)\n"
    "            opened = null\n"
    "            encryptedBytes?.fill(0)\n"
    "            encryptedBytes = null\n"
    "        }\n\n"
    "        /** Temporary legacy accessor. Prefer [takeXmlContentBytes] for production parsing. */\n",
)

path = "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt"
replace_once(
    path,
    "            val payload = CboxDecryptor.decrypt(ByteArrayInputStream(bytes), password)\n"
    "            if (payload != null) {\n"
    "                if (!server.contentPublicKey.isNullOrBlank() &&\n"
    "                    !CboxDecryptor.verifySignature(payload, server.contentPublicKey!!)\n"
    "                ) {\n"
    "                    Logger.e(\"Signature verification failed for server ${server.name}\")\n"
    "                    return Pair(emptyList(), null)\n"
    "                }\n"
    "                val xml = payload.takeXmlContentBytes()\n",
    "            val payload = CboxDecryptor.decrypt(ByteArrayInputStream(bytes), password)\n"
    "            if (payload != null) {\n"
    "                val publicKey = server.contentPublicKey?.takeUnless { it.isBlank() }\n"
    "                if (publicKey == null && payload.hasSignature) {\n"
    "                    payload.discard()\n"
    "                    Logger.e(\"Signed CBOX requires an explicit verification key for server ${server.name}\")\n"
    "                    return Pair(emptyList(), null)\n"
    "                }\n"
    "                if (publicKey != null && !CboxDecryptor.verifySignature(payload, publicKey)) {\n"
    "                    payload.discard()\n"
    "                    Logger.e(\"Signature verification failed for server ${server.name}\")\n"
    "                    return Pair(emptyList(), null)\n"
    "                }\n"
    "                val xml = payload.takeXmlContentBytes()\n",
)
replace_once(
    path,
    "                val allKeys = ArrayList<CertHack.KeyBox>()\n"
    "                val password = pack.password ?: server.contentPassword ?: \"\"\n"
    "                val publicKey = server.contentPublicKey\n",
    "                val allKeys = ArrayList<CertHack.KeyBox>()\n"
    "                val password = pack.password ?: server.contentPassword ?: \"\"\n"
    "                val publicKey = server.contentPublicKey?.takeUnless { it.isBlank() }\n",
)
replace_once(
    path,
    "                    if (!publicKey.isNullOrBlank() &&\n"
    "                        !CboxDecryptor.verifySignature(payload, publicKey)\n"
    "                    ) {\n"
    "                        Logger.e(\"Signature verification failed for zip entry $name\")\n"
    "                        return Pair(emptyList(), null)\n"
    "                    }\n"
    "                    val xml = payload.takeXmlContentBytes()\n",
    "                    if (publicKey == null && payload.hasSignature) {\n"
    "                        payload.discard()\n"
    "                        Logger.e(\"Signed zip entry requires an explicit verification key: $name\")\n"
    "                        return Pair(emptyList(), null)\n"
    "                    }\n"
    "                    if (publicKey != null && !CboxDecryptor.verifySignature(payload, publicKey)) {\n"
    "                        payload.discard()\n"
    "                        Logger.e(\"Signature verification failed for zip entry $name\")\n"
    "                        return Pair(emptyList(), null)\n"
    "                    }\n"
    "                    val xml = payload.takeXmlContentBytes()\n",
)

# Regression tests.
path = "service/src/test/java/cleveres/tricky/cleverestech/ProcStatParsingTest.kt"
replace_once(
    path,
    "    fun parsesAggregateCpuTicks() {\n"
    "        assertEquals(550L, parseTotalCpuTicks(\"cpu  10 20 30 40 50 60 70 80 90 100\"))\n"
    "    }\n",
    "    fun parsesAggregateCpuTicksWithoutDoubleCountingGuestTime() {\n"
    "        assertEquals(360L, parseTotalCpuTicks(\"cpu  10 20 30 40 50 60 70 80 90 100\"))\n"
    "        assertEquals(36L, parseTotalCpuTicks(\"cpu 1 2 3 4 5 6 7 8 100 200\"))\n"
    "    }\n",
)

path = "service/src/test/java/cleveres/tricky/cleverestech/StoredKeyboxInventoryTest.kt"
replace_once(
    path,
    "import org.junit.rules.TemporaryFolder\n"
    "import java.io.File\n",
    "import org.junit.rules.TemporaryFolder\n"
    "import java.io.File\n"
    "import java.io.RandomAccessFile\n",
)
replace_once(
    path,
    "    @Test\n"
    "    fun `runtime XML source count is bounded`() {\n",
    "    @Test\n"
    "    fun `oversized XML is rejected before content stamping`() {\n"
    "        val root = temp.newFolder(\"oversized\")\n"
    "        val oversized = File(root, \"oversized.xml\")\n"
    "        RandomAccessFile(oversized, \"rw\").use { file ->\n"
    "            file.setLength(StoredKeyboxInventory.MAX_XML_BYTES + 1)\n"
    "        }\n\n"
    "        val source = StoredKeyboxInventory.runtimeXmlSources(root).single()\n\n"
    "        assertEquals(File::class.java, source.file.javaClass)\n"
    "        assertEquals(StoredKeyboxInventory.MAX_XML_BYTES + 1, source.file.length())\n"
    "    }\n\n"
    "    @Test\n"
    "    fun `runtime XML source count is bounded`() {\n",
)

path = "service/src/test/java/cleveres/tricky/cleverestech/RuntimeWorkCoordinatorTest.kt"
replace_once(
    path,
    "import kotlinx.coroutines.CompletableDeferred\n"
    "import kotlinx.coroutines.CoroutineScope\n",
    "import kotlinx.coroutines.CompletableDeferred\n"
    "import kotlinx.coroutines.CoroutineExceptionHandler\n"
    "import kotlinx.coroutines.CoroutineScope\n",
)
replace_once(
    path,
    "    @Test\n"
    "    fun refreshSchedulerKeepsRefreshesSerializedAcrossCancelAndRestart() {\n",
    "    @Test\n"
    "    fun refreshSchedulerRunsPendingFollowUpAfterRefreshFailure() {\n"
    "        val failureObserved = CountDownLatch(1)\n"
    "        val exceptionHandler = CoroutineExceptionHandler { _, _ -> failureObserved.countDown() }\n"
    "        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)\n"
    "        val firstStarted = CountDownLatch(1)\n"
    "        val releaseFirst = CompletableDeferred<Unit>()\n"
    "        val secondFinished = CountDownLatch(1)\n"
    "        val count = AtomicInteger(0)\n"
    "        val scheduler =\n"
    "            ConflatedRefreshScheduler(scope, debounceMs = 10L) {\n"
    "                when (count.incrementAndGet()) {\n"
    "                    1 -> {\n"
    "                        firstStarted.countDown()\n"
    "                        releaseFirst.await()\n"
    "                        throw IllegalStateException(\"refresh failed\")\n"
    "                    }\n"
    "                    2 -> secondFinished.countDown()\n"
    "                }\n"
    "            }\n\n"
    "        try {\n"
    "            scheduler.submit()\n"
    "            assertTrue(\"Initial refresh did not start\", firstStarted.await(2, TimeUnit.SECONDS))\n"
    "            scheduler.submit()\n"
    "            releaseFirst.complete(Unit)\n"
    "            assertTrue(\"Refresh failure was not observed\", failureObserved.await(2, TimeUnit.SECONDS))\n"
    "            assertTrue(\"Pending follow-up was lost after failure\", secondFinished.await(2, TimeUnit.SECONDS))\n"
    "            assertEquals(2, count.get())\n"
    "        } finally {\n"
    "            releaseFirst.complete(Unit)\n"
    "            scheduler.cancel()\n"
    "            scope.cancel()\n"
    "        }\n"
    "    }\n\n"
    "    @Test\n"
    "    fun refreshSchedulerKeepsRefreshesSerializedAcrossCancelAndRestart() {\n",
)

path = "service/src/test/java/cleveres/tricky/cleverestech/ServerManagerCacheTest.kt"
replace_once(
    path,
    "import org.junit.Assert.assertArrayEquals\n"
    "import org.junit.Assert.assertEquals\n"
    "import org.junit.Test\n",
    "import org.junit.Assert.assertArrayEquals\n"
    "import org.junit.Assert.assertEquals\n"
    "import org.junit.Assert.assertNull\n"
    "import org.junit.Assert.assertTrue\n"
    "import org.junit.Test\n",
)
replace_once(
    path,
    "    private fun serverConfig() =\n"
    "        ServerManager.ServerConfig(\n",
    "    @Test\n"
    "    fun `signed direct cbox requires explicit verification key`() {\n"
    "        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)\n"
    "        CboxDecryptor.backendOpenOverride = { _, _, _ ->\n"
    "            NativeBackend.CboxPayload(\n"
    "                author = \"signed-test\",\n"
    "                xmlContent = sourceXml.copyOf(),\n"
    "                hasSignature = true,\n"
    "            )\n"
    "        }\n"
    "        KeyboxLoader.parserOverride = { xml, filename ->\n"
    "            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)\n"
    "        }\n"
    "        val cbox = supportedCboxEnvelope()\n\n"
    "        try {\n"
    "            val result = ServerManager.processContent(cbox, serverConfig())\n"
    "            assertTrue(result.first.isEmpty())\n"
    "            assertNull(result.second)\n"
    "        } finally {\n"
    "            cbox.fill(0)\n"
    "            sourceXml.fill(0)\n"
    "        }\n"
    "    }\n\n"
    "    @Test\n"
    "    fun `signed zip cbox requires explicit verification key`() {\n"
    "        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)\n"
    "        CboxDecryptor.backendOpenOverride = { _, _, _ ->\n"
    "            NativeBackend.CboxPayload(\n"
    "                author = \"signed-zip-test\",\n"
    "                xmlContent = sourceXml.copyOf(),\n"
    "                hasSignature = true,\n"
    "            )\n"
    "        }\n"
    "        KeyboxLoader.parserOverride = { xml, filename ->\n"
    "            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)\n"
    "        }\n"
    "        val cbox = supportedCboxEnvelope()\n"
    "        val archive = zipOf(\"issuer.cbox\", cbox)\n\n"
    "        try {\n"
    "            val result = ServerManager.processContent(archive, serverConfig())\n"
    "            assertTrue(result.first.isEmpty())\n"
    "            assertNull(result.second)\n"
    "        } finally {\n"
    "            archive.fill(0)\n"
    "            cbox.fill(0)\n"
    "            sourceXml.fill(0)\n"
    "        }\n"
    "    }\n\n"
    "    @Test\n"
    "    fun `signed cbox with verification key remains accepted`() {\n"
    "        val sourceXml = TestKeyboxFixtures.validEcKeyboxXml.toByteArray(StandardCharsets.UTF_8)\n"
    "        CboxDecryptor.backendOpenOverride = { _, _, _ ->\n"
    "            NativeBackend.CboxPayload(\n"
    "                author = \"signed-key-test\",\n"
    "                xmlContent = sourceXml.copyOf(),\n"
    "                hasSignature = true,\n"
    "            )\n"
    "        }\n"
    "        KeyboxLoader.parserOverride = { xml, filename ->\n"
    "            ManagedOpaqueKeyOracle.parse(StringReader(String(xml, StandardCharsets.UTF_8)), filename)\n"
    "        }\n"
    "        val cbox = supportedCboxEnvelope()\n"
    "        var cached: ByteArray? = null\n\n"
    "        try {\n"
    "            val result = ServerManager.processContent(cbox, serverConfig(contentPublicKey = \"test-key\"))\n"
    "            cached = result.second\n"
    "            assertEquals(1, result.first.size)\n"
    "            assertTrue(cached != null)\n"
    "        } finally {\n"
    "            cached?.fill(0)\n"
    "            cbox.fill(0)\n"
    "            sourceXml.fill(0)\n"
    "        }\n"
    "    }\n\n"
    "    private fun serverConfig(contentPublicKey: String? = null) =\n"
    "        ServerManager.ServerConfig(\n",
)
replace_once(
    path,
    "            refreshIntervalHours = 24,\n"
    "        )\n",
    "            refreshIntervalHours = 24,\n"
    "            contentPublicKey = contentPublicKey,\n"
    "        )\n",
)
