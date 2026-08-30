import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import java.util.Collections
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.remap) apply false
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

fun execute(
    vararg command: String,
    currentWorkingDir: File = file("./"),
): String {
    val byteOut = ByteArrayOutputStream()
    val process =
        ProcessBuilder(*command)
            .directory(currentWorkingDir)
            .redirectErrorStream(true)
            .start()
    process.inputStream.copyTo(byteOut)
    val output = byteOut.toString(Charsets.UTF_8).trim()
    if (process.waitFor() != 0) {
        throw GradleException("Command failed: ${command.joinToString(" ")}\n$output")
    }
    return output
}

val gitCommitCount = execute("git", "rev-list", "HEAD", "--count").toInt()
val gitCommitHash = execute("git", "rev-parse", "--verify", "--short", "HEAD")

val moduleId = "cleverestricky"
val moduleName = "CleveresTricky"
val author = "tryigitx"
val description = "KernelSU keystore compatibility and device configuration module. See GitHub for details."
val verName = "V2.7.0"
val verCode = gitCommitCount
val commitHash = gitCommitHash
val abiList = listOf("arm64-v8a", "x86_64")

val androidMinSdkVersion = 31
val androidTargetSdkVersion = 37
val androidCompileSdkVersion = 37
val androidMaxSupportedSdkVersion = 37
val androidCompileNdkVersion = "27.3.13750724"
val androidSourceCompatibility = JavaVersion.VERSION_17
val androidTargetCompatibility = JavaVersion.VERSION_17

extra.set("moduleId", moduleId)
extra.set("moduleName", moduleName)
extra.set("author", author)
extra.set("description", description)
extra.set("verName", verName)
extra.set("verCode", verCode)
extra.set("commitHash", commitHash)
extra.set("abiList", abiList)
extra.set("androidMinSdkVersion", androidMinSdkVersion)
extra.set("androidTargetSdkVersion", androidTargetSdkVersion)
extra.set("androidCompileSdkVersion", androidCompileSdkVersion)
extra.set("androidMaxSupportedSdkVersion", androidMaxSupportedSdkVersion)
extra.set("androidCompileNdkVersion", androidCompileNdkVersion)
extra.set("androidSourceCompatibility", androidSourceCompatibility)
extra.set("androidTargetCompatibility", androidTargetCompatibility)

tasks.register("Delete", Delete::class) {
    delete(layout.buildDirectory)
}

fun Project.configureBaseExtension() {
    extensions.findByType(ApplicationExtension::class)?.run {
        namespace = "cleveres.tricky.cleverestech"
        compileSdk = androidCompileSdkVersion
        ndkVersion = androidCompileNdkVersion

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName
        }

        lint {
            checkReleaseBuilds = false
            abortOnError = true
            warningsAsErrors = true
        }

        compileOptions {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }

    extensions.findByType(LibraryExtension::class)?.run {
        namespace = "cleveres.tricky.cleverestech"
        compileSdk = androidCompileSdkVersion
        ndkVersion = androidCompileNdkVersion

        defaultConfig {
            minSdk = androidMinSdkVersion
        }

        lint {
            checkReleaseBuilds = false
            abortOnError = true
            warningsAsErrors = true
        }

        compileOptions {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }
}

subprojects {
    plugins.withId("com.android.application") {
        configureBaseExtension()
    }
    plugins.withId("com.android.library") {
        configureBaseExtension()
    }
    plugins.withType(JavaPlugin::class.java) {
        extensions.configure(JavaPluginExtension::class.java) {
            sourceCompatibility = androidSourceCompatibility
            targetCompatibility = androidTargetCompatibility
        }
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors.set(true)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(
            listOf(
                "-Werror",
                "-Xlint:all",
                "-Xlint:-options",
                "-Xlint:-path",
                "-Xlint:-rawtypes",
                "-Xlint:-unchecked",
                "-Xlint:-this-escape",
            ),
        )
    }

    tasks.withType<Test>().configureEach {
        val forbiddenOutput = Collections.synchronizedList(mutableListOf<String>())
        val forbiddenMarker = Regex("(?i)(^|[^a-z])(warning|warn|error|exception|failed)([^a-z]|$)")
        addTestOutputListener(
            object : TestOutputListener {
                override fun onOutput(
                    testDescriptor: TestDescriptor,
                    outputEvent: TestOutputEvent,
                ) {
                    val message = outputEvent.message.trim()
                    if (message.isEmpty()) return
                    if (
                        outputEvent.destination == TestOutputEvent.Destination.StdErr ||
                        forbiddenMarker.containsMatchIn(message)
                    ) {
                        forbiddenOutput +=
                            "${testDescriptor.className}.${testDescriptor.name} " +
                            "[${outputEvent.destination}]: $message"
                    }
                }
            },
        )
        doLast {
            val violations = synchronized(forbiddenOutput) { forbiddenOutput.toList() }
            if (violations.isNotEmpty()) {
                throw GradleException(
                    buildString {
                        appendLine("Tests emitted forbidden warning/error output:")
                        violations.forEach { appendLine(it) }
                    }.trimEnd(),
                )
            }
        }
    }

    project.plugins.apply("org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        debug.set(false)
        version.set("1.2.1")
        enableExperimentalRules.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
    }
}
