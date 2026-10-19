package app.kelimo.learn

import java.text.Normalizer

enum class WordOrder { Fixed, GermanV2, VerbFinal }

/** Outcome of a deterministic check. No model is involved in grading, ever. */
enum class Verdict { Correct, Typo, Wrong }

data class Grade(val verdict: Verdict, val expected: String, val note: String? = null) {
    val passed: Boolean get() = verdict != Verdict.Wrong
}

object Grader {
    /** Small words where one letter changes the grammar. A "typo" here is a real mistake. */
    private val critical = setOf(
        "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "einer", "eines",
        "kein", "keine", "keinen", "keinem", "keiner", "mein", "meine", "meinen", "meinem", "dein", "deine",
        "er", "es", "sie", "ihr", "ihn", "ihm", "im", "in", "an", "am", "zu", "zum", "zur", "bin", "bist", "ist", "sind",
        "a", "an", "the", "is", "are", "am", "was", "were", "he", "she", "his", "her",
    )

    fun normalize(s: String): String = s
        .lowercase()
        .replace('’', '\'')
        .replace(Regex("[.,!?;:¿¡\"„“”«»()\\-–—…]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Folds German special letters so "Strasse" == "Straße" and "Mueller" == "Müller". */
    fun fold(s: String): String = normalize(s)
        .replace("ß", "ss").replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        .let { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") }

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotBlank() }

    /** Typed answer against a set of accepted answers. */
    fun typed(input: String, accepted: List<String>): Grade {
        val primary = accepted.first()
        val ni = normalize(input)
        if (ni.isEmpty()) return Grade(Verdict.Wrong, primary)
        for (a in accepted) if (normalize(a) == ni) return Grade(Verdict.Correct, a)
        for (a in accepted) if (fold(a) == fold(input)) return Grade(Verdict.Typo, a, "umlaut")
        for (a in accepted) {
            val at = tokens(a); val it = tokens(input)
            if (at.size != it.size) continue
            var typos = 0; var fatal = false
            for (i in at.indices) {
                if (at[i] == it[i]) continue
                val d = levenshtein(fold(at[i]), fold(it[i]))
                if (at[i] in critical || it[i] in critical || d > allowedTypos(at[i])) { fatal = true; break }
                typos += d
            }
            if (!fatal && typos <= maxOf(1, a.length / 8)) return Grade(Verdict.Typo, a, "typo")
        }
        return Grade(Verdict.Wrong, primary)
    }

    /** Word-bank answer: order matters, punctuation and case don't. */
    fun tiles(chosen: List<String>, answer: String, alternatives: List<String> = emptyList(), order: WordOrder = WordOrder.Fixed): Grade {
        val c = tokens(chosen.joinToString(" "))
        if ((listOf(answer) + alternatives).any { tokens(it) == c }) return Grade(Verdict.Correct, answer)
        // German allows fronting other parts ("Heute gehe ich ...") as long as the finite verb
        // stays second and the sentence end stays put. Same words, that shape: accept, show the model answer.
        val a = tokens(answer)
        val sameWords = a.size >= 3 && c.sorted() == a.sorted()
        val ok = when (order) {
            WordOrder.Fixed -> false
            WordOrder.GermanV2 -> sameWords && a.size >= 4 && c[1] == a[1] && c.last() == a.last()
            // Turkish: free order before the verb, verb stays last.
            WordOrder.VerbFinal -> sameWords && c.last() == a.last()
        }
        if (ok) return Grade(Verdict.Typo, answer, "order")
        return Grade(Verdict.Wrong, answer)
    }

    data class SpeechScore(val score: Double, val matched: List<Boolean>)

    /**
     * Pronunciation proxy: how many target words the on-device recognizer heard, in order.
     * Uses the best of the recognizer's alternatives.
     */
    fun speech(heardAlternatives: List<String>, target: String): SpeechScore {
        val t = tokens(target).map(::fold)
        if (t.isEmpty()) return SpeechScore(0.0, emptyList())
        return heardAlternatives.map { heard ->
            val h = tokens(heard).map(::fold)
            val matched = lcsMask(t, h) { a, b -> a == b || (a.length > 3 && levenshtein(a, b) <= 1) }
            SpeechScore(matched.count { it }.toDouble() / t.size, matched)
        }.maxByOrNull { it.score } ?: SpeechScore(0.0, t.map { false })
    }

    private fun allowedTypos(word: String) = when {
        word.length <= 3 -> 0
        word.length <= 7 -> 1
        else -> 2
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** Marks which items of [a] appear, in order, in [b] (longest common subsequence). */
    fun <T> lcsMask(a: List<T>, b: List<T>, eq: (T, T) -> Boolean = { x, y -> x == y }): List<Boolean> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (eq(a[i], b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val mask = MutableList(a.size) { false }
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                eq(a[i], b[j]) -> { mask[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return mask
    }

    /** Word-level diff for showing corrections: pairs of (token, op) where op is '=', '-', '+'. */
    fun diff(original: String, corrected: String): List<Pair<String, Char>> {
        val a = original.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = corrected.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val eq = { x: String, y: String -> x == y }
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed())
            dp[i][j] = if (eq(a[i], b[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val out = mutableListOf<Pair<String, Char>>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                eq(a[i], b[j]) -> { out += a[i] to '='; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out += a[i] to '-'; i++ }
                else -> { out += b[j] to '+'; j++ }
            }
        }
        while (i < a.size) out += a[i++] to '-'
        while (j < b.size) out += b[j++] to '+'
        return out
    }

    /** Share of words that changed between two texts, 0.0..1.0. */
    fun changeRatio(original: String, corrected: String): Double {
        val d = diff(normalize(original), normalize(corrected))
        val changed = d.count { it.second != '=' }
        return if (d.isEmpty()) 0.0 else changed.toDouble() / d.size
    }
}
