package cleveres.tricky.cleverestech.binder

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BinderInterceptorTest {

    @Before
    fun setup() {
        Parcel.resetStats()
    }

    @Test
    fun testOnTransactPostTransact_ZeroSz2_ReducesObtain() {
        val interceptor = object : BinderInterceptor() {
            override fun onPostTransact(
                target: IBinder,
                code: Int,
                flags: Int,
                callingUid: Int,
                callingPid: Int,
                data: Parcel,
                reply: Parcel?,
                resultCode: Int
            ): Result {
                return BinderInterceptor.Continue
            }
        }

        val data = Parcel.obtain()
        // Setup data for POST_TRANSACT (code 2)
        // val target = data.readStrongBinder()
        data.pushBinder(Binder())
        // val theCode = data.readInt()
        data.pushInt(0)
        // val theFlags = data.readInt()
        data.pushInt(0)
        // val callingUid = data.readInt()
        data.pushInt(1000)
        // val callingPid = data.readInt()
        data.pushInt(100)
        // val resultCode = data.readInt()
        data.pushInt(0)

        // val sz = data.readLong().toInt()
        data.pushLong(10L) // sz = 10

        // val sz2 = data.readLong().toInt()
        data.pushLong(0L) // sz2 = 0

        val reply = Parcel.obtain()

        // Reset stats before calling onTransact to count calls inside it
        Parcel.resetStats()

        interceptor.transact(2, data, reply, 0)

        // In unoptimized code:
        // 1. val theData = Parcel.obtain()
        // 2. val theReply = Parcel.obtain()
        // Total 2 calls.

        // After optimization, if sz2 is 0, it should be 1 call.
        assertEquals(1, Parcel.obtainCount.get())
    }

    @Test
    fun testOnTransactPostTransact_NonZeroSz2_AllocatesTwo() {
        val interceptor = object : BinderInterceptor() {
            override fun onPostTransact(
                target: IBinder,
                code: Int,
                flags: Int,
                callingUid: Int,
                callingPid: Int,
                data: Parcel,
                reply: Parcel?,
                resultCode: Int
            ): Result {
                return BinderInterceptor.Continue
            }
        }

        val data = Parcel.obtain()
        // Setup data for POST_TRANSACT (code 2)
        data.pushBinder(Binder())
        data.pushInt(0)
        data.pushInt(0)
        data.pushInt(1000)
        data.pushInt(100)
        data.pushInt(0)
        data.pushLong(10L) // sz = 10
        data.pushLong(5L)  // sz2 = 5 (non-zero)

        val reply = Parcel.obtain()

        Parcel.resetStats()
        interceptor.transact(2, data, reply, 0)

        // 1. theData obtained
        // 2. theReply obtained (sz2 != 0)
        assertEquals(2, Parcel.obtainCount.get())
    }

    @Test
    fun testOnInterceptorReplaced() {
        val replaced = java.util.concurrent.atomic.AtomicBoolean(false)
        val interceptor = object : BinderInterceptor() {
            override fun onInterceptorReplaced() {
                replaced.set(true)
            }
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        interceptor.transact(3, data, reply, 0)

        assertEquals(true, replaced.get())

        data.recycle()
        reply.recycle()
    }
}
