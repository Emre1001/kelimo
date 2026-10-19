package app.kelimo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.ai.EngineState
import app.kelimo.core.Lang
import app.kelimo.course.Course
import app.kelimo.learn.*
import app.kelimo.speech.SttState
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed interface SessionKind {
    data class Lesson(val id: String) : SessionKind
    /** review, mistakes, articles, verbs, listening, speaking */
    data class Drill(val kind: String) : SessionKind
}

/** What an exercise reports when checked. */
data class AnswerResult(val verdict: Verdict, val score: Double, val given: String, val expected: String, val note: String? = null)

/** Lets the shared bottom bar check whichever exercise is on screen. */
class AnswerHolder {
    var ready by mutableStateOf(false)
    var check: () -> AnswerResult = { AnswerResult(Verdict.Wrong, 0.0, "", "") }
}

private data class Item(val ex: Exercise, val retry: Boolean)

@Composable
fun LessonScreen(kind: SessionKind) {
    val app = KelimoApp.instance
    val nav = LocalNav.current
    val t = LocalT.current
    val s = app.store.value
    val course = remember { app.currentCourse() }
    val native = s.profile.native
    val target = Lang.of(s.profile.target)
    val factory = remember { ExerciseFactory(course, native, listening = s.settings.listeningExercises, speaking = s.settings.speakingExercises) }

    val queue = remember {
        mutableStateListOf<Item>().apply {
            val list = when (kind) {
                is SessionKind.Lesson -> course.lesson(kind.id)?.let { factory.lessonSession(it, s.course) }.orEmpty()
                is SessionKind.Drill -> when (kind.kind) {
                    "review" -> {
                        val ids = Srs.due(s.course).map { it.id } + s.course.mistakes.filter { !it.fixed }.map { it.cardId }
                        factory.reviewSession(ids.ifEmpty { s.course.srs.values.sortedBy { Srs.strength(it) }.take(15).map { it.id } })
                    }
                    "mistakes" -> factory.reviewSession(s.course.mistakes.filter { !it.fixed }.map { it.cardId }.reversed())
                    "articles" -> factory.articleDrill()
                    "verbs" -> factory.verbDrill()
                    "listening" -> factory.listeningDrill()
                    "speaking" -> factory.speakingDrill()
                    else -> emptyList()
                }
            }
            addAll(list.map { Item(it, false) })
        }
    }
    val initialSize = remember { queue.size }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<AnswerResult?>(null) }
    var combo by remember { mutableIntStateOf(0) }
    var bestCombo by remember { mutableIntStateOf(0) }
    var firstTryRight by remember { mutableIntStateOf(0) }
    var firstTryTotal by remember { mutableIntStateOf(0) }
    var showQuit by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    val started = remember { System.currentTimeMillis() }
    // One holder per exercise: the outgoing exercise keeps composing during the transition and must not touch the new one.
    val holders = remember { mutableMapOf<Int, AnswerHolder>() }
    val holder = holders.getOrPut(index) { AnswerHolder() }
    val h = rememberHaptics()

    LaunchedEffect(Unit) { app.tts.setLanguage(target.locale); app.tts.rate = s.settings.ttsRate }
    DisposableEffect(Unit) { onDispose { app.tts.stop(); app.stt.stop() } }

    if (queue.isEmpty()) {
        Screen {
            TopBar(null, { nav.pop() })
            EmptyState(Icons.Rounded.TaskAlt, t("lesson.empty.title"), t("lesson.empty.body"), Modifier.weight(1f))
        }
        return
    }

    val xp = remember(finished) {
        val acc = if (firstTryTotal == 0) 1.0 else firstTryRight.toDouble() / firstTryTotal
        10 + (if (acc >= 0.9) 5 else 0) + (if (bestCombo >= 5) 3 else 0)
    }
    if (finished) {
        DoneScreen(xp, firstTryRight, firstTryTotal, bestCombo, (System.currentTimeMillis() - started) / 1000) { nav.pop() }
        return
    }

    BackHandler { showQuit = true }

    fun submit(r: AnswerResult) {
        if (feedback != null) return
        val item = queue[index]
        val first = !item.retry
        feedback = r
        if (r.verdict == Verdict.Wrong) { h.reject(); combo = 0 } else { h.confirm(); combo++; bestCombo = maxOf(bestCombo, combo) }
        if (first) { firstTryTotal++; if (r.verdict != Verdict.Wrong) firstTryRight++ }
        Progress.answer(app.store, item.ex, r.verdict, r.score, promptOf(item.ex), r.given, r.expected, first)
        // Wrong answers come back once at the end, like a real teacher would do.
        if (r.verdict == Verdict.Wrong && first && item.ex !is Exercise.MatchPairs) queue.add(Item(item.ex, true))
        if (r.verdict != Verdict.Wrong && (item.ex is Exercise.BuildTarget || item.ex is Exercise.Cloze)) app.tts.speak(r.expected)
    }

    fun next() {
        feedback = null
        if (index + 1 >= queue.size) {
            Progress.finishLesson(app.store, (kind as? SessionKind.Lesson)?.id, xp)
            finished = true
        } else index++
    }

    Box(Modifier.fillMaxSize().background(K.c.bg)) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // Header: close, progress, combo.
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.Close, "close", { showQuit = true }, tint = K.c.ink2)
                val done = index + (if (feedback != null) 1 else 0)
                ProgressBar(done / queue.size.toFloat().coerceAtLeast(initialSize.toFloat()), Modifier.weight(1f).padding(horizontal = 8.dp), color = if (combo >= 3) K.c.warm else K.c.good)
                AnimatedVisibility(combo >= 3, enter = scaleIn(Motion.press()) + fadeIn(), exit = fadeOut()) {
                    Pill("×$combo", color = K.c.warmSoft, fg = K.c.warm, icon = Icons.Rounded.Bolt)
                }
                Spacer(Modifier.width(8.dp))
            }
            AnimatedContent(
                index,
                transitionSpec = { (slideInHorizontally(Motion.offset) { it / 3 } + fadeIn(Motion.quick())) togetherWith (slideOutHorizontally(Motion.offset) { -it / 3 } + fadeOut(Motion.quick())) },
                modifier = Modifier.weight(1f), label = "exercise",
            ) { i ->
                val item = queue[i]
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    if (item.retry) Pill(t("lesson.retry"), color = K.c.warmSoft, fg = K.c.warm, icon = Icons.Rounded.Replay, modifier = Modifier.padding(bottom = 10.dp))
                    ExerciseView(item.ex, target, holders.getOrPut(i) { AnswerHolder() }, locked = feedback != null || i != index, onAuto = ::submit)
                }
            }
            // Bottom: check button (feedback panel overlays it).
            val auto = queue[index].ex.let { it is Exercise.MatchPairs || it is Exercise.Article || it is Exercise.Speak }
            Box(Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 54.dp)) {
                if (!auto) KButton(t("lesson.check"), { submit(holder.check()) }, Modifier.fillMaxWidth(), enabled = holder.ready && feedback == null)
            }
        }

        AnimatedVisibility(
            feedback != null, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(Motion.offset) { it } + fadeIn(Motion.quick()),
            exit = slideOutVertically(Motion.offset) { it } + fadeOut(Motion.quick()),
        ) {
            feedback?.let { fb -> FeedbackPanel(fb, queue[index].ex, course, onContinue = ::next) }
        }

        if (showQuit) QuitDialog({ showQuit = false }) { showQuit = false; nav.pop() }
    }

    // Auto-play audio for the new exercise.
    LaunchedEffect(index) {
        val a = queue[index].ex.audio
        if (a != null && s.settings.autoplayAudio && queue[index].ex !is Exercise.Speak) { delay(280); app.tts.speak(a) }
    }
}

