package app.kelimo.learn

import app.kelimo.course.Course
import app.kelimo.course.Lesson
import app.kelimo.course.Sent
import app.kelimo.course.Word
import app.kelimo.data.CourseProgress
import app.kelimo.data.Skill
import kotlin.random.Random

/**
 * Every exercise carries its own answer key taken from curated content,
 * so grading is exact and reproducible.
 */
sealed class Exercise {
    abstract val cardId: String
    abstract val skill: Skill
    abstract val difficulty: Double
    /** Text to speak when the exercise opens (target language), if any. */
    open val audio: String? = null

    /** Native gloss shown, pick the target word. */
    data class PickWord(override val cardId: String, val prompt: String, val options: List<String>, val answer: Int, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Vocabulary
    }

    /** Target word shown + spoken, pick the meaning. */
    data class PickMeaning(override val cardId: String, val word: String, val options: List<String>, val answer: Int, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Reading
        override val audio get() = word
    }

    data class MatchPairs(override val cardId: String, val pairs: List<Pair<String, String>>, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Vocabulary
    }

    /** Native sentence shown, build the target sentence from tiles. */
    data class BuildTarget(override val cardId: String, val prompt: String, val answer: String, val tiles: List<String>, override val difficulty: Double, val alternatives: List<String> = emptyList()) : Exercise() {
        override val skill = Skill.Grammar
    }

    /** Target sentence shown + spoken, build the meaning from native tiles. */
    data class BuildNative(override val cardId: String, val sentence: String, val answer: String, val tiles: List<String>, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Reading
        override val audio get() = sentence
    }

    /** Only audio, type what you hear. */
    data class Dictation(override val cardId: String, val sentence: String, val meaning: String, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Listening
        override val audio get() = sentence
    }

    /** Read the sentence aloud; scored by the on-device recognizer. */
    data class Speak(override val cardId: String, val sentence: String, val meaning: String, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Speaking
        override val audio get() = sentence
    }

    /** der / die / das. */
    data class Article(override val cardId: String, val noun: String, val meaning: String, val answer: String, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Grammar
    }

    /** Sentence with a gap, pick the missing word. */
    data class Cloze(override val cardId: String, val before: String, val after: String, val meaning: String, val options: List<String>, val answer: Int, val full: String, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Grammar
    }

    /** Native gloss shown, type the target word. */
    data class TypeWord(override val cardId: String, val prompt: String, val accepted: List<String>, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Writing
    }

    /** Conjugate a verb for a pronoun. */
    data class Conjugate(override val cardId: String, val pronoun: String, val infinitive: String, val meaning: String, val answer: String, override val difficulty: Double) : Exercise() {
        override val skill = Skill.Grammar
    }
}

fun wordId(w: Word) = "w:${w.key}"
fun sentId(s: Sent) = "s:${Grader.normalize(s.t)}"

