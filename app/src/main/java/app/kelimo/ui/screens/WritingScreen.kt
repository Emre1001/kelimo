package app.kelimo.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.ai.Tutor
import app.kelimo.learn.Progress
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import kotlinx.coroutines.launch

/** Writing prompts per level, in the learner's UI language. */
private val prompts = mapOf(
    "A1" to listOf("write.p.a1.1", "write.p.a1.2", "write.p.a1.3"),
    "A2" to listOf("write.p.a2.1", "write.p.a2.2", "write.p.a2.3"),
    "B1" to listOf("write.p.b1.1", "write.p.b1.2", "write.p.b1.3"),
)

@Composable
fun WritingScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s = app.store.value
    val list = prompts[s.course.cefr] ?: prompts.getValue("B1")
    var promptIdx by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var streamed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Tutor.WritingResult?>(null) }
    var checkedText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val task = t(list[promptIdx % list.size])

    Screen {
        TopBar(t("practice.writing"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
            KCard(Modifier.fillMaxWidth(), color = c.surface2) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t("write.task"), style = K.t.caption, color = c.ink3, modifier = Modifier.weight(1f))
                    IconBtn(Icons.Rounded.Shuffle, t("write.other"), { promptIdx++ }, tint = c.ink2, size = 36.dp)
                }
                Text(task, style = K.t.bodyStrong, color = c.ink)
            }
            Spacer(Modifier.height(12.dp))
            KField(text, { text = it; result = null }, t("write.hint"), singleLine = false, minLines = 6, capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default)
            Text("${text.split(Regex("\\s+")).count { it.isNotBlank() }} ${t("write.words")}", style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            Spacer(Modifier.height(12.dp))
            KButton(t("write.check"), {
                error = null; busy = true; streamed = ""
                val snapshot = text
                scope.launch {
                    if (!app.ensureModel()) { error = t("err.noModel"); busy = false; return@launch }
                    val r = runCatching { app.tutor.checkWriting(app.learner(), snapshot, task, app.store.value.settings.doubleCheck) { streamed = it } }.getOrNull()
                    busy = false
                    if (r == null) error = t("err.generic") else {
                        result = r; checkedText = snapshot
                        Progress.addXp(app.store, 5)
                        app.store.update { it.copy(stats = it.stats.copy(wordsWritten = it.stats.wordsWritten + snapshot.split(' ').size)) }
                    }
                }
            }, Modifier.fillMaxWidth(), enabled = text.trim().length >= 10, loading = busy, icon = Icons.Rounded.Spellcheck)

            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = K.t.caption, color = c.bad)
                if (it == t("err.noModel")) KButton(t("models.title"), { nav.push(Route.Models) }, style = BtnStyle.Ghost)
            }
            if (busy && streamed.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(streamed, style = K.t.body, color = c.ink3)
            }
            AnimatedContent(result, label = "res") { r ->
                if (r != null) Column {
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiBadge(t("ai.label"))
                        Spacer(Modifier.width(8.dp))
                        if (r.level != null) Pill(t("write.level").replace("%s", r.level))
                    }
                    SectionTitle(t("write.corrected"))
                    KCard(Modifier.fillMaxWidth()) {
                        Text(diffText(checkedText, r.corrected, c.bad, c.good), style = K.t.body, color = c.ink)
                        if (r.heavyRewrite) { Spacer(Modifier.height(8.dp)); Text(t("write.heavy"), style = K.t.caption, color = c.warm) }
                    }
                    if (r.issues.isNotEmpty()) {
                        SectionTitle(t("write.issues").replace("%d", "${r.issues.size}"))
                        r.issues.forEach { i ->
                            KCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), padding = PaddingValues(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(i.wrong, style = K.t.bodyStrong, color = c.bad)
                                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = c.ink3, modifier = Modifier.padding(horizontal = 8.dp).size(16.dp))
                                    Text(i.right, style = K.t.bodyStrong, color = c.good)
                                }
                                if (i.why.isNotBlank()) Text(i.why, style = K.t.caption, color = c.ink2, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.goodSoft).padding(12.dp)) { Text(t("write.noIssues"), style = K.t.body, color = c.good) }
                    }
                    if (r.feedback.isNotBlank()) {
                        SectionTitle(t("write.feedback"))
                        Text(r.feedback, style = K.t.body, color = c.ink)
                    }
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