private fun promptOf(e: Exercise): String = when (e) {
    is Exercise.PickWord -> e.prompt
    is Exercise.PickMeaning -> e.word
    is Exercise.MatchPairs -> e.pairs.joinToString { it.first }
    is Exercise.BuildTarget -> e.prompt
    is Exercise.BuildNative -> e.sentence
    is Exercise.Dictation -> e.sentence
    is Exercise.Speak -> e.sentence
    is Exercise.Article -> e.noun
    is Exercise.Cloze -> e.full
    is Exercise.TypeWord -> e.prompt
    is Exercise.Conjugate -> "${e.pronoun} (${e.infinitive})"
}

// ============================================================== exercise views

@Composable
private fun ExerciseView(ex: Exercise, target: Lang, holder: AnswerHolder, locked: Boolean, onAuto: (AnswerResult) -> Unit) {
    val t = LocalT.current
    val title = when (ex) {
        is Exercise.PickWord -> t("ex.pickWord")
        is Exercise.PickMeaning -> t("ex.pickMeaning")
        is Exercise.MatchPairs -> t("ex.match")
        is Exercise.BuildTarget -> t("ex.buildTarget")
        is Exercise.BuildNative -> t("ex.buildNative")
        is Exercise.Dictation -> t("ex.dictation")
        is Exercise.Speak -> t("ex.speak")
        is Exercise.Article -> t("ex.article")
        is Exercise.Cloze -> t("ex.cloze")
        is Exercise.TypeWord -> t("ex.typeWord").replace("%s", target.endonym)
        is Exercise.Conjugate -> t("ex.conjugate")
    }
    Text(title, style = K.t.title, color = K.c.ink)
    Spacer(Modifier.height(18.dp))
    when (ex) {
        is Exercise.PickWord -> { PromptBubble(ex.prompt, null); Choices(ex.options, ex.answer, holder, locked) }
        is Exercise.PickMeaning -> { PromptBubble(ex.word, ex.word); Choices(ex.options, ex.answer, holder, locked) }
        is Exercise.Cloze -> { ClozeView(ex); Spacer(Modifier.height(12.dp)); Choices(ex.options, ex.answer, holder, locked, expectedText = ex.full) }
        is Exercise.MatchPairs -> MatchView(ex, locked, onAuto)
        is Exercise.BuildTarget -> { PromptBubble(ex.prompt, null); TileBuilder(ex.tiles, ex.answer, holder, locked, if (target == Lang.DE) WordOrder.GermanV2 else WordOrder.Fixed, ex.alternatives) }
        is Exercise.BuildNative -> { PromptBubble(ex.sentence, ex.sentence); TileBuilder(ex.tiles, ex.answer, holder, locked, if (KelimoApp.instance.store.value.profile.native == "tr") WordOrder.VerbFinal else WordOrder.Fixed) }
        is Exercise.Dictation -> DictationView(ex, target, holder, locked)
        is Exercise.TypeWord -> { PromptBubble(ex.prompt, null); TypeView(ex.accepted, target, holder, locked) }
        is Exercise.Conjugate -> { PromptBubble("${ex.pronoun}  ·  ${ex.infinitive}", null, sub = ex.meaning); TypeView(listOf(ex.answer, "${ex.pronoun.substringBefore('/')} ${ex.answer}"), target, holder, locked) }
        is Exercise.Article -> ArticleView(ex, locked, onAuto)
        is Exercise.Speak -> SpeakView(ex, target, locked, onAuto)
    }
}

