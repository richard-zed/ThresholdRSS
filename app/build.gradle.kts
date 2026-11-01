plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.daisy"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.daisy"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

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

dependencies {
    // Interface-only: OkHttpFetcher is the sole place this is referenced, so
    // swapping clients touches one file. 5.x needs API 21+; 4.12.0 is the
    // fallback if the toolchain in Termux is older.
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
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
}
