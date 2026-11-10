plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val appName = "GooglebookAppRestorer"

android {
  namespace = "com.google.android.desktop.apprestorer"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.google.android.desktop.apprestorer"
    minSdk = 33
    targetSdk = 36
    versionCode = 120
    versionName = "1.2.1"
    base.archivesName.set(appName)
  }

  signingConfigs {
    // Use local persistent debug keystore when present so iterative installs upgrade in place,
    // while allowing clean CI runners to auto-generate one seamlessly.
    getByName("debug") {
      val localDebugKeystore = file("${System.getProperty("user.home")}/.android/debug.keystore")
      if (localDebugKeystore.exists()) {
        storeFile = localDebugKeystore
      }
    }
    val envKeystorePath = System.getenv("ANDROID_UPLOAD_KEYSTORE_PATH")
    if (!envKeystorePath.isNullOrBlank() && file(envKeystorePath).exists()) {
      create("upload") {
        storeFile = file(envKeystorePath)
        storePassword = System.getenv("ANDROID_UPLOAD_KEYSTORE_PASSWORD")
        keyAlias = System.getenv("ANDROID_UPLOAD_KEY_ALIAS")
        keyPassword = System.getenv("ANDROID_UPLOAD_KEY_PASSWORD")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    debug {
      enableUnitTestCoverage = project.hasProperty("coverage")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
      all {
        it.extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java)?.apply {
          isIncludeNoLocationClasses = true
          excludes = listOf("jdk.internal.*")
        }
      }
    }
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
  testImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.test.ext.junit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
}
