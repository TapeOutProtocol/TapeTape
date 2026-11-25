-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-dontwarn java.awt.**
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
