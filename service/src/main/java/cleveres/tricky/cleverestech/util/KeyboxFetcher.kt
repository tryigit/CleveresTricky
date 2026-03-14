package cleveres.tricky.cleverestech.util

import cleveres.tricky.cleverestech.Config
import cleveres.tricky.cleverestech.Logger
import cleveres.tricky.cleverestech.keystore.CertHack
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class KeyboxFetcher(private val networkClient: NetworkClient = DefaultNetworkClient()) {

    interface NetworkClient {
        fun fetch(url: String): String?
    }

    class DefaultNetworkClient : NetworkClient {

        override fun fetch(url: String): String? {
            return try {
                val urlObj = URL(url)
                val conn = urlObj.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    val length = conn.contentLength
                    if (length > MAX_FILE_SIZE) {
                        Logger.e("Fetcher: File too large ($length bytes)")
                        return null
                    }

                    val bytes = conn.inputStream.readBytes()
                    if (bytes.size > MAX_FILE_SIZE) {
                        Logger.e("Fetcher: File exceeds size limit")
                        return null
                    }
                    String(bytes, Charsets.UTF_8)
                } else {
                    Logger.e("Fetcher: Failed to fetch $url: ${conn.responseCode}")
                    null
                }
            } catch (e: Exception) {
                Logger.e("Fetcher: Exception fetching $url", e)
                null
            }
        }
    }

    fun harvest(
        sourceUrl: String? = Config.keyboxSourceUrl,
        outputDir: File = Config.keyboxDirectory
    ) {
        if (sourceUrl.isNullOrBlank()) {
            Logger.i("Fetcher: No source URL configured.")
            return
        }

        Logger.i("Fetcher: Starting harvest from $sourceUrl")

        try {
            val content = networkClient.fetch(sourceUrl) ?: return
            val keyboxes = ArrayList<CertHack.KeyBox>()

            if (content.trimStart().startsWith("<")) {
                keyboxes.addAll(CertHack.parseKeyboxXml(content.reader(), "harvested_direct.xml"))
            } else {
                content.lines().forEach { line ->
                    if (line.isNotBlank() && !line.startsWith("#")) {
                        val url = line.trim()
                        val xml = networkClient.fetch(url)
                        if (xml != null) {
                            keyboxes.addAll(CertHack.parseKeyboxXml(xml.reader(), "harvested_${url.hashCode()}.xml"))
                        }
                    }
                }
            }

            if (keyboxes.isEmpty()) {
                Logger.i("Fetcher: No keyboxes found.")
                return
            }

            val crl = KeyboxVerifier.fetchCrl()
            if (crl == null) {
                Logger.e("Fetcher: Failed to fetch CRL, aborting verification.")
                return
            }

            var added = 0
            val digest = MessageDigest.getInstance("SHA-256")
            for (kb in keyboxes) {
                if (KeyboxVerifier.verifyKeybox(kb, crl) == KeyboxVerifier.Status.VALID) {
                    saveKeybox(kb, outputDir, digest)
                    added++
                }
            }
            Logger.i("Fetcher: Finished. Added/Updated $added valid keyboxes.")

        } catch (e: Exception) {
            Logger.e("Fetcher: Error during harvest", e)
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private val hexFormat = HexFormat { upperCase = false }

    @OptIn(ExperimentalStdlibApi::class)
    private fun saveKeybox(kb: CertHack.KeyBox, dir: File, digest: MessageDigest) {
        try {
            val pubEncoded = kb.keyPair.public.encoded
            val hash = digest.digest(pubEncoded)
            val hashStr = hash.toHexString(hexFormat).substring(0, 16)
            val fileName = "harvested_$hashStr.xml"
            val file = File(dir, fileName)

            if (file.exists()) return

            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\"?>\n")
            sb.append("<AndroidAttestation>\n")
            sb.append("    <NumberOfKeyboxes>1</NumberOfKeyboxes>\n")
            sb.append("    <Keybox DeviceID=\"Harvested\">\n")

            val algo = kb.keyPair.public.algorithm
            val algoStr = if (algo == "EC" || algo == "ECDSA") "ecdsa" else "rsa"

            sb.append("        <Key algorithm=\"$algoStr\">\n")
            sb.append("            <PrivateKey format=\"pem\">\n")
            sb.append(getPem(kb.keyPair.private))
            sb.append("            </PrivateKey>\n")

            sb.append("            <CertificateChain>\n")
            sb.append("                <NumberOfCertificates>${kb.certificates.size}</NumberOfCertificates>\n")
            for (cert in kb.certificates) {
                sb.append("                <Certificate format=\"pem\">\n")
                sb.append(getPem(cert))
                sb.append("                </Certificate>\n")
            }
            sb.append("            </CertificateChain>\n")
            sb.append("        </Key>\n")
            sb.append("    </Keybox>\n")
            sb.append("</AndroidAttestation>\n")

            SecureFile.writeText(file, sb.toString())
            Logger.i("Fetcher: Saved new keybox $fileName")
        } catch (e: Exception) {
            Logger.e("Fetcher: Failed to save keybox", e)
        }
    }

    private fun getPem(key: PrivateKey): String {
        val encoded = Base64.getEncoder().encodeToString(key.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----"
    }

    private fun getPem(cert: Certificate): String {
        val encoded = Base64.getEncoder().encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$encoded\n-----END CERTIFICATE-----"
    }

    companion object {
        private const val MAX_FILE_SIZE = 10 * 1024 * 1024 // 10MB
        private val executor = Executors.newSingleThreadScheduledExecutor()

        fun schedule() {
            executor.scheduleAtFixedRate({
                KeyboxFetcher().harvest()
            }, 5, 360, TimeUnit.MINUTES)
        }
    }
}
