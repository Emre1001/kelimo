package app.kelimo.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.core.Lang
import app.kelimo.course.Course
import app.kelimo.course.titleFor
import app.kelimo.learn.Srs
import app.kelimo.ui.BottomBarSpace
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import java.time.LocalDate

@Composable
fun LearnTab() {
    val app = KelimoApp.instance
    val s by app.store.state.collectAsStateWithLifecycle()
    val t = LocalT.current
    val nav = LocalNav.current
    val target = Lang.of(s.profile.target)
    val course: Course = remember(s.profile.target, s.course.aiLessons) { app.currentCourse() }
    val ui = s.profile.ui
    val due = remember(s.course.srs) { Srs.due(s.course).size }
    val lessons = course.lessons
    // First not-yet-completed lesson = where the learner is.
    val currentId = lessons.firstOrNull { (s.course.lessons[it.id] ?: 0) == 0 }?.id
    val listState = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize().background(K.c.bg), state = listState, contentPadding = PaddingValues(bottom = BottomBarSpace)) {
        item { Header(target, s.course.cefr, s.stats.streak(), s.stats.xpOn(LocalDate.now()), s.profile.dailyGoalXp, s.stats.freezes) }
        item { UpdateBanner(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }

        if (due > 0) item {
            KCard(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth(), onClick = { nav.push(Route.Drill("review")) }, color = K.c.accent) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Autorenew, null, tint = K.c.accentInk)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("home.review.title"), style = K.t.bodyStrong, color = K.c.accentInk)
                        Text(t("home.review.due").replace("%d", "$due"), style = K.t.caption, color = K.c.accentInk.copy(alpha = 0.8f))
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = K.c.accentInk)
                }
            }
        }

        if (!target.curated) item { AiCourseIntro(course.lessons.isEmpty()) }

        course.units.forEachIndexed { ui0, unit ->
            item(key = "u_${unit.id}") {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 26.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t("home.unit")} ${ui0 + 1} · ${unit.cefr}", style = K.t.caption, color = K.c.ink3)
                        Text(unit.titleFor(ui), style = K.t.title, color = K.c.ink)
                    }
                    if (unit.grammar.isNotEmpty()) IconBtn(Icons.AutoMirrored.Rounded.MenuBook, t("home.grammar"), { nav.push(Route.GrammarUnit(unit.id)) }, bg = K.c.surface)
                }
            }
            items(unit.lessons, key = { it.id }) { lesson ->
                val idx = lessons.indexOfFirst { it.id == lesson.id }
                val done = s.course.lessons[lesson.id] ?: 0
                val unlocked = done > 0 || lesson.id == currentId || idx == 0
                LessonNode(
                    title = lesson.titleFor(ui),
                    subtitle = lesson.words.take(4).joinToString(" · ") { it.display },
                    icon = iconFor(unit.icon), crowns = done, current = lesson.id == currentId, locked = !unlocked,
                    offset = listOf(0, 1, 2, 1)[idx % 4],
                ) { nav.push(Route.Lesson(lesson.id)) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    // Scroll to the current lesson the first time.
    LaunchedEffect(currentId) {
        if (currentId != null && listState.firstVisibleItemIndex == 0) {
            var i = 2 + (if (due > 0) 1 else 0) + (if (!target.curated) 1 else 0)
            for (u in course.units) { i++; val j = u.lessons.indexOfFirst { it.id == currentId }; if (j >= 0) { i += j; break } else i += u.lessons.size }
            if (i > 4) listState.animateScrollToItem((i - 2).coerceAtLeast(0))
        }
    }
}

@Composable
private fun Header(target: Lang, cefr: String, streak: Int, xpToday: Int, goal: Int, freezes: Int) {
    val t = LocalT.current
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(target.flag, style = K.t.headline)
                Spacer(Modifier.width(8.dp))
                Text(target.endonym, style = K.t.headline, color = K.c.ink)
                Spacer(Modifier.width(8.dp))
                Pill(cefr)
            }
            Spacer(Modifier.height(6.dp))
            val nav = LocalNav.current
            val lit = xpToday > 0
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(if (lit) K.c.warmSoft else K.c.surface2)
                    .pressable(scaleTo = 0.94f) { nav.push(Route.Streak) }.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (lit) K.c.warm else K.c.ink3, modifier = Modifier.size(18.dp))
                Text(" $streak ${t("home.days")}", style = K.t.label, color = if (lit) K.c.ink else K.c.ink2)
                if (freezes > 0) {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Rounded.AcUnit, null, tint = K.c.accent, modifier = Modifier.size(15.dp))
                    Text(" $freezes", style = K.t.label, color = K.c.ink2)
                }
            }
            if (!lit && streak > 0) Text(t("streak.atRiskShort"), style = K.t.caption, color = K.c.warm, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
        }
        Ring(xpToday / goal.toFloat(), size = 58.dp, color = if (xpToday >= goal) K.c.good else K.c.accent) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$xpToday", style = K.t.label, color = K.c.ink)
                Text("/$goal XP", style = K.t.caption.copy(fontSize = K.t.caption.fontSize * 0.8f), color = K.c.ink3)
            }
        }
    }
}

@Composable
private fun LessonNode(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, crowns: Int, current: Boolean, locked: Boolean, offset: Int, onClick: () -> Unit) {
    val c = K.c
    val t = LocalT.current
    val h = rememberHaptics()
    val pulse by animateFloatAsState(if (current) 1f else 0f, Motion.gentle(), label = "cur")
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp + (offset * 18).dp, end = 20.dp, top = 6.dp, bottom = 6.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(if (current) c.surface else c.bg)
            .then(if (current) Modifier.border(1.5.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(22.dp)) else Modifier)
            .pressable(enabled = !locked, scaleTo = 0.97f) { h.tap(); onClick() }
            .padding(12.dp)
            .graphicsLayer { alpha = if (locked) 0.45f else 1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val circle = when { crowns >= 5 -> c.warm; crowns > 0 -> c.good; current -> c.accent; else -> c.surface2 }
        Box(Modifier.size(54.dp).graphicsLayer { scaleX = 1f + 0.04f * pulse; scaleY = 1f + 0.04f * pulse }.clip(CircleShape).background(circle), contentAlignment = Alignment.Center) {
            Icon(
                when { locked -> Icons.Rounded.Lock; crowns > 0 -> Icons.Rounded.Check; else -> icon },
                null, tint = if (crowns > 0 || current) androidx.compose.ui.graphics.Color.White else c.ink3, modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = K.t.bodyStrong, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = K.t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (crowns > 0) Row(Modifier.padding(top = 4.dp)) {
                repeat(5) { i -> Dot(if (i < crowns) c.warm else c.surface2, 6.dp); Spacer(Modifier.width(3.dp)) }
            }
        }
        if (current) Pill(t("home.start"), color = c.accent, fg = c.accentInk)
    }
}

@Composable
private fun AiCourseIntro(empty: Boolean) {
    val t = LocalT.current
    val nav = LocalNav.current
    KCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), color = K.c.warmSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, null, tint = K.c.warm)
            Spacer(Modifier.width(10.dp))
            Text(t("ai.course.title"), style = K.t.bodyStrong, color = K.c.ink)
        }
        Spacer(Modifier.height(6.dp))
        Text(t(if (empty) "ai.course.empty" else "ai.course.body"), style = K.t.body, color = K.c.ink2)
        Spacer(Modifier.height(12.dp))
        KButton(t("ai.course.new"), { nav.push(Route.AiLessons) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
    }
}

