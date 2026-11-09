import java.time.LocalDate
import java.time.format.DateTimeFormatter
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.plugin.compose)
}

android {
    buildFeatures { compose = true }
    androidResources {
        generateLocaleConfig = true
    }

    packaging {
        resources.excludes.add("META-INF/DEPENDENCIES")
        resources.excludes.add("META-INF/LICENSE")
        resources.excludes.add("META-INF/LICENSE.txt")
        resources.excludes.add("META-INF/license.txt")
        resources.excludes.add("META-INF/NOTICE")
        resources.excludes.add("META-INF/NOTICE.txt")
        resources.excludes.add("META-INF/notice.txt")
        resources.excludes.add("META-INF/ASL2.0")
        resources.excludes.add("META-INF/LICENSE.md")
        resources.excludes.add("META-INF/NOTICE.md")
    }

    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }
    defaultConfig {
        applicationId = "io.github.audacitymicro.dashboard"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = System.getenv("VERSION_CODE_OVERRIDE")?.toIntOrNull() ?: 2026091516
        versionName = "0.1.0-preview.18"
    }

    buildTypes {
        release {
            applicationIdSuffix = ".release"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        create("nightly") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            applicationIdSuffix = ".nightly"
            versionNameSuffix = "-${LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))}-nightly"
            signingConfig = null

            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        flavorDimensions += "variant"
        productFlavors {
            create("default") {
                dimension = "variant"
            }
            create("fdroid") {
                dimension = "variant"
                versionNameSuffix = "-fdroid"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    lint {
        abortOnError = false
    }
    namespace = "de.mm20.launcher2"
    buildFeatures {
        buildConfig = true
    }
}


dependencies {
    implementation(project(":data:dashboard"))
    implementation(libs.bundles.kotlin)
    implementation(libs.androidx.work)
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    testImplementation(libs.junit)
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(libs.androidx.compose.uitest)
    implementation(libs.androidx.compose.runtime)
    androidTestImplementation(libs.androidx.activitycompose)
    androidTestImplementation(libs.androidx.compose.foundation)
    androidTestImplementation(libs.androidx.compose.foundationlayout)
    androidTestImplementation(libs.androidx.compose.material3)

    //Android Jetpack
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)

    implementation(libs.coil.core)
    implementation(libs.coil.svg)

    implementation(libs.koin.android)

    implementation(project(":services:accounts"))
    implementation(project(":data:applications"))
    implementation(project(":data:appshortcuts"))
    implementation(project(":services:backup"))
    implementation(project(":services:badges"))
    implementation(project(":core:base"))
    implementation(project(":data:calculator"))
    implementation(project(":data:calendar"))
    implementation(project(":data:contacts"))
    implementation(project(":core:crashreporter"))
    implementation(project(":data:currencies"))
    implementation(project(":data:customattrs"))
    implementation(project(":data:searchable"))
    implementation(project(":data:plugins"))
    implementation(project(":data:themes"))
    implementation(project(":data:i18n"))
    implementation(project(":data:files"))
    implementation(project(":core:i18n"))
    implementation(project(":services:icons"))
    implementation(project(":core:ktx"))
    implementation(project(":services:music"))
    implementation(project(":libs:nextcloud"))
    implementation(project(":data:notifications"))
    implementation(project(":libs:owncloud"))
    implementation(project(":core:permissions"))
    implementation(project(":core:profiles"))
    implementation(project(":core:preferences"))
    implementation(project(":services:search"))
    implementation(project(":services:tags"))
    implementation(project(":data:unitconverter"))
    implementation(project(":app:ui"))
    implementation(project(":data:weather"))
    implementation(project(":data:websites"))
    implementation(project(":data:widgets"))
    implementation(project(":data:wikipedia"))
    implementation(project(":data:database"))
    implementation(project(":data:search-actions"))
    implementation(project(":services:global-actions"))
    implementation(project(":services:widgets"))
    implementation(project(":services:favorites"))
    implementation(project(":data:locations"))
    implementation(project(":services:plugins"))
    implementation(project(":core:devicepose"))
    implementation(project(":services:feed"))

    // Uncomment this if you want annoying notifications in your debug builds
    //debugImplementation(libs.leakcanary)
}
