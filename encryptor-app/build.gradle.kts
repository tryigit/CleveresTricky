import java.util.Base64

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "cleveres.tricky.encryptor"
    compileSdk = 35

    defaultConfig {
        applicationId = "cleveres.tricky.encryptor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("encryptor.jks")
            if (System.getenv("ENCRYPTOR_KEYSTORE_BASE64") != null) {
                val keystoreBytes = Base64.getDecoder().decode(System.getenv("ENCRYPTOR_KEYSTORE_BASE64"))
                keystoreFile.writeBytes(keystoreBytes)
                storeFile = keystoreFile
                storePassword = System.getenv("ENCRYPTOR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ENCRYPTOR_KEY_ALIAS")
                keyPassword = System.getenv("ENCRYPTOR_KEY_PASSWORD")
            } else if (!keystoreFile.exists()) {
                println("Generating temporary keystore for Encryptor App...")
                val keytool = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
                exec {
                    commandLine(keytool, "-genkey", "-v", "-keystore", keystoreFile.absolutePath, "-alias", "key0", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000", "-storepass", "android", "-keypass", "android", "-dname", "CN=Android Debug,O=Android,C=US")
                }
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "key0"
                keyPassword = "android"
            } else {
                 // Fallback for local builds if file exists but no env vars
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "key0"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)
}
