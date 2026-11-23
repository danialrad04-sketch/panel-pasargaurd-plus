# Keep Kotlinx Serialization generated serializers and DTOs
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pasarguard.vpn.**$$serializer { *; }
-keepclassmembers class com.pasarguard.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class com.pasarguard.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.pasarguard.vpn.widget.PasarGuardWidgetProvider { *; }
-dontwarn com.google.zxing.**

# Keep Retrofit & OkHttp
-keepattributes Signature, Exceptions
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Keep Room entities and DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep Xray-core (AndroidLibXrayLite) & sing-box libbox JNI/Go bindings intact
-keep class libv2ray.** { *; }
-keep interface libv2ray.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep interface io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**

# Keep Android Security Crypto / Tink
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Strip verbose debug logs in release builds
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