@Composable
private fun PromptBubble(text: String, speak: String?, sub: String? = null) {
    val app = KelimoApp.instance
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (speak != null) {
            IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(speak) }, tint = K.c.accentInk, bg = K.c.accent, size = 48.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(K.c.surface).border(1.dp, K.c.line, RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(text, style = K.t.headline, color = K.c.ink)
            if (sub != null) Text(sub, style = K.t.caption, color = K.c.ink3)
        }
    }
    Spacer(Modifier.height(22.dp))
}

@Composable
private fun Choices(options: List<String>, answer: Int, holder: AnswerHolder, locked: Boolean, expectedText: String? = null) {
    var sel by remember { mutableIntStateOf(-1) }
    val h = rememberHaptics()
    SideEffect { holder.ready = sel >= 0 }
    holder.check = {
        AnswerResult(if (sel == answer) Verdict.Correct else Verdict.Wrong, if (sel == answer) 1.0 else 0.0, options.getOrElse(sel) { "" }, expectedText ?: options[answer])
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEachIndexed { i, o ->
            val state = when {
                locked && i == answer -> 2
                locked && i == sel -> 3
                i == sel -> 1
                else -> 0
            }
            val c = K.c
            val bg by animateColorAsState(when (state) { 1 -> c.accentSoft; 2 -> c.goodSoft; 3 -> c.badSoft; else -> c.surface }, Motion.quick(), label = "c")
            val bd by animateColorAsState(when (state) { 1 -> c.accent; 2 -> c.good; 3 -> c.bad; else -> c.line }, Motion.quick(), label = "b")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).border(1.5.dp, bd, RoundedCornerShape(18.dp))
                    .pressable(enabled = !locked, scaleTo = 0.98f) { h.tick(); sel = i }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).border(1.5.dp, bd, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = K.t.caption, color = if (state == 0) c.ink3 else bd)
                }
                Spacer(Modifier.width(14.dp))
                Text(o, style = K.t.body, color = c.ink, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ClozeView(ex: Exercise.Cloze) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(K.c.surface).border(1.dp, K.c.line, RoundedCornerShape(20.dp)).padding(18.dp)) {
        Text(buildAnnotatedString {
            append(ex.before); append(" ")
            withStyle(SpanStyle(color = K.c.accent, fontWeight = FontWeight.Bold)) { append("_____") }
            append(ex.after)
        }, style = K.t.headline, color = K.c.ink)
        Spacer(Modifier.height(6.dp))
        Text(ex.meaning, style = K.t.caption, color = K.c.ink3)
    }
}

