// Macrobenchmarks and Baseline Profile generation (docs/PERFORMANCE_AND_POWER.md §9).
// Runs against the app's "benchmark" build type: optimized, non-debuggable, debug-signed.
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.maogig.gigreader.benchmark"
    compileSdk { version = release(37) }

    defaultConfig {
        // Baseline Profile generation needs API 28+ (rooted) or 33+; Macrobenchmark needs 23+.
        minSdk { version = release(28) }
        targetSdk { version = release(36) }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

androidComponents {
    // Only the "benchmark" variant makes sense for a macrobenchmark module.
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
}
