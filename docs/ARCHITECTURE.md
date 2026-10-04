# Architecture

## Vue d'ensemble

```
┌────────────────────┐   charge le .esx (dex + assets/extension.json)
│  Endless Sea (app) │ ─────────────────────────────────────────────┐
└────────────────────┘                                              │
            ▲ ExtensionContext (http, storage, settings, logger)    ▼
            │                                             ┌──────────────────┐
            │                                             │  EsExtension     │
            │                                             │  = votre classe  │
            └─────────── List<VideoLink> ────────────────  └──────────────────┘
                                                                   │ hérite de
                                                                   ▼
                                                            ┌──────────────┐
                                                            │  EsProvider  │  (common/)
                                                            └──────────────┘
```

* `api-stub/` : copie **verbatim** de `dev.endlesssea.extensions.api`, consommée
  en `compileOnly` — elle est fournie par l'application à l'exécution, elle
  n'est donc jamais embarquée dans le `.esx`.
* `common/` : bibliothèque interne (`implementation`), embarquée dans chaque
  `.esx`. C'est elle qui contient 90 % de la logique réutilisable.
* `extensions/<Nom>/` : un module Android par source. Seule la logique propre
  au site y vit.

## Cycle de vie d'une extension

1. L'app lit `assets/extension.json`, instancie `entryClass` avec un
   `ExtensionContext`.
2. `getMainPage(MainPageRequest)` alimente les rangées d'accueil
   (`homeRows` → `home(row, page)` dans `EsProvider`).
3. `search(SearchRequest)` → `searchQuery(query, page)`.
4. `load(url)` renvoie une `MediaDetails` contenant les `Season`/`Episode`.
   Chaque `Episode.data` est une **charge utile opaque** propre à l'extension.
5. `loadLinks(LinkRequest)` reçoit cette charge utile et renvoie les
   `VideoLink` jouables. La plupart des extensions délèguent à
   `servers(payload)` + `resolveServers(entries)`.

## Classe de base `EsProvider`

Ce qu'elle fournit :

| Membre | Rôle |
|---|---|
| `http` | wrapper HTTP (`get`, `post`, `head`, cookies, params, `requireOk`) |
| `item(...)` | fabrique de `SearchItem` (corrige les URLs, décode le HTML) |
| `movieDetails(...)` | fiche à épisode unique (films, directs) |
| `episode(...)` | fabrique d'`Episode` |
| `resolveServers(entries, preferred)` | résout les embeds via les extracteurs, trie par qualité, applique la langue |
| `serverRefs(entries)` | expose la liste des serveurs à l'UI |
| `extractors()` | extracteurs natifs (surchargeable pour en ajouter) |

Ce que chaque extension implémente :

```kotlin
class MonSiteExtension(ctx: ExtensionContext) : EsProvider(ctx) {
    override val mainUrl = "https://monsite.tld"
    override val providerName = "Mon Site"
    override val homeRows = listOf(HomeRow("films", "Films", "/films/"))

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> { … }
    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> { … }
    override suspend fun load(url: String): MediaDetails { … }
    override suspend fun servers(payload: String): List<ServerEntry> { … }
    override suspend fun loadLinks(data: LinkRequest): List<VideoLink> =
        resolveServers(servers(data.episode.data), data.preferredServer)
}
```

## `ServerEntry`, le pivot de la lecture

```kotlin
data class ServerEntry(
    val name: String,          // « Uqload · VF »
    val url: String,           // embed OU flux direct
    val lang: AudioLang = OTHER,
    val referer: String? = null,
    val direct: Boolean = false, // true = déjà jouable (m3u8/mp4)
)
```

`resolveServers` traite les entrées `direct = true` telles quelles (en passant
par `M3u8.variants` pour les playlists maîtres) et envoie les autres aux
extracteurs.

## Extracteurs

* `Extractors.all(http)` : liste des extracteurs natifs (Vidara/StreamUp et
  consorts, plus un **sniffer générique** qui télécharge la page d'embed et y
  cherche `file:`, `source src=`, les playlists `.m3u8`, les scripts
  `p,a,c,k,e,d` et les sources videojs chiffrées en XOR).
* `allExtractorsWithAggregators(http)` ajoute `OneEmbed`.
* Pour ajouter un hébergeur : écrire une classe `EsExtractor` dans
  `common/Extractors.kt` et l'ajouter à `Extractors.all`.

## Agrégateurs

`common/Aggregators.kt` et `common/TmdbEmbeds.kt` regroupent les API tierces
partagées par plusieurs sites FR, toutes indexées par identifiant TMDB :

`playerix`, `movix`, `movixNetwork` (purstream/wiflix/fstream/cpasmal/liens),
`frembedNetwork`, `zeus` (SSE), `mouve`, `moviesApi`, `TmdbEmbeds.wiflix`,
`TmdbEmbeds.publicEmbeds`, `VidSrcBuzz.links`.

Règle d'usage : **toujours** les appeler dans un `runCatching` et fusionner les
résultats dans un `LinkedHashMap` dédupliqué par URL.

## Erreurs

| Situation | À lever |
|---|---|
| Page protégée (Cloudflare, DDoS-Guard) | `Res.verifyNotBlocked()` → `SourceException.CaptchaRequired` |
| Aucun lecteur / flux exploitable | `SourceException.VideoUnavailable("message en français")` |
| URL ou charge utile incohérente | `SourceException.VideoUnavailable` également (l'app affiche le message) |

## Compilation hors Gradle (vérification rapide)

```bash
kotlinc -nowarn -cp jsoup.jar -d /tmp/out \
  api-stub/src/main/kotlin common/src/main/kotlin extensions/*/src/main/kotlin
```

C'est le contrôle utilisé pendant le portage : il valide tout le code Kotlin
sans nécessiter le SDK Android.