@Composable
private fun TileBuilder(tiles: List<String>, answer: String, holder: AnswerHolder, locked: Boolean, order: WordOrder, alternatives: List<String> = emptyList()) {
    // Indices into tiles, in the order the learner placed them. Bank tiles keep their slot (ghost) when used.
    val chosen = remember { mutableStateListOf<Int>() }
    val h = rememberHaptics()
    SideEffect { holder.ready = chosen.isNotEmpty() }
    holder.check = {
        val g = Grader.tiles(chosen.map { tiles[it] }, answer, alternatives, order)
        AnswerResult(g.verdict, if (g.passed) 1.0 else 0.0, chosen.joinToString(" ") { tiles[it] }, answer, g.note)
    }
    Column {
        FlowRow(
            Modifier.fillMaxWidth().heightIn(min = 112.dp).drawLines(K.c.line),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            chosen.forEach { idx ->
                key(idx) {
                    var shown by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { shown = true }
                    val sc by animateFloatAsState(if (shown) 1f else 0.7f, Motion.press(), label = "tile")
                    WordTile(tiles[idx], Modifier.graphicsLayer { scaleX = sc; scaleY = sc }, enabled = !locked) { h.tick(); chosen.remove(idx) }
                }
            }
        }
        Spacer(Modifier.height(26.dp))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.forEachIndexed { i, w ->
                val used = i in chosen
                WordTile(w, ghost = used, enabled = !locked && !used) { h.tick(); chosen.add(i) }
            }
        }
    }
}

/** Ruled lines under the answer area, like a notebook. */
private fun Modifier.drawLines(color: Color) = this.drawBehind {
    val step = 56.dp.toPx()
    var y = step - 4.dp.toPx()
    while (y < size.height) { drawLine(color, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx()); y += step }
}

@Composable
private fun WordTile(text: String, modifier: Modifier = Modifier, ghost: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val c = K.c
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(if (ghost) c.surface2 else c.surface)
            .border(1.5.dp, if (ghost) Color.Transparent else c.line, RoundedCornerShape(14.dp))
            .pressable(enabled = enabled, scaleTo = 0.92f, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) { Text(text, style = K.t.body, color = if (ghost) Color.Transparent else c.ink) }
}

private fun specialChars(target: Lang): List<String> = when (target) {
    Lang.DE -> listOf("ä", "ö", "ü", "ß", "Ä", "Ö", "Ü")
    Lang.ES -> listOf("á", "é", "í", "ó", "ú", "ñ", "¿", "¡")
    Lang.FR -> listOf("é", "è", "ê", "à", "ç", "ù", "œ")
    Lang.PT -> listOf("ã", "õ", "á", "é", "ç", "ê", "ô")
    Lang.IT -> listOf("à", "è", "é", "ì", "ò", "ù")
    Lang.PL -> listOf("ą", "ć", "ę", "ł", "ń", "ó", "ś", "ź", "ż")
    Lang.TR -> listOf("ç", "ğ", "ı", "ö", "ş", "ü", "İ")
    else -> emptyList()
}

