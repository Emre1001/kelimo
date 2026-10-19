package app.kelimo.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kelimo.KelimoApp
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.LocalReduceMotion
import app.kelimo.ui.theme.Motion

// ---------------------------------------------------------------- haptics

class Haptics(private val view: android.view.View, private val enabled: Boolean) {
    fun tick() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    fun tap() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    fun confirm() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) }
    fun reject() { if (enabled) view.performHapticFeedback(HapticFeedbackConstants.REJECT) }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    val enabled = KelimoApp.instance.store.value.settings.haptics
    return remember(view, enabled) { Haptics(view, enabled) }
}

// ---------------------------------------------------------------- press feedback

/** Scale-on-press with a spring; replaces the ripple everywhere for a calmer, physical feel. */
fun Modifier.pressable(
    enabled: Boolean = true,
    scaleTo: Float = 0.965f,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = composedPressable(enabled, scaleTo, role, onClick)

@Composable
private fun pressState(source: MutableInteractionSource, scaleTo: Float): Float {
    val pressed by source.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val s by animateFloatAsState(if (pressed && !reduce) scaleTo else 1f, Motion.press(), label = "press")
    return s
}

private fun Modifier.composedPressable(enabled: Boolean, scaleTo: Float, role: Role, onClick: () -> Unit): Modifier =
    this.composed {
        val source = remember { MutableInteractionSource() }
        val s = pressState(source, scaleTo)
        Modifier
            .graphicsLayer { scaleX = s; scaleY = s }
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
    }

// ---------------------------------------------------------------- buttons

enum class BtnStyle { Primary, Secondary, Ghost, Good, Bad }

@Composable
fun KButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = 54.dp,
) {
    val c = K.c
    val h = rememberHaptics()
    val (bg, fg) = when (style) {
        BtnStyle.Primary -> c.accent to c.accentInk
        BtnStyle.Secondary -> c.surface2 to c.ink
        BtnStyle.Ghost -> Color.Transparent to c.accent
        BtnStyle.Good -> c.good to Color.White
        BtnStyle.Bad -> c.bad to Color.White
    }
    val bgA by animateColorAsState(if (enabled) bg else c.surface2, Motion.quick(), label = "bg")
    val fgA by animateColorAsState(if (enabled) fg else c.ink3, Motion.quick(), label = "fg")
    Box(
        modifier
            .heightIn(min = height)
            .clip(RoundedCornerShape(18.dp))
            .background(bgA)
            .pressable(enabled && !loading) { h.tap(); onClick() }
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = fgA, strokeWidth = 2.5.dp)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { Icon(icon, null, tint = fgA, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, style = K.t.bodyStrong, color = fgA, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun IconBtn(icon: ImageVector, desc: String?, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = K.c.ink, bg: Color = Color.Transparent, size: Dp = 44.dp) {
    val h = rememberHaptics()
    Box(
        modifier.size(size).clip(CircleShape).background(bg).pressable(scaleTo = 0.9f) { h.tick(); onClick() },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

// ---------------------------------------------------------------- surfaces

@Composable
fun KCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    color: Color = K.c.surface,
    border: Color? = null,
    padding: PaddingValues = PaddingValues(18.dp),
    radius: Dp = 22.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val h = rememberHaptics()
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(color)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), RoundedCornerShape(radius)) else Modifier)
            .then(if (onClick != null) Modifier.pressable(scaleTo = 0.98f) { h.tap(); onClick() } else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun TopBar(title: String?, onBack: (() -> Unit)?, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) IconBtn(Icons.AutoMirrored.Rounded.ArrowBack, "back", onBack) else Spacer(Modifier.width(12.dp))
        if (title != null) Text(title, style = K.t.headline, color = K.c.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 4.dp))
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = K.t.caption, color = K.c.ink3, modifier = modifier.padding(start = 4.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
fun Pill(text: String, color: Color = K.c.accentSoft, fg: Color = K.c.accent, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, style = K.t.caption, color = fg, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------- progress

@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, color: Color = K.c.accent, track: Color = K.c.surface2, height: Dp = 10.dp) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.gentle(), label = "progress")
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(track)) {
        Box(Modifier.fillMaxWidth(p).height(height).clip(RoundedCornerShape(50)).background(color))
    }
}

