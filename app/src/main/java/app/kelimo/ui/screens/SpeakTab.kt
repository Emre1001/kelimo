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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.ai.EngineState
import app.kelimo.ai.Scenarios
import app.kelimo.core.loc
import app.kelimo.learn.Adaptive
import app.kelimo.ui.BottomBarSpace
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.Route
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K

@Composable
fun SpeakTab() {
    val app = KelimoApp.instance
    val s by app.store.state.collectAsStateWithLifecycle()
    val engine by app.llm.state.collectAsStateWithLifecycle()
    val t = LocalT.current
    val nav = LocalNav.current
    val c = K.c
    val ui = s.profile.ui
    val lvl = Adaptive.levels.indexOf(s.course.cefr)

    Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(16.dp))
        Text(t("tab.speak"), style = K.t.display, color = c.ink)
        Spacer(Modifier.height(4.dp))
        ModelStatusLine(engine)
        Spacer(Modifier.height(16.dp))

        if (engine is EngineState.NoModel) {
            KCard(Modifier.fillMaxWidth(), color = c.warmSoft, onClick = { nav.push(Route.Models) }) {
                Text(t("speak.noModel.title"), style = K.t.bodyStrong, color = c.ink)
                Text(t("speak.noModel.body"), style = K.t.body, color = c.ink2)
                Spacer(Modifier.height(10.dp))
                KButton(t("models.title"), { nav.push(Route.Models) }, icon = Icons.Rounded.Download)
            }
            Spacer(Modifier.height(12.dp))
        }

        // Hero: hands-free voice conversation.
        KCard(Modifier.fillMaxWidth(), color = c.accent, onClick = { nav.push(Route.Chat("free", voice = true)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.accentInk.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = c.accentInk, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t("speak.voice.title"), style = K.t.headline, color = c.accentInk)
                    Text(t("speak.voice.sub"), style = K.t.caption, color = c.accentInk.copy(alpha = 0.8f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        KCard(Modifier.fillMaxWidth(), onClick = { nav.push(Route.Chat("free")) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = c.accent)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(Scenarios.free.title.loc(ui), style = K.t.bodyStrong, color = c.ink)
                    Text(Scenarios.free.goal.loc(ui), style = K.t.caption, color = c.ink3)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
            }
        }

        SectionTitle(t("speak.roleplays"))
        Scenarios.all.drop(1).forEach { sc ->
            val above = Adaptive.levels.indexOf(sc.minLevel) > lvl
            KCard(Modifier.fillMaxWidth().padding(vertical = 5.dp), onClick = { nav.push(Route.Chat(sc.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                        Icon(iconFor(sc.icon), null, tint = c.ink2)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sc.title.loc(ui), style = K.t.bodyStrong, color = c.ink)
                        Text(sc.goal.loc(ui), style = K.t.caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Pill(sc.minLevel, color = if (above) c.warmSoft else c.surface2, fg = if (above) c.warm else c.ink2)
                }
            }
        }

        val recent = s.chats.filter { it.target == s.profile.target && it.messages.size > 1 }.sortedByDescending { it.updatedAt }.take(5)
        if (recent.isNotEmpty()) {
            SectionTitle(t("speak.recent"))
            recent.forEach { th ->
                val sc = Scenarios.byId(th.scenarioId)
                KCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { nav.push(Route.Chat(th.scenarioId, th.id)) }, padding = PaddingValues(14.dp)) {
                    Text(sc.title.loc(ui), style = K.t.label, color = c.ink)
                    Text(th.messages.last().text, style = K.t.caption, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(BottomBarSpace))
    }
}

@Composable
fun ModelStatusLine(engine: EngineState) {
    val t = LocalT.current
    val app = KelimoApp.instance
    val (color, text) = when (engine) {
        is EngineState.Ready -> K.c.good to t("model.ready").replace("%s", app.store.value.activeModel?.name ?: "") + " · ${engine.backend}"
        is EngineState.Loading -> K.c.warm to t("model.loading").replace("%s", engine.name)
        is EngineState.Idle -> K.c.ink3 to t("model.idle").replace("%s", app.store.value.activeModel?.name ?: "")
        is EngineState.Failed -> K.c.bad to t("model.failed")
        EngineState.NoModel -> K.c.bad to t("model.none")
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(color)
        Spacer(Modifier.width(8.dp))
        Text(text, style = K.t.caption, color = K.c.ink2)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.WifiOff, null, tint = K.c.ink3, modifier = Modifier.size(14.dp))
        Text(" " + t("privacy.offline"), style = K.t.caption, color = K.c.ink3)
    }
}
