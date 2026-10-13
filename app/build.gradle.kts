import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prop(name: String, default: String): String =
    (localProperties.getProperty(name) ?: default).trim().trimEnd('/')

fun pathProp(name: String, default: String): String =
    prop(name, default).let { if (it.isBlank()) "" else "$it/" }

android {
    namespace = "ai.anywear.mvp"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.kabuda.mvp"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-kabuda"

        buildConfigField("String", "API_BASE_URL", "\"${pathProp("API_BASE_URL", "")}\"")
        buildConfigField("String", "REALTIME_SIGNALING_URL", "\"${localProperties.getProperty("REALTIME_SIGNALING_URL", "wss://api.decart.ai")}\"")
        buildConfigField("String", "DEFAULT_MODEL_NAME", "\"${localProperties.getProperty("DEFAULT_MODEL_NAME", "lucy-vton-latest")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("com.github.DecartAI:decart-android:0.8.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
}
