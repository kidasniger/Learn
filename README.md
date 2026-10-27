# Learn 2.0

Learn est une application Android de transcription locale fondée sur [whisper.cpp](https://github.com/ggml-org/whisper.cpp). Après l'installation d'un modèle, les transcriptions sont traitées sur le téléphone sans envoyer l'audio à un serveur.

## Capacités de cette préversion

- Enregistrer depuis le microphone avec arrêt, pause et reprise ; limite de cinq minutes par session pour préserver la mémoire.
- Importer des fichiers audio compatibles avec les décodeurs Android. Les fichiers sont décodés en blocs de 30 secondes et le texte apparaît au fur et à mesure du traitement des blocs.
- Afficher les segments horodatés, éditer le texte, lire le texte par synthèse vocale et partager la transcription avec les applications installées.
- Exporter en TXT, PDF, SRT et WebVTT.
- Télécharger ou importer plusieurs modèles Whisper et choisir lequel charger. Les modèles restent dans des fichiers distincts ; remplacer ou charger un autre modèle ne supprime pas les modèles conservés.
- Choisir la détection automatique ou une langue précise (français, anglais, espagnol, allemand, portugais, arabe, haoussa et swahili). Les résultats varient selon le modèle et la qualité audio ; chaque combinaison de langue et d'appareil doit encore être évaluée.
- Traduire la transcription avec ML Kit vers une langue cible prise en charge. Le modèle source/cible est téléchargé au premier usage et conservé localement ; la traduction se fait ensuite sur l'appareil. La langue source est détectée automatiquement si le mode de transcription était réglé sur « auto ».
- Choisir le mode rapide ou le mode haute précision.
- Rechercher dans les transcriptions sauvegardées, classer les éléments par dossier/cours/réunion, ajouter des favoris et des commentaires personnels ; confirmer les suppressions.
- Obtenir un résumé de sélection de phrases, des idées clés candidates, des tâches candidates et des fiches de révision au moyen de règles locales simples.
- Lancer un test du moteur avec l'audio anglais de démonstration de whisper.cpp (téléchargement Internet requis).
- Utiliser le mode sombre, la vérification de stockage et un diagnostic du microphone, des autorisations et du chargement de Whisper.

Les transcriptions sauvegardées sont conservées dans une base Room locale, y compris après fermeture de l'application. Les anciens enregistrements sauvegardés restent accessibles après la migration de la base de données.

## Installer et choisir un modèle

1. Ouvrez le menu **Modèle**.
2. Téléchargez Tiny Q8 (~42 Mo) pour privilégier la vitesse, Base Q5 (~57 Mo) comme compromis, ou Small Q5 (~181 Mo) si le téléphone dispose de davantage de mémoire.
3. Pour charger l'un des modèles déjà installés, sélectionnez-le dans la liste du menu. Les modèles téléchargés sont conservés séparément.
4. Autorisez le microphone si vous souhaitez enregistrer. L'import d'un fichier audio fonctionne avec le sélecteur de documents Android.

Le téléchargement du modèle nécessite Internet. La transcription elle-même peut ensuite être effectuée hors ligne. Les modèles sont stockés dans l'espace privé de l'application ; ils ne sont pas accessibles comme des fichiers ordinaires sans export.

## Tests et compilation

Le workflow GitHub Actions s'exécute sur les Pull Requests vers `main` et sur les mises à jour de `main`. Il exécute `assembleDebug`, les tests unitaires, lint et un test de démarrage de l'application sur un émulateur Android. Un APK de débogage est conservé comme artefact de CI pendant 14 jours.

## Distribution signée

L'APK de production ne doit pas utiliser la clé de débogage. Le workflow de release ne publie une version que lorsque les tests réussissent, que le commit commence par `release:` et que la variable de dépôt `LEARN_RELEASE_READY` vaut `true`.

Configurer dans **Settings → Secrets and variables → Actions** les secrets suivants :
- `LEARN_KEYSTORE_BASE64` : contenu Base64 d'un keystore de release privé.
- `LEARN_KEYSTORE_PASSWORD`
- `LEARN_KEY_ALIAS`
- `LEARN_KEY_PASSWORD`

Ne pas committer le keystore ni son mot de passe dans le dépôt. L'activation de `LEARN_RELEASE_READY=true` doit se faire uniquement après avoir vérifié ces secrets et sauvegardé la clé de signature en lieu sûr.

## Limites connues à cette étape

- Le texte de la transcription est progressif pendant le traitement des blocs ; la transcription ne s'exécute pas en continu pendant que le microphone est encore en train d'enregistrer.
- Le détecteur de silence supprime les blocs silencieux et réduit les silences aux bords des blocs ; il ne supprime pas encore tous les silences internes d'un long passage.
- Les résumés, idées et tâches sont des extractions heuristiques locales, pas une génération par un modèle d'IA.
- La traduction dépend des langues prises en charge par ML Kit et du téléchargement préalable du modèle de langue. Elle ne fonctionne pas pour toutes les langues détectées par Whisper.
- La restauration d'un enregistrement micro non sauvegardé après fermeture forcée n'est pas encore fournie. Le test de démonstration télécharge un court extrait anglais de whisper.cpp et nécessite Internet.
- Le workflow de production est prêt pour une signature protégée, mais aucune release de production ne doit être publiée tant que les secrets de signature ne sont pas configurés et que la build de release n'a pas été vérifiée sur l'appareil cible.
