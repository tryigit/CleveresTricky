@file:Suppress("ktlint:standard:function-naming")

package cleveres.tricky.encryptor

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

// --- Theme Colors ---
val WebUiBackground = Color(0xFF0B0B0C)
val WebUiForeground = Color(0xFFE5E7EB)
val WebUiAccent = Color(0xFFD1D5DB)
val WebUiPanel = Color(0xFF161616)
val WebUiBorder = Color(0xFF333333)
val WebUiInputBackground = Color(0xFF1A1A1A)
val WebUiSuccess = Color(0xFF34D399)
val WebUiDanger = Color(0xFFEF4444)

@Composable
fun WebUiTheme(content: @Composable () -> Unit) {
    val colorScheme =
        darkColorScheme(
            background = WebUiBackground,
            surface = WebUiPanel,
            onBackground = WebUiForeground,
            onSurface = WebUiForeground,
            primary = WebUiAccent,
            onPrimary = WebUiBackground, // Text on primary button should be dark
            secondary = WebUiAccent,
            outline = WebUiBorder,
            error = WebUiDanger,
            surfaceVariant = WebUiInputBackground,
            onSurfaceVariant = WebUiForeground,
        )
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

// --- Navigation ---
enum class Screen {
    List,
    Create,
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WebUiTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf(Screen.List) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { paddingValues ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (currentScreen) {
                Screen.List ->
                    KeyboxListScreen(
                        onNavigateToCreate = { currentScreen = Screen.Create },
                    )
                Screen.Create ->
                    CreateKeyboxScreen(
                        onNavigateBack = { currentScreen = Screen.List },
                        snackbarHostState = snackbarHostState,
                        coroutineScope = coroutineScope,
                    )
            }
        }
    }
}

@Composable
fun KeyboxListScreen(onNavigateToCreate: () -> Unit) {
    val context = LocalContext.current
    var keyboxFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(Unit) {
        val dir = context.getExternalFilesDir(null)
        keyboxFiles = dir?.listFiles { file -> file.name.endsWith(".cbox") }?.toList() ?: emptyList()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreate,
                containerColor = WebUiAccent,
                contentColor = WebUiBackground,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create New")
            }
        },
        containerColor = WebUiBackground,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
        ) {
            Text(
                "Keyboxes",
                style = MaterialTheme.typography.headlineMedium,
                color = WebUiForeground,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            if (keyboxFiles.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No keyboxes found.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(keyboxFiles) { file ->
                        KeyboxItem(file)
                    }
                }
            }
        }
    }
}

