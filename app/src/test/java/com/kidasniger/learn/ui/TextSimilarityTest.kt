package com.kidasniger.learn.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSimilarityTest {

    @Test
    fun `identical texts have full similarity and zero wer`() {
        val report = TextSimilarity.analyze(
            "Le chat noir dort.",
            "Le chat noir dort !"
        )
        assertEquals(1f, report.similarity, 0.001f)
        assertEquals(0f, report.wer, 0.001f)
        assertEquals(4, report.referenceWords)
        assertTrue(report.diff.all { it.type == TextSimilarity.DiffType.MATCH })
    }

    @Test
    fun `punctuation and case are ignored but accents are kept`() {
        assertEquals("école primaire", TextSimilarity.normalize("École, primaire !"))
        val report = TextSimilarity.analyze("L'école est ouverte", "l école est ouverte")
        assertTrue("similarité attendue élevée, obtenue ${report.similarity}", report.similarity >= 0.99f)
    }

    @Test
    fun `substitution is counted as one error`() {
        val report = TextSimilarity.analyze("je mange une pomme", "je mange une poire")
        assertEquals(1, report.diff.count { it.type == TextSimilarity.DiffType.SUBSTITUTION })
        assertEquals(0.75f, report.similarity, 0.001f)
        assertEquals(0.25f, report.wer, 0.001f)
    }

    @Test
    fun `missing word is a deletion and extra word is an insertion`() {
        val report = TextSimilarity.analyze("je mange une pomme verte", "je mange pomme verte")
        assertEquals(1, report.diff.count { it.type == TextSimilarity.DiffType.DELETION })
        assertEquals(5, report.referenceWords)
        assertEquals(4, report.hypothesisWords)

        val extra = TextSimilarity.analyze("je mange pomme", "je mange très pomme")
        assertEquals(1, extra.diff.count { it.type == TextSimilarity.DiffType.INSERTION })
    }

    @Test
    fun `empty hypothesis yields zero similarity`() {
        val report = TextSimilarity.analyze("bonjour le monde", "")
        assertEquals(0f, report.similarity, 0.001f)
        assertEquals(1f, report.wer, 0.001f)
    }

    @Test
    fun `both empty yields perfect score without crash`() {
        val report = TextSimilarity.analyze("   ", "")
        assertEquals(1f, report.similarity, 0.001f)
        assertEquals(0, report.diff.size)
    }

    @Test
    fun `word levenshtein matches known distances`() {
        assertEquals(0, TextSimilarity.wordLevenshtein(listOf("a", "b"), listOf("a", "b")))
        assertEquals(1, TextSimilarity.wordLevenshtein(listOf("a", "b"), listOf("a", "c")))
        assertEquals(3, TextSimilarity.wordLevenshtein(listOf("a", "b", "c"), listOf("x")))
        assertEquals(1, TextSimilarity.wordLevenshtein(listOf("a"), listOf("a", "b")))
    }

    @Test
    fun `character levenshtein matches known distances`() {
        assertEquals(3, TextSimilarity.levenshtein("kitten", "sitting"))
        assertEquals(0, TextSimilarity.levenshtein("chat", "chat"))
        assertEquals(2, TextSimilarity.levenshtein("flaw", "lawn"))
    }
}
