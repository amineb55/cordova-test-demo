# Keep Crashlytics line numbers for readable stack traces.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*

# Kotlinx Serialization: keep generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.actionverite.live.**$$serializer { *; }

# Domain models are serialized to/from Firestore via reflection — keep them.
-keep class com.actionverite.live.data.remote.dto.** { *; }

# WebRTC (Stream) uses JNI; keep native-bound classes.
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }

# Hilt / Dagger generated code is handled by their own consumer rules.
