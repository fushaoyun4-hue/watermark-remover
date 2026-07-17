# Add project specific ProGuard rules here.

# OpenCV
-keep class org.opencv.** { *; }

# FFmpegKit
-keep class com.arthenica.ffmpegkit.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep model classes
-keep class com.watermarkremover.inference.** { *; }