@Composable
private fun TypeView(accepted: List<String>, target: Lang, holder: AnswerHolder, locked: Boolean) {
    val t = LocalT.current
    var text by remember { mutableStateOf("") }
    SideEffect { holder.ready = text.isNotBlank() }
    holder.check = {
        val g = Grader.typed(text, accepted)
        AnswerResult(g.verdict, if (g.passed) 1.0 else 0.0, text, g.expected, g.note)
    }
    KField(text, { if (!locked) text = it }, t("lesson.typeHere"), imeAction = ImeAction.Done, enabled = !locked)
    CharBar(target, locked) { text += it }
}

@Composable
private fun CharBar(target: Lang, locked: Boolean, onChar: (String) -> Unit) {
    val chars = specialChars(target)
    if (chars.isEmpty()) return
    val h = rememberHaptics()
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        chars.forEach { ch ->
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(K.c.surface2).pressable(enabled = !locked, scaleTo = 0.88f) { h.tick(); onChar(ch) },
                contentAlignment = Alignment.Center,
            ) { Text(ch, style = K.t.bodyStrong, color = K.c.ink) }
        }
    }
}

@Composable
private fun DictationView(ex: Exercise.Dictation, target: Lang, holder: AnswerHolder, locked: Boolean) {
    val app = KelimoApp.instance
    val t = LocalT.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(ex.sentence) }, tint = K.c.accentInk, bg = K.c.accent, size = 84.dp)
        IconBtn(Icons.Rounded.SlowMotionVideo, "slow", { app.tts.speak(ex.sentence, slow = true) }, tint = K.c.accent, bg = K.c.accentSoft, size = 60.dp)
    }
    Spacer(Modifier.height(24.dp))
    var text by remember { mutableStateOf("") }
    SideEffect { holder.ready = text.isNotBlank() }
    holder.check = {
        val g = Grader.typed(text, listOf(ex.sentence))
        AnswerResult(g.verdict, if (g.passed) 1.0 else 0.0, text, ex.sentence, g.note)
    }
    KField(text, { if (!locked) text = it }, t("lesson.typeHeard"), singleLine = false, minLines = 2, enabled = !locked)
    CharBar(target, locked) { text += it }
    if (locked) { Spacer(Modifier.height(10.dp)); Text(ex.meaning, style = K.t.caption, color = K.c.ink3) }
}

@Composable
private fun ArticleView(ex: Exercise.Article, locked: Boolean, onAuto: (AnswerResult) -> Unit) {
    val c = K.c
    var picked by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        Text(ex.noun, style = K.t.display, color = c.ink)
        Text(ex.meaning, style = K.t.body, color = c.ink3)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("der" to c.der, "die" to c.die, "das" to c.das).forEach { (a, col) ->
                val state = when { locked && a == ex.answer -> 1; locked && a == picked -> 2; else -> 0 }
                val alpha by animateFloatAsState(if (locked && state == 0) 0.35f else 1f, Motion.quick(), label = "a")
                Box(
                    Modifier.weight(1f).height(76.dp).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(20.dp))
                        .background(if (state == 2) c.bad else col)
                        .pressable(enabled = !locked, scaleTo = 0.93f) {
                            picked = a
                            onAuto(AnswerResult(if (a == ex.answer) Verdict.Correct else Verdict.Wrong, if (a == ex.answer) 1.0 else 0.0, "$a ${ex.noun}", "${ex.answer} ${ex.noun}"))
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(a, style = K.t.title, color = Color.White) }
            }
        }
    }
}

