package app.kelimo.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.LocalReduceMotion
import app.kelimo.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

sealed interface Route {
    val key: String get() = toString()
    data object Main : Route
    data class Lesson(val lessonId: String) : Route
    data class Drill(val kind: String) : Route
    data class Chat(val scenarioId: String, val threadId: String? = null, val voice: Boolean = false) : Route
    data object Writing : Route
    data object Models : Route
    data object Settings : Route
    data object Privacy : Route
    data object Dictionary : Route
    data object Grammar : Route
    data class GrammarUnit(val unitId: String) : Route
    data object Vocab : Route
    data object Mistakes : Route
    data object Stories : Route
    data object AiLessons : Route
    data object Placement : Route
    data object Voices : Route
    data object Streak : Route
}

@Stable
class Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    /** Set when a back gesture already animated the screen away, so the content swap skips its own animation. */
    var gestureCommitted by mutableStateOf(false)
    val top: Route get() = stack.last()
    var forward by mutableStateOf(true)
        private set

    fun push(r: Route) { forward = true; gestureCommitted = false; stack.add(r) }
    fun replace(r: Route) { forward = true; gestureCommitted = false; stack[stack.lastIndex] = r }
    fun pop(): Boolean {
        if (stack.size <= 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }
    fun popToMain() { forward = false; while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("no nav") }

/**
 * Stack host with Android predictive back: during the swipe the current screen follows the finger
 * (scale + edge shift + rounded corners) over the previous one; release commits or springs back.
 */
@Composable
fun NavHost(nav: Nav, content: @Composable (Route) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    var edge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }
    val reduce = LocalReduceMotion.current

    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        try {
            events.collect { e ->
                edge = e.swipeEdge
                progress.snapTo(e.progress)
            }
            nav.gestureCommitted = true
            nav.pop()
            progress.snapTo(0f)
        } catch (c: CancellationException) {
            scope.launch { progress.animateTo(0f, Motion.layout()) }
        }
    }

    val below = nav.stack.getOrNull(nav.stack.lastIndex - 1)
    Box(Modifier.fillMaxSize().background(K.c.bg)) {
        // Previous screen peeks through only while a back gesture is in progress.
        if (progress.value > 0f && below != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.6f + 0.4f * progress.value }) {
                holder.SaveableStateProvider(below.key) { content(below) }
            }
        }
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = { transition(nav.forward, nav.gestureCommitted, reduce) },
            label = "nav",
            contentKey = { it.key },
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                if (p > 0f) {
                    val s = 1f - 0.08f * p
                    scaleX = s; scaleY = s
                    translationX = (if (edge == BackEventCompat.EDGE_LEFT) 1 else -1) * 24.dp.toPx() * p
                    shape = RoundedCornerShape(28.dp * p)
                    clip = true
                }
            },
        ) { route ->
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(K.c.bg)) {
                holder.SaveableStateProvider(route.key) { content(route) }
            }
        }
    }
}

private fun transition(forward: Boolean, gesture: Boolean, reduce: Boolean): ContentTransform = when {
    gesture -> EnterTransition.None togetherWith (fadeOut(Motion.quick()) + scaleOut(Motion.quick(), targetScale = 0.9f))
    reduce -> fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick())
    forward -> (slideInHorizontally(Motion.offset) { it / 3 } + fadeIn(Motion.quick())) togetherWith
        (slideOutHorizontally(Motion.offset) { -it / 8 } + fadeOut(Motion.quick()))
    else -> (slideInHorizontally(Motion.offset) { -it / 8 } + fadeIn(Motion.quick())) togetherWith
        (slideOutHorizontally(Motion.offset) { it / 3 } + fadeOut(Motion.quick()))
}
