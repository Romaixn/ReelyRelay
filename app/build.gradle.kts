import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

// A build secret: local.properties first (your machine), then an environment
// variable of the same name (CI - see .github/workflows/release.yml). Null
// when neither has it.
fun secret(name: String): String? =
    localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.reely.relay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.reely.relay"
        // 24 covers essentially every Google TV / Android TV / Fire TV device in
        // the field. The wizard default of 35 would exclude almost all of them.
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        // Read from local.properties (gitignored, not versioned), or from
        // the TMDB_API_KEY environment variable (CI). This is the shared
        // default used when a user hasn't entered their own TMDB key in
        // Settings (see Preferences.getEffectiveTmdbApiKey) - get a free
        // key at https://www.themoviedb.org/settings/api
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${secret("TMDB_API_KEY") ?: ""}\""
        )
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = secret("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = secret("RELEASE_STORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Only sign with the release config if a keystore is configured;
            // otherwise leave the build unsigned instead of failing, so
            // `assembleRelease` still works on fresh checkouts. (An unsigned
            // APK can't be installed - use assembleDebug for a quick build.)
            if (secret("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest to inflate
            // the real screens.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.leanback)
    implementation(libs.okhttp)
    // Core artifact only (QR generation). The full zxing-android-embedded
    // library adds a camera-based scanner we don't need.
    implementation(libs.zxing.core)
    // Bundled/offline ML Kit Text Recognition (Latin script) - no Google
    // Play Services dependency, no API key, ~4MB. Used by the OCR capture
    // fallback (OcrCaptureManager) for the "Top picks for you" cards that
    // carry no accessible text at all - a MediaProjection-based workaround,
    // since a sideloaded AccessibilityService can't retrieve window content
    // or take a screenshot on this class of device (see RelayAccessibilityService).
    implementation(libs.mlkit.text.recognition)
    // registerForActivityResult()/ComponentActivity for OcrConsentActivity
    // - see the version catalog comment for why this can't be assumed
    // already resolvable via leanback's transitive fragment dependency.
    implementation(libs.androidx.activity)
    // FileProvider, for the update-download install flow (ApkInstaller) -
    // see the version catalog comment.
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
