@file:Suppress("ktlint:standard:function-naming")

package cleveres.tricky.encryptor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow

private const val MAX_LOCAL_KEYBOX_FILES = 256
private const val MAX_XML_BYTES = 10 * 1024 * 1024

private val WebUiBackground = Color(0xFF0A0A0B)
private val WebUiForeground = Color(0xFFF4F4F5)
private val WebUiMuted = Color(0xFF9CA3AF)
private val WebUiAccent = Color(0xFFE7E5E4)
private val WebUiPanel = Color(0xFF151516)
private val WebUiPanelElevated = Color(0xFF1C1C1E)
private val WebUiBorder = Color(0xFF303033)
private val WebUiInputBackground = Color(0xFF111113)
private val WebUiSuccess = Color(0xFF4ADE80)
private val WebUiDanger = Color(0xFFFB7185)

@Composable
fun WebUiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                background = WebUiBackground,
                surface = WebUiPanel,
                onBackground = WebUiForeground,
                onSurface = WebUiForeground,
                primary = WebUiAccent,
                onPrimary = WebUiBackground,
                secondary = WebUiAccent,
                outline = WebUiBorder,
                error = WebUiDanger,
                surfaceVariant = WebUiInputBackground,
                onSurfaceVariant = WebUiForeground,
            ),
        content = content,
    )
}

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
                        snackbarHostState = snackbarHostState,
                        coroutineScope = coroutineScope,
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
fun KeyboxListScreen(
    onNavigateToCreate: () -> Unit,
    snackbarHostState: SnackbarHostState,
    coroutineScope: CoroutineScope,
) {
    val context = LocalContext.current
    var keyboxFiles by remember { mutableStateOf<List<File>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refreshToken by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var fileToDelete by remember { mutableStateOf<File?>(null) }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri: Uri? ->
            val file = pendingExport
            pendingExport = null
            if (uri == null || file == null) return@rememberLauncherForActivityResult
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        require(
                            file.isFile &&
                                !Files.isSymbolicLink(file.toPath()),
                        ) { "The selected keybox is no longer available" }
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            file.inputStream().buffered().use { input ->
                                input.copyTo(output)
                            }
                        } ?: throw IOException("Cannot open destination")
                    }
                }.onSuccess {
                    snackbarHostState.showSnackbar("Keybox exported")
                }.onFailure { error ->
                    snackbarHostState.showSnackbar("Export failed: ${error.message ?: "Unknown error"}")
                }
            }
        }

    LaunchedEffect(refreshToken) {
        loading = true
        keyboxFiles = loadKeyboxFiles(context.getExternalFilesDir(null))
        loading = false
    }

    val visibleFiles =
        remember(keyboxFiles, query) {
            if (query.isBlank()) {
                keyboxFiles
            } else {
                keyboxFiles.filter { it.name.contains(query.trim(), ignoreCase = true) }
            }
        }

    val totalBytes = remember(keyboxFiles) { keyboxFiles.sumOf { it.length().coerceAtLeast(0L) } }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToCreate,
                containerColor = WebUiAccent,
                contentColor = WebUiBackground,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create encrypted keybox")
            }
        },
        containerColor = WebUiBackground,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Cleveres Vault",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Encrypted keyboxes, kept local.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WebUiMuted,
                    )
                }
                IconButton(
                    onClick = { refreshToken++ },
                    enabled = !loading,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh vault")
                }
            }

            VaultSummary(
                fileCount = keyboxFiles.size,
                totalBytes = totalBytes,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(120) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = { Text("Search vault") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
                colors = vaultTextFieldColors(),
            )

            when {
                loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = WebUiAccent)
                    }
                }

                keyboxFiles.isEmpty() -> {
                    EmptyVault(onCreate = onNavigateToCreate)
                }

                visibleFiles.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Nothing matches “$query”.", fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text("Try another filename.", color = WebUiMuted)
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = visibleFiles,
                            key = { it.absolutePath },
                        ) { file ->
                            KeyboxItem(
                                file = file,
                                onExport = {
                                    pendingExport = file
                                    exportLauncher.launch(file.name)
                                },
                                onDelete = { fileToDelete = file },
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(88.dp))
                        }
                    }
                }
            }
        }
    }

    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete keybox?") },
            text = {
                Text(
                    "“${file.name}” will be permanently removed from this device. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        fileToDelete = null
                        coroutineScope.launch {
                            val deleted =
                                withContext(Dispatchers.IO) {
                                    file.isFile &&
                                        !Files.isSymbolicLink(file.toPath()) &&
                                        file.delete()
                                }
                            snackbarHostState.showSnackbar(
                                if (deleted) "Keybox deleted" else "Could not delete keybox",
                            )
                            if (deleted) refreshToken++
                        }
                    },
                ) {
                    Text("Delete", color = WebUiDanger)
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            },
            containerColor = WebUiPanelElevated,
        )
    }
}

