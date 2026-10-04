plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fr.endlesssea.ext.xalaflix"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "fr.endlesssea.ext.xalaflix"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 15
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // le .esx est chargé par réflexion : pas d'obfuscation
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    sourceSets["main"].java.srcDir("src/main/kotlin")
}

dependencies {
    // Fournie par l'app hôte à l'exécution : jamais embarquée dans le .esx.
    compileOnly(project(":api-stub"))
    // Boîte à outils native (HTTP, JSON, extracteurs…) : embarquée.
    implementation(project(":common"))
    implementation(libs.org.jsoup)
}
