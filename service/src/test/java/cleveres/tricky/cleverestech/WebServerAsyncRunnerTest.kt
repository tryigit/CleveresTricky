package cleveres.tricky.cleverestech

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebServerAsyncRunnerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private var server: WebServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    @Test
    fun `web server installs bounded async runner`() {
        server = WebServer(0, tempFolder.newFolder("config"))

        val field = NanoHTTPD::class.java.getDeclaredField("asyncRunner").apply { isAccessible = true }
        val runner = field.get(server)

        assertTrue(runner is BoundedHttpAsyncRunner)
        assertEquals(MAX_HTTP_WORKERS, (runner as BoundedHttpAsyncRunner).workerCountForTest())
        assertEquals(MAX_HTTP_QUEUE_CAPACITY, runner.queueCapacityForTest())
    }

    @Test
    fun `accepted idle sockets stay within bounded client cap`() {
        server = WebServer(0, tempFolder.newFolder("config"))
        server!!.start()
        val sockets = ArrayList<Socket>()
        try {
            repeat(MAX_HTTP_WORKERS + MAX_HTTP_QUEUE_CAPACITY + 8) {
                runCatching {
                    sockets += Socket().apply {
                        connect(java.net.InetSocketAddress("127.0.0.1", server!!.listeningPort), 1_000)
                    }
                }
            }

            val field = NanoHTTPD::class.java.getDeclaredField("asyncRunner").apply { isAccessible = true }
            val runner = field.get(server) as BoundedHttpAsyncRunner
            repeat(20) {
                if (runner.runningCountForTest() >= MAX_HTTP_WORKERS + MAX_HTTP_QUEUE_CAPACITY) return@repeat
                Thread.sleep(10)
            }
            assertTrue(runner.runningCountForTest() <= MAX_HTTP_WORKERS + MAX_HTTP_QUEUE_CAPACITY)
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `queue full closes new client without growing retained handlers`() {
        server = WebServer(0, tempFolder.newFolder("config"))
        val runner = BoundedHttpAsyncRunner(workerCount = 2, queueCapacity = 1)
        val owner = HandlerOwner()
        val release = CountDownLatch(1)
        val started = CountDownLatch(2)
        val handlers = arrayOfNulls<NanoHTTPD.ClientHandler>(4)

        for (index in handlers.indices) {
            lateinit var handler: NanoHTTPD.ClientHandler
            handler = owner.newBlockingHandler(index < 3, started, release) { runner.closed(handler) }
            handlers[index] = handler
        }
        val concreteHandlers = handlers.requireNoNulls()

        concreteHandlers.take(3).forEach(runner::exec)

        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertEquals(3, runner.runningCountForTest())

        runner.exec(concreteHandlers[3])

        assertTrue(owner.closedHandlers.await(1, TimeUnit.SECONDS))
        assertEquals(3, runner.runningCountForTest())

        runner.closeAll()
        concreteHandlers.forEach { handler ->
            assertTrue(owner.closedSignals[handler]!!.await(1, TimeUnit.SECONDS))
        }
        assertEquals(0, runner.runningCountForTest())
    }

    private class HandlerOwner : NanoHTTPD("127.0.0.1", 0) {
        val closedHandlers = CountDownLatch(1)
        val closedSignals = mutableMapOf<NanoHTTPD.ClientHandler, CountDownLatch>()

        override fun serve(session: IHTTPSession): Response =
            newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")

        fun newBlockingHandler(
            releaseOnClose: Boolean,
            started: CountDownLatch,
            release: CountDownLatch,
            onClosed: () -> Unit,
        ): NanoHTTPD.ClientHandler {
            val signal = CountDownLatch(1)
            val handler = BlockingClientHandler(ByteArrayInputStream(ByteArray(0)), Socket(), releaseOnClose, started, release, onClosed, signal)
            closedSignals[handler] = signal
            return handler
        }

        private inner class BlockingClientHandler(
            input: ByteArrayInputStream,
            socket: Socket,
            private val releaseOnClose: Boolean,
            private val started: CountDownLatch,
            private val release: CountDownLatch,
            private val onClosed: () -> Unit,
            private val closedSignal: CountDownLatch,
        ) : ClientHandler(input, socket) {
            override fun run() {
                try {
                    started.countDown()
                    try {
                        release.await()
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                } finally {
                    onClosed()
                    closedSignal.countDown()
                }
            }

            override fun close() {
                closedHandlers.countDown()
                if (releaseOnClose) release.countDown()
                closedSignal.countDown()
                super.close()
            }
        }
    }
}
