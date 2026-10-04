# Demandes d'évolution — application Endless Sea

> **État : résolu.** Tous les points de ce document ont été livrés par l'app
> **0.7.0** (issue [endlesssea#1](https://github.com/j97970293-lang/endlesssea/issues/1),
> fermée) : `categories()` + `HomeCategory`, filtres type/langue transmis,
> `rating`/`ratingCount`, `ExtensionSetting.options`, préférence VF/VOSTFR globale,
> `SearchItem.audioLangs`/`genres`, `ExtensionContext.cacheDir`.
> Les extensions les exploitent depuis la **v1.4.0** ; le document reste ici comme
> trace de la demande et des contournements historiques.

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

## 6. Divers (plus petit, mais utile)

| Manque | Conséquence | Demande |
|---|---|---|
| Pas de titre de rangée renvoyé par `getMainPage` | l'app ne peut pas nommer une rangée autrement qu'en dur | inclure le titre dans `HomeCategory` (cf. §1) |
| `SearchItem` sans `genres` | pas de filtrage local des résultats | `var genres: List<String>` hors constructeur |
| Aucun retour d'erreur typé visible | l'utilisateur voit « aucun serveur » sans cause | afficher le message de `SourceException` (déjà typé côté API) |
| Pas de cache fourni par l'hôte | chaque écran refait les requêtes | `ExtensionContext.cacheDir` + aide mémoire simple |

---

## Résumé

| Besoin | Faisable aujourd'hui ? | Qui doit bouger |
|---|---|---|
| Catalogue par genre | contournement par la recherche | **app** (déclaration + appel des catégories) |
| Filtres de recherche | non | **app** (UI qui remplit `FilterSet`) |
| Notes | contournement dans le synopsis | **app** (champ `rating`) |
| Badge/préférence VF-VOSTFR | contournement par réglage de source | **app** (préférence globale, `options` pour LIST, langue sur `SearchItem`) |
| Vignettes d'épisodes | **oui, en place** | — |
