# Endless Sea · Extensions FR

Sources **francophones** pour l'application [Endless Sea](https://github.com/j97970293-lang/endlesssea) :
films, séries, animes et chaînes TV en direct.

Ce dépôt est le portage **natif** des plugins CloudStream de
[`plugin-fr`](https://github.com/j97970293-lang/plugin-fr) vers l'API
d'extension d'Endless Sea (`dev.endlesssea.extensions.api`). Il n'y a **aucune
couche de compatibilité CloudStream** : chaque provider a été réécrit sur
`EsExtension`. Voir [`MIGRATION.md`](MIGRATION.md) pour le détail de la
conversion.

> Les providers NSFW du dépôt d'origine ne sont **pas** portés ici.

## Installation dans l'application

1. Ouvrir Endless Sea → **Extensions** → **Dépôts** → **Ajouter**.
2. Coller l'URL de l'index :

   ```
   https://github.com/j97970293-lang/endlesssea-plugins-fr/releases/latest/download/index.json
   ```

3. Installer les extensions souhaitées depuis la liste.

Les fichiers `.esx` peuvent aussi être téléchargés un par un depuis l'onglet
**Releases** et installés manuellement.

## Réglages par source (Endless Sea 0.4.0+)

Depuis la version 0.4.0 de l'application, les réglages déclarés par une extension
sont éditables dans **Paramètres → Extensions — réglages par source**, puis injectés
dans `ExtensionContext.settings`.

Les 17 extensions exposent désormais :

| Réglage | Sources | Effet |
|---|---|---|
| `site_url` | 16 sources (toutes sauf Télé FR Direct) | Impose l'adresse du site. Prioritaire sur le domaine par défaut **et** sur la résolution automatique (Xalaflix, Frembed). Accepte `exemple.com` comme `https://exemple.com/`. Vide = valeur d'usine. |
| `playlist_url`, `franco_playlist` | Télé FR Direct | Playlists M3U à charger (ex. `…/countries/mg.m3u`). |

Concrètement : quand une source change de domaine, **plus besoin d'attendre une mise
à jour** — colle la nouvelle adresse dans le réglage.

## Extensions disponibles (17)

| Extension | Contenus | Particularités du site |
|---|---|---|
| **Anime-Sama** | animes VF/VOSTFR | catalogue `/catalogue/`, `episodes.js`, `panneauAnime()` |
| **AnimeSite** | animes | API `/api/medias`, jeton de lecture `POST /api/stream/token` |
| **AnimoFlix** | animes VF/VOSTFR | `<select>` de lecteurs, autocomplétion, saisons spéciales |
| **Afterdark** | films, séries (VOSTFR) | proxy TMDB + agrégateurs FR (zeus, wiflix, playerix…) |
| **CineStream** | films | Cloudflare, lecteurs `/player/{tmdb}/{index}` |
| **Flemmix** | films, séries | DLE + `loadVideo()`, bot-shield (cookie `h_check`) |
| **FRAnime** | animes | API FRAnime + métadonnées Kitsu, URLs `watch2` chiffrées |
| **Frembed** | films, séries | réseau de lecteurs indexé TMDB, recherche sondée |
| **French Stream** | films, séries | DLE, recherche POST, `film_api.php`, `series/{id}.js` |
| **1Jour1Film** | films, séries | WordPress `admin-ajax`, scripts inline base64 |
| **Movix** | films, séries | catalogue TMDB-FR + réseau `api.movix.men` |
| **Purstream** | films, séries | API `api.purstream.ad/api/v1` |
| **Télé FR Direct** | TV en direct | listes IPTV-org + chaînes YouTube live |
| **Vostfree** | animes, films | DLE, jetons de lecteur par épisode |
| **WaveWatch** | films, séries, animes, TV | API maison (proxy TMDB), `wwembed`, zeus/mouve |
| **Xalaflix** | films, séries | domaine tournant, saisons via Livewire |
| **Zenix** | films, séries | `ajax/search/suggest`, `selectStream()`, 1Embed |

L'index machine (identifiants, versions, URLs de téléchargement) est dans
[`repo/index.json`](repo/index.json).

## Construire soi-même

Prérequis : JDK 17 et le SDK Android (compileSdk 35).

```bash
./gradlew packageAll        # → build/esx/<Nom>-<version>.esx
python3 tools/build-index.py # → repo/index.json
```

La CI GitHub Actions (`.github/workflows/build.yml`) fait les deux à chaque
push sur `main`, et publie une release à chaque tag `v*`.

## Ajouter une extension

```bash
./tools/new-extension.sh MonSite fr.endlesssea.ext.monsite \
  fr.endlesssea.ext.monsite.MonSiteExtension 1 1.0.0 \
  "Mon Site" "Description FR" "fr" "MOVIE,SERIES" false monsite.tld
```

Le script crée le module Gradle, le `extension.json` et un squelette de classe
héritant de `EsProvider` (voir [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)).

## Structure

```
api-stub/     copie de dev.endlesssea.extensions.api (compileOnly)
common/       socle partagé : HTTP, JSON, extracteurs, agrégateurs, TMDB…
extensions/   un module Gradle par source
repo/         index.json consommé par l'application
tools/        scaffolder + générateur d'index
docs/         architecture et guide de contribution
```

## Avertissement

Ces extensions ne stockent ni n'hébergent aucun contenu : elles se contentent
d'indexer des sites publics. Vous êtes responsable de l'usage que vous en
faites et du respect du droit applicable dans votre pays.