@Composable
private fun VaultSummary(
    fileCount: Int,
    totalBytes: Long,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WebUiPanel),
        border = BorderStroke(1.dp, WebUiBorder),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = WebUiPanelElevated,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                    tint = WebUiSuccess,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Local vault", fontWeight = FontWeight.SemiBold)
                Text(
                    "$fileCount ${if (fileCount == 1) "keybox" else "keyboxes"} · ${formatBytes(totalBytes)}",
                    color = WebUiMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "ON DEVICE",
                color = WebUiSuccess,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyVault(onCreate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 28.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = WebUiPanel,
                border = BorderStroke(1.dp, WebUiBorder),
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    modifier = Modifier.padding(18.dp).size(30.dp),
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                "Your vault is empty",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Import a keybox XML, protect it with a password, and keep the encrypted result locally.",
                color = WebUiMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
                onClick = onCreate,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = WebUiAccent,
                        contentColor = WebUiBackground,
                    ),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Create keybox", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun KeyboxItem(
    file: File,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WebUiPanel),
        border = BorderStroke(1.dp, WebUiBorder),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(13.dp),
                color = WebUiPanelElevated,
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp).size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(
                    text = file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${formatBytes(file.length())} · ${formatTimestamp(file.lastModified())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = WebUiMuted,
                )
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Default.UploadFile, contentDescription = "Export ${file.name}")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ${file.name}",
                    tint = WebUiDanger,
                )
            }
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
    var xmlSize by remember { mutableStateOf<Long?>(null) }
    var publicKey by remember { mutableStateOf("Generating…") }
    var publicKeyReady by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var overwriteTarget by remember { mutableStateOf<File?>(null) }

    val authorError =
        when {
            author.isBlank() -> "Author or credit link is required"
            author.length > 1024 -> "Maximum length is 1024 characters"
            else -> null
        }
    val passwordError =
        when {
            password.isEmpty() -> "Use at least 12 characters"
            password.length < 12 -> "${12 - password.length} more characters needed"
            password.length > 1024 -> "Maximum length is 1024 characters"
            else -> null
        }
    val confirmError =
        when {
            confirmPassword.isEmpty() -> "Repeat your password"
            password != confirmPassword -> "Passwords do not match"
            else -> null
        }
    val canSave =
        authorError == null &&
            passwordError == null &&
            confirmError == null &&
            xmlContent != null &&
            !saving

    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                CryptoUtils.generateSigningKey()
                CryptoUtils.getPublicKeyBase64()
            }
        }.onSuccess { key ->
            publicKey =
                if (key.isNullOrBlank()) {
                    "Signing key unavailable"
                } else {
                    key
                }
            publicKeyReady = !key.isNullOrBlank()
        }.onFailure {
            publicKey = "Signing key unavailable"
            publicKeyReady = false
        }
    }

    val pickXmlLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            xmlContent = null
            xmlFilename = null
            xmlSize = null
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val bytes = readBytes(stream)
                            try {
                                val decoded = decodeUtf8Strict(bytes)
                                require(decoded.isNotBlank()) { "XML file is empty" }
                                require(decoded.trimStart().startsWith("<")) {
                                    "Selected file does not look like XML"
                                }
                                Triple(
                                    decoded,
                                    queryDisplayName(context, uri) ?: "keybox.xml",
                                    bytes.size.toLong(),
                                )
                            } finally {
                                bytes.fill(0)
                            }
                        } ?: throw IOException("Cannot open selected file")
                    }
                }.onSuccess { result ->
                    xmlContent = result.first
                    xmlFilename = result.second
                    xmlSize = result.third
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(
                        "Could not use XML: ${error.message ?: "Unknown error"}",
                    )
                }
            }
        }

    fun saveCurrentKeybox(replaceExisting: Boolean) {
        val selectedXml = xmlContent ?: return
        if (!canSave && !replaceExisting) return
        val selectedAuthor = author
        val selectedPassword = password
        saving = true
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    saveCboxAtomically(
                        directory = context.getExternalFilesDir(null),
                        author = selectedAuthor,
                        xml = selectedXml,
                        password = selectedPassword,
                        replaceExisting = replaceExisting,
                    )
                }
            }.onSuccess { file ->
                password = ""
                confirmPassword = ""
                xmlContent = null
                xmlFilename = null
                xmlSize = null
                saving = false
                overwriteTarget = null
                snackbarHostState.showSnackbar("Encrypted ${file.name}")
                onNavigateBack()
            }.onFailure { error ->
                saving = false
                if (error is java.nio.file.FileAlreadyExistsException) {
                    overwriteTarget = buildDestinationFile(context.getExternalFilesDir(null), selectedAuthor)
                } else {
                    snackbarHostState.showSnackbar(
                        "Encryption failed: ${error.message ?: "Unknown error"}",
                    )
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Create keybox", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Three checks, then encrypt.",
                            style = MaterialTheme.typography.labelMedium,
                            color = WebUiMuted,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        enabled = !saving,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = WebUiBackground,
                        titleContentColor = WebUiForeground,
                    ),
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
                    .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SecurityBanner()

            SectionCard(
                step = "01",
                title = "Identity",
                subtitle = "Name the encrypted keybox and keep your signing identity visible.",
                completed = authorError == null && publicKeyReady,
            ) {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.take(1024) },
                    label = { Text("Author / credit link") },
                    supportingText = {
                        Text(
                            authorError ?: "Also used to create the local .cbox filename.",
                        )
                    },
                    isError = author.isNotEmpty() && authorError != null,
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = vaultTextFieldColors(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Signing public key",
                    style = MaterialTheme.typography.labelLarge,
                    color = WebUiMuted,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    color = WebUiInputBackground,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, WebUiBorder),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = publicKey,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (publicKeyReady) WebUiForeground else WebUiMuted,
                        )
                        IconButton(
                            onClick = {
                                val clipboard =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Cleveres signing public key", publicKey),
                                )
                                coroutineScope.launch {
                                    snackbarHostState.showSnackbar("Public key copied")
                                }
                            },
                            enabled = publicKeyReady,
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy public key")
                        }
                    }
                }
            }

            SectionCard(
                step = "02",
                title = "Keybox XML",
                subtitle = "Select the source file. It stays in memory only for this encryption flow.",
                completed = xmlContent != null,
            ) {
                if (xmlContent == null) {
                    OutlinedButton(
                        onClick = {
                            pickXmlLauncher.launch(
                                arrayOf(
                                    "text/xml",
                                    "application/xml",
                                    "text/plain",
                                ),
                            )
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        border = BorderStroke(1.dp, WebUiBorder),
                    ) {
                        Icon(Icons.Default.FileOpen, contentDescription = null)
                        Text("Choose XML file", modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "UTF-8 XML · up to 10 MiB",
                        style = MaterialTheme.typography.bodySmall,
                        color = WebUiMuted,
                    )
                } else {
                    Surface(
                        color = WebUiInputBackground,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, WebUiBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = WebUiSuccess,
                            )
                            Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(
                                    xmlFilename ?: "keybox.xml",
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${formatBytes(xmlSize ?: 0L)} · UTF-8 ready",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = WebUiMuted,
                                )
                            }
                            TextButton(
                                onClick = {
                                    xmlContent = null
                                    xmlFilename = null
                                    xmlSize = null
                                },
                                enabled = !saving,
                            ) {
                                Text("Change")
                            }
                        }
                    }
                }
            }

            SectionCard(
                step = "03",
                title = "Protection",
                subtitle = "Use a unique password. Cleveres does not store or recover it.",
                completed = passwordError == null && confirmError == null,
            ) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(1024) },
                    label = { Text("Password") },
                    supportingText = {
                        Text(passwordError ?: passwordStrengthLabel(password))
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    visualTransformation =
                        if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    singleLine = true,
                    enabled = !saving,
                    isError = password.isNotEmpty() && passwordError != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = vaultTextFieldColors(),
                )
                PasswordStrength(password)

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it.take(1024) },
                    label = { Text("Confirm password") },
                    supportingText = { Text(confirmError ?: "Passwords match") },
                    visualTransformation =
                        if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    singleLine = true,
                    enabled = !saving,
                    isError = confirmPassword.isNotEmpty() && confirmError != null,
                    trailingIcon = {
                        if (confirmPassword.isNotEmpty() && confirmError == null) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Passwords match",
                                tint = WebUiSuccess,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = vaultTextFieldColors(),
                )
            }

            Button(
                onClick = { saveCurrentKeybox(replaceExisting = false) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = canSave,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = WebUiAccent,
                        contentColor = WebUiBackground,
                        disabledContainerColor = WebUiPanelElevated,
                        disabledContentColor = WebUiMuted,
                    ),
                shape = RoundedCornerShape(16.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = WebUiBackground,
                    )
                    Text("Encrypting…", modifier = Modifier.padding(start = 10.dp))
                } else {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Text("Encrypt & save", modifier = Modifier.padding(start = 8.dp))
                }
            }

            Text(
                "AES-GCM encryption · PBKDF2-HMAC-SHA256 · signed before encryption",
                style = MaterialTheme.typography.labelSmall,
                color = WebUiMuted,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Spacer(modifier = Modifier.height(26.dp))
        }
    }

    overwriteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!saving) overwriteTarget = null },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("Replace existing keybox?") },
            text = {
                Text(
                    "A keybox named “${target.name}” already exists. " +
                        "Replacing it permanently overwrites the encrypted file.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        overwriteTarget = null
                        saveCurrentKeybox(replaceExisting = true)
                    },
                    enabled = !saving,
                ) {
                    Text("Replace", color = WebUiDanger)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { overwriteTarget = null },
                    enabled = !saving,
                ) {
                    Text("Cancel")
                }
            },
            containerColor = WebUiPanelElevated,
        )
    }
}

