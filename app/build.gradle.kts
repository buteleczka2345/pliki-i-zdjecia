plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.pliki.menedzer"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.pliki.zdjecia"
        minSdk = 30 // Android 11+: "dostęp do wszystkich plików", karta SD, kosz zdjęć (createTrashRequest)
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        // usuwa nieużywany kod bibliotek (7z), APK z ~7,7 MB robi się kilkukrotnie mniejszy
        debug { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // rozpakowywanie 7z (xz = dekoder LZMA/LZMA2, domyślnej kompresji 7-Zip)
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
}
