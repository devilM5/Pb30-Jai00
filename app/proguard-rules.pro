# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve JNI class, native methods, and TokenCallback invoked from libfriday_llama.so
-keep class com.example.ai.AndroidLlamaNativeBridge {
    *;
}
-keepclassmembers class com.example.ai.AndroidLlamaNativeBridge$* {
    public boolean onToken(java.lang.String);
}
-keep interface com.example.ai.AndroidLlamaNativeBridge$TokenCallback {
    public boolean onToken(java.lang.String);
}
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

