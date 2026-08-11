import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.agp.app)
}

val moduleId = rootProject.extra["moduleId"] as String
val moduleName = rootProject.extra["moduleName"] as String
val verCode = rootProject.extra["verCode"] as Int
val verName = rootProject.extra["verName"] as String
val commitHash = rootProject.extra["commitHash"] as String
val abiList = (rootProject.extra["abiList"] as List<*>).map { it as String }
val androidMinSdkVersion = rootProject.extra["androidMinSdkVersion"] as Int
val androidMaxSupportedSdkVersion = rootProject.extra["androidMaxSupportedSdkVersion"] as Int
val author = rootProject.extra["author"] as String
val description = rootProject.extra["description"] as String
val moduleDescription = description

android {
    defaultConfig {
        ndk {
            abiFilters.addAll(abiList)
        }
        externalNativeBuild {
            cmake {
                arguments(
                    "-Wno-dev",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF",
                    "-DMODULE_NAME=$moduleId",
                    "-DCMAKE_CXX_STANDARD=20",
                    "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON",
                    "-DCMAKE_VISIBILITY_INLINES_HIDDEN=ON",
                    "-DCMAKE_CXX_VISIBILITY_PRESET=hidden",
                )
            }
        }
    }

    buildFeatures {
        prefab = true
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {}

evaluationDependsOn(":service")

val isWindowsHost = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

fun commandExists(command: String): Boolean {
    val pathEntries =
        System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?: return false

    return if (isWindowsHost) {
        val extensions = listOf(".exe", ".cmd", ".bat")
        pathEntries.any { dir ->
            extensions.any { ext -> File(dir, "$command$ext").isFile }
        }
    } else {
        pathEntries.any { dir ->
            val candidate = File(dir, command)
            candidate.isFile && candidate.canExecute()
        }
    }
}

fun resolveRustupBinary(): String? {
    val envRustup = System.getenv("RUSTUP")?.takeIf { it.isNotBlank() }
    if (envRustup != null && File(envRustup).isFile) return envRustup

    val cargoHome =
        System.getenv("CARGO_HOME")
            ?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("user.home"), ".cargo").absolutePath
    val rustupName = if (isWindowsHost) "rustup.exe" else "rustup"
    val cargoRustup = File(cargoHome, "bin/$rustupName")
    if (cargoRustup.isFile) return cargoRustup.absolutePath

    return if (commandExists("rustup")) "rustup" else null
}

fun String.runCommand(
    workingDir: File = rootDir,
    extraEnv: Map<String, String> = emptyMap(),
) {
    val arguments = split(" ").filter { it.isNotEmpty() }.toMutableList()
    val rustup = resolveRustupBinary()
    if (arguments.firstOrNull() == "rustup" && rustup != null) {
        arguments[0] = rustup
    }
    exec {
        commandLine(arguments)
        this.workingDir = workingDir
        environment(extraEnv)
    }
}

val rustProject = rootProject.file("native")
val rustTargetDir = layout.buildDirectory.dir("rust-target")
val rustToolchain = "nightly-2025-06-23"
val hostRustOs =
    when {
        isWindowsHost -> "windows"
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
        else -> "linux"
    }
val hostRustArch =
    when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> System.getProperty("os.arch").lowercase()
    }
val ndkHostTag =
    when {
        isWindowsHost -> "windows-x86_64"
        hostRustOs == "macos" && hostRustArch == "aarch64" -> "darwin-arm64"
        hostRustOs == "macos" -> "darwin-x86_64"
        else -> "linux-x86_64"
    }
val rustAndroidTargetByAbi =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
        "x86" to "i686-linux-android",
    )
val rustCcPrefixByAbi =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7a-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
        "x86" to "i686-linux-android",
    )

val prepareRust by tasks.registering {
    inputs.file(rootProject.file("rust-toolchain.toml"))
    outputs.file(layout.buildDirectory.file("rust-toolchain-ready"))
    doLast {
        val rustup = resolveRustupBinary()
        if (rustup == null) {
            throw GradleException("rustup is required to build native Rust components")
        }
        val rustupCommand = if (rustup == "rustup") "rustup" else rustup
        "$rustupCommand toolchain install $rustToolchain --profile minimal --component rust-src".runCommand()
        rustAndroidTargetByAbi.values.forEach { target ->
            "$rustupCommand target add --toolchain $rustToolchain $target".runCommand()
        }
        layout.buildDirectory.file("rust-toolchain-ready").get().asFile.writeText(rustToolchain)
    }
}

