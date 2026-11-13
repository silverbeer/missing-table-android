import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing keystore path — set by CI (decoded from a base64 secret).
// When absent (local dev), release builds fall back to the debug key so
// assembleRelease still works without any secrets.
val releaseKeystore: String? = System.getenv("ANDROID_KEYSTORE_FILE")

// Firebase client config for push (SB-1464), per flavor. Read from an optional,
// gitignored app/src/<flavor>/google-services.json (scripts/fetch-google-services.sh
// writes it; CI decodes it from a secret), falling back to FIREBASE_<FLAVOR>_*
// env vars. No google-services plugin: these become BuildConfig fields and
// MtApp initialises FirebaseApp by hand. Absent everywhere -> "" and push is
// simply off, so the build never needs the file.
data class FirebaseConfig(val appId: String, val apiKey: String, val projectId: String, val senderId: String)

fun firebaseConfig(flavor: String, packageName: String): FirebaseConfig {
    val env = { key: String -> System.getenv("FIREBASE_${flavor.uppercase()}_$key").orEmpty() }
    val fromEnv = FirebaseConfig(env("APP_ID"), env("API_KEY"), env("PROJECT_ID"), env("SENDER_ID"))
    val json = file("src/$flavor/google-services.json")
    if (!json.exists()) return fromEnv
    @Suppress("UNCHECKED_CAST")
    val root = JsonSlurper().parse(json) as Map<String, Any?>
    val project = root["project_info"] as? Map<String, Any?> ?: emptyMap()
    @Suppress("UNCHECKED_CAST")
    val client = (root["client"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { c ->
        val info = c["client_info"] as? Map<*, *>
        (info?.get("android_client_info") as? Map<*, *>)?.get("package_name") == packageName
    } ?: return fromEnv
    val apiKey = ((client["api_key"] as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("current_key")
    return FirebaseConfig(
        appId = (client["client_info"] as? Map<*, *>)?.get("mobilesdk_app_id")?.toString().orEmpty(),
        apiKey = apiKey?.toString().orEmpty(),
        projectId = project["project_id"]?.toString().orEmpty(),
        senderId = project["project_number"]?.toString().orEmpty(),
    )
}

fun com.android.build.api.dsl.VariantDimension.firebaseFields(cfg: FirebaseConfig) {
    buildConfigField("String", "FIREBASE_APP_ID", "\"${cfg.appId}\"")
    buildConfigField("String", "FIREBASE_API_KEY", "\"${cfg.apiKey}\"")
    buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${cfg.projectId}\"")
    buildConfigField("String", "FIREBASE_SENDER_ID", "\"${cfg.senderId}\"")
}

android {
    namespace = "com.missingtable.scorer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.missingtable.scorer"
        minSdk = 29
        targetSdk = 36
        // CI passes the workflow run number so each build supersedes the last
        // on-device; local builds stay at 1.
        versionCode = System.getenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "0.2.26"
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore != null) {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign with the real release keystore when CI provides it; otherwise
            // fall back to the debug key so a local assembleRelease still builds.
            signingConfig = if (releaseKeystore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("local") {
            dimension = "env"
            applicationIdSuffix = ".local"
            // 10.0.2.2 = host loopback from the Android emulator
            buildConfigField("String", "BASE_URL", "\"http://10.0.2.2:8000\"")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            firebaseFields(firebaseConfig("local", "com.missingtable.scorer.local"))
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "BASE_URL", "\"https://api.missingtable.com\"")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            firebaseFields(firebaseConfig("prod", "com.missingtable.scorer"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            // Robolectric (SyncEngine tests run Room on the JVM)
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    // Club logos on the scoreboard and match list (SB-651)
    implementation(libs.coil.compose)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.lifecycle.process)
    // Follow-team push (SB-1464)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.installations)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
