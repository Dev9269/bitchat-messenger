plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

import java.util.Properties
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreFile = rootProject.file("keystore/bitchat-release.keystore")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val hasReleaseKeystore = keystorePropsFile.exists() &&
        keystoreFile.exists() &&
        !keystoreProps.getProperty("storePassword").isNullOrBlank() &&
        !keystoreProps.getProperty("keyPassword").isNullOrBlank()

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
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = "bitchat"
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
            (this as BaseVariantOutputImpl).outputFileName =
                    "Ghostwire-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }
}

if (!hasReleaseKeystore) {
    tasks.matching {
        it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
    }.configureEach {
        doFirst {
            error(
                "No release keystore: create keystore/bitchat-release.keystore and " +
                        "keystore/keystore.properties (storePassword, keyPassword, alias 'bitchat'), " +
                        "or build assembleDebug instead."
            )
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
