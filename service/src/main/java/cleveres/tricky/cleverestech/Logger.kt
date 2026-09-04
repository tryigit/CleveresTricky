package cleveres.tricky.cleverestech

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

object Logger {
    const val TAG = "cleverestricky"
    private const val DEBUG_FLAG_REFRESH_NANOS = 2_000_000_000L
    private val runtimeDebugFlag = File("/data/adb/cleverestricky/debug_logging")

    @Volatile
    private var cachedRuntimeDebug = false

    @Volatile
    private var lastRuntimeDebugCheckNanos = Long.MIN_VALUE

    interface LogImpl {
        fun d(
            tag: String,
            msg: String,
        )

        fun e(
            tag: String,
            msg: String,
        )

        fun e(
            tag: String,
            msg: String,
            t: Throwable?,
        )

        fun i(
            tag: String,
            msg: String,
        )

        fun w(
            tag: String,
            msg: String,
        ) {
            i(tag, msg)
        }

        fun w(
            tag: String,
            msg: String,
            t: Throwable?,
        ) {
            w(tag, if (t?.message.isNullOrBlank()) msg else "$msg: ${t.message}")
        }
    }

    private var impl: LogImpl =
        object : LogImpl {
            override fun d(
                tag: String,
                msg: String,
            ) {
                runCatching { Log.d(tag, msg) }
            }

            override fun e(
                tag: String,
                msg: String,
            ) {
                runCatching { Log.e(tag, msg) }
            }

            override fun e(
                tag: String,
                msg: String,
                t: Throwable?,
            ) {
                runCatching {
                    if (t != null) {
                        Log.e(tag, msg, t)
                    } else {
                        Log.e(tag, msg)
                    }
                }
            }

            override fun i(
                tag: String,
                msg: String,
            ) {
                runCatching { Log.i(tag, msg) }
            }

            override fun w(
                tag: String,
                msg: String,
            ) {
                runCatching { Log.w(tag, msg) }
            }

            override fun w(
                tag: String,
                msg: String,
                t: Throwable?,
            ) {
                runCatching {
                    if (t != null) {
                        Log.w(tag, msg, t)
                    } else {
                        Log.w(tag, msg)
                    }
                }
            }
        }

    @JvmStatic
    fun setImpl(newImpl: LogImpl) {
        impl = newImpl
    }

    @JvmStatic
    fun d(msg: String) {
        if (isDebugEnabled()) impl.d(TAG, msg)
    }

    @JvmStatic
    fun d(
        tag: String,
        msg: String,
    ) {
        if (isDebugEnabled()) impl.d(tag, msg)
    }

    @JvmStatic
    inline fun d(msg: () -> String) {
        if (isDebugEnabled()) emitDebug(msg())
    }

    @PublishedApi
    internal fun emitDebug(msg: String) {
        impl.d(TAG, msg)
    }

    @JvmStatic
    fun e(msg: String) {
        impl.e(TAG, msg)
    }

    @JvmStatic
    fun e(
        tag: String,
        msg: String,
    ) {
        impl.e(tag, msg)
    }

    @JvmStatic
    fun e(
        msg: String,
        t: Throwable?,
    ) {
        impl.e(TAG, msg, t)
    }

    @JvmStatic
    fun e(
        tag: String,
        msg: String,
        t: Throwable?,
    ) {
        impl.e(tag, msg, t)
    }

    @JvmStatic
    fun i(msg: String) {
        impl.i(TAG, msg)
    }

    @JvmStatic
    fun i(
        tag: String,
        msg: String,
    ) {
        impl.i(tag, msg)
    }

    @JvmStatic
    fun w(msg: String) {
        impl.w(TAG, msg)
    }

    @JvmStatic
    fun w(
        msg: String,
        t: Throwable?,
    ) {
        impl.w(TAG, msg, t)
    }

    @JvmStatic
    fun w(
        tag: String,
        msg: String,
    ) {
        impl.w(tag, msg)
    }

    @JvmStatic
    fun w(
        tag: String,
        msg: String,
        t: Throwable?,
    ) {
        impl.w(tag, msg, t)
    }

    @JvmStatic
    inline fun i(msg: () -> String) {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.INFO)) {
            i(msg())
        }
    }

    @JvmStatic
    fun isDebugEnabled(): Boolean {
        if (BuildConfig.DEBUG || Log.isLoggable(TAG, Log.DEBUG)) return true
        val now = System.nanoTime()
        val last = lastRuntimeDebugCheckNanos
        if (last != Long.MIN_VALUE && now >= last && now - last < DEBUG_FLAG_REFRESH_NANOS) {
            return cachedRuntimeDebug
        }
        return synchronized(this) {
            val secondLast = lastRuntimeDebugCheckNanos
            if (secondLast != Long.MIN_VALUE && now >= secondLast && now - secondLast < DEBUG_FLAG_REFRESH_NANOS) {
                cachedRuntimeDebug
            } else {
                val path = runtimeDebugFlag.toPath()
                cachedRuntimeDebug =
                    runCatching {
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
                    }.getOrDefault(false)
                lastRuntimeDebugCheckNanos = now
                cachedRuntimeDebug
            }
        }
    }
}
