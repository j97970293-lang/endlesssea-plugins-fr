# Limites connues du projet

Document d'honnêteté technique : ce que ce dépôt **ne** garantit **pas**, et pourquoi.
État au 4 octobre 2026 — 17 extensions, v1.1.0, API Endless Sea `apiVersion 1` (app 0.4.0).

---

## 1. Limites structurelles (par nature, non corrigeables)

### 1.1 Dépendance totale aux sites tiers
Chaque extension est un **parseur de HTML/JSON non documenté**. Les sites changent de
sélecteur CSS, de nom de paramètre, d'endpoint ou de domaine sans préavis. Une
extension qui marche aujourd'hui peut être cassée demain sans qu'une seule ligne de
ce dépôt n'ait bougé. C'est la limite dominante : **la durée de vie moyenne d'un
parseur se compte en semaines ou en mois**, pas en années.

Cas les plus fragiles ici :

| Source | Point de rupture probable |
|---|---|
| Xalaflix | protocole Livewire (`POST /livewire/update`), très sensible aux mises à jour du framework |
| Franime | chiffrement maison du lien (base64 → hex → XOR 1 octet) ; la clé peut changer |
| UnJour1Film | actions `admin-ajax.php` + nonce WordPress |
| fsvid.lol / vidzy.cc | XOR videojs dérivé du nom d'hôte : tout changement de domaine casse le déchiffrement |
| Agrégateurs (playerix, movix, zeus, mouve, moviesapi) | clés d'API en dur, endpoints privés |

### 1.2 Domaines miroirs
Plusieurs sources tournent sur des domaines qui changent (saisies, blocages FAI).
**Depuis la v1.1.0, les 16 sources concernées exposent un réglage `site_url`** que
l'utilisateur peut corriger lui-même sans attendre une mise à jour (nécessite
l'application en 0.4.0 ou plus). Reste une limite : il faut *connaître* la nouvelle
adresse, et un changement de domaine s'accompagne souvent d'un changement de
structure HTML, que le réglage ne corrige pas.

### 1.3 Protections anti-bot
Il n'y a **pas de contournement de Cloudflare**. Le `CloudflareKiller` de CloudStream
n'a pas d'équivalent natif : les 3 modules concernés remontent simplement
`SourceException.CaptchaRequired`, et l'utilisateur doit résoudre le défi via la
WebView de l'application. Si un site passe en « Under Attack », la source devient
inutilisable sans intervention.

### 1.4 Disponibilité réseau et géoblocage
Les tests ont été faits depuis un conteneur européen. Certaines sources filtrent par
IP/pays, d'autres exigent un referer précis. Un lecteur qui renvoie 403 chez
l'utilisateur alors que l'extension est correcte est un scénario attendu.

---

## 2. Limites de validation (ce qui n'a pas été prouvé)

C'est la limite la plus importante à connaître avant d'ouvrir un ticket.

- **Aucune exécution réelle n'a été faite.** La correction a été vérifiée par
  **compilation Kotlin** (typecheck, exit 0) et par **build Android en CI**. Le
  sandbox ne disposait ni d'Android SDK local, ni d'émulateur, ni de l'application
  Endless Sea : **aucun `homeRows`, `search`, `episode` ou `servers` n'a été appelé
  pour de vrai**.
- Conséquence : le code compile et s'installe, mais **chaque source doit être
  considérée comme « non testée à l'exécution »** tant que tu ne l'as pas ouverte
  dans l'app. Les erreurs attendues sont des listes vides, des `NullPointerException`
  sur un sélecteur disparu, ou zéro serveur retourné.
- **Pas de tests automatisés** : aucun test unitaire, aucun test d'intégration,
  aucun « smoke test » CI qui irait vraiment interroger les sites. La CI valide la
  compilation et l'empaquetage, rien de plus.
- Les protocoles de site ont été **reconstruits par lecture du code CloudStream
  d'origine**, pas par observation du trafic réseau actuel. Si le plugin CloudStream
  source était lui-même déjà périmé sur un point, le portage l'est aussi.

---

## 3. Limites de périmètre

- **17 sources sur 26.** Les 9 fournisseurs NSFW du dépôt d'origine (Adkami Hentai,
  HentaiCity, HentaiFap, HentaiHaven, HentaiStream, HentaiVost, Pornovore,
  TrixHentai, Xvideos) ont été exclus à ta demande. Les réintégrer est mécanique
  (le scaffolder et `common/` sont prêts) mais représente du travail.
