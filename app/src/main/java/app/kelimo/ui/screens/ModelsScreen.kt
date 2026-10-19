package app.kelimo.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kelimo.KelimoApp
import app.kelimo.ai.CatalogModel
import app.kelimo.ai.DeviceInfo
import app.kelimo.ai.EngineState
import app.kelimo.ai.LlmRequest
import app.kelimo.ai.ModelCatalog
import app.kelimo.ai.ModelFiles
import app.kelimo.core.loc
import app.kelimo.data.InstalledModel
import app.kelimo.ui.LocalNav
import app.kelimo.ui.LocalT
import app.kelimo.ui.components.*
import app.kelimo.ui.theme.K
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen() {
    val nav = LocalNav.current
    val t = LocalT.current
    Screen {
        TopBar(t("models.title"), { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            ModelsContent(compact = false)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
fun ModelsContent(compact: Boolean) {
    val app = KelimoApp.instance
    val t = LocalT.current
    val c = K.c
    val ctx = LocalContext.current
    val s by app.store.state.collectAsStateWithLifecycle()
    val engine by app.llm.state.collectAsStateWithLifecycle()
    val ui = s.profile.ui
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf<Float?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var benchmarking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<InstalledModel?>(null) }
    val ram = remember { DeviceInfo.totalRamGb(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importError = null; importing = 0f
        scope.launch {
            try {
                val m = app.modelFiles.import(uri) { p -> importing = p }
                app.store.update { st -> st.copy(models = st.models.filter { it.fileName != m.fileName } + m, activeModelId = m.id) }
                app.llm.unload()
                app.warmUpModel()
            } catch (e: ModelFiles.InvalidModel) {
                importError = t("models.err.${e.message}")
            } catch (e: Exception) {
                importError = t("models.err.generic") + " (${e.message})"
            } finally { importing = null }
        }
    }

    fun use(m: InstalledModel) {
        app.store.update { it.copy(activeModelId = m.id) }
        scope.launch { app.llm.load(m, app.store.value.settings.backend) }
    }

    // How it works (always visible in setup, collapsed into one card otherwise).
    KCard(Modifier.fillMaxWidth(), color = c.accentSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text(t("models.howto.title"), style = K.t.bodyStrong, color = c.ink)
        }
        Spacer(Modifier.height(8.dp))
        listOf("models.howto.1", "models.howto.2", "models.howto.3").forEachIndexed { i, k ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Text("${i + 1}", style = K.t.caption, color = c.accentInk) }
                Spacer(Modifier.width(10.dp))
                Text(t(k), style = K.t.body, color = c.ink2)
            }
        }
    }

    if (s.models.isNotEmpty()) {
        SectionTitle(t("models.installed"))
        s.models.forEach { m ->
            val active = m.id == s.activeModelId
            KCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), border = if (active) c.accent else null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = K.t.bodyStrong, color = c.ink)
                        Text("%.2f GB".format(m.sizeBytes / 1e9) + (m.tokensPerSecond?.let { " · ~%.0f tok/s".format(it) } ?: ""), style = K.t.caption, color = c.ink3)
                    }
                    if (active) Pill(when (engine) { is EngineState.Ready -> t("models.active"); is EngineState.Loading -> t("models.loading"); is EngineState.Failed -> t("model.failed"); else -> t("models.selected") },
                        color = if (engine is EngineState.Failed) c.badSoft else c.goodSoft, fg = if (engine is EngineState.Failed) c.bad else c.good)
                }
                if (!compact) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!active) KButton(t("models.use"), { use(m) }, height = 40.dp)
                        KButton(t("models.benchmark"), {
                            benchmarking = m.id
                            scope.launch {
                                if (!active) use(m)
                                if (app.ensureModel()) {
                                    val start = System.nanoTime()
                                    val out = runCatching { app.llm.generate(LlmRequest("You are a helpful assistant.", "Schreibe fünf kurze Sätze über das Wetter in Berlin.", temperature = 0.7)) }.getOrDefault("")
                                    val secs = (System.nanoTime() - start) / 1e9
                                    // ~4 characters per token for European languages; rough but comparable between models.
                                    val tps = out.length / 4.0 / secs
                                    app.store.update { st -> st.copy(models = st.models.map { if (it.id == m.id) it.copy(tokensPerSecond = tps) else it }) }
                                }
                                benchmarking = null
                            }
                        }, style = BtnStyle.Secondary, loading = benchmarking == m.id, height = 40.dp)
                        Spacer(Modifier.weight(1f))
                        IconBtn(Icons.Rounded.DeleteOutline, t("models.delete"), { confirmDelete = m }, tint = c.bad)
                    }
                }
            }
        }
        if (engine is EngineState.Failed) Text(t("models.err.load"), style = K.t.caption, color = c.bad, modifier = Modifier.padding(4.dp))
    }

    Spacer(Modifier.height(12.dp))
    if (importing != null) {
        KCard(Modifier.fillMaxWidth()) {
            Text(t("models.importing"), style = K.t.bodyStrong, color = c.ink)
            Spacer(Modifier.height(8.dp))
            ProgressBar(importing ?: 0f, Modifier.fillMaxWidth())
            Text("${((importing ?: 0f) * 100).toInt()} %", style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 4.dp))
        }
    } else KButton(t("models.import"), { picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen)
    importError?.let { Text(it, style = K.t.caption, color = c.bad, modifier = Modifier.padding(top = 8.dp)) }

    SectionTitle(t("models.catalog") + " · " + t("models.ram").replace("%s", "%.0f".format(ram)))
    val list = if (compact) ModelCatalog.models.filter { it.recommended || it.id == "gemma-4-e2b" } else ModelCatalog.models
    list.forEach { m -> CatalogCard(m, ui, ram, installed = s.models.any { it.fileName == m.file }) }
    if (!compact) Text(t("models.future"), style = K.t.caption, color = c.ink3, modifier = Modifier.padding(top = 8.dp, start = 4.dp))

    confirmDelete?.let { m ->
        KCard(Modifier.fillMaxWidth().padding(top = 12.dp), color = c.badSoft) {
            Text(t("models.delete.confirm").replace("%s", m.name), style = K.t.bodyStrong, color = c.ink)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KButton(t("models.delete"), {
                    scope.launch {
                        if (m.id == s.activeModelId) app.llm.unload()
                        app.modelFiles.delete(m)
                        app.store.update { st ->
                            val rest = st.models.filter { it.id != m.id }
                            st.copy(models = rest, activeModelId = if (st.activeModelId == m.id) rest.firstOrNull()?.id else st.activeModelId)
                        }
                        if (app.store.value.activeModel == null) app.llm.markNoModel()
                        confirmDelete = null
                    }
                }, style = BtnStyle.Bad, height = 40.dp)
                KButton(t("common.cancel"), { confirmDelete = null }, style = BtnStyle.Secondary, height = 40.dp)
            }
        }
    }
}

