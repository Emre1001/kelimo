package app.kelimo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.ai.Scenarios
import app.kelimo.core.Lang
import app.kelimo.core.loc
import app.kelimo.data.ChatMsg
import app.kelimo.data.ChatThread
import app.kelimo.data.Correction
import app.kelimo.learn.Grader
import app.kelimo.learn.Progress
import app.kelimo.speech.SttState
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private enum class Phase { Idle, Thinking, Speaking, Listening }

@Composable
fun ChatScreen(scenarioId: String, threadId: String?, startVoice: Boolean) {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val c = K.c
    val s0 = remember { app.store.value }
    val ui = s0.profile.ui
    val target = Lang.of(s0.profile.target)
    val scenario = Scenarios.byId(scenarioId)
    val id = rememberSaveable { threadId ?: UUID.randomUUID().toString() }
    val state by app.store.state.collectAsState()
    val thread = state.chats.firstOrNull { it.id == id } ?: ChatThread(id, scenarioId, target.code)
    val messages = thread.messages

    var input by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf(Phase.Idle) }
    var voice by rememberSaveable { mutableStateOf(startVoice) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var xpGiven by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val stt by app.stt.state.collectAsState()
    val level by app.stt.level.collectAsState()
    val speaking by app.tts.speaking.collectAsState()
    val h = rememberHaptics()

    fun save(msgs: List<ChatMsg>) = app.store.update { st ->
        val th = thread.copy(messages = msgs.takeLast(120), updatedAt = System.currentTimeMillis())
        st.copy(chats = (listOf(th) + st.chats.filter { it.id != id }).take(30))
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) { phase = Phase.Listening; app.stt.start(target.locale) } else error = t("err.mic") }
    fun listen() {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { mic.launch(Manifest.permission.RECORD_AUDIO); return }
        app.tts.stop(); phase = Phase.Listening; app.stt.start(target.locale)
    }

    fun speakReply(text: String) {
        if (voice) { phase = Phase.Speaking; app.tts.speak(text) } else phase = Phase.Idle
    }

    fun send(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || job?.isActive == true) return
        input = ""; error = null
        h.tap()
        val history = messages
        val withUser = history + ChatMsg(true, clean)
        save(withUser)
        app.store.update { st -> st.copy(stats = st.stats.copy(wordsWritten = st.stats.wordsWritten + clean.split(' ').size)) }
        if (xpGiven < 30) { Progress.addXp(app.store, 2); xpGiven += 2 }
        phase = Phase.Thinking
        job = scope.launch {
            if (!app.ensureModel()) { error = t("err.noModel"); phase = Phase.Idle; return@launch }
            pending = ""
            val r = runCatching {
                app.tutor.chat(app.learner(), scenario, history, clean, app.store.value.settings.doubleCheck) { pending = it }
            }.getOrNull()
            pending = null
            if (r == null) { error = t("err.generic"); phase = Phase.Idle; return@launch }
            val updated = withUser.dropLast(1) + ChatMsg(true, clean, correction = r.userCorrection) + r.reply
            save(updated)
            if (r.userCorrection != null) h.reject()
            speakReply(r.reply.text)
        }
    }

    // Opener for a new conversation.
    LaunchedEffect(id) {
        app.tts.setLanguage(target.locale); app.tts.rate = s0.settings.ttsRate
        if (messages.isEmpty()) {
            phase = Phase.Thinking
            job = launch {
                if (!app.ensureModel()) { error = t("err.noModel"); phase = Phase.Idle; return@launch }
                pending = ""
                val o = runCatching { app.tutor.opener(app.learner(), scenario) { pending = it } }.getOrNull()
                pending = null
                if (o == null) { error = t("err.generic"); phase = Phase.Idle; return@launch }
                save(listOf(o))
                speakReply(o.text)
            }
        }
    }
    // Voice loop: when the tutor stops speaking, start listening again.
    LaunchedEffect(speaking, voice) {
        if (voice && !speaking && phase == Phase.Speaking) { delay(250); listen() }
        if (!speaking && phase == Phase.Speaking && !voice) phase = Phase.Idle
    }
    LaunchedEffect(stt) {
        when (val st = stt) {
            is SttState.Result -> {
                val text = st.alternatives.first()
                app.store.update { it.copy(stats = it.stats.copy(minutesSpoken = it.stats.minutesSpoken + text.split(' ').size / 120.0)) }
                app.stt.reset()
                if (voice) send(text) else { input = text; phase = Phase.Idle }
            }
            is SttState.Unavailable -> { error = t("err.sttUnavailable"); phase = Phase.Idle; voice = false; app.stt.reset() }
            is SttState.Error -> { phase = Phase.Idle; app.stt.reset() }
            else -> {}
        }
    }
    LaunchedEffect(messages.size, pending != null) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size + (if (pending != null) 1 else 0)) }
    DisposableEffect(Unit) { onDispose { app.tts.stop(); app.stt.stop(); job?.cancel() } }

    Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
        TopBar(scenario.title.loc(ui), { nav.pop() }) {
            IconBtn(if (voice) Icons.Rounded.Headset else Icons.Rounded.HeadsetOff, t("chat.voice"), {
                voice = !voice
                if (!voice) { app.stt.stop(); app.tts.stop(); phase = Phase.Idle }
                else if (phase == Phase.Idle && job?.isActive != true) listen()
            }, tint = if (voice) c.accent else c.ink2)
            IconBtn(Icons.Rounded.Add, t("chat.new"), { nav.replace(Route.Chat(scenarioId)) })
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                KCard(Modifier.fillMaxWidth(), color = c.surface2, padding = PaddingValues(14.dp)) {
                    Text(t("chat.goal"), style = K.t.caption, color = c.ink3)
                    Text(scenario.goal.loc(ui), style = K.t.body, color = c.ink)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiBadge(t("ai.label"))
                        Spacer(Modifier.width(8.dp))
                        if (state.settings.doubleCheck) AiBadge(t("chat.doublecheck"), verified = true)
                    }
                }
            }
            itemsIndexed(messages, key = { i, _ -> i }) { i, m ->
                Bubble(m, last = i == messages.lastIndex, onSuggestion = { if (voice) send(it) else input = it })
            }
            if (pending != null) item { TypingBubble(pending.orEmpty()) }
        }
        AnimatedVisibility(error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(c.badSoft).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(error.orEmpty(), style = K.t.caption, color = c.bad, modifier = Modifier.weight(1f))
                if (error == t("err.noModel")) KButton(t("models.title"), { nav.push(Route.Models) }, style = BtnStyle.Ghost, height = 36.dp)
            }
        }
        if (voice) VoiceOrb(phase, level, onTap = {
            when (phase) {
                Phase.Listening -> app.stt.finish()
                Phase.Speaking -> { app.tts.stop(); listen() }
                Phase.Idle -> listen()
                Phase.Thinking -> {}
            }
        }, partial = (stt as? SttState.Partial)?.text)
        else InputBar(input, { input = it }, busy = job?.isActive == true, listening = phase == Phase.Listening, level = level, target = target,
            onSend = { send(input) }, onMic = { if (phase == Phase.Listening) app.stt.finish() else listen() }, onStop = { job?.cancel(); pending = null; phase = Phase.Idle })
    }
}