- **Pas de parité fonctionnelle 1:1** avec CloudStream. Fonctionnalités abandonnées
  au portage :
  - le « link doctor » OkHttp (réécriture d'URL mortes) ;
  - les boîtes de dialogue de réglages `AlertDialog` (remplacées par des
    `ExtensionSetting` de type TEXT, moins riches) ;
  - le `loadExtractor` global de CloudStream, qui donnait accès à ~150 extracteurs
    maintenus par la communauté. Ici, seuls les extracteurs réécrits dans
    `common/Extractors.kt` existent : **un lecteur inconnu ne sera pas résolu**, il
    sera au mieux renvoyé en `EMBED` brut.
- **Pas de téléchargement garanti.** `downloads` est déclaré dans les capacités, mais
  un flux HLS protégé par referer/token court peut échouer au téléchargement même
  s'il se lit en streaming.
- **Sous-titres** : gérés uniquement là où le lecteur les expose explicitement
  (principalement la famille Vidara/StreamUp et quelques agrégateurs). La plupart des
  sources VF n'en fournissent aucun.
- **Pas d'authentification** : `auth: false` partout. Aucun compte, aucune source
  premium, aucune synchronisation de progression (Trakt, AniList, MAL).

---

## 4. Limites techniques d'implémentation

- **TMDB avec une clé d'API partagée en dur** (`common/Tmdb.kt`), héritée du plugin
  d'origine. Elle peut être révoquée ou rate-limitée à tout moment ; 3 extensions en
  dépendent pour tout leur catalogue, et plusieurs autres pour les métadonnées.
  Aucune gestion de quota, aucun repli.
- **Pas de cache**. Chaque ouverture d'écran refait les requêtes réseau. Les
  extensions qui interrogent plusieurs agrégateurs en parallèle (WaveWatch,
  Afterdark, Movix) peuvent émettre une dizaine d'appels pour une seule fiche.
- **Pas de parallélisme maîtrisé** : les appels d'agrégateurs sont enveloppés dans
  des `runCatching` et fusionnés, mais sans timeout global ni annulation fine. Une
  source lente peut rallonger l'affichage des serveurs.
- **Gestion d'erreurs silencieuse** : le pattern `runCatching` masque les causes
  réelles. En cas de panne, l'utilisateur voit « aucun serveur » sans diagnostic, et
  il n'y a **aucune journalisation structurée** exploitable.
- **Pas de pagination réelle partout** : quelques sources renvoient `hasNext` de
  façon approximative (déduit de la présence de résultats), ce qui peut provoquer un
  défilement infini vide.
- **`.esx` non signés / non vérifiés côté chaîne d'approvisionnement.** L'index porte
  bien un `sha256` par fichier (intégrité), mais il n'y a **pas de signature
  cryptographique** attestant l'auteur. Quiconque contrôle le dépôt GitHub peut
  publier n'importe quel binaire.
- **`minAppVersion: 1` déclaré partout**, sans avoir été vérifié contre une version
  réelle de l'application. Si l'API évolue vers `apiVersion 2`, les 17 extensions
  casseront simultanément.
- **Icônes via `google.com/s2/favicons`** : dépendance externe, et icônes absentes si
  le domaine de la source change.

---

## 5. Limites d'exploitation

- **Mise à jour = push de tag.** Toute correction impose : modifier le module, monter
  `version`/`versionName` dans son `extension.json`, relancer `build-index.py`,
  commiter, puis `git tag v1.0.x && git push origin v1.0.x`. Pas de publication
  partielle : la CI reconstruit et republie les 17.
- **Mainteneur unique, pas de CI de régression.** Rien ne détecte automatiquement
  qu'une source est tombée ; la découverte se fait par l'utilisateur.
- **Pas de versionnage par extension dans l'index publié** : `releases/latest`
  pointe toujours vers le dernier tag global ; impossible d'épingler une extension à
  une version antérieure.

---

## 6. Limites juridiques et éthiques

Ces extensions indexent et lisent des contenus hébergés par des tiers, pour la
plupart **sans autorisation des ayants droit**. Le code est fourni à titre technique
(MIT), mais son usage relève de la responsabilité de l'utilisateur et peut être
illégal selon la juridiction. Le projet n'héberge aucun contenu et ne peut rien
garantir quant à la licéité, la qualité ou la sécurité des flux tiers.

---

## 7. Ce qui, en revanche, est solide

Pour équilibrer : la **structure** du projet n'est pas une limite. Les 17 modules
partagent `common/` (2 000 lignes : HTTP, JSON, extracteurs, agrégateurs, TMDB), le
scaffolder crée une extension complète en une commande, la CI construit et publie
sans intervention, et l'index est désormais conforme au schéma de l'app. **Réparer
une source cassée est typiquement une affaire de quelques lignes dans un seul
fichier** — c'était l'objectif du portage natif plutôt que d'une couche de
compatibilité.
