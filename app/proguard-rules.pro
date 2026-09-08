# Keep generic signatures and annotations: Gson and Retrofit both read them at runtime.
-keepattributes Signature
-keepattributes Exceptions
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# ---------------------------------------------------------------------------
# LiteRT-LM
#
# The AAR ships no consumer rules of its own. Its JNI layer passes Kotlin
# config objects across the native boundary and reads their fields by name, so
# obfuscating them breaks inference at runtime rather than at build time. The
# library also uses kotlin-reflect to build tool descriptions.
# ---------------------------------------------------------------------------
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** {
    native <methods>;
}
-keep,allowobfuscation class kotlin.reflect.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ---------------------------------------------------------------------------
# Gson
#
# Anything deserialised by field name must keep its field names. This includes
# the engine state that crosses the LlmService process boundary as JSON.
# ---------------------------------------------------------------------------
-keep class com.aiassistant.data.model.** { *; }
-keep class com.aiassistant.domain.model.** { *; }
-keep class com.aiassistant.domain.llm.OnDeviceLlmEngine$EngineState { *; }
-keep class com.aiassistant.domain.llm.OnDeviceLlmEngine$ModelCapabilities { *; }
-keep class com.aiassistant.domain.llm.OnDeviceLlmSettings { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn sun.misc.**

# ---------------------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------------------
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn retrofit2.**

# ---------------------------------------------------------------------------
# OkHttp / Okio: optional TLS providers are absent at runtime.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# ---------------------------------------------------------------------------
# Room: keep the app's entities and generated DAO implementations.
# ---------------------------------------------------------------------------
-keep class com.aiassistant.data.database.** { *; }

# ---------------------------------------------------------------------------
# Rhino (code interpreter tool): resolves its own classes reflectively.
# ---------------------------------------------------------------------------
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ---------------------------------------------------------------------------
# Jsoup
# ---------------------------------------------------------------------------
-dontwarn org.jsoup.**
