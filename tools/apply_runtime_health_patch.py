from pathlib import Path

path = Path("service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt")
text = path.read_text()

marker = "    override fun serve(session: IHTTPSession): Response {\n"
helper = '''    private fun readProcessStartTicks(pid: Int): Long? {
        if (pid <= 0) return null
        val statFile = File("/proc/$pid/stat")
        return runCatching {
            if (!Files.isRegularFile(statFile.toPath(), LinkOption.NOFOLLOW_LINKS) || statFile.length() > 16 * 1024) {
                return@runCatching null
            }
            val stat = statFile.readText()
            val commandEnd = stat.lastIndexOf(')')
            if (commandEnd < 0) return@runCatching null
            stat.substring(commandEnd + 1)
                .trim()
                .splitToSequence(' ')
                .filter { it.isNotEmpty() }
                .elementAtOrNull(19)
                ?.toLongOrNull()
        }.getOrNull()
    }

    private fun readNativeRuntimeStatus(): JSONObject {
        val unavailable = JSONObject().put("state", "unavailable").put("alive", false)
        val statusFile = File(configDir, "native_runtime_status")
        if (!Files.isRegularFile(statusFile.toPath(), LinkOption.NOFOLLOW_LINKS) || statusFile.length() !in 1..4096) {
            return unavailable
        }
        return runCatching {
            val values = LinkedHashMap<String, String>()
            statusFile.useLines { lines ->
                lines.take(16).forEach { line ->
                    val separator = line.indexOf('=')
                    if (separator > 0 && separator < line.lastIndex) {
                        values[line.substring(0, separator)] = line.substring(separator + 1)
                    }
                }
            }
            if (values["version"] != "1") return@runCatching unavailable
            val state = values["state"]?.takeIf { it in setOf("starting", "active", "failed") }
                ?: return@runCatching unavailable
            val pid = values["pid"]?.toIntOrNull()?.takeIf { it > 0 } ?: 0
            val recordedStartTicks = values["start_ticks"]?.toLongOrNull()?.takeIf { it > 0 } ?: 0L
            val currentStartTicks = readProcessStartTicks(pid)
            val alive = pid > 0 && recordedStartTicks > 0 && currentStartTicks == recordedStartTicks
            JSONObject()
                .put("state", state)
                .put("alive", alive)
                .put("pid", pid)
                .put("entry", values["entry"] ?: "unknown")
                .put("timestamp_ms", values["timestamp_ms"]?.toLongOrNull() ?: 0L)
        }.getOrElse { unavailable }
    }

'''
if marker not in text:
    raise SystemExit("serve marker not found")
text = text.replace(marker, helper + marker, 1)

old_api = '''            json.put("real_ram_kb", getRamUsageKb())
            json.put("real_cpu", getCpuUsagePercent())
            json.put("environment", getEnvironmentInfo())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
'''
new_api = '''            json.put("real_ram_kb", getRamUsageKb())
            json.put("real_cpu", getCpuUsagePercent())
            json.put("environment", getEnvironmentInfo())
            json.put("native_runtime", readNativeRuntimeStatus())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
'''
if old_api not in text:
    raise SystemExit("resource api marker not found")
text = text.replace(old_api, new_api, 1)

