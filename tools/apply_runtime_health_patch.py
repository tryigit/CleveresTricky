from pathlib import Path

p = Path("service/src/main/java/cleveres/tricky/cleverestech/WebServer.kt")
s = p.read_text()

old = '''            json.put("environment", getEnvironmentInfo())
            json.put("native_runtime", readNativeRuntimeStatus())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
'''
new = '''            json.put("environment", getEnvironmentInfo())
            json.put("native_runtime", readNativeRuntimeStatus())
            json.put("keystore_interceptor_running", KeystoreInterceptor.isRunning())
            json.put("telephony_interceptor_running", TelephonyInterceptor.isRunning())
            return secureResponse(Response.Status.OK, "application/json", json.toString())
'''
if old not in s:
    raise SystemExit("resource status marker not found")
s = s.replace(old, new, 1)

old = '''            const nativeRuntime = data.native_runtime || {};
            const nativeActive = nativeRuntime.state === 'active' && nativeRuntime.alive === true;
            if (health && healthText && healthBadge) {
'''
new = '''            const nativeRuntime = data.native_runtime || {};
            const nativeActive = nativeRuntime.state === 'active' && nativeRuntime.alive === true;
            const keystoreRunning = data.keystore_interceptor_running === true;
            const telephonyRunning = data.telephony_interceptor_running === true;
            if (health && healthText && healthBadge) {
'''
if old not in s:
    raise SystemExit("runtime variable marker not found")
s = s.replace(old, new, 1)

old = '''                } else if (!nativeActive) {
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
'''
new = '''                } else if (!keystoreRunning) {
                    state = 'error';
                    if (nativeRuntime.state === 'failed') {
                        badge = 'NATIVE FAILED';
                        message = 'The last native activation attempt failed before the Keystore interceptor became operational.';
                    } else if (nativeActive) {
                        badge = 'REGISTERING';
                        message = 'A native target accepted activation, but the Keystore Binder interceptor is not registered yet.';
                    } else if (nativeRuntime.state === 'starting') {
                        badge = 'STARTING';
                        message = 'Native activation is in progress and the Keystore interceptor is not registered yet.';
                    } else {
                        badge = 'NATIVE OFFLINE';
                        message = 'No operational Keystore interceptor is registered and no matching live native activation is available.';
                    }
                } else if (data.tee_broken_mode) {
'''
if old not in s:
    raise SystemExit("health decision marker not found")
s = s.replace(old, new, 1)

old = '''                { id: 'spoof_enabled', name: 'Spoof Engine', activity: data.spoof_enabled ? (nativeActive ? 'Native runtime active' : 'Configured; native runtime unavailable') : 'Interceptors parked', scope: 'All spoof and hook paths', desc: 'Master switch; boot-disabled mode avoids native injection entirely.' },
                { id: 'global_mode', name: 'Global Mode', activity: 'UID decision only', scope: 'Resolved application UIDs', desc: 'Targets every eligible app while protecting system and RKP infrastructure UIDs.' },
'''
new = '''                { id: 'spoof_enabled', name: 'Spoof Engine', activity: data.spoof_enabled ? (keystoreRunning ? 'Keystore interceptor operational' : (nativeActive ? 'Native active; registration pending' : 'Configured; native runtime unavailable')) : 'Interceptors parked', scope: 'All spoof and hook paths', desc: 'Master switch; operational readiness requires a live Keystore Binder registration.' },
                { id: 'keystore_runtime', name: 'Keystore Runtime', activity: keystoreRunning ? 'Registered and Binder alive' : 'Not operational', scope: 'Keystore2 Binder lifecycle', desc: 'Reports the daemon registration state rather than inferring readiness from configuration.' },
                { id: 'telephony_runtime', name: 'Telephony Runtime', activity: data.telephony ? (telephonyRunning ? 'Registered and Binder alive' : 'Enabled but not operational') : 'Disabled', scope: 'Phone subscription Binder lifecycle', desc: 'Reports the independent telephony registration state.' },
                { id: 'global_mode', name: 'Global Mode', activity: 'UID decision only', scope: 'Resolved application UIDs', desc: 'Targets every eligible app while protecting system and RKP infrastructure UIDs.' },
'''
if old not in s:
    raise SystemExit("feature marker not found")
s = s.replace(old, new, 1)
p.write_text(s)
Path(".runtime-readiness-trigger").unlink(missing_ok=True)
