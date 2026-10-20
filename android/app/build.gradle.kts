import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Google Places key that finds the exact place a Maps link names. CI passes it as an
// environment variable; a local build can set PLACES_API_KEY in android/local.properties.
// Without it, shared places are looked up by name and marked approximate.
val placesApiKey = (
    System.getenv("PLACES_API_KEY")?.takeIf { it.isNotBlank() }
        ?: rootProject.file("local.properties").takeIf { it.exists() }
            ?.let { file -> Properties().apply { file.inputStream().use { load(it) } }.getProperty("PLACES_API_KEY") }
        ?: ""
    ).trim()
require(placesApiKey.matches(Regex("[A-Za-z0-9_-]*"))) { "PLACES_API_KEY has unexpected characters" }

android {
    namespace = "com.example.are_we_there_yet"
    compileSdk = flutter.compileSdkVersion
    buildToolsVersion = "36.0.0"
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.are_we_there_yet"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        buildConfigField("String", "PLACES_API_KEY", "\"$placesApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // CI supplies the release key (see .github/workflows/release.yml). Local
    // release builds without it fall back to the debug key.
    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
