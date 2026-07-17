# Google Play Services & Maps Keep Rules
-keep class com.google.android.gms.maps.** { *; }
-dontwarn com.google.android.gms.maps.**
-keep class com.google.android.gms.common.** { *; }
-dontwarn com.google.android.gms.common.**

# Room Database Keep Rules
# `-keep class X` with no `{ *; }` body only stops R8 from renaming/removing
# the class itself — it does NOT stop R8 from stripping "unused" members,
# including the no-arg constructor that Room (and WorkManager's internal
# Room database) invoke reflectively via
# Class.getDeclaredConstructor().newInstance(). That gap is the confirmed
# root cause of the internal-track startup crash:
#   Fatal Exception: java.lang.RuntimeException: Unable to get provider
#   androidx.startup.InitializationProvider
#   Caused by: java.lang.NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
# R8 stripped the constructor of WorkManager's generated Room database impl
# because static analysis can't see the reflective call site. Fixed by
# keeping full members on RoomDatabase/Dao subclasses and on every
# Room-generated `*_Impl` class (covers our own AppDatabase_Impl and DAO
# impls too, not just WorkManager's).
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.Dao { *; }
-keep class **_Impl { *; }
-keepclassmembers class **_Impl {
    public <init>(...);
}
-keep class androidx.work.impl.** { *; }
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
