# R8 full mode. Most keep rules come from the libraries' consumer rules
# (Hilt, Room, Compose, kotlinx-serialization all ship their own).

# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepclassmembers,allowobfuscation class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# The rclone binary is exec'd by path (lib/<abi>/librclone.so), not loaded via
# System.loadLibrary, so no JNI keep rules are required. R8 does not touch
# native artifacts. This comment documents that intentional absence.
