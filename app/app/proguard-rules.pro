-keepattributes SourceFile,LineNumberTable
-dontobfuscate
-dontoptimize
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep public class de.mm20.launcher2.ui.launcher.search.common.SearchableItemVM {
    public <init>();
}

