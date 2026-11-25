import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val coreOut = rootProject.file("../core/out/android")
val realCore = providers.gradleProperty("tapetape.core").orNull != "fake" &&
    coreOut.resolve("jniLibs").isDirectory && coreOut.resolve("kotlin").isDirectory
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "org.tapetape.v2"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.tapetape.v2"
        minSdk = 26
        targetSdk = 36
        versionCode = 24
        versionName = "2.0.22"
        buildConfigField("boolean", "REAL_CORE", realCore.toString())
        buildConfigField("String", "FILES_BACKEND", "\"${providers.gradleProperty("tapetape.filesBackend").orNull.orEmpty().trim()}\"")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            if (realCore) {
                kotlin.directories.add("src/realcore/kotlin")
                kotlin.directories.add(coreOut.resolve("kotlin").path)
                jniLibs.directories.add(coreOut.resolve("jniLibs").path)
            } else {
                kotlin.directories.add("src/fakecore/kotlin")
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.emoji.picker)
    implementation(libs.work.runtime)
    implementation(libs.webkit)
    testImplementation(libs.junit)
    if (realCore) implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
