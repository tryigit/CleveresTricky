package cleveres.tricky.cleverestech

import java.security.MessageDigest
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Logger.i("Welcome to TrickyStore!")
    Verification.check()
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
