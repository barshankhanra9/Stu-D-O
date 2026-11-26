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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Preserve CameraX classes and camera2 interop/extensions
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }
-keepclassmembers class * extends androidx.camera.core.impl.Config$Option { *; }

# Preserve ML Kit segmentation & Play Services base
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Preserve app UI, OEM adapters, portrait pipeline, and models
-keep class com.example.model.** { *; }
-keep class com.example.oem.** { *; }
-keep class com.example.portrait.** { *; }
-keep class com.example.ui.** { *; }
-keep class com.example.editor.** { *; }
-keep class com.example.viewmodel.** { *; }