@Composable
private fun Bubble(m: ChatMsg, last: Boolean, onSuggestion: (String) -> Unit) {
    val c = K.c
    val t = LocalT.current
    val app = KelimoApp.instance
    var showTr by remember { mutableStateOf(false) }
    val shape = if (m.fromUser) RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp) else RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape).background(if (m.fromUser) c.accent else c.surface)
                .then(if (m.fromUser) Modifier else Modifier.border(1.dp, c.line, shape)).padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(m.text, style = K.t.body, color = if (m.fromUser) c.accentInk else c.ink)
            if (!m.fromUser) {
                AnimatedVisibility(showTr && m.translation != null) {
                    Text(m.translation.orEmpty(), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.AutoMirrored.Rounded.VolumeUp, "play", { app.tts.speak(m.text) }, tint = c.ink3, size = 34.dp)
                    IconBtn(Icons.Rounded.SlowMotionVideo, "slow", { app.tts.speak(m.text, slow = true) }, tint = c.ink3, size = 34.dp)
                    if (m.translation != null) IconBtn(Icons.Rounded.Translate, t("chat.translate"), { showTr = !showTr }, tint = if (showTr) c.accent else c.ink3, size = 34.dp)
                }
            }
        }
        m.correction?.let { CorrectionCard(m.text, it) }
        if (!m.fromUser && last && m.suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(t("chat.suggest"), style = K.t.caption, color = c.ink3)
            Spacer(Modifier.height(4.dp))
            m.suggestions.forEach { sug ->
                Box(
                    Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.accent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .pressable { onSuggestion(sug) }.padding(horizontal = 14.dp, vertical = 9.dp)
                ) { Text(sug, style = K.t.label, color = c.accent) }
            }
        }
    }
}

