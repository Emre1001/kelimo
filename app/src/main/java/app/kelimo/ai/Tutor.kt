package app.kelimo.ai

import app.kelimo.core.Lang
import app.kelimo.core.LangDetect
import app.kelimo.course.Course
import app.kelimo.course.GrammarNote
import app.kelimo.course.Word
import app.kelimo.core.loc
import app.kelimo.data.AiLesson
import app.kelimo.data.AiPair
import app.kelimo.data.ChatMsg
import app.kelimo.data.Correction
import app.kelimo.data.Tip
import app.kelimo.learn.Grader
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

data class LearnerCtx(
    val native: Lang,
    val target: Lang,
    val cefr: String,
    val name: String,
    val interests: List<String>,
    val knownWords: List<String> = emptyList(),
)

/**
 * All model-facing logic. The rule everywhere: the model proposes, deterministic code disposes.
 * - Output is schema-constrained JSON, validated field by field.
 * - Corrections must survive a second, independent verification pass.
 * - Claims about the learner's text must point at text that actually exists.
 * - Grammar answers are grounded on curated notes and cite them.
 * Anything that fails a check is dropped or shown as "unverified", never silently trusted.
 */
class Tutor(private val llm: LlmEngine) {

    // ---------------------------------------------------------------- prompts

    private fun levelRules(cefr: String) = when (cefr) {
        "A1" -> "Use only very short, simple sentences (max 8 words), present tense, very common words. One question per turn."
        "A2" -> "Use short sentences (max 12 words), present and perfect tense, common everyday words. One question per turn."
        "B1" -> "Use clear natural sentences; subordinate clauses (because, that, if) are fine. Everyday topics."
        "B2" -> "Speak naturally with varied structures. Occasionally use a common idiom."
        else -> "Speak like an educated native speaker."
    }

    private fun base(c: LearnerCtx) = """
        You are Kelimo, a patient and precise ${c.target.endonym} (${c.target.name}) tutor.
        The learner's native language is ${c.native.endonym}. Learner level: CEFR ${c.cefr}.
        ${levelRules(c.cefr)}
        Hard rules:
        - Never claim that correct text is wrong. If you are not sure something is an error, it is NOT an error.
        - Ignore missing capital letters and punctuation in chat messages; they are not errors.
        - Never invent facts about the learner. Never use emojis.
        - Explanations: max 2 short sentences, written in ${c.native.endonym}.${if (c.native == Lang.TR) " Compare with Turkish grammar when it helps." else ""}
    """.trimIndent()

    // ---------------------------------------------------------------- chat

    private val chatSchema = """
        {"type":"object","properties":{
          "correction_needed":{"type":"boolean"},
          "corrected":{"type":"string"},
          "explanation":{"type":"string"},
          "more_natural":{"type":"string"},
          "natural_why":{"type":"string"},
          "reply":{"type":"string"},
          "reply_translation":{"type":"string"},
          "suggestions":{"type":"array","items":{"type":"string"}}
        },"required":["correction_needed","corrected","explanation","more_natural","natural_why","reply","reply_translation","suggestions"]}
    """.trimIndent()

    data class ChatResult(val userCorrection: Correction?, val tip: Tip?, val reply: ChatMsg)

    private fun chatSystem(c: LearnerCtx, s: Scenario) = base(c) + """

        Role-play: you are ${s.role}. Setting: ${s.setting}
        The learner's goal: ${s.goal["en"]}
        ${if (c.interests.isNotEmpty()) "Learner interests: ${c.interests.joinToString()}." else ""}
        For every learner message, fill the JSON fields:
        - correction_needed: true only for real grammar, word-choice or spelling mistakes in the learner's last message.
        - corrected: the learner's message with minimal changes (keep their words). Copy it unchanged if no mistake.
        - explanation: why, in ${c.native.endonym}. Empty if no mistake.
        - more_natural: how a native speaker would naturally say the learner's message, in ${c.target.endonym}, at the learner's level. Empty if the message is already natural.
        - natural_why: one short sentence in ${c.native.endonym} on what makes it more natural. Empty if more_natural is empty.
        - reply: your next line in the role-play, in ${c.target.endonym} only, at the learner's level.
        - reply_translation: the reply translated into ${c.native.endonym}.
        - suggestions: two short possible answers the learner could give next, in ${c.target.endonym}.
    """.trimIndent()