@Composable
private fun SecurityBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = WebUiPanel),
        border = BorderStroke(1.dp, WebUiBorder),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = WebUiSuccess.copy(alpha = 0.12f),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    tint = WebUiSuccess,
                    modifier = Modifier.padding(9.dp).size(20.dp),
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text("Local-first encryption", fontWeight = FontWeight.SemiBold)
                Text(
                    "The source XML is encrypted on this device and is never uploaded by this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = WebUiMuted,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    step: String,
    title: String,
    subtitle: String,
    completed: Boolean,
    content: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WebUiPanel),
        border =
            BorderStroke(
                1.dp,
                if (completed) WebUiSuccess.copy(alpha = 0.55f) else WebUiBorder,
            ),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    color = if (completed) WebUiSuccess.copy(alpha = 0.12f) else WebUiPanelElevated,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        step,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (completed) WebUiSuccess else WebUiMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = WebUiMuted,
                    )
                }
                if (completed) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Complete",
                        tint = WebUiSuccess,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = WebUiBorder,
            )
            content()
        }
    }
}

@Composable
private fun PasswordStrength(password: String) {
    val score = passwordStrengthScore(password)
    val progress = score / 4f
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp),
            color = if (score >= 3) WebUiSuccess else WebUiAccent,
            trackColor = WebUiPanelElevated,
        )
    }
}

