plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.esp32controller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.esp32controller"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation("org.jetbrains.kotlin:kotlin-stdlib")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.github.mik3y:usb-serial-for-android:3.6.0")  // For USB serial

    implementation("androidx.constraintlayout:constraintlayout:2.1.4")



    implementation("io.github.webrtc-sdk:android:137.7151.05")

    // CameraX
    implementation("androidx.camera:camera-core:1.3.3")
    implementation("androidx.camera:camera-camera2:1.3.3")
    implementation("androidx.camera:camera-lifecycle:1.3.3")
    implementation("androidx.camera:camera-view:1.3.3")

    // TensorFlow Lite Vision Tasks
    implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")
    // (Optional: AndroidX appcompat/material if not already there)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")

    // other dependencies


    implementation("org.tensorflow:tensorflow-lite-gpu:2.12.0")

    implementation ("org.tensorflow:tensorflow-lite:2.14.0") // Core TFLite
    implementation ("org.tensorflow:tensorflow-lite-support:0.4.3") // Helper utils
    implementation ("org.tensorflow:tensorflow-lite-task-vision:0.4.0") // Object detection task API
    implementation("org.tensorflow:tensorflow-lite-gpu:2.12.0")

    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")


    implementation("com.squareup.okhttp3:okhttp:4.12.0")




}