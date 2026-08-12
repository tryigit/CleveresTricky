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
val author = rootProject.extra["author"] as String
val description = rootProject.extra["description"] as String
val moduleDescription = description

fun calculateChecksum(variantLowered: String): String {
    return MessageDigest.getInstance("SHA-256").run {
        update(moduleId.toByteArray(Charsets.UTF_8))
        update(moduleName.toByteArray(Charsets.UTF_8))
        update("$verName ($verCode-$commitHash-$variantLowered)".toByteArray(Charsets.UTF_8))
        update(verCode.toString().toByteArray(Charsets.UTF_8))
        update(author.toByteArray(Charsets.UTF_8))
        update(description.toByteArray(Charsets.UTF_8))
        HexFormat.of().formatHex(digest())
    }
}

android {
    namespace = "cleveres.tricky.cleverestech"
    compileSdk = 37

    defaultConfig {
        applicationId = "cleveres.tricky.cleverestech"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (project.hasProperty("RELEASE_KEYSTORE")) {
                storeFile = file(project.property("RELEASE_KEYSTORE") as String)
                storePassword = project.property("RELEASE_KEY_PASSWORD") as String
                keyAlias = project.property("RELEASE_KEY_ALIAS") as String
                keyPassword = project.property("RELEASE_KEY_PASSWORD") as String
            } else if (project.hasProperty("BETA_KEYSTORE")) {
                storeFile = file(project.property("BETA_KEYSTORE") as String)
                storePassword = project.property("BETA_KEY_PASSWORD") as String
                keyAlias = project.property("BETA_KEY_ALIAS") as String
                keyPassword = project.property("BETA_KEY_PASSWORD") as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (project.hasProperty("RELEASE_KEYSTORE") || project.hasProperty("BETA_KEYSTORE")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        forEach {
            val checksum = calculateChecksum(it.name)
            it.buildConfigField("String", "CHECKSUM", "\"$checksum\"")
        }
    }

    packaging {
        resources {
            excludes += "META-INF/versions/**"
            excludes += "META-INF/DEPENDENCIES"
            pickFirsts += "META-INF/LICENSE.md"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = false
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.all {
            it.jvmArgs("-XX:+EnableDynamicAgentLoading")
            it.testLogging {
                events =
                    setOf(
                        org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_OUT,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.STANDARD_ERROR,
                        org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED,
                    )
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("17"))
    }
}

dependencies {
    compileOnly(project(":stub"))
    implementation(libs.annotation)
    implementation(libs.bcpkix.jdk18on)
    implementation(libs.bcprov.jdk18on)
    implementation(libs.bcutil.jdk18on)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(project(":stub"))
    testImplementation(libs.kxml)
    testImplementation(libs.json)
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(libs.nanohttpd)
}

afterEvaluate {
    android.buildTypes.forEach { buildType ->
        val variantLowered = buildType.name.lowercase()
        val variantCapped = buildType.name.replaceFirstChar { it.uppercaseChar() }

        val pushTask =
            tasks.register<Task>("pushService$variantCapped") {
                group = "Service"
                dependsOn("assemble$variantCapped")
                doLast {
                    ProcessBuilder(
                        "adb",
                        "push",
                        layout.buildDirectory.file("outputs/apk/$variantLowered/service-$variantLowered.apk").get().asFile.absolutePath,
                        "/data/local/tmp/service.apk",
                    ).inheritIO().start().waitFor().let {
                        if (it != 0) {
                            throw GradleException("Command failed with exit code $it")
                        }
                    }

                    ProcessBuilder(
                        "adb",
                        "shell",
                        "su",
                        "-c",
                        "rm /data/adb/modules/cleverestricky/service.apk; mv /data/local/tmp/service.apk /data/adb/modules/cleverestricky/",
                    ).inheritIO().start().waitFor().let {
                        if (it != 0) {
                            throw GradleException("Command failed with exit code $it")
                        }
                    }
                }
            }

        tasks.register<Task>("pushAndRestartService$variantCapped") {
            group = "Service"
            dependsOn(pushTask)
            doLast {
                ProcessBuilder(
                    "adb",
                    "shell",
                    "su",
                    "-c",
                    "setprop ctl.restart keystore2",
                ).inheritIO().start().waitFor().let {
                    if (it != 0) {
                        throw GradleException("Command failed with exit code $it")
                    }
                }
            }
        }
    }
}
