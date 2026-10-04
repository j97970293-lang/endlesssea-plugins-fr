/*
 * Copie verbatim de :extensions-api du dépôt endlesssea (sans le module manifest,
 * qui n'est utile qu'à l'application hôte). Utilisé en `compileOnly` : l'API est
 * fournie par l'application à l'exécution, elle ne doit JAMAIS être embarquée
 * dans un .esx.
 */
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
