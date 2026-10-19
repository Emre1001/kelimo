package app.kelimo.learn

import app.kelimo.course.PlacementItem

/**
 * Adaptive placement: starts at A2, one level up after two correct answers in a row,
 * one level down after a wrong one. Result = highest level answered mostly correctly.
 */
class Placement(items: List<PlacementItem>, private val maxQuestions: Int = 14) {
    private val byLevel = items.groupBy { it.cefr }.mapValues { it.value.shuffled().toMutableList() }
    private val levels = Adaptive.levels.filter { byLevel[it]?.isNotEmpty() == true }
    private var level = levels.indexOf("A2").takeIf { it >= 0 } ?: 0
    private var streak = 0
    private val results = mutableListOf<Pair<String, Boolean>>()
    var current: PlacementItem? = next()
        private set

    val answered: Int get() = results.size
    val total: Int get() = maxQuestions
    val done: Boolean get() = current == null

    private fun next(): PlacementItem? {
        if (results.size >= maxQuestions || levels.isEmpty()) return null
        // Fall back to a neighbouring level if this one ran out of questions.
        for (off in listOf(0, -1, 1, -2, 2)) {
            val l = levels.getOrNull(level + off) ?: continue
            byLevel[l]?.removeFirstOrNull()?.let { return it }
        }
        return null
    }

    fun answer(choice: Int): Boolean {
        val q = current ?: return false
        val ok = choice == q.answer
        results += q.cefr to ok
        if (ok) {
            streak++
            if (streak >= 2) { level = (level + 1).coerceAtMost(levels.lastIndex); streak = 0 }
        } else {
            streak = 0
            level = (level - 1).coerceAtLeast(0)
        }
        current = next()
        return ok
    }

    /** Highest level where at least 2/3 of the questions asked were right. */
    fun result(): String {
        var best = "A1"
        for (l in Adaptive.levels) {
            val r = results.filter { it.first == l }
            if (r.isEmpty()) continue
            if (r.count { it.second } * 3 >= r.size * 2) best = l else break
        }
        return best
    }
}
