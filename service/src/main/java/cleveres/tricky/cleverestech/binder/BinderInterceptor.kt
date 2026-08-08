package cleveres.tricky.cleverestech.binder

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import cleveres.tricky.cleverestech.Logger

open class BinderInterceptor : Binder() {
    sealed class Result

    data object Skip : Result()

    data object Continue : Result()

    data class OverrideData(val data: Parcel) : Result()

    data class OverrideReply(val code: Int = 0, val reply: Parcel) : Result()

    companion object {
        private const val PRE_TRANSACT = 1
        private const val POST_TRANSACT = 2
        private const val INTERCEPTOR_REPLACED = 3
        private const val REGISTER_INTERCEPTOR = 1
        private const val UNREGISTER_INTERCEPTOR = 2
        private const val PARK_HOOK = 3
        private const val CONTROL_ENDPOINT_TRANSACTION = 0xdeadbeef.toInt()
        private const val MAX_FILTERED_CODES = 1024
        private const val MAX_INTERCEPT_PARCEL_BYTES = 8L * 1024 * 1024

        fun getBinderControlEndpoint(remote: IBinder): IBinder? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                if (!remote.transact(CONTROL_ENDPOINT_TRANSACTION, data, reply, 0)) {
                    Logger.d("Native Binder control endpoint is not installed")
                    null
                } else {
                    reply.readStrongBinder()
                }
            } catch (error: Throwable) {
                Logger.e("Failed to query native Binder control endpoint", error)
                null
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun registerBinderInterceptor(
            controlEndpoint: IBinder,
            target: IBinder,
            interceptor: BinderInterceptor,
            filteredCodes: IntArray,
        ): Boolean {
            val codes = filteredCodes.filter { it > 0 }.distinct()
            if (codes.isEmpty() || codes.size > MAX_FILTERED_CODES) {
                Logger.e("Refusing invalid Binder transaction filter")
                return false
            }

            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeStrongBinder(target)
                data.writeStrongBinder(interceptor)
                data.writeInt(codes.size)
                codes.forEach(data::writeInt)
                val handled = controlEndpoint.transact(REGISTER_INTERCEPTOR, data, reply, 0)
                val status = if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt() else -1
                handled && status == 0
            } catch (error: Throwable) {
                Logger.e("Failed to register Binder interceptor", error)
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun unregisterBinderInterceptor(
            controlEndpoint: IBinder,
            target: IBinder,
            interceptor: BinderInterceptor,
        ): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeStrongBinder(target)
                data.writeStrongBinder(interceptor)
                val handled = controlEndpoint.transact(UNREGISTER_INTERCEPTOR, data, reply, 0)
                val status = if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt() else -1
                handled && status == 0
            } catch (error: Throwable) {
                Logger.e("Failed to unregister Binder interceptor", error)
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        fun parkBinderHook(controlEndpoint: IBinder): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                val handled = controlEndpoint.transact(PARK_HOOK, data, reply, 0)
                val status = if (reply.dataAvail() >= Int.SIZE_BYTES) reply.readInt() else -1
                handled && status == 0
            } catch (error: Throwable) {
                Logger.e("Failed to park an idle Binder hook", error)
                false
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

        private fun readBoundedSize(parcel: Parcel): Int? {
            if (parcel.dataAvail() < Long.SIZE_BYTES) return null
            val declared = parcel.readLong()
            return declared
                .takeIf { it in 0..MAX_INTERCEPT_PARCEL_BYTES && it <= parcel.dataAvail().toLong() }
                ?.toInt()
        }
    }

    open fun onPreTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
    ): Result = Skip

    open fun onPostTransact(
        target: IBinder,
        code: Int,
        flags: Int,
        callingUid: Int,
        callingPid: Int,
        data: Parcel,
        reply: Parcel?,
        resultCode: Int,
    ): Result = Skip

