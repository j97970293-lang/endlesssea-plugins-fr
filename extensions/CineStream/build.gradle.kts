// Version unique de vérité : src/main/assets/extension.json (évite toute dérive
// entre le manifeste embarqué, le nom du .esx et repo/index.json).
val esManifestText = file("src/main/assets/extension.json").readText()
fun esManifest(key: String): String =
    Regex("\"" + key + "\"\\s*:\\s*\"?([^\",}]+)\"?").find(esManifestText)!!.groupValues[1].trim()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "fr.endlesssea.ext.cinestream"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "fr.endlesssea.ext.cinestream"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = esManifest("version").toInt()
        versionName = esManifest("versionName")
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
    // Boîte à outils native (HTTP, JSON, extracteurs…) : embarquée, c'est notre code.
    implementation(project(":common"))
    // Fournies par l'app hôte à l'exécution (ClassLoader parent) : jamais embarquées.
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.org.jsoup)
    compileOnly(libs.kotlinx.coroutines.core)
}
