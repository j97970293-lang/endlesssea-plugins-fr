plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * Agrège tous les .esx produits par les modules extensions/ dans build/esx/
 * et régénère repo/index.json (sha256 + taille réels).
 *
 *     ./gradlew packageAll
 */
val esxOut = layout.buildDirectory.dir("esx")

tasks.register("packageAll") {
    group = "endlesssea"
    description = "Construit toutes les extensions et copie les .esx dans build/esx/"
    val extProjects = subprojects.filter { it.path.startsWith(":extensions:") }
    dependsOn(extProjects.map { "${it.path}:assembleRelease" })
    doLast {
        val outDir = esxOut.get().asFile.apply { mkdirs() }
        extProjects.forEach { p ->
            val apk = p.layout.buildDirectory.get().asFile
                .resolve("outputs/apk/release")
                .listFiles()?.firstOrNull { it.name.endsWith(".apk") }
            if (apk != null) {
                val version = p.extensions.findByName("android")
                    ?.let { (it as com.android.build.gradle.BaseExtension).defaultConfig.versionName }
                    ?: "1.0.0"
                apk.copyTo(outDir.resolve("${p.name}-$version.esx"), overwrite = true)
            }
        }
        println("→ ${outDir.absolutePath}")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
