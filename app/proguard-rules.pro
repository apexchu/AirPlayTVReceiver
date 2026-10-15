# Keep JNI callback interface (called from native code)
-keep class io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class * implements io.github.jqssun.airplay.bridge.RaopCallbackHandler { *; }
-keep class io.github.jqssun.airplay.bridge.LogListener { *; }
-keep class * implements io.github.jqssun.airplay.bridge.LogListener { *; }

# Keep NativeBridge native methods
-keep class io.github.jqssun.airplay.bridge.NativeBridge { *; }

# Keep Cling UPnP & Seamless
-keep class org.fourthline.cling.** { *; }
-keep interface org.fourthline.cling.** { *; }
-keep class org.seamless.** { *; }
-keep interface org.seamless.** { *; }
-keepclassmembers class * {
    @org.fourthline.cling.binding.annotations.* <methods>;
    @org.fourthline.cling.binding.annotations.* <fields>;
}

# Keep Jetty 8 for Cling StreamServer
-keep class org.eclipse.jetty.** { *; }
-dontwarn org.eclipse.jetty.**
-keep class javax.servlet.** { *; }
-dontwarn javax.servlet.**

