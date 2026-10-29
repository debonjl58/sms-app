##############################################################################
# proguard-rules.pro — Tagala Immigration Services
#
# Ces règles sont indispensables dès que minifyEnabled = true.
# Sans elles : Gson perd les champs de la file d'attente et la file stockée
# devient illisible après une mise à jour ; Java-WebSocket casse à l'exécution.
##############################################################################

# ----------------------------------------------------------------------------
# Traces de crash lisibles
# ----------------------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Attributs nécessaires à Gson, aux annotations et aux classes internes Kotlin
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations
-keepattributes AnnotationDefault

# ----------------------------------------------------------------------------
# MODÈLES GSON — indispensable
#
# Ces classes sont sérialisées en JSON dans les SharedPreferences.
# Gson utilise la réflexion sur les noms de champs : sans ces règles, R8
# renomme les champs et la file d'attente stockée devient corrompue.
# ----------------------------------------------------------------------------
-keep class com.smsforwarder.PendingSmsQueue$QueuedSms { *; }
-keep class com.smsforwarder.SmsData { *; }
-keep class com.smsforwarder.SmsStatus { *; }

# Gson lui-même (TypeToken + Unsafe)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep class sun.misc.Unsafe { *; }
-dontwarn sun.misc.**

# ----------------------------------------------------------------------------
# COMPOSANTS ANDROID déclarés dans le manifeste
# (AGP génère déjà des règles, on les rend explicites pour la robustesse)
# ----------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

-keep class com.smsforwarder.MainActivity { *; }
-keep class com.smsforwarder.SmsForwarderApp { *; }
-keep class com.smsforwarder.SmsForwarderService { *; }
-keep class com.smsforwarder.SmsReceiver { *; }
-keep class com.smsforwarder.BootReceiver { *; }

# ----------------------------------------------------------------------------
# JAVA-WEBSOCKET
# La bibliothèque est pilotée par des callbacks et référence slf4j qu'on
# n'embarque pas -> R8 signalerait des classes manquantes et casserait le
# chargement des classes de frames.
# ----------------------------------------------------------------------------
-keep class org.java_websocket.** { *; }
-keep interface org.java_websocket.** { *; }
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**
-dontwarn java.lang.instrument.**

# ----------------------------------------------------------------------------
# KOTLIN / COROUTINES
# ----------------------------------------------------------------------------
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# Nécessaire au "when" sur un enum compilé par Kotlin
-keepclassmembers class **$WhenMappings {
    <fields>;
}

# valueOf() / values() des enums utilisés par réflexion (SmsStatus)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ----------------------------------------------------------------------------
# ANDROIDX
# ----------------------------------------------------------------------------
-dontwarn android.support.**
-dontwarn androidx.**
-keep class androidx.appcompat.widget.** { *; }

# ----------------------------------------------------------------------------
# OPTIONNEL — supprimer les logs en release
#
# À ACTIVER seulement quand tu ne débogues plus : en release, ces règles
# retirent tous les Log.v/d/i de l'APK.
# ----------------------------------------------------------------------------
#-assumenosideeffects class android.util.Log {
#    public static boolean isLoggable(java.lang.String, int);
#    public static int v(...);
#    public static int d(...);
#    public static int i(...);
#    public static int w(...);
#    public static int e(...);
#}

##############################################################################
# FILET DE SÉCURITÉ (optionnel)
#
# Si après activation de minifyEnabled tu as encore un comportement bizarre
# difficile à tracer, décommente cette ligne : elle empêche l'obfuscation de
# tout ton code applicatif et élimine d'un coup toute cause liée à R8.
# L'APK grossit légèrement, c'est tout.
##############################################################################
#-keep class com.smsforwarder.** { *; }
