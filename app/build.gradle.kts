// file: app/build.gradle.kts
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    // ---- Load local.properties once & tiny helpers ----
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    /**
     * Read a project property first (e.g., -Pgh.token=...), then fallback to local.properties.
     * This avoids committing secrets while allowing CI overrides.
     */
    fun prop(name: String, default: String = ""): String =
        (project.findProperty(name) as String?)
            ?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty(name)
                ?.takeIf { it.isNotBlank() }
            ?: default

    /** Escape a string for BuildConfig string fields. */
    fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ===== Single source of truth for appId (override via local.properties: appId=...) =====
    val appId = prop("appId", "com.negi.survey")

    namespace = appId
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Use AndroidX Test Runner (required for Orchestrator)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ---- Instrumentation args to reduce flakiness on Android 14+ and to avoid accidental sharding ----
        // clearPackageData: isolates app state between test invocations (safer with Orchestrator).
        testInstrumentationRunnerArguments["clearPackageData"] = "true"
        // useTestStorageService: scoped test storage instead of legacy external storage (A14+).
        testInstrumentationRunnerArguments["useTestStorageService"] = "true"
        // 🔒 Safety guard: explicitly disable sharding unless a CI overrides it.
        // Some CI/build scripts inject -e numShards=2 by default; this keeps a single run per device.
        testInstrumentationRunnerArguments["numShards"] = "1"
        // NOTE: A CI can still override this with: -Pandroid.testInstrumentationRunnerArguments.numShards=2
    }

    // Always run androidTest against the debug build (same applicationId)
    testBuildType = "debug"

    testOptions {
        // ✅ Enable Android Test Orchestrator (each test runs in its own Instrumentation instance)
        //    This drastically reduces flakiness caused by shared state between tests.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        // Optional but helps reduce UI flakiness during Espresso/UI tests
        animationsDisabled = true

        // If you need resources in local unit tests, uncomment:
        // unitTests.isIncludeAndroidResources = true
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        // Keep Java 17 toolchain consistent with Kotlin 'jvmTarget'
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        // Align with compileOptions; avoid mixed bytecode levels on CI
        jvmTarget = "17"
        // You can gradually enable stricter checks if needed:
        // freeCompilerArgs += listOf("-Xjvm-default=all", "-Xjsr305=strict")
    }

    buildTypes {
        debug {
            // NOTE: Do not use applicationIdSuffix; keep a stable package for MediaStore ownership.
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote("SurveyExports"))
            buildConfigField("String", "GH_BRANCH",      quote("main"))
            buildConfigField("String", "GH_PATH_PREFIX", quote(""))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "GH_OWNER",       quote(prop("gh.owner")))
            buildConfigField("String", "GH_REPO",        quote("SurveyExports"))
            buildConfigField("String", "GH_BRANCH",      quote("main"))
            buildConfigField("String", "GH_PATH_PREFIX", quote(""))
            buildConfigField("String", "GH_TOKEN",       quote(prop("gh.token")))
            buildConfigField("String", "HF_TOKEN",       quote(prop("HF_TOKEN")))
            // Use debug signing for convenience in CI/dev (OK for internal/testing)
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Broad META-INF excludes to avoid conflicts among OkHttp/Coroutines/Media3/MediaPipe, etc.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "META-INF/*.kotlin_module"
            )
            // If you hit duplicate license files with new libs, prefer excludes over pickFirst to avoid surprises.
        }
    }
}

dependencies {
    // ===== Compose BOM =====
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui.geometry)
    implementation(libs.androidx.compose.foundation.layout)
    androidTestImplementation(platform(libs.androidx.compose.bom))

    // Navigation 3 (Runtime/UI helpers)
    implementation(libs.nav3.runtime)
    implementation(libs.nav3.ui)

    // Kotlin / Coroutines / Serialization
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json) // (de-duplicated; keep only one)
    implementation(libs.kaml) // YAML via kotlinx.serialization

    // Core / AppCompat
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.runtime.saveable)

    // Debug/Preview-only
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // Activity / Navigation / Lifecycle
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3.android)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)

    // Media3
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // MediaPipe GenAI
    implementation(libs.mediapipe.tasks.text)
    implementation(libs.mediapipe.tasks.genai)

    // Accompanist
    implementation(libs.accompanist.navigation.animation)

    // SAF (androidTest uses DocumentFile)
    androidTestImplementation(libs.androidx.documentfile)

    // ===== Test libs =====
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))

    // AndroidX Test
    androidTestImplementation(libs.androidx.junit)            // androidx.test.ext:junit
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.mockito.android)

    // ✅ Orchestrator runner dependency (explicit).
    // Keep runner explicit to avoid subtle transitive version drifts across machines/CI.
    androidTestImplementation(libs.androidx.test.runner)
    // If your version catalog lacks the alias, a fallback literal works:
    // androidTestImplementation("androidx.test:runner:1.6.2")

    // ✅ Orchestrator itself (required when execution = ANDROIDX_TEST_ORCHESTRATOR)
    androidTestUtil(libs.androidx.test.orchestrator) // e.g., 1.5.1 or newer
}

/**
 * ---- Optional debug helpers (safe to keep in the build script) ----
 *
 * 1) Print the resolved instrumentation arguments that will be passed by default.
 *    Run: ./gradlew :app:printAndroidTestArgs
 *
 * 2) (Optional) Guard against multiple connected devices.
 *    Run: ./gradlew :app:checkSingleConnectedDevice
 *    It will fail if more than one device/emulator is connected.
 */
tasks.register("printAndroidTestArgs") {
    group = "verification"
    description = "Print resolved default instrumentation runner arguments."
    doLast {
        println("=== Default Instrumentation Args ===")
        val args = android.defaultConfig.testInstrumentationRunnerArguments
        args.forEach { (k, v) -> println(" - $k = $v") }
        println("===================================")
        println("Tip: CI can override via -Pandroid.testInstrumentationRunnerArguments.<key>=<value>")
    }
}

tasks.register("checkSingleConnectedDevice") {
    group = "verification"
    description = "Fails if more than one device is connected (helps avoid double runs)."
    doLast {
        // Use 'adb devices' to count connected, skip the header line.
        val process = ProcessBuilder("adb", "devices").redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val lines = out.lineSequence()
            .drop(1) // skip header
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("\tdevice") }
            .toList()
        println("Connected devices: ${lines.size}")
        lines.forEach { println(" - $it") }
        if (lines.size > 1) {
            throw GradleException(
                "More than one device/emulator is connected. " +
                        "Run `adb devices -l` and keep exactly one to avoid duplicate test runs."
            )
        }
    }
}
