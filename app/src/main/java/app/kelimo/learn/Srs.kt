package app.kelimo.learn

import app.kelimo.data.CourseProgress
import app.kelimo.data.Skill
import app.kelimo.data.SrsCard
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private const val DAY = 86_400_000L

/** SM-2 style scheduler. Quality: 5 perfect, 4 correct, 3 typo, 1 wrong. */
object Srs {
    fun review(card: SrsCard, quality: Int, now: Long = System.currentTimeMillis()): SrsCard {
        if (quality < 3) {
            // Lapse: see it again in ~10 minutes, then rebuild the interval.
            return card.copy(
                ease = max(1.3, card.ease - 0.2), intervalDays = 0.0, reps = 0,
                lapses = card.lapses + 1, due = now + 10 * 60_000L, lastSeen = now,
            )
        }
        val ease = max(1.3, card.ease + (0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02)))
        val interval = when (card.reps) {
            0 -> if (quality >= 4) 1.0 else 0.5
            1 -> if (quality >= 4) 3.0 else 2.0
            else -> card.intervalDays * ease * (if (quality == 3) 0.8 else 1.0)
        }.let { min(it, 365.0) }
        return card.copy(ease = ease, intervalDays = interval, reps = card.reps + 1, due = now + (interval * DAY).toLong(), lastSeen = now)
    }

    fun due(p: CourseProgress, now: Long = System.currentTimeMillis()): List<SrsCard> =
        p.srs.values.filter { it.due <= now }.sortedBy { it.due }

    /** 0..1, how settled a card is. Used for the vocabulary "strength" bars. */
    fun strength(card: SrsCard?, now: Long = System.currentTimeMillis()): Double {
        if (card == null || card.reps == 0) return 0.0
        val elapsed = (now - card.lastSeen).toDouble() / DAY
        val retention = 0.9.pow(elapsed / max(card.intervalDays, 0.5))
        return (min(1.0, card.reps / 5.0) * retention).coerceIn(0.0, 1.0)
    }
}

/**
 * Learner model. Each skill has an Elo rating; each exercise has a difficulty on the
 * same scale. The level shown to the user, the mix of exercise types and the
 * language the AI partner uses all derive from these numbers.
 */
object Adaptive {
    val levels = listOf("A1", "A2", "B1", "B2", "C1")

    fun levelBase(cefr: String): Double = 1000.0 + 200.0 * levels.indexOf(cefr).coerceAtLeast(0)

    fun update(rating: Double, difficulty: Double, score: Double, k: Double = 28.0): Double {
        val expected = 1.0 / (1.0 + 10.0.pow((difficulty - rating) / 400.0))
        return rating + k * (score - expected)
    }

    fun cefrFor(rating: Double): String {
        val idx = ((rating - 1000.0) / 200.0 + 0.5).toInt().coerceIn(0, levels.lastIndex)
        return levels[idx]
    }

    fun overall(p: CourseProgress): Double = p.skills.values.average().takeIf { !it.isNaN() } ?: 900.0

    /** Recent accuracy, 0..1. Null when there is not enough data. */
    fun accuracy(p: CourseProgress): Double? =
        p.recent.takeLast(20).takeIf { it.size >= 6 }?.let { r -> r.count { it }.toDouble() / r.size }

    /** "Stretch" factor: >0 means give harder exercise types, <0 easier ones. */
    fun stretch(p: CourseProgress): Double = when (val a = accuracy(p)) {
        null -> 0.0
        else -> ((a - 0.8) * 4).coerceIn(-1.0, 1.0)
    }

    fun weakest(p: CourseProgress, allowed: Set<Skill>): Skill =
        p.skills.filterKeys { it in allowed }.minByOrNull { it.value }?.key ?: Skill.Vocabulary

    /** Applies one graded answer to the learner model. */
    fun record(p: CourseProgress, skill: Skill, difficulty: Double, score: Double): CourseProgress {
        val skills = p.skills.toMutableMap()
        skills[skill] = update(skills[skill] ?: 900.0, difficulty, score)
        val next = p.copy(skills = skills, recent = (p.recent + (score >= 0.5)).takeLast(40))
        // The displayed level only moves up when overall ability clearly supports it.
        val est = cefrFor(overall(next))
        val cefr = if (levels.indexOf(est) > levels.indexOf(p.cefr) && overall(next) < levelBase(est) + 20) p.cefr else est
        return next.copy(cefr = cefr)
    }
}
