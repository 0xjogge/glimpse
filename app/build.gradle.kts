plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val glimpseVersion = providers.gradleProperty("glimpseVersion").orElse("0.4").get()

require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matches(glimpseVersion))

val (featureVersion, patchVersion) = glimpseVersion.split(".").map(String::toLong)

val glimpseVersionCode = featureVersion * 10_000 + patchVersion

require(
    featureVersion <= 210_000 && patchVersion < 10_000 && glimpseVersionCode in 1..2_100_000_000L,
)

val releaseKeystorePath = providers.environmentVariable("GLIMPSE_KEYSTORE_PATH").orNull

android {
    namespace = "app.glimpse"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.glimpse"
        minSdk = 35
        targetSdk = 37
        versionCode = glimpseVersionCode.toInt()
        versionName = glimpseVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    splits {
        abi {
            isEnable =
                providers
                    .gradleProperty("glimpseSplitApks")
                    .map(String::toBooleanStrict)
                    .orElse(false)
                    .get()
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
    if (releaseKeystorePath != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath)
            storePassword = providers.environmentVariable("GLIMPSE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("GLIMPSE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("GLIMPSE_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        getByName("release") {
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engines:mlkit"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.5.0-beta01")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("androidx.camera:camera-camera2:1.5.1")
    implementation("androidx.camera:camera-lifecycle:1.5.1")
    implementation("androidx.camera:camera-view:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
