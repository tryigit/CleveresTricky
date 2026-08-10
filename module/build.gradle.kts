import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat

plugins {
    alias(libs.plugins.agp.app)
}

val moduleId: String by rootProject.extra
val moduleName: String by rootProject.extra
val verCode: Int by rootProject.extra
val verName: String by rootProject.extra
val commitHash: String by rootProject.extra
val abiList: List<String> by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val author: String by rootProject.extra
val description: String by rootProject.extra
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

tasks.register<Exec>("installCargoNdk") {
    group = "rust"
    description = "Installs cargo-ndk if not present"
    onlyIf { commandExists("cargo") }
    if (isWindowsHost) {
        commandLine("cmd", "/c", "cargo ndk --version >NUL 2>&1 || cargo install cargo-ndk --locked")
    } else {
        commandLine("sh", "-c", "cargo ndk --version >/dev/null 2>&1 || cargo install cargo-ndk --locked")
    }
}

tasks.register<Exec>("installRustTargets") {
    group = "rust"
    description = "Installs Android Rust targets via rustup"
    onlyIf { commandExists("rustup") }
    commandLine(
        "rustup",
        "target",
        "add",
        "aarch64-linux-android",
        "x86_64-linux-android",
    )
    dependsOn("installCargoNdk")
}

tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "Builds the Rust native library and injector for all Android targets using cargo-ndk"
    workingDir = file("../rust")

    doFirst {
        if (!commandExists("cargo") || !commandExists("rustup")) {
            throw GradleException("Rust and rustup are required to build the native attestation core")
        }
    }

    dependsOn("installRustTargets")

    environment("RUSTFLAGS", "-D warnings")

    commandLine(
        "cargo",
        "ndk",
        "--platform",
        androidMinSdkVersion.toString(),
        "-t",
        "arm64-v8a",
        "-t",
        "x86_64",
        "build",
        "--release",
        "-p",
        "cleverestricky-native-core",
        "-p",
        "cleverestricky-injector-core",
    )
}

tasks.named("preBuild") {
    dependsOn("cargoBuild")
}

afterEvaluate {
    android.buildTypes.forEach { buildType ->
        val variantLowered = buildType.name.lowercase()
        val variantCapped = buildType.name.replaceFirstChar { it.uppercaseChar() }
        val buildTypeCapped = buildType.name.replaceFirstChar { it.uppercase() }
        val buildTypeLowered = buildType.name.lowercase()
        val supportedAbis =
            abiList.map {
                when (it) {
                    "arm64-v8a" -> "arm64"
                    "armeabi-v7a" -> "arm"
                    "x86" -> "x86"
                    "x86_64" -> "x64"
                    else -> error("unsupported abi $it")
                }
            }.joinToString(" ")

        val moduleDir = layout.buildDirectory.file("outputs/module/$variantLowered")
        val zipFileName =
            "$moduleName-$verName-$verCode-$commitHash-$buildTypeLowered.zip".replace(' ', '-')

        val prepareModuleFilesTask =
            tasks.register<Sync>("prepareModuleFiles$variantCapped") {
                group = "module"
                dependsOn(
                    "assemble$variantCapped",
                    ":service:package$buildTypeCapped",
                )
                into(moduleDir)
                from(rootProject.layout.projectDirectory.file("README.md"))
                from(layout.projectDirectory.file("template")) {
                    exclude("module.prop", "customize.sh", "post-fs-data.sh", "service.sh", "daemon")
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
                from(layout.projectDirectory.file("template")) {
                    include("module.prop")
                    expand(
                        "moduleId" to moduleId,
                        "moduleName" to moduleName,
                        "versionName" to "$verName ($verCode-$commitHash-$variantLowered)",
                        "versionCode" to verCode,
                        "author" to author,
                        "description" to moduleDescription,
                    )
                }
                from(layout.projectDirectory.file("template")) {
                    include("customize.sh", "post-fs-data.sh", "service.sh", "daemon")
                    val tokens =
                        mapOf(
                            "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                            "SONAME" to moduleId,
                            "SUPPORTED_ABIS" to supportedAbis,
                            "MIN_SDK" to androidMinSdkVersion.toString(),
                            "MAX_SDK" to androidTargetSdkVersion.toString(),
                        )
                    filter<ReplaceTokens>("tokens" to tokens)
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
                from(project(":service").tasks.getByName("package$buildTypeCapped").outputs) {
                    include("*.apk")
                    rename(".*\\.apk", "service.apk")
                }
                from(
                    layout.buildDirectory.file(
                        "intermediates/stripped_native_libs/$variantLowered/strip${variantCapped}DebugSymbols/out/lib",
                    ),
                ) {
                    exclude("**/libbinder.so", "**/libutils.so")
                    into("lib")
                }

                abiList.forEach { abi ->
                    val rustTarget =
                        when (abi) {
                            "arm64-v8a" -> "aarch64-linux-android"
                            "x86_64" -> "x86_64-linux-android"
                            else -> error("unsupported Rust injector ABI $abi")
                        }
                    from(rootProject.layout.projectDirectory.file("rust/target/$rustTarget/release/inject")) {
                        into("lib/$abi")
                    }
                }

                doLast {
                    val apk = file("${moduleDir.get().asFile}/service.apk")
                    if (!apk.exists() || apk.length() == 0L) {
                        throw GradleException("service.apk is missing or empty!")
                    }

                    abiList.forEach { abi ->
                        val injectPath = file("${moduleDir.get().asFile}/lib/$abi/inject")
                        if (!injectPath.exists()) {
                            throw GradleException("inject binary for $abi is missing at $injectPath")
                        }
                    }

                    val payloadFiles =
                        fileTree(moduleDir) {
                            exclude("**/*.sha256")
                        }.files
                            .filter(File::isFile)
                            .sortedBy { it.relativeTo(moduleDir.get().asFile).invariantSeparatorsPath }
                    payloadFiles.forEach { payload ->
                        val md = MessageDigest.getInstance("SHA-256")
                        payload.forEachBlock(4096) { bytes, size ->
                            md.update(bytes, 0, size)
                        }
                        file(payload.path + ".sha256").writeText(
                            HexFormat.of().formatHex(md.digest()),
                        )
                    }
                }
            }

        val zipTask =
            tasks.register<Zip>("zip$variantCapped") {
                group = "module"
                dependsOn(prepareModuleFilesTask)
                archiveFileName.set(zipFileName)
                destinationDirectory.set(layout.projectDirectory.file("release").asFile)
                from(moduleDir)
            }

        val pushTask =
            tasks.register<Exec>("push$variantCapped") {
                group = "module"
                dependsOn(zipTask)
                doFirst {
                    commandLine("adb", "push", zipTask.get().outputs.files.singleFile.path, "/data/local/tmp")
                }
            }

        val installKsuTask =
            tasks.register<Exec>("installKsu$variantCapped") {
                group = "module"
                dependsOn(pushTask)
                commandLine(
                    "adb",
                    "shell",
                    "su",
                    "-c",
                    "/data/adb/ksud module install /data/local/tmp/$zipFileName",
                )
            }

        tasks.register<Exec>("installKsuAndReboot$variantCapped") {
            group = "module"
            dependsOn(installKsuTask)
            commandLine("adb", "reboot")
        }
    }
}