old_health = '''            const health = document.getElementById('runtimeHealth');
            const healthText = document.getElementById('runtimeHealthText');
            const healthBadge = document.getElementById('runtimeHealthBadge');
            if (health && healthText && healthBadge) {
                const keyboxCount = Number(data.keybox_count || 0);
                let state = 'ok';
                let badge = 'READY';
                let message;
                if (!data.spoof_enabled) {
                    state = 'error';
                    badge = 'PAUSED';
                    message = 'Spoof Engine is paused, so runtime interception paths are parked.';
                } else if (data.tee_broken_mode) {
                    state = 'warn';
                    badge = 'SAFE MODE';
                    message = 'Certificate Safe Mode is enabled, so certificate substitution is intentionally disabled.';
                } else if (keyboxCount <= 0) {
                    state = 'warn';
                    badge = 'NO KEYS';
                    message = 'The runtime controller is active, but no verified keybox is currently active.';
                } else if (!data.global_mode) {
                    message = 'Runtime controller is active with ' + keyboxCount + ' verified keybox' + (keyboxCount === 1 ? '' : 'es') + '. Targeted mode is enabled, so app rules determine scope.';
                } else {
                    message = 'Runtime controller is active with ' + keyboxCount + ' verified keybox' + (keyboxCount === 1 ? '' : 'es') + '. Global application scope is enabled.';
                }
                health.dataset.state = state;
                healthBadge.textContent = badge;
                healthText.textContent = message + ' Hardware bootloader and root-of-trust state remain genuine.';
            }

            const features = [
                { id: 'spoof_enabled', name: 'Spoof Engine', activity: data.spoof_enabled ? 'Runtime controller active' : 'Interceptors parked', scope: 'All spoof and hook paths', desc: 'Master switch; boot-disabled mode avoids native injection entirely.' },
'''
new_health = '''            const health = document.getElementById('runtimeHealth');
            const healthText = document.getElementById('runtimeHealthText');
            const healthBadge = document.getElementById('runtimeHealthBadge');
            const nativeRuntime = data.native_runtime || {};
            const nativeActive = nativeRuntime.state === 'active' && nativeRuntime.alive === true;
            if (health && healthText && healthBadge) {
                const keyboxCount = Number(data.keybox_count || 0);
                let state = 'ok';
                let badge = 'READY';
                let message;
                if (!data.spoof_enabled) {
                    state = 'error';
                    badge = 'PAUSED';
                    message = 'Spoof Engine is paused, so runtime interception paths are parked.';
                } else if (!nativeActive) {
                    state = 'error';
                    badge = nativeRuntime.state === 'failed' ? 'NATIVE FAILED' : 'NATIVE OFFLINE';
                    if (nativeRuntime.state === 'starting') {
                        message = 'The runtime is configured to run, but native activation is still in progress.';
                    } else if (nativeRuntime.state === 'failed') {
                        message = 'The last native activation attempt failed. Open Logs and inspect the first CleveresTricky error.';
                    } else {
                        message = 'The runtime is configured to run, but no live native activation snapshot matches the current target process.';
                    }
                } else if (data.tee_broken_mode) {
                    state = 'warn';
                    badge = 'SAFE MODE';
                    message = 'Certificate Safe Mode is enabled, so certificate substitution is intentionally disabled.';
                } else if (keyboxCount <= 0) {
                    state = 'warn';
                    badge = 'NO KEYS';
                    message = 'The native runtime is active, but no verified keybox is currently active.';
                } else if (!data.global_mode) {
                    message = 'Native runtime is active with ' + keyboxCount + ' verified keybox' + (keyboxCount === 1 ? '' : 'es') + '. Targeted mode is enabled, so app rules determine scope.';
                } else {
                    message = 'Native runtime is active with ' + keyboxCount + ' verified keybox' + (keyboxCount === 1 ? '' : 'es') + '. Global application scope is enabled.';
                }
                health.dataset.state = state;
                healthBadge.textContent = badge;
                healthText.textContent = message + ' Hardware bootloader and root-of-trust state remain genuine.';
            }

            const features = [
                { id: 'spoof_enabled', name: 'Spoof Engine', activity: data.spoof_enabled ? (nativeActive ? 'Native runtime active' : 'Configured; native runtime unavailable') : 'Interceptors parked', scope: 'All spoof and hook paths', desc: 'Master switch; boot-disabled mode avoids native injection entirely.' },
'''
if old_health not in text:
    raise SystemExit("health block marker not found")
text = text.replace(old_health, new_health, 1)
path.write_text(text)