    suspend fun opener(c: LearnerCtx, s: Scenario, onText: (String) -> Unit = {}): ChatMsg? {
        val schema = """{"type":"object","properties":{"reply":{"type":"string"},"reply_translation":{"type":"string"},"suggestions":{"type":"array","items":{"type":"string"}}},"required":["reply","reply_translation","suggestions"]}"""
        val req = LlmRequest(
            system = chatSystem(c, s),
            user = "Start the role-play with your first line (greeting + one question). Fill reply, reply_translation, suggestions.",
            temperature = 0.7, schema = schema,
        )
        val o = llm.json(req, streamField("reply", onText)) ?: return null
        val reply = o.str("reply")?.takeIf { it.isNotBlank() && !wrongLanguage(c, it) } ?: return null
        return ChatMsg(false, clean(reply), o.str("reply_translation")?.let(::clean), suggestions = o.strList("suggestions").map(::clean).take(2))
    }

    suspend fun chat(c: LearnerCtx, s: Scenario, history: List<ChatMsg>, userText: String, doubleCheck: Boolean, onText: (String) -> Unit = {}): ChatResult? {
        val turns = history.map { Turn(it.fromUser, it.text) }
        var result: JsonObject? = null
        // Up to two attempts: the second one only if the reply came back in the wrong language.
        for (attempt in 0..1) {
            val o = llm.json(
                LlmRequest(chatSystem(c, s), "Learner: $userText", turns, temperature = if (attempt == 0) 0.6 else 0.3, schema = chatSchema),
                streamField("reply", onText),
            ) ?: continue
            val reply = o.str("reply").orEmpty()
            val wrongLang = wrongLanguage(c, reply)
            result = o
            if (reply.isNotBlank() && !wrongLang) break
        }
        val o = result ?: return null
        val reply = clean(o.str("reply").orEmpty()).ifBlank { return null }
        val correction = checkedCorrection(
            c, userText,
            needed = o.bool("correction_needed") == true,
            corrected = o.str("corrected").orEmpty(),
            explanation = o.str("explanation").orEmpty(),
            doubleCheck = doubleCheck,
        )
        val tip = checkedTip(c, userText, correction?.corrected, o.str("more_natural").orEmpty(), o.str("natural_why").orEmpty())
        return ChatResult(
            correction, tip,
            ChatMsg(false, reply, o.str("reply_translation")?.let(::clean)?.takeIf { it.isNotBlank() }, suggestions = o.strList("suggestions").map(::clean).filter { it.isNotBlank() }.take(2)),
        )
    }

    /** Reply not in the target language, e.g. a small model echoing the (English) instructions. */
    private fun wrongLanguage(c: LearnerCtx, reply: String): Boolean =
        (c.target != c.native && !LangDetect.looksLike(reply, c.target, c.native)) ||
            (c.target != Lang.EN && !LangDetect.looksLike(reply, c.target, Lang.EN))

    /** Guard + verifier for a proposed correction. Returns null when there is nothing trustworthy to show. */
    suspend fun checkedCorrection(c: LearnerCtx, original: String, needed: Boolean, corrected: String, explanation: String, doubleCheck: Boolean): Correction? {
        if (!needed || corrected.isBlank()) return null
        // Same text modulo case/punctuation: not a real correction.
        if (Grader.normalize(corrected) == Grader.normalize(original)) return null
        val ratio = Grader.changeRatio(original, corrected)
        // A "correction" that rewrites most of the message is a rewrite, not a fix.
        if (ratio > 0.75 && original.split(' ').size > 3) return null
        val expl = explanation.takeIf { it.isNotBlank() && (c.native == c.target || LangDetect.looksLike(it, c.native, c.target)) }.orEmpty()
        var verified = ratio <= 0.5
        if (doubleCheck) verified = verified && verify(c, original, corrected) ?: false
        if (doubleCheck && !verified) return null
        return Correction(clean(corrected), clean(expl), verified)
    }

    /**
     * A "say it more naturally" suggestion. Not a correction, so no verifier pass, but it must be in
     * the target language, actually different, and close enough to what the learner meant.
     */
    fun checkedTip(c: LearnerCtx, original: String, corrected: String?, better: String, why: String): Tip? {
        val b = clean(better)
        if (b.isBlank() || b.length > original.length * 2 + 40) return null
        val nb = Grader.normalize(b)
        if (nb == Grader.normalize(original) || (corrected != null && nb == Grader.normalize(corrected))) return null
        if (c.target != c.native && !LangDetect.looksLike(b, c.target, c.native)) return null
        if (Grader.changeRatio(original, b) > 0.85 && original.split(' ').size > 2) return null
        val w = clean(why).takeIf { it.isNotBlank() && (c.native == c.target || LangDetect.looksLike(it, c.native, c.target)) }.orEmpty()
        return Tip(b, w)
    }