@Composable
private fun CatalogCard(m: CatalogModel, ui: String, ram: Double, installed: Boolean) {
    val c = K.c
    val t = LocalT.current
    val ctx = LocalContext.current
    val fits = ram + 0.5 >= m.ramGb
    KCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), border = if (m.recommended) c.accent.copy(alpha = 0.5f) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(m.name, style = K.t.bodyStrong, color = c.ink, modifier = Modifier.weight(1f))
            if (m.recommended) Pill(t("models.recommended"))
        }
        Text("%.2f GB · %s".format(m.sizeGb, t("models.needs").replace("%d", "${m.ramGb}")), style = K.t.caption, color = if (fits) c.ink3 else c.bad)
        Spacer(Modifier.height(6.dp))
        Text(m.note.loc(ui), style = K.t.body, color = c.ink2)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("models.quality"), style = K.t.caption, color = c.ink3); Spacer(Modifier.width(6.dp)); Dots(m.quality)
            Spacer(Modifier.width(16.dp))
            Text(t("models.speed"), style = K.t.caption, color = c.ink3); Spacer(Modifier.width(6.dp)); Dots(m.speed)
        }
        Spacer(Modifier.height(12.dp))
        if (installed) Pill(t("models.isInstalled"), color = c.goodSoft, fg = c.good, icon = Icons.Rounded.Check)
        else KButton(t("models.download"), {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(m.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, style = BtnStyle.Secondary, icon = Icons.Rounded.OpenInBrowser, height = 44.dp)
    }
}

@Composable
private fun Dots(n: Int) = Row { repeat(5) { i -> Dot(if (i < n) K.c.accent else K.c.surface2, 7.dp); Spacer(Modifier.width(3.dp)) } }
