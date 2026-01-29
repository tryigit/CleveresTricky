package cleveres.tricky.cleverestech.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object RootUtils {
    private const val CONFIG_DIR = "/data/adb/cleveres_tricky"

    fun exec(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            process.waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun readFile(filename: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $CONFIG_DIR/$filename"))
            val content = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            content
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    fun saveFile(context: Context, filename: String, content: String): Boolean {
        return try {
            val tempFile = File(context.cacheDir, filename)
            tempFile.writeText(content)
            val path = tempFile.absolutePath
            val cmd = "cp \"$path\" \"$CONFIG_DIR/$filename\" && chmod 600 \"$CONFIG_DIR/$filename\""
            exec(cmd)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrickyTheme {
                MainApp()
            }
        }
    }
}

@Composable
fun TrickyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color.White,
            onPrimary = Color.Black,
            background = Color.Black,
            onBackground = Color.White,
            surface = Color(0xFF121212),
            onSurface = Color.White
        ),
        content = content
    )
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainApp() {
    var screen by remember { mutableStateOf("home") }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "ScreenTransition"
    ) { targetScreen ->
        when (targetScreen) {
            "home" -> HomeScreen(onNavigate = { screen = it })
            "config" -> ConfigScreen(onBack = { screen = "home" })
        }
    }
}

@Composable
fun HomeScreen(onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "CleveresTricky",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BETA",
            fontSize = 16.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        MenuButton(text = "Configure Keybox") {
            onNavigate("config")
        }

        Spacer(modifier = Modifier.height(16.dp))

        MenuButton(text = "Join Telegram") {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/cleverestech"))
            context.startActivity(intent)
        }
    }
}

@Composable
fun MenuButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(text = text, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var keyboxContent by remember { mutableStateOf("Loading...") }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            keyboxContent = RootUtils.readFile("keybox.xml")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Back",
                color = Color.Gray,
                modifier = Modifier.clickable { onBack() }
            )
            Text(
                text = "keybox.xml",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (isSaving) "Saving..." else "Save",
                color = if (isSaving) Color.Gray else Color.White,
                modifier = Modifier.clickable {
                    if (!isSaving) {
                        isSaving = true
                        scope.launch(Dispatchers.IO) {
                            val success = RootUtils.saveFile(context, "keybox.xml", keyboxContent)
                            withContext(Dispatchers.Main) {
                                isSaving = false
                                Toast.makeText(context, if (success) "Saved" else "Failed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextField(
            value = keyboxContent,
            onValueChange = { keyboxContent = it },
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF121212),
                unfocusedContainerColor = Color(0xFF121212),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color.White
            )
        )
    }
}
