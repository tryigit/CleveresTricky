import org.apache.tools.ant.filters.FixCrLfFilter
import org.apache.tools.ant.filters.ReplaceTokens
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
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

private data class RuntimePayloadFloor(
    val relativePath: String,
    val minimumBytes: Long,
)

private fun verifyRuntimePayloadContract(
    moduleRoot: File,
    abis: List<String>,
) {
    val commonFloors =
        listOf(
            RuntimePayloadFloor("service.apk", 300_000),
            RuntimePayloadFloor("service.sh", 4_000),
            RuntimePayloadFloor("customize.sh", 10_000),
            RuntimePayloadFloor("daemon", 32),
            RuntimePayloadFloor("webroot/index.html", 100_000),
            RuntimePayloadFloor("webroot/bridge.js", 15_000),
            RuntimePayloadFloor("webroot/policy.js", 40_000),
            RuntimePayloadFloor("webroot/ux.js", 250_000),
        )
    val nativeFloors =
        mapOf(
            "libcleverestricky.so" to 400_000L,
            "inject" to 200_000L,
            "webui_bridge" to 200_000L,
            "cleverestrickyd" to 250_000L,
            "cleverestricky_backend" to 500_000L,
        )

    val required =
        buildList {
            addAll(commonFloors)
            abis.forEach { abi ->
                nativeFloors.forEach { (name, minimumBytes) ->
                    add(RuntimePayloadFloor("lib/$abi/$name", minimumBytes))
                }
            }
        }

    required.forEach { requirement ->
        val payload = File(moduleRoot, requirement.relativePath)
        if (!payload.isFile) {
            throw GradleException("Runtime contract missing ${requirement.relativePath}")
        }
        if (payload.length() < requirement.minimumBytes) {
            throw GradleException(
                "Runtime contract payload ${requirement.relativePath} shrank to ${payload.length()} bytes; " +
                    "minimum is ${requirement.minimumBytes}. If this reduction is intentional, review and update the floor explicitly.",
            )
        }
    }

    val daemonWrapper = File(moduleRoot, "daemon").readText()
    if (!daemonWrapper.contains("exec \"\$MODDIR/cleverestrickyd\" \"\$MODDIR\"")) {
        throw GradleException("Runtime contract daemon wrapper no longer launches cleverestrickyd with the module directory")
    }

    val serviceSupervisor = File(moduleRoot, "service.sh").readText()
    val supervisorMarkers =
        listOf(
            "generate_backend_auth()",
            "CLEVERES_TRICKY_BACKEND_AUTH",
            "\"\$MODDIR/daemon\"",
            "unset CLEVERES_TRICKY_BACKEND_AUTH",
        )
    supervisorMarkers.forEach { marker ->
        if (!serviceSupervisor.contains(marker)) {
            throw GradleException("Runtime contract service supervisor lost required marker: $marker")
        }
    }

    val installer = File(moduleRoot, "customize.sh").readText()
    listOf("inject", "webui_bridge", "cleverestrickyd", "cleverestricky_backend").forEach { executable ->
        if (!installer.contains("/$executable\"") && !installer.contains("/$executable'")) {
            throw GradleException("Runtime contract installer no longer extracts $executable")
        }
    }

    val expectedWebUiFiles =
        setOf(
            "webroot/index.html",
            "webroot/bridge.js",
            "webroot/policy.js",
            "webroot/ux.js",
        )
    val actualWebUiFiles =
        fileTree(moduleRoot)
            .matching { include("webroot/**") }
            .files
            .filter(File::isFile)
            .map { it.relativeTo(moduleRoot).invariantSeparatorsPath }
            .toSet()
    if (actualWebUiFiles != expectedWebUiFiles) {
        throw GradleException(
            "Runtime WebUI file set is not canonical: ${actualWebUiFiles.sorted()}",
        )
    }

    abis.forEach { abi ->
        val runtimeBytes =
            nativeFloors.keys.sumOf { name -> File(moduleRoot, "lib/$abi/$name").length() } +
                File(moduleRoot, "service.apk").length() +
                expectedWebUiFiles.sumOf { File(moduleRoot, it).length() }

        if (runtimeBytes < 2_500_000L) {
            throw GradleException(
                "Runtime contract aggregate payload for $abi is unexpectedly small: $runtimeBytes bytes",
            )
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
    description = "Builds all Rust native components for Android targets using cargo-ndk"
    workingDir = file("../rust")

    doFirst {
        if (!commandExists("cargo") || !commandExists("rustup")) {
            throw GradleException("Rust and rustup are required to build the native attestation core")
        }
    }

    dependsOn("installRustTargets")

    environment("RUSTFLAGS", "-D warnings -C link-arg=-Wl,-z,max-page-size=16384")

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
        "-p",
        "cleverestricky-webui-bridge",
        "-p",
        "cleverestricky-daemon",
        "-p",
        "cleverestricky-backend",
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
                    exclude("module.prop", "customize.sh", "post-fs-data.sh", "service.sh", "daemon", "webroot/**")
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
                        "webroot/index.html",
                        "webroot/bridge.js",
                        "webroot/policy.js",
                        "webroot/ux.js",
                    )
                    val tokens =
                        mapOf(
                            "DEBUG" to if (buildTypeLowered == "debug") "true" else "false",
                            "SONAME" to moduleId,
                            "SUPPORTED_ABIS" to supportedAbis,
                            "MIN_SDK" to androidMinSdkVersion.toString(),
                            "MAX_SDK" to androidMaxSupportedSdkVersion.toString(),
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
                    from(rootProject.layout.projectDirectory.file("rust/target/$rustTarget/release/webui_bridge")) {
                        into("lib/$abi")
                    }
                    from(rootProject.layout.projectDirectory.file("rust/target/$rustTarget/release/cleverestrickyd")) {
                        into("lib/$abi")
                    }
                    from(rootProject.layout.projectDirectory.file("rust/target/$rustTarget/release/cleverestricky_backend")) {
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
                        val webUiBridgePath = file("${moduleDir.get().asFile}/lib/$abi/webui_bridge")
                        if (!webUiBridgePath.exists()) {
                            throw GradleException("WebUI bridge binary for $abi is missing at $webUiBridgePath")
                        }
                        val daemonPath = file("${moduleDir.get().asFile}/lib/$abi/cleverestrickyd")
                        if (!daemonPath.exists()) {
                            throw GradleException("Rust daemon binary for $abi is missing at $daemonPath")
                        }
                        val backendPath = file("${moduleDir.get().asFile}/lib/$abi/cleverestricky_backend")
                        if (!backendPath.exists()) {
                            throw GradleException("Rust backend binary for $abi is missing at $backendPath")
                        }
                    }

                    verifyRuntimePayloadContract(moduleDir.get().asFile, abiList)

                    fun computeSha256(f: File): String {
                        val md = MessageDigest.getInstance("SHA-256")
                        f.forEachBlock(4096) { bytes, size ->
                            md.update(bytes, 0, size)
                        }
                        return HexFormat.of().formatHex(md.digest())
                    }

                    // Generate .sha256 checksum files for all files in the module directory
                    val allFiles =
                        fileTree(moduleDir) {
                            exclude("**/*.sha256")
                            exclude("**/integrity_manifest.json")
                        }.files.filter(File::isFile)

                    allFiles.forEach { payload ->
                        val hexHash = computeSha256(payload)
                        file(payload.path + ".sha256").writeText(hexHash)
                    }

                    val commonFiles =
                        listOf(
                            "service.apk" to "regular",
                            "service.sh" to "executable",
                            "post-fs-data.sh" to "executable",
                            "action.sh" to "executable",
                            "daemon" to "executable",
                            "sepolicy.rule" to "regular",
                            "module.prop" to "regular",
                            "webroot/index.html" to "regular",
                            "webroot/bridge.js" to "regular",
                            "webroot/policy.js" to "regular",
                            "webroot/ux.js" to "regular",
                        )

                    val archBinaryNames =
                        listOf(
                            "lib$moduleId.so" to "regular",
                            "inject" to "executable",
                            "webui_bridge" to "executable",
                            "cleverestrickyd" to "executable",
                            "cleverestricky_backend" to "executable",
                        )

                    val pkcs8Header = HexFormat.of().parseHex("302e020100300506032b657004220420")
                    val privateKeySeed =
                        System.getenv("INTEGRITY_SIGNING_KEY")?.trim()
                            ?: rootProject.file("keys/integrity_signer.key").takeIf { it.exists() }?.readText()?.trim()

                    val isReleaseBuild =
                        System.getenv("CI_RELEASE") == "true" ||
                            (System.getenv("CI") == "true" && System.getenv("GITHUB_EVENT_NAME") != "pull_request") ||
                            System.getenv("REQUIRE_INTEGRITY_SIGNING_KEY") == "true"

                    abiList.forEach { abi ->
                        val manifestFilesList = mutableListOf<Map<String, String>>()

                        commonFiles.forEach { (relPath, type) ->
                            val f = file("${moduleDir.get().asFile}/$relPath")
                            if (f.exists()) {
                                manifestFilesList.add(
                                    mapOf(
                                        "path" to relPath,
                                        "sha256" to computeSha256(f),
                                        "type" to type,
                                    ),
                                )
                            }
                        }

                        archBinaryNames.forEach { (binName, type) ->
                            val f = file("${moduleDir.get().asFile}/lib/$abi/$binName")
                            if (f.exists()) {
                                manifestFilesList.add(
                                    mapOf(
                                        "path" to binName,
                                        "sha256" to computeSha256(f),
                                        "type" to type,
                                    ),
                                )
                            }
                        }

                        val sortedEntries = manifestFilesList.sortedBy { it["path"] as String }
                        val canonicalData =
                            buildString {
                                append("1\n")
                                for (entry in sortedEntries) {
                                    append(entry["path"]).append('\n')
                                    append((entry["sha256"] as String).lowercase()).append('\n')
                                    append(entry["type"]).append('\n')
                                }
                            }

                        val signatureHex =
                            if (!privateKeySeed.isNullOrBlank()) {
                                val privKeyBytes = pkcs8Header + HexFormat.of().parseHex(privateKeySeed)
                                val keyFactory = KeyFactory.getInstance("Ed25519")
                                val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privKeyBytes))
                                val sig = Signature.getInstance("Ed25519")
                                sig.initSign(privKey)
                                sig.update(canonicalData.toByteArray(Charsets.UTF_8))
                                HexFormat.of().formatHex(sig.sign())
                            } else if (isReleaseBuild) {
                                throw GradleException(
                                    "INTEGRITY_SIGNING_KEY environment variable or keys/integrity_signer.key is required " +
                                        "to sign the module manifest for release builds. Hardcoded signing keys are strictly prohibited.",
                                )
                            } else {
                                ""
                            }

                        val manifest =
                            groovy.json.JsonBuilder(
                                mapOf(
                                    "version" to 1,
                                    "files" to sortedEntries,
                                    "signature" to signatureHex,
                                ),
                            ).toPrettyString()

                        val abiManifestFile = file("${moduleDir.get().asFile}/lib/$abi/integrity_manifest.json")
                        abiManifestFile.writeText(manifest)
                        file("${moduleDir.get().asFile}/lib/$abi/integrity_manifest.json.sha256").writeText(computeSha256(abiManifestFile))

                        if (abi == "arm64-v8a" || abi == abiList.first()) {
                            val rootManifestFile = file("${moduleDir.get().asFile}/integrity_manifest.json")
                            rootManifestFile.writeText(manifest)
                            file("${moduleDir.get().asFile}/integrity_manifest.json.sha256").writeText(computeSha256(rootManifestFile))
                        }
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
