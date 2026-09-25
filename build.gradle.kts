// Plugins are declared here once (apply false) so every module shares one classloader and one
// version of each plugin. The Kotlin JVM plugin pins the Kotlin Gradle Plugin version that AGP's
// built-in Kotlin support uses.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
