plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kayb28.supervisor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kayb28.supervisor"
        // Android 10 en adelante: cualquier tablet/teléfono moderno de la casa.
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-estetoscopio"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

dependencies {
    // Shizuku: la llave de solo-lectura que ya usa el dueño en su tablet.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
