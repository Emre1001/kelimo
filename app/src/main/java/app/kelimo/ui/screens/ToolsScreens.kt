package app.kelimo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.ai.Tutor
import app.kelimo.core.loc
import app.kelimo.course.GrammarNote
import app.kelimo.course.titleFor
import app.kelimo.learn.Progress
import app.kelimo.learn.Srs
import app.kelimo.learn.wordId
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============================================================== dictionary

@Composable
fun DictionaryScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s = app.store.value
    val course = remember { app.courses.course(s.profile.target) }
    var q by rememberSaveable { mutableStateOf("") }
    var ai by remember { mutableStateOf<Tutor.Lookup?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiNone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val hits = remember(q) { app.tutor.lookupCurated(q, course, s.profile.native) }
    LaunchedEffect(q) { ai = null; aiNone = false }

    Screen {
        TopBar(t("practice.dictionary"), { nav.pop() })
        Column(Modifier.padding(horizontal = 20.dp)) {
            KField(q, { q = it }, t("dict.hint"), imeAction = ImeAction.Search)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(hits) { LookupCard(it) }
            if (q.length >= 2) item {
                Spacer(Modifier.height(6.dp))
                when {
                    ai != null -> LookupCard(ai!!)
                    aiNone -> Text(t("dict.aiNone"), style = K.t.caption, color = c.ink3)
                    else -> KButton(t("dict.askAi").replace("%s", q), {
                        aiBusy = true
                        scope.launch {
                            val r = if (app.ensureModel()) runCatching { app.tutor.lookupAi(app.learner(), q) }.getOrNull() else null
                            aiBusy = false; ai = r; aiNone = r == null
                        }
                    }, Modifier.fillMaxWidth(), style = BtnStyle.Secondary, loading = aiBusy, icon = Icons.Rounded.AutoAwesome)
                }
            }
            if (q.isBlank()) item { EmptyState(Icons.Rounded.Search, t("dict.empty.title"), t("dict.empty.body")) }
        }
    }
}

@Composable
private fun LookupCard(l: Tutor.Lookup) {
    val app = KelimoApp.instance
    val c = K.c
    val t = LocalT.current
    KCard(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (l.gender != null) Text("${l.gender} ", style = K.t.headline, color = when (l.gender) { "der" -> c.der; "die" -> c.die; else -> c.das })
                    Text(l.word, style = K.t.headline, color = c.ink)
                }
                Text(l.translation, style = K.t.body, color = c.ink2)
                if (l.plural != null) Text("${t("dict.plural")}: ${l.plural}", style = K.t.caption, color = c.ink3)
            }
            IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(listOfNotNull(l.gender, l.word).joinToString(" ")) }, tint = c.accent, bg = c.accentSoft)
        }
        if (l.example != null) {
            Spacer(Modifier.height(8.dp))
            Text(l.example, style = K.t.body, color = c.ink)
            if (!l.exampleTranslation.isNullOrBlank()) Text(l.exampleTranslation, style = K.t.caption, color = c.ink3)
        }
        Spacer(Modifier.height(8.dp))
        if (l.fromCourse) AiBadge(t("dict.verified"), verified = true) else AiBadge(t("ai.label"))
    }
}

// ============================================================== grammar

