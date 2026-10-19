package app.kelimo.data

import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
data class AppState(
    val version: Int = 1,
    val onboarded: Boolean = false,
    val profile: Profile = Profile(),
    val settings: Settings = Settings(),
    /** Per target language, so switching languages never loses progress. */
    val courses: Map<String, CourseProgress> = emptyMap(),
    val models: List<InstalledModel> = emptyList(),
    val activeModelId: String? = null,
    val stats: Stats = Stats(),
    val chats: List<ChatThread> = emptyList(),
) {
    val course: CourseProgress get() = courses[profile.target] ?: CourseProgress()
    val activeModel: InstalledModel? get() = models.firstOrNull { it.id == activeModelId }
}

@Serializable
data class Profile(
    val name: String = "",
    val native: String = "tr",
    val target: String = "de",
    val ui: String = "tr",
    val dailyGoalXp: Int = 30,
    val reason: String = "life",
    val interests: List<String> = emptyList(),
)

@Serializable
enum class ThemeMode { System, Light, Dark }

@Serializable
enum class Backend { GPU, CPU }

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val haptics: Boolean = true,
    val sounds: Boolean = true,
    val ttsRate: Float = 0.95f,
    val autoplayAudio: Boolean = true,
    /** Second, independent model pass that must agree before a correction is shown. */
    val doubleCheck: Boolean = true,
    val showTranslations: Boolean = true,
    val speakingExercises: Boolean = true,
    val listeningExercises: Boolean = true,
    val backend: Backend = Backend.GPU,
    val reminderHour: Int = -1,
    val reduceMotion: Boolean = false,
)

@Serializable
enum class Skill { Vocabulary, Grammar, Listening, Speaking, Reading, Writing }

@Serializable
data class CourseProgress(
    val cefr: String = "A1",
    /** Elo-style ability per skill. 1000 = A1 entry, +200 per CEFR step. */
    val skills: Map<Skill, Double> = Skill.entries.associateWith { 900.0 },
    /** lessonId -> times completed (1..5 = "crowns"). */
    val lessons: Map<String, Int> = emptyMap(),
    val srs: Map<String, SrsCard> = emptyMap(),
    val mistakes: List<Mistake> = emptyList(),
    val placementDone: Boolean = false,
    /** Recent answer results (true = correct), newest last, for adaptive difficulty. */
    val recent: List<Boolean> = emptyList(),
    val aiLessons: List<AiLesson> = emptyList(),
)

/** Spaced-repetition card. Times are epoch millis. */
@Serializable
data class SrsCard(
    val id: String,
    val ease: Double = 2.5,
    val intervalDays: Double = 0.0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val due: Long = 0,
    val lastSeen: Long = 0,
)

@Serializable
data class Mistake(
    val cardId: String,
    val prompt: String,
    val expected: String,
    val given: String,
    val at: Long,
    val fixed: Boolean = false,
)

@Serializable
data class InstalledModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val addedAt: Long,
    val tokensPerSecond: Double? = null,
)

@Serializable
data class Stats(
    /** ISO date -> XP earned that day. */
    val xpByDay: Map<String, Int> = emptyMap(),
    val totalXp: Int = 0,
    val freezes: Int = 1,
    /** Days a freeze was spent on, so the streak stays intact. */
    val frozenDays: Set<String> = emptySet(),
    val lessonsDone: Int = 0,
    val minutesSpoken: Double = 0.0,
    val wordsWritten: Int = 0,
) {
    fun xpOn(day: LocalDate): Int = xpByDay[day.toString()] ?: 0

    fun streak(today: LocalDate = LocalDate.now()): Int {
        fun active(d: LocalDate) = xpOn(d) > 0 || d.toString() in frozenDays
        var d = if (active(today)) today else today.minusDays(1)
        var n = 0
        while (active(d)) { n++; d = d.minusDays(1) }
        return n
    }
}

@Serializable
data class AiLesson(
    val id: String,
    val title: String,
    val words: List<AiPair>,
    val sentences: List<AiPair>,
    val createdAt: Long,
)

@Serializable
data class AiPair(val t: String, val native: String)

@Serializable
data class ChatThread(
    val id: String,
    val scenarioId: String,
    val target: String,
    val messages: List<ChatMsg> = emptyList(),
    val updatedAt: Long = 0,
)

@Serializable
data class ChatMsg(
    val fromUser: Boolean,
    val text: String,
    val translation: String? = null,
    val correction: Correction? = null,
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class Correction(
    val corrected: String,
    val explanation: String,
    /** false when the verifier disagreed or the edit was suspiciously large. */
    val verified: Boolean,
)
