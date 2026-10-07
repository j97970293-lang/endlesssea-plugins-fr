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
| `loadLinks(data, …, callback)` | `linkStream(LinkRequest)` → `Flow<VideoLink>` (émission au fil de l'eau, app 0.25.0+) ; `loadLinks` en reste la variante « liste », finale, pour les apps plus anciennes |
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
| `AlertDialog` de réglages | `ExtensionSetting` (champ TEXT `site_url`), valeurs lues via `ExtensionContext.settings` (app 0.4.0+) |

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

## Suivi de l'API Endless Sea

| Version de l'app | Nouveauté | Prise en charge ici |
|---|---|---|
| 0.4.0 | `ExtensionContext.settings` (réglages injectés) | v1.1.0 — réglage `site_url` sur 16 sources, playlists M3U pour Télé FR Direct |
| 0.5.0 | `MediaDetails.trailerUrl` et `MediaDetails.characters` (`CharacterCredit`) | v1.2.0 — remplis depuis TMDB (`append_to_response=videos,credits`) pour toute fiche dont l'identifiant TMDB est connu |

Les deux ajouts sont des propriétés **hors constructeur** : `apiVersion` reste à 1 et
les binaires précédents continuent de fonctionner.

### Point d'entrée unique des fiches

Depuis la v1.2.0, les providers n'implémentent plus `load(url)` mais
`details(url)` : `EsProvider.load` est `final` et ajoute l'enrichissement TMDB
(bande-annonce + distribution) quand `tmdbIdOf()` trouve un identifiant —
`externalIds["tmdb"]` ou les conventions de charge utile du dépôt
(`movie|123`, `x:movie:123`, `tv|123|1|1`…).

| 0.7.0 | `categories()`/`HomeCategory`, `rating`/`ratingCount`, `SearchItem.audioLangs`/`genres`, `ExtensionSetting.options`, `ExtensionContext.cacheDir`, préférence globale `app.pref_lang` | v1.4.0 — rangées exposées en catalogues, notes et genres TMDB sur les vignettes, réglage de langue en liste, cache disque 6 h des métadonnées |
| 0.8.0 → 0.12.0 (images) | `EsImages.safeImageUrl()` **rejette toute URL qui ne commence pas par `http(s)://`** : affiche relative, protocole-relative ou encodée en entités = plus aucune image (placeholder « vague »). L'app réessaie en revanche seule avec un `Referer` d'origine (anti-hotlink, 0.10.0) | v1.7.0 — `Text.imageUrl()` absolutise et assainit toute URL d'image (relatif, `//`, `url('…')`, `&amp;`, espaces → `%20`, rejet de `data:`/`javascript:`/`blob:`), appliqué par le socle à l'accueil, à la recherche (y compris les `SearchItem` construits à la main) et aux fiches (affiche, bannière, vignettes d'épisodes) |
| 0.9.0 (fiche serveurs) | La feuille « Serveurs & priorité » regroupe les liens par `VideoLink.server` **exact** et mémorise l'ordre choisi sous forme de chaînes | v1.6.0 — `Text.serverLabel()` canonise tout libellé avant émission : miroir du jour retiré (`vidara23.site` → `Vidara`), langue et qualité retirées (`Vidara VF` → `Vidara`, déjà portées par `audioLang`/`quality`), marques connues normalisées. L'ordre défini par l'utilisateur survit donc aux rotations de domaine |
| 0.9.0 | `ExtensionInfo.iconUrl` (additif) : l'app affiche l'icône **déclarée par le code de l'extension** (pas celle du manifeste) dans l'accueil, la recherche et l'écran Extensions ; fiche serveurs à l'épisode, bibliothèque locale SAF, marqueurs intro/outro — côté app uniquement | v1.5.0 — `EsProvider.iconUrl` dérive le favicon du **domaine réellement utilisé** (`mainUrl`, donc réglage `site_url` ou résolution dynamique) et le passe à `ExtensionInfo` : l'icône suit automatiquement les migrations de domaine |
| 0.8.0 (lot A-1) | `RepositoryIndex.iconUrl` (ajout purement additif), durcissement des images via `EsImages` : PNG/JPEG/WebP/GIF uniquement, **pas de SVG distant**, `javascript:` rejeté, croix rouge si l'icône échoue | v1.5.0 — `repo/index.json` expose `iconUrl` (PNG 256×256 servi par `raw.githubusercontent.com`) et les 17 icônes de sources sont revalidées à chaque revue (une icône pointant vers un domaine mort renvoie 404 et affiche la croix rouge) |