@Composable
fun GrammarScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s = app.store.value
    val ui = s.profile.ui
    val course = remember { app.courses.course(s.profile.target) }
    var q by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var streamed by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf<Tutor.GrammarAnswer?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Screen {
        TopBar(t("practice.grammar"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
            Text(t("grammar.ask.title"), style = K.t.headline, color = c.ink)
            Text(t("grammar.ask.body"), style = K.t.caption, color = c.ink3)
            Spacer(Modifier.height(10.dp))
            KField(q, { q = it }, t("grammar.ask.hint"), singleLine = false, minLines = 2, imeAction = ImeAction.Default)
            Spacer(Modifier.height(10.dp))
            KButton(t("grammar.ask.go"), {
                busy = true; failed = false; answer = null; streamed = ""
                scope.launch {
                    val r = if (app.ensureModel()) runCatching { app.tutor.askGrammar(app.learner(), q, course) { streamed = it } }.getOrNull() else null
                    busy = false; answer = r; failed = r == null
                }
            }, Modifier.fillMaxWidth(), enabled = q.trim().length >= 4, loading = busy, icon = Icons.Rounded.AutoAwesome)
            if (busy && streamed.isNotBlank()) Text(streamed, style = K.t.body, color = c.ink3, modifier = Modifier.padding(top = 12.dp))
            if (failed) Text(t(if (app.store.value.activeModel == null) "err.noModel" else "err.generic"), style = K.t.caption, color = c.bad, modifier = Modifier.padding(top = 8.dp))
            answer?.let { a ->
                Spacer(Modifier.height(16.dp))
                KCard(Modifier.fillMaxWidth()) {
                    Row { AiBadge(t(if (a.confident) "grammar.grounded" else "grammar.unsure"), verified = a.confident) }
                    Spacer(Modifier.height(8.dp))
                    Text(a.answer, style = K.t.body, color = c.ink)
                    a.examples.forEach { (tg, nat) ->
                        Spacer(Modifier.height(8.dp))
                        Text(tg, style = K.t.bodyStrong, color = c.ink)
                        if (nat.isNotBlank()) Text(nat, style = K.t.caption, color = c.ink3)
                    }
                    if (a.sources.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(t("grammar.sources"), style = K.t.caption, color = c.ink3)
                        a.sources.forEach { n -> Text("· " + n.title.loc(ui), style = K.t.caption, color = c.accent) }
                    }
                }
            }
            if (course != null) {
                SectionTitle(t("grammar.all"))
                course.units.forEach { u ->
                    if (u.grammar.isNotEmpty()) KCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { nav.push(Route.GrammarUnit(u.id)) }, padding = PaddingValues(14.dp)) {
                        Text("${u.cefr} · ${u.titleFor(ui)}", style = K.t.caption, color = c.ink3)
                        Text(u.grammar.joinToString(" · ") { it.title.loc(ui) }, style = K.t.bodyStrong, color = c.ink)
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun GrammarUnitScreen(unitId: String) {
    val app = KelimoApp.instance
    val nav = LocalNav.current
    val s = app.store.value
    val ui = s.profile.ui
    val unit = remember { app.courses.course(s.profile.target)?.units?.firstOrNull { it.id == unitId } }
    Screen {
        TopBar(unit?.titleFor(ui), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            unit?.grammar?.forEach { GrammarNoteCard(it, ui, s.profile.native) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun GrammarNoteCard(n: GrammarNote, ui: String, native: String) {
    val app = KelimoApp.instance
    val c = K.c
    KCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(n.title.loc(ui), style = K.t.headline, color = c.ink)
        Spacer(Modifier.height(8.dp))
        Text(n.body.loc(ui), style = K.t.body, color = c.ink2)
        n.examples.forEach { e ->
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).pressable { app.tts.speak(e.t) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.t, style = K.t.bodyStrong, color = c.ink)
                    Text(e.gloss(native), style = K.t.caption, color = c.ink3)
                }
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = c.ink3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ============================================================== vocabulary

@Composable
fun VocabScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s = app.store.value
    val course = remember { app.currentCourse() }
    val words = remember { course.allWords.distinctBy { it.key }.filter { s.course.srs.containsKey(wordId(it)) } }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val shown = words.map { it to Srs.strength(s.course.srs[wordId(it)]) }.filter { (_, st) -> when (filter) { 1 -> st < 0.4; 2 -> st >= 0.75; else -> true } }.sortedBy { it.second }
    Screen {
        TopBar(t("practice.vocab"), { nav.pop() })
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("vocab.all", "vocab.weak", "vocab.strong").forEachIndexed { i, k ->
                val sel = filter == i
                val bg by animateColorAsState(if (sel) c.accent else c.surface, Motion.quick(), label = "f")
                Box(Modifier.clip(RoundedCornerShape(50)).background(bg).border(1.dp, if (sel) c.accent else c.line, RoundedCornerShape(50)).pressable { filter = i }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text(t(k), style = K.t.label, color = if (sel) c.accentInk else c.ink)
                }
            }
        }
        if (words.isEmpty()) EmptyState(Icons.Rounded.Style, t("vocab.empty.title"), t("vocab.empty.body"), Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.first.key }) { (w, st) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).pressable { app.tts.speak(w.display) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.display, style = K.t.bodyStrong, color = when (w.g) { "m" -> c.der; "f" -> c.die; "n" -> c.das; else -> c.ink })
                        Text(w.gloss(s.profile.native), style = K.t.caption, color = c.ink3)
                    }
                    ProgressBar(st.toFloat(), Modifier.width(64.dp), color = if (st >= 0.75) c.good else if (st >= 0.4) c.warm else c.bad, height = 6.dp)
                }
            }
        }
        if (words.isNotEmpty()) Box(Modifier.padding(20.dp)) { KButton(t("practice.review"), { nav.push(Route.Drill("review")) }, Modifier.fillMaxWidth()) }
    }
}

// ============================================================== mistakes

@Composable
fun MistakesScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s by app.store.state.collectAsState()
    val open = s.course.mistakes.filter { !it.fixed }.reversed()
    Screen {
        TopBar(t("practice.mistakes"), { nav.pop() })
        if (open.isEmpty()) EmptyState(Icons.Rounded.TaskAlt, t("mistakes.empty.title"), t("mistakes.empty.body"), Modifier.weight(1f))
        else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(open, key = { it.cardId + it.at }) { m ->
                    KCard(Modifier.fillMaxWidth(), padding = PaddingValues(14.dp)) {
                        Text(m.prompt, style = K.t.caption, color = c.ink3)
                        if (m.given.isNotBlank()) Text(m.given, style = K.t.body, color = c.bad)
                        Text(m.expected, style = K.t.bodyStrong, color = c.good)
                    }
                }
            }
            Box(Modifier.padding(20.dp)) { KButton(t("mistakes.practice").replace("%d", "${open.size}"), { nav.push(Route.Drill("mistakes")) }, Modifier.fillMaxWidth()) }
        }
    }
}

// ============================================================== stories

