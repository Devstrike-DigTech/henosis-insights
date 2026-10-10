// A fictional host app that uses the Acme Payments SDK. It knows nothing about Insights.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.shop"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.shop"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "12.3.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":sample-sdk"))
}
