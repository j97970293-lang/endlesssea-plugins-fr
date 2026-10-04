# De CloudStream à Endless Sea — notes de portage

Ce document décrit comment les plugins de
[`plugin-fr`](https://github.com/j97970293-lang/plugin-fr) (CloudStream) ont été
convertis en extensions Endless Sea.

## Décisions de départ

| Sujet | Choix |
|---|---|
| Stratégie | **Réécriture native** de chaque provider sur `EsExtension` — pas de couche de compatibilité CloudStream |
| Périmètre | Providers **non NSFW** uniquement (17 sur 26) |
| Livraison | **Dépôt séparé** (`endlesssea-plugins-fr`), l'app `endlesssea` reste intacte |
| Langue | Interface et messages d'erreur en **français** |

## Correspondance des API

| CloudStream | Endless Sea |
|---|---|
| `MainAPI` | `EsExtension` (via la classe de base `EsProvider`) |
| `mainPageOf("path" to "Nom")` | `homeRows = listOf(HomeRow(clé, titre, chemin))` |
| `getMainPage(page, request)` | `getMainPage(MainPageRequest)` → `PagedResult<SearchItem>` |
| `search(query)` | `search(SearchRequest)` → `PagedResult<SearchItem>` |
| `load(url)` → `LoadResponse` | `load(url)` → `MediaDetails` (+ `Season`/`Episode`) |
| `loadLinks(data, …, callback)` | `loadLinks(LinkRequest)` → `List<VideoLink>` (valeur de retour, pas de callback) |
| `newMovieLoadResponse` / `newTvSeriesLoadResponse` / `newAnimeLoadResponse` | `MediaDetails(type = MOVIE / SERIES / ANIME)` + helper `movieDetails(...)` |
| `DubStatus.Dubbed` / `Subbed` | `AudioLang.VF` / `AudioLang.VOSTFR` portée par `Season`, `ServerEntry` et `VideoLink` |
| `ExtractorLink` | `VideoLink(url, streamType, quality, audioLang, headers, subtitles)` |
| `SubtitleFile` | `SubtitleTrack(url, lang, label, format)` |
| `ExtractorApi` | `EsExtractor` (classe de base locale) déclaré via `extractors()` |
| `loadExtractor(url, …)` | `Extractors.resolve(http, url, referer, nom)` / `resolveServers(entries)` |
| `app.get/post` (NiceHttp) | `http.get/post` (wrapper sur `ctx.http`, le client de l'app) |
| `AppUtils.parseJson<T>` (Jackson) | `Json.parse` / `JsonNode` (parseur maison, zéro dépendance) |
| `JsUnpacker` | `Unpacker.unpack` |
| `ErrorLoadingException` | `SourceException.VideoUnavailable` / `.CaptchaRequired` |
| `CloudflareKiller` | `Res.verifyNotBlocked()` → `SourceException.CaptchaRequired` (l'app ouvre une WebView) |
| `AlertDialog` de réglages | `ExtensionSetting` (champ TEXT `site_url`) |

## Ce qui a été supprimé

* **`CloudflareKiller`** et toute dépendance à OkHttp/Jackson/Android : le socle
  n'utilise plus que l'API d'Endless Sea + Jsoup.
* Le **« docteur de liens »** (requêtes `HEAD` de vérification en arrière-plan,
  présent dans Zenix/Flemmix/AnimoFlix…) : l'app gère elle-même le repli d'un
  lien mort au lecteur suivant, et il consommait beaucoup de requêtes.
* Les **boîtes de dialogue Android** de changement de domaine, remplacées par un
  réglage déclaré, et par la résolution automatique de domaine là où le site en
  publie une liste (Xalaflix, Frembed).
* Le **code dupliqué** : chaque plugin CloudStream embarquait sa propre copie de
  Vidara, apiwiflix, playerix, movix, vidsrc.buzz, le décodeur XOR videojs…
  Tout est désormais dans `common/`.

## Ce qui a été factorisé dans `common/`

| Fichier | Contenu |
|---|---|
| `Http.kt` | `get/post/head`, cookies, params, `requireOk()`, `verifyNotBlocked()` |
| `Json.kt` | parseur JSON sans dépendance (`JsonNode`, `str`, `list`, `find`…) |
| `Text.kt` | URLs, entités HTML, qualité, langue, type de flux, années, durées |
| `M3u8.kt` | lecture des playlists maîtres → variantes par qualité |
| `Unpacker.kt` | désobfuscation `p,a,c,k,e,d` |
| `Extractors.kt` | extracteurs natifs (Vidara/StreamUp, sniffer générique, XOR videojs…) |
| `Aggregators.kt` | playerix, movix, moviesapi, réseau movix, réseau Frembed, zeus, mouve, 1Embed |
| `TmdbEmbeds.kt` | lecteurs publics indexés TMDB + `apiwiflix` |
| `VidSrcBuzz.kt` | chaîne complète `/embed` → `a=sources` → `a=play` |
| `Tmdb.kt` | catalogue, fiches, saisons, `idFromTitle`, `imdbId` |
| `EsProvider.kt` | socle commun : fabriques de DTO, `resolveServers`, pagination |

## Conventions de portage

* **Charge utile d'épisode** : une chaîne opaque, généralement `type|id|…`
  (ex. `tv|{tmdb}|{s}|{e}`, `ep|{url}|{n}|vf`), reconstruite dans `servers()`.
* **VF / VOSTFR** : deux `Season` distinctes quand le site sépare les langues,
  sinon un `AudioLang` sur chaque `ServerEntry`.
* **Fiches sans épisodes** (films, direct) : helper `movieDetails(...)`, qui crée
  une saison unique à un épisode.
* **Résilience** : chaque agrégateur est appelé dans un `runCatching` ; une
  source morte ne fait jamais échouer la lecture entière. Si rien ne sort,
  `SourceException.VideoUnavailable` avec un message en français.

## Vérification

Le projet n'a pas de tests d'intégration (ils dépendraient de sites tiers). La
validation repose sur :

1. `kotlinc` sur l'ensemble `api-stub + common + extensions` (zéro erreur) ;
2. la compilation Gradle/Android en CI (`./gradlew packageAll`) ;
3. une relecture ligne à ligne de chaque protocole de site par rapport au
   plugin CloudStream d'origine (regex, endpoints, en-têtes, ordre des appels).
