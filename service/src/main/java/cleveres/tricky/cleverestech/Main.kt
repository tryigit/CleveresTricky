package cleveres.tricky.cleverestech

import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Logger.i("Welcome to TrickyStore!")
    Verification.check()

    // Start Web Server
    try {
        val configDir = File("/data/adb/cleverestricky")
        val server = WebServer(0, configDir) // Random port
        server.start()
        val port = server.listeningPort
        val token = server.token
        Logger.i("Web server started on port $port")
        val portFile = File(configDir, "web_port")
        if (!configDir.exists()) configDir.mkdirs()
        portFile.writeText("$port|$token")
        portFile.setReadable(false, false) // Clear all
        portFile.setReadable(true, true) // Owner only (0600)
    } catch (e: Exception) {
        Logger.e("Failed to start web server", e)
    }

    while (true) {
        if (!KeystoreInterceptor.tryRunKeystoreInterceptor()) {
            Thread.sleep(1000)
            continue
        }
        Config.initialize()
        while (true) {
            Thread.sleep(1000000)
        }
    }
}
