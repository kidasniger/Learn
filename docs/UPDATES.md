# Learn Android — publication des mises à jour

## Vérifications

Les commits sur `main` exécutent les tests unitaires, lint et le build debug. Le job de prévalidation de `main` compile aussi l'APK release signé sans publier de release publique.

## Publier une release publique

1. Incrémente `defaultVersionName` et `defaultVersionCode` dans `app/build.gradle.kts`. Les deux doivent augmenter pour une mise à jour Android.
2. Vérifie que la compilation de `main` est verte.
3. Ouvre **Actions → Validate and publish Learn release → Run workflow** et saisis exactement le `defaultVersionName` configuré.
4. Le workflow refuse les versions invalides, anciennes ou déjà taguées. Il restaure le keystore depuis les secrets, exécute les tests et lint, produit un APK signé et son empreinte SHA-256, puis vérifie une mise à niveau Android en émulateur.
5. Le tag `vX.Y.Z` et la release ne sont créés qu'après la réussite de ces contrôles.

Secrets GitHub Actions requis : `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` et `ANDROID_KEY_PASSWORD`. La clé privée ne doit jamais être commitée ni embarquée dans l'application.

## Fonctionnement du vérificateur intégré

L'application consulte uniquement la dernière release stable publique via l'API officielle GitHub. Les vérifications automatiques sont limitées à une par 24 heures et peuvent aussi être déclenchées manuellement dans Réglages. Le téléchargement utilise le cache privé, un fichier temporaire, une progression réelle, une limite de taille, un contrôle de taille exacte et le SHA-256 si la release publie le digest GitHub ou un fichier `.sha256`.

Avant l'ouverture de l'installateur système, Learn vérifie que le ZIP/APK est lisible, que le package et la version correspondent, que `versionCode` augmente et que l'APK utilise la même clé de signature que l'application installée. Android affiche toujours son installateur système : Learn n'installe rien silencieusement.

**Migration de signature :** certaines anciennes APK v1.2.0 peuvent avoir été signées avec une clé de débogage différente du keystore de publication. Android interdit la mise à jour par-dessus une application signée par une autre clé. L'APK installée doit alors être remplacée manuellement, après sauvegarde des données locales. Les futures versions doivent impérativement conserver la même clé de publication.

## Journal des versions

### v1.5.1 — correction du crash au lancement

- **Cause du crash** : depuis l'ajout du vérificateur de mises à jour, `MainActivity` lisait `androidx.lifecycle.compose.LocalLifecycleOwner`, or lifecycle-runtime-compose **2.8.0** crée ce CompositionLocal sans valeur par défaut (`IllegalStateException: CompositionLocal LocalLifecycleOwner not present`) tant que Compose UI 1.7 ne le fournit pas. L'application utilise Compose UI 1.6 (BOM 2024.05.00) : le crash survenait au premier frame, donc à chaque lancement. Ce code n'avait jamais été publié avant v1.5.0 et le test émulateur n'a pas pu l'attraper (dépassement du délai de démarrage de l'émulateur, 759 s pour un timeout de 600 s).
- **Correctif** : lifecycle-viewmodel-compose et lifecycle-runtime-compose passent de 2.8.0 à **2.8.7**, qui rétablit la compatibilité avec Compose 1.6 via le shim de rétrocompatibilité officiel d'AndroidX.
- **Diagnostic** : nouveau rapporteur de plantage local (`CrashReporter`) — les exceptions non rattrapées sont écrites dans le stockage privé et un bouton « Partager » apparaît dans Réglages pour envoyer le rapport par e-mail ou messagerie, sans ordinateur.
- **CI** : délai de démarrage de l'émulateur porté à 1200 s et la mise à niveau testée en release démarre désormais réellement l'application (`am start` + vérification que le processus survit).
