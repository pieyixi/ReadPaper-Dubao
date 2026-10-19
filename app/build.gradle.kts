plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
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
        versionCode = 8
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            if (System.getenv("DUBAO_LIVE") != "1") it.exclude("**/LiveSourcesTest*")
        }
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
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.google.code.gson:gson:2.10.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.12.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