@Composable
fun KeyboxItem(file: File) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WebUiPanel),
        border = BorderStroke(1.dp, WebUiBorder),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.titleMedium,
                color = WebUiForeground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Size: ${file.length()} bytes",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateKeyboxScreen(
    onNavigateBack: () -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
) {
    val context = LocalContext.current
    var author by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var xmlContent by remember { mutableStateOf<String?>(null) }
    var xmlFilename by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf("Generating...") }

    // Load key on start
    LaunchedEffect(Unit) {
        CryptoUtils.generateSigningKey()
        val key = CryptoUtils.getPublicKeyBase64()
        if (key != null) publicKey = key else publicKey = "Error generating key"
    }

    val pickXmlLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                xmlContent = null
                xmlFilename = null
                try {
                    context.contentResolver.openInputStream(it)?.use { stream ->
                        val bytes = readBytes(stream)
                        try {
                            xmlContent = String(bytes, StandardCharsets.UTF_8)
                        } finally {
                            bytes.fill(0)
                        }
                        val cursor = context.contentResolver.query(it, null, null, null, null)
                        cursor?.use { c ->
                            if (c.moveToFirst()) {
                                val idx = c.getColumnIndex("_display_name")
                                if (idx != -1) xmlFilename = c.getString(idx)
                            }
                        }
                        if (xmlFilename == null) xmlFilename = "keybox.xml"
                    }
                } catch (e: Exception) {
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Error reading file: ${e.message}")
                    }
                }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create Keybox", color = WebUiForeground) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = WebUiForeground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WebUiBackground),
            )
        },
        containerColor = WebUiBackground,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Public Key Section
            OutlinedTextField(
                value = publicKey,
                onValueChange = {},
                label = { Text("Your Public Key") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WebUiInputBackground,
                        unfocusedContainerColor = WebUiInputBackground,
                        focusedBorderColor = WebUiAccent,
                        unfocusedBorderColor = WebUiBorder,
                        focusedTextColor = WebUiForeground,
                        unfocusedTextColor = WebUiForeground,
                        focusedLabelColor = WebUiAccent,
                        unfocusedLabelColor = Color.Gray,
                    ),
            )
            Button(
                onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("Public Key", publicKey)
                    clipboard.setPrimaryClip(clip)
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Copied to clipboard")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = WebUiPanel, contentColor = WebUiAccent),
                border = BorderStroke(1.dp, WebUiBorder),
            ) {
                Text("Copy Public Key")
            }

            HorizontalDivider(color = WebUiBorder)

            // Input Fields
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Author / Credit Link") },
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WebUiInputBackground,
                        unfocusedContainerColor = WebUiInputBackground,
                        focusedBorderColor = WebUiAccent,
                        unfocusedBorderColor = WebUiBorder,
                        focusedTextColor = WebUiForeground,
                        unfocusedTextColor = WebUiForeground,
                        focusedLabelColor = WebUiAccent,
                        unfocusedLabelColor = Color.Gray,
                    ),
            )

            Button(
                onClick = { pickXmlLauncher.launch("text/xml") },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = WebUiPanel, contentColor = WebUiForeground),
                border = BorderStroke(1.dp, WebUiBorder),
            ) {
                Text(if (xmlFilename == null) "Select Keybox XML" else "Selected: $xmlFilename")
            }

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WebUiInputBackground,
                        unfocusedContainerColor = WebUiInputBackground,
                        focusedBorderColor = WebUiAccent,
                        unfocusedBorderColor = WebUiBorder,
                        focusedTextColor = WebUiForeground,
                        unfocusedTextColor = WebUiForeground,
                        focusedLabelColor = WebUiAccent,
                        unfocusedLabelColor = Color.Gray,
                    ),
            )

            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                label = { Text("Confirm Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = WebUiInputBackground,
                        unfocusedContainerColor = WebUiInputBackground,
                        focusedBorderColor = WebUiAccent,
                        unfocusedBorderColor = WebUiBorder,
                        focusedTextColor = WebUiForeground,
                        unfocusedTextColor = WebUiForeground,
                        focusedLabelColor = WebUiAccent,
                        unfocusedLabelColor = Color.Gray,
                    ),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (author.isBlank()) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Author required")
                        }
                        return@Button
                    }
                    if (author.length > 1024) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Author is too long")
                        }
                        return@Button
                    }
                    if (password.length !in 12..1024 || password != confirmPassword) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Use a matching password of at least 12 characters")
                        }
                        return@Button
                    }
                    if (xmlContent == null) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("Select an XML file")
                        }
                        return@Button
                    }

                    val selectedAuthor = author
                    val selectedPassword = password
                    val selectedXml = xmlContent ?: return@Button
                    coroutineScope.launch {
                        try {
                            val file =
                                withContext(Dispatchers.IO) {
                                    saveCboxAtomically(context.getExternalFilesDir(null), selectedAuthor, selectedXml, selectedPassword)
                                }
                            password = ""
                            confirmPassword = ""
                            xmlContent = null
                            snackbarHostState.showSnackbar("Saved to ${file.absolutePath}")
                            onNavigateBack()
                        } catch (e: Exception) {
                            snackbarHostState.showSnackbar("Error saving: ${e.message}")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WebUiAccent, contentColor = WebUiBackground),
                enabled = xmlContent != null,
            ) {
                Text("Encrypt & Save")
            }
        }
    }
}

private fun saveCboxAtomically(
    directory: File?,
    author: String,
    xml: String,
    password: String,
): File {
    require(directory != null && (directory.isDirectory || directory.mkdirs())) {
        "Application storage is unavailable"
    }
    val safeBase = author.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.').take(100)
    val filename = "${safeBase.ifEmpty { "keybox" }}.cbox"
    val destination = File(directory, filename)
    require(!Files.isSymbolicLink(destination.toPath())) { "Refusing symbolic-link output" }
    val temporary = File.createTempFile(".cbox-", ".tmp", directory)
    try {
        temporary.outputStream().buffered().use { stream ->
            CryptoUtils.encryptAndWriteCbox(stream, xml, author, password)
        }
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return destination
    } finally {
        if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit()
    }
}

fun readBytes(inputStream: InputStream): ByteArray {
    val buffer = ByteArrayOutputStream(minOf(MAX_XML_BYTES, 64 * 1024))
    val data = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    try {
        while (true) {
            val nRead = inputStream.read(data, 0, data.size)
            if (nRead < 0) break
            if (nRead == 0) continue
            if (nRead > MAX_XML_BYTES - total) throw IOException("XML file exceeds 10 MiB")
            buffer.write(data, 0, nRead)
            total += nRead
        }
        return buffer.toByteArray()
    } finally {
        data.fill(0)
    }
}

private const val MAX_XML_BYTES = 10 * 1024 * 1024
