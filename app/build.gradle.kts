plugins {
    id("com.android.application")
}

android {
    namespace = "com.newspaper.helper"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("../release.jks")
            storePassword = "sg3cxhmjyj"
            keyAlias = "newspaper"
            keyPassword = "sg3cxhmjyj"
        }
    }

    defaultConfig {
        applicationId = "com.newspaper.helper"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
}
