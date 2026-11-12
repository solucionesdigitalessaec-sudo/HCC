import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cl.carrerarentable"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.carrerarentable"
        minSdk = 26
        targetSdk = 35
        // En GitHub Actions se pasa -PbuildNumber=<n> y cada compilación queda como versión 1.<n>.
        val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.$buildNumber"
    }

    // Firma fija para que cada versión se pueda instalar encima de la anterior.
    // En GitHub Actions la clave vive en la caché privada del workflow (no en el código)
    // y llega por variables de entorno. Sin ellas (compilación local) se usa la firma de depuración.
    val keystorePath = System.getenv("KMR_KEYSTORE")
    val keystorePass = System.getenv("KMR_STORE_PASS")
    val fixedSigning = if (keystorePath != null && keystorePass != null) {
        signingConfigs.create("kmrentable") {
            storeFile = file(keystorePath)
            storePassword = keystorePass
            keyAlias = "kmrentable"
            keyPassword = keystorePass
        }
    } else null

    buildTypes {
        debug {
            if (fixedSigning != null) signingConfig = fixedSigning
        }
        release {
            isMinifyEnabled = false
            signingConfig = fixedSigning ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    // Reconocimiento de texto en imágenes, modelo incluido en la app (funciona sin internet).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
