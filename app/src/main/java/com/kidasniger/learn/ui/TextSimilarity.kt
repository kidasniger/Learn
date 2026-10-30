package com.kidasniger.learn.ui

/**
 * Analyse comparative entre un texte de référence et une transcription
 * (score de dictée du mode « S'entraîner »). Implémentation purement
 * Kotlin, testable sur JVM sans dépendance Android.
 */
object TextSimilarity {

    enum class DiffType { MATCH, SUBSTITUTION, DELETION, INSERTION }

    data class WordDiff(
        val type: DiffType,
        val reference: String?,
        val hypothesis: String?
    )

    data class Report(
        val similarity: Float,
        val wer: Float,
        val referenceWords: Int,
        val hypothesisWords: Int,
        val diff: List<WordDiff>
    )

    private val punctuation = Regex("""[.,;:!?«»"'()\[\]{}\-–—…/\\]+""")

    /** Minuscule, retire la ponctuation et normalise les espaces. Les accents sont conservés. */
    fun normalize(text: String): String {
        val lowered = text.lowercase(LocaleSafe.current())
            .replace('’', '\'')
        return punctuation.replace(lowered, " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    fun tokenize(text: String): List<String> =
        normalize(text).split(" ").filter { it.isNotBlank() }

    /** Distance de Levenshtein classique sur les mots. */
    fun wordLevenshtein(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var previous = IntArray(b.size + 1) { it }
        val current = IntArray(b.size + 1)
        for (i in 1..a.size) {
            current[0] = i
            for (j in 1..b.size) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return previous[b.size]
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        return previous[b.length]
    }

    /**
     * Compare la référence et la transcription : renvoie la similarité
     * (1.0 = identique), le WER (taux d'erreur par mot) et l'alignement
     * mot à mot pour l'affichage coloré.
     */
    fun analyze(reference: String, hypothesis: String): Report {
        val ref = tokenize(reference)
        val hyp = tokenize(hypothesis)
        if (ref.isEmpty() && hyp.isEmpty()) {
            return Report(1f, 0f, 0, 0, emptyList())
        }
        if (ref.isEmpty()) {
            return Report(0f, 0f, 0, hyp.size, hyp.map { WordDiff(DiffType.INSERTION, null, it) })
        }
        if (hyp.isEmpty()) {
            return Report(0f, 1f, ref.size, 0, ref.map { WordDiff(DiffType.DELETION, it, null) })
        }

        // DP avec backtracking pour produire un alignement lisible.
        val n = ref.size
        val m = hyp.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) dp[i][0] = i
        for (j in 0..m) dp[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val substitution = dp[i - 1][j - 1] + if (ref[i - 1] == hyp[j - 1]) 0 else 1
                dp[i][j] = minOf(substitution, dp[i - 1][j] + 1, dp[i][j - 1] + 1)
            }
        }

        val diff = ArrayList<WordDiff>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && ref[i - 1] == hyp[j - 1] &&
                    dp[i][j] == dp[i - 1][j - 1] -> {
                    diff.add(WordDiff(DiffType.MATCH, ref[i - 1], hyp[j - 1]))
                    i--; j--
                }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + 1 -> {
                    diff.add(WordDiff(DiffType.SUBSTITUTION, ref[i - 1], hyp[j - 1]))
                    i--; j--
                }
                i > 0 && dp[i][j] == dp[i - 1][j] + 1 -> {
                    diff.add(WordDiff(DiffType.DELETION, ref[i - 1], null))
                    i--
                }
                else -> {
                    diff.add(WordDiff(DiffType.INSERTION, null, hyp[j - 1]))
                    j--
                }
            }
        }
        diff.reverse()

        val distance = dp[n][m]
        val similarity = 1f - distance.toFloat() / ref.size
        val wer = distance.toFloat() / ref.size
        return Report(similarity, wer, ref.size, hyp.size, diff)
    }

    private object LocaleSafe {
        fun current(): java.util.Locale = java.util.Locale.ROOT
    }
}
