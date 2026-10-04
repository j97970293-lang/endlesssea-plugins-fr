package fr.endlesssea.common

import dev.endlesssea.extensions.api.ExtensionContext
import java.io.File

/**
 * Cache disque minimal, adossé à `ExtensionContext.cacheDir` (app 0.7.0+).
 *
 * Objectif : éviter de refrapper TMDB à chaque ouverture d'écran. Tout est
 * défensif — si le dossier est absent (app plus ancienne) ou si l'écriture
 * échoue, on se comporte exactement comme avant, sans cache.
 */
class Cache(private val ctx: ExtensionContext, private val ttlMillis: Long = 6 * 60 * 60 * 1000L) {

    private val dir: File? by lazy {
        runCatching { ctx.cacheDir?.apply { if (!exists()) mkdirs() }?.takeIf { it.isDirectory } }
            .getOrNull()
    }

    private fun fileFor(key: String): File? {
        val d = dir ?: return null
        val safe = key.hashCode().toUInt().toString(16) + "-" + key.length
        return File(d, "$safe.json")
    }

    /** Valeur encore fraîche, ou `null`. */
    fun get(key: String): String? = runCatching {
        val f = fileFor(key) ?: return null
        if (!f.isFile) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMillis) {
            f.delete()
            return null
        }
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun put(key: String, value: String) {
        runCatching {
            if (value.isBlank()) return
            fileFor(key)?.writeText(value)
        }
    }

    /** Récupère depuis le cache, sinon exécute [loader] et mémorise son résultat. */
    suspend fun getOrPut(key: String, loader: suspend () -> String?): String? {
        get(key)?.let { return it }
        val fresh = loader() ?: return null
        put(key, fresh)
        return fresh
    }

    /** Supprime les entrées périmées (appel optionnel). */
    fun prune() {
        runCatching {
            val now = System.currentTimeMillis()
            dir?.listFiles()?.forEach { f -> if (now - f.lastModified() > ttlMillis) f.delete() }
        }
    }
}
