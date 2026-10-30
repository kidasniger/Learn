package com.kidasniger.learn.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sérialisation/désérialisation JSON de l'historique complet pour la
 * sauvegarde et la restauration. Implémentation pure (org.json), testable
 * sur JVM.
 */
object HistoryBackup {

    private const val FORMAT = "learn-backup"
    private const val VERSION = 1

    fun toJson(items: List<TranscriptionEntity>, exportedAtMs: Long): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("text", item.text)
                    .put("timestamp", item.timestamp)
                    .put("language", item.language)
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", exportedAtMs)
            .put("count", items.size)
            .put("transcriptions", array)
            .toString(2)
    }

    /**
     * Analyse un contenu de sauvegarde et renvoie les transcriptions.
     * Lève [IllegalArgumentException] avec un message utilisateur si le
     * fichier est invalide.
     */
    fun fromJson(json: String): List<TranscriptionEntity> {
        val root = try {
            JSONObject(json)
        } catch (error: Exception) {
            throw IllegalArgumentException("Le fichier n'est pas un JSON valide.")
        }
        if (root.optString("format") != FORMAT) {
            throw IllegalArgumentException("Ce fichier n'est pas une sauvegarde Learn.")
        }
        val array = try {
            root.getJSONArray("transcriptions")
        } catch (error: Exception) {
            throw IllegalArgumentException("Sauvegarde invalide : liste des transcriptions absente.")
        }
        val items = ArrayList<TranscriptionEntity>(array.length())
        for (index in 0 until array.length()) {
            val entry = try {
                array.getJSONObject(index)
            } catch (error: Exception) {
                throw IllegalArgumentException("Sauvegarde invalide : entrée numéro " + (index + 1) + ".")
            }
            val text = entry.optString("text")
            if (text.isBlank()) continue
            items.add(
                TranscriptionEntity(
                    id = entry.optLong("id", 0L),
                    title = entry.optString("title").ifBlank { "Transcription importée" },
                    text = text,
                    timestamp = entry.optLong("timestamp", System.currentTimeMillis()),
                    language = entry.optString("language", "fr").ifBlank { "fr" }
                )
            )
        }
        return items
    }
}
