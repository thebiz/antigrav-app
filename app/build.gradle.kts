plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNr = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.antigrav.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.antigrav.app"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNr
        versionName = "1.$buildNr"
    }

    // Fester Schlüssel: so lassen sich neue Versionen über die alte drüber installieren
    signingConfigs {
        create("ag") {
            storeFile = file("ag.keystore")
            storePassword = "viz-app-key"
            keyAlias = "viz"
            keyPassword = "viz-app-key"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        getByName("release") { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("ag") }
        getByName("debug") { signingConfig = signingConfigs.getByName("ag") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    // Sounds und Modell nicht nochmal packen
    androidResources { noCompress += listOf("bin", "ogg", "wav") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
}
