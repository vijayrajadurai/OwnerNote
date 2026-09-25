# Owner Note — R8 / ProGuard
# Obfuscate and shrink release builds. Keep reflection, JNI, and JSON contracts.

# Crashlytics readable stacks (mapping file is uploaded by the Crashlytics plugin)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keep public class * extends java.lang.Exception
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Android components (also referenced from the manifest)
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep class com.shopai.app.ShopAiApplication { *; }
-keep class com.shopai.app.MainActivity { *; }
-keep class com.shopai.app.auth.RecaptchaFallbackActivity { *; }
-keep class com.shopai.app.push.ShopAiFirebaseMessagingService { *; }

# Gson: field names are the JSON contract (no @SerializedName on models)
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn sun.misc.**
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.shopai.app.data.model.** { *; }
-keep class com.shopai.app.data.tts.TtsRequest { *; }
-keep class com.shopai.app.data.tts.TtsResponse { *; }
-keepclassmembers class com.shopai.app.data.model.** { <fields>; }
-keepclassmembers enum * { *; }

# Retrofit + Kotlin suspend
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep interface com.shopai.app.data.api.ShopAiApi { *; }
-keep interface com.shopai.app.data.tts.TtsProxyApi { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*

# OkHttp / Okio (consumer rules exist; extra OEM warnings)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin / coroutines
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlinx.coroutines.internal.SynchronizedKt {
    void notify*(java.lang.Object);
}

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
-keep class com.shopai.app.data.local.room.** { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# Firebase Auth, Messaging, Analytics, Crashlytics
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-keep class com.google.android.play.core.** { *; }
-keep class com.google.android.play.integrity.** { *; }

# Play Integrity / SMS User Consent
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }

# Tesseract JNI
-keep class cz.adaptech.tesseract4android.** { *; }
-keepclassmembers class * {
    native <methods>;
}

# WebView recaptcha fallback
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Compose / Navigation: library consumer rules apply. Keep R8 from stripping
# composable lambda classes used via reflection on some devices.
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# Serialization helpers used by Kotlin
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class **$DefaultImpls {
    <methods>;
}