    data class Feedback(val summary: String, val focus: List<String>)

    /**
     * End-of-conversation feedback. Grounded only on the corrections that were actually shown;
     * with none, the model is not asked to find any.
     */
    suspend fun conversationFeedback(c: LearnerCtx, items: List<Pair<String, String>>): Feedback? {
        if (items.isEmpty()) return null
        val list = items.take(12).joinToString("\n") { (wrong, right) -> "- \"$wrong\" -> \"$right\"" }
        val schema = """{"type":"object","properties":{"summary":{"type":"string"},"focus":{"type":"array","items":{"type":"string"}}},"required":["summary","focus"]}"""
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nYou give short, warm, concrete feedback after a conversation. Only talk about the corrections listed. Never invent other mistakes.",
                user = "Corrections from this conversation:\n$list\n\nIn ${c.native.endonym}: summary = 2 sentences on what went well and the main pattern in the mistakes. focus = up to 3 short, concrete things to practise next.",
                temperature = 0.3, schema = schema, maxChars = 1200,
            )
        ) ?: return null
        val summary = clean(o.str("summary").orEmpty())
        if (summary.isBlank()) return null
        return Feedback(summary, o.strList("focus").map(::clean).filter { it.isNotBlank() }.take(3))
    }

    /** Independent second opinion at temperature 0. Null when the model gave no usable answer. */
    private suspend fun verify(c: LearnerCtx, original: String, corrected: String): Boolean? {
        val schema = """{"type":"object","properties":{"original_has_error":{"type":"boolean"},"fixed_is_correct":{"type":"boolean"},"same_meaning":{"type":"boolean"}},"required":["original_has_error","fixed_is_correct","same_meaning"]}"""
        val o = llm.json(
            LlmRequest(
                system = "You are a strict ${c.target.endonym} grammar checker. Judge only grammar, word choice and spelling. Ignore capitalization at the start and punctuation. When unsure, answer original_has_error=false.",
                user = "Sentence A: \"$original\"\nSentence B: \"$corrected\"\nDoes A contain a real mistake? Is B fully correct? Do A and B mean the same?",
                temperature = 0.0, topK = 1, schema = schema, maxChars = 300,
            )
        ) ?: return null
        return o.bool("original_has_error") == true && o.bool("fixed_is_correct") == true && o.bool("same_meaning") != false
    }

    // ---------------------------------------------------------------- writing coach

    data class Issue(val wrong: String, val right: String, val why: String)
    data class WritingResult(val corrected: String, val issues: List<Issue>, val level: String?, val feedback: String, val heavyRewrite: Boolean)

    suspend fun checkWriting(c: LearnerCtx, text: String, task: String?, doubleCheck: Boolean, onText: (String) -> Unit = {}): WritingResult? {
        val schema = """
            {"type":"object","properties":{
              "corrected_text":{"type":"string"},
              "issues":{"type":"array","items":{"type":"object","properties":{"wrong":{"type":"string"},"right":{"type":"string"},"why":{"type":"string"}},"required":["wrong","right","why"]}},
              "level":{"type":"string","enum":["A1","A2","B1","B2","C1"]},
              "feedback":{"type":"string"}
            },"required":["corrected_text","issues","level","feedback"]}
        """.trimIndent()
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nYou correct written texts. Make minimal corrections, keep the learner's style and words. List each real mistake once: 'wrong' must be copied exactly from the learner's text.",
                user = (task?.let { "Task: $it\n" } ?: "") + "Learner text:\n\"\"\"\n$text\n\"\"\"\nReturn corrected_text, issues, estimated CEFR level, and 2 sentences of encouraging feedback in ${c.native.endonym}.",
                temperature = 0.2, schema = schema, maxChars = 4000,
            ),
            streamField("corrected_text", onText),
        ) ?: return null
        val corrected = clean(o.str("corrected_text").orEmpty()).ifBlank { text }
        val normText = Grader.normalize(text)
        val normCorr = Grader.normalize(corrected)
        // Drop issues that point at text the learner never wrote, or fixes that are not in the corrected text.
        var issues = o.arr("issues").mapNotNull { it as? JsonObject }.mapNotNull { i ->
            val w = i.str("wrong").orEmpty(); val r = i.str("right").orEmpty()
            val nw = Grader.normalize(w); val nr = Grader.normalize(r)
            if (nw.isEmpty() || nw == nr || !normText.contains(nw) || (nr.isNotEmpty() && !normCorr.contains(nr))) null
            else Issue(w.trim(), r.trim(), clean(i.str("why").orEmpty()))
        }.distinctBy { Grader.normalize(it.wrong) }
        if (doubleCheck && issues.isNotEmpty()) {
            issues = issues.take(8).filter { verify(c, sentenceAround(text, it.wrong), sentenceAround(text, it.wrong).replace(it.wrong, it.right)) != false }
        }
        val level = o.str("level")?.takeIf { it in listOf("A1", "A2", "B1", "B2", "C1") }
        return WritingResult(corrected, issues, level, clean(o.str("feedback").orEmpty()), Grader.changeRatio(text, corrected) > 0.5)
    }

    private fun sentenceAround(text: String, part: String): String {
        val i = text.indexOf(part).takeIf { it >= 0 } ?: return part
        val start = text.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), i).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOfAny(charArrayOf('.', '!', '?', '\n'), i + part.length).let { if (it < 0) text.length else it + 1 }
        return text.substring(start, end).trim()
    }

    // ---------------------------------------------------------------- explanations

    /** Explains why the curated answer is right. The answer key is authoritative; the model only explains it. */
    suspend fun explainMistake(c: LearnerCtx, prompt: String, correct: String, given: String, notes: List<GrammarNote>, onText: (String) -> Unit = {}): String? {
        val ref = notes.take(2).joinToString("\n") { "- " + it.title.loc("en") + ": " + it.body.loc("en") }
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nThe correct answer below comes from a verified answer key and is definitely correct. Never contradict it. Explain in ${c.native.endonym}, max 3 sentences.",
                user = "Exercise: $prompt\nCorrect answer: $correct\nLearner answered: $given\n${if (ref.isNotBlank()) "Reference notes:\n$ref" else ""}\nWhy is the correct answer right and what was wrong in the learner's answer?",
                temperature = 0.2, schema = """{"type":"object","properties":{"explanation":{"type":"string"}},"required":["explanation"]}""", maxChars = 800,
            ),
            streamField("explanation", onText),
        ) ?: return null
        return o.str("explanation")?.let(::clean)?.takeIf { it.isNotBlank() }
    }

    data class GrammarAnswer(val answer: String, val examples: List<Pair<String, String>>, val confident: Boolean, val sources: List<GrammarNote>)

    /** Grounded Q&A: retrieves the most relevant curated notes and makes the model answer from them. */
    suspend fun askGrammar(c: LearnerCtx, question: String, course: Course?, onText: (String) -> Unit = {}): GrammarAnswer? {
        val notes = retrieve(question, course).take(3)
        val ref = notes.mapIndexed { i, n -> "[${i + 1}] ${n.title.loc("en")}: ${n.body.loc("en")}" }.joinToString("\n")
        val schema = """{"type":"object","properties":{"answer":{"type":"string"},"examples":{"type":"array","items":{"type":"object","properties":{"target":{"type":"string"},"native":{"type":"string"}},"required":["target","native"]}},"confident":{"type":"boolean"}},"required":["answer","examples","confident"]}"""
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nAnswer grammar and vocabulary questions about ${c.target.endonym}. Base your answer on the reference notes when they are relevant. Set confident=false if the notes do not cover the question and you are not completely sure. Answer in ${c.native.endonym}, max 6 sentences, plus up to 3 short example sentences.",
                user = (if (ref.isNotBlank()) "Reference notes:\n$ref\n\n" else "") + "Question: $question",
                temperature = 0.2, schema = schema, maxChars = 2500,
            ),
            streamField("answer", onText),
        ) ?: return null
        val ex = o.arr("examples").mapNotNull { it as? JsonObject }.mapNotNull { e ->
            val t = e.str("target"); val n = e.str("native")
            if (t.isNullOrBlank()) null else clean(t) to clean(n.orEmpty())
        }.take(3)
        return GrammarAnswer(clean(o.str("answer").orEmpty()), ex, o.bool("confident") != false && notes.isNotEmpty(), notes)
    }

    fun retrieve(query: String, course: Course?): List<GrammarNote> {
        val notes = course?.units?.flatMap { it.grammar } ?: return emptyList()
        val q = Grader.fold(query).split(' ').filter { it.length > 2 }.toSet()
        if (q.isEmpty()) return emptyList()
        return notes.map { n ->
            val hay = Grader.fold((n.title.values + n.body.values + n.keys + n.examples.map { it.t }).joinToString(" ")).split(' ').toSet()
            n to q.count { w -> hay.any { it.startsWith(w) || w.startsWith(it) && it.length > 3 } }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    // ---------------------------------------------------------------- dictionary

    data class Lookup(val word: String, val translation: String, val gender: String?, val plural: String?, val example: String?, val exampleTranslation: String?, val fromCourse: Boolean)

    fun lookupCurated(query: String, course: Course?, native: String): List<Lookup> {
        val q = Grader.fold(query)
        if (q.isBlank() || course == null) return emptyList()
        return course.allWords.distinctBy { it.key }.filter { w ->
            Grader.fold(w.t).startsWith(q) || Grader.fold(w.gloss(native)).split(' ', ',', '/').any { it.startsWith(q) }
        }.sortedBy { if (Grader.fold(it.t) == q || Grader.fold(it.gloss(native)) == q) 0 else 1 }.take(12).map { w: Word ->
            val ex = course.allSentences.firstOrNull { s -> Grader.tokens(s.t).contains(w.key) }
            Lookup(w.t, w.gloss(native), w.article, w.pl, ex?.t, ex?.gloss(native), true)
        }
    }

    suspend fun lookupAi(c: LearnerCtx, query: String): Lookup? {
        val schema = """{"type":"object","properties":{"found":{"type":"boolean"},"word":{"type":"string"},"translation":{"type":"string"},"article":{"type":"string","enum":["der","die","das","none"]},"plural":{"type":"string"},"example":{"type":"string"},"example_translation":{"type":"string"}},"required":["found","word","translation","article","plural","example","example_translation"]}"""
        val o = llm.json(
            LlmRequest(
                system = "You are a careful bilingual dictionary ${c.target.endonym} - ${c.native.endonym}. If the word does not exist or you are not sure, set found=false. article only for German nouns, else none.",
                user = "Look up: \"$query\" (it may be given in either language). Give the ${c.target.endonym} word, its ${c.native.endonym} translation, plural if a noun, and one simple example sentence in ${c.target.endonym} with translation.",
                temperature = 0.0, topK = 1, schema = schema, maxChars = 700,
            )
        ) ?: return null
        if (o.bool("found") == false) return null
        val w = o.str("word")?.takeIf { it.isNotBlank() } ?: return null
        return Lookup(clean(w), clean(o.str("translation").orEmpty()), o.str("article")?.takeIf { it != "none" && c.target == Lang.DE }, o.str("plural")?.takeIf { it.isNotBlank() }, o.str("example")?.let(::clean), o.str("example_translation")?.let(::clean), false)
    }

    // ---------------------------------------------------------------- generated content

    /** Lesson for languages without a curated course. Strictly validated, labelled as AI content in the UI. */
    suspend fun generateLesson(c: LearnerCtx, topic: String, onText: (String) -> Unit = {}): AiLesson? {
        val schema = """{"type":"object","properties":{"title":{"type":"string"},"words":{"type":"array","items":{"type":"object","properties":{"t":{"type":"string"},"native":{"type":"string"}},"required":["t","native"]}},"sentences":{"type":"array","items":{"type":"object","properties":{"t":{"type":"string"},"native":{"type":"string"}},"required":["t","native"]}}},"required":["title","words","sentences"]}"""
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nYou write beginner course material. Only use correct, standard, very common ${c.target.endonym}. Each sentence must be simple and natural. Translations must be exact.",
                user = "Topic: $topic. Create a lesson with exactly 8 useful words (t = ${c.target.endonym} word, native = ${c.native.endonym} translation) and exactly 5 short sentences (3-8 words) that use these words. title in ${c.native.endonym}.",
                temperature = 0.3, schema = schema, maxChars = 3000,
            ),
            onText,
        ) ?: return null
        fun pairs(key: String, maxWords: Int) = o.arr(key).mapNotNull { it as? JsonObject }.mapNotNull { p ->
            val t = clean(p.str("t").orEmpty()); val n = clean(p.str("native").orEmpty())
            val ok = t.isNotBlank() && n.isNotBlank() && t.length <= 80 && n.length <= 80 && t.split(' ').size <= maxWords && Grader.normalize(t) != Grader.normalize(n)
            if (ok) AiPair(t, n) else null
        }.distinctBy { Grader.normalize(it.t) }
        val words = pairs("words", 3)
        val sents = pairs("sentences", 10).filter { c.target.stopwords.isEmpty() || LangDetect.looksLike(it.t, c.target, c.native) }
        if (words.size < 5 || sents.size < 3) return null
        return AiLesson(UUID.randomUUID().toString().take(8), clean(o.str("title").orEmpty()).ifBlank { topic }, words.take(10), sents.take(6), System.currentTimeMillis())
    }

    data class Story(val title: String, val lines: List<Pair<String, String>>, val questions: List<Question>)
    data class Question(val q: String, val options: List<String>, val answer: Int)

    suspend fun story(c: LearnerCtx, topic: String, onText: (String) -> Unit = {}): Story? {
        val schema = """{"type":"object","properties":{"title":{"type":"string"},"lines":{"type":"array","items":{"type":"object","properties":{"t":{"type":"string"},"native":{"type":"string"}},"required":["t","native"]}},"questions":{"type":"array","items":{"type":"object","properties":{"q":{"type":"string"},"options":{"type":"array","items":{"type":"string"}},"answer":{"type":"integer"},"evidence":{"type":"string"}},"required":["q","options","answer","evidence"]}}},"required":["title","lines","questions"]}"""
        val known = c.knownWords.shuffled().take(40).joinToString()
        val o = llm.json(
            LlmRequest(
                system = base(c) + "\nYou write short graded reading stories.",
                user = "Write a story of 8-10 short sentences in ${c.target.endonym} about: $topic. ${if (known.isNotBlank()) "Prefer these known words: $known." else ""} Each line: t (${c.target.endonym}) and native (${c.native.endonym} translation). Then 3 comprehension questions in ${c.native.endonym}, each with 3 options, the index of the correct option, and 'evidence' = the exact story sentence (in ${c.target.endonym}) that proves the answer.",
                temperature = 0.6, schema = schema, maxChars = 5000,
            ),
            onText,
        ) ?: return null
        val lines = o.arr("lines").mapNotNull { it as? JsonObject }.mapNotNull { l ->
            clean(l.str("t").orEmpty()).takeIf { it.isNotBlank() }?.let { it to clean(l.str("native").orEmpty()) }
        }
        if (lines.size < 4) return null
        val storyNorm = lines.joinToString(" ") { Grader.normalize(it.first) }
        // A question survives only if its evidence sentence is really in the story.
        val qs = o.arr("questions").mapNotNull { it as? JsonObject }.mapNotNull { q ->
            val opts = q.strList("options").map(::clean).filter { it.isNotBlank() }
            val a = q.int("answer") ?: -1
            val ev = Grader.normalize(q.str("evidence").orEmpty())
            if (opts.size !in 2..4 || a !in opts.indices || ev.length < 6 || !storyNorm.contains(ev)) null
            else Question(clean(q.str("q").orEmpty()), opts, a)
        }
        return Story(clean(o.str("title").orEmpty()), lines, qs)
    }

    // ---------------------------------------------------------------- helpers

    /** Streams only the human-readable field while the JSON is being generated. */
    private fun streamField(field: String, onText: (String) -> Unit): (String) -> Unit = { raw ->
        val key = "\"$field\""
        val k = raw.indexOf(key)
        if (k >= 0) {
            val q = raw.indexOf('"', raw.indexOf(':', k + key.length) + 1)
            if (q > 0) {
                val sb = StringBuilder(); var i = q + 1; var esc = false
                while (i < raw.length) {
                    val ch = raw[i]
                    if (esc) { sb.append(if (ch == 'n') '\n' else ch); esc = false }
                    else if (ch == '\\') esc = true
                    else if (ch == '"') break
                    else sb.append(ch)
                    i++
                }
                onText(sb.toString())
            }
        }
    }

    private fun clean(s: String) = s.trim().replace(Regex("\\s+\n"), "\n").replace(Regex("[*_#`]{2,}"), "").removeSurrounding("\"")
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull ?: str(k)?.toBooleanStrictOrNull()
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
private fun JsonObject.arr(k: String): JsonArray = (this[k] as? JsonArray) ?: JsonArray(emptyList())
private fun JsonObject.strList(k: String): List<String> = arr(k).mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

