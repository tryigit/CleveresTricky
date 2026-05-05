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
                force("io.netty:netty-codec-http:4.2.13.Final")
                force("io.netty:netty-codec-http2:4.2.13.Final")
            }
        }
        configurations.all {
            resolutionStrategy {
                force("io.netty:netty-codec-http:4.2.13.Final")
                force("io.netty:netty-codec-http2:4.2.13.Final")
            }
        }
    }
}
