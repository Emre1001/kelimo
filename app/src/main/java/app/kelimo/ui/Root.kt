package app.kelimo.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Route as RouteIcon
import androidx.compose.material.icons.rounded.SportsGymnastics
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.kelimo.data.AppState
import app.kelimo.ui.components.pressable
import app.kelimo.ui.components.rememberHaptics
import app.kelimo.ui.screens.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion

@Composable
fun Root(state: AppState) {
    val nav = remember { Nav() }
    CompositionLocalProvider(LocalT provides I18n.table(state.profile.ui), LocalNav provides nav) {
        AnimatedContent(state.onboarded, transitionSpec = { (fadeIn(Motion.quick()) + scaleIn(Motion.layout(), 0.96f)) togetherWith fadeOut(Motion.quick()) }, label = "root") { onboarded ->
            if (!onboarded) SetupFlow()
            else NavHost(nav) { route ->
                when (route) {
                    Route.Main -> MainTabs()
                    is Route.Lesson -> LessonScreen(SessionKind.Lesson(route.lessonId))
                    is Route.Drill -> LessonScreen(SessionKind.Drill(route.kind))
                    is Route.Chat -> ChatScreen(route.scenarioId, route.threadId, route.voice)
                    Route.Writing -> WritingScreen()
                    Route.Models -> ModelsScreen()
                    Route.Settings -> SettingsScreen()
                    Route.Privacy -> PrivacyScreen()
                    Route.Dictionary -> DictionaryScreen()
                    Route.Grammar -> GrammarScreen()
                    is Route.GrammarUnit -> GrammarUnitScreen(route.unitId)
                    Route.Vocab -> VocabScreen()
                    Route.Mistakes -> MistakesScreen()
                    Route.Stories -> StoriesScreen()
                    Route.AiLessons -> AiLessonsScreen()
                    Route.Placement -> PlacementScreen()
                    Route.Voices -> VoicesScreen()
                    Route.Streak -> StreakScreen()
                }
            }
        }
    }
}

private data class Tab(val key: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val tabs = listOf(
    Tab("tab.learn", Icons.Rounded.RouteIcon),
    Tab("tab.practice", Icons.Rounded.SportsGymnastics),
    Tab("tab.speak", Icons.Rounded.Forum),
    Tab("tab.me", Icons.Rounded.Person),
)

@Composable
private fun MainTabs() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val holder = rememberSaveableStateHolder()
    Box(Modifier.fillMaxSize().background(K.c.bg)) {
        AnimatedContent(
            tab,
            transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) },
            label = "tabs",
        ) { i ->
            holder.SaveableStateProvider(i) {
                when (i) {
                    0 -> LearnTab()
                    1 -> PracticeTab()
                    2 -> SpeakTab()
                    else -> MeTab()
                }
            }
        }
        // Solid strip under the status bar so scrolled content never runs into the clock and icons.
        Box(Modifier.fillMaxWidth().background(K.c.bg).statusBarsPadding())
        BottomBar(tab, { tab = it }, Modifier.align(Alignment.BottomCenter))
    }
}

/** Floating pill bar with a sliding selection indicator. */
@Composable
private fun BottomBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    val t = LocalT.current
    val c = K.c
    val h = rememberHaptics()
    var itemW by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val x by animateDpAsState(with(density) { (itemW * selected).toDp() }, Motion.layout(), label = "indicator")
    Box(
        modifier
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .fillMaxWidth()
            .shadow(18.dp, RoundedCornerShape(28.dp), ambientColor = c.ink.copy(alpha = 0.2f), spotColor = c.ink.copy(alpha = 0.2f))
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(28.dp))
            .padding(6.dp),
    ) {
        if (itemW > 0) Box(
            Modifier.offset(x = x).width(with(density) { itemW.toDp() }).height(56.dp)
                .clip(RoundedCornerShape(22.dp)).background(c.accentSoft)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            tabs.forEachIndexed { i, tb ->
                val sel = i == selected
                val col by animateColorAsState(if (sel) c.accent else c.ink3, Motion.quick(), label = "tabc")
                Column(
                    Modifier.weight(1f).height(56.dp).onSizeChanged { itemW = it.width }
                        .pressable(scaleTo = 0.92f) { if (!sel) { h.tick(); onSelect(i) } },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(tb.icon, null, tint = col, modifier = Modifier.size(24.dp))
                    Text(t(tb.key), style = K.t.caption, color = col)
                }
            }
        }
    }
}

/** Space at the bottom of tab screens so content scrolls clear of the floating bar. */
val BottomBarSpace = 110.dp
