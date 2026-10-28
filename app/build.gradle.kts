plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.evlogger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.evlogger"
        minSdk = 26
        targetSdk = 34
        // Номер сборки берётся из GitHub Actions, чтобы каждая версия отличалась
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionCode = run
        versionName = "0.$run"
    }

    // Постоянный ключ подписи: новые сборки ставятся поверх старых без удаления
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
