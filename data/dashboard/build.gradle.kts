import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "de.mm20.launcher2.dashboard"
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) { minorApiLevel = libs.versions.compileSdkMinor.get().toInt() } }
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    buildTypes { create("nightly") { initWith(getByName("release")); matchingFallbacks += "release" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
}
