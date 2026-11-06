// No Kotlin plugin: AGP 9 compiles Kotlin itself. The Compose compiler plugin
// is separate machinery and is still required.
import java.util.Properties
import org.gradle.api.GradleException

// Release signing. Credentials live in keystore.properties at the repo root,
// which is gitignored -- the keystore itself must never enter git. When the
// file is absent the release build falls back to the debug key so a fresh
// clone still builds; `./gradlew :app:assembleRelease` on a machine without
// the properties file therefore produces a TEST-ONLY apk.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasReleaseKey = keystoreProps.getProperty("storeFile") != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    namespace = "rip.failed.daisy"
    compileSdk = 37

    defaultConfig {
        applicationId = "rip.failed.daisy"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Release signing comes from keystore.properties (gitignored).
            //
            // Switching from the debug key to a real one changes the
            // signature, which forces one uninstall -- that wipes feeds.json
            // and every downloaded article. Do it once, deliberately.
            //
            // Fail loudly rather than silently signing with the debug key.
            // The fallback used to apply here too, which meant a release apk
            // built without keystore.properties looked fine and carried a
            // certificate anyone can forge (password "android"). A build that
            // stops is cheaper than a build that ships the wrong signature.
            if (!hasReleaseKey) {
                throw GradleException(
                    "Release signing needs keystore.properties at the repo root " +
                        "(storeFile, storePassword, keyAlias, keyPassword). " +
                        "Use :app:assembleDebug for a debug build instead."
                )
            }
            signingConfig = signingConfigs.getByName("release")
            // R8 full mode is AGP's default. `proguard-android-optimize.txt`
            // turns on the optimisation passes as well as shrinking -- the
            // plain `proguard-android.txt` variant only strips dead code.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Debuggable release builds are refused by the Play uploader and
            // make R8 skip some optimisations, so this stays explicit.
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // kotlinOptions was removed in AGP 9 along with the Kotlin plugin. The
    // replacement is the kotlin { compilerOptions { } } DSL, applied below the
    // android block.

    buildFeatures {
        compose = true
    }

    // Keep the manifest's locale filters and the Compose runtime honest about
    // how large the app is allowed to get.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        // Must agree with compileOptions above. A mismatch compiles but
        // produces class files the D8 pass then rejects, which reads as an
        // unrelated error much later in the build.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Interface-only: OkHttpFetcher is the sole place this is referenced, so
    // swapping clients touches one file. 5.x needs API 21+; 4.12.0 is the
    // fallback if the toolchain in Termux is older.
    // OkHttp 5.5.x requires compileSdk 37, which this project now meets. That
    // floor is why the project moved to AGP 9 in the first place, so the 5.x
    // line is the natural home: it also keeps body non-null, which is what the
    // fetcher's decoding path is written against.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    // Discoverer.discover is a suspend function, so tests need runBlocking.
    // core publishes no Flow or Dispatchers, so this stays an implementation
    // detail rather than an API commitment.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.hamcrest:hamcrest-core:1.3")

    // Plain-JVM artifact: the -android one above carries an Android runtime
    // dependency and will not resolve on the unit-test classpath.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Compose. The BOM pins every artifact to one tested set, so the versions
    // below stay absent on purpose -- bumping one requires bumping the BOM.
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    // material-icons-core, not -extended. The bottom bar needs List, Add and
    // Settings; all three ship in core, which is a few hundred KB. Extended is
    // tens of MB and still the wrong trade. Refresh is gone from the UI
    // entirely -- pull-to-refresh replaced it -- so the glyph that core used to
    // lack is no longer needed.
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // collectAsStateWithLifecycle lives here, not in lifecycle-runtime-ktx.
    // Pinned to the same version as the viewmodel artifact above; the two are
    // released together and a split version is a quiet way to get a mismatch.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    // Coil 3, split in two on purpose: coil-core dropped network loading in
    // 3.0, so the network artifact is mandatory and the app is not functional
    // without it. AsyncImage("https://...") with only coil-compose loads
    // nothing and reports no error.
    //
    // The OkHttp variant rather than Ktor because Daisy already speaks OkHttp,
    // so image fetches share one client, one connection pool and one disk
    // cache with feed parsing. It does NOT mean the OkHttp version is shared
    // for free: coil-network-okhttp declares 4.12.0, Daisy pins 5.5.1, and the
    // exclude below is what stops that being two versions in the graph.
    //
    // 3.5.0, not the newest. Coil 3.6.0 moved to Compose Multiplatform 1.12.0,
    // which depends on androidx.compose.foundation 1.12.0 -- and 1.12.0
    // requires AGP 9.1.0+, so it cannot be used with this build's AGP 9.0.1.
    // 3.5.0 targets CMP 1.11.1 and resolves against a Compose vintage the BOM
    // still covers. The alternative was bumping AGP to satisfy a transitive
    // dependency, which would make the build's toolchain requirement a
    // function of what Coil ships.
    //
    // Excluding the org.jetbrains.compose group is NOT an option: coil's
    // compose artifact has no AndroidX Compose dependency of its own, so CMP
    // is the only Compose it links against. Removing it leaves AsyncImage
    // with nothing.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0") {
        // coil-network-okhttp declares OkHttp 4.12.0; Daisy pins 5.5.1. Gradle
        // would pick 5.5.1 anyway (higher wins), but leaving that implicit means
        // the resolved graph depends on ordering rather than on a decision.
        // Stating it here makes the mismatch visible at the point it is
        // introduced. Compiled-against-4 / running-on-5 is the usual supported
        // path for OkHttp, but it is not nothing, so it is declared not assumed.
        exclude(group = "com.squareup.okhttp3", module = "okhttp")
    }
}
