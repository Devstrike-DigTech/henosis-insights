import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.util.Properties
import java.util.zip.ZipFile

/** The newest compileSdk an app or SDK must use to consume this AAR. */
val MIN_COMPILE_SDK_FOR_CONSUMERS = 23

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.devstrike.henosis.insights"
    compileSdk = 37

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
        // AGP otherwise stamps compileSdk (37) into the AAR as the minimum every consumer must
        // compile against. The client only uses APIs from Android 6, so ask for nothing newer.
        aarMetadata {
            minCompileSdk = MIN_COMPILE_SDK_FOR_CONSUMERS
        }
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
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

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.devstrike.henosis"
            artifactId = "insights"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("Henosis Insights for Android")
                description.set("Integration telemetry for SDK vendors, with no end-user data.")
                url.set("https://github.com/Devstrike-DigTech/henosis-insights")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
}

// ---- Footprint gate (CI): AAR < 100 KB, and no runtime dependency except the Kotlin stdlib -------
val checkFootprint = tasks.register("checkFootprint") {
    group = "verification"
    description = "Fails if the release AAR is 100 KB or more, or if it pulls in a third-party runtime dependency."
    dependsOn("assembleRelease")
    val maxAarBytes = 100 * 1024
    val maxConsumerCompileSdk = MIN_COMPILE_SDK_FOR_CONSUMERS
    val allowedRuntimeGroups = setOf("org.jetbrains.kotlin", "org.jetbrains")
    val aar = layout.buildDirectory.file("outputs/aar/insights-release.aar")
    val runtimeRoot = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    inputs.file(aar)
    doLast {
        val file = aar.get().asFile
        val size = file.length()
        println("insights-release.aar: $size bytes (limit $maxAarBytes)")
        check(size < maxAarBytes) { "AAR is $size bytes; the limit is $maxAarBytes (CLAUDE.md rule 4)" }

        // Consumers must not be forced onto a new compileSdk just to use Insights.
        val metadata = ZipFile(file).use { zip ->
            val entry = zip.getEntry("META-INF/com/android/build/gradle/aar-metadata.properties")
            Properties().apply { zip.getInputStream(entry).use { load(it) } }
        }
        val minCompile = metadata.getProperty("minCompileSdk")?.toIntOrNull() ?: 1
        println("minCompileSdk for consumers: $minCompile (must be <= $maxConsumerCompileSdk)")
        check(minCompile <= maxConsumerCompileSdk) { "AAR requires consumers to compile against API $minCompile" }

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
