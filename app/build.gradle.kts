import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.obdanalyzer"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.simcardiag.auto"
        minSdk = 26
        targetSdk = 35
        // Sur GitHub, chaque compilation reçoit un numéro : c'est lui que l'appli compare pour proposer une mise à jour
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionCode = build
        versionName = "1.0.$build"
    }

    // Même clé pour toutes les versions de test : Android accepte ainsi les mises à jour par-dessus
    signingConfigs {
        create("shared") {
            storeFile = file("simcardiag-debug.jks")
            storePassword = "android"
            keyAlias = "simcardiag"
            keyPassword = "android"
        }
    }
    buildTypes.getByName("debug").signingConfig = signingConfigs.getByName("shared")

    // Signature de la version publiable : créez keystore.properties à la racine du projet (voir README)
    val ks = rootProject.file("keystore.properties")
    if (ks.exists()) {
        val props = Properties().apply { ks.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
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
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Boîtiers OBD à câble USB (FTDI, CH340, CP210x, PL2303…)
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
}
