plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.versions)
}

(
    "dependencyUpdates",
) {
    rejectVersionIf {
        candidate.version.contains("alpha", ignoreCase = true) ||
            candidate.version.contains("beta", ignoreCase = true) ||
            candidate.version.contains("rc", ignoreCase = true) ||
            candidate.version.contains("dev", ignoreCase = true)
    }
}
