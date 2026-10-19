package app.kelimo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.learn.Srs
import app.kelimo.ui.BottomBarSpace
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K

private data class Mode(val title: String, val sub: String, val icon: ImageVector, val color: Color, val soft: Color, val ai: Boolean = false, val go: () -> Unit)

@Composable
fun PracticeTab() {
    val app = KelimoApp.instance
    val s by app.store.state.collectAsStateWithLifecycle()
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val course = remember(s.profile.target) { app.courses.course(s.profile.target) }
    val due = Srs.due(s.course).size
    val open = s.course.mistakes.count { !it.fixed }

    val core = buildList {
        add(Mode(t("practice.review"), if (due > 0) t("home.review.due").replace("%d", "$due") else t("practice.review.sub"), Icons.Rounded.Autorenew, c.accent, c.accentSoft) { nav.push(Route.Drill("review")) })
        add(Mode(t("practice.mistakes"), t("practice.mistakes.sub").replace("%d", "$open"), Icons.Rounded.RuleFolder, c.bad, c.badSoft) { nav.push(Route.Mistakes) })
        if (s.profile.target == "de") add(Mode(t("practice.articles"), "der · die · das", Icons.Rounded.Palette, c.die, c.badSoft) { nav.push(Route.Drill("articles")) })
        if (course?.verbs?.isNotEmpty() == true) add(Mode(t("practice.verbs"), t("practice.verbs.sub"), Icons.Rounded.Transform, c.good, c.goodSoft) { nav.push(Route.Drill("verbs")) })
        if (course != null) {
            add(Mode(t("practice.listening"), t("practice.listening.sub"), Icons.Rounded.Headphones, c.accent, c.accentSoft) { nav.push(Route.Drill("listening")) })
            add(Mode(t("practice.speaking"), t("practice.speaking.sub"), Icons.Rounded.RecordVoiceOver, c.warm, c.warmSoft) { nav.push(Route.Drill("speaking")) })
        }
    }
    val ai = listOf(
        Mode(t("practice.writing"), t("practice.writing.sub"), Icons.Rounded.EditNote, c.accent, c.accentSoft, ai = true) { nav.push(Route.Writing) },
        Mode(t("practice.stories"), t("practice.stories.sub"), Icons.Rounded.AutoStories, c.good, c.goodSoft, ai = true) { nav.push(Route.Stories) },
        Mode(t("practice.grammar"), t("practice.grammar.sub"), Icons.Rounded.School, c.warm, c.warmSoft, ai = true) { nav.push(Route.Grammar) },
    )
    val tools = listOf(
        Mode(t("practice.dictionary"), t("practice.dictionary.sub"), Icons.Rounded.Search, c.ink2, c.surface2) { nav.push(Route.Dictionary) },
        Mode(t("practice.vocab"), t("practice.vocab.sub").replace("%d", "${s.course.srs.count { it.key.startsWith("w:") }}"), Icons.Rounded.Style, c.ink2, c.surface2) { nav.push(Route.Vocab) },
    )

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(t("tab.practice"), style = K.t.display, color = c.ink)
        SectionTitle(t("practice.section.train"))
        Grid(core)
        SectionTitle(t("practice.section.ai"))
        Grid(ai)
        SectionTitle(t("practice.section.tools"))
        Grid(tools)
        Spacer(Modifier.height(BottomBarSpace))
    }
}

@Composable
private fun Grid(modes: List<Mode>) {
    val t = LocalT.current
    modes.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { m ->
                KCard(Modifier.weight(1f).heightIn(min = 132.dp), onClick = m.go) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(m.soft), contentAlignment = Alignment.Center) {
                            Icon(m.icon, null, tint = m.color, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        if (m.ai) Icon(Icons.Rounded.AutoAwesome, t("ai.label"), tint = K.c.ink3, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(m.title, style = K.t.bodyStrong, color = K.c.ink)
                    Text(m.sub, style = K.t.caption, color = K.c.ink3, maxLines = 2)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
