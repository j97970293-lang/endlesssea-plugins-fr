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
5. `linkStream(LinkRequest)` reçoit cette charge utile et émet les `VideoLink`
   jouables **au fil de l'eau** : chaque lecteur est publié dès qu'il est résolu,
   sans attendre le plus lent (app 0.25.0, `EsExtension.loadLinksFlow`).
   La plupart des extensions délèguent à `servers(payload)` +
   `resolveServersFlow(entries)` ; `loadLinks` (finale) collecte ce flux pour les
   applications plus anciennes.

## Classe de base `EsProvider`

Ce qu'elle fournit :

| Membre | Rôle |
|---|---|
| `http` | wrapper HTTP (`get`, `post`, `head`, cookies, params, `requireOk`) |
| `item(...)` | fabrique de `SearchItem` (corrige les URLs, décode le HTML) |
| `movieDetails(...)` | fiche à épisode unique (films, directs) |
| `episode(...)` | fabrique d'`Episode` |
| `resolveServers(entries, preferred)` | résout les embeds via les extracteurs, trie par qualité, applique la langue — renvoie une **liste** |
| `resolveServersFlow(entries, preferred)` | même travail, mais **émet chaque lecteur dès qu'il est résolu** |
| `firstNonEmpty { … }, { … }` | enchaîne des secours : l'étape suivante n'est lancée que si la précédente n'a rien donné |
| `linksBlocking { … }` | enveloppe en flux une résolution sans équivalent progressif (secours par identifiant IMDb…) |
| `serverRefs(entries)` | expose la liste des serveurs à l'UI |
| `extractors()` | extracteurs natifs (surchargeable pour en ajouter) |

Ce que chaque extension implémente :

```kotlin
class MonSiteExtension(ctx: ExtensionContext) : EsProvider(ctx) {
    override val defaultUrl = "https://monsite.tld"   // pas mainUrl : voir ci-dessous
    override val providerName = "Mon Site"
    override val homeRows = listOf(HomeRow("films", "Films", "/films/"))

    override suspend fun home(row: HomeRow, page: Int): PagedResult<SearchItem> { … }
    override suspend fun searchQuery(query: String, page: Int): List<SearchItem> { … }
    override suspend fun details(url: String): MediaDetails { … }
    override suspend fun servers(payload: String): List<ServerEntry> { … }
}
```

C'est tout : `linkStream` par défaut fait déjà
`resolveServersFlow(servers(payload), preferredServer)`.

### Trois pièges du gabarit

| À ne pas faire | Pourquoi |
|---|---|
| `override val mainUrl = "https://…"` | `mainUrl` est **dérivée** : `userUrl ?: defaultUrl`. L'écraser avec une constante désactive le réglage `site_url`, seule échappatoire de l'utilisateur quand le site change de domaine. On surcharge `defaultUrl`. |
| `override suspend fun load(url)` | `load` est **finale** : elle applique les corrections d'images et l'enrichissement communs. La méthode à implémenter est `details(url)`. |
| `override suspend fun loadLinks(data)` | `loadLinks` est **finale** : elle collecte `linkStream` pour les apps < 0.25.0. Pour une résolution personnalisée, on surcharge `linkStream`. |

### Résolution personnalisée

Quand la source a ses propres secours (agrégateurs TMDB, repli par identifiant
IMDb…), on surcharge `linkStream` et on enchaîne avec `firstNonEmpty` :

```kotlin
override fun linkStream(data: LinkRequest): Flow<VideoLink> = flow {
    emitAll(
        firstNonEmpty(
            { resolveServersFlow(servers(data.episode.data), data.preferredServer) },
            { linksBlocking { secours(data) } },   // votre repli : agrégateur TMDB, IMDb…
        )
    )
}

private suspend fun secours(data: LinkRequest): List<VideoLink> = …
```

`loadLinks` continue de fonctionner sans rien faire de plus : il collecte ce flux.
C'est exactement le schéma des 15 sources du dépôt qui ont une logique propre.

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


## Poids des `.esx` — ce que l'extension embarque (et ce qu'elle n'embarque pas)

`ExtensionLoader` charge chaque extension avec
`PathClassLoader(apk, EsExtension::class.java.classLoader)` : **le ClassLoader
de l'application est le parent**. Tout ce que l'app contient déjà est donc
résolvable depuis l'extension, et l'embarquer une seconde fois est du poids mort
chargé 17 fois en mémoire.

| Dépendance | Portée | Pourquoi |
|---|---|---|
| `dev.endlesssea.extensions.api` (`:api-stub`) | `compileOnly` | fournie par l'app, contrat partagé obligatoire |
| `kotlin-stdlib` | `compileOnly` (+ `kotlin.stdlib.default.dependency=false`) | l'app est écrite en Kotlin |
| `kotlinx-coroutines-core` | `compileOnly` | `extensions-api`/`extensions-loader` en dépendent |
| `org.jsoup` | `compileOnly` | embarqué par `extensions-loader` (providers déclaratifs) |
| `:common` | `implementation` | **notre** code, il doit être dans le `.esx` |

Avant la v1.8.0, chaque `.esx` pesait ~1,2 Mo pour **3,6 Mo de `classes.dex`** :
stdlib + coroutines + jsoup dupliqués 17 fois. La CI publie désormais un tableau
des tailles et avertit au-delà de 900 Ko par extension.

⚠️ Contrat implicite : les APK publiés par l'app sont des builds **debug** (pas
de R8). Si l'app passait en build release minifié, il lui faudrait conserver
`org.jsoup.**`, `kotlin.**` et `kotlinx.coroutines.**` dans
`app/proguard-rules.pro`, sans quoi les extensions compilées ne retrouveraient
plus ces classes.
