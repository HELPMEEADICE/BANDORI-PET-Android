plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedAssetsDir = layout.buildDirectory.dir("generated/assets/root")
val generatedIconDir = layout.buildDirectory.dir("generated/res/rootIcon")

val syncRootAssets by tasks.registering(Sync::class) {
    into(generatedAssetsDir)

    from(rootProject.file("outfit.json")) {
        into("")
    }
    from(rootProject.file("models")) {
        into("models")
    }
    from(rootProject.file("third_party/Live2D-v2-Lua")) {
        into("live2d-lua")
        exclude("**/__pycache__/**", "**/*.pyc", ".git/**")
    }
}

val copyLauncherIcon by tasks.registering(Copy::class) {
    from(rootProject.file("icon.png"))
    into(generatedIconDir.map { it.dir("mipmap-nodpi") })
    rename { "ic_launcher.png" }
}

android {
    namespace = "com.bandori.pet.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bandori.pet.android"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    sourceSets["main"].assets.srcDir(generatedAssetsDir)
    sourceSets["main"].res.srcDir(generatedIconDir)

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}

tasks.named("preBuild") {
    dependsOn(syncRootAssets, copyLauncherIcon)
}
