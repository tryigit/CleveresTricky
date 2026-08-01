import android.databinding.tool.ext.capitalizeUS
import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.File
import java.security.MessageDigest

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
                    "-DANDROID_CPP_FEATURES=exceptions",
                    "-Wno-dev",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DANDROID_USE_LEGACY_TOOLCHAIN_FILE=OFF",
                    "-DCMAKE_CXX_STANDARD=23",
                    "-DCMAKE_C_STANDARD=23",
                    "-DCMAKE_INTERPROCEDURAL_OPTIMIZATION=ON",
                    "-DCMAKE_VISIBILITY_INLINES_HIDDEN=ON",
                    "-DCMAKE_CXX_VISIBILITY_PRESET=hidden",
                    "-DCMAKE_C_VISIBILITY_PRESET=hidden",
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

// Rust Binder interceptor build integration. The process injector is built by
// CMake from module/src/main/cpp/inject; it must not be replaced by the Rust
// monitoring daemon because that program does not implement remote dlopen.
val isWindowsHost = org.gradle.internal.os.OperatingSystem.current().isWindows

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
        commandLine("cmd", "/c", "cargo ndk --version >NUL 2>&1 || cargo install cargo-ndk")
    } else {
        commandLine("sh", "-c", "cargo ndk --version >/dev/null 2>&1 || cargo install cargo-ndk")
    }
}

tasks.register<Exec>("installRustTargets") {
    group = "rust"
    description = "Installs Android Rust targets via rustup"
    onlyIf { commandExists("rustup") }
    commandLine("rustup", "target", "add", "aarch64-linux-android")
    dependsOn("installCargoNdk")
}

tasks.register<Exec>("cargoBuild") {
    group = "rust"
    description = "Builds the Rust Binder interceptor for supported Android ABIs"
    workingDir = file("../rust")
    onlyIf { commandExists("cargo") && commandExists("rustup") }

    dependsOn("installRustTargets")
    environment("RUSTFLAGS", "-D warnings")

    commandLine(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "build",
        "--release",
        "-p",
        "interceptor",
    )

    doLast {
        val abiMap = mapOf("aarch64-linux-android" to "arm64-v8a")
        val baseTarget = "../rust/target"

        abiMap.forEach { (rustAbi, androidAbi) ->
            copy {
                from("$baseTarget/$rustAbi/release/libinterceptor.so")
                into(layout.buildDirectory.dir("rust_outputs/lib/$androidAbi"))
                rename { "libcleverestricky.so" }
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("cargoBuild")
}

afterEvaluate {
    android.buildTypes.forEach { buildType ->
        val variantLowered = buildType.name.lowercase()
        val variantCapped = buildType.name.capitalizeUS()
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
                    exclude(
                        "module.prop",
                        "customize.sh",
                        "post-fs-data.sh",
                        "service.sh",
                        "daemon",
                        "provision_attestation.sh",
                    )
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
                    include(
                        "customize.sh",
                        "post-fs-data.sh",
                        "service.sh",
                        "daemon",
                        "provision_attestation.sh",
                    )
                    val tokens =
                        mapOf(
                            "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                            "SONAME" to moduleId,
                            "SUPPORTED_ABIS" to supportedAbis,
                            "MIN_SDK" to androidMinSdkVersion.toString(),
                        )
                    filter<ReplaceTokens>("tokens" to tokens)
                    filter<FixCrLfFilter>("eol" to FixCrLfFilter.CrLf.newInstance("lf"))
                }
                from(project(":service").tasks.getByName("package$buildTypeCapped").outputs) {
                    include("*.apk")
                    rename(".*\\.apk", "service.apk")
                }
                from(layout.buildDirectory.dir("rust_outputs/lib")) {
                    into("lib")
                }
                from(layout.buildDirectory.dir("intermediates/cxx")) {
                    include("**/inject")
                    eachFile {
                        val segments = relativePath.segments
                        if (buildTypeLowered == "release" && segments.contains("Debug")) {
                            exclude()
                            return@eachFile
                        }
                        if (buildTypeLowered == "debug" && !segments.contains("Debug")) {
                            exclude()
                            return@eachFile
                        }

                        val abi = segments.find { it in abiList }
                        if (abi != null) {
                            relativePath = RelativePath(true, "lib", abi, "inject")
                        } else {
                            exclude()
                        }
                    }
                    includeEmptyDirs = false
                }

                doLast {
                    val apk = file("${moduleDir.get().asFile}/service.apk")
                    if (!apk.exists() || apk.length() == 0L) {
                        throw GradleException("service.apk is missing or empty!")
                    }

                    abiList.forEach { abi ->
                        val libraryPath = file("${moduleDir.get().asFile}/lib/$abi/lib$moduleId.so")
                        if (!libraryPath.exists() || libraryPath.length() == 0L) {
                            throw GradleException("Binder interceptor for $abi is missing at $libraryPath")
                        }
                        val injectPath = file("${moduleDir.get().asFile}/lib/$abi/inject")
                        if (!injectPath.exists() || injectPath.length() == 0L) {
                            throw GradleException("inject binary for $abi is missing at $injectPath")
                        }
                    }

                    fileTree(moduleDir).visit {
                        if (isDirectory) return@visit
                        val md = MessageDigest.getInstance("SHA-256")
                        file.forEachBlock(4096) { bytes, size ->
                            md.update(bytes, 0, size)
                        }
                        file(file.path + ".sha256").writeText(
                            org.apache.commons.codec.binary.Hex.encodeHexString(md.digest()),
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

        val installMagiskTask =
            tasks.register<Exec>("installMagisk$variantCapped") {
                group = "module"
                dependsOn(pushTask)
                commandLine(
                    "adb",
                    "shell",
                    "su",
                    "-M",
                    "-c",
                    "magisk --install-module /data/local/tmp/$zipFileName",
                )
            }

        tasks.register<Exec>("installKsuAndReboot$variantCapped") {
            group = "module"
            dependsOn(installKsuTask)
            commandLine("adb", "reboot")
        }

        tasks.register<Exec>("installMagiskAndReboot$variantCapped") {
            group = "module"
            dependsOn(installMagiskTask)
            commandLine("adb", "reboot")
        }
    }
}