@Composable
private fun MatchView(ex: Exercise.MatchPairs, locked: Boolean, onAuto: (AnswerResult) -> Unit) {
    val left = remember { ex.pairs.map { it.first }.shuffled() }
    val right = remember { ex.pairs.map { it.second }.shuffled() }
    var selL by remember { mutableStateOf<String?>(null) }
    var selR by remember { mutableStateOf<String?>(null) }
    val matched = remember { mutableStateListOf<String>() }
    var wrongFlash by remember { mutableStateOf<Pair<String, String>?>(null) }
    var mistakes by remember { mutableIntStateOf(0) }
    val h = rememberHaptics()
    val app = KelimoApp.instance

    LaunchedEffect(selL, selR) {
        val l = selL; val r = selR
        if (l != null && r != null) {
            if (ex.pairs.any { it.first == l && it.second == r }) {
                h.confirm(); matched += l; selL = null; selR = null
            } else {
                h.reject(); mistakes++; wrongFlash = l to r; delay(450); wrongFlash = null; selL = null; selR = null
            }
        }
    }
    // Separate effect: resetting the selection above would cancel a delay running inside that one.
    LaunchedEffect(matched.size) {
        if (matched.size == ex.pairs.size) {
            delay(250)
            val score = (1.0 - mistakes.toDouble() / ex.pairs.size).coerceAtLeast(0.0)
            onAuto(AnswerResult(if (mistakes <= 1) Verdict.Correct else Verdict.Typo, score, "", ""))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            left.forEach { w ->
                MatchTile(w, selected = selL == w, done = w in matched, wrong = wrongFlash?.first == w, enabled = !locked) { selL = w; app.tts.speak(w) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            right.forEach { g ->
                val done = ex.pairs.any { it.second == g && it.first in matched }
                MatchTile(g, selected = selR == g, done = done, wrong = wrongFlash?.second == g, enabled = !locked) { selR = g }
            }
        }
    }
}

@Composable
private fun MatchTile(text: String, selected: Boolean, done: Boolean, wrong: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = K.c
    val bg by animateColorAsState(when { wrong -> c.badSoft; done -> c.goodSoft; selected -> c.accentSoft; else -> c.surface }, Motion.quick(), label = "m")
    val bd by animateColorAsState(when { wrong -> c.bad; done -> c.good.copy(alpha = 0.3f); selected -> c.accent; else -> c.line }, Motion.quick(), label = "mb")
    val alpha by animateFloatAsState(if (done) 0.45f else 1f, Motion.quick(), label = "ma")
    Box(
        Modifier.fillMaxWidth().heightIn(min = 58.dp).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(16.dp)).background(bg).border(1.5.dp, bd, RoundedCornerShape(16.dp))
            .pressable(enabled = enabled && !done, scaleTo = 0.95f, onClick = onClick).padding(10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = K.t.body, color = c.ink, textAlign = TextAlign.Center) }
}

@Composable
private fun SpeakView(ex: Exercise.Speak, target: Lang, locked: Boolean, onAuto: (AnswerResult) -> Unit) {
    val app = KelimoApp.instance
    val t = LocalT.current
    val ctx = LocalContext.current
    val stt by app.stt.state.collectAsState()
    val level by app.stt.level.collectAsState()
    var result by remember { mutableStateOf<Grader.SpeechScore?>(null) }
    var attempts by remember { mutableIntStateOf(0) }
    var permDenied by remember { mutableStateOf(false) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permDenied = !ok
        if (ok) app.stt.start(target.locale)
    }
    LaunchedEffect(Unit) { app.stt.reset() }
    LaunchedEffect(stt) {
        val st = stt
        if (st is SttState.Result && !locked) {
            attempts++
            val sc = Grader.speech(st.alternatives, ex.sentence)
            result = sc
            if (sc.score >= 0.7 || attempts >= 3) {
                delay(500)
                onAuto(AnswerResult(if (sc.score >= 0.9) Verdict.Correct else if (sc.score >= 0.7) Verdict.Typo else Verdict.Wrong, sc.score, st.alternatives.first(), ex.sentence, "speech"))
            }
            // Reset last: it changes this effect's key and would cancel the delay above.
            app.stt.reset()
        }
    }
    val words = ex.sentence.split(' ')
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(ex.sentence) }, tint = K.c.accent, bg = K.c.accentSoft)
            Spacer(Modifier.width(6.dp))
            IconBtn(Icons.Rounded.SlowMotionVideo, "slow", { app.tts.speak(ex.sentence, slow = true) }, tint = K.c.accent, bg = K.c.accentSoft)
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            words.forEachIndexed { i, w ->
                val m = result?.matched?.getOrNull(i)
                Text(w, style = K.t.title, color = when (m) { true -> K.c.good; false -> K.c.bad; null -> K.c.ink })
            }
        }
        Text(ex.meaning, style = K.t.body, color = K.c.ink3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(36.dp))
        val listening = stt is SttState.Listening || stt is SttState.Partial
        val ring by animateFloatAsState(if (listening) 1f + level * 0.25f else 1f, Motion.press(), label = "ring")
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(112.dp).graphicsLayer { scaleX = ring; scaleY = ring }.clip(CircleShape).background(K.c.accentSoft))
            Box(
                Modifier.size(88.dp).clip(CircleShape).background(if (listening) K.c.bad else K.c.accent)
                    .pressable(enabled = !locked, scaleTo = 0.9f) {
                        if (listening) app.stt.finish()
                        else if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perm.launch(Manifest.permission.RECORD_AUDIO)
                        else { app.tts.stop(); result = null; app.stt.start(target.locale) }
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp)) }
        }
        Spacer(Modifier.height(12.dp))
        when (val st = stt) {
            is SttState.Partial -> Text(st.text, style = K.t.body, color = K.c.ink2, textAlign = TextAlign.Center)
            is SttState.Listening -> Waveform(level)
            is SttState.Unavailable -> Text(t("err.sttUnavailable"), style = K.t.caption, color = K.c.bad, textAlign = TextAlign.Center)
            is SttState.Error -> Text(t("err.sttRetry"), style = K.t.caption, color = K.c.ink3, textAlign = TextAlign.Center)
            else -> result?.let { Text("${(it.score * 100).toInt()} %  ·  ${t(if (it.score >= 0.7) "speak.good" else "speak.again")}", style = K.t.label, color = if (it.score >= 0.7) K.c.good else K.c.warm) }
                ?: Text(t("speak.tap"), style = K.t.caption, color = K.c.ink3)
        }
        if (permDenied) Text(t("err.mic"), style = K.t.caption, color = K.c.bad)
        Spacer(Modifier.height(16.dp))
        if (!locked) KButton(t("speak.cant"), {
            app.stt.stop()
            onAuto(AnswerResult(Verdict.Typo, 0.5, "", ex.sentence, "skipped"))
        }, style = BtnStyle.Ghost)
    }
}

