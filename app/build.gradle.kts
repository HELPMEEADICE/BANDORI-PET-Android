import org.gradle.api.tasks.Exec
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

    val generatedAssetsDir = layout.buildDirectory.dir("generated/live2dAssets")
    val generatedResDir = layout.buildDirectory.dir("generated/iconRes")
    val generatedRustJniDir = layout.buildDirectory.dir("generated/rustJniLibs")

    sourceSets["main"].assets.srcDir(generatedAssetsDir)
    sourceSets["main"].res.srcDir(generatedResDir)
    sourceSets["debug"].jniLibs.srcDir(generatedRustJniDir.map { it.dir("debug") })
    sourceSets["release"].jniLibs.srcDir(generatedRustJniDir.map { it.dir("release") })

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

val rustProjectDir = layout.projectDirectory.dir("src/main/rust")

fun registerRustBuild(variant: String, release: Boolean) = tasks.register<Exec>(
    "buildRust${variant.replaceFirstChar { it.uppercaseChar() }}",
) {
    group = "build"
    description = "Builds the $variant Rust JNI library with cargo-ndk."
    workingDir(rustProjectDir)

    val outputDir = layout.buildDirectory.dir("generated/rustJniLibs/$variant")
    val cargoTargetDir = layout.buildDirectory.dir("rust-target/$variant")
    inputs.files(fileTree(rustProjectDir) {
        include("Cargo.toml", "Cargo.lock", ".cargo/**", "src/**")
    })
    outputs.dir(outputDir)
    environment("CARGO_TARGET_DIR", cargoTargetDir.get().asFile.absolutePath)

    val cargoArguments = mutableListOf(
        "cargo",
        "ndk",
        "-t",
        "arm64-v8a",
        "-p",
        "26",
        "-o",
        outputDir.get().asFile.absolutePath,
        "build",
        "--manifest-path",
        rustProjectDir.file("Cargo.toml").asFile.absolutePath,
    )
    if (release) cargoArguments += "--release"
    commandLine(*cargoArguments.toTypedArray())
}

val buildRustDebug = registerRustBuild("debug", release = false)
val buildRustRelease = registerRustBuild("release", release = true)

tasks.named("preDebugBuild") {
    dependsOn(buildRustDebug)
}

tasks.named("preReleaseBuild") {
    dependsOn(buildRustRelease)
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val cameraXVersion = "1.5.3"
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
