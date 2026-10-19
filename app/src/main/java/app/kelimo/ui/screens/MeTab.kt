package app.kelimo.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.ai.EngineState
import app.kelimo.core.Lang
import app.kelimo.data.Skill
import app.kelimo.learn.Adaptive
import app.kelimo.ui.BottomBarSpace
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import java.time.LocalDate
import java.time.format.TextStyle

@Composable
fun MeTab() {
    val app = KelimoApp.instance
    val s by app.store.state.collectAsStateWithLifecycle()
    val engine by app.llm.state.collectAsStateWithLifecycle()
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val native = Lang.of(s.profile.native)
    val target = Lang.of(s.profile.target)
    val learned = s.course.srs.count { it.key.startsWith("w:") && it.value.reps > 0 }

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                Text(s.profile.name.firstOrNull()?.uppercase() ?: "K", style = K.t.title, color = c.accentInk)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(s.profile.name.ifBlank { t("me.learner") }, style = K.t.title, color = c.ink)
                Text("${native.flag} ${native.endonym}  →  ${target.flag} ${target.endonym}", style = K.t.caption, color = c.ink2)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("${s.stats.streak()}", t("me.streak"), c.warm, Modifier.weight(1f))
            StatBox("${s.stats.totalXp}", "XP", c.accent, Modifier.weight(1f))
            StatBox(s.course.cefr, t("me.level"), c.good, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox("$learned", t("me.words"), c.ink, Modifier.weight(1f))
            StatBox("${s.stats.lessonsDone}", t("me.lessons"), c.ink, Modifier.weight(1f))
            StatBox("%.0f".format(s.stats.minutesSpoken), t("me.minutes"), c.ink, Modifier.weight(1f))
        }

        SectionTitle(t("me.week"))
        KCard(Modifier.fillMaxWidth()) { WeekChart(s.stats.xpByDay, s.profile.dailyGoalXp, s.profile.ui) }

        SectionTitle(t("me.skills"))
        KCard(Modifier.fillMaxWidth()) {
            Skill.entries.forEach { sk ->
                val r = s.course.skills[sk] ?: 900.0
                val lvl = Adaptive.cefrFor(r)
                val within = ((r - (Adaptive.levelBase(lvl) - 100)) / 200).toFloat().coerceIn(0f, 1f)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t("skill.${sk.name.lowercase()}"), style = K.t.body, color = c.ink, modifier = Modifier.width(110.dp))
                    ProgressBar(within, Modifier.weight(1f), height = 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(lvl, style = K.t.label, color = c.ink2)
                }
            }
            Text(t("me.skills.note"), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
        }

        SectionTitle(t("me.app"))
        KCard(Modifier.fillMaxWidth(), padding = PaddingValues(vertical = 4.dp)) {
            LinkRow(Icons.Rounded.Memory, t("models.title"), when (engine) {
                is EngineState.NoModel -> t("model.none")
                else -> s.activeModel?.name.orEmpty()
            }) { nav.push(Route.Models) }
            LinkRow(Icons.Rounded.Tune, t("settings.title"), null) { nav.push(Route.Settings) }
            LinkRow(Icons.Rounded.Shield, t("privacy.title"), t("privacy.offline")) { nav.push(Route.Privacy) }
            if (app.courses.course(s.profile.target)?.placement?.isNotEmpty() == true) LinkRow(Icons.Rounded.Explore, t("me.placement"), null) { nav.push(Route.Placement) }
        }
        Spacer(Modifier.height(BottomBarSpace))
    }
}

@Composable
fun LinkRow(icon: ImageVector, title: String, value: String?, onClick: () -> Unit) {
    val h = rememberHaptics()
    Row(Modifier.fillMaxWidth().pressable(scaleTo = 0.99f) { h.tap(); onClick() }.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = K.c.ink2, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = K.t.body, color = K.c.ink, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = K.t.caption, color = K.c.ink3)
        Icon(Icons.Rounded.ChevronRight, null, tint = K.c.ink3)
    }
}

@Composable
private fun WeekChart(xpByDay: Map<String, Int>, goal: Int, ui: String) {
    val c = K.c
    val today = LocalDate.now()
    val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val values = days.map { xpByDay[it.toString()] ?: 0 }
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(goal).coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, d ->
            val v = values[i]
            val f by animateFloatAsState(v / max.toFloat(), Motion.gentle(), label = "bar$i")
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (v > 0) "$v" else "", style = K.t.caption, color = c.ink3)
                Box(Modifier.padding(vertical = 4.dp).width(22.dp).height((96 * f).dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(8.dp))
                    .background(if (v >= goal) c.good else if (v > 0) c.accent else c.surface2))
                Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, java.util.Locale.forLanguageTag(ui)), style = K.t.caption, color = if (d == today) c.ink else c.ink3)
            }
        }
    }
}
