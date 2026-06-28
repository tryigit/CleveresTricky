pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CleveresTricky"
include(":module")
include(":service")
include(":stub")
include(":encryptor-app")

gradle.rootProject {
    allprojects {
        buildscript.configurations.all {
            resolutionStrategy {
                force("io.netty:netty-codec-http:4.1.118.Final")
                force("io.netty:netty-codec-http2:4.1.118.Final")
                force("io.netty:netty-codec:4.1.118.Final")
                force("io.netty:netty-handler-proxy:4.1.118.Final")
                force("org.bouncycastle:bcpkix-jdk18on:1.80")
                force("org.bouncycastle:bcprov-jdk18on:1.80")
                force("ch.qos.logback:logback-core:1.5.37")
                force("ch.qos.logback:logback-classic:1.5.37")
            }
        }
        configurations.all {
            resolutionStrategy {
                force("io.netty:netty-codec-http:4.1.118.Final")
                force("io.netty:netty-codec-http2:4.1.118.Final")
                force("io.netty:netty-codec:4.1.118.Final")
                force("io.netty:netty-handler-proxy:4.1.118.Final")
                force("org.bouncycastle:bcpkix-jdk18on:1.80")
                force("org.bouncycastle:bcprov-jdk18on:1.80")
                force("ch.qos.logback:logback-core:1.5.37")
                force("ch.qos.logback:logback-classic:1.5.37")
            }
        }
    }
}
