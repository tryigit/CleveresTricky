package cleveres.tricky.cleverestech

import android.util.Log

object Logger {
    const val TAG = "TrickyStore"

    interface LogImpl {
        fun d(tag: String, msg: String)
        fun e(tag: String, msg: String)
        fun e(tag: String, msg: String, t: Throwable?)
        fun i(tag: String, msg: String)
    }

    private var impl: LogImpl = object : LogImpl {
        override fun d(tag: String, msg: String) {
            Log.d(tag, msg)
        }

        override fun e(tag: String, msg: String) {
            Log.e(tag, msg)
        }

        override fun e(tag: String, msg: String, t: Throwable?) {
            if (t != null) {
                Log.e(tag, msg, t)
            } else {
                Log.e(tag, msg)
            }
        }

        override fun i(tag: String, msg: String) {
            Log.i(tag, msg)
        }
    }

    @JvmStatic
    fun setImpl(newImpl: LogImpl) {
        impl = newImpl
    }

    @JvmStatic
    fun d(msg: String) {
        impl.d(TAG, msg)
    }

    @JvmStatic
    inline fun d(msg: () -> String) {
        if (isDebugEnabled()) {
            d(msg())
        }
    }

    @JvmStatic
    fun e(msg: String) {
        impl.e(TAG, msg)
    }

    @JvmStatic
    fun e(msg: String, t: Throwable?) {
        impl.e(TAG, msg, t)
    }

    @JvmStatic
    fun i(msg: String) {
        impl.i(TAG, msg)
    }

    @JvmStatic
    inline fun i(msg: () -> String) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            i(msg())
        }
    }

    @JvmStatic
    fun isDebugEnabled(): Boolean {
        return Log.isLoggable(TAG, Log.DEBUG)
    }
}