@Composable
private fun vaultTextFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = WebUiInputBackground,
        unfocusedContainerColor = WebUiInputBackground,
        disabledContainerColor = WebUiInputBackground,
        focusedBorderColor = WebUiAccent,
        unfocusedBorderColor = WebUiBorder,
        focusedTextColor = WebUiForeground,
        unfocusedTextColor = WebUiForeground,
        focusedLabelColor = WebUiAccent,
        unfocusedLabelColor = WebUiMuted,
        focusedSupportingTextColor = WebUiMuted,
        unfocusedSupportingTextColor = WebUiMuted,
    )

private suspend fun loadKeyboxFiles(directory: File?): List<File> =
    withContext(Dispatchers.IO) {
        if (directory == null || !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return@withContext emptyList()
        }
        val files = ArrayList<File>(MAX_LOCAL_KEYBOX_FILES)
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            for (entry in entries) {
                if (!entry.fileName.toString().endsWith(".cbox", ignoreCase = true) ||
                    !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                ) {
                    continue
                }
                if (files.size == MAX_LOCAL_KEYBOX_FILES) break
                files.add(entry.toFile())
            }
        }
        files.sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenBy { it.name.lowercase() },
        )
    }

private fun queryDisplayName(
    context: Context,
    uri: Uri,
): String? {
    context.contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)
        }
    }
    return null
}