@Composable
fun Ring(progress: Float, modifier: Modifier = Modifier, size: Dp = 64.dp, stroke: Dp = 7.dp, color: Color = K.c.accent, track: Color = K.c.surface2, content: @Composable BoxScope.() -> Unit = {}) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.gentle(), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = this.size.copy(this.size.width - s, this.size.height - s))
            drawArc(color, -90f, 360f * p, false, style = Stroke(s, cap = StrokeCap.Round), topLeft = Offset(s / 2, s / 2), size = this.size.copy(this.size.width - s, this.size.height - s))
        }
        content()
    }
}

/** Mic level bars. */
@Composable
fun Waveform(level: Float, modifier: Modifier = Modifier, color: Color = K.c.accent) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(level) { anim.animateTo(level, tween(90)) }
    Canvas(modifier.height(36.dp).width(120.dp)) {
        val n = 9
        val w = size.width / (n * 2f)
        for (i in 0 until n) {
            val shape = 1f - kotlin.math.abs(i - n / 2) / (n / 2f + 1)
            val h = (0.15f + anim.value * shape) * size.height
            drawLine(color, Offset(w + i * 2 * w, (size.height - h) / 2), Offset(w + i * 2 * w, (size.height + h) / 2), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- inputs

@Composable
fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val h = rememberHaptics()
    Row(
        Modifier.fillMaxWidth().pressable(scaleTo = 0.99f, role = Role.Switch) { h.tick(); onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = K.t.body, color = K.c.ink)
            if (subtitle != null) Text(subtitle, style = K.t.caption, color = K.c.ink3)
        }
        Switch(
            checked, { h.tick(); onChange(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = K.c.accent, checkedThumbColor = K.c.accentInk, uncheckedTrackColor = K.c.surface2, uncheckedBorderColor = K.c.line),
        )
    }
}

@Composable
fun SelectRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: String? = null, subtitle: String? = null) {
    val c = K.c
    val h = rememberHaptics()
    val bg by animateColorAsState(if (selected) c.accentSoft else c.surface, Motion.quick(), label = "sel")
    val bd by animateColorAsState(if (selected) c.accent else c.line, Motion.quick(), label = "bd")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(bg).border(1.5.dp, bd, RoundedCornerShape(18.dp))
            .pressable(scaleTo = 0.98f) { h.tick(); onClick() }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Text(leading, style = K.t.headline); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = K.t.bodyStrong, color = c.ink)
            if (subtitle != null) Text(subtitle, style = K.t.caption, color = c.ink2)
        }
        AnimatedVisibility(selected, enter = fadeIn(), exit = fadeOut()) {
            Icon(Icons.Rounded.CheckCircle, null, tint = c.accent)
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(K.c.surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = K.c.ink2, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = K.t.headline, color = K.c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = K.t.body, color = K.c.ink2, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

/** Subtle "AI generated / unverified" label shown on all model output. */
@Composable
fun AiBadge(text: String, verified: Boolean = false) {
    Pill(text, color = if (verified) K.c.goodSoft else K.c.warmSoft, fg = if (verified) K.c.good else K.c.warm, icon = if (verified) Icons.Rounded.Verified else Icons.Rounded.AutoAwesome)
}

fun iconFor(name: String): ImageVector = when (name) {
    "chat" -> Icons.Rounded.ChatBubbleOutline
    "numbers" -> Icons.Rounded.Pin
    "family" -> Icons.Rounded.People
    "food" -> Icons.Rounded.Restaurant
    "home" -> Icons.Rounded.Home
    "day" -> Icons.Rounded.WbSunny
    "work" -> Icons.Rounded.Work
    "travel", "train" -> Icons.Rounded.Train
    "health" -> Icons.Rounded.LocalHospital
    "doc" -> Icons.Rounded.Description
    "free" -> Icons.Rounded.Celebration
    "feel" -> Icons.Rounded.Favorite
    "bakery" -> Icons.Rounded.BakeryDining
    "coffee" -> Icons.Rounded.LocalCafe
    "calendar" -> Icons.Rounded.CalendarMonth
    "key" -> Icons.Rounded.Key
    "school" -> Icons.Rounded.School
    "alert" -> Icons.Rounded.ReportProblem
    "shop" -> Icons.Rounded.ShoppingBag
    "spark" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.Circle
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun ScaleIn(visible: Boolean, content: @Composable () -> Unit) {
    val s by animateFloatAsState(if (visible) 1f else 0.6f, Motion.press(), label = "scaleIn")
    val a by animateFloatAsState(if (visible) 1f else 0f, Motion.quick(), label = "alphaIn")
    Box(Modifier.scale(s).graphicsLayer { alpha = a }) { content() }
}

@Composable
fun Screen(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(K.c.bg), content = content)
}
