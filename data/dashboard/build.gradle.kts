import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
plugins { alias(libs.plugins.android.library) }
android {
    namespace = "de.mm20.launcher2.dashboard"
    compileSdk { version = release(libs.versions.compileSdk.get().toInt()) { minorApiLevel = libs.versions.compileSdkMinor.get().toInt() } }
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
    buildTypes { create("nightly") { initWith(getByName("release")); matchingFallbacks += "release" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
}

// Pin the public, Apache-2.0 English model. No runtime download or speech service.
val speechAssets = layout.buildDirectory.dir("generated/speechAssets")
val prepareSpeechModel = tasks.register("prepareSpeechModel") {
    val output = speechAssets.get().file("speech/en-us.zip").asFile
    val expected = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
    inputs.property("sha256", expected)
    outputs.file(output)
    doLast {
        output.parentFile.mkdirs()
        val temporary = File(output.parentFile, "model.download")
        try {
            URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL().openConnection().apply {
                connectTimeout = 30000; readTimeout = 120000
            }.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            val hash = MessageDigest.getInstance("SHA-256").digest(temporary.readBytes()).joinToString("") { "%02x".format(it) }
            check(hash == expected) { "Speech model checksum mismatch" }
            Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addStaticSourceDirectory("build/generated/speechAssets")
}
tasks.named("preBuild").configure { dependsOn(prepareSpeechModel) }
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
