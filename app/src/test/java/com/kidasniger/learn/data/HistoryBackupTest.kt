package com.kidasniger.learn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryBackupTest {

    private val items = listOf(
        TranscriptionEntity(id = 1L, title = "Cours", text = "Première leçon de français.", timestamp = 1_700_000_000_000L, language = "fr"),
        TranscriptionEntity(id = 2L, title = "Note", text = "Second enregistrement.", timestamp = 1_700_000_100_000L, language = "en")
    )

    @Test
    fun `round trip preserves every field`() {
        val json = HistoryBackup.toJson(items, 1_700_000_200_000L)
        val restored = HistoryBackup.fromJson(json)
        assertEquals(items.size, restored.size)
        assertEquals(items[0].id, restored[0].id)
        assertEquals(items[0].title, restored[0].title)
        assertEquals(items[0].text, restored[0].text)
        assertEquals(items[0].timestamp, restored[0].timestamp)
        assertEquals(items[0].language, restored[0].language)
        assertEquals(items[1].language, restored[1].language)
    }

    @Test
    fun `json declares the format and count`() {
        val json = HistoryBackup.toJson(items, 42L)
        assertTrue(json.contains("\"format\": \"learn-backup\""))
        assertTrue(json.contains("\"count\": 2"))
        assertTrue(json.contains("\"exportedAt\": 42"))
    }

    @Test
    fun `invalid json is rejected with a readable message`() {
        try {
            HistoryBackup.fromJson("ceci n'est pas du json {")
            fail("Une IllegalArgumentException était attendue.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("JSON"))
        }
    }

    @Test
    fun `foreign json without learn format is rejected`() {
        try {
            HistoryBackup.fromJson("""{"hello":"world"}""")
            fail("Une IllegalArgumentException était attendue.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("sauvegarde Learn"))
        }
    }

    @Test
    fun `entries without text are skipped`() {
        val json = """
            {"format":"learn-backup","version":1,"exportedAt":0,"count":2,"transcriptions":[
              {"id":1,"title":"Vide","text":"   ","timestamp":1,"language":"fr"},
              {"id":2,"title":"OK","text":"Contenu utile","timestamp":2,"language":"fr"}
            ]}
        """.trimIndent()
        val restored = HistoryBackup.fromJson(json)
        assertEquals(1, restored.size)
        assertEquals("OK", restored[0].title)
    }
}
