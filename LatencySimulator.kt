package com.android.keystore

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.delay

class LatencySimulator(private val context: Context) {
    // LATENCY EMULATION LAYER
    // Emulate real hardware delays

    companion object {
        const val MIN_TEE_WAIT_MS = 15L
        const val STRONGBOX_GENERATE_WAIT_MS = 250L
        const val STRONGBOX_CREATE_WAIT_MS = 80L

        const val CANNOT_ATTEST_IDS = -66
    }

    suspend fun emulateTeeOperation(action: () -> Unit) {
        val start = System.nanoTime()
        action()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        if (elapsedMs < MIN_TEE_WAIT_MS) {
            delay(MIN_TEE_WAIT_MS - elapsedMs)
        }
    }

    suspend fun emulateStrongBoxGenerateKey(action: () -> Unit) {
        val start = System.nanoTime()
        action()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        if (elapsedMs < STRONGBOX_GENERATE_WAIT_MS) {
            delay(STRONGBOX_GENERATE_WAIT_MS - elapsedMs)
        }
    }

    suspend fun emulateStrongBoxCreateOperation(action: () -> Unit) {
        val start = System.nanoTime()
        action()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        if (elapsedMs < STRONGBOX_CREATE_WAIT_MS) {
            delay(STRONGBOX_CREATE_WAIT_MS - elapsedMs)
        }
    }

    fun requestDeviceId(callerUid: Int, callerPid: Int): Int {
        // Access Control: Enforce READ_PRIVILEGED_PHONE_STATE
        val permission = context.checkPermission(
            "android.permission.READ_PRIVILEGED_PHONE_STATE",
            callerPid,
            callerUid
        )

        if (permission != PackageManager.PERMISSION_GRANTED) {
            return CANNOT_ATTEST_IDS
        }

        return 0 // Success
    }
}
