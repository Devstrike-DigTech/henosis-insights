import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

// A fictional vendor SDK ("Acme Payments") that embeds Henosis Insights the way a real vendor would.
plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.acme.payments"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        aarMetadata {
            minCompileSdk = 23
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    publishing {
        singleVariant("release")
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

kotlin {
    // Like the Insights client: readable by apps on Kotlin 2.2+ (React Native 0.87 uses 2.2.0).
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

publishing {
    publications {
        // Published to mavenLocal so the Flutter and React Native wrappers in examples/wrapped-sdk
        // consume it as a real AAR, the way a vendor's wrappers consume their native SDK.
        register<MavenPublication>("release") {
            groupId = "com.acme"
            artifactId = "payments"
            version = "4.2.0"
            afterEvaluate { from(components["release"]) }
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
