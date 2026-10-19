package app.kelimo.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.core.Lang
import app.kelimo.learn.Reminders
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion

private const val STEPS = 9

@Composable
fun SetupFlow() {
    val app = KelimoApp.instance
    val store = app.store
    val s by store.state.collectAsState()
    val t = LocalT.current
    val nav = LocalNav.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var wantsPlacement by rememberSaveable { mutableStateOf(false) }
    fun go(n: Int) { forward = n > step; step = n }

    BackHandler(step > 0) { go(step - 1) }

    fun finish() {
        store.update { it.copy(onboarded = true) }
        if (wantsPlacement && app.courses.course(s.profile.target)?.placement?.isNotEmpty() == true) nav.push(Route.Placement)
        app.warmUpModel()
    }

    Column(Modifier.fillMaxSize().background(K.c.bg).systemBarsPadding().imePadding()) {
        if (step > 0) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.ArrowBackIosNew, "back", { go(step - 1) })
            ProgressBar(step / (STEPS - 1f), Modifier.weight(1f).padding(horizontal = 8.dp))
            Spacer(Modifier.width(44.dp))
        }
        AnimatedContent(
            step,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (slideInHorizontally(Motion.offset) { dir * it / 4 } + fadeIn(Motion.quick())) togetherWith
                    (slideOutHorizontally(Motion.offset) { -dir * it / 6 } + fadeOut(Motion.quick()))
            },
            modifier = Modifier.weight(1f), label = "setup",
        ) { st ->
            when (st) {
                0 -> Welcome { go(1) }
                1 -> LangStep(t("setup.native.title"), t("setup.native.body"), Lang.entries.toList(), s.profile.native) { l ->
                    store.update { it.copy(profile = it.profile.copy(native = l.code, ui = if (l in Lang.uiLanguages) l.code else "en", target = if (it.profile.target == l.code) (if (l == Lang.DE) "en" else "de") else it.profile.target)) }
                    go(2)
                }
                2 -> LangStep(t("setup.target.title"), t("setup.target.body"), Lang.entries.filter { it.code != s.profile.native }, s.profile.target, showCourse = true) { l ->
                    store.update { it.copy(profile = it.profile.copy(target = l.code)) }
                    go(3)
                }
                3 -> NameStep(s.profile.name) { n -> store.update { it.copy(profile = it.profile.copy(name = n.trim().take(30))) }; go(4) }
                4 -> ChoiceStep(t("setup.reason.title"), listOf("life", "work", "family", "travel", "exam", "fun").map { it to t("reason.$it") }, listOf(s.profile.reason)) { r ->
                    store.update { it.copy(profile = it.profile.copy(reason = r)) }; go(5)
                }
                5 -> InterestsStep(s.profile.interests) { list -> store.update { it.copy(profile = it.profile.copy(interests = list)) }; go(6) }
                6 -> ChoiceStep(t("setup.goal.title"), listOf(10, 30, 50, 80).map { "$it" to t("goal.$it") }, listOf("${s.profile.dailyGoalXp}")) { g ->
                    store.update { it.copy(profile = it.profile.copy(dailyGoalXp = g.toInt())) }; go(7)
                }
                7 -> LevelStep(hasPlacement = app.courses.course(s.profile.target)?.placement?.isNotEmpty() == true) { placement -> wantsPlacement = placement; go(8) }
                else -> FinalStep(onDone = ::finish)
            }
        }
    }
}

@Composable
private fun StepFrame(title: String, body: String?, bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Spacer(Modifier.height(12.dp))
            Text(title, style = K.t.title, color = K.c.ink)
            if (body != null) { Spacer(Modifier.height(6.dp)); Text(body, style = K.t.body, color = K.c.ink2) }
            Spacer(Modifier.height(20.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
        if (bottom != null) Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) { bottom() }
    }
}

@Composable
private fun Welcome(onNext: () -> Unit) {
    val t = LocalT.current
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(K.c.accent), contentAlignment = Alignment.Center) {
            Text("K", style = K.t.display, color = K.c.accentInk)
        }
        Spacer(Modifier.height(28.dp))
        Text("Kelimo", style = K.t.display, color = K.c.ink)
        Spacer(Modifier.height(10.dp))
        Text(t("setup.welcome.body"), style = K.t.body, color = K.c.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        listOf(
            Icons.Rounded.Lock to "setup.welcome.p1",
            Icons.Rounded.AirplanemodeActive to "setup.welcome.p2",
            Icons.Rounded.Verified to "setup.welcome.p3",
        ).forEach { (ic, k) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(K.c.accentSoft), contentAlignment = Alignment.Center) { Icon(ic, null, tint = K.c.accent, modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.width(12.dp))
                Text(t(k), style = K.t.body, color = K.c.ink)
            }
        }
        Spacer(Modifier.weight(1f))
        KButton(t("setup.start"), onNext, Modifier.fillMaxWidth())
    }
}

