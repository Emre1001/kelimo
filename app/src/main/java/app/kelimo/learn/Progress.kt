package app.kelimo.learn

import app.kelimo.data.Mistake
import app.kelimo.data.SrsCard
import app.kelimo.data.Store
import java.time.LocalDate

/** All writes caused by learning activity go through here. */
object Progress {
    /**
     * Records one answer: learner model, spaced repetition, mistake notebook.
     * [score] is 0..1 (speech gives partial scores), [verdict] decides SRS quality.
     */
    fun answer(store: Store, ex: Exercise, verdict: Verdict, score: Double, prompt: String, given: String, expected: String, firstTry: Boolean) {
        val now = System.currentTimeMillis()
        store.updateCourse { p ->
            var next = if (firstTry) Adaptive.record(p, ex.skill, ex.difficulty, score) else p
            // Pair matching covers several cards at once; it only feeds the learner model.
            if (ex !is Exercise.MatchPairs) {
                val q = when {
                    verdict == Verdict.Wrong -> 1
                    verdict == Verdict.Typo -> 3
                    firstTry -> 5
                    else -> 4
                }
                val card = next.srs[ex.cardId] ?: SrsCard(ex.cardId)
                next = next.copy(srs = next.srs + (ex.cardId to Srs.review(card, q, now)))
            }
            if (verdict == Verdict.Wrong && firstTry && ex !is Exercise.MatchPairs) {
                val m = Mistake(ex.cardId, prompt, expected, given, now)
                next = next.copy(mistakes = (next.mistakes.filterNot { it.cardId == ex.cardId && !it.fixed } + m).takeLast(200))
            } else if (verdict != Verdict.Wrong) {
                next = next.copy(mistakes = next.mistakes.map { if (it.cardId == ex.cardId && !it.fixed) it.copy(fixed = true) else it })
            }
            next
        }
    }

    fun addXp(store: Store, xp: Int, today: LocalDate = LocalDate.now()) = store.update { s ->
        val key = today.toString()
        val newFreeze = if (s.stats.streak(today) > 0 && (s.stats.streak(today) + 1) % 7 == 0 && s.stats.xpOn(today) == 0) 1 else 0
        s.copy(stats = s.stats.copy(
            xpByDay = s.stats.xpByDay + (key to (s.stats.xpByDay[key] ?: 0) + xp),
            totalXp = s.stats.totalXp + xp,
            freezes = (s.stats.freezes + newFreeze).coerceAtMost(3),
        ))
    }

    fun finishLesson(store: Store, lessonId: String?, xp: Int) {
        addXp(store, xp)
        store.update { s -> s.copy(stats = s.stats.copy(lessonsDone = s.stats.lessonsDone + 1)) }
        if (lessonId != null) store.updateCourse { p -> p.copy(lessons = p.lessons + (lessonId to ((p.lessons[lessonId] ?: 0) + 1).coerceAtMost(5))) }
    }

    /**
     * Spends a freeze for yesterday if the streak would break otherwise.
     * Called on app start.
     */
    fun applyFreeze(store: Store, today: LocalDate = LocalDate.now()) = store.update { s ->
        val y = today.minusDays(1)
        val yActive = s.stats.xpOn(y) > 0 || y.toString() in s.stats.frozenDays
        val before = s.stats.copy(frozenDays = s.stats.frozenDays + y.toString()).streak(y)
        if (!yActive && s.stats.freezes > 0 && before > 1 && s.stats.xpOn(today) == 0)
            s.copy(stats = s.stats.copy(freezes = s.stats.freezes - 1, frozenDays = s.stats.frozenDays + y.toString()))
        else s
    }

    /** Marks lessons below the placement result as done once, so the path starts at the right spot. */
    fun applyPlacement(store: Store, cefr: String, lessonIdsBelow: List<String>) = store.updateCourse { p ->
        val base = Adaptive.levelBase(cefr)
        p.copy(
            cefr = cefr,
            placementDone = true,
            skills = p.skills.mapValues { maxOf(it.value, base) },
            lessons = p.lessons + lessonIdsBelow.filter { it !in p.lessons }.associateWith { 1 },
        )
    }
}
