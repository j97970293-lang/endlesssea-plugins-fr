# Demandes d'évolution — application Endless Sea

> **État : tout est résolu.** Les points 1 à 5 ont été livrés par l'app **0.7.0**
> (issue [endlesssea#1](https://github.com/j97970293-lang/endlesssea/issues/1),
> fermée) : `categories()` + `HomeCategory`, filtres type/langue transmis,
> `rating`/`ratingCount`, `ExtensionSetting.options`, préférence VF/VOSTFR globale,
> `SearchItem.audioLangs`/`genres`, `ExtensionContext.cacheDir`.
> Les extensions les exploitent depuis la **v1.4.0**.
>
> **§6 à §9 : livrés par l'app 0.25.0** (vérifié dans le code de l'app le 07/10/2026)
> et exploités par ce dépôt depuis la **v2.0.0** :
>
> | § | Demande | Côté app (0.25.0) | Côté extensions (v2.0.0) |
> |---|---|---|---|
> | 6 | Images qui ne s'affichent pas ([#3](https://github.com/j97970293-lang/endlesssea/issues/3)) | `EsImages` mémoïse l'`ImageLoader` (`@Volatile` + `synchronized`) et le récupère via `remember` | rien à faire |
> | 7 | Réglages perdus ([#4](https://github.com/j97970293-lang/endlesssea/issues/4)) | `ExtensionSettingsStore` : séparateur `\|` (légal en XML) + migration des anciennes clés | le contournement v1.9.0 reste en place, devenu inerte (la valeur vive est prioritaire) |
> | 8 | Contrat R8 | `app/proguard-rules.pro` : `-keep class org.jsoup.**`, `kotlin.**`, `kotlinx.coroutines.**` | rien à faire — les `.esx` allégés survivent à un build release minifié |
> | 9 | Serveurs au fil de l'eau ([#5](https://github.com/j97970293-lang/endlesssea/issues/5)) | `EsExtension.loadLinksFlow` (défaut = rejoue `loadLinks`) + appel tolérant `linksFlowCompat` | **`EsProvider` émet chaque lecteur dès qu'il est résolu** — les 17 sources en héritent |
>
> Le document reste ici comme trace des demandes et des contournements historiques.

Ce document liste ce que les extensions **ne peuvent pas faire** aujourd'hui, pourquoi,
et le changement minimal côté application qui le débloquerait. Il sert de base à une
demande dans le dépôt [`endlesssea`](https://github.com/j97970293-lang/endlesssea).

Référence : app `d4fb235` (0.5.0), `extensions-api` `apiVersion 1`.
Toutes les propositions ci-dessous sont **compatibles binairement** si elles sont
ajoutées **hors constructeur** (`var` sur un DTO existant) ou via une méthode
d'interface **avec implémentation par défaut** — comme l'ont été
`ExtensionContext.settings` (0.4.0) et `MediaDetails.characters` (0.5.0).

---

## 1. Catalogues par genre — **bloqué côté app**

**Constat.** L'application n'appelle jamais qu'une seule rangée :

```kotlin
// app/ui/home/HomeViewModel.kt:93, ui/explore/ExploreViewModel.kt:59,
// ui/explore/SeeAllViewModel.kt:49 — identiques
ext.getMainPage(MainPageRequest(category = "main", page = 1))
```

`MainPageRequest.category` existe, mais aucune API ne permet à une extension de
**déclarer** les catégories qu'elle propose, et l'app n'en demande aucune autre.
Nos 17 sources déclarent pourtant de 2 à 14 rangées chacune (Action, Horreur,
Animation, Tendances, Dernières sorties, Planning…) : **tout cela est inutilisé**.

**Demande.**

```kotlin
/** Catégorie proposée par une extension (rangée de catalogue). */
data class HomeCategory(val key: String, val title: String)

interface EsExtension {
    /** Rangées proposées ; la première sert de « main ». Défaut : vide. */
    suspend fun categories(): List<HomeCategory> = emptyList()
}
```

Côté app : appeler `categories()` à l'activation de l'extension, afficher une rangée
par catégorie sur l'accueil (ou une liste déroulante dans Explorer), et passer
`MainPageRequest(category = key)`. Rétrocompatible : une extension qui ne
l'implémente pas garde le comportement actuel.

**Contournement en place (v1.3.0).** Les rangées sont atteignables par la recherche :
`genre:action`, `#horreur`, `:animation`. C'est fonctionnel mais non découvrable —
un utilisateur ne devine pas la syntaxe.

---

## 2. Filtres de recherche — **jamais transmis**

**Constat.** `FilterSet` est riche (`genres`, `years`, `types`, `languages`,
`status`, `qualities`, `extra`) mais l'app envoie systématiquement un objet vide :

```kotlin
// app/ui/search/SearchViewModel.kt:71
ext.search(query, page = 1, filters = FilterSet())
```

**Demande.** Une barre de filtres dans l'écran Recherche (genre, année, type,
langue), remplissant `FilterSet`. Aucun changement d'API nécessaire — uniquement
de l'UI. Nos extensions honorent déjà `filters.genres` depuis la v1.3.0.

---

## 3. Notes / évaluations — **aucun champ dans l'API**

**Constat.** Ni `SearchItem` ni `MediaDetails` n'ont de champ de note. TMDB,
AniList et la plupart des sources en exposent une ; elle est perdue.

**Demande.**

```kotlin
data class MediaDetails(/* … */) {
    var rating: Double? = null        // sur 10
    var ratingCount: Int? = null
}

data class SearchItem(/* … */) {
    var rating: Double? = null        // pour le badge sur la vignette
}
```

Affichage souhaité : badge « ⭐ 7,8 » sur les vignettes de catalogue et ligne
dédiée sur la fiche.

**Contournement en place (v1.3.0).** La note est préfixée au synopsis
(`⭐ 7,8/10 · 4 213 votes (TMDB)`). C'est visible, mais ça pollue le résumé et
reste invisible sur les vignettes.

---

## 4. Langue VF / VOSTFR — **pas de préférence globale, pas de badge**

**Constat.**
- `VideoLink.audioLang` existe et l'app groupe correctement par langue dans la
  feuille « Télécharger » (`DetailsScreen.kt:433`), mais **il n'y a aucune
  préférence utilisateur** « je veux la VF par défaut » : l'ordre de lecture suit
  la qualité, pas la langue.
- `SearchItem` n'a pas de champ langue : impossible d'afficher un badge VF/VOSTFR
  sur les vignettes, ni de filtrer un catalogue.
- `ExtensionSetting.Type.LIST` existe **mais le DTO ne porte aucune liste
  d'options** — le type est donc inutilisable tel quel.

**Demandes.**

```kotlin
// a) options pour les réglages de type LIST
data class ExtensionSetting(
    /* … */
    val options: List<String> = emptyList(),   // libellés proposés
)

// b) langue sur les vignettes
data class SearchItem(/* … */) {
    var audioLangs: List<AudioLang> = emptyList()
}
```

c) Un réglage global dans l'app : « Langue préférée : auto / VF / VOSTFR »,
appliqué au tri des `VideoLink` et transmis aux extensions
(par ex. via `ExtensionContext.settings["app.pref_lang"]`).

**Contournement en place (v1.3.0).** Un réglage texte `pref_lang` par source
(`vf`, `vostfr`, `vo`, `multi`) fait passer les lecteurs de cette langue en tête.
Il faut le saisir source par source, et en texte libre faute de type LIST exploitable.

---

## 5. Vignettes d'épisodes — **déjà possible, rien à demander**

`Episode.thumbnailUrl` existe et l'app l'affiche (`DetailsScreen.kt:619`).
Depuis la v1.3.0, les extensions complètent automatiquement les vignettes
manquantes avec les images d'épisode TMDB dès qu'un identifiant TMDB est connu.

Seule limite résiduelle : les sources d'animes sans correspondance TMDB
(Anime-Sama, FRAnime…) restent sans vignette. Une passerelle AniList/Kitsu côté
extension est possible — c'est de notre ressort, pas d'une évolution de l'app.

---

## 6. Images — aucune ne s'affiche · **anomalie bloquante**

Suivi : [endlesssea#3](https://github.com/j97970293-lang/endlesssea/issues/3).

`SafeAsyncImage` appelle `EsImages.imageLoader(context)` **dans le corps du
composable**, donc à chaque recomposition. `imageLoader()` ne mémorise rien :
il reconstruit un `ImageLoader` complet — `DiskCache` de 512 Mo **sur le même
répertoire**, `MemoryCache`, `OkHttpClient`, puis `Coil.setImageLoader()`
global. Coil verrouille son répertoire de cache : la deuxième instance échoue à
ouvrir le journal et **toutes** les requêtes passent en `State.Error`.

**Conséquence.** Affiches, icônes de sources et icône de dépôt restent sur le
placeholder, et la création en boucle de clients HTTP dégrade les performances.

**Demande.** Mémoïser l'`ImageLoader` (`@Volatile` + `synchronized`, ou
`ImageLoaderFactory` sur l'`Application`) et le récupérer via `remember` côté
composable.

**Côté extensions.** Rien à contourner : v1.7.0 a déjà absolutisé toutes nos URL
d'images (`Text.imageUrl`), nécessaire mais pas suffisant tant que le chargeur
est recréé.

---

## 7. Réglages perdus à chaque redémarrage · **anomalie bloquante**

Suivi : [endlesssea#4](https://github.com/j97970293-lang/endlesssea/issues/4).

`ExtensionSettingsStore` écrit ses `SharedPreferences` avec la clé
`"<id>\u0000<clé>"`. Le caractère NUL est **illégal en XML 1.0** et les
`SharedPreferences` sont persistées en XML : la valeur vit en mémoire pour la
session, puis le fichier ne se relit plus au démarrage suivant.

**Conséquence.** L'utilisateur doit resaisir l'adresse de chaque source à chaque
lancement de l'app.

**Demande.** Séparateur légal (`|`, `::`) ou un fichier de préférences par
extension, avec une migration au premier lancement.

**Contournement (v1.9.0).** `EsProvider.setting()` mémorise la dernière valeur
saisie dans `ExtensionContext.cacheDir` et la rejoue quand `ctx.settings` revient
vide. À retirer une fois l'app corrigée — la valeur vive est déjà prioritaire.

---

## 8. Contrat R8 pour les extensions allégées · **évolution**

Depuis la v1.8.0, les `.esx` n'embarquent plus `kotlin-stdlib`,
`kotlinx-coroutines` ni `jsoup` : `ExtensionLoader` instancie l'extension avec
le `ClassLoader` de l'app comme parent, ces classes sont donc déjà là. Gain :
**1,2 Mo → 97 Ko par extension** (20 Mo → 1,6 Mo pour les 17).

Les APK publiés sont des builds **debug**, sans R8 — le contrat tient. Mais un
build **release** minifié élaguerait ces classes et casserait toutes les
extensions compilées.

**Demande.** Figer le contrat dans `app/proguard-rules.pro` :

```proguard
-keep class org.jsoup.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
```

et le documenter dans `docs/en/04-extension-model.md`, à côté de la règle
`compileOnly` déjà énoncée pour `extensions-api`.

---

## 9. Serveurs : afficher ceux qui sont prêts sans attendre les autres · **évolution**

Suivi : [endlesssea#5](https://github.com/j97970293-lang/endlesssea/issues/5).

**Constat.** `loadLinks(LinkRequest)` renvoie `List<VideoLink>` : une valeur de
retour unique, donc l'app attend que **tous** les lecteurs soient résolus avant
d'afficher quoi que ce soit. Or une fiche agrège facilement 10 à 20 lecteurs
(agrégateurs Movix, MoviesAPI, Frembed, VidSrc…) : les rapides répondent en
moins d'une seconde, les lents — ou ceux qui finissent en timeout de 20 s —
retiennent toute la liste. La feuille « Serveurs & priorité » de la 0.9.0 reste
donc sur son indicateur de chargement alors que la moitié des liens sont déjà
utilisables.

**Demande (compatible binairement).** Ajouter à `EsExtension` une méthode
**avec implémentation par défaut**, qui émet les liens au fil de l'eau :

```kotlin
/** Flux de liens : chaque lecteur résolu est émis dès qu'il est prêt. */
fun loadLinksFlow(data: LinkRequest): Flow<VideoLink> = flow {
    loadLinks(data).forEach { emit(it) }   // repli : comportement actuel
}
```

Côté app : collecter le flux et insérer chaque serveur dans la feuille à son
arrivée (l'ordre de priorité utilisateur s'applique au tri de la liste déjà
reçue), en gardant un indicateur « recherche en cours » tant que le flux n'est
pas terminé. Les extensions qui n'implémentent pas la méthode gardent
exactement le comportement actuel.

**Variante sans `Flow`**, si l'on veut éviter kotlinx.coroutines dans le
contrat : un callback `fun loadLinks(data: LinkRequest, onLink: (VideoLink) -> Unit)`
avec la même implémentation par défaut.

**Côté extensions.** Prêt à l'emploi : `EsProvider.resolveServers()` itère déjà
serveur par serveur et pourrait émettre à chaque itération — une dizaine de
lignes à changer dans le socle, les 17 sources en héritent.

---

## Résumé

| Besoin | État au 07/10/2026 | Qui a bougé |
|---|---|---|
| Catalogue par genre | **livré** (app 0.7.0, exploité v1.4.0) | app |
| Filtres de recherche | **livré** (app 0.7.0, exploité v1.4.0) | app |
| Notes | **livré** (`rating`/`ratingCount`, badge ⭐) | app |
| Badge/préférence VF-VOSTFR | **livré** (`options` pour LIST, `audioLangs`, préférence globale) | app |
| Vignettes d'épisodes | **livré** (complément TMDB depuis v1.3.0) | — |
| Affichage des images | **livré** (app 0.25.0, §6 / [#3](https://github.com/j97970293-lang/endlesssea/issues/3)) | app |
| Persistance des réglages | **livré** (app 0.25.0, §7 / [#4](https://github.com/j97970293-lang/endlesssea/issues/4)) | app |
| Extensions légères en build release | **livré** (règles R8 de l'app, §8) | app |
| Serveurs affichés au fil de l'eau | **livré** (app 0.25.0 `loadLinksFlow` + v2.0.0 côté sources, §9 / [#5](https://github.com/j97970293-lang/endlesssea/issues/5)) | app + api + extensions |
