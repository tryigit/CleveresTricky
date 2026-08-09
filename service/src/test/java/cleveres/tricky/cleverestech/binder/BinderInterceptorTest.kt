package cleveres.tricky.cleverestech.binder

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BinderInterceptorTest {
    @Before
    fun setup() {
        Parcel.resetStats()
    }

    @Test
    fun testOnTransactPostTransact_ZeroSz2_ReducesObtain() {
        val interceptor = BinderInterceptor()

        val data = Parcel.obtain()
        data.pushBinder(Binder())
        data.pushInt(0)
        data.pushInt(0)
        data.pushInt(1000)
        data.pushInt(100)
        data.pushInt(0)
        data.pushLong(0L)
        data.pushLong(0L)

        val reply = Parcel.obtain()
        Parcel.resetStats()
        interceptor.transact(2, data, reply, 0)

        assertEquals(1, Parcel.obtainCount.get())
    }

    @Test
    fun testOnTransactPostTransact_NonZeroSz2_AllocatesTwo() {
        val interceptor = BinderInterceptor()

        val data = Parcel.obtain()
        data.pushBinder(Binder())
        data.pushInt(0)
        data.pushInt(0)
        data.pushInt(1000)
        data.pushInt(100)
        data.pushInt(0)
        data.pushLong(0L)
        data.pushLong(Long.SIZE_BYTES.toLong())

        val reply = Parcel.obtain()
        Parcel.resetStats()
        interceptor.transact(2, data, reply, 0)

        assertEquals(2, Parcel.obtainCount.get())
    }

    @Test
    fun testOnInterceptorReplaced() {
        val replaced = java.util.concurrent.atomic.AtomicBoolean(false)
        val interceptor =
            object : BinderInterceptor() {
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

    @Test
    fun testRuntimeControlTransactions() {
        val seenCodes = mutableListOf<Int>()
        val control =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    seenCodes += code
                    reply?.writeInt(0)
                    return true
                }
            }
        val interceptor = BinderInterceptor()

        assertTrue(BinderInterceptor.unregisterBinderInterceptor(control, Binder(), interceptor))
        assertTrue(BinderInterceptor.parkBinderHook(control))
        assertTrue(BinderInterceptor.clearAndParkBinderHook(control))
        assertEquals(listOf(2, 3, 4), seenCodes)
    }
}