val buildRust by tasks.registering {
    dependsOn(prepareRust)
    inputs.dir(rustProject)
    inputs.file(rootProject.file("Cargo.toml"))
    inputs.file(rootProject.file("Cargo.lock"))
    inputs.file(rootProject.file("rust-toolchain.toml"))
    outputs.dir(rustTargetDir)
    doLast {
        val sdkDir = android.sdkDirectory
        val ndkVersion = android.ndkVersion
        val ndkDir = File(sdkDir, "ndk/$ndkVersion")
        val toolchainBin = File(ndkDir, "toolchains/llvm/prebuilt/$ndkHostTag/bin")
        if (!toolchainBin.isDirectory) {
            throw GradleException("Android NDK toolchain not found at ${toolchainBin.absolutePath}")
        }
        abiList.forEach { abi ->
            val rustTarget = rustAndroidTargetByAbi[abi]
                ?: throw GradleException("Unsupported ABI for Rust build: $abi")
            val ccPrefix = rustCcPrefixByAbi[abi]
                ?: throw GradleException("Unsupported ABI for Rust linker: $abi")
            val clangName = if (isWindowsHost) "$ccPrefix${androidMinSdkVersion}-clang.cmd" else "$ccPrefix${androidMinSdkVersion}-clang"
            val clang = File(toolchainBin, clangName)
            if (!clang.isFile) {
                throw GradleException("Android clang not found at ${clang.absolutePath}")
            }
            val linkerKey = "CARGO_TARGET_${rustTarget.uppercase().replace('-', '_')}_LINKER"
            val env =
                mapOf(
                    "CARGO_TARGET_DIR" to rustTargetDir.get().asFile.absolutePath,
                    linkerKey to clang.absolutePath,
                )
            "cargo +$rustToolchain build --release --target $rustTarget".runCommand(rustProject, env)
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            buildRust,
        ) { task ->
            task.rustTargetDir.map { dir ->
                dir.dir("generated-jni/${variant.name}")
            }
        }
    }
}

tasks.named("buildRust") {
    doLast {
        val output = rustTargetDir.get().asFile
        val generated = File(output, "generated-jni")
        generated.deleteRecursively()
        abiList.forEach { abi ->
            val target = rustAndroidTargetByAbi.getValue(abi)
            val source = File(output, "$target/release/libcleveres_tricky.so")
            if (!source.isFile) throw GradleException("Rust output missing: ${source.absolutePath}")
            val destination = File(generated, "release/$abi/libcleveres_tricky.so")
            destination.parentFile.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }
}

val zipTemplate = layout.buildDirectory.dir("zip-template")

val prepareModuleTemplate by tasks.registering(Copy::class) {
    dependsOn(":service:assembleRelease")
    dependsOn(buildRust)
    from("template")
    into(zipTemplate)
    filteringCharset = "UTF-8"
    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
    filter<ReplaceTokens>(
        "tokens" to
            mapOf(
                "MODULE_ID" to moduleId,
                "MODULE_NAME" to moduleName,
                "VERSION_NAME" to verName,
                "VERSION_CODE" to verCode.toString(),
                "AUTHOR" to author,
                "DESCRIPTION" to moduleDescription,
                "MIN_SDK" to androidMinSdkVersion.toString(),
                "MAX_SDK" to androidMaxSupportedSdkVersion.toString(),
            ),
    )
    from(project(":service").layout.buildDirectory.file("outputs/apk/release/service-release-unsigned.apk")) {
        rename { "service.apk" }
    }
    from(rustTargetDir.map { it.dir("generated-jni/release") }) {
        into("lib")
    }
}

val zipRelease by tasks.registering(Zip::class) {
    dependsOn(prepareModuleTemplate)
    archiveFileName.set("$moduleName-$verName-$commitHash.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("outputs"))
    from(zipTemplate)
}

val zipDebug by tasks.registering(Zip::class) {
    dependsOn(prepareModuleTemplate)
    archiveFileName.set("$moduleName-$verName-$commitHash-debug.zip")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("outputs"))
    from(zipTemplate)
}

tasks.register("printModuleInfo") {
    doLast {
        println("$moduleId $moduleName $verCode $verName $commitHash $androidMinSdkVersion $androidMaxSupportedSdkVersion")
    }
}