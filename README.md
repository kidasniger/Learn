# Learn

Learn est une application Android de transcription vocale en français basée sur [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Une fois le modèle installé, la transcription s'effectue localement sur le téléphone.

## Fonctionnalités (v1.5.0)

- **Transcription hors ligne** en français et 6 autres langues, avec affichage du texte **au fil de l'eau** pendant le traitement.
- **Horodatages natifs** : les exports SRT/VTT suivent le rythme réel de la voix (segments renvoyés par le moteur).
- **Mode « S'entraîner »** : dictée interactive avec score de similarité, taux d'erreur par mot (WER) et correction mot à mot colorée.
- **Réglages moteur avancés** : nombre de fils, beam search, température, prompt initial de vocabulaire, bascule GPU expérimentale.
- **Historique enrichi** : recherche, favoris, tri, **édition** des transcriptions sauvegardées.
- **Exports multiples** : TXT, PDF, SRT, VTT, copie et partage système.
- **Sauvegarde/restauration JSON** de tout l'historique, plus statistiques d'usage (mots, caractères, langues).

## Installation

1. Téléchargez l'APK depuis [la dernière version publiée](https://github.com/kidasniger/Learn/releases/latest).
2. Installez l'APK sur un appareil Android 8.0 (API 26) ou supérieur.
3. Ouvrez l'application puis choisissez **Télécharger le modèle** puis Tiny Q8 (le plus rapide) ou Base Q5 (recommandé).
4. Choisissez un modèle compatible Whisper, par exemple `ggml-base-q5_1.bin`.
5. Accordez l'autorisation du microphone et lancez une transcription.

Le téléchargement du modèle nécessite une connexion Internet. Après son téléchargement, la transcription ne nécessite pas de connexion réseau. Le modèle reste dans le stockage privé de l'application.

## Compilation

Le workflow GitHub Actions compile et vérifie l'APK à chaque mise à jour de la branche `main`. L'APK de test est disponible dans les artefacts du workflow pendant 14 jours. Les commits de publication commençant par `release:` créent également une release GitHub après un build réussi.

## Limites connues

- La transcription est actuellement configurée pour le français.
- L'enregistrement est limité à 2 minutes par session afin de maîtriser l'utilisation de la mémoire.
- L'APK distribué par la release est un APK de débogage destiné à l'installation et aux essais manuels, pas un paquet signé pour une publication Google Play.


## Modèles conseillés pour Android

- **Tiny Q8** (~42 Mo) : le plus rapide, recommandé pour les appareils modestes.
- **Base Q5** (~57 Mo) : compromis conseillé entre vitesse et précision.
- **Small Q5** (~181 Mo) : plus précis, mais exige davantage de mémoire et de calcul.

Les modèles Large Turbo ont été retirés du menu standard pour éviter les délais excessifs et les saturations mémoire sur les téléphones modestes. Les enregistrements sont limités à deux minutes par session ; lancez plusieurs sessions pour un audio plus long.
