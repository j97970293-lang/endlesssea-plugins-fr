/*
 * :common — boîte à outils native partagée par toutes les extensions
 * (HTTP, JSON, HTML, extracteurs d'hébergeurs, m3u8, packer…).
 *
 * Pur Kotlin/JVM : aucune dépendance Android, donc testable sur JVM et
 * embarqué (dexé) dans chaque .esx.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Tout ce que l'app hôte embarque déjà est compileOnly : le .esx ne doit
    // contenir QUE notre code (voir gradle.properties).
    compileOnly(project(":api-stub"))
    compileOnly(kotlin("stdlib"))
    compileOnly(libs.org.jsoup)
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(project(":api-stub"))
    testImplementation(kotlin("stdlib"))
    testImplementation(libs.org.jsoup)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.test.junit)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
