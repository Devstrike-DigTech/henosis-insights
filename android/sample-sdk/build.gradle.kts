// A fictional vendor SDK ("Acme Payments") that embeds Henosis Insights the way a real vendor would.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.acme.payments"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                it.systemProperty("junit.platform.launcher.interceptors.enabled", "true")
                listOf(
                    "INSIGHTS_E2E_ENDPOINT",
                    "INSIGHTS_E2E_WRITE_KEY",
                    "INSIGHTS_E2E_CLICKHOUSE_URL",
                    "INSIGHTS_E2E_CLICKHOUSE_PASSWORD",
                    "INSIGHTS_E2E_CLICKHOUSE_DATABASE",
                ).forEach { name -> System.getenv(name)?.let { value -> it.environment(name, value) } }
            }
        }
    }
}

dependencies {
    implementation(project(":insights"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.junit5)
}
