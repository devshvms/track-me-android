# Google Play Services & Maps Keep Rules
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.maps.**
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.common.**

# Room Database Keep Rules
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Dao
-dontwarn androidx.room.**

# Kotlin Serialization Keep Rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keep class kotlinx.serialization.json.** { *; }
-dontwarn kotlinx.serialization.**

# Vico Charts Keep Rules
-keep class com.patrykandpatrick.vico.** { *; }
-dontwarn com.patrykandpatrick.vico.**

# PostHog Keep Rules
-keep class com.posthog.** { *; }
-dontwarn com.posthog.**

# Firebase Keep Rules
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# Credential Manager / Google Identity Keep Rules
# These libraries discover implementations via reflection/ServiceLoader at
# runtime (e.g. CredentialProviderFactory lookup on sign-in). Missing these
# rules is the likely root cause of the v1.5.0 R8 startup crash: stripped
# classes fail silently at the first auth attempt, not at install time, which
# matches the "won't start even after reinstall/clear cache" symptom.
-keep class androidx.credentials.playservices.** { *; }
-dontwarn androidx.credentials.playservices.**
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn com.google.android.libraries.identity.googleid.**

# WorkManager Keep Rules
# WorkManager instantiates Worker/CoroutineWorker subclasses by class name at
# runtime; keep names so R8 doesn't rename/strip them.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-dontwarn androidx.work.**
