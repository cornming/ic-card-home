plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cornming.iccard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cornming.iccard"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"

        // GitHub repo that hosts the update APKs (Releases). The app reads its public API, no token.
        buildConfigField("String", "UPDATE_REPO", "\"cornming/ic-card-home\"")
    }

    // The release key lives outside the repo; its path and passwords are in ~/.gradle/gradle.properties.
    // Updates only install over an app signed with the same key, so debug builds use it too.
    val keystorePath = providers.gradleProperty("ICCARD_KEYSTORE_PATH").orNull
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.gradleProperty("ICCARD_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("ICCARD_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("ICCARD_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Home API SDK: resolved from mavenLocal() (~/.m2/repository), not committed to this repo.
    implementation(libs.play.services.home)
    implementation(libs.play.services.home.types)
}
