plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bitchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bitchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.4.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/bitchat-release.keystore")
            storePassword = keystoreProps.getProperty("storePassword")
                ?: error("Missing storePassword — add keystore/keystore.properties")
            keyAlias = "bitchat"
            keyPassword = keystoreProps.getProperty("keyPassword")
                ?: error("Missing keyPassword — add keystore/keystore.properties")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

android {
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "Ghostwire-${variant.versionName}.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.fragment)
    implementation(libs.sqlcipher)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
