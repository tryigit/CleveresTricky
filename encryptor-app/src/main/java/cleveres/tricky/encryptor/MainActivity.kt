package cleveres.tricky.encryptor

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent() {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var publicKeyBase64 by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("t.me/cleverestech") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf("") }

    val keyStoreAlias = "cleveres_encryptor_signing_key"

    LaunchedEffect(Unit) {
        publicKeyBase64 = getPublicKey(keyStoreAlias) ?: ""
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = uri.path?.substringAfterLast("/") ?: "keybox.xml"
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null && selectedFileUri != null) {
            try {
                val xmlContent = context.contentResolver.openInputStream(selectedFileUri!!)?.use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                } ?: ""

                if (xmlContent.isBlank()) {
                    Toast.makeText(context, "File is empty", Toast.LENGTH_SHORT).show()
                    return@rememberLauncherForActivityResult
                }

                val encryptedBytes = encryptAndSign(
                    alias = keyStoreAlias,
                    author = author,
                    xmlContent = xmlContent,
                    password = password
                )

                if (encryptedBytes != null) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(encryptedBytes)
                    }
                    Toast.makeText(context, "Saved successfully!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Encryption failed", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                e.printStackTrace()
            }
        }
    }

    Column(
        modifier = Modifier
            .padding(16.dp)
            .verticalScroll(scrollState)
    ) {
        Text("Cleveres Encryptor", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(20.dp))

        // Key Management
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Signing Key", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                if (publicKeyBase64.isEmpty()) {
                    Button(onClick = {
                        generateKey(keyStoreAlias)
                        publicKeyBase64 = getPublicKey(keyStoreAlias) ?: ""
                    }) {
                        Text("Generate Signing Key")
                    }
                } else {
                    OutlinedTextField(
                        value = publicKeyBase64,
                        onValueChange = {},
                        label = { Text("Public Key (Share this)") },
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Public Key", publicKeyBase64)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy Public Key")
                    }
                }
                Text("⚠️ Private key stays on this device only.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Encryption
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Encrypt Keybox", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = { filePicker.launch("text/xml") }) {
                    Text(if (selectedFileUri == null) "Select keybox.xml" else "Selected: $selectedFileName")
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author / Credit") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("Confirm Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (selectedFileUri == null) {
                            Toast.makeText(context, "Select a file", Toast.LENGTH_SHORT).show()
                        } else if (password != confirmPassword) {
                            Toast.makeText(context, "Passwords do not match", Toast.LENGTH_SHORT).show()
                        } else if (password.isEmpty()) {
                            Toast.makeText(context, "Enter a password", Toast.LENGTH_SHORT).show()
                        } else if (publicKeyBase64.isEmpty()) {
                            Toast.makeText(context, "Generate a key first", Toast.LENGTH_SHORT).show()
                        } else {
                            val sanitizedAuthor = author.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                            saveLauncher.launch("$sanitizedAuthor.cbox")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Encrypt & Sign")
                }
            }
        }
    }
}

fun getPublicKey(alias: String): String? {
    try {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val entry = ks.getEntry(alias, null)
        if (entry is KeyStore.PrivateKeyEntry) {
            return Base64.encodeToString(entry.certificate.publicKey.encoded, Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return null
}

fun generateKey(alias: String) {
    val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
    val spec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )
        .setDigests(KeyProperties.DIGEST_SHA256)
        .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
        .setKeySize(2048)
        .build()
    kpg.initialize(spec)
    kpg.generateKeyPair()
}

fun encryptAndSign(alias: String, author: String, xmlContent: String, password: String): ByteArray? {
    try {
        // 1. Sign
        val signatureInput = author + xmlContent
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(entry.privateKey)
        signer.update(signatureInput.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)

        // 2. Build JSON
        val json = JSONObject()
        json.put("author", author)
        json.put("signature", signature)
        json.put("xml_content", xmlContent)
        val plaintext = json.toString().toByteArray(StandardCharsets.UTF_8)

        // 3. Encrypt
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 250000, 256)
        val secretKey = factory.generateSecret(spec)
        val key = SecretKeySpec(secretKey.encoded, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)

        // 4. Output Format
        // [4 bytes: "CBOX"] [4 bytes: version=1] [16 bytes: salt] [12 bytes: IV] [ciphertext + tag]
        val output = ByteArrayOutputStream()
        output.write("CBOX".toByteArray(StandardCharsets.US_ASCII))
        output.write(byteArrayOf(0, 0, 0, 1)) // Version 1 (Big Endian)
        output.write(salt)
        output.write(iv)
        output.write(ciphertext)

        return output.toByteArray()
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }
}
