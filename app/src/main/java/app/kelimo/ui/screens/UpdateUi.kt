package app.kelimo.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.ui.LocalT
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import app.kelimo.ui.theme.Motion
import app.kelimo.update.UpdateState
import app.kelimo.update.Updater
import kotlinx.coroutines.launch

/** Card on the learn tab while an update is available, downloading or ready. */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier) {
    val st by Updater.state.collectAsStateWithLifecycle()
    val visible = st is UpdateState.Available || st is UpdateState.Downloading || st is UpdateState.Ready || (st as? UpdateState.Failed)?.release != null
    AnimatedVisibility(visible, modifier, enter = expandVertically(Motion.layout()) + fadeIn(), exit = shrinkVertically(Motion.layout()) + fadeOut()) {
        KCard(Modifier.fillMaxWidth(), color = K.c.accentSoft) { UpdateContent(st, banner = true) }
    }
}

@Composable
fun UpdateContent(st: UpdateState, banner: Boolean) {
    val t = LocalT.current
    val c = K.c
    val ctx = LocalContext.current
    val release = when (st) {
        is UpdateState.Available -> st.release
        is UpdateState.Downloading -> st.release
        is UpdateState.Ready -> st.release
        is UpdateState.Failed -> st.release
        is UpdateState.Dismissed -> st.release
        else -> null
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = c.accentInk, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (release != null) t("update.available").replace("%s", release.version) else t("update.title"),
                style = K.t.bodyStrong, color = c.ink,
            )
            Text(
                when (st) {
                    is UpdateState.Checking -> t("update.checking")
                    is UpdateState.UpToDate -> t("update.upToDate").replace("%s", Updater.currentVersion(ctx))
                    is UpdateState.Failed -> t(if (st.reason == "network") "update.offline" else "update.failed")
                    is UpdateState.Ready -> t("update.ready")
                    is UpdateState.Downloading -> "${(st.progress * 100).toInt()} %"
                    else -> t("update.keep")
                },
                style = K.t.caption, color = c.ink2,
            )
        }
        if (banner && st is UpdateState.Available) IconBtn(Icons.Rounded.Close, t("common.cancel"), { Updater.dismiss() }, tint = c.ink3, size = 36.dp)
    }
    if (release != null && release.notes.isNotBlank() && st !is UpdateState.Downloading) {
        Spacer(Modifier.height(8.dp))
        Text(release.notes.lines().filter { it.isNotBlank() }.take(4).joinToString("\n") { it.trim().removePrefix("- ").removePrefix("* ") },
            style = K.t.caption, color = c.ink2, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
    if (st is UpdateState.Downloading) {
        Spacer(Modifier.height(10.dp))
        ProgressBar(st.progress, Modifier.fillMaxWidth())
    }
    if (release != null && st !is UpdateState.Downloading) {
        Spacer(Modifier.height(12.dp))
        KButton(
            t(if (st is UpdateState.Ready) "update.install" else "update.download"),
            {
                if (st is UpdateState.Ready) Updater.install(ctx, st.apk)
                // App scope: leaving the screen must not cancel the download.
                else KelimoApp.instance.scope.launch { Updater.download(ctx.applicationContext, release) }
            },
            Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, height = 46.dp,
        )
    }
}
