// Plugin versions live here rather than in settings' pluginManagement so they
// are visible in one place. Kotlin 2.x requires the Compose compiler plugin to
// match the Kotlin version exactly -- they are released in lockstep, and a
// mismatch is a build failure with a message that names neither number.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("com.android.library") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