@Composable
fun CorrectionCard(original: String, corr: Correction) {
    val c = K.c
    val t = LocalT.current
    Column(
        Modifier.padding(top = 6.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(18.dp)).background(c.warmSoft).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Spellcheck, null, tint = c.warm, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("chat.correction"), style = K.t.label, color = c.warm, modifier = Modifier.weight(1f))
            if (corr.verified) Icon(Icons.Rounded.Verified, t("chat.verified"), tint = c.good, modifier = Modifier.size(16.dp))
            else Text(t("chat.unverified"), style = K.t.caption, color = c.ink3)
        }
        Spacer(Modifier.height(6.dp))
        Text(diffText(original, corr.corrected, c.bad, c.good), style = K.t.body, color = c.ink)
        if (corr.explanation.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(corr.explanation, style = K.t.caption, color = c.ink2)
        }
    }
}

@Composable
fun diffText(original: String, corrected: String, bad: Color, good: Color): AnnotatedString = buildAnnotatedString {
    Grader.diff(original, corrected).forEachIndexed { i, (w, op) ->
        if (i > 0) append(" ")
        when (op) {
            '-' -> withStyle(SpanStyle(color = bad, textDecoration = TextDecoration.LineThrough)) { append(w) }
            '+' -> withStyle(SpanStyle(color = good, fontWeight = FontWeight.Bold)) { append(w) }
            else -> append(w)
        }
    }
}

@Composable
private fun TypingBubble(partial: String) {
    val c = K.c
    Row(
        Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (partial.isNotBlank()) Text(partial, style = K.t.body, color = c.ink)
        else {
            val tr = rememberInfiniteTransition(label = "typing")
            repeat(3) { i ->
                val a by tr.animateFloat(0.25f, 1f, infiniteRepeatable(tween(520, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
                Box(Modifier.padding(horizontal = 3.dp).size(8.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(c.ink2))
            }
        }
    }
}

@Composable
private fun InputBar(value: String, onChange: (String) -> Unit, busy: Boolean, listening: Boolean, level: Float, target: Lang, onSend: () -> Unit, onMic: () -> Unit, onStop: () -> Unit) {
    val c = K.c
    val t = LocalT.current
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.weight(1f)) {
            KField(value, onChange, if (listening) t("chat.listening") else t("chat.placeholder").replace("%s", target.endonym),
                capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send, singleLine = false, autoCorrect = false, onDone = onSend)
        }
        Spacer(Modifier.width(8.dp))
        val micScale by animateFloatAsState(if (listening) 1f + level * 0.2f else 1f, Motion.press(), label = "mic")
        IconBtn(if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic, t("chat.mic"), onMic, tint = if (listening) Color.White else c.accent, bg = if (listening) c.bad else c.accentSoft, size = 52.dp, modifier = Modifier.graphicsLayer { scaleX = micScale; scaleY = micScale })
        Spacer(Modifier.width(6.dp))
        AnimatedContent(busy, label = "send") { b ->
            if (b) IconBtn(Icons.Rounded.StopCircle, t("chat.stop"), onStop, tint = c.ink, bg = c.surface2, size = 52.dp)
            else IconBtn(Icons.AutoMirrored.Rounded.Send, t("chat.send"), onSend, tint = c.accentInk, bg = if (value.isBlank()) c.ink3 else c.accent, size = 52.dp)
        }
    }
}

@Composable
private fun VoiceOrb(phase: Phase, level: Float, onTap: () -> Unit, partial: String?) {
    val c = K.c
    val t = LocalT.current
    val tr = rememberInfiniteTransition(label = "orb")
    val breathe by tr.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    val scale = when (phase) { Phase.Listening -> 1f + level * 0.35f; Phase.Speaking, Phase.Thinking -> breathe; Phase.Idle -> 1f }
    val s by animateFloatAsState(scale, Motion.press(), label = "orbS")
    val color by animateColorAsState(when (phase) { Phase.Listening -> c.bad; Phase.Speaking -> c.good; Phase.Thinking -> c.warm; Phase.Idle -> c.accent }, Motion.quick(), label = "orbC")
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 18.dp, top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (partial != null) Text(partial, style = K.t.body, color = c.ink2, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(110.dp).graphicsLayer { scaleX = s; scaleY = s }.clip(CircleShape).background(color.copy(alpha = 0.18f)))
            Box(Modifier.size(78.dp).clip(CircleShape).background(color).pressable(scaleTo = 0.9f, onClick = onTap), contentAlignment = Alignment.Center) {
                Icon(when (phase) { Phase.Listening -> Icons.Rounded.Mic; Phase.Speaking -> Icons.Rounded.GraphicEq; Phase.Thinking -> Icons.Rounded.MoreHoriz; Phase.Idle -> Icons.Rounded.Mic }, null, tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(t(when (phase) { Phase.Listening -> "voice.listening"; Phase.Speaking -> "voice.speaking"; Phase.Thinking -> "voice.thinking"; Phase.Idle -> "voice.tap" }), style = K.t.label, color = c.ink2)
    }
}
