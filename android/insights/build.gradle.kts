import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.devstrike.henosis.insights"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
                it.systemProperty("insights.schema", rootProject.file("../schema/v1.json").absolutePath)
                // End-to-end test (skipped unless set): a running ingest and its ClickHouse.
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
    explicitApi()
    // Compile against an older language/API level and depend on an older stdlib so vendor SDKs on
    // older Kotlin can consume this library and are not forced to upgrade their stdlib.
    coreLibrariesVersion = "2.2.21"
    compilerOptions {
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}

dependencies {
    // Runtime: nothing beyond the Kotlin stdlib (CLAUDE.md rule 4 / section 3).
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.junit5)
    testImplementation(libs.org.json)
    testImplementation(libs.json.schema.validator)
}

// ---- Footprint gate (CI): AAR < 100 KB, and no runtime dependency except the Kotlin stdlib -------
val checkFootprint by tasks.registering {
    group = "verification"
    description = "Fails if the release AAR is 100 KB or more, or if it pulls in a third-party runtime dependency."
    dependsOn("assembleRelease")
    val maxAarBytes = 100 * 1024
    val allowedRuntimeGroups = setOf("org.jetbrains.kotlin", "org.jetbrains")
    val aar = layout.buildDirectory.file("outputs/aar/insights-release.aar")
    val runtimeRoot = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    inputs.file(aar)
    doLast {
        val size = aar.get().asFile.length()
        println("insights-release.aar: $size bytes (limit $maxAarBytes)")
        check(size < maxAarBytes) { "AAR is $size bytes; the limit is $maxAarBytes (CLAUDE.md rule 4)" }

        val seen = linkedSetOf<String>()
        val foreign = mutableListOf<String>()
        val pending = ArrayDeque(listOf(runtimeRoot.get()))
        val visited = mutableSetOf<Any>()
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            if (!visited.add(component.id)) continue
            component.moduleVersion?.takeIf { it.group.isNotEmpty() && component !== runtimeRoot.get() }?.let { mv ->
                val id = "${mv.group}:${mv.name}:${mv.version}"
                seen += id
                if (mv.group !in allowedRuntimeGroups) foreign += id
            }
            component.dependencies
                .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                .forEach { pending.addLast(it.selected) }
        }
        println("runtime dependencies: ${seen.joinToString()}")
        check(foreign.isEmpty()) { "Third-party runtime dependencies are not allowed: $foreign" }
    }
}

tasks.named("check") { dependsOn(checkFootprint) }
