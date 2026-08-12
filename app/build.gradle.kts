import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseStorePath = providers.gradleProperty("BANDORI_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.gradleProperty("BANDORI_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("BANDORI_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("BANDORI_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.bandori.pet"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bandori.pet"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-Wall", "-Wextra")
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    val generatedAssetsDir = layout.buildDirectory.dir("generated/live2dAssets")
    val generatedResDir = layout.buildDirectory.dir("generated/iconRes")

    sourceSets["main"].assets.srcDir(generatedAssetsDir)
    sourceSets["main"].res.srcDir(generatedResDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "darwin/**",
                "win/**",
                "linux/**",
                "aix/**",
                "freebsd/**",
            )
        }
    }
}

val syncLive2DAssets by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/live2dAssets"))
    from(rootProject.file("band.json"))
    from(rootProject.file("outfit.json"))
    from(rootProject.file("band_logo")) { into("band_logo") }
    from(rootProject.file("models")) { into("models") }
    from(rootProject.file("lang")) { into("lang") }
    from(rootProject.file("prompt.json"))
    from(rootProject.file("characters")) { into("characters") }
    from(rootProject.file("third_party/Live2D-v2-Lua")) {
        into("third_party/Live2D-v2-Lua")
        exclude("**/.git/**")
        exclude("**/venv/**")
        exclude("**/frames_output/**")
        exclude("**/*.md")
        exclude("**/*.png")
        exclude("**/tests/**")
        exclude("**/test-data/**")
        exclude("**/resources/**")
    }
}

val syncAppIcon by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/iconRes/mipmap-xxxhdpi"))
    from(rootProject.file("icon.png")) {
        rename { "ic_launcher.png" }
    }
}

tasks.named("preBuild") {
    dependsOn(syncLive2DAssets, syncAppIcon)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.github.luben:zstd-jni:1.5.6-9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
