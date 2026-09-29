plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.maogig.gigreader.core.pdf"
    compileSdk { version = release(37) }

    defaultConfig {
        minSdk { version = release(26) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    // Kotlin's jvmTarget follows this (AGP built-in Kotlin).
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:common"))
    implementation(libs.androidx.core)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // MuPDF (AGPL-3.0; docs/PDF_ENGINE_COMPARISON.md). Ships the native libraries for all four ABIs.
    implementation(libs.mupdf.fitz)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