// ============================================================== feedback, done, quit

@Composable
private fun FeedbackPanel(fb: AnswerResult, ex: Exercise, course: Course, onContinue: () -> Unit) {
    val app = KelimoApp.instance
    val t = LocalT.current
    val c = K.c
    val ok = fb.verdict != Verdict.Wrong
    val (bg, fg) = if (fb.verdict == Verdict.Wrong) c.badSoft to c.bad else if (fb.verdict == Verdict.Typo) c.warmSoft to c.warm else c.goodSoft to c.good
    var explanation by remember { mutableStateOf<String?>(null) }
    var explaining by remember { mutableStateOf(false) }
    var explainFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val modelReady by app.llm.state.collectAsState()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(bg)
            .navigationBarsPadding().padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(fg), contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(
                t(when { fb.verdict == Verdict.Correct -> "fb.correct"; fb.note == "order" -> "fb.order"; fb.note == "umlaut" -> "fb.umlaut"; fb.note == "skipped" -> "fb.skipped"; fb.verdict == Verdict.Typo -> "fb.typo"; else -> "fb.wrong" }),
                style = K.t.headline, color = fg,
            )
        }
        if (fb.verdict != Verdict.Correct && fb.expected.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(t("fb.answer"), style = K.t.caption, color = fg)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fb.expected, style = K.t.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
                // Only target-language answers get audio; a native gloss read by the target voice would be wrong.
                if (ex !is Exercise.PickMeaning && ex !is Exercise.BuildNative && fb.expected.any { it.isLetter() }) IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(fb.expected) }, tint = fg, size = 40.dp)
            }
        }
        if (fb.verdict == Verdict.Wrong && fb.given.isNotBlank()) {
            AnimatedContent(explanation, label = "expl") { e ->
                Column {
                    if (e != null) {
                        Spacer(Modifier.height(10.dp))
                        AiBadge(t("ai.label"))
                        Spacer(Modifier.height(6.dp))
                        Text(e, style = K.t.body, color = c.ink)
                    } else if (modelReady is EngineState.Ready || modelReady is EngineState.Idle) {
                        Spacer(Modifier.height(6.dp))
                        KButton(
                            t(if (explainFailed) "fb.whyFailed" else "fb.why"), {
                                explaining = true
                                scope.launch {
                                    val unit = course.lessons.firstOrNull { l -> l.words.any { wordId(it) == ex.cardId } || l.sentences.any { sentId(it) == ex.cardId } }?.let { course.unitOf(it.id) }
                                    val r = if (!app.ensureModel()) null else runCatching {
                                        app.tutor.explainMistake(app.learner(), promptOf(ex), fb.expected, fb.given, unit?.grammar.orEmpty())
                                    }.getOrNull()
                                    explaining = false
                                    if (r == null) explainFailed = true else explanation = r
                                }
                            }, style = BtnStyle.Ghost, loading = explaining, icon = Icons.Rounded.AutoAwesome, height = 44.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        KButton(t("lesson.continue"), onContinue, Modifier.fillMaxWidth(), style = if (fb.verdict == Verdict.Wrong) BtnStyle.Bad else BtnStyle.Good)
    }
}

@Composable
private fun DoneScreen(xp: Int, right: Int, total: Int, combo: Int, seconds: Long, onClose: () -> Unit) {
    val t = LocalT.current
    val shownXp = remember { Animatable(0f) }
    val h = rememberHaptics()
    LaunchedEffect(Unit) { h.confirm(); shownXp.animateTo(xp.toFloat(), tween(900)) }
    val acc = if (total == 0) 100 else right * 100 / total
    Column(Modifier.fillMaxSize().background(K.c.bg).systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        ScaleIn(true) {
            Box(Modifier.size(120.dp).clip(CircleShape).background(K.c.goodSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.EmojiEvents, null, tint = K.c.good, modifier = Modifier.size(60.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(t(if (acc >= 90) "done.great" else "done.title"), style = K.t.display, color = K.c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("+${shownXp.value.toInt()}", "XP", K.c.warm, Modifier.weight(1f))
            StatBox("$acc %", t("done.accuracy"), K.c.good, Modifier.weight(1f))
            StatBox("%d:%02d".format(seconds / 60, seconds % 60), t("done.time"), K.c.accent, Modifier.weight(1f))
        }
        if (combo >= 5) { Spacer(Modifier.height(12.dp)); Pill(t("done.combo").replace("%d", "$combo"), color = K.c.warmSoft, fg = K.c.warm, icon = Icons.Rounded.Bolt) }
        Spacer(Modifier.weight(1f))
        KButton(t("lesson.continue"), onClose, Modifier.fillMaxWidth())
    }
}

@Composable
fun StatBox(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(K.c.surface).border(1.dp, K.c.line, RoundedCornerShape(20.dp)).padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = K.t.headline, color = color)
        Text(label, style = K.t.caption, color = K.c.ink3)
    }
}

@Composable
private fun QuitDialog(onStay: () -> Unit, onQuit: () -> Unit) {
    val t = LocalT.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).pressable(scaleTo = 1f, onClick = onStay), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(K.c.surface)
                .pressable(scaleTo = 1f) {}.navigationBarsPadding().padding(24.dp)
        ) {
            Text(t("quit.title"), style = K.t.title, color = K.c.ink)
            Spacer(Modifier.height(6.dp))
            Text(t("quit.body"), style = K.t.body, color = K.c.ink2)
            Spacer(Modifier.height(20.dp))
            KButton(t("quit.stay"), onStay, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            KButton(t("quit.leave"), onQuit, Modifier.fillMaxWidth(), style = BtnStyle.Ghost)
        }
    }
}
