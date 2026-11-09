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
        versionCode = 3
        versionName = "0.3.0-tiempo-real"
    }

    // Firma estable opcional: si el CI define el keystore, el APK sale
    // siempre firmado con la misma clave y las actualizaciones se
    // instalan encima sin desinstalar nada. Sin variables (compilado
    // en local), se usa la firma de depuración de siempre.
    val ksRuta = System.getenv("SUPERVISOR_KEYSTORE")
    val ksClave = System.getenv("SUPERVISOR_KEYSTORE_PASSWORD")
    val ksAlias = System.getenv("SUPERVISOR_KEY_ALIAS")

    signingConfigs {
        if (ksRuta != null && ksClave != null && ksAlias != null) {
            create("estable") {
                storeFile = file(ksRuta)
                storePassword = ksClave
                keyAlias = ksAlias
                keyPassword = ksClave
            }
        }
    }

    buildTypes {
        debug {
            if (ksRuta != null) signingConfig = signingConfigs.getByName("estable")
        }
        release {
            isMinifyEnabled = false
            if (ksRuta != null) signingConfig = signingConfigs.getByName("estable")
        }
    }

    buildFeatures {
        // Necesario para compilar el contrato AIDL (IShellService).
        aidl = true
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
