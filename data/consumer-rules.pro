# Consumer ProGuard rules contributed by the :data module to its consumers.

# Keep Firestore DTOs (de/serialized reflectively by the Firestore SDK).
-keep class com.actionverite.live.data.remote.dto.** { *; }
-keepclassmembers class com.actionverite.live.data.remote.dto.** {
    <init>();
    <fields>;
}

# WebRTC native bindings.
-keep class org.webrtc.** { *; }
-keep class io.getstream.webrtc.** { *; }
