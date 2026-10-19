import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

// Release signing secrets live in <repo>/keystore.properties (git-ignored, never committed).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.example.tiktoktowhatsappstickers"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.tiktoktowhatsappstickers"
        minSdk = 24
        targetSdk = 36
        // Bump BOTH on every release. update.json (repo root) must carry the same versionCode.
        versionCode = 2
        versionName = "1.1.0"

        val authority = "com.example.tiktoktowhatsappstickers.stickercontentprovider"
        manifestPlaceholders["contentProviderAuthority"] = authority
        buildConfigField("String", "CONTENT_PROVIDER_AUTHORITY", "\"$authority\"")
        // Where installed apps look for new versions (served by GitHub, no server needed).
        buildConfigField(
            "String", "UPDATE_MANIFEST_URL",
            "\"https://raw.githubusercontent.com/Just4Skii/StickerBridge/main/update.json\""
        )
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.containsKey("storeFile")) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = true
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // TikTok to WhatsApp Sticker Engine dependencies
  implementation(libs.okhttp)
  implementation(libs.coil.compose)
  implementation(libs.coil.gif)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.documentfile)

  // Background update checks (notification when a new version is published)
  implementation(libs.androidx.work.runtime.ktx)

  // Shizuku: the only no-root way to read TikTok's cache on Android 13+
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)
}
