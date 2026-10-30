# ProGuard rules for SMS Forwarder
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Keep WebSocket classes
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Keep Gson models
-keep class com.smsforwarder.** { *; }

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.content.BroadcastReceiver