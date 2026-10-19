package app.kelimo.course

import android.content.Context
import app.kelimo.core.loc
import app.kelimo.data.AiLesson
import app.kelimo.data.AppJson
import kotlinx.serialization.Serializable

/**
 * Hand-written course content. This is the ground truth of the app:
 * every graded exercise is built from it, never from model output.
 */
@Serializable
data class Course(
    val target: String,
    val version: Int = 1,
    val units: List<CUnit>,
    val placement: List<PlacementItem> = emptyList(),
    val verbs: List<Verb> = emptyList(),
) {
    val lessons: List<Lesson> get() = units.flatMap { it.lessons }
    fun unitOf(lessonId: String): CUnit? = units.firstOrNull { u -> u.lessons.any { it.id == lessonId } }
    fun lesson(id: String): Lesson? = lessons.firstOrNull { it.id == id }
    val allWords: List<Word> get() = lessons.flatMap { it.words }
    val allSentences: List<Sent> get() = lessons.flatMap { it.sentences }
}

@Serializable
data class CUnit(
    val id: String,
    val cefr: String,
    val title: Map<String, String>,
    val icon: String = "chat",
    val grammar: List<GrammarNote> = emptyList(),
    val lessons: List<Lesson>,
)

@Serializable
data class GrammarNote(
    val id: String,
    val title: Map<String, String>,
    val body: Map<String, String>,
    val examples: List<Sent> = emptyList(),
    /** Extra search terms for grounded Q&A retrieval. */
    val keys: List<String> = emptyList(),
)

@Serializable
data class Lesson(
    val id: String,
    val title: Map<String, String>,
    val words: List<Word>,
    val sentences: List<Sent>,
    /** Optional native-language tip shown before the lesson (e.g. Turkish-specific pitfalls). */
    val tip: Map<String, String> = emptyMap(),
)

@Serializable
data class Word(
    /** Target-language form, nouns without article. */
    val t: String,
    val tr: String = "",
    val en: String = "",
    val de: String = "",
    /** Grammatical gender for nouns: m, f, n. Null for non-nouns. */
    val g: String? = null,
    val pl: String? = null,
    /** Other accepted target spellings/forms. */
    val alt: List<String> = emptyList(),
) {
    fun gloss(lang: String): String = when (lang) {
        "tr" -> tr.ifBlank { en }
        "de" -> de.ifBlank { en }
        else -> en.ifBlank { tr }
    }
    val article: String? get() = when (g) { "m" -> "der"; "f" -> "die"; "n" -> "das"; else -> null }
    /** "der Tisch" for German nouns, otherwise the bare form. */
    val display: String get() = article?.let { "$it $t" } ?: t
    val key: String get() = t.lowercase()
}

@Serializable
data class Sent(
    val t: String,
    val tr: String = "",
    val en: String = "",
    val de: String = "",
    /** Other correct target sentences (e.g. different word order). */
    val alt: List<String> = emptyList(),
) {
    fun gloss(lang: String): String = when (lang) {
        "tr" -> tr.ifBlank { en }
        "de" -> de.ifBlank { en }
        else -> en.ifBlank { tr }
    }
}

@Serializable
data class PlacementItem(
    val cefr: String,
    val q: String,
    val options: List<String>,
    val answer: Int,
    /** Native-language hint for the question, optional. */
    val hint: Map<String, String> = emptyMap(),
)

/** Present-tense table + perfect, for the conjugation trainer. */
@Serializable
data class Verb(
    val inf: String,
    val tr: String,
    val en: String,
    /** ich, du, er/sie/es, wir, ihr, sie/Sie */
    val present: List<String>,
    val aux: String,
    val participle: String,
)

class CourseRepository(private val context: Context) {
    private val cache = mutableMapOf<String, Course?>()

    fun course(target: String): Course? = cache.getOrPut(target) {
        runCatching {
            context.assets.open("course_$target.json").use { AppJson.decodeFromString(Course.serializer(), it.readBytes().decodeToString()) }
        }.getOrNull()
    }

    /** Wraps AI-generated lessons for non-curated languages into the same shape. */
    fun aiCourse(target: String, native: String, ai: List<AiLesson>): Course = Course(
        target = target,
        units = ai.map { l ->
            CUnit(
                id = "ai_${l.id}", cefr = "A1", title = mapOf("en" to l.title, native to l.title), icon = "spark",
                lessons = listOf(
                    Lesson(
                        id = l.id, title = mapOf("en" to l.title),
                        words = l.words.map { Word(t = it.t, tr = if (native == "tr") it.native else "", en = if (native != "tr") it.native else "", de = if (native == "de") it.native else "") },
                        sentences = l.sentences.map { Sent(t = it.t, tr = if (native == "tr") it.native else "", en = if (native != "tr") it.native else "", de = if (native == "de") it.native else "") },
                    )
                ),
            )
        },
    )
}

fun CUnit.titleFor(lang: String) = title.loc(lang)
fun Lesson.titleFor(lang: String) = title.loc(lang)
