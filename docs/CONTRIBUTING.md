# Contribuer

## Ajouter une source

1. **Générer le module**

   ```bash
   ./tools/new-extension.sh MonSite fr.endlesssea.ext.monsite \
     fr.endlesssea.ext.monsite.MonSiteExtension 1 1.0.0 \
     "Mon Site" "Films et séries VF" "fr" "MOVIE,SERIES" false monsite.tld
   ```

   Arguments : `<Module> <package> <EntryClass> <versionCode> <versionName>
   <"Nom affiché"> <"description"> <langues> <types> <nsfw> <domaineIcône>`.

2. **Écrire la classe** dans `extensions/MonSite/src/main/kotlin/...`, en
   héritant de `EsProvider` (voir [`ARCHITECTURE.md`](ARCHITECTURE.md)).

3. **Vérifier la compilation**

   ```bash
   kotlinc -nowarn -cp jsoup.jar -d /tmp/out \
     api-stub/src/main/kotlin common/src/main/kotlin extensions/*/src/main/kotlin
   # ou, avec le SDK Android :
   ./gradlew :extensions:MonSite:assembleRelease
   ```

4. **Régénérer l'index**

   ```bash
   python3 tools/build-index.py
   ```

5. **Documenter** la source dans le tableau du `README.md`.

## Style

* Code et commentaires **en français**, y compris les messages d'erreur
  visibles par l'utilisateur.
* Commenter **le protocole du site**, pas le langage : expliquer pourquoi une
  requête est en POST, pourquoi un cookie est nécessaire, quel paramètre est
  ignoré par l'API… Ces notes sont ce qui permet de réparer une source quand
  elle casse.
* Pas de nouvelle dépendance sans nécessité : seuls l'API Endless Sea et Jsoup
  sont disponibles. Si une aide est réutilisable, elle va dans `common/`.
* Jamais de secrets, de jetons personnels ni de comptes dans le dépôt.

## Mettre à jour une source cassée

1. Reproduire : quelle étape échoue (accueil, recherche, fiche, lecture) ?
2. Comparer le HTML/JSON actuel du site avec les regex et endpoints du code.
3. Corriger, **incrémenter `versionCode`** dans `extension.json` (et dans la
   classe si elle le redéfinit), puis régénérer l'index.
4. Décrire le changement de protocole dans le message de commit.

## Domaines qui changent

Beaucoup de sites FR tournent de domaine. Deux stratégies déjà en place :

* **Liste de secours** interrogée au démarrage (Frembed) ;
* **Page d'annonce officielle** analysée puis validée (Xalaflix).

Préférer l'une des deux à une constante figée quand le site le permet.
