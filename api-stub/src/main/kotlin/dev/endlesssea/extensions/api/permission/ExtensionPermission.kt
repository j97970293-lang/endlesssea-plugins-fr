package dev.endlesssea.extensions.api.permission

/** Permissions an extension declares in its manifest, shown to the user before install (spec §19). */
enum class ExtensionPermission(val displayFr: String, val displayEn: String) {
    INTERNET("accès Internet", "network access"),
    DOWNLOAD("accès au téléchargement", "download access"),
    FILES("accès aux fichiers (dossier privé de l'extension)", "file access (extension-private folder)"),
    WEBVIEW("vérifications interactives possibles", "may ask for interactive verification"),
}
