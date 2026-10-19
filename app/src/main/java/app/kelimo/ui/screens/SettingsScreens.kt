package app.kelimo.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.core.Lang
import app.kelimo.data.Backend
import app.kelimo.data.Settings
import app.kelimo.data.ThemeMode
import app.kelimo.course.Course
import app.kelimo.learn.Adaptive
import app.kelimo.learn.Placement
import app.kelimo.learn.Progress
import app.kelimo.learn.Reminders
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val ctx = LocalContext.current
    val s by app.store.state.collectAsStateWithLifecycle()
    val st = s.settings
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    fun set(f: (Settings) -> Settings) = app.store.update { it.copy(settings = f(it.settings)) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching { withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(app.store.exportJson().toByteArray()) } }; t("settings.exported") }.getOrElse { t("err.generic") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = runCatching {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
                app.store.importJson(text); t("settings.imported")
            }.getOrElse { t("settings.importFailed") }
        }
    }

    Screen {
        TopBar(t("settings.title"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            SectionTitle(t("settings.languages"))
            KCard(Modifier.fillMaxWidth()) {
                Text(t("settings.ui"), style = K.t.caption, color = c.ink3)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.uiLanguages.forEach { l -> Chip("${l.flag} ${l.endonym}", s.profile.ui == l.code) { app.store.update { it.copy(profile = it.profile.copy(ui = l.code)) } } }
                }
                Text(t("settings.target"), style = K.t.caption, color = c.ink3)
                FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Lang.entries.filter { it.code != s.profile.native }.forEach { l ->
                        Chip("${l.flag} ${l.endonym}", s.profile.target == l.code) { app.store.update { it.copy(profile = it.profile.copy(target = l.code)) } }
                    }
                }
                Text(t("settings.target.note"), style = K.t.caption, color = c.ink3)
            }

            SectionTitle(t("settings.learning"))
            KCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                Text(t("settings.goal"), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 12.dp))
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(10, 30, 50, 80).forEach { g -> Chip("$g XP", s.profile.dailyGoalXp == g) { app.store.update { it.copy(profile = it.profile.copy(dailyGoalXp = g)) } } }
                }
                ToggleRow(t("settings.listening"), null, st.listeningExercises) { v -> set { it.copy(listeningExercises = v) } }
                ToggleRow(t("settings.speaking"), null, st.speakingExercises) { v -> set { it.copy(speakingExercises = v) } }
                ToggleRow(t("settings.autoplay"), null, st.autoplayAudio) { v -> set { it.copy(autoplayAudio = v) } }
                Text(t("settings.ttsRate").replace("%s", "%.2f".format(st.ttsRate)), style = K.t.body, color = c.ink, modifier = Modifier.padding(top = 8.dp))
                Slider(st.ttsRate, { v -> set { it.copy(ttsRate = v) } }, valueRange = 0.6f..1.3f, onValueChangeFinished = { app.tts.rate = app.store.value.settings.ttsRate; app.tts.speak("Hallo, wie geht es dir?") },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.surface2))
            }

            SectionTitle(t("settings.ai"))
            KCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                ToggleRow(t("settings.doubleCheck"), t("settings.doubleCheck.sub"), st.doubleCheck) { v -> set { it.copy(doubleCheck = v) } }
                ToggleRow(t("settings.gpu"), t("settings.gpu.sub"), st.backend == Backend.GPU) { v ->
                    set { it.copy(backend = if (v) Backend.GPU else Backend.CPU) }
                    scope.launch { app.llm.unload(); app.warmUpModel() }
                }
            }

            SectionTitle(t("settings.look"))
            KCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { m -> Chip(t("theme.${m.name.lowercase()}"), st.theme == m) { set { it.copy(theme = m) } } }
                }
                ToggleRow(t("settings.haptics"), null, st.haptics) { v -> set { it.copy(haptics = v) } }
                ToggleRow(t("settings.reduceMotion"), null, st.reduceMotion) { v -> set { it.copy(reduceMotion = v) } }
            }

            SectionTitle(t("settings.reminder"))
            val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            KCard(Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(t("settings.reminder.off"), st.reminderHour < 0) { set { it.copy(reminderHour = -1) }; Reminders.schedule(app, -1) }
                    listOf(8, 12, 18, 19, 21).forEach { hh ->
                        Chip("%02d:00".format(hh), st.reminderHour == hh) {
                            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            set { it.copy(reminderHour = hh) }; Reminders.schedule(app, hh)
                        }
                    }
                }
            }

            SectionTitle(t("settings.data"))
            KCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
                LinkRow(Icons.Rounded.Upload, t("settings.export"), null) { export.launch("kelimo-backup.json") }
                LinkRow(Icons.Rounded.Download, t("settings.import"), null) { import.launch(arrayOf("application/json", "*/*")) }
                LinkRow(Icons.Rounded.RestartAlt, t("settings.reset"), null) { confirmReset = true }
            }
            msg?.let { Text(it, style = K.t.caption, color = c.ink2, modifier = Modifier.padding(8.dp)) }
            if (confirmReset) KCard(Modifier.fillMaxWidth().padding(top = 10.dp), color = c.badSoft) {
                Text(t("settings.reset.confirm"), style = K.t.bodyStrong, color = c.ink)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KButton(t("settings.reset"), { confirmReset = false; app.store.reset(); nav.popToMain() }, style = BtnStyle.Bad, height = 40.dp)
                    KButton(t("common.cancel"), { confirmReset = false }, style = BtnStyle.Secondary, height = 40.dp)
                }
            }
            Text("Kelimo 1.0.0", style = K.t.caption, color = c.ink3, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = K.c
    val h = rememberHaptics()
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) c.accent else c.surface2).pressable(scaleTo = 0.94f) { h.tick(); onClick() }.padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(text, style = K.t.label, color = if (selected) c.accentInk else c.ink) }
}