class ExerciseFactory(
    private val course: Course,
    private val native: String,
    private val rnd: Random = Random.Default,
    private val listening: Boolean = true,
    private val speaking: Boolean = true,
) {
    private val isGerman = course.target == "de"
    private val pronouns = listOf("ich", "du", "er/sie/es", "wir", "ihr", "sie/Sie")

    private fun base(lessonId: String): Double {
        val u = course.unitOf(lessonId) ?: return 1000.0
        return Adaptive.levelBase(u.cefr) + 15.0 * u.lessons.indexOfFirst { it.id == lessonId }.coerceAtLeast(0)
    }

    /**
     * Builds a lesson session. [stretch] in -1..1 shifts toward easier (choice) or
     * harder (typing, speaking, dictation) types; the weakest skill gets extra weight.
     */
    fun lessonSession(lesson: Lesson, progress: CourseProgress, size: Int = 12): List<Exercise> {
        val stretch = Adaptive.stretch(progress)
        val weak = Adaptive.weakest(progress, enabledSkills())
        val firstTime = (progress.lessons[lesson.id] ?: 0) == 0
        val d = base(lesson.id)
        val out = mutableListOf<Exercise>()
        val words = lesson.words.shuffled(rnd)
        val sents = lesson.sentences.shuffled(rnd)

        // New words are introduced with recognition first.
        if (firstTime) words.take(4).forEach { w -> out += pickMeaning(w, d - 60) ?: return@forEach }
        if (words.size >= 4) out += match(words.take(5), d - 40)

        val pool = mutableListOf<() -> Exercise?>()
        for (w in words) {
            pool += { pickWord(w, d - 20) }
            if (isGerman && w.g != null) pool += { article(w, d) }
            if (stretch > -0.3) pool += { typeWord(w, d + 40) }
        }
        for (s in sents) {
            pool += { buildTarget(s, d + 20) }
            pool += { buildNative(s, d) }
            pool += { cloze(s, lesson, d + 10) }
            if (listening) pool += { dictation(s, d + 80) }
            if (speaking) pool += { speak(s, d + 60) }
        }
        val candidates = pool.mapNotNull { it() }
        // Weight: weak skill x2, hard types boosted by stretch.
        fun weight(e: Exercise): Double {
            var w = 1.0
            if (e.skill == weak) w *= 2.0
            val hard = e is Exercise.TypeWord || e is Exercise.Dictation || e is Exercise.Speak || e is Exercise.BuildTarget
            w *= if (hard) 1.0 + stretch else 1.0 - stretch * 0.6
            return w.coerceAtLeast(0.15)
        }
        val remaining = candidates.toMutableList()
        while (out.size < size && remaining.isNotEmpty()) {
            val total = remaining.sumOf(::weight)
            var r = rnd.nextDouble() * total
            val pick = remaining.first { r -= weight(it); r <= 0 }
            remaining.remove(pick)
            // Avoid the same item twice in a row.
            if (out.lastOrNull()?.cardId == pick.cardId && remaining.isNotEmpty()) { remaining.add(pick); continue }
            out += pick
        }
        return out
    }

    /** Review: due cards and open mistakes, each as the exercise that fits it best. */
    fun reviewSession(cardIds: List<String>, size: Int = 15): List<Exercise> {
        val words = course.allWords.associateBy { wordId(it) }
        val sents = course.allSentences.associateBy { sentId(it) }
        return cardIds.distinct().mapNotNull { id ->
            val lessonId = course.lessons.firstOrNull { l -> l.words.any { wordId(it) == id } || l.sentences.any { sentId(it) == id } }?.id
            val d = lessonId?.let { base(it) } ?: 1000.0
            words[id]?.let { w ->
                listOfNotNull(pickWord(w, d), typeWord(w, d + 40), if (isGerman && w.g != null) article(w, d) else null).random(rnd)
            } ?: sents[id]?.let { s ->
                val l = course.lessons.first { it.id == lessonId }
                listOfNotNull(buildTarget(s, d), cloze(s, l, d), if (listening) dictation(s, d + 60) else null).random(rnd)
            } ?: verbExercise(id)
        }.take(size)
    }

    fun articleDrill(size: Int = 20): List<Exercise> =
        course.allWords.filter { it.g != null }.shuffled(rnd).take(size).mapNotNull { article(it, 1000.0) }

    fun verbDrill(size: Int = 15): List<Exercise> = course.verbs.flatMap { v ->
        v.present.indices.map { i -> "v:${v.inf}:$i" }
    }.shuffled(rnd).take(size).mapNotNull(::verbExercise)

    fun listeningDrill(size: Int = 10): List<Exercise> =
        course.allSentences.shuffled(rnd).take(size).mapNotNull { dictation(it, 1100.0) }

    fun speakingDrill(size: Int = 8): List<Exercise> =
        course.allSentences.shuffled(rnd).take(size).mapNotNull { speak(it, 1100.0) }

    private fun enabledSkills() = Skill.entries.filter {
        (it != Skill.Listening || listening) && (it != Skill.Speaking || speaking)
    }.toSet()

    // ---- builders ---------------------------------------------------------------

    private fun distractWords(w: Word, n: Int): List<Word> =
        course.allWords.filter { it.key != w.key && it.gloss(native) != w.gloss(native) }
            // Similar length makes distractors plausible; the random part is drawn once per word (stable sort key).
            .map { it to kotlin.math.abs(it.t.length - w.t.length) + rnd.nextInt(6) }
            .sortedBy { it.second }.map { it.first }
            .distinctBy { it.key }.take(n)

    private fun pickWord(w: Word, d: Double): Exercise? {
        val ds = distractWords(w, 3).map { it.display }
        if (ds.size < 3) return null
        val opts = (ds + w.display).shuffled(rnd)
        return Exercise.PickWord(wordId(w), w.gloss(native), opts, opts.indexOf(w.display), d)
    }

    private fun pickMeaning(w: Word, d: Double): Exercise? {
        val ds = distractWords(w, 3).map { it.gloss(native) }.distinct()
        if (ds.size < 3) return null
        val opts = (ds + w.gloss(native)).shuffled(rnd)
        return Exercise.PickMeaning(wordId(w), w.display, opts, opts.indexOf(w.gloss(native)), d)
    }

    private fun match(ws: List<Word>, d: Double): Exercise =
        Exercise.MatchPairs("m:" + ws.joinToString(",") { it.key }, ws.distinctBy { it.gloss(native) }.map { it.display to it.gloss(native) }, d)

    private fun article(w: Word, d: Double): Exercise? = w.article?.let {
        Exercise.Article(wordId(w), w.t, w.gloss(native), it, d)
    }

    private fun typeWord(w: Word, d: Double): Exercise =
        Exercise.TypeWord(wordId(w), w.gloss(native), listOf(w.display, w.t) + w.alt, d)

    private fun tilesFor(answer: String, foreign: List<String>, extra: Int): List<String> {
        val ans = answer.split(' ').map { it.trim(',', '.', '!', '?', ';', ':') }.filter { it.isNotBlank() }
        val lower = ans.map { it.lowercase() }.toSet()
        val distract = foreign.flatMap { it.split(' ') }.map { it.trim(',', '.', '!', '?', ';', ':') }
            .filter { it.isNotBlank() && it.lowercase() !in lower }.distinctBy { it.lowercase() }.shuffled(rnd).take(extra)
        return (ans + distract).shuffled(rnd)
    }

    private fun buildTarget(s: Sent, d: Double): Exercise? {
        if (s.t.split(' ').size > 12) return null
        val others = course.allSentences.filter { it.t != s.t }.shuffled(rnd).take(3).map { it.t }
        return Exercise.BuildTarget(sentId(s), s.gloss(native), s.t, tilesFor(s.t, others, 3), d, s.alt)
    }

    private fun buildNative(s: Sent, d: Double): Exercise? {
        val g = s.gloss(native)
        if (g.isBlank() || g.split(' ').size > 12) return null
        val others = course.allSentences.filter { it.t != s.t }.shuffled(rnd).take(3).map { it.gloss(native) }
        return Exercise.BuildNative(sentId(s), s.t, g, tilesFor(g, others, 3), d)
    }

    private fun cloze(s: Sent, lesson: Lesson, d: Double): Exercise? {
        // German only: capitalization tells nouns from other words, so distractors can be
        // drawn from the other word class and never fit the gap by accident.
        if (!isGerman) return null
        val parts = s.t.split(' ')
        val keys = lesson.words.map { it.key }.toSet()
        val idx = parts.indices.filter { it > 0 && Grader.normalize(parts[it]) in keys }.randomOrNull(rnd) ?: return null
        val raw = parts[idx]
        val core = raw.trimEnd(',', '.', '!', '?')
        val punct = raw.removePrefix(core)
        val isNoun = core.first().isUpperCase()
        val pool = course.allWords.map { it.t }.filter { it.first().isUpperCase() != isNoun && !it.contains(' ') && it.lowercase() != core.lowercase() }.distinct()
        if (pool.size < 3) return null
        val opts = (pool.shuffled(rnd).take(3) + core).shuffled(rnd)
        return Exercise.Cloze(
            sentId(s), parts.take(idx).joinToString(" "), punct + parts.drop(idx + 1).joinToString(" ", prefix = if (idx + 1 < parts.size) " " else ""),
            s.gloss(native), opts, opts.indexOf(core), s.t, d,
        )
    }

    private fun dictation(s: Sent, d: Double): Exercise? =
        if (!listening || s.t.split(' ').size > 10) null else Exercise.Dictation(sentId(s), s.t, s.gloss(native), d)

    private fun speak(s: Sent, d: Double): Exercise? =
        if (!speaking || s.t.split(' ').size > 10) null else Exercise.Speak(sentId(s), s.t, s.gloss(native), d)

    private fun verbExercise(id: String): Exercise? {
        if (!id.startsWith("v:")) return null
        val (_, inf, i) = id.split(':').takeIf { it.size == 3 } ?: return null
        val v = course.verbs.firstOrNull { it.inf == inf } ?: return null
        val idx = i.toIntOrNull()?.takeIf { it in v.present.indices } ?: return null
        return Exercise.Conjugate(id, pronouns[idx], v.inf, if (native == "tr") v.tr else v.en, v.present[idx], 1050.0)
    }
}
