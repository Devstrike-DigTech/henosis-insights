// AGP 9 compiles Kotlin itself ("built-in Kotlin"). Putting the Kotlin Gradle plugin on the build
// classpath pins the compiler to the current stable Kotlin instead of the version AGP bundles.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

allprojects {
    group = "com.devstrike.henosis"
    version = providers.gradleProperty("insightsVersion").get()
}
