import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.agp.app) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
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

val moduleId by extra("cleverestricky")
val moduleName by extra("CleveresTricky")
val author by extra("tryigitx")
val description by extra("KernelSU keystore compatibility and device configuration module. See GitHub for details.")
val verName by extra("V2.5.1")
val verCode by extra(gitCommitCount)
val commitHash by extra(gitCommitHash)
val abiList by extra(listOf("arm64-v8a", "x86_64"))

val androidMinSdkVersion by extra(31)
val androidTargetSdkVersion by extra(36)
val androidCompileSdkVersion by extra(36)
val androidBuildToolsVersion by extra("36.0.0")
val androidCompileNdkVersion by extra("27.3.13750724")
val androidSourceCompatibility by extra(JavaVersion.VERSION_17)
val androidTargetCompatibility by extra(JavaVersion.VERSION_17)

tasks.register("Delete", Delete::class) {
    delete(layout.buildDirectory)
}

fun Project.configureBaseExtension() {
    extensions.findByType(ApplicationExtension::class)?.run {
        namespace = "cleveres.tricky.cleverestech"
        compileSdk = androidCompileSdkVersion
        ndkVersion = androidCompileNdkVersion
        buildToolsVersion = androidBuildToolsVersion

        defaultConfig {
            minSdk = androidMinSdkVersion
            targetSdk = androidTargetSdkVersion
            versionCode = verCode
            versionName = verName
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
        buildToolsVersion = androidBuildToolsVersion

        defaultConfig {
            minSdk = androidMinSdkVersion
        }

        lint {
            checkReleaseBuilds = false
            abortOnError = true
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
            allWarningsAsErrors.set(!name.contains("UnitTest") && !project.name.contains("encryptor-app"))
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

    project.plugins.apply("org.jlleitschuh.gradle.ktlint")

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        debug.set(false)
        version.set("1.2.1")
        enableExperimentalRules.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
    }
}
