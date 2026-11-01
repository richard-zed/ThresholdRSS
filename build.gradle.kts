// Plugin versions live here rather than in settings' pluginManagement so they
// are visible in one place.
//
// AGP 9 changes two things worth knowing before editing this file:
//
// 1. Kotlin support is now BUILT IN. org.jetbrains.kotlin.android is gone --
//    applying it alongside AGP 9 is the compatibility problem it was meant to
//    solve. The app module therefore declares no Kotlin plugin at all.
//
// 2. The Compose compiler plugin is still applied separately, and still has to
//    match the Kotlin version the compiler reports. AGP 9's bundled Kotlin is
//    2.4.x, so the plugin below is pinned to the same line.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
