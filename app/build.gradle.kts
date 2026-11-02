import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Keys come from, in order: environment variables (CI), then local.properties (a developer machine).
// Nothing secret is committed. The Spotify client ID is public by design (PKCE flow, no client secret).
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun cfg(name: String, default: String = ""): String =
    System.getenv(name) ?: localProps.getProperty(name) ?: default

val keystorePath = cfg("MOTIF_KEYSTORE_PATH")
val hasReleaseKey = keystorePath.isNotBlank() && file(keystorePath).exists()

android {
    namespace = "com.kirkisham.motif"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kirkisham.motif"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.8"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"${cfg("SPOTIFY_CLIENT_ID", "9343a80fdf824a4e8c1b896312207ff0")}\"")
        buildConfigField("String", "SPOTIFY_REDIRECT_URI", "\"motif://callback\"")
        buildConfigField("String", "LASTFM_API_KEY", "\"${cfg("LASTFM_API_KEY")}\"")
        buildConfigField("String", "GENIUS_ACCESS_TOKEN", "\"${cfg("GENIUS_ACCESS_TOKEN")}\"")
        buildConfigField("String", "TICKETMASTER_API_KEY", "\"${cfg("TICKETMASTER_API_KEY")}\"")
        buildConfigField("String", "GETSONGBPM_API_KEY", "\"${cfg("GETSONGBPM_API_KEY")}\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = cfg("MOTIF_KEYSTORE_PASSWORD")
                keyAlias = cfg("MOTIF_KEY_ALIAS", "motif")
                keyPassword = cfg("MOTIF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.browser:browser:1.8.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Spotify App Remote (playback control through the installed Spotify app).
    // CI downloads the AAR from github.com/spotify/android-sdk into app/libs; a developer machine does the same by hand.
    // Kept optional so the project compiles before the AAR is present.
    if (file("libs").exists() && file("libs").listFiles()?.any { it.name.endsWith(".aar") } == true) {
        implementation(fileTree("libs") { include("*.aar") })
        implementation("com.google.code.gson:gson:2.11.0") // required by App Remote
    }
}
