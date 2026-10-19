package app.kelimo.ai

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import app.kelimo.data.InstalledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Known-good models in LiteRT-LM format. The app never downloads anything itself
 * (it has no network permission); the link opens the browser, the user imports the file.
 * Any future .litertlm model can be imported the same way.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val repo: String,
    val file: String,
    val sizeGb: Double,
    /** Rough RAM needed to run comfortably. */
    val ramGb: Int,
    /** 1..5 */
    val quality: Int,
    /** 1..5 */
    val speed: Int,
    val recommended: Boolean = false,
    val note: Map<String, String>,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$file?download=true"
    val page: String get() = "https://huggingface.co/$repo"
}

object ModelCatalog {
    val models = listOf(
        CatalogModel(
            "gemma-4-e4b", "Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm", "gemma-4-E4B-it.litertlm",
            3.66, 8, quality = 5, speed = 3, recommended = true,
            note = mapOf(
                "tr" to "Galaxy S25+ için en iyi seçim. Almanca ve Türkçede çok güçlü.",
                "de" to "Beste Wahl fürs Galaxy S25+. Sehr stark in Deutsch und Türkisch.",
                "en" to "Best choice for Galaxy S25+. Very strong in German and Turkish.",
            ),
        ),
        CatalogModel(
            "gemma-4-e2b", "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "gemma-4-E2B-it.litertlm",
            2.59, 6, quality = 4, speed = 4,
            note = mapOf(
                "tr" to "Daha hızlı ve küçük. Akıcı sesli sohbet için iyi.",
                "de" to "Schneller und kleiner. Gut für flüssige Sprachgespräche.",
                "en" to "Faster and smaller. Good for fluid voice chat.",
            ),
        ),
        CatalogModel(
            "qwen3-4b-2507", "Qwen3 4B Instruct", "litert-community/Qwen3-4B-Instruct-2507", "qwen3_4b_instruct_2507_mixed_int4.litertlm",
            2.66, 6, quality = 4, speed = 3,
            note = mapOf(
                "tr" to "Güçlü alternatif, çok dilli.",
                "de" to "Starke Alternative, mehrsprachig.",
                "en" to "Strong multilingual alternative.",
            ),
        ),
        CatalogModel(
            "qwen3.5-4b", "Qwen3.5 4B", "litert-community/Qwen3.5-4B", "Qwen3.5-4B_mixed_int4.litertlm",
            2.75, 6, quality = 4, speed = 3,
            note = mapOf(
                "tr" to "Yeni nesil Qwen. Gramer açıklamalarında iyi.",
                "de" to "Neue Qwen-Generation. Gut bei Grammatik-Erklärungen.",
                "en" to "Newer Qwen generation. Good at grammar explanations.",
            ),
        ),
        CatalogModel(
            "ministral-3-3b", "Ministral 3 3B", "litert-community/Ministral-3-3B-Instruct-2512", "Ministral-3-3B-Instruct-2512_q4_block32_ekv4096.litertlm",
            2.34, 5, quality = 3, speed = 4,
            note = mapOf(
                "tr" to "Avrupa dillerinde iyi (Fransızca, İspanyolca, İtalyanca).",
                "de" to "Gut für europäische Sprachen (Französisch, Spanisch, Italienisch).",
                "en" to "Good for European languages (French, Spanish, Italian).",
            ),
        ),
    )

    fun byFile(name: String) = models.firstOrNull { it.file == name }
}

object DeviceInfo {
    fun totalRamGb(context: Context): Double {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / 1e9
    }

    fun freeStorageBytes(context: Context): Long = StatFs(context.filesDir.path).availableBytes
}

class ModelFiles(private val context: Context) {
    val dir: File get() = File(context.filesDir, "models").apply { mkdirs() }
    fun file(m: InstalledModel) = File(dir, m.fileName)

    class InvalidModel(msg: String) : IOException(msg)

    /**
     * Copies a picked file into private storage. Checks the LiteRT-LM magic header first,
     * so a wrong or truncated download is rejected before 3 GB get copied.
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): InstalledModel = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "model.litertlm"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                size = if (c.isNull(1)) -1 else c.getLong(1)
            }
        }
        name = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".litertlm")) throw InvalidModel("ext")
        if (size > 0 && size + 200_000_000 > DeviceInfo.freeStorageBytes(context)) throw InvalidModel("space")

        val target = File(dir, name)
        val tmp = File(dir, "$name.part")
        try {
            (cr.openInputStream(uri) ?: throw InvalidModel("open")).use { input ->
                tmp.outputStream().buffered(1 shl 20).use { out ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var first = true
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        if (first) {
                            if (n < 8 || String(buf, 0, 8, Charsets.US_ASCII) != "LITERTLM") throw InvalidModel("magic")
                            first = false
                        }
                        out.write(buf, 0, n)
                        copied += n
                        if (size > 0) onProgress((copied.toDouble() / size).toFloat())
                    }
                    if (copied < 50_000_000) throw InvalidModel("small")
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("rename")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
        val cat = ModelCatalog.byFile(name)
        InstalledModel(
            id = cat?.id ?: name.removeSuffix(".litertlm"),
            name = cat?.name ?: name.removeSuffix(".litertlm").replace('_', ' '),
            fileName = name, sizeBytes = target.length(), addedAt = System.currentTimeMillis(),
        )
    }

    fun delete(m: InstalledModel) { file(m).delete() }
}
