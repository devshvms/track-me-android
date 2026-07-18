import java.util.Properties
import java.io.FileInputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.services)
  alias(libs.plugins.ksp)
  alias(libs.plugins.crashlytics)
}

android {
    namespace = "in.shvms.trackme"
    compileSdk = 36
    testBuildType = "release"
    
    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { stream -> 
            localProperties.load(stream) 
        }
    }
    val mapsApiKey = localProperties.getProperty("MAPS_API_KEY") ?: ""
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() } ?: localProperties.getProperty("KEYSTORE_PASSWORD").takeIf { !it.isNullOrBlank() }
    val keyAlias = System.getenv("KEY_ALIAS").takeIf { !it.isNullOrBlank() } ?: localProperties.getProperty("KEY_ALIAS").takeIf { !it.isNullOrBlank() }
    val keyPassword = System.getenv("KEY_PASSWORD").takeIf { !it.isNullOrBlank() } ?: localProperties.getProperty("KEY_PASSWORD").takeIf { !it.isNullOrBlank() }
    val posthogApiKey = System.getenv("POSTHOG_API_KEY").takeIf { !it.isNullOrBlank() } ?: localProperties.getProperty("POSTHOG_API_KEY") ?: "dummy_key"
    // Opt-in StrictMode: ./gradlew ... -PstrictMode (or -PstrictMode=true); off by default.
    val strictModeEnabled = project.findProperty("strictMode")?.toString()
        ?.let { it.isEmpty() || it.equals("true", ignoreCase = true) } ?: false

    defaultConfig {
        applicationId = "in.shvms.trackme"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = (System.getenv("VERSION_CODE") ?: "20").toInt()
        versionName = "1.5.9"
        
        resValue("string", "google_maps_key", mapsApiKey)
        buildConfigField("String", "POSTHOG_API_KEY", "\"$posthogApiKey\"")
        buildConfigField("boolean", "STRICT_MODE", strictModeEnabled.toString())
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            
            val isReleaseTask = gradle.startParameter.taskRequests.any { request ->
                request.args.any { arg -> arg.contains("Release", ignoreCase = true) }
            }
            if (isReleaseTask && (keystorePassword.isNullOrBlank() || keyAlias.isNullOrBlank() || keyPassword.isNullOrBlank())) {
                throw GradleException("Signing configuration for 'release' build type is incomplete. Please define KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD as environment variables or in local.properties.")
            }
            
            storePassword = keystorePassword ?: ""
            this.keyAlias = keyAlias ?: ""
            this.keyPassword = keyPassword ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
      resValues = true
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
  coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
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
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  
  // Analytics
  implementation(libs.posthog)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation("androidx.navigation:navigation-compose:2.8.4")

  // Room
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)
  
  // Hilt

  // Google Maps & Location
  implementation(libs.maps.compose)
  implementation(libs.play.services.maps)
  implementation(libs.play.services.location)
  implementation(libs.credentials)
  implementation(libs.credentials.play.services.auth)
  implementation(libs.googleid)

  // Vico Charts
  implementation(libs.vico.compose)
  implementation(libs.vico.compose.m3)
  implementation(libs.vico.core)

  // Coil & Map Utils
  implementation(libs.coil.compose)
  implementation(libs.maps.utils)

  // Firebase
  implementation(platform(libs.firebase.bom))
  implementation(libs.firebase.auth)
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.crashlytics)

  // WorkManager (Background Scheduled Sync)
  implementation("androidx.work:work-runtime-ktx:2.9.1")
}