@Composable
private fun LangStep(title: String, body: String, langs: List<Lang>, selected: String, showCourse: Boolean = false, onPick: (Lang) -> Unit) {
    val t = LocalT.current
    StepFrame(title, body) {
        langs.forEach { l ->
            SelectRow(
                l.endonym, l.code == selected, { onPick(l) }, Modifier.padding(vertical = 5.dp), leading = l.flag,
                subtitle = if (showCourse) (if (l.curated) t("setup.target.curated") else t("setup.target.ai")) else null,
            )
        }
    }
}

@Composable
private fun NameStep(initial: String, onNext: (String) -> Unit) {
    val t = LocalT.current
    var name by rememberSaveable { mutableStateOf(initial) }
    StepFrame(t("setup.name.title"), t("setup.name.body"), bottom = { KButton(if (name.isBlank()) t("common.skip") else t("common.next"), { onNext(name) }, Modifier.fillMaxWidth()) }) {
        KField(name, { name = it }, t("setup.name.hint"), capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done, onDone = { onNext(name) })
    }
}

@Composable
private fun ChoiceStep(title: String, options: List<Pair<String, String>>, selected: List<String>, onPick: (String) -> Unit) {
    StepFrame(title, null) {
        options.forEach { (k, label) -> SelectRow(label, k in selected, { onPick(k) }, Modifier.padding(vertical = 5.dp)) }
    }
}

val interestKeys = listOf("football", "cooking", "music", "tech", "travel", "family", "movies", "nature", "business", "health", "games", "books", "cars", "fashion")

@Composable
private fun InterestsStep(initial: List<String>, onNext: (List<String>) -> Unit) {
    val t = LocalT.current
    var sel by remember { mutableStateOf(initial.toSet()) }
    val h = rememberHaptics()
    StepFrame(t("setup.interests.title"), t("setup.interests.body"), bottom = { KButton(t("common.next"), { onNext(sel.toList()) }, Modifier.fillMaxWidth()) }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            interestKeys.forEach { k ->
                val on = k in sel
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) K.c.accent else K.c.surface)
                        .border(1.dp, if (on) K.c.accent else K.c.line, RoundedCornerShape(50))
                        .pressable(scaleTo = 0.94f) { h.tick(); sel = if (on) sel - k else sel + k }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) { Text(t("interest.$k"), style = K.t.label, color = if (on) K.c.accentInk else K.c.ink) }
            }
        }
    }
}

@Composable
private fun LevelStep(hasPlacement: Boolean, onPick: (Boolean) -> Unit) {
    val t = LocalT.current
    StepFrame(t("setup.level.title"), t("setup.level.body")) {
        SelectRow(t("setup.level.new"), false, { onPick(false) }, Modifier.padding(vertical = 5.dp), leading = "🌱", subtitle = t("setup.level.new.sub"))
        if (hasPlacement) SelectRow(t("setup.level.test"), false, { onPick(true) }, Modifier.padding(vertical = 5.dp), leading = "🧭", subtitle = t("setup.level.test.sub"))
    }
}

@Composable
private fun FinalStep(onDone: () -> Unit) {
    val t = LocalT.current
    val app = KelimoApp.instance
    val s by app.store.state.collectAsState()
    var hour by remember { mutableIntStateOf(if (s.settings.reminderHour >= 0) s.settings.reminderHour else 19) }
    var remind by remember { mutableStateOf(true) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    StepFrame(t("setup.model.title"), t("setup.model.body"), bottom = {
        KButton(t("setup.done"), {
            mic.launch(Manifest.permission.RECORD_AUDIO)
            if (remind) {
                if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                app.store.update { it.copy(settings = it.settings.copy(reminderHour = hour)) }
                Reminders.schedule(app, hour)
            }
            onDone()
        }, Modifier.fillMaxWidth())
    }) {
        ModelsContent(compact = true)
        SectionTitle(t("settings.reminder"))
        KCard {
            ToggleRow(t("setup.remind"), t("setup.remind.sub").replace("%s", "%02d:00".format(hour)), remind) { remind = it }
            if (remind) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(8, 12, 18, 19, 21).forEach { hh ->
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp)).background(if (hh == hour) K.c.accent else K.c.surface2)
                            .pressable { hour = hh }.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Text("%02d".format(hh), style = K.t.label, color = if (hh == hour) K.c.accentInk else K.c.ink) }
                }
            }
        }
    }
}

/** Shared text field with the app's look. */
@Composable
fun KField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    imeAction: ImeAction = ImeAction.Done,
    singleLine: Boolean = true,
    minLines: Int = 1,
    autoCorrect: Boolean = false,
    enabled: Boolean = true,
    onDone: () -> Unit = {},
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        textStyle = K.t.body.copy(color = K.c.ink),
        singleLine = singleLine, minLines = minLines,
        cursorBrush = androidx.compose.ui.graphics.SolidColor(K.c.accent),
        keyboardOptions = KeyboardOptions(capitalization = capitalization, autoCorrectEnabled = autoCorrect, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }, onSend = { onDone() }, onGo = { onDone() }),
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(K.c.surface)
                    .border(1.5.dp, K.c.line, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 15.dp)
            ) {
                if (value.isEmpty()) Text(hint, style = K.t.body, color = K.c.ink3)
                inner()
            }
        },
    )
}
