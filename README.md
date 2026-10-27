# Learn

Learn est une application Android de transcription vocale en français basée sur [whisper.cpp](https://github.com/ggerganov/whisper.cpp). Une fois le modèle installé, la transcription s'effectue localement sur le téléphone.

## Installation

1. Téléchargez l'APK depuis [la dernière version publiée](https://github.com/kidasniger/Learn/releases/latest).
2. Installez l'APK sur un appareil Android 8.0 (API 26) ou supérieur.
3. Ouvrez l'application puis choisissez **Télécharger le modèle** ou **Importer depuis l'appareil**.
4. Choisissez un modèle compatible Whisper, par exemple `ggml-large-v3-turbo-q5_0.bin`.
5. Accordez l'autorisation du microphone et lancez une transcription.

Le téléchargement du modèle nécessite une connexion Internet. Après son téléchargement, la transcription ne nécessite pas de connexion réseau. Le modèle reste dans le stockage privé de l'application.

## Compilation

Le workflow GitHub Actions compile et vérifie l'APK à chaque mise à jour de la branche `main`. L'APK de test est disponible dans les artefacts du workflow pendant 14 jours. Les commits de publication commençant par `release:` créent également une release GitHub après un build réussi.

## Limites connues

- La transcription est actuellement configurée pour le français.
- L'enregistrement est limité à 10 minutes par session afin de maîtriser l'utilisation de la mémoire.
- L'APK distribué par la release est un APK de débogage destiné à l'installation et aux essais manuels, pas un paquet signé pour une publication Google Play.