private val storyTopics = listOf("story.t.market", "story.t.weekend", "story.t.job", "story.t.trip", "story.t.family", "story.t.doctor", "story.t.party", "story.t.lost")

@Composable
fun StoriesScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    var story by remember { mutableStateOf<Tutor.Story?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var streamedLen by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateMapOf<Int, Int>() }
    var showTr by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val h = rememberHaptics()

    fun make(topicKey: String) {
        busy = true; failed = false; story = null; answers.clear(); streamedLen = 0
        scope.launch {
            val r = if (app.ensureModel()) runCatching { app.tutor.story(app.learner(), t(topicKey)) { streamedLen = it.length } }.getOrNull() else null
            busy = false; story = r; failed = r == null
        }
    }

    Screen {
        TopBar(t("practice.stories"), { nav.pop() }) {
            if (story != null) IconBtn(Icons.Rounded.Translate, t("chat.translate"), { showTr = !showTr }, tint = if (showTr) c.accent else c.ink2)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            val st = story
            if (st == null) {
                Text(t("story.pick"), style = K.t.headline, color = c.ink)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    storyTopics.forEach { k ->
                        Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).pressable(enabled = !busy) { make(k) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(t(k), style = K.t.label, color = c.ink)
                        }
                    }
                }
                if (busy) {
                    Spacer(Modifier.height(24.dp))
                    ProgressBar((streamedLen / 2200f).coerceAtMost(0.95f), Modifier.fillMaxWidth())
                    Text(t("story.writing"), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
                }
                if (failed) Text(t(if (app.store.value.activeModel == null) "err.noModel" else "err.generic"), style = K.t.caption, color = c.bad, modifier = Modifier.padding(top = 12.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(st.title, style = K.t.title, color = c.ink, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.PlayCircle, "play", { app.tts.speak(st.lines.joinToString(" ") { it.first }) }, tint = c.accent, size = 48.dp)
                }
                AiBadge(t("ai.label"))
                Spacer(Modifier.height(12.dp))
                st.lines.forEach { (tg, nat) ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable(scaleTo = 0.99f) { app.tts.speak(tg) }.padding(vertical = 6.dp)) {
                        Text(tg, style = K.t.body.copy(fontSize = K.t.body.fontSize * 1.08f), color = c.ink)
                        AnimatedVisibility(showTr && nat.isNotBlank()) { Text(nat, style = K.t.caption, color = c.ink3) }
                    }
                }
                if (st.questions.isNotEmpty()) {
                    SectionTitle(t("story.questions"))
                    st.questions.forEachIndexed { qi, q ->
                        Text(q.q, style = K.t.bodyStrong, color = c.ink, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                        q.options.forEachIndexed { oi, o ->
                            val picked = answers[qi]
                            val state = when { picked == null -> 0; oi == q.answer -> 1; oi == picked -> 2; else -> 0 }
                            val bg by animateColorAsState(when (state) { 1 -> c.goodSoft; 2 -> c.badSoft; else -> c.surface }, Motion.quick(), label = "q")
                            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(bg).border(1.dp, c.line, RoundedCornerShape(14.dp))
                                .pressable(enabled = picked == null) {
                                    answers[qi] = oi
                                    if (oi == q.answer) { h.confirm() } else h.reject()
                                }.padding(12.dp)) { Text(o, style = K.t.body, color = c.ink) }
                        }
                    }
                    val done = answers.size == st.questions.size
                    LaunchedEffect(done) { if (done) { delay(300); Progress.addXp(app.store, 5 + 2 * answers.count { (k, v) -> st.questions[k].answer == v }) } }
                }
                Spacer(Modifier.height(16.dp))
                KButton(t("story.another"), { story = null }, Modifier.fillMaxWidth(), style = BtnStyle.Secondary)
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ============================================================== AI lessons (non-curated languages)

private val lessonTopics = listOf("ai.t.greetings", "ai.t.numbers", "ai.t.food", "ai.t.family", "ai.t.directions", "ai.t.shopping", "ai.t.work", "ai.t.time", "ai.t.health", "ai.t.hobbies")

@Composable
fun AiLessonsScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s by app.store.state.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen {
        TopBar(t("ai.course.title"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Text(t("ai.course.warn"), style = K.t.caption, color = c.warm)
            SectionTitle(t("ai.course.new"))
            lessonTopics.forEach { k ->
                val exists = s.course.aiLessons.any { it.title == t(k) }
                SelectRow(t(k), exists, {
                    if (busy != null) return@SelectRow
                    busy = k; failed = false
                    scope.launch {
                        val l = if (app.ensureModel()) runCatching { app.tutor.generateLesson(app.learner(), t(k)) }.getOrNull() else null
                        busy = null
                        if (l == null) failed = true else app.store.updateCourse { p -> p.copy(aiLessons = p.aiLessons + l.copy(title = t(k))) }
                    }
                }, Modifier.padding(vertical = 4.dp), subtitle = if (busy == k) t("story.writing") else null)
            }
            if (failed) Text(t(if (s.activeModel == null) "err.noModel" else "err.generic"), style = K.t.caption, color = c.bad, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}
