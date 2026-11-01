plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
}

dependencies {
    // Interface-only: OkHttpFetcher is the sole place this is referenced, so
    // swapping clients touches one file. 5.x needs API 21+; 4.12.0 is the
    // fallback if the toolchain in Termux is older.
    implementation("com.squareup.okhttp3:okhttp:5.5.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.hamcrest:hamcrest-core:1.3")
}
