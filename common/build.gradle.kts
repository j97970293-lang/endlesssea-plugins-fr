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
    compileOnly(project(":api-stub"))
    implementation(libs.org.jsoup)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(project(":api-stub"))
    testImplementation(libs.test.junit)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
