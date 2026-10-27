package com.kidasniger.learn.data

import kotlinx.coroutines.flow.Flow

class TranscriptionRepository(private val dao: TranscriptionDao) {
    val allTranscriptions: Flow<List<TranscriptionEntity>> = dao.getAllTranscriptions()

    suspend fun insert(transcription: TranscriptionEntity) {
        dao.insertTranscription(transcription)
    }

    suspend fun update(transcription: TranscriptionEntity) {
        dao.updateTranscription(transcription)
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }
}