    open fun onInterceptorReplaced() = Unit

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        val result =
            try {
                when (code) {
                    PRE_TRANSACT -> readPreTransaction(data)
                    POST_TRANSACT -> readPostTransaction(data)
                    INTERCEPTOR_REPLACED -> {
                        onInterceptorReplaced()
                        Skip
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
            } catch (error: Throwable) {
                Logger.e("Rejected malformed Binder interception parcel", error)
                Skip
            }

        val boundedResult = enforceResultSize(result)
        if (reply == null) {
            recycleResult(boundedResult)
            return true
        }

        when (boundedResult) {
            Skip -> reply.writeInt(1)
            Continue -> reply.writeInt(2)
            is OverrideReply -> {
                reply.writeInt(3)
                reply.writeInt(boundedResult.code)
                reply.writeLong(boundedResult.reply.dataSize().toLong())
                reply.appendFrom(boundedResult.reply, 0, boundedResult.reply.dataSize())
                boundedResult.reply.recycle()
            }
            is OverrideData -> {
                reply.writeInt(4)
                reply.writeLong(boundedResult.data.dataSize().toLong())
                reply.appendFrom(boundedResult.data, 0, boundedResult.data.dataSize())
                boundedResult.data.recycle()
            }
        }
        return true
    }

    private fun readPreTransaction(data: Parcel): Result {
        val target = requireNotNull(data.readStrongBinder()) { "Missing Binder target" }
        val transactionCode = data.readInt()
        val transactionFlags = data.readInt()
        val callingUid = data.readInt()
        val callingPid = data.readInt()
        val requestSize = readBoundedSize(data) ?: return Skip
        if (requestSize != data.dataAvail()) return Skip
        return onPreTransact(
            target,
            transactionCode,
            transactionFlags,
            callingUid,
            callingPid,
            data,
        )
    }

    private fun readPostTransaction(data: Parcel): Result {
        val target = requireNotNull(data.readStrongBinder()) { "Missing Binder target" }
        val transactionCode = data.readInt()
        val transactionFlags = data.readInt()
        val callingUid = data.readInt()
        val callingPid = data.readInt()
        val resultCode = data.readInt()
        val request = Parcel.obtain()
        var response: Parcel? = null
        val result =
            try {
                val requestSize = requireNotNull(readBoundedSize(data)) { "Invalid request size" }
                request.appendFrom(data, data.dataPosition(), requestSize)
                request.setDataPosition(0)
                data.setDataPosition(data.dataPosition() + requestSize)

                val responseSize = requireNotNull(readBoundedSize(data)) { "Invalid response size" }
                require(responseSize == data.dataAvail()) { "Trailing response data" }
                if (responseSize > 0) {
                    response =
                        Parcel.obtain().also { parcel ->
                            parcel.appendFrom(data, data.dataPosition(), responseSize)
                            parcel.setDataPosition(0)
                        }
                }
                onPostTransact(
                    target,
                    transactionCode,
                    transactionFlags,
                    callingUid,
                    callingPid,
                    request,
                    response,
                    resultCode,
                )
            } catch (error: Throwable) {
                Logger.e("Exception in post-transaction interceptor", error)
                Skip
            }

        if (result !is OverrideData || result.data !== request) request.recycle()
        response?.let { responseParcel ->
            if (result !is OverrideReply || result.reply !== responseParcel) {
                responseParcel.recycle()
            }
        }
        return result
    }

    private fun enforceResultSize(result: Result): Result =
        when (result) {
            is OverrideData ->
                if (result.data.dataSize().toLong() <= MAX_INTERCEPT_PARCEL_BYTES) {
                    result
                } else {
                    result.data.recycle()
                    Skip
                }
            is OverrideReply ->
                if (result.reply.dataSize().toLong() <= MAX_INTERCEPT_PARCEL_BYTES) {
                    result
                } else {
                    result.reply.recycle()
                    Skip
                }
            else -> result
        }

    private fun recycleResult(result: Result) {
        when (result) {
            is OverrideData -> result.data.recycle()
            is OverrideReply -> result.reply.recycle()
            else -> Unit
        }
    }
}
