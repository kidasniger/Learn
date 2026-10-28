package com.kidasniger.learn.updater

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/** Validates bytes and ZIP structure before handing an APK to Android's installer. */
internal object ApkIntegrityVerifier {
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    @Throws(IOException::class)
    fun verify(file: File, expectedSize: Long, expectedSha256: String?): String {
        if (!file.exists() || !file.isFile || file.length() <= 0L) throw IOException("Le fichier APK est absent ou vide.")
        if (file.length() > MAX_APK_BYTES) throw IOException("L'APK dépasse la taille maximale autorisée.")
        if (expectedSize > 0L && file.length() != expectedSize) throw IOException("Taille de l'APK incorrecte : téléchargement incomplet.")
        try {
            ZipFile(file).use { archive ->
                if (!archive.entries().hasMoreElements()) throw IOException("L'APK ne contient aucune entrée.")
            }
        } catch (error: IOException) {
            throw IOException("Le fichier téléchargé n'est pas un APK/ZIP valide.", error)
        } catch (error: Exception) {
            throw IOException("Impossible de vérifier la structure de l'APK.", error)
        }
        val actualHash = sha256(file)
        val expected = expectedSha256?.trim()?.lowercase(Locale.ROOT)
        if (!expected.isNullOrEmpty()) {
            if (!expected.matches(Regex("^[a-f0-9]{64}$"))) throw IOException("L'empreinte SHA-256 publiée est invalide.")
            if (!MessageDigest.isEqual(actualHash.toByteArray(Charsets.US_ASCII), expected.toByteArray(Charsets.US_ASCII))) {
                throw IOException("L'empreinte SHA-256 ne correspond pas. Le fichier a été rejeté.")
            }
        }
        return actualHash
    }

    private const val MAX_APK_BYTES = 512L * 1024L * 1024L
}