// ============================================================== privacy proof

@Composable
fun PrivacyScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val ctx = LocalContext.current
    val target = Lang.of(app.store.value.profile.target)
    // Checked at runtime against what the OS actually granted, not what we claim.
    val perms = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toList().orEmpty() }.getOrDefault(emptyList())
    }
    val noInternet = Manifest.permission.INTERNET !in perms
    val sttLocal = app.stt.available
    val ttsLocal = remember { app.tts.hasOfflineVoice(target.locale) }
    val noBackup = (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP) == 0

    Screen {
        TopBar(t("privacy.title"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(88.dp).clip(CircleShape).background(if (noInternet) c.goodSoft else c.badSoft), contentAlignment = Alignment.Center) {
                    Icon(if (noInternet) Icons.Rounded.WifiOff else Icons.Rounded.Wifi, null, tint = if (noInternet) c.good else c.bad, modifier = Modifier.size(42.dp))
                }
            }
            Text(t(if (noInternet) "privacy.headline" else "privacy.headline.bad"), style = K.t.title, color = c.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(t("privacy.body"), style = K.t.body, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            SectionTitle(t("privacy.checks"))
            KCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Check(t("privacy.c.internet"), noInternet)
                Check(t("privacy.c.model"), true)
                Check(t(if (sttLocal) "privacy.c.stt" else "privacy.c.stt.no"), sttLocal)
                Check(t(if (ttsLocal) "privacy.c.tts" else "privacy.c.tts.no"), ttsLocal)
                Check(t("privacy.c.backup"), noBackup)
                Check(t("privacy.c.tracking"), true)
            }
            Text(t("privacy.how"), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
            if (!sttLocal || !ttsLocal) Text(t("privacy.voiceHint"), style = K.t.caption, color = c.warm, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Check(text: String, ok: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, tint = if (ok) K.c.good else K.c.warm, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = K.t.body, color = K.c.ink)
    }
}

// ============================================================== placement

@Composable
fun PlacementScreen() {
    val app = KelimoApp.instance
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val s = app.store.value
    val course = remember { app.courses.course(s.profile.target) }
    val placement = remember { course?.let { Placement(it.placement) } }
    var tick by remember { mutableIntStateOf(0) }
    var picked by remember { mutableIntStateOf(-1) }
    var result by remember { mutableStateOf<String?>(null) }
    val h = rememberHaptics()

    Screen {
        TopBar(t("placement.title"), { nav.pop() })
        if (placement == null || course == null) { EmptyState(Icons.Rounded.Explore, t("placement.title"), t("placement.none"), Modifier.weight(1f)); return@Screen }
        val r = result
        if (r != null) {
            Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(t("placement.result"), style = K.t.body, color = c.ink2)
                Text(r, style = K.t.display.copy(fontSize = K.t.display.fontSize * 2), color = c.accent)
                Text(t("placement.result.body"), style = K.t.body, color = c.ink2, textAlign = TextAlign.Center)
            }
            Box(Modifier.padding(20.dp)) { KButton(t("lesson.continue"), { nav.pop() }, Modifier.fillMaxWidth()) }
            return@Screen
        }
        val q = placement.current
        key(tick) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                ProgressBar(placement.answered / placement.total.toFloat(), Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
                if (q != null) {
                    Text(q.q, style = K.t.title, color = c.ink)
                    q.hint[s.profile.ui]?.let { Text(it, style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(20.dp))
                    q.options.forEachIndexed { i, o ->
                        SelectRow(o, picked == i, { picked = i }, Modifier.padding(vertical = 5.dp))
                    }
                }
            }
        }
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            fun answer(choice: Int) {
                placement.answer(choice); h.tick(); picked = -1; tick++
                if (placement.done) result = finish(placement, course)
            }
            KButton(t("placement.dontKnow"), { answer(-1) }, Modifier.weight(1f), style = BtnStyle.Secondary)
            KButton(t("common.next"), { answer(picked) }, Modifier.weight(1f), enabled = picked >= 0)
        }
    }
}

private fun finish(p: Placement, course: Course): String {
    val app = KelimoApp.instance
    val level = p.result()
    val idx = Adaptive.levels.indexOf(level)
    val below = course.units.filter { Adaptive.levels.indexOf(it.cefr) < idx }.flatMap { u -> u.lessons.map { it.id } }
    Progress.applyPlacement(app.store, level, below)
    return level
}
