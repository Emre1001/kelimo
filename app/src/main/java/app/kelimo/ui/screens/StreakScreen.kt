package app.kelimo.ui.screens

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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.data.Stats
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

val milestones = listOf(3, 7, 14, 30, 50, 100, 200, 365)

@Composable
fun StreakScreen() {
    val app = KelimoApp.instance
    val s by app.store.state.collectAsStateWithLifecycle()
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val streak = s.stats.streak()
    val today = LocalDate.now()
    val doneToday = s.stats.xpOn(today) > 0
    Screen {
        TopBar(null, { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Flame(streak, lit = doneToday, size = 132)
            Text("$streak", style = K.t.display.copy(fontSize = 64.sp, lineHeight = 68.sp), color = c.ink)
            Text(t(if (streak == 1) "streak.day" else "streak.days"), style = K.t.headline, color = c.ink2)
            Spacer(Modifier.height(8.dp))
            Text(t(if (doneToday) "streak.safe" else if (streak > 0) "streak.atRisk" else "streak.start"), style = K.t.body, color = if (doneToday) c.good else c.warm, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("${s.stats.longestStreak()}", t("streak.longest"), c.warm, Modifier.weight(1f))
                StatBox("${s.stats.freezes}", t("streak.freezes"), c.accent, Modifier.weight(1f))
                StatBox("${s.stats.xpByDay.count { it.value > 0 }}", t("streak.daysTotal"), c.good, Modifier.weight(1f))
            }
            SectionTitle(t("streak.calendar"), Modifier.fillMaxWidth())
            KCard(Modifier.fillMaxWidth()) { Calendar(s.stats, s.profile.ui) }
            SectionTitle(t("streak.milestones"), Modifier.fillMaxWidth())
            KCard(Modifier.fillMaxWidth()) {
                val best = s.stats.longestStreak()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    milestones.forEach { m ->
                        val got = best >= m
                        Column(
                            Modifier.width(70.dp).clip(RoundedCornerShape(16.dp)).background(if (got) c.warmSoft else c.surface2).padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (got) c.warm else c.ink3.copy(alpha = 0.5f), modifier = Modifier.size(26.dp))
                            Text("$m", style = K.t.bodyStrong, color = if (got) c.ink else c.ink3)
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(t("streak.freezeInfo"), style = K.t.caption, color = c.ink3, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** Flame with a soft glow; grey when today's lesson is still open. */
@Composable
fun Flame(streak: Int, lit: Boolean, size: Int, modifier: Modifier = Modifier) {
    val c = K.c
    val glow by animateFloatAsState(if (lit) 1f else 0f, Motion.gentle(), label = "glow")
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * 0.86f).dp).graphicsLayer { alpha = 0.25f + 0.75f * glow }.clip(CircleShape).background(if (lit) c.warmSoft else c.surface2))
        Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (lit || streak == 0) c.warm.copy(alpha = if (lit) 1f else 0.45f) else c.ink3, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/** Last 5 weeks, Monday first. Orange = learned, blue = freeze used. */
@Composable
private fun Calendar(stats: Stats, ui: String) {
    val c = K.c
    val today = LocalDate.now()
    val start = today.minusDays(today.dayOfWeek.value - 1L).minusWeeks(4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEach { d ->
            Text(d.getDisplayName(TextStyle.NARROW, Locale.forLanguageTag(ui)), style = K.t.caption, color = c.ink3, modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
        }
    }
    Spacer(Modifier.height(6.dp))
    for (w in 0 until 5) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            for (d in 0 until 7) {
                val day = start.plusDays(w * 7L + d)
                val xp = stats.xpOn(day)
                val frozen = day.toString() in stats.frozenDays
                val future = day.isAfter(today)
                Box(
                    Modifier.size(36.dp).clip(CircleShape)
                        .background(when { xp > 0 -> c.warm; frozen -> c.accentSoft; else -> Color.Transparent })
                        .then(if (day == today) Modifier.border(2.dp, c.warm, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${day.dayOfMonth}", style = K.t.caption,
                        color = when { xp > 0 -> Color.White; frozen -> c.accent; future -> c.ink3.copy(alpha = 0.4f); else -> c.ink2 },
                    )
                }
            }
        }
    }
}

/** Shown after the first lesson of the day: the streak counter ticks up. */
@Composable
fun StreakCelebration(streak: Int, stats: Stats, onDone: () -> Unit) {
    val t = LocalT.current
    val c = K.c
    val h = rememberHaptics()
    val shown = remember { Animatable((streak - 1).coerceAtLeast(0).toFloat()) }
    var lit by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(350); lit = true; h.confirm()
        shown.animateTo(streak.toFloat(), tween(700))
    }
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().background(c.bg).systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Flame(streak, lit, 168)
        Text("${shown.value.toInt()}", style = K.t.display.copy(fontSize = 84.sp, lineHeight = 88.sp), color = c.warm)
        Text(t("streak.extended"), style = K.t.title, color = c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(milestones.firstOrNull { it > streak }?.let { t("streak.next").replace("%d", "${it - streak}").replace("%m", "$it") } ?: "", style = K.t.body, color = c.ink2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        // This week, Monday to Sunday.
        val monday = today.minusDays(today.dayOfWeek.value - 1L)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 7) {
                val d = monday.plusDays(i.toLong())
                val on = stats.active(d) || (d == today && lit)
                val sc by animateFloatAsState(if (on) 1f else 0.82f, Motion.press(), label = "wd$i")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = K.t.caption, color = c.ink3)
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.size(34.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape).background(if (on) c.warm else c.surface2), contentAlignment = Alignment.Center) {
                        if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        KButton(t("lesson.continue"), onDone, Modifier.fillMaxWidth())
    }
}
