package cleveres.tricky.encryptor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val LOG_TAG = "CleveresEncryptor"

private data class VaultItem(
    val file: File,
    val size: Long,
)

class SecureMainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleController.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = CleveresVaultColors) {
                SecureEncryptorApp()
            }
        }
    }
}

@Composable
private fun SecureEncryptorApp() {
    var creating by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        if (creating) {
            CreateScreen(onBack = { creating = false }, snackbar = snackbar)
        } else {
            VaultScreen(onCreate = { creating = true }, snackbar = snackbar)
        }
        SnackbarHost(
            hostState = snackbar,
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun VaultScreen(
    onCreate: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val exportSuccess = stringResource(R.string.export_success)
    val exportFailed = stringResource(R.string.export_failed)
    val deleted = stringResource(R.string.keybox_deleted)
    val deleteFailed = stringResource(R.string.delete_failed)
    var files by remember { mutableStateOf<List<VaultItem>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var exportTarget by remember { mutableStateOf<File?>(null) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val source = exportTarget
            exportTarget = null
            if (uri == null || source == null) return@rememberLauncherForActivityResult
            scope.launch {
                val success =
                    withContext(Dispatchers.IO) {
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                VaultStore.export(source, output)
                            } ?: throw IOException("output unavailable")
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }
                snackbar.showSnackbar(if (success) exportSuccess else exportFailed)
            }
        }

    LaunchedEffect(refresh) {
        files =
            withContext(Dispatchers.IO) {
                try {
                    VaultStore.migrateLegacy(context)
                    VaultStore.list(context).map { file -> VaultItem(file, file.length()) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, top = 10.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.vault_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(text = stringResource(R.string.vault_subtitle))
                Text(
                    text = stringResource(R.string.vault_summary, files.size, formatBytes(files.sumOf { it.size })),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LanguagePicker()
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.create_encrypted_keybox))
            }
        },
    ) { padding ->
        if (files.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringResource(R.string.empty_vault_title), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.empty_vault_body))
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = onCreate) { Text(stringResource(R.string.create_keybox)) }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(files, key = { it.file.name }) { item ->
                    val file = item.file
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(formatBytes(item.size), style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = {
                                    exportTarget = file
                                    exportLauncher.launch(file.name)
                                },
                            ) {
                                Icon(
                                    Icons.Default.UploadFile,
                                    contentDescription = stringResource(R.string.export_file, file.name),
                                )
                            }
                            IconButton(onClick = { deleteTarget = file }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete_file, file.name),
                                )
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
    }

    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_keybox_title)) },
            text = { Text(stringResource(R.string.delete_keybox_message, file.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val success =
                                withContext(Dispatchers.IO) {
                                    try {
                                        VaultStore.delete(file)
                                    } catch (_: Exception) {
                                        false
                                    }
                                }
                            snackbar.showSnackbar(if (success) deleted else deleteFailed)
                            if (success) refresh++
                        }
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScreen(
    onBack: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val xmlFailed = stringResource(R.string.xml_use_failed)
    val encryptFailed = stringResource(R.string.encryption_failed)
    val encryptSuccess = stringResource(R.string.encrypted_success)
    val signingUnavailable = stringResource(R.string.signing_key_unavailable)
    val signingPublicKey = stringResource(R.string.signing_public_key)
    val publicKeyCopied = stringResource(R.string.public_key_copied)
    var author by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var keyboxes by remember { mutableStateOf<List<SelectedKeybox>>(emptyList()) }
    var sourceName by remember { mutableStateOf<String?>(null) }
    var publicKey by remember { mutableStateOf<String?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            keyboxes.forEach { it.bytes.fill(0) }
            password = ""
            confirmation = ""
        }
    }

    LaunchedEffect(Unit) {
        publicKey =
            withContext(Dispatchers.IO) {
                try {
                    MobileCrypto.ensureSigningKey()
                    MobileCrypto.publicKeyBase64()
                } catch (_: Exception) {
                    null
                }
            }
    }

    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val selected =
                    withContext(Dispatchers.IO) {
                        try {
                            val selectedName = displayName(context, uri) ?: "keybox.xml"
                            val entries =
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    KeyboxImportReader.read(input, selectedName, NativeCrypto::validateKeyboxXml)
                                } ?: throw IOException("input unavailable")
                            Pair(entries, selectedName)
                        } catch (_: Exception) {
                            null
                        }
                    }
                if (selected == null) {
                    snackbar.showSnackbar(xmlFailed)
                } else {
                    keyboxes.forEach { it.bytes.fill(0) }
                    keyboxes = selected.first
                    sourceName = selected.second
                }
            }
        }

    val authorValid = author.isNotBlank() && author.length <= 1024
    val passwordValid = password.length in 12..1024
    val confirmationValid = confirmation == password && confirmation.isNotEmpty()
    val canSave =
        authorValid &&
            passwordValid &&
            confirmationValid &&
            keyboxes.isNotEmpty() &&
            publicKey != null &&
            !saving

    fun save() {
        val selectedKeyboxes = keyboxes
        if (selectedKeyboxes.isEmpty()) return
        saving = true
        val selectedAuthor = author
        val selectedPassword = password
        scope.launch {
            val outcome =
                withContext(Dispatchers.IO) {
                    try {
                        val filenames =
                            VaultStore.allocateBatchFilenames(
                                context = context,
                                author = selectedAuthor,
                                sourceNames = selectedKeyboxes.map { it.displayName },
                            )
                        val items =
                            selectedKeyboxes.zip(filenames) { keybox, filename ->
                                MobileCrypto.BatchItem(filename = filename, xmlUtf8 = keybox.bytes)
                            }
                        MobileCrypto.encryptAndSaveBatch(
                            noBackupDirectory = context.noBackupFilesDir.absolutePath,
                            author = selectedAuthor,
                            items = items,
                            password = selectedPassword,
                        )
                    } catch (_: Exception) {
                        MobileCrypto.EncryptResult.NATIVE_FAILURE
                    }
                }
            saving = false
            when (outcome) {
                MobileCrypto.EncryptResult.SUCCESS -> {
                    selectedKeyboxes.forEach { it.bytes.fill(0) }
                    keyboxes = emptyList()
                    sourceName = null
                    password = ""
                    confirmation = ""
                    snackbar.showSnackbar(encryptSuccess)
                    onBack()
                }
                MobileCrypto.EncryptResult.INVALID_INPUT -> snackbar.showSnackbar(xmlFailed)
                MobileCrypto.EncryptResult.SIGNING_FAILURE -> snackbar.showSnackbar(signingUnavailable)
                MobileCrypto.EncryptResult.NATIVE_FAILURE -> {
                    Log.w(LOG_TAG, "Native batch keybox encryption failed")
                    snackbar.showSnackbar(encryptFailed)
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.create_keybox)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.security_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.security_body), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it.take(1024) },
                    label = { Text(stringResource(R.string.author_label)) },
                    supportingText = { if (!authorValid) Text(stringResource(R.string.author_required)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = publicKey ?: signingUnavailable,
                    onValueChange = {},
                    label = { Text(stringResource(R.string.signing_public_key)) },
                    readOnly = true,
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                val key = publicKey ?: return@IconButton
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText(signingPublicKey, key))
                                scope.launch { snackbar.showSnackbar(publicKeyCopied) }
                            },
                            enabled = publicKey != null,
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_public_key))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            picker.launch(
                                arrayOf(
                                    "application/xml",
                                    "text/xml",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(sourceName ?: stringResource(R.string.choose_xml))
                    }
                    if (keyboxes.isNotEmpty()) {
                        Text(
                            stringResource(
                                R.string.vault_summary,
                                keyboxes.size,
                                formatBytes(keyboxes.sumOf { it.bytes.size.toLong() }),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(stringResource(R.string.xml_limit), style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(1024) },
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription =
                                    stringResource(
                                        if (showPassword) R.string.hide_password else R.string.show_password,
                                    ),
                            )
                        }
                    },
                    supportingText = { if (!passwordValid) Text(stringResource(R.string.password_minimum)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(1024) },
                    label = { Text(stringResource(R.string.confirm_password)) },
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = { if (!confirmationValid) Text(stringResource(R.string.passwords_mismatch)) },
                    singleLine = true,
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = ::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                        if (saving) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                        } else {
                            Text(stringResource(R.string.encrypt_save))
                        }
                    }
                    Text(stringResource(R.string.crypto_summary), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun displayName(
    context: Context,
    uri: Uri,
): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) return cursor.getString(index)?.take(255)
        }
    }
    return null
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KiB"
        else -> "${bytes / (1024 * 1024)} MiB"
    }