private fun decodeUtf8Strict(bytes: ByteArray): String =
    StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

private fun passwordStrengthScore(password: String): Int {
    if (password.isEmpty()) return 0
    var score = 0
    if (password.length >= 12) score++
    if (password.length >= 16) score++
    val classes =
        listOf(
            password.any(Char::isLowerCase),
            password.any(Char::isUpperCase),
            password.any(Char::isDigit),
            password.any { !it.isLetterOrDigit() },
        ).count { it }
    if (classes >= 3) score++
    if (classes == 4 && password.length >= 20) score++
    return score.coerceIn(0, 4)
}

private fun passwordStrengthLabel(password: String): String =
    when (passwordStrengthScore(password)) {
        0, 1 -> "Password strength: basic"
        2 -> "Password strength: good"
        3 -> "Password strength: strong"
        else -> "Password strength: excellent"
    }

private fun buildDestinationFile(
    directory: File?,
    author: String,
): File {
    require(directory != null && (directory.isDirectory || directory.mkdirs())) {
        "Application storage is unavailable"
    }
    val safeBase = author.replace(Regex("[^a-zA-Z0-9._-]"), "_").trim('.').take(100)
    return File(directory, "${safeBase.ifEmpty { "keybox" }}.cbox")
}

private fun saveCboxAtomically(
    directory: File?,
    author: String,
    xml: String,
    password: String,
    replaceExisting: Boolean,
): File {
    val destination = buildDestinationFile(directory, author)
    require(!Files.isSymbolicLink(destination.toPath())) { "Refusing symbolic-link output" }

    if (!replaceExisting && destination.exists()) {
        throw java.nio.file.FileAlreadyExistsException(destination.absolutePath)
    }

    val parent = destination.parentFile ?: throw IOException("Application storage is unavailable")
    val temporary = File.createTempFile(".cbox-", ".tmp", parent)
    try {
        temporary.outputStream().buffered().use { stream ->
            CryptoUtils.encryptAndWriteCbox(stream, xml, author, password)
        }

        val moveOptions =
            if (replaceExisting) {
                arrayOf(StandardCopyOption.REPLACE_EXISTING)
            } else {
                emptyArray()
            }

        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                *moveOptions,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                *moveOptions,
            )
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

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val unit = 1024.0
    val exponent = (ln(bytes.toDouble()) / ln(unit)).toInt().coerceIn(1, 4)
    val value = bytes / unit.pow(exponent.toDouble())
    val suffix = arrayOf("B", "KiB", "MiB", "GiB", "TiB")[exponent]
    return if (value >= 10) {
        String.format("%.0f %s", value, suffix)
    } else {
        String.format("%.1f %s", value, suffix)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "Unknown date"
    return DateFormat
        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestamp))
}
