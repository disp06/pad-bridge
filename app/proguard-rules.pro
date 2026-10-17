# Release builds are not minified (see app/build.gradle).
# If you turn minification on, keep the hidden-API reflection targets working:
-keepclassmembers class android.bluetooth.** { *; }
