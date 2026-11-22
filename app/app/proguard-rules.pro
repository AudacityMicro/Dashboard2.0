-keepattributes SourceFile,LineNumberTable
-dontobfuscate
-dontoptimize
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
# Agent health pages serialize typed SDK records and named unit getters.
-keep class androidx.health.connect.client.records.** { *; }
-keep class androidx.health.connect.client.units.** { *; }
-keep public class de.mm20.launcher2.ui.launcher.search.common.SearchableItemVM {
    public <init>();
}

