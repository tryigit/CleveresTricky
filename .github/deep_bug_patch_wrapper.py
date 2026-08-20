from pathlib import Path


script_path = Path(".github/deep_bug_patch.py")
script = script_path.read_text()

# The signature policy is enforced at the CboxDecryptor plaintext-release boundary
# instead of duplicating the rule in each ServerManager container path. Remove the
# earlier ServerManager-specific patch section before executing the deterministic
# source/test edits.
server_start = script.index(
    'path = "service/src/main/java/cleveres/tricky/cleverestech/ServerManager.kt"'
)
server_end = script.index("# Regression tests.", server_start)
script = script[:server_start] + script[server_end:]
exec(compile(script, str(script_path), "exec"), {"__name__": "__main__"})


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: wrapper expected one match, found {count}")
    target.write_text(text.replace(old, new, 1))


# A signed CBOX may expose author metadata before verification, but plaintext XML
# must never cross the compatibility boundary until verifySignature() succeeds.
cbox = "service/src/main/java/cleveres/tricky/cleverestech/util/CboxDecryptor.kt"
replace_once(
    cbox,
    "        @Synchronized\n"
    "        internal fun takeXmlContentBytes(): ByteArray {\n"
    "            val payload = openWithoutVerification() ?: return ByteArray(0)\n"
    "            val copy = payload.xmlContent.copyOf()\n"
    "            payload.xmlContent.fill(0)\n"
    "            return copy\n"
    "        }\n",
    "        @Synchronized\n"
    "        internal fun takeXmlContentBytes(): ByteArray {\n"
    "            val payload = openWithoutVerification() ?: return ByteArray(0)\n"
    "            if (hasSignature && verifiedPublicKey == null) {\n"
    "                discard()\n"
    "                return ByteArray(0)\n"
    "            }\n"
    "            val copy = payload.xmlContent.copyOf()\n"
    "            payload.xmlContent.fill(0)\n"
    "            return copy\n"
    "        }\n",
)

# Ensure an old cancelled worker cannot overwrite a replacement worker if a
# non-cancellable refresh later throws a non-cancellation exception.
scheduler = "service/src/main/java/cleveres/tricky/cleverestech/RuntimeWorkCoordinator.kt"
replace_once(
    scheduler,
    "import kotlinx.coroutines.CoroutineScope\n"
    "import kotlinx.coroutines.Dispatchers\n",
    "import kotlinx.coroutines.CoroutineScope\n"
    "import kotlinx.coroutines.Dispatchers\n"
    "import kotlinx.coroutines.currentCoroutineContext\n",
)
replace_once(
    scheduler,
    "    private suspend fun drainRequests() {\n"
    "        while (true) {\n",
    "    private suspend fun drainRequests() {\n"
    "        val currentWorker = currentCoroutineContext()[Job]\n"
    "        while (true) {\n",
)
replace_once(
    scheduler,
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
    "            }\n",
    "            } catch (error: Throwable) {\n"
    "                synchronized(stateLock) {\n"
    "                    if (workerJob === currentWorker) {\n"
    "                        workerJob =\n"
    "                            if (requestedGeneration != generation) {\n"
    "                                scope.launch { drainRequests() }\n"
    "                            } else {\n"
    "                                null\n"
    "                            }\n"
    "                    }\n"
    "                }\n"
    "                throw error\n"
    "            }\n",
)
