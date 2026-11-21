plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ch.dansoft.karoofusedgps"
    compileSdk = 34

    defaultConfig {
        applicationId = "ch.dansoft.karoofusedgps"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Fester Schlüssel im Repository, damit sich jede neue Version über die alte
    // installieren lässt (die App ist nur für den eigenen Karoo gedacht).
    signingConfigs {
        create("karoo") {
            storeFile = rootProject.file("keystore/karoo-fused-gps.jks")
            storePassword = "karoofusedgps"
            keyAlias = "karoo"
            keyPassword = "karoofusedgps"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("karoo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
