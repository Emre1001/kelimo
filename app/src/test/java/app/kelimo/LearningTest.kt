package app.kelimo

import app.kelimo.ai.LlmEngine
import app.kelimo.course.Course
import app.kelimo.data.AppJson
import app.kelimo.data.CourseProgress
import app.kelimo.data.SrsCard
import app.kelimo.data.Stats
import app.kelimo.learn.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

class GraderTest {
    @Test fun exactAndCase() {
        assertEquals(Verdict.Correct, Grader.typed("ich wohne in berlin", listOf("Ich wohne in Berlin.")).verdict)
    }

    @Test fun umlautFolding() {
        assertEquals(Verdict.Typo, Grader.typed("Strasse", listOf("Straße")).verdict)
        assertEquals(Verdict.Typo, Grader.typed("Mueller", listOf("Müller")).verdict)
    }

    @Test fun smallTypoAccepted() {
        assertEquals(Verdict.Typo, Grader.typed("Ich wohne in Berlinn", listOf("Ich wohne in Berlin")).verdict)
    }

    @Test fun articleIsNeverATypo() {
        assertEquals(Verdict.Wrong, Grader.typed("die Tisch", listOf("der Tisch")).verdict)
        assertEquals(Verdict.Wrong, Grader.typed("Ich habe ein Bruder", listOf("Ich habe einen Bruder")).verdict)
    }

    @Test fun wrongWordRejected() {
        assertEquals(Verdict.Wrong, Grader.typed("Ich wohne in Hamburg", listOf("Ich wohne in Berlin")).verdict)
        assertEquals(Verdict.Wrong, Grader.typed("", listOf("x")).verdict)
    }

    @Test fun tilesOrderMatters() {
        assertEquals(Verdict.Correct, Grader.tiles(listOf("Ich", "wohne", "in", "Berlin"), "Ich wohne in Berlin.").verdict)
        assertEquals(Verdict.Wrong, Grader.tiles(listOf("Ich", "in", "Berlin", "wohne"), "Ich wohne in Berlin.").verdict)
    }

    @Test fun germanV2Alternative() {
        val a = "Ich gehe heute ins Kino."
        assertTrue(Grader.tiles("Heute gehe ich ins Kino".split(' '), a, order = WordOrder.GermanV2).passed)
        assertFalse(Grader.tiles("Ich heute gehe ins Kino".split(' '), a, order = WordOrder.GermanV2).passed)
        assertFalse(Grader.tiles("Heute gehe ich ins Kino".split(' '), a, order = WordOrder.Fixed).passed)
    }

    @Test fun listedAlternativeAccepted() {
        assertEquals(Verdict.Correct, Grader.tiles("Am Abend kaufe ich ein".split(' '), "Ich kaufe am Abend ein.", listOf("Am Abend kaufe ich ein.")).verdict)
    }

    @Test fun turkishVerbFinal() {
        assertTrue(Grader.tiles("Berlin'de ben oturuyorum".split(' '), "Ben Berlin'de oturuyorum.", order = WordOrder.VerbFinal).passed)
        assertFalse(Grader.tiles("oturuyorum Ben Berlin'de".split(' '), "Ben Berlin'de oturuyorum.", order = WordOrder.VerbFinal).passed)
    }

    @Test fun speechScore() {
        val s = Grader.speech(listOf("ich wohne in berlin", "ich wohne im berlin"), "Ich wohne in Berlin.")
        assertEquals(1.0, s.score, 1e-9)
        val half = Grader.speech(listOf("ich wohne"), "Ich wohne in Berlin.")
        assertEquals(0.5, half.score, 1e-9)
        assertEquals(listOf(true, true, false, false), half.matched)
    }

    @Test fun diffAndRatio() {
        val d = Grader.diff("Ich habe ein Bruder", "Ich habe einen Bruder")
        assertEquals(listOf("Ich" to '=', "habe" to '=', "ein" to '-', "einen" to '+', "Bruder" to '='), d)
        assertEquals(0.0, Grader.changeRatio("Hallo Welt", "hallo welt!"), 1e-9)
    }
}

class SrsAdaptiveTest {
    @Test fun intervalsGrow() {
        var c = SrsCard("x")
        c = Srs.review(c, 5, 0); assertEquals(1.0, c.intervalDays, 1e-9)
        c = Srs.review(c, 5, 0); assertEquals(3.0, c.intervalDays, 1e-9)
        c = Srs.review(c, 5, 0); assertTrue(c.intervalDays > 6)
    }

    @Test fun lapseResets() {
        var c = SrsCard("x", reps = 4, intervalDays = 20.0)
        c = Srs.review(c, 1, 1000)
        assertEquals(0, c.reps); assertEquals(1, c.lapses); assertEquals(1000 + 600_000L, c.due)
    }

    @Test fun eloMovesTowardScore() {
        assertTrue(Adaptive.update(1000.0, 1000.0, 1.0) > 1000.0)
        assertTrue(Adaptive.update(1000.0, 1000.0, 0.0) < 1000.0)
        assertEquals("A1", Adaptive.cefrFor(900.0))
        assertEquals("B1", Adaptive.cefrFor(1400.0))
    }

    @Test fun stretchFollowsAccuracy() {
        assertEquals(0.0, Adaptive.stretch(CourseProgress()), 1e-9)
        assertTrue(Adaptive.stretch(CourseProgress(recent = List(20) { true })) > 0)
        assertTrue(Adaptive.stretch(CourseProgress(recent = List(20) { it % 2 == 0 })) < 0)
    }

