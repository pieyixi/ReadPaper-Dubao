plugins {
    id("com.android.application")
}

// 云端与本地使用同一份原始签名；凭证通过环境注入，不写入仓库。
val releaseKeystore = providers.environmentVariable("DUBAO_KEYSTORE_FILE")
    .orElse(rootProject.file("release.jks").absolutePath)
val releaseStorePassword = providers.environmentVariable("DUBAO_STORE_PASSWORD")
val releaseKeyPassword = providers.environmentVariable("DUBAO_KEY_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("DUBAO_KEY_ALIAS").orElse("newspaper")

android {
    namespace = "com.newspaper.helper"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystore.get())
            storePassword = releaseStorePassword.orNull
            keyAlias = releaseKeyAlias.get()
            keyPassword = releaseKeyPassword.orNull
        }
    }

    defaultConfig {
        applicationId = "com.newspaper.helper"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
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

// Debug 和静态检查不要求私钥；Release 缺少原签名时必须明确失败。
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(file(releaseKeystore.get()).isFile &&
            !releaseStorePassword.orNull.isNullOrBlank() &&
            !releaseKeyPassword.orNull.isNullOrBlank()) {
            "Configure the original Release keystore and DUBAO signing environment variables."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseSigning)
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.webkit:webkit:1.9.0")
}
