# R8 configuration, release builds only.
#
# The app itself uses no reflection -- JSON is hand-rolled in core/Json.kt and
# there is no kotlinx.serialization -- so nothing of ours needs a keep rule.
# Everything below exists because a THIRD-PARTY library reflects or is loaded
# by name, and the failure mode is silent: a stripped class does not crash,
# it just stops appearing.

# ---------------------------------------------------------------------------
# Coil 3
#
# Neither coil-compose nor coil-network-okhttp ships consumer ProGuard rules.
# Without these, AsyncImage simply renders nothing -- no exception, no log --
# which looks like "the network is broken" rather than "R8 removed a class".
#
# What has to survive: ImageLoader, ImageRequest, the component registry and
# whatever the decoders reach. All of it falls under one blanket keep below.
# ---------------------------------------------------------------------------
-keep class coil3.** { *; }
-keep interface coil3.** { *; }
-dontwarn coil3.**

# NOTE on scope: the blanket `coil3.**` keep above is deliberately wide and
# makes the narrower rules that people normally copy from Coil's docs
# (ImageLoader companion, coil3.decode, the component registry) redundant.
# Kept wide on purpose: Coil 3 documents no consumer rules of its own, its
# internal reachability through the registry is not something we can reason
# about safely, and the cost is a few hundred KB of image-loading code against
# a silent, no-exception failure mode. Narrow this only with a device test.

# ---------------------------------------------------------------------------
# OkHttp 5
#
# OkHttp ships consumer rules, but R8 still needs to be told the optional
# platform integrations are absent rather than missing. Without these the
# build fails on unresolved references to Conscrypt / BouncyCastle / OpenJSSE
# rather than shipping.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp's internal Platform class is chosen by name at class-init time.
-keep class okhttp3.internal.platform.** { *; }
-keep class okio.** { *; }

# ---------------------------------------------------------------------------
# Kotlin
#
# Kotlin metadata and coroutine intrinsics. Missing metadata does not break
# execution but degrades stack traces and can break libraries that read it.
# ---------------------------------------------------------------------------
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# Compose
#
# Nothing added. AGP contributes Compose's own consumer rules, and Compose is
# reflection-free in the path this app uses. A blanket `-dontwarn androidx.**
# would suppress genuine missing-reference warnings rather than fix them, so
# if the release build complains about Compose the answer is a specific rule,
# not that line.
# ---------------------------------------------------------------------------
