plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appEnv = providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText.orNull
    ?.lineSequence()
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
    ?.associate { line ->
        val (key, value) = line.split('=', limit = 2)
        key.trim() to value.trim().removeSurrounding("\"").removeSurrounding("'")
    } ?: emptyMap()
fun envString(name: String): String = "\"${appEnv[name].orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.frop.montash"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.frop.montash"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"

        buildConfigField("String", "MONTASH_GLOBAL_API_URL", envString("MONTASH_GLOBAL_API_URL"))
        buildConfigField("String", "MONTASH_LOCAL_API_URL", envString("MONTASH_LOCAL_API_URL"))
        buildConfigField("String", "MONTASH_LOCAL_WIFI_SSID", envString("MONTASH_LOCAL_WIFI_SSID"))
        buildConfigField("String", "MONTASH_CLIENT_API_TOKEN", envString("MONTASH_CLIENT_API_TOKEN"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
