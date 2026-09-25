plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.maogig.gigreader.core.data"
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
    api(project(":core:model"))
    api(project(":core:common"))
    api(project(":core:database"))
    api(project(":core:pdf"))
    implementation(libs.androidx.core)
    api(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
