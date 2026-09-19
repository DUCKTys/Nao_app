@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
                includeGroupAndSubgroups("androidx")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral { mavenContent { releasesOnly() } }
        // NewPipeExtractor publishes its runtime dependency nanojson under
        // the case-sensitive TeamNewPipe group on JitPack.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.TeamNewPipe")
                includeGroup("com.github.teamnewpipe")
                includeGroup("com.github.therealbush")
            }
        }
    }
}

rootProject.name = "NaoMD"
include(":app")
