/*
 * endlesssea-plugins-fr — extensions natives Endless Sea (.esx)
 *
 * Chaque dossier de extensions/ qui contient un build.gradle.kts devient
 * automatiquement un module (une extension = un paquet .esx).
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "endlesssea-plugins-fr"

include(":api-stub")
include(":common")

val disabled = listOf<String>()

File(rootDir, "extensions").listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {
        include(":extensions:${dir.name}")
    }
}