    @Test fun streakWithFreeze() {
        val today = LocalDate.of(2026, 10, 18)
        val s = Stats(xpByDay = mapOf("2026-10-18" to 10, "2026-10-16" to 5), frozenDays = setOf("2026-10-17"))
        assertEquals(3, s.streak(today))
        assertEquals(0, Stats().streak(today))
    }
}

class CourseContentTest {
    private fun load(lang: String): Course {
        val f = listOf(File("src/main/assets/course_$lang.json"), File("app/src/main/assets/course_$lang.json")).first { it.exists() }
        return AppJson.decodeFromString(Course.serializer(), f.readText())
    }

    @Test fun germanCourseIsConsistent() = check(load("de"))
    @Test fun englishCourseIsConsistent() = check(load("en"))

    private fun check(c: Course) {
        assertTrue(c.units.isNotEmpty())
        val ids = c.lessons.map { it.id }
        assertEquals("lesson ids unique", ids.size, ids.toSet().size)
        for (l in c.lessons) {
            assertTrue("${l.id} words", l.words.size >= 5)
            assertTrue("${l.id} sentences", l.sentences.size >= 4)
            for (w in l.words) {
                assertTrue("${w.t} gloss", w.tr.isNotBlank() && (w.en.isNotBlank() || w.de.isNotBlank()))
                if (c.target == "de" && w.g != null) {
                    assertTrue("noun ${w.t} capitalized", w.t.first().isUpperCase())
                    assertTrue("gender ${w.t}", w.g in setOf("m", "f", "n"))
                }
            }
            for (s in l.sentences) {
                assertTrue("${s.t} gloss", s.tr.isNotBlank())
                // Alternatives must use exactly the same words, or the word bank can't build them.
                for (a in s.alt) assertEquals("alt of ${s.t}", Grader.tokens(s.t).sorted(), Grader.tokens(a).sorted())
            }
        }
        for (p in c.placement) assertTrue(p.answer in p.options.indices && p.options.toSet().size == p.options.size)
        for (v in c.verbs) assertEquals(6, v.present.size)
        // The answer key must not sit at a fixed position.
        assertTrue(c.placement.map { it.answer }.toSet().size > 1)
    }

    @Test fun everyLessonBuildsValidExercises() {
        for (lang in listOf("de", "en")) {
            val c = load(lang)
            for (native in listOf("tr", if (lang == "de") "en" else "de")) {
                val f = ExerciseFactory(c, native, Random(1))
                for (l in c.lessons) {
                    val session = f.lessonSession(l, CourseProgress())
                    assertTrue("${l.id} session size", session.size >= 8)
                    session.forEach { assertValid(it) }
                }
                f.articleDrill().forEach(::assertValid)
                f.verbDrill().forEach(::assertValid)
            }
        }
    }

    private fun assertValid(e: Exercise) {
        when (e) {
            is Exercise.PickWord -> { assertEquals(4, e.options.size); assertEquals(4, e.options.toSet().size) ; assertTrue(e.answer in e.options.indices) }
            is Exercise.PickMeaning -> { assertEquals(4, e.options.toSet().size); assertTrue(e.answer in e.options.indices) }
            is Exercise.BuildTarget -> assertTrue("tiles for ${e.answer}", Grader.tiles(Grader.tokens(e.answer).map { tok -> e.tiles.first { Grader.normalize(it) == tok } }, e.answer).passed)
            is Exercise.BuildNative -> assertTrue(Grader.tokens(e.answer).all { tok -> e.tiles.any { Grader.normalize(it) == tok } })
            is Exercise.Cloze -> {
                assertEquals(Grader.normalize(e.full), Grader.normalize("${e.before} ${e.options[e.answer]}${e.after}"))
                assertEquals(e.options.size, e.options.map { it.lowercase() }.toSet().size)
            }
            is Exercise.Article -> assertTrue(e.answer in setOf("der", "die", "das"))
            is Exercise.MatchPairs -> assertTrue(e.pairs.size >= 3 && e.pairs.map { it.second }.toSet().size == e.pairs.size)
            is Exercise.TypeWord -> assertTrue(Grader.typed(e.accepted.first(), e.accepted).verdict == Verdict.Correct)
            else -> {}
        }
    }
}

class PlacementTest {
    @Test fun perfectAnswersReachTop() {
        val c = AppJson.decodeFromString(Course.serializer(), listOf(File("src/main/assets/course_de.json"), File("app/src/main/assets/course_de.json")).first { it.exists() }.readText())
        val p = Placement(c.placement)
        while (!p.done) p.answer(p.current!!.answer)
        assertTrue(Adaptive.levels.indexOf(p.result()) >= 2)
        val q = Placement(c.placement)
        while (!q.done) q.answer(-1)
        assertEquals("A1", q.result())
    }
}

class JsonGuardTest {
    @Test fun extractsFirstBalancedObject() {
        val o = LlmEngine.extractJson("Sure! ```json\n{\"a\": \"b {x}\", \"n\": {\"c\": 1}}\n``` trailing")
        assertNotNull(o); assertEquals("\"b {x}\"", o!!["a"].toString())
        assertNull(LlmEngine.extractJson("no json here"))
        assertNull(LlmEngine.extractJson("{\"broken\": "))
    }
}

class StringsTest {
    @Test fun allLanguagesComplete() {
        val en = app.kelimo.ui.Strings.en.keys
        assertEquals(en, app.kelimo.ui.Strings.de.keys)
        assertEquals(en, app.kelimo.ui.Strings.tr.keys)
        assertTrue(app.kelimo.ui.Strings.tr.values.none { it.isBlank() })
    }
}
